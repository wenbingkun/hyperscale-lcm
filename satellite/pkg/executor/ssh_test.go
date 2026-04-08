package executor

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRunSSHSuccessWithPrivateKey(t *testing.T) {
	restore := stubSSHBinary(t, `#!/bin/bash
printf 'ARGS:%s\n' "$*"
exit 0
`)
	defer restore()

	payload := marshalSSHRequest(t, SSHRequest{
		Host:                  "127.0.0.1",
		Port:                  2222,
		User:                  "tester",
		PrivateKey:            "PRIVATE KEY DATA",
		Command:               "echo hello",
		InsecureIgnoreHostKey: true,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	output, exitCode, err := RunSSH(ctx, payload)
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}
	if exitCode != 0 {
		t.Fatalf("expected exit code 0, got %d", exitCode)
	}
	if !strings.Contains(output, "tester@127.0.0.1") {
		t.Fatalf("expected ssh target in output, got %q", output)
	}
	if !strings.Contains(output, "echo hello") {
		t.Fatalf("expected command in output, got %q", output)
	}
}

func TestRunSSHReturnsExitCodeFromSSHBinary(t *testing.T) {
	restore := stubSSHBinary(t, `#!/bin/bash
echo "boom" >&2
exit 23
`)
	defer restore()

	payload := marshalSSHRequest(t, SSHRequest{
		Host:                  "127.0.0.1",
		User:                  "tester",
		PrivateKey:            "PRIVATE KEY DATA",
		Command:               "hostname",
		InsecureIgnoreHostKey: true,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	output, exitCode, err := RunSSH(ctx, payload)
	if err == nil {
		t.Fatal("expected command failure, got nil")
	}
	if exitCode != 23 {
		t.Fatalf("expected exit code 23, got %d", exitCode)
	}
	if !strings.Contains(output, "boom") {
		t.Fatalf("expected stderr in output, got %q", output)
	}
}

func TestRunSSHRejectsMissingHostKeyPolicy(t *testing.T) {
	payload := marshalSSHRequest(t, SSHRequest{
		Host:       "127.0.0.1",
		User:       "tester",
		PrivateKey: "PRIVATE KEY DATA",
		Command:    "hostname",
	})

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, exitCode, err := RunSSH(ctx, payload)
	if err == nil {
		t.Fatal("expected host key policy validation error, got nil")
	}
	if exitCode != -1 {
		t.Fatalf("expected exit code -1, got %d", exitCode)
	}
}

func TestRunSSHRejectsPasswordWithoutSshpass(t *testing.T) {
	restoreSSH := stubSSHBinary(t, `#!/bin/bash
exit 0
`)
	defer restoreSSH()

	originalSshpassBinary := sshpassBinary
	sshpassBinary = filepath.Join(t.TempDir(), "missing-sshpass")
	defer func() {
		sshpassBinary = originalSshpassBinary
	}()

	payload := marshalSSHRequest(t, SSHRequest{
		Host:                  "127.0.0.1",
		User:                  "tester",
		Password:              "secret",
		Command:               "hostname",
		InsecureIgnoreHostKey: true,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	_, exitCode, err := RunSSH(ctx, payload)
	if err == nil {
		t.Fatal("expected sshpass validation error, got nil")
	}
	if exitCode != -1 {
		t.Fatalf("expected exit code -1, got %d", exitCode)
	}
}

func marshalSSHRequest(t *testing.T, request SSHRequest) string {
	t.Helper()

	payload, err := json.Marshal(request)
	if err != nil {
		t.Fatalf("failed to marshal SSH request: %v", err)
	}
	return string(payload)
}

func stubSSHBinary(t *testing.T, script string) func() {
	t.Helper()

	originalSSHBinary := sshBinary
	scriptPath := filepath.Join(t.TempDir(), "fake-ssh")
	if err := os.WriteFile(scriptPath, []byte(script), 0o755); err != nil {
		t.Fatalf("failed to write fake ssh binary: %v", err)
	}
	sshBinary = scriptPath

	return func() {
		sshBinary = originalSSHBinary
	}
}
