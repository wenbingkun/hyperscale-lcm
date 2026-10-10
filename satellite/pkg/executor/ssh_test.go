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
	t.Setenv("LCM_SSH_ALLOW_INLINE", "true")
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
	t.Setenv("LCM_SSH_ALLOW_INLINE", "true")
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
	t.Setenv("LCM_SSH_ALLOW_INLINE", "true")
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
	t.Setenv("LCM_SSH_ALLOW_INLINE", "true")
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

func setupSSHTaskEnv(t *testing.T) (keysDir, knownHosts string) {
	t.Helper()
	dir := t.TempDir()
	keysDir = filepath.Join(dir, "keys")
	if err := os.MkdirAll(keysDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(keysDir, "lab_ed25519"), []byte("KEY"), 0o600); err != nil {
		t.Fatal(err)
	}
	knownHosts = filepath.Join(dir, "known_hosts")
	if err := os.WriteFile(knownHosts, []byte("host ssh-ed25519 AAAA"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("LCM_SSH_KEYS_DIR", keysDir)
	t.Setenv("LCM_SSH_KNOWN_HOSTS", knownHosts)
	return keysDir, knownHosts
}

func taskPayload(t *testing.T, mutate func(*SSHRequest)) string {
	t.Helper()
	request := SSHRequest{Host: "10.0.0.5", User: "ops", KeyRef: "lab_ed25519", Task: "SYSTEM_INFO"}
	if mutate != nil {
		mutate(&request)
	}
	return marshalSSHRequest(t, request)
}

func TestRunSSHTaskUsesFixedCommandAndStrictHostKey(t *testing.T) {
	setupSSHTaskEnv(t)
	restore := stubSSHBinary(t, `#!/bin/bash
printf 'ARGS:%s\n' "$*"
`)
	defer restore()

	output, exitCode, err := RunSSH(context.Background(), taskPayload(t, nil))
	if err != nil || exitCode != 0 {
		t.Fatalf("expected success, got exit=%d err=%v", exitCode, err)
	}
	for _, want := range []string{"StrictHostKeyChecking=yes", "BatchMode=yes", "ops@10.0.0.5", "uname -a; uptime; df -h /; free -m"} {
		if !strings.Contains(output, want) {
			t.Fatalf("expected %q in %q", want, output)
		}
	}
	if strings.Contains(output, "StrictHostKeyChecking=no") {
		t.Fatalf("task path must never disable host key checking: %q", output)
	}
}

func TestRunSSHTaskRejectsInvalidRequests(t *testing.T) {
	setupSSHTaskEnv(t)
	restore := stubSSHBinary(t, "#!/bin/bash\nexit 0\n")
	defer restore()

	cases := map[string]func(*SSHRequest){
		"unknown task":      func(r *SSHRequest) { r.Task = "REBOOT" },
		"path traversal":    func(r *SSHRequest) { r.KeyRef = "../keys/lab_ed25519" },
		"absolute keyRef":   func(r *SSHRequest) { r.KeyRef = "/etc/passwd" },
		"missing key file":  func(r *SSHRequest) { r.KeyRef = "nope" },
		"option-like host":  func(r *SSHRequest) { r.Host = "-oProxyCommand=x" },
		"option-like user":  func(r *SSHRequest) { r.User = "-x" },
		"inline credential": func(r *SSHRequest) { r.PrivateKey = "K" },
		"inline command":    func(r *SSHRequest) { r.Command = "id" },
		"no keyRef":         func(r *SSHRequest) { r.KeyRef = "" },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			if _, exitCode, err := RunSSH(context.Background(), taskPayload(t, mutate)); err == nil || exitCode != -1 {
				t.Fatalf("expected rejection, got exit=%d err=%v", exitCode, err)
			}
		})
	}
}

func TestRunSSHTaskRequiresKnownHostsFile(t *testing.T) {
	_, knownHosts := setupSSHTaskEnv(t)
	if err := os.Remove(knownHosts); err != nil {
		t.Fatal(err)
	}
	restore := stubSSHBinary(t, "#!/bin/bash\nexit 0\n")
	defer restore()

	if _, _, err := RunSSH(context.Background(), taskPayload(t, nil)); err == nil || !strings.Contains(err.Error(), "known_hosts") {
		t.Fatalf("expected known_hosts error, got %v", err)
	}
}

func TestRunSSHInlinePayloadDisabledByDefault(t *testing.T) {
	restore := stubSSHBinary(t, "#!/bin/bash\nexit 0\n")
	defer restore()

	payload := marshalSSHRequest(t, SSHRequest{Host: "h", User: "u", PrivateKey: "K", Command: "id", InsecureIgnoreHostKey: true})
	if _, exitCode, err := RunSSH(context.Background(), payload); err == nil || exitCode != -1 || !strings.Contains(err.Error(), "disabled") {
		t.Fatalf("expected inline payload to be refused, got exit=%d err=%v", exitCode, err)
	}
}

func TestRunSSHTimeoutReportsUnknownRemoteState(t *testing.T) {
	setupSSHTaskEnv(t)
	t.Setenv("LCM_SSH_TIMEOUT", "300ms")
	restore := stubSSHBinary(t, "#!/bin/bash\nexec sleep 5\n")
	defer restore()

	start := time.Now()
	_, exitCode, err := RunSSH(context.Background(), taskPayload(t, nil))
	if err == nil || exitCode != -1 || !strings.Contains(err.Error(), "state is unknown") {
		t.Fatalf("expected timeout with unknown state, got exit=%d err=%v", exitCode, err)
	}
	if time.Since(start) > 3*time.Second {
		t.Fatalf("timeout was not enforced promptly")
	}
}

func TestRunSSHTruncatesLargeOutput(t *testing.T) {
	setupSSHTaskEnv(t)
	restore := stubSSHBinary(t, "#!/bin/bash\nhead -c 200000 /dev/zero | tr '\\0' 'x'\n")
	defer restore()

	output, exitCode, err := RunSSH(context.Background(), taskPayload(t, nil))
	if err != nil || exitCode != 0 {
		t.Fatalf("expected success, got exit=%d err=%v", exitCode, err)
	}
	if len(output) > maxSSHOutputBytes+100 || !strings.Contains(output, "[output truncated at 65536 bytes]") {
		t.Fatalf("expected truncated output, got %d bytes", len(output))
	}
}
