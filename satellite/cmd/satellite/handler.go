package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"

	"github.com/sc-lcm/satellite/pkg/docker"
	"github.com/sc-lcm/satellite/pkg/executor"
	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

func injectContext(ctx context.Context) map[string]string {
	carrier := propagation.MapCarrier{}
	otel.GetTextMapPropagator().Inject(ctx, carrier)
	return carrier
}

type dockerRunner interface {
	RunContainer(context.Context, string, []string, time.Duration) (string, int, error)
}

// agent carries per-process command state shared across handled commands.
type agent struct {
	satelliteID   string
	dockerExec    dockerRunner
	dockerTimeout time.Duration
	ledger        *commandLedger
	outbox        *outbox
}

func newAgent(satelliteID string, dockerExec *docker.Executor, dockerTimeout time.Duration) *agent {
	a := &agent{satelliteID: satelliteID, dockerTimeout: dockerTimeout,
		ledger: newCommandLedger(defaultLedgerSize), outbox: newOutbox(defaultOutboxSize)}
	if dockerExec != nil {
		a.dockerExec = dockerExec
	}
	return a
}

func (a *agent) send(stream pb.LcmService_ConnectStreamClient, update *pb.JobStatusUpdate) error {
	return stream.Send(&pb.StreamRequest{
		SatelliteId: a.satelliteID,
		Payload: &pb.StreamRequest_StatusUpdate{
			StatusUpdate: update,
		},
	})
}

// report sends a status update. Terminal updates are remembered in the ledger
// and queued for replay when the send fails; RUNNING updates are best effort.
func (a *agent) report(stream pb.LcmService_ConnectStreamClient, update *pb.JobStatusUpdate) {
	terminal := isTerminal(update.Status)
	if terminal {
		a.ledger.finish(update.JobId, update)
	}
	if err := a.send(stream, update); err != nil {
		log.Printf("Failed to send status update for job %s: %v", update.JobId, err)
		if terminal {
			a.outbox.add(update)
		}
	}
}

// replayPending resends queued terminal updates after a (re)connect.
func (a *agent) replayPending(stream pb.LcmService_ConnectStreamClient) {
	for _, u := range a.outbox.takeAll() {
		log.Printf("Replaying terminal status of job %s", u.JobId)
		a.report(stream, u)
	}
}

// dockerSpec accepts a bare image name or {"image":"...","cmd":[...]}.
func dockerSpec(payload string) (string, []string, error) {
	p := strings.TrimSpace(payload)
	if !strings.HasPrefix(p, "{") {
		return p, nil, nil
	}
	var spec struct {
		Image string   `json:"image"`
		Cmd   []string `json:"cmd"`
	}
	if err := json.Unmarshal([]byte(p), &spec); err != nil || spec.Image == "" {
		return "", nil, fmt.Errorf("invalid docker payload: need {\"image\":...}")
	}
	return spec.Image, spec.Cmd, nil
}

