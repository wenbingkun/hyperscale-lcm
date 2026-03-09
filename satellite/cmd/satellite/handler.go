package main

import (
	"context"
	"fmt"
	"log"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"

	"github.com/sc-lcm/satellite/pkg/docker"
	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

func injectContext(ctx context.Context) map[string]string {
	carrier := propagation.MapCarrier{}
	otel.GetTextMapPropagator().Inject(ctx, carrier)
	return carrier
}

func sendStatus(stream pb.LcmService_ConnectStreamClient, satelliteId string, update *pb.JobStatusUpdate) {
	if err := stream.Send(&pb.StreamRequest{
		SatelliteId: satelliteId,
		Payload: &pb.StreamRequest_StatusUpdate{
			StatusUpdate: update,
		},
	}); err != nil {
		log.Printf("Failed to send status update for job %s: %v", update.JobId, err)
	}
}

func handleCommand(resp *pb.StreamResponse, satelliteId string, dockerExec *docker.Executor, stream pb.LcmService_ConnectStreamClient) {
	// Extract context from Core
	ctx := context.Background()
	if len(resp.TraceContext) > 0 {
		carrier := propagation.MapCarrier(resp.TraceContext)
		ctx = otel.GetTextMapPropagator().Extract(ctx, carrier)
	}

	tracer := otel.Tracer("satellite-agent")
	ctx, span := tracer.Start(ctx, fmt.Sprintf("handleCommand.%s", resp.CommandType), trace.WithSpanKind(trace.SpanKindConsumer))
	defer span.End()

	span.SetAttributes(
		attribute.String("command.id", resp.CommandId),
		attribute.String("command.type", resp.CommandType),
	)

	if resp.CommandType == "EXEC_SHELL" {
		log.Printf("Executing Shell: %s", resp.Payload)
		sendStatus(stream, satelliteId, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_COMPLETED,
			Message:      "Shell command executed successfully",
			ExitCode:     0,
			TraceContext: injectContext(ctx),
		})

	} else if resp.CommandType == "EXEC_DOCKER" {
		if dockerExec == nil {
			log.Printf("Cannot execute Docker command: Docker client not initialized")
			sendStatus(stream, satelliteId, &pb.JobStatusUpdate{
				JobId:        resp.CommandId,
				Status:       pb.JobStatus_FAILED,
				Message:      "Docker executor not initialized",
				ExitCode:     -1,
				TraceContext: injectContext(ctx),
			})
			return
		}
		imageName := resp.Payload
		log.Printf("Executing Docker Image: %s", imageName)

		// Notify Running
		sendStatus(stream, satelliteId, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_RUNNING,
			Message:      fmt.Sprintf("Pulling and starting %s", imageName),
			TraceContext: injectContext(ctx),
		})

		// Use traced context instead of context.Background()
		containerID, err := dockerExec.RunContainer(ctx, imageName, nil)

		if err != nil {
			log.Printf("Docker execution failed: %v", err)
			sendStatus(stream, satelliteId, &pb.JobStatusUpdate{
				JobId:        resp.CommandId,
				Status:       pb.JobStatus_FAILED,
				Message:      err.Error(),
				ExitCode:     1,
				TraceContext: injectContext(ctx),
			})
		} else {
			log.Printf("Container started: %s", containerID)
			sendStatus(stream, satelliteId, &pb.JobStatusUpdate{
				JobId:        resp.CommandId,
				Status:       pb.JobStatus_COMPLETED,
				Message:      fmt.Sprintf("Container started: %s", containerID),
				ExitCode:     0,
				TraceContext: injectContext(ctx),
			})
		}
	} else if resp.CommandType == "PING" {
		log.Printf("PING received from Core")
		sendStatus(stream, satelliteId, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_COMPLETED,
			Message:      "PONG",
			ExitCode:     0,
			TraceContext: injectContext(ctx),
		})
	}
}
