package executor

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
)

var (
	sshBinary     = "ssh"
	sshpassBinary = "sshpass"
)

// SSHRequest describes the remote SSH execution payload sent by Core.
type SSHRequest struct {
	Host                  string `json:"host"`
	Port                  int    `json:"port,omitempty"`
	User                  string `json:"user"`
	Password              string `json:"password,omitempty"`
	PrivateKey            string `json:"privateKey,omitempty"`
	Command               string `json:"command"`
	KnownHosts            string `json:"knownHosts,omitempty"`
	InsecureIgnoreHostKey bool   `json:"insecureIgnoreHostKey,omitempty"`
}

// RunSSH executes a remote command via the local OpenSSH client using a JSON payload.
func RunSSH(ctx context.Context, rawPayload string) (string, int, error) {
	request, err := parseSSHRequest(rawPayload)
	if err != nil {
		return "", -1, err
	}

	commandName, args, cleanup, err := buildSSHInvocation(request)
	if err != nil {
		return "", -1, err
	}
	defer cleanup()

	cmd := exec.CommandContext(ctx, commandName, args...)
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
	if request.Command == "" {
		return SSHRequest{}, errors.New("ssh command is required")
	}
	if request.Password == "" && request.PrivateKey == "" {
		return SSHRequest{}, errors.New("ssh password or privateKey is required")
	}

	return request, nil
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
