package executor

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

var (
	sshBinary     = "ssh"
	sshpassBinary = "sshpass"
)

const (
	envSSHKeysDir     = "LCM_SSH_KEYS_DIR"
	envSSHKnownHosts  = "LCM_SSH_KNOWN_HOSTS"
	envSSHTimeout     = "LCM_SSH_TIMEOUT"
	envSSHAllowInline = "LCM_SSH_ALLOW_INLINE"

	defaultSSHKeysDir    = "/app/ssh/keys"
	defaultSSHKnownHosts = "/app/ssh/known_hosts"
	defaultSSHTimeout    = 60 * time.Second
	maxSSHOutputBytes    = 64 * 1024
)

// sshTasks is the fixed catalog of read-only tasks. Commands live on the Satellite so that
// neither Core nor the API caller can inject an arbitrary remote command through a task job.
var sshTasks = map[string]string{
	"SYSTEM_INFO": "uname -a; uptime; df -h /; free -m",
}

// SSHRequest describes the remote SSH execution payload sent by Core.
type SSHRequest struct {
	Host                  string `json:"host"`
	Port                  int    `json:"port,omitempty"`
	User                  string `json:"user"`
	Password              string `json:"password,omitempty"`
	PrivateKey            string `json:"privateKey,omitempty"`
	Command               string `json:"command,omitempty"`
	KeyRef                string `json:"keyRef,omitempty"`
	Task                  string `json:"task,omitempty"`
	KnownHosts            string `json:"knownHosts,omitempty"`
	InsecureIgnoreHostKey bool   `json:"insecureIgnoreHostKey,omitempty"`
}

// RunSSH executes a remote command via the local OpenSSH client using a JSON payload.
//
// A payload with a "task" runs one of the fixed read-only tasks with a key and known_hosts
// file that live on the Satellite. A payload with an inline "command" and credentials is the
// legacy path and is refused unless LCM_SSH_ALLOW_INLINE=true.
func RunSSH(ctx context.Context, rawPayload string) (string, int, error) {
	request, err := parseSSHRequest(rawPayload)
	if err != nil {
		return "", -1, err
	}

	var commandName string
	var args []string
	var cleanup func()
	if request.Task != "" {
		commandName, args, cleanup, err = buildSSHTaskInvocation(request)
	} else {
		if !strings.EqualFold(os.Getenv(envSSHAllowInline), "true") {
			return "", -1, errors.New("inline SSH payloads are disabled; submit a task job (set " + envSSHAllowInline + "=true for dev/test only)")
		}
		commandName, args, cleanup, err = buildSSHInvocation(request)
	}
	if err != nil {
		return "", -1, err
	}
	defer cleanup()

	timeout := sshTimeout()
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, commandName, args...)
	cmd.Env = append(os.Environ(), "LC_ALL=C")

	outBuf := &limitedBuffer{limit: maxSSHOutputBytes}
	cmd.Stdout = outBuf
	cmd.Stderr = outBuf

	err = cmd.Run()
	output := outBuf.String()
	exitCode := 0

	if err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			// The local ssh client was killed; the remote command may still be running.
			exitCode = -1
			err = fmt.Errorf("ssh timed out after %s; remote command state is unknown: %w", timeout, err)
		} else if exitError, ok := err.(*exec.ExitError); ok {
			ws := exitError.Sys().(syscall.WaitStatus)
			exitCode = ws.ExitStatus()
		} else {
			exitCode = -1
		}
	}

	return output, exitCode, err
}

func sshTimeout() time.Duration {
	if raw := strings.TrimSpace(os.Getenv(envSSHTimeout)); raw != "" {
		if d, err := time.ParseDuration(raw); err == nil && d > 0 {
			return d
		}
	}
	return defaultSSHTimeout
}

// limitedBuffer keeps at most limit bytes and records that the rest was dropped.
type limitedBuffer struct {
	buf       bytes.Buffer
	limit     int
	truncated bool
}

func (b *limitedBuffer) Write(p []byte) (int, error) {
	if remaining := b.limit - b.buf.Len(); remaining > 0 {
		if len(p) > remaining {
			b.buf.Write(p[:remaining])
			b.truncated = true
		} else {
			b.buf.Write(p)
		}
	} else if len(p) > 0 {
		b.truncated = true
	}
	return len(p), nil
}

func (b *limitedBuffer) String() string {
	if b.truncated {
		return b.buf.String() + fmt.Sprintf("\n[output truncated at %d bytes]", b.limit)
	}
	return b.buf.String()
}

func parseSSHRequest(rawPayload string) (SSHRequest, error) {
	var request SSHRequest
	if err := json.Unmarshal([]byte(rawPayload), &request); err != nil {
		return SSHRequest{}, fmt.Errorf("failed to parse SSH payload: %w", err)
	}

	request.Host = strings.TrimSpace(request.Host)
	request.User = strings.TrimSpace(request.User)
	request.Command = strings.TrimSpace(request.Command)
	if request.Port == 0 {
		request.Port = 22
	}

	if request.Host == "" {
		return SSHRequest{}, errors.New("ssh host is required")
	}
	if request.User == "" {
		return SSHRequest{}, errors.New("ssh user is required")
	}
	// A leading '-' would let the value be parsed as an ssh option.
	if strings.HasPrefix(request.Host, "-") || strings.HasPrefix(request.User, "-") ||
		strings.ContainsAny(request.Host, " \t\r\n") || strings.ContainsAny(request.User, " \t\r\n@") {
		return SSHRequest{}, errors.New("ssh host or user contains invalid characters")
	}

	if request.Task != "" {
		if _, ok := sshTasks[request.Task]; !ok {
			return SSHRequest{}, fmt.Errorf("unknown ssh task %q", request.Task)
		}
		if request.KeyRef == "" {
			return SSHRequest{}, errors.New("ssh keyRef is required for task jobs")
		}
		if request.Password != "" || request.PrivateKey != "" || request.Command != "" {
			return SSHRequest{}, errors.New("task jobs must not carry inline credentials or commands")
		}
		return request, nil
	}

	if request.Command == "" {
		return SSHRequest{}, errors.New("ssh command is required")
	}
	if request.Password == "" && request.PrivateKey == "" {
		return SSHRequest{}, errors.New("ssh password or privateKey is required")
	}

	return request, nil
}

