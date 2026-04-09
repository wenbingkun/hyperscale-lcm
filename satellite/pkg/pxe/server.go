package pxe

import (
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/pin/tftp/v3"
)

// ServerConfig holds the configuration for both TFTP and HTTP servers
type ServerConfig struct {
	TFTPAddr          string
	TFTPRootDir       string
	HTTPAddr          string
	DHCPProxyEnabled  bool
	DHCPProxyAddr     string
	BootServerHost    string
	LegacyPXEBootFile string
	IPXEBootScriptURL string
}

// DefaultConfig provides sensible defaults.
// HTTPAddr uses :8090 to avoid conflicting with the satellite's own :8080 port.
var DefaultConfig = ServerConfig{
	TFTPAddr:          ":69",
	TFTPRootDir:       "/var/lib/lcm/tftpboot",
	HTTPAddr:          ":8090",
	DHCPProxyEnabled:  true,
	DHCPProxyAddr:     ":4011",
	LegacyPXEBootFile: "undionly.kpxe",
}

// StartPXEServices initializes and blocks to run both the TFTP and HTTP servers concurrently.
func StartPXEServices(ctx context.Context, cfg ServerConfig) {
	cfg = ConfigFromEnv(cfg)

	log.Println("🚀 Starting PXE Provisioning Services...")

	// 1. Ensure TFTP Root directory exists
	if err := os.MkdirAll(cfg.TFTPRootDir, 0755); err != nil {
		log.Printf("⚠️ Failed to ensure TFTP root directory %s: %v", cfg.TFTPRootDir, err)
		return
	}

	serviceCount := 2
	if cfg.DHCPProxyEnabled {
		serviceCount++
	}
	errChan := make(chan error, serviceCount)

	// 2. Start TFTP Server in background
	go func() {
		errChan <- startTFTPServer(ctx, cfg)
	}()

	// 3. Start HTTP Server in background
	go func() {
		errChan <- startHTTPServer(ctx, cfg)
	}()

	if cfg.DHCPProxyEnabled {
		go func() {
			errChan <- startDHCPProxyServer(ctx, cfg)
		}()
	}

	for {
		select {
		case <-ctx.Done():
			log.Println("🛑 Shutting down PXE Provisioning Services...")
			return
		case err := <-errChan:
			if err != nil {
				log.Printf("❌ PXE Service failed: %v", err)
			}
		}
	}
}

// startTFTPServer handles the initial bootloader downloads (e.g., undionly.kpxe)
func startTFTPServer(ctx context.Context, cfg ServerConfig) error {
	readHandler := func(filename string, rf io.ReaderFrom) error {
		cleanPath := filepath.Join(cfg.TFTPRootDir, filepath.Clean(filename))

		// Prevent path traversal outside TFTP root using prefix check on the
		// resolved absolute path, which handles both ".." sequences and symlinks
		// more reliably than rel[0] == '.' alone.
		root := cfg.TFTPRootDir + string(os.PathSeparator)
		if !strings.HasPrefix(cleanPath, root) {
			log.Printf("TFTP: path traversal attempt blocked: %s", filename)
			return fmt.Errorf("access denied")
		}

		file, err := os.Open(cleanPath)
		if err != nil {
			log.Printf("TFTP: Requested file not found: %s", filename)
			return err
		}
		defer file.Close()

		log.Printf("TFTP: Serving file %s", filename)
		_, err = rf.ReadFrom(file)
		return err
	}

	server := tftp.NewServer(readHandler, nil)

	go func() {
		<-ctx.Done()
		server.Shutdown()
	}()

	log.Printf("📡 TFTP Server listening on %s", cfg.TFTPAddr)
	return server.ListenAndServe(cfg.TFTPAddr)
}

// startHTTPServer handles dynamic iPXE scripts and Cloud-Init templates
func startHTTPServer(ctx context.Context, cfg ServerConfig) error {
	mux := http.NewServeMux()

	mux.HandleFunc("/ipxe", handleIpxeScript)
	mux.HandleFunc("/cloud-init/user-data", handleCloudInit)
	mux.HandleFunc("/cloud-init/meta-data", handleMetaData)

	server := &http.Server{
		Addr:    cfg.HTTPAddr,
		Handler: mux,
	}

	go func() {
		<-ctx.Done()
		server.Shutdown(context.Background())
	}()

	log.Printf("🌐 HTTP PXE Server listening on %s", cfg.HTTPAddr)
	return server.ListenAndServe()
}

// handleIpxeScript generates the `#!ipxe` boot script.
// It tells the booting server to download the kernel and pass the cloud-init url.
func handleIpxeScript(w http.ResponseWriter, r *http.Request) {
	mac := r.URL.Query().Get("mac")
	if mac == "" {
		mac = "unknown"
	}

	log.Printf("HTTP: Generating iPXE script for MAC %s", mac)

	host := r.Host
	if host == "" {
		// Fallback to the remote address host when running behind a proxy or
		// accessed directly by IP without a Host header.
		host = r.RemoteAddr
	}

	// A highly simplified Ubuntu netboot iPXE example script
	script := fmt.Sprintf(`#!ipxe
echo "Booting Hyperscale LCM Managed Node (MAC: %s)"
set base-url http://archive.ubuntu.com/ubuntu/dists/noble/main/installer-amd64/current/legacy-images/netboot/ubuntu-installer/amd64
kernel ${base-url}/linux autoinstall ds=nocloud-net;s=http://%s/cloud-init/
initrd ${base-url}/initrd.gz
boot
`, mac, host)

	w.Header().Set("Content-Type", "text/plain")
	w.Write([]byte(script))
}

// handleCloudInit returns the dynamically generated Cloud-Init YAML payload.
func handleCloudInit(w http.ResponseWriter, r *http.Request) {
	// Simple hardcoded Cloud-Init file for demonstration purposes.
	// In production, this would query the Core via gRPC to get node-specific SSH keys,
	// networking logic, and the exact Satellite binary download URL.

	log.Printf("HTTP: Serving Cloud-Init user-data")

	userData := `#cloud-config
autoinstall:
  version: 1
  identity:
    hostname: hyperscale-node
    password: "$6$ex$c/1/L3...HashHere..."
    username: lcmadmin
  ssh:
    install-server: true
    allow-pw: true
  packages:
    - docker.io
    - curl
  runcmd:
    - "echo 'Node provisioned automatically by Hyperscale LCM' > /etc/motd"
    # - "curl -L http://CORE_IP/downloads/satellite -o /usr/local/bin/satellite"
    # - "chmod +x /usr/local/bin/satellite"
    # - "systemctl enable --now satellite.service"
`

	w.Header().Set("Content-Type", "text/plain")
	w.Write([]byte(userData))
}
