package executor

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"syscall"
)

// RunAnsiblePlaybook receives the raw YAML string of an Ansible playbook,
// writes it to a temporary file, invokes `ansible-playbook`, and returns the results.
func RunAnsiblePlaybook(ctx context.Context, playbookYaml string) (string, int, error) {
	// 1. Create temporary file for the playbook
	tmpFile, err := os.CreateTemp("", "playbook-*.yml")
	if err != nil {
		return "", -1, fmt.Errorf("failed to create temp playbook file: %w", err)
	}
	defer os.Remove(tmpFile.Name()) // Clean up after execution

	// 2. Write the playbook content
	if _, err := tmpFile.Write([]byte(playbookYaml)); err != nil {
		return "", -1, fmt.Errorf("failed to write playbook content: %w", err)
	}
	tmpFile.Close() // Ensure it's flushed and closed before ansible reads it

	// 3. Execute ansible-playbook
	// Note: In a production heavily-secured environment, you might want to specify
	// inventories, specify users, or run this within a specific environment.
	cmd := exec.CommandContext(ctx, "ansible-playbook", tmpFile.Name())
	cmd.Env = append(os.Environ(), "LC_ALL=C")

	var outBuf bytes.Buffer
	cmd.Stdout = &outBuf
	cmd.Stderr = &outBuf

	err = cmd.Run()
	output := outBuf.String()

	exitCode := 0
	if err != nil {
		if exitError, ok := err.(*exec.ExitError); ok {
			ws := exitError.Sys().(syscall.WaitStatus)
			exitCode = ws.ExitStatus()
		} else {
			exitCode = -1
		}
	}

	return output, exitCode, err
}
