package main

import (
	"bytes"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"

	"golang.org/x/crypto/ssh"
)

func main() {
	listenAddr := flag.String("listen", "127.0.0.1:2222", "listen address")
	hostKeyPath := flag.String("host-key", "", "path to the SSH host private key")
	authorizedKeyPath := flag.String("authorized-key", "", "path to the allowed client public key")
	username := flag.String("user", "demo", "accepted SSH username")
	outputPrefix := flag.String("output-prefix", "mock-ssh", "stdout prefix for exec requests")
	flag.Parse()

	if *hostKeyPath == "" || *authorizedKeyPath == "" {
		log.Fatal("--host-key and --authorized-key are required")
	}

	hostKeyBytes, err := os.ReadFile(*hostKeyPath)
	if err != nil {
		log.Fatalf("failed to read host key: %v", err)
	}
	hostSigner, err := ssh.ParsePrivateKey(hostKeyBytes)
	if err != nil {
		log.Fatalf("failed to parse host key: %v", err)
	}

	authorizedKeyBytes, err := os.ReadFile(*authorizedKeyPath)
	if err != nil {
		log.Fatalf("failed to read authorized key: %v", err)
	}
	authorizedKey, _, _, _, err := ssh.ParseAuthorizedKey(authorizedKeyBytes)
	if err != nil {
		log.Fatalf("failed to parse authorized key: %v", err)
	}

	config := &ssh.ServerConfig{
		PublicKeyCallback: func(conn ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			if conn.User() != *username {
				return nil, fmt.Errorf("unexpected username %q", conn.User())
			}
			if !bytes.Equal(key.Marshal(), authorizedKey.Marshal()) {
				return nil, fmt.Errorf("unauthorized key for %q", conn.User())
			}
			return nil, nil
		},
	}
	config.AddHostKey(hostSigner)

	listener, err := net.Listen("tcp", *listenAddr)
	if err != nil {
		log.Fatalf("failed to listen on %s: %v", *listenAddr, err)
	}
	defer listener.Close()

	log.Printf("mock SSH server listening on %s for user %s", *listenAddr, *username)
	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Fatalf("accept failed: %v", err)
		}
		go handleConn(conn, config, *outputPrefix)
	}
}

func handleConn(conn net.Conn, config *ssh.ServerConfig, outputPrefix string) {
	defer conn.Close()

	serverConn, chans, reqs, err := ssh.NewServerConn(conn, config)
	if err != nil {
		log.Printf("ssh handshake failed: %v", err)
		return
	}
	defer serverConn.Close()
	go ssh.DiscardRequests(reqs)

	for newChannel := range chans {
		if newChannel.ChannelType() != "session" {
			_ = newChannel.Reject(ssh.UnknownChannelType, "only session channels are supported")
			continue
		}

		channel, requests, err := newChannel.Accept()
		if err != nil {
			log.Printf("failed to accept channel: %v", err)
			continue
		}

		go handleSession(channel, requests, outputPrefix)
	}
}

func handleSession(channel ssh.Channel, requests <-chan *ssh.Request, outputPrefix string) {
	defer channel.Close()

	for req := range requests {
		switch req.Type {
		case "exec":
			command := parseExecCommand(req.Payload)
			_ = req.Reply(true, nil)
			if _, err := io.WriteString(channel, fmt.Sprintf("%s command=%s\n", outputPrefix, command)); err != nil {
				log.Printf("failed to write exec output: %v", err)
			}
			if _, err := channel.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{Status: 0})); err != nil {
				log.Printf("failed to send exit status: %v", err)
			}
			return
		case "shell", "pty-req", "env":
			_ = req.Reply(true, nil)
			if req.Type == "shell" {
				if _, err := io.WriteString(channel, outputPrefix+"\n"); err != nil {
					log.Printf("failed to write shell output: %v", err)
				}
				if _, err := channel.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{Status: 0})); err != nil {
					log.Printf("failed to send exit status: %v", err)
				}
				return
			}
		default:
			_ = req.Reply(false, nil)
		}
	}
}

func parseExecCommand(payload []byte) string {
	if len(payload) < 4 {
		return ""
	}
	return string(payload[4:])
}
