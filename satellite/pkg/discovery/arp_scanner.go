package discovery

import (
	"context"
	"log"
	"net"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

var macRegex = regexp.MustCompile(`([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}`)

// RunARPScanner periodically scans the local subnet for active hosts
// using the system ARP table. It runs every scanInterval and reports
// new devices to the events channel.
func RunARPScanner(ctx context.Context, iface string, events chan<- Event) {
	const scanInterval = 60 * time.Second

	log.Printf("Starting ARP scanner on interface %s (interval: %s)", iface, scanInterval)

	// Run an initial scan immediately
	scanARP(ctx, iface, events)

	ticker := time.NewTicker(scanInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			log.Println("ARP scanner stopped")
			return
		case <-ticker.C:
			scanARP(ctx, iface, events)
		}
	}
}

// scanARP reads the system ARP table and sends discovery events for each entry.
// It uses `ip neigh show` (Linux) to enumerate the ARP cache, which is populated
// by normal network traffic and doesn't require raw socket privileges.
func scanARP(ctx context.Context, iface string, events chan<- Event) {
	// First, trigger ARP population by pinging the broadcast address
	subnet := getSubnetForInterface(iface)
	if subnet != "" {
		pingSubnet(ctx, subnet)
	}

	// Read ARP table
	cmd := exec.CommandContext(ctx, "ip", "neigh", "show", "dev", iface)
	out, err := cmd.Output()
	if err != nil {
		log.Printf("ARP scan failed: %v", err)
		return
	}

	count := 0
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.Contains(line, "FAILED") {
			continue
		}

		parts := strings.Fields(line)
		if len(parts) < 3 {
			continue
		}

		ip := net.ParseIP(parts[0])
		if ip == nil || ip.To4() == nil {
			continue
		}

		macStr := macRegex.FindString(line)
		if macStr == "" {
			continue
		}

		mac, err := net.ParseMAC(macStr)
		if err != nil {
			continue
		}

		select {
		case events <- Event{IP: ip, MAC: mac, Method: "ARP_SCAN"}:
			count++
		default:
		}
	}

	if count > 0 {
		log.Printf("ARP scan found %d hosts on %s", count, iface)
	}
}

// getSubnetForInterface returns the CIDR subnet string for the given interface.
func getSubnetForInterface(iface string) string {
	i, err := net.InterfaceByName(iface)
	if err != nil {
		return ""
	}
	addrs, err := i.Addrs()
	if err != nil {
		return ""
	}
	for _, addr := range addrs {
		if ipnet, ok := addr.(*net.IPNet); ok && ipnet.IP.To4() != nil {
			return ipnet.String()
		}
	}
	return ""
}

// pingSubnet sends a single broadcast ping to populate the ARP cache.
// This is best-effort; if ping fails, we still read whatever is in the ARP table.
func pingSubnet(ctx context.Context, subnet string) {
	// Parse the subnet to get the broadcast address
	_, ipnet, err := net.ParseCIDR(subnet)
	if err != nil {
		return
	}

	// Calculate broadcast address
	broadcast := make(net.IP, len(ipnet.IP))
	for i := range ipnet.IP {
		broadcast[i] = ipnet.IP[i] | ^ipnet.Mask[i]
	}

	// Best-effort ping, timeout 2s
	pingCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	exec.CommandContext(pingCtx, "ping", "-c", "1", "-b", "-W", "1", broadcast.String()).Run()
}
