package main

import (
	"log"

	"github.com/sc-lcm/satellite/pkg/docker"
	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

// handleCommand handles commands received via the gRPC stream.
func handleCommand(cmd *pb.StreamResponse, satelliteId string, dockerExec *docker.Executor, stream pb.LcmService_ConnectStreamClient) {
	log.Printf("🛠️ Handling Command [%s]: %s", cmd.CommandId, cmd.CommandType)

	switch cmd.CommandType {
	case "EXEC_DOCKER":
		if dockerExec == nil {
			log.Printf("❌ Cannot execute Docker command: Docker executor not initialized")
			sendUpdate(stream, satelliteId, cmd.CommandId, "FAILED", "Docker not available", 1)
			return
		}

		// Execute docker run
		go func() {
			log.Printf("🐳 Running Docker Container: %s", cmd.Payload)
			err := dockerExec.RunContainer(cmd.Payload)
			if err != nil {
				log.Printf("❌ Docker execution failed: %v", err)
				sendUpdate(stream, satelliteId, cmd.CommandId, "FAILED", err.Error(), 1)
			} else {
				log.Printf("✅ Docker execution successful")
				sendUpdate(stream, satelliteId, cmd.CommandId, "COMPLETED", "Execution finished", 0)
			}
		}()

	case "PING":
		log.Printf("🏓 PING received from Core")
		sendUpdate(stream, satelliteId, cmd.CommandId, "COMPLETED", "PONG", 0)

	default:
		log.Printf("⚠️ Unknown command type: %s", cmd.CommandType)
		sendUpdate(stream, satelliteId, cmd.CommandId, "FAILED", "Unknown command", 1)
	}
}

// sendUpdate sends a status update back to the Core via the stream.
func sendUpdate(stream pb.LcmService_ConnectStreamClient, satelliteId, commandId, status, message string, exitCode int32) {
	err := stream.Send(&pb.StreamRequest{
		SatelliteId: satelliteId,
		Payload: &pb.StreamRequest_StatusUpdate{
			StatusUpdate: &pb.JobStatusUpdate{
				JobId:    commandId, // Using commandId as JobId for now
				Status:   status,
				Message:  message,
				ExitCode: exitCode,
			},
		},
	})
	if err != nil {
		log.Printf("❌ Failed to send status update: %v", err)
	}
}
