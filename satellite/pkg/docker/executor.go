package docker

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/network"
	"github.com/docker/docker/client"
	"github.com/docker/docker/pkg/stdcopy"
	specs "github.com/opencontainers/image-spec/specs-go/v1"
)

const (
	// MaxOutputBytes caps the container output kept for the job result (tail is kept).
	MaxOutputBytes = 64 * 1024
	cleanupTimeout = 30 * time.Second
	stopGrace      = 10 * time.Second
)

// api is the subset of the Docker client used here, so tests can fake it.
type api interface {
	ImagePull(ctx context.Context, ref string, options types.ImagePullOptions) (io.ReadCloser, error)
	ContainerCreate(ctx context.Context, config *container.Config, hostConfig *container.HostConfig, networkingConfig *network.NetworkingConfig, platform *specs.Platform, name string) (container.ContainerCreateCreatedBody, error)
	ContainerStart(ctx context.Context, id string, options types.ContainerStartOptions) error
	ContainerWait(ctx context.Context, id string, condition container.WaitCondition) (<-chan container.ContainerWaitOKBody, <-chan error)
	ContainerLogs(ctx context.Context, id string, options types.ContainerLogsOptions) (io.ReadCloser, error)
	ContainerStop(ctx context.Context, id string, timeout *time.Duration) error
	ContainerRemove(ctx context.Context, id string, options types.ContainerRemoveOptions) error
}

// CleanupError leaves container ownership unresolved; callers must retain capacity for manual verification.
type CleanupError struct{ Cause error }

func (e *CleanupError) Error() string { return e.Cause.Error() }
func (e *CleanupError) Unwrap() error { return e.Cause }

type Executor struct {
	cli api
}

func NewExecutor() (*Executor, error) {
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return nil, err
	}
	return &Executor{cli: cli}, nil
}

// RunContainer pulls the image, runs the container to completion and returns
// its (tail-capped) combined output and real exit code. A non-nil error means
// the run did not reach a normal exit (pull/create/start failure, timeout, or
// lost wait); a non-zero exit code with nil error is a normal failed run. The
// container removal is attempted independently; CleanupError means ownership requires verification.
func (e *Executor) RunContainer(ctx context.Context, imageName string, cmd []string, timeout time.Duration) (output string, exitCode int, runErr error) {
	// Bound pull/create/start as well as wait; cleanup uses its own independent deadline.
	if timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, timeout)
		defer cancel()
	}
	log.Printf("🐳 Pulling image: %s", imageName)
	reader, err := e.cli.ImagePull(ctx, imageName, types.ImagePullOptions{})
	if err != nil {
		return "", -1, err
	}
	// Draining the reader is important for the pull to complete/progress
	if _, err := io.Copy(os.Stdout, reader); err != nil {
		reader.Close()
		return "", -1, fmt.Errorf("image pull stream failed: %w", err)
	}
	reader.Close()

	resp, err := e.cli.ContainerCreate(ctx, &container.Config{Image: imageName, Cmd: cmd}, nil, nil, nil, "")
	if err != nil {
		return "", -1, err
	}
	id := resp.ID
	defer func() {
		// ctx may already be done (timeout/shutdown); clean up independently.
		cctx, cancel := context.WithTimeout(context.Background(), cleanupTimeout)
		defer cancel()
		if err := e.cli.ContainerRemove(cctx, id, types.ContainerRemoveOptions{Force: true}); err != nil {
			runErr = &CleanupError{Cause: errors.Join(runErr,
				fmt.Errorf("container cleanup failed; state requires manual verification: %w", err))}
		}
	}()

	log.Printf("🚀 Starting container: %s", id)
	if err := e.cli.ContainerStart(ctx, id, types.ContainerStartOptions{}); err != nil {
		return "", -1, err
	}

	bodyCh, errCh := e.cli.ContainerWait(ctx, id, container.WaitConditionNotRunning)
	select {
	case body := <-bodyCh:
		exitCode = int(body.StatusCode)
		if body.Error != nil {
			runErr = fmt.Errorf("container wait error: %s", body.Error.Message)
		}
	case err := <-errCh:
		runErr = e.interrupted(id, err)
		exitCode = -1
	case <-ctx.Done():
		runErr = e.interrupted(id, ctx.Err())
		exitCode = -1
	}

	return e.collectLogs(id), exitCode, runErr
}

// interrupted stops the container after a timeout/cancel and reports honestly
// whether the stop was confirmed.
func (e *Executor) interrupted(id string, cause error) error {
	cctx, cancel := context.WithTimeout(context.Background(), cleanupTimeout)
	defer cancel()
	grace := stopGrace
	if err := e.cli.ContainerStop(cctx, id, &grace); err != nil {
		return fmt.Errorf("container did not finish (%v); stop failed, container state unknown: %v", cause, err)
	}
	return fmt.Errorf("container did not finish (%v); container stopped", cause)
}

func (e *Executor) collectLogs(id string) string {
	cctx, cancel := context.WithTimeout(context.Background(), cleanupTimeout)
	defer cancel()
	rc, err := e.cli.ContainerLogs(cctx, id, types.ContainerLogsOptions{ShowStdout: true, ShowStderr: true})
	if err != nil {
		return fmt.Sprintf("(failed to read container logs: %v)", err)
	}
	defer rc.Close()
	tail := &tailBuffer{max: MaxOutputBytes}
	if _, err := stdcopy.StdCopy(tail, tail, rc); err != nil {
		tail.Write([]byte(fmt.Sprintf("\n(log stream error: %v)", err)))
	}
	return tail.String()
}

// tailBuffer keeps only the last max bytes written.
type tailBuffer struct {
	max       int
	buf       bytes.Buffer
	truncated bool
}

func (t *tailBuffer) Write(p []byte) (int, error) {
	n := len(p)
	t.buf.Write(p)
	if t.buf.Len() > t.max {
		t.truncated = true
		b := t.buf.Bytes()
		keep := append([]byte(nil), b[len(b)-t.max:]...)
		t.buf.Reset()
		t.buf.Write(keep)
	}
	return n, nil
}

func (t *tailBuffer) String() string {
	if t.truncated {
		return "...(output truncated, showing tail)\n" + t.buf.String()
	}
	return t.buf.String()
}