func (a *agent) handleCommand(resp *pb.StreamResponse, stream pb.LcmService_ConnectStreamClient) {
	// Extract context from Core
	ctx := context.Background()
	if len(resp.TraceContext) > 0 {
		carrier := propagation.MapCarrier(resp.TraceContext)
		ctx = otel.GetTextMapPropagator().Extract(ctx, carrier)
	}

	switch resp.CommandType {
	case "EXEC_SHELL", "EXEC_ANSIBLE", "EXEC_SSH", "EXEC_DOCKER":
		switch state, terminal := a.ledger.begin(resp.CommandId); state {
		case ledgerRunning:
			log.Printf("Ignoring duplicate command %s: still running", resp.CommandId)
			return
		case ledgerDone:
			log.Printf("Not re-executing duplicate command %s; resending its terminal status", resp.CommandId)
			a.report(stream, terminal)
			return
		}
	}

	tracer := otel.Tracer("satellite-agent")
	ctx, span := tracer.Start(ctx, fmt.Sprintf("handleCommand.%s", resp.CommandType), trace.WithSpanKind(trace.SpanKindConsumer))
	defer span.End()

	span.SetAttributes(
		attribute.String("command.id", resp.CommandId),
		attribute.String("command.type", resp.CommandType),
	)

	if resp.CommandType == "EXEC_SHELL" {
		log.Printf("Executing Shell Script")

		// Notify Running
		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_RUNNING,
			Message:      "Executing Shell script...",
			TraceContext: injectContext(ctx),
		})

		output, exitCode, err := executor.RunShell(ctx, resp.Payload)

		status := pb.JobStatus_COMPLETED
		if err != nil {
			status = pb.JobStatus_FAILED
		}

		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       status,
			Message:      output,
			ExitCode:     int32(exitCode),
			TraceContext: injectContext(ctx),
		})

	} else if resp.CommandType == "EXEC_ANSIBLE" {
		log.Printf("Executing Ansible Playbook")

		// Notify Running
		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_RUNNING,
			Message:      "Executing Ansible Playbook...",
			TraceContext: injectContext(ctx),
		})

		output, exitCode, err := executor.RunAnsiblePlaybook(ctx, resp.Payload)

		status := pb.JobStatus_COMPLETED
		if err != nil {
			status = pb.JobStatus_FAILED
		}

		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       status,
			Message:      output,
			ExitCode:     int32(exitCode),
			TraceContext: injectContext(ctx),
		})
	} else if resp.CommandType == "EXEC_SSH" {
		log.Printf("Executing SSH Command")

		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_RUNNING,
			Message:      "Executing SSH command...",
			TraceContext: injectContext(ctx),
		})

		output, exitCode, err := executor.RunSSH(ctx, resp.Payload)

		status := pb.JobStatus_COMPLETED
		if err != nil {
			status = pb.JobStatus_FAILED
		}

		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       status,
			Message:      output,
			ExitCode:     int32(exitCode),
			TraceContext: injectContext(ctx),
		})
	} else if resp.CommandType == "EXEC_DOCKER" {
		if a.dockerExec == nil {
			log.Printf("Cannot execute Docker command: Docker client not initialized")
			a.report(stream, &pb.JobStatusUpdate{
				JobId:        resp.CommandId,
				Status:       pb.JobStatus_FAILED,
				Message:      "Docker executor not initialized",
				ExitCode:     -1,
				TraceContext: injectContext(ctx),
			})
			return
		}
		imageName, cmd, err := dockerSpec(resp.Payload)
		if err != nil {
			a.report(stream, &pb.JobStatusUpdate{
				JobId:        resp.CommandId,
				Status:       pb.JobStatus_FAILED,
				Message:      err.Error(),
				ExitCode:     -1,
				TraceContext: injectContext(ctx),
			})
			return
		}
		log.Printf("Executing Docker Image: %s", imageName)

		// Notify Running
		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_RUNNING,
			Message:      fmt.Sprintf("Pulling and running %s", imageName),
			TraceContext: injectContext(ctx),
		})

		output, exitCode, err := a.dockerExec.RunContainer(ctx, imageName, cmd, a.dockerTimeout)

		status := pb.JobStatus_COMPLETED
		message := output
		if err != nil {
			log.Printf("Docker execution failed: %v", err)
			status = pb.JobStatus_FAILED
			message = strings.TrimSpace(err.Error() + "\n" + output)
			var cleanup *docker.CleanupError
			if errors.As(err, &cleanup) {
				// No safe final result: retain Core's reservation until its sweeper marks UNKNOWN.
				status = pb.JobStatus_RUNNING
			}
		} else if exitCode != 0 {
			status = pb.JobStatus_FAILED
		}
		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       status,
			Message:      message,
			ExitCode:     int32(exitCode),
			TraceContext: injectContext(ctx),
		})
	} else if resp.CommandType == "PING" {
		log.Printf("PING received from Core")
		a.report(stream, &pb.JobStatusUpdate{
			JobId:        resp.CommandId,
			Status:       pb.JobStatus_COMPLETED,
			Message:      "PONG",
			ExitCode:     0,
			TraceContext: injectContext(ctx),
		})
	}
}
