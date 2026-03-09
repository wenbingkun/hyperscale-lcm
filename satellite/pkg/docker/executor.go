package docker

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/client"
)

type Executor struct {
	cli *client.Client
}

func NewExecutor() (*Executor, error) {
	cli, err := client.NewClientWithOpts(client.FromEnv, client.WithAPIVersionNegotiation())
	if err != nil {
		return nil, err
	}
	return &Executor{cli: cli}, nil
}

func (e *Executor) RunContainer(ctx context.Context, imageName string, cmd []string) (string, error) {
	// 1. Pull Image
	log.Printf("🐳 Pulling image: %s", imageName)
	reader, err := e.cli.ImagePull(ctx, imageName, types.ImagePullOptions{})
	if err != nil {
		return "", err
	}
	// Draining the reader is important for the pull to complete/progress
	if _, err := io.Copy(os.Stdout, reader); err != nil {
		reader.Close()
		return "", fmt.Errorf("image pull stream failed: %w", err)
	}
	reader.Close()

	// 2. Create Container
	log.Printf("📦 Creating container...")
	resp, err := e.cli.ContainerCreate(ctx,
		&container.Config{
			Image: imageName,
			Cmd:   cmd,
		},
		nil, nil, nil, "")
	if err != nil {
		return "", err
	}

	// 3. Start Container
	log.Printf("🚀 Starting container: %s", resp.ID)
	if err := e.cli.ContainerStart(ctx, resp.ID, types.ContainerStartOptions{}); err != nil {
		return "", err
	}

	return resp.ID, nil
}
