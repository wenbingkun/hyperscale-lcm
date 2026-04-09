package pxe

import "testing"

func TestConfigFromEnvBuildsDefaultIPXEScriptURL(t *testing.T) {
	t.Setenv("LCM_PXE_BOOT_SERVER_HOST", "10.0.0.15")
	t.Setenv("LCM_PXE_HTTP_ADDR", ":8091")
	t.Setenv("LCM_PXE_DHCP_PROXY_ENABLED", "true")

	cfg := ConfigFromEnv(ServerConfig{})

	if cfg.BootServerHost != "10.0.0.15" {
		t.Fatalf("expected boot server host override, got %q", cfg.BootServerHost)
	}
	if cfg.IPXEBootScriptURL != "http://10.0.0.15:8091/ipxe" {
		t.Fatalf("expected computed iPXE URL, got %q", cfg.IPXEBootScriptURL)
	}
	if !cfg.DHCPProxyEnabled {
		t.Fatalf("expected DHCP proxy to be enabled from env")
	}
}