// buildSSHTaskInvocation builds a strict-host-key, key-only, non-interactive ssh call for a fixed task.
func buildSSHTaskInvocation(request SSHRequest) (string, []string, func(), error) {
	noop := func() {}

	if request.KeyRef != filepath.Base(request.KeyRef) || request.KeyRef == "." || request.KeyRef == ".." ||
		strings.ContainsRune(request.KeyRef, 0) {
		return "", nil, noop, errors.New("ssh keyRef must be a plain file name")
	}
	keyPath := filepath.Join(envOrDefault(envSSHKeysDir, defaultSSHKeysDir), request.KeyRef)
	if info, err := os.Stat(keyPath); err != nil || !info.Mode().IsRegular() {
		return "", nil, noop, fmt.Errorf("ssh key %q is not available on this satellite", request.KeyRef)
	}

	knownHostsPath := envOrDefault(envSSHKnownHosts, defaultSSHKnownHosts)
	if info, err := os.Stat(knownHostsPath); err != nil || !info.Mode().IsRegular() {
		return "", nil, noop, errors.New("ssh known_hosts file is not available on this satellite")
	}

	args := []string{
		"-p", strconv.Itoa(request.Port),
		"-o", "BatchMode=yes",
		"-o", "StrictHostKeyChecking=yes",
		"-o", "UserKnownHostsFile=" + knownHostsPath,
		"-o", "IdentitiesOnly=yes",
		"-o", "ConnectTimeout=10",
		"-i", keyPath,
		request.User + "@" + request.Host,
		sshTasks[request.Task],
	}
	return sshBinary, args, noop, nil
}

func envOrDefault(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func buildSSHInvocation(request SSHRequest) (string, []string, func(), error) {
	args := []string{"-p", strconv.Itoa(request.Port)}
	cleanupFns := make([]func(), 0, 2)
	cleanup := func() {
		for i := len(cleanupFns) - 1; i >= 0; i-- {
			cleanupFns[i]()
		}
	}

	if request.KnownHosts != "" {
		knownHostsPath, err := writeTempFile("known-hosts-*", request.KnownHosts, 0o600)
		if err != nil {
			return "", nil, cleanup, err
		}
		cleanupFns = append(cleanupFns, func() { _ = os.Remove(knownHostsPath) })
		args = append(args,
			"-o", "StrictHostKeyChecking=yes",
			"-o", "UserKnownHostsFile="+knownHostsPath)
	} else if request.InsecureIgnoreHostKey {
		args = append(args,
			"-o", "StrictHostKeyChecking=no",
			"-o", "UserKnownHostsFile=/dev/null")
	} else {
		return "", nil, cleanup, errors.New("knownHosts or insecureIgnoreHostKey=true is required for SSH")
	}

	if request.PrivateKey != "" {
		keyPath, err := writeTempFile("ssh-key-*", request.PrivateKey, 0o600)
		if err != nil {
			return "", nil, cleanup, err
		}
		cleanupFns = append(cleanupFns, func() { _ = os.Remove(keyPath) })
		args = append(args, "-i", keyPath)
	}

	args = append(args, request.User+"@"+request.Host, request.Command)

	commandName := sshBinary
	if request.Password != "" {
		if _, err := exec.LookPath(sshpassBinary); err != nil {
			return "", nil, cleanup, errors.New("ssh password auth requires sshpass to be installed")
		}
		// Use a temp file (-f) instead of command-line arg (-p) to avoid
		// leaking the password via /proc/*/cmdline.
		passFilePath, err := writeTempFile("sshpass-*", request.Password, 0o600)
		if err != nil {
			return "", nil, cleanup, err
		}
		cleanupFns = append(cleanupFns, func() { _ = os.Remove(passFilePath) })
		commandName = sshpassBinary
		args = append([]string{"-f", passFilePath, sshBinary}, args...)
	}

	return commandName, args, cleanup, nil
}

func writeTempFile(pattern string, content string, mode os.FileMode) (string, error) {
	tmpFile, err := os.CreateTemp("", pattern)
	if err != nil {
		return "", fmt.Errorf("failed to create temp file %s: %w", pattern, err)
	}
	defer tmpFile.Close()

	// Restrict permissions before writing sensitive content to eliminate the
	// race window between file creation and chmod.
	if err := tmpFile.Chmod(mode); err != nil {
		_ = os.Remove(tmpFile.Name())
		return "", fmt.Errorf("failed to set temp file permissions %s: %w", tmpFile.Name(), err)
	}
	if _, err := tmpFile.WriteString(content); err != nil {
		_ = os.Remove(tmpFile.Name())
		return "", fmt.Errorf("failed to write temp file %s: %w", tmpFile.Name(), err)
	}
	return tmpFile.Name(), nil
}
