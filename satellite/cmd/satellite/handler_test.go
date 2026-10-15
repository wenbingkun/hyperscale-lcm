package main

import (
	"context"
	"errors"
	"github.com/sc-lcm/satellite/pkg/docker"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"google.golang.org/grpc"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

type fakeStream struct {
	grpc.ClientStream
	fail bool
	sent []*pb.JobStatusUpdate
}

func (f *fakeStream) Send(r *pb.StreamRequest) error {
	if f.fail {
		return errors.New("stream down")
	}
	f.sent = append(f.sent, r.GetStatusUpdate())
	return nil
}
func (f *fakeStream) Recv() (*pb.StreamResponse, error) { return nil, errors.New("unused") }

func (f *fakeStream) terminals() []*pb.JobStatusUpdate {
	var out []*pb.JobStatusUpdate
	for _, u := range f.sent {
		if isTerminal(u.Status) {
			out = append(out, u)
		}
	}
	return out
}

func shellCmd(id, script string) *pb.StreamResponse {
	return &pb.StreamResponse{CommandId: id, CommandType: "EXEC_SHELL", Payload: script}
}

func TestDuplicateCommandIsNotReExecuted(t *testing.T) {
	marker := filepath.Join(t.TempDir(), "runs")
	a := newAgent("sat", nil, 0)
	s := &fakeStream{}
	cmd := shellCmd("job-1", "echo x >> "+marker)

	a.handleCommand(cmd, s)
	a.handleCommand(cmd, s)

	data, _ := os.ReadFile(marker)
	if n := strings.Count(string(data), "x"); n != 1 {
		t.Fatalf("command executed %d times, want 1", n)
	}
	if got := s.terminals(); len(got) != 2 || got[1].Status != pb.JobStatus_COMPLETED {
		t.Fatalf("want terminal status re-sent for the duplicate, got %v", got)
	}
}

func TestUnsentTerminalIsReplayedAfterReconnect(t *testing.T) {
	a := newAgent("sat", nil, 0)
	down := &fakeStream{fail: true}
	a.handleCommand(shellCmd("job-2", "true"), down)

	up := &fakeStream{}
	a.replayPending(up)
	got := up.terminals()
	if len(got) != 1 || got[0].JobId != "job-2" || got[0].Status != pb.JobStatus_COMPLETED {
		t.Fatalf("replay got %v", got)
	}
	a.replayPending(up)
	if len(up.terminals()) != 1 {
		t.Fatal("replayed update must not be queued again after a successful send")
	}
}

func TestDockerSpec(t *testing.T) {
	if img, cmd, err := dockerSpec("alpine:3.20"); err != nil || img != "alpine:3.20" || cmd != nil {
		t.Fatal(img, cmd, err)
	}
	if img, cmd, err := dockerSpec(`{"image":"alpine:3.20","cmd":["sh","-c","exit 3"]}`); err != nil || img != "alpine:3.20" || len(cmd) != 3 {
		t.Fatal(img, cmd, err)
	}
	if _, _, err := dockerSpec(`{"cmd":["x"]}`); err == nil {
		t.Fatal("missing image must be rejected")
	}
}

func TestDockerUnavailableFails(t *testing.T) {
	a := newAgent("sat", nil, 0)
	s := &fakeStream{}
	a.handleCommand(&pb.StreamResponse{CommandId: "job-3", CommandType: "EXEC_DOCKER", Payload: "alpine"}, s)
	if got := s.terminals(); len(got) != 1 || got[0].Status != pb.JobStatus_FAILED {
		t.Fatalf("got %v", got)
	}
}

type uncertainDocker struct{ calls int }

func (d *uncertainDocker) RunContainer(context.Context, string, []string, time.Duration) (string, int, error) {
	d.calls++
	return "partial", -1, &docker.CleanupError{Cause: errors.New("stop and remove denied")}
}

func TestUnconfirmedDockerCleanupDoesNotReleaseCapacityOrReExecute(t *testing.T) {
	runner := &uncertainDocker{}
	a := newAgent("sat", nil, time.Second)
	a.dockerExec = runner
	stream := &fakeStream{}
	cmd := &pb.StreamResponse{CommandId: "uncertain-cleanup", CommandType: "EXEC_DOCKER", Payload: "alpine"}
	a.handleCommand(cmd, stream)
	a.handleCommand(cmd, stream)
	if len(stream.terminals()) != 0 || runner.calls != 1 {
		t.Fatalf("unsafe final status or re-execution: terminal=%v calls=%d", stream.terminals(), runner.calls)
	}
	last := stream.sent[len(stream.sent)-1]
	if last.Status != pb.JobStatus_RUNNING || !strings.Contains(last.Message, "remove denied") {
		t.Fatalf("uncertain cleanup must retain RUNNING for the Core sweeper: %v", last)
	}
}
