package docker

import (
	"bytes"
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/network"
	"github.com/docker/docker/pkg/stdcopy"
	specs "github.com/opencontainers/image-spec/specs-go/v1"
)

type fakeAPI struct {
	pullErr, createErr, startErr, stopErr error
	exit                                  int64
	hang                                  bool // Wait never returns
	logs                                  string
	calls                                 []string
	removed                               bool
	pullDelay                             time.Duration
	removeErr                             error
}

func (f *fakeAPI) ImagePull(ctx context.Context, _ string, _ types.ImagePullOptions) (io.ReadCloser, error) {
	f.calls = append(f.calls, "pull")
	if f.pullDelay > 0 {
		select {
		case <-time.After(f.pullDelay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return io.NopCloser(strings.NewReader("")), f.pullErr
}
func (f *fakeAPI) ContainerCreate(context.Context, *container.Config, *container.HostConfig, *network.NetworkingConfig, *specs.Platform, string) (container.ContainerCreateCreatedBody, error) {
	f.calls = append(f.calls, "create")
	return container.ContainerCreateCreatedBody{ID: "c1"}, f.createErr
}
func (f *fakeAPI) ContainerStart(context.Context, string, types.ContainerStartOptions) error {
	f.calls = append(f.calls, "start")
	return f.startErr
}
func (f *fakeAPI) ContainerWait(ctx context.Context, _ string, _ container.WaitCondition) (<-chan container.ContainerWaitOKBody, <-chan error) {
	b := make(chan container.ContainerWaitOKBody, 1)
	e := make(chan error, 1)
	if !f.hang {
		b <- container.ContainerWaitOKBody{StatusCode: f.exit}
	}
	return b, e
}
func (f *fakeAPI) ContainerLogs(context.Context, string, types.ContainerLogsOptions) (io.ReadCloser, error) {
	var buf bytes.Buffer
	w := stdcopy.NewStdWriter(&buf, stdcopy.Stdout)
	w.Write([]byte(f.logs))
	return io.NopCloser(&buf), nil
}
func (f *fakeAPI) ContainerStop(context.Context, string, *time.Duration) error {
	f.calls = append(f.calls, "stop")
	return f.stopErr
}
func (f *fakeAPI) ContainerRemove(context.Context, string, types.ContainerRemoveOptions) error {
	f.removed = true
	return f.removeErr
}

func TestRunContainerExitCodes(t *testing.T) {
	for _, code := range []int64{0, 3} {
		f := &fakeAPI{exit: code, logs: "hi\n"}
		out, got, err := (&Executor{cli: f}).RunContainer(context.Background(), "img", nil, time.Second)
		if err != nil || got != int(code) || out != "hi\n" || !f.removed {
			t.Fatalf("code %d: out=%q got=%d err=%v removed=%v", code, out, got, err, f.removed)
		}
	}
}

func TestRunContainerTimeoutStopsAndRemoves(t *testing.T) {
	f := &fakeAPI{hang: true, logs: "partial"}
	out, code, err := (&Executor{cli: f}).RunContainer(context.Background(), "img", nil, 30*time.Millisecond)
	if err == nil || code != -1 || !strings.Contains(err.Error(), "container stopped") || out != "partial" {
		t.Fatalf("out=%q code=%d err=%v", out, code, err)
	}
	if !f.removed || f.calls[len(f.calls)-1] != "stop" {
		t.Fatalf("calls=%v removed=%v", f.calls, f.removed)
	}
}

func TestRunContainerTimeoutStopFailureIsReportedAsUnknown(t *testing.T) {
	f := &fakeAPI{hang: true, stopErr: errors.New("boom")}
	_, _, err := (&Executor{cli: f}).RunContainer(context.Background(), "img", nil, 20*time.Millisecond)
	if err == nil || !strings.Contains(err.Error(), "state unknown") {
		t.Fatalf("err=%v", err)
	}
}

func TestRunContainerSetupFailures(t *testing.T) {
	cases := map[string]*fakeAPI{
		"pull":   {pullErr: errors.New("x")},
		"create": {createErr: errors.New("x")},
		"start":  {startErr: errors.New("x")},
	}
	for name, f := range cases {
		_, code, err := (&Executor{cli: f}).RunContainer(context.Background(), "img", nil, time.Second)
		if err == nil || code != -1 {
			t.Fatalf("%s: code=%d err=%v", name, code, err)
		}
		// A created container must be removed even when start fails.
		if name == "start" && !f.removed {
			t.Fatal("container not removed after start failure")
		}
	}
}

func TestRunContainerOutputKeepsTail(t *testing.T) {
	f := &fakeAPI{logs: strings.Repeat("a", MaxOutputBytes) + "TAIL"}
	out, _, _ := (&Executor{cli: f}).RunContainer(context.Background(), "img", nil, time.Second)
	if !strings.HasSuffix(out, "TAIL") || !strings.Contains(out, "truncated") || len(out) > MaxOutputBytes+100 {
		t.Fatalf("len=%d", len(out))
	}
}

func TestRunContainerTimeoutIncludesImagePull(t *testing.T) {
	f := &fakeAPI{pullDelay: 200 * time.Millisecond}
	started := time.Now()
	_, code, err := (&Executor{cli: f}).RunContainer(context.Background(), "img", nil, 25*time.Millisecond)
	if err == nil || code != -1 || time.Since(started) > 150*time.Millisecond {
		t.Fatalf("timeout excluded pull: code=%d err=%v elapsed=%s", code, err, time.Since(started))
	}
	if len(f.calls) != 1 {
		t.Fatalf("created a container after pull timeout: %v", f.calls)
	}
}

func TestRunContainerCleanupFailureCannotReportSuccess(t *testing.T) {
	f := &fakeAPI{removeErr: errors.New("remove denied")}
	_, _, err := (&Executor{cli: f}).RunContainer(context.Background(), "img", nil, time.Second)
	var cleanup *CleanupError
	if err == nil || !errors.As(err, &cleanup) || !strings.Contains(err.Error(), "remove denied") {
		t.Fatalf("cleanup failure hidden: %v", err)
	}
}
