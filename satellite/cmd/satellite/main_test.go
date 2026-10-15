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

func TestEnvEnabledDefaultsToDisabled(t *testing.T) {
	t.Setenv("LCM_TEST_FLAG", "")
	if envEnabled("LCM_TEST_FLAG") {
		t.Fatal("empty value must be disabled")
	}
	for _, v := range []string{"true", " TRUE ", "1"} {
		t.Setenv("LCM_TEST_FLAG", v)
		if !envEnabled("LCM_TEST_FLAG") {
			t.Fatalf("%q must enable", v)
		}
	}
	t.Setenv("LCM_TEST_FLAG", "yes-please")
	if envEnabled("LCM_TEST_FLAG") {
		t.Fatal("unparsable value must stay disabled")
	}
}
