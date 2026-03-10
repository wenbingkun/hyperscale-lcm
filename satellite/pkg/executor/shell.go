package executor

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"syscall"
)

// RunShell executes a shell command/script using `bash -c`.
// It captures combined stdout and stderr, and returns the exit code.
func RunShell(ctx context.Context, script string) (string, int, error) {
	cmd := exec.CommandContext(ctx, "bash", "-c", script)
	cmd.Env = append(os.Environ(), "LC_ALL=C")

	// Capture combined output
	var outBuf bytes.Buffer
	cmd.Stdout = &outBuf
	cmd.Stderr = &outBuf

	err := cmd.Run()
	output := outBuf.String()

	exitCode := 0
	if err != nil {
		if exitError, ok := err.(*exec.ExitError); ok {
			ws := exitError.Sys().(syscall.WaitStatus)
			exitCode = ws.ExitStatus()
		} else {
			// e.g. command not found, context timeout, etc.
			exitCode = -1
		}
	}

	return output, exitCode, err
}
