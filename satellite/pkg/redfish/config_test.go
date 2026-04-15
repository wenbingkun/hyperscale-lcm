package redfish

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestConfigDefaultsSessionPreferred(t *testing.T) {
	t.Setenv("LCM_BMC_IP", "bmc.example")
	t.Setenv("LCM_BMC_USER", "")
	t.Setenv("LCM_BMC_PASSWORD", "")
	t.Setenv("LCM_BMC_AUTH_MODE", "")
	t.Setenv("LCM_BMC_SESSION_TTL_SECONDS_MAX", "")

	config, mock := loadConfigFromEnv()
	if mock {
		t.Fatal("expected real config, got mock mode")
	}
	if config.AuthMode != AuthModeSessionPreferred {
		t.Fatalf("expected default auth mode %s, got %s", AuthModeSessionPreferred, config.AuthMode)
	}
	if config.SessionTTLSecondsMax != defaultSessionTTLSecondsMax {
		t.Fatalf("expected default TTL %d, got %d", defaultSessionTTLSecondsMax, config.SessionTTLSecondsMax)
	}
}

func TestConfigInvalidAuthModeFallsBackWithWarning(t *testing.T) {
	t.Setenv("LCM_BMC_IP", "bmc.example")
	t.Setenv("LCM_BMC_AUTH_MODE", "not-a-mode")

	logBuffer, restore := captureStandardLogger(t)
	defer restore()

	config, mock := loadConfigFromEnv()
	if mock {
		t.Fatal("expected real config, got mock mode")
	}
	if config.AuthMode != AuthModeSessionPreferred {
		t.Fatalf("expected fallback auth mode %s, got %s", AuthModeSessionPreferred, config.AuthMode)
	}
	if !strings.Contains(logBuffer.String(), "Unknown Redfish auth mode") {
		t.Fatalf("expected warning log, got %q", logBuffer.String())
	}
}

func TestConfigSessionTTLSecondsMaxCapping(t *testing.T) {
	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      true,
		sessionTimeout:   600,
	})

	t.Setenv("LCM_BMC_IP", server.server.URL)
	t.Setenv("LCM_BMC_USER", server.username)
	t.Setenv("LCM_BMC_PASSWORD", server.password)
	t.Setenv("LCM_BMC_INSECURE", "true")
	t.Setenv("LCM_BMC_AUTH_MODE", string(AuthModeSessionPreferred))
	t.Setenv("LCM_BMC_SESSION_TTL_SECONDS_MAX", "120")

	config, mock := loadConfigFromEnv()
	if mock {
		t.Fatal("expected real config, got mock mode")
	}
	if config.SessionTTLSecondsMax != 120 {
		t.Fatalf("expected TTL cap 120, got %d", config.SessionTTLSecondsMax)
	}

	now := time.Date(2026, 4, 15, 15, 0, 0, 0, time.UTC)
	manager := NewSessionManager()
	manager.now = func() time.Time { return now }
	transport := NewTransport(config, TransportOptions{
		AuthMode:             config.AuthMode,
		SessionTTLSecondsMax: config.SessionTTLSecondsMax,
		Client:               server.server.Client(),
	}, manager)

	if _, err := transport.Do(context.Background(), TransportRequest{
		Method:   "GET",
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	}); err != nil {
		t.Fatalf("Do() error = %v", err)
	}

	snapshot := manager.Snapshot()
	if len(snapshot) != 1 {
		t.Fatalf("expected 1 cached session, got %d", len(snapshot))
	}

	for _, session := range snapshot {
		if !session.ExpiresAt.Equal(now.Add(120 * time.Second)) {
			t.Fatalf("expected session expiry at %s, got %s", now.Add(120*time.Second), session.ExpiresAt)
		}
	}
}
