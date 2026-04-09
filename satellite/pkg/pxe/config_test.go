package pxe

import "testing"

func TestConfigFromEnvBuildsDefaultIPXEScriptURL(t *testing.T) {
	t.Setenv("LCM_PXE_BOOT_SERVER_HOST", "10.0.0.15")
	t.Setenv("LCM_PXE_HTTP_ADDR", ":8091")
	t.Setenv("LCM_PXE_DHCP_PROXY_ENABLED", "true")
	t.Setenv("LCM_PXE_INSTALL_REPO_URL", "http://mirror.local/rocky/9/BaseOS/x86_64/os")

	cfg := ConfigFromEnv(ServerConfig{})

	if cfg.BootServerHost != "10.0.0.15" {
		t.Fatalf("expected boot server host override, got %q", cfg.BootServerHost)
	}
	if cfg.IPXEBootScriptURL != "http://10.0.0.15:8091/ipxe" {
		t.Fatalf("expected computed iPXE URL, got %q", cfg.IPXEBootScriptURL)
	}
	if cfg.InstallKernelURL != "http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/vmlinuz" {
		t.Fatalf("expected derived kernel URL, got %q", cfg.InstallKernelURL)
	}
	if cfg.InstallInitrdURL != "http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/initrd.img" {
		t.Fatalf("expected derived initrd URL, got %q", cfg.InstallInitrdURL)
	}
	if !cfg.DHCPProxyEnabled {
		t.Fatalf("expected DHCP proxy to be enabled from env")
	}
}
