// Package stream maintains the bidirectional command stream between the
// satellite and Core, including the reconnection loop previously inlined in
// cmd/satellite/main.go.
package stream

import (
	"context"
	"log"
	"time"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

// Handler processes a single command received on the stream. The stream is
// passed through so handlers can send command results back to Core.
type Handler func(resp *pb.StreamResponse, stream pb.LcmService_ConnectStreamClient)

// Config controls the reconnection loop.
type Config struct {
	SatelliteID string
	// RetryInterval is the wait between reconnect attempts; defaults to 5s.
	RetryInterval time.Duration
	// Logf defaults to log.Printf.
	Logf func(format string, v ...any)
}

// RunCommandStreamLoop connects to the Core command stream, sends the init
// handshake and dispatches received commands to handle. Any connect,
// handshake or receive failure waits RetryInterval and reconnects. Returns
// once ctx is cancelled.
func RunCommandStreamLoop(ctx context.Context, client pb.LcmServiceClient, cfg Config, handle Handler) {
	retry := cfg.RetryInterval
	if retry <= 0 {
		retry = 5 * time.Second
	}
	logf := cfg.Logf
	if logf == nil {
		logf = log.Printf
	}

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		logf("🔌 Connecting to Command Stream...")
		stream, err := client.ConnectStream(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			logf("❌ Failed to connect stream: %v. Retrying in %s...", err, retry)
			if !sleepCtx(ctx, retry) {
				return
			}
			continue
		}

		// Send initial handshake
		if err := stream.Send(&pb.StreamRequest{
			SatelliteId: cfg.SatelliteID,
			Payload:     &pb.StreamRequest_Init{Init: true},
		}); err != nil {
			logf("❌ Failed to send handshake: %v. Retrying...", err)
			stream.CloseSend()
			if !sleepCtx(ctx, retry) {
				return
			}
			continue
		}

		logf("✅ Command Stream Connected")

		// Listen for commands
		for {
			resp, err := stream.Recv()
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				logf("❌ Stream disconnected: %v", err)
				break // reconnect
			}

			logf("⚡ Received Command [%s]: %s %s", resp.CommandId, resp.CommandType, resp.Payload)
			handle(resp, stream)
		}

		// Stream disconnected, wait before retry
		if !sleepCtx(ctx, retry) {
			return
		}
	}
}

// sleepCtx waits d or until ctx is cancelled; returns false on cancellation.
func sleepCtx(ctx context.Context, d time.Duration) bool {
	select {
	case <-ctx.Done():
		return false
	case <-time.After(d):
		return true
	}
}
