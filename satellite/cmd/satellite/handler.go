package main

import (
	"context"
	"fmt"
	"log"

	"github.com/sc-lcm/satellite/pkg/docker"
	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

func handleCommand(resp *pb.StreamResponse, satelliteId string, dockerExec *docker.Executor, stream pb.LcmService_ConnectStreamClient) {
	if resp.CommandType == "EXEC_SHELL" {
		log.Printf("💻 Executing Shell: %s", resp.Payload)
		// Mock success for shell
		stream.Send(&pb.StreamRequest{
			SatelliteId: satelliteId,
			Payload: &pb.StreamRequest_StatusUpdate{
				StatusUpdate: &pb.JobStatusUpdate{
					JobId:    resp.CommandId,
					Status:   pb.JobStatus_COMPLETED,
					Message:  "Shell command executed successfully",
					ExitCode: 0,
				},
			},
		})

	} else if resp.CommandType == "EXEC_DOCKER" {
		if dockerExec == nil {
			log.Printf("❌ Cannot execute Docker command: Docker client not initialized")
			stream.Send(&pb.StreamRequest{
				SatelliteId: satelliteId,
				Payload: &pb.StreamRequest_StatusUpdate{
					StatusUpdate: &pb.JobStatusUpdate{
						JobId:    resp.CommandId,
						Status:   pb.JobStatus_FAILED,
						Message:  "Docker executor not initialized",
						ExitCode: -1,
					},
				},
			})
			return
		}
		// Payload using as image name
		imageName := resp.Payload
		log.Printf("🐳 Executing Docker Image: %s", imageName)

		// Notify Running
		stream.Send(&pb.StreamRequest{
			SatelliteId: satelliteId,
			Payload: &pb.StreamRequest_StatusUpdate{
				StatusUpdate: &pb.JobStatusUpdate{
					JobId:   resp.CommandId,
					Status:  pb.JobStatus_RUNNING,
					Message: fmt.Sprintf("Pulling and starting %s", imageName),
				},
			},
		})

		containerID, err := dockerExec.RunContainer(context.Background(), imageName, nil)

		if err != nil {
			log.Printf("❌ Docker execution failed: %v", err)
			stream.Send(&pb.StreamRequest{
				SatelliteId: satelliteId,
				Payload: &pb.StreamRequest_StatusUpdate{
					StatusUpdate: &pb.JobStatusUpdate{
						JobId:    resp.CommandId,
						Status:   pb.JobStatus_FAILED,
						Message:  err.Error(),
						ExitCode: 1,
					},
				},
			})
		} else {
			log.Printf("✅ Container started: %s", containerID)
			stream.Send(&pb.StreamRequest{
				SatelliteId: satelliteId,
				Payload: &pb.StreamRequest_StatusUpdate{
					StatusUpdate: &pb.JobStatusUpdate{
						JobId:    resp.CommandId,
						Status:   pb.JobStatus_COMPLETED,
						Message:  fmt.Sprintf("Container started: %s", containerID),
						ExitCode: 0,
					},
				},
			})
		}
	} else if resp.CommandType == "PING" {
		log.Printf("🏓 PING received from Core")
		stream.Send(&pb.StreamRequest{
			SatelliteId: satelliteId,
			Payload: &pb.StreamRequest_StatusUpdate{
				StatusUpdate: &pb.JobStatusUpdate{
					JobId:    resp.CommandId,
					Status:   pb.JobStatus_COMPLETED,
					Message:  "PONG",
					ExitCode: 0,
				},
			},
		})
	}
}
