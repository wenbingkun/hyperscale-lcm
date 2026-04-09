package main

import "testing"

func TestUsePlaintextGRPC(t *testing.T) {
	t.Setenv("LCM_GRPC_PLAINTEXT", "true")
	if !usePlaintextGRPC() {
		t.Fatalf("expected plaintext gRPC transport to be enabled")
	}
}

func TestUsePlaintextGRPCDefaultsToFalse(t *testing.T) {
	t.Setenv("LCM_GRPC_PLAINTEXT", "")
	if usePlaintextGRPC() {
		t.Fatalf("expected plaintext gRPC transport to be disabled by default")
	}
}
