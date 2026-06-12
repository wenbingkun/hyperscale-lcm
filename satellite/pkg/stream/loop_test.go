package stream

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

const testRetry = 20 * time.Millisecond

// fakeCore implements the LcmService ConnectStream handler with a
// per-connection script so tests can drive disconnect scenarios.
type fakeCore struct {
	pb.UnimplementedLcmServiceServer

	mu          sync.Mutex
	conns       int
	handshakeCh chan string
	script      func(conn int, stream pb.LcmService_ConnectStreamServer) error
}

func (f *fakeCore) ConnectStream(stream pb.LcmService_ConnectStreamServer) error {
	req, err := stream.Recv()
	if err != nil {
		return err
	}
	f.mu.Lock()
	f.conns++
	conn := f.conns
	f.mu.Unlock()
	f.handshakeCh <- req.GetSatelliteId()
	return f.script(conn, stream)
}

// startFakeCore serves fake over bufconn and returns a connected client.
func startFakeCore(t *testing.T, fake *fakeCore) pb.LcmServiceClient {
	t.Helper()
	lis := bufconn.Listen(1024 * 1024)
	server := grpc.NewServer()
	pb.RegisterLcmServiceServer(server, fake)
	go server.Serve(lis)
	t.Cleanup(server.Stop)

	conn, err := grpc.NewClient("passthrough:///bufnet",
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
			return lis.DialContext(ctx)
		}),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		t.Fatalf("failed to create client: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	return pb.NewLcmServiceClient(conn)
}

func waitHandshake(t *testing.T, ch chan string, what string) string {
	t.Helper()
	select {
	case id := <-ch:
		return id
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
		return ""
	}
}

// The loop must reconnect and re-handshake after the server drops the
// stream, and commands received before the drop must reach the handler.
func TestRunCommandStreamLoopReconnectsAfterStreamDrop(t *testing.T) {
	commands := make(chan string, 1)
	fake := &fakeCore{
		handshakeCh: make(chan string, 4),
		script: func(conn int, stream pb.LcmService_ConnectStreamServer) error {
			if conn == 1 {
				if err := stream.Send(&pb.StreamResponse{
					CommandId:   "cmd-1",
					CommandType: "NOOP",
					Payload:     "{}",
				}); err != nil {
					return err
				}
				return nil // drop the stream after one command
			}
			<-stream.Context().Done() // second connection stays open
			return nil
		},
	}
	client := startFakeCore(t, fake)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		RunCommandStreamLoop(ctx, client,
			Config{SatelliteID: "sat-reconnect", RetryInterval: testRetry, Logf: t.Logf},
			func(resp *pb.StreamResponse, _ pb.LcmService_ConnectStreamClient) {
				commands <- resp.CommandId
			})
	}()

	if id := waitHandshake(t, fake.handshakeCh, "first handshake"); id != "sat-reconnect" {
		t.Fatalf("unexpected satellite id in handshake: %q", id)
	}
	select {
	case got := <-commands:
		if got != "cmd-1" {
			t.Fatalf("handler received unexpected command %q", got)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for command to reach handler")
	}
	waitHandshake(t, fake.handshakeCh, "re-handshake after stream drop")

	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("loop did not exit after context cancellation")
	}
}

// When the connection cannot be established at all, the loop must keep
// retrying without crashing and still honour context cancellation.
func TestRunCommandStreamLoopRetriesWhenConnectFails(t *testing.T) {
	conn, err := grpc.NewClient("passthrough:///unreachable",
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
			return nil, errors.New("dial refused")
		}),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		t.Fatalf("failed to create client: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	client := pb.NewLcmServiceClient(conn)

	var failures atomic.Int32
	retried := make(chan struct{})
	logf := func(format string, v ...any) {
		t.Logf(format, v...)
		if strings.HasPrefix(format, "❌ Failed to connect stream") {
			if failures.Add(1) == 2 {
				close(retried)
			}
		}
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		RunCommandStreamLoop(ctx, client,
			Config{SatelliteID: "sat-retry", RetryInterval: testRetry, Logf: logf},
			func(*pb.StreamResponse, pb.LcmService_ConnectStreamClient) {
				t.Error("handler must not be called when connect always fails")
			})
	}()

	select {
	case <-retried:
	case <-time.After(5 * time.Second):
		t.Fatal("loop did not retry after connect failures")
	}

	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("loop did not exit after context cancellation")
	}
}

// With a healthy but idle stream, cancelling the context must terminate the
// loop promptly.
func TestRunCommandStreamLoopExitsOnContextCancel(t *testing.T) {
	fake := &fakeCore{
		handshakeCh: make(chan string, 1),
		script: func(_ int, stream pb.LcmService_ConnectStreamServer) error {
			<-stream.Context().Done()
			return nil
		},
	}
	client := startFakeCore(t, fake)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		RunCommandStreamLoop(ctx, client,
			Config{SatelliteID: "sat-cancel", RetryInterval: testRetry, Logf: t.Logf},
			func(*pb.StreamResponse, pb.LcmService_ConnectStreamClient) {})
	}()

	waitHandshake(t, fake.handshakeCh, "handshake")
	cancel()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("loop did not exit after context cancellation")
	}
}
