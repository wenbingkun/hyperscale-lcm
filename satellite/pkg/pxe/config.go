package pxe

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
)

// ConfigFromEnv applies runtime overrides without forcing main to know PXE internals.
func ConfigFromEnv(base ServerConfig) ServerConfig {
	cfg := base

	cfg.TFTPAddr = stringFromEnv("LCM_PXE_TFTP_ADDR", cfg.TFTPAddr)
	cfg.TFTPRootDir = stringFromEnv("LCM_PXE_TFTP_ROOT", cfg.TFTPRootDir)
	cfg.HTTPAddr = stringFromEnv("LCM_PXE_HTTP_ADDR", cfg.HTTPAddr)
	cfg.ImageDir = stringFromEnv("LCM_PXE_IMAGE_DIR", cfg.ImageDir)
	cfg.KickstartTemplate = stringFromEnv("LCM_PXE_KICKSTART_TEMPLATE", cfg.KickstartTemplate)
	cfg.InstallRepoURL = stringFromEnv("LCM_PXE_INSTALL_REPO_URL", cfg.InstallRepoURL)
	cfg.InstallKernelURL = stringFromEnv("LCM_PXE_BOOT_KERNEL_URL", cfg.InstallKernelURL)
	cfg.InstallInitrdURL = stringFromEnv("LCM_PXE_BOOT_INITRD_URL", cfg.InstallInitrdURL)
	cfg.InstallKernelArgs = stringFromEnv("LCM_PXE_BOOT_KERNEL_ARGS", cfg.InstallKernelArgs)
	cfg.DHCPProxyAddr = stringFromEnv("LCM_PXE_DHCP_PROXY_ADDR", cfg.DHCPProxyAddr)
	cfg.BootServerHost = stringFromEnv("LCM_PXE_BOOT_SERVER_HOST", cfg.BootServerHost)
	cfg.LegacyPXEBootFile = stringFromEnv("LCM_PXE_DHCP_BOOTFILE", cfg.LegacyPXEBootFile)
	cfg.IPXEBootScriptURL = stringFromEnv("LCM_PXE_DHCP_IPXE_SCRIPT_URL", cfg.IPXEBootScriptURL)
	cfg.DHCPProxyEnabled = boolFromEnv("LCM_PXE_DHCP_PROXY_ENABLED", cfg.DHCPProxyEnabled)

	if cfg.BootServerHost == "" {
		cfg.BootServerHost = detectLocalIPv4()
	}
	if cfg.LegacyPXEBootFile == "" {
		cfg.LegacyPXEBootFile = DefaultConfig.LegacyPXEBootFile
	}
	if cfg.ImageDir == "" {
		cfg.ImageDir = DefaultConfig.ImageDir
	}
	if cfg.InstallRepoURL == "" {
		cfg.InstallRepoURL = DefaultConfig.InstallRepoURL
	}
	if cfg.InstallKernelURL == "" {
		cfg.InstallKernelURL = defaultKernelURL(cfg)
	}
	if cfg.InstallInitrdURL == "" {
		cfg.InstallInitrdURL = defaultInitrdURL(cfg)
	}
	if cfg.InstallKernelArgs == "" {
		cfg.InstallKernelArgs = DefaultConfig.InstallKernelArgs
	}
	if cfg.IPXEBootScriptURL == "" {
		cfg.IPXEBootScriptURL = defaultIPXEBootScriptURL(cfg)
	}

	return cfg
}

func stringFromEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		trimmed := strings.TrimSpace(value)
		if trimmed != "" {
			return trimmed
		}
	}
	return fallback
}

func boolFromEnv(key string, fallback bool) bool {
	value, ok := os.LookupEnv(key)
	if !ok {
		return fallback
	}

	parsed, err := strconv.ParseBool(strings.TrimSpace(value))
	if err != nil {
		return fallback
	}
	return parsed
}

func defaultIPXEBootScriptURL(cfg ServerConfig) string {
	hostPort := advertisedHTTPHostPort(cfg)
	return fmt.Sprintf("http://%s/ipxe", hostPort)
}

func defaultKernelURL(cfg ServerConfig) string {
	repoURL := strings.TrimRight(cfg.InstallRepoURL, "/")
	return repoURL + "/images/pxeboot/vmlinuz"
}

func defaultInitrdURL(cfg ServerConfig) string {
	repoURL := strings.TrimRight(cfg.InstallRepoURL, "/")
	return repoURL + "/images/pxeboot/initrd.img"
}

func advertisedHTTPHostPort(cfg ServerConfig) string {
	host := cfg.BootServerHost
	if host == "" {
		host = detectLocalIPv4()
	}

	httpPort := extractPort(cfg.HTTPAddr)
	if httpPort == "" || httpPort == "80" {
		return host
	}

	return net.JoinHostPort(host, httpPort)
}

func extractPort(addr string) string {
	if addr == "" {
		return ""
	}

	if strings.HasPrefix(addr, ":") {
		return strings.TrimPrefix(addr, ":")
	}

	_, port, err := net.SplitHostPort(addr)
	if err == nil {
		return port
	}

	lastColon := strings.LastIndex(addr, ":")
	if lastColon >= 0 && lastColon < len(addr)-1 {
		return addr[lastColon+1:]
	}

	return ""
}

func detectLocalIPv4() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "127.0.0.1"
	}

	for _, addr := range addrs {
		if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ip4 := ipnet.IP.To4(); ip4 != nil {
				return ip4.String()
			}
		}
	}

	return "127.0.0.1"
}
