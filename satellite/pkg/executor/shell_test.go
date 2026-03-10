package executor

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestRunShellSuccess(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	script := `echo "hello world"`
	output, exitCode, err := RunShell(ctx, script)

	if err != nil {
		t.Fatalf("Expected no error, got: %v", err)
	}

	if exitCode != 0 {
		t.Errorf("Expected exit code 0, got %d", exitCode)
	}

	if strings.TrimSpace(output) != "hello world" {
		t.Errorf("Expected 'hello world', got '%s'", output)
	}
}

func TestRunShellFailure(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	script := `exit 42`
	output, exitCode, err := RunShell(ctx, script)

	if err == nil {
		t.Fatal("Expected an error (ExitError), got nil")
	}

	if exitCode != 42 {
		t.Errorf("Expected exit code 42, got %d", exitCode)
	}

	if len(output) > 0 {
		t.Errorf("Expected no output, got '%s'", output)
	}
}

func TestRunShellTimeout(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	script := `sleep 2 && echo "done"`
	_, exitCode, err := RunShell(ctx, script)

	if err == nil {
		t.Fatal("Expected a timeout error, got nil")
	}

	// For context timeouts, bash receives SIGKILL; our parser returns -1
	if exitCode != -1 {
		t.Errorf("Expected exit code -1 on timeout, got %d", exitCode)
	}
}
