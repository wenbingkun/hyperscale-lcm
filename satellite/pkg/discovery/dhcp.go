package discovery

import (
	"context"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	pb "github.com/sc-lcm/satellite/pkg/grpc"
)

// DHCP message formats (simplified)
const (
	BootRequest = 1
	BootReply   = 2
	MagicCookie = 0x63825363 // 99.130.83.99
)

type dhcpCacheEntry struct {
	lastSeen time.Time
}

var (
	macCache = make(map[string]dhcpCacheEntry)
	cacheMu  sync.Mutex
)

// cleanCache removes MACs we haven't seen in the last 5 minutes to avoid memory leaks
// and allow re-reporting if a machine drops offline and re-broadcasts later.
func cleanCache() {
	for {
		time.Sleep(5 * time.Minute)
		cacheMu.Lock()
		now := time.Now()
		for mac, entry := range macCache {
			if now.Sub(entry.lastSeen) > 5*time.Minute {
				delete(macCache, mac)
			}
		}
		cacheMu.Unlock()
	}
}

// StartDHCPListener listens for UDP broadcast packets on port 67 (DHCP Server port).
func StartDHCPListener(ctx context.Context, client pb.LcmServiceClient, satelliteID string) {
	log.Println("📡 Starting DHCP Discover Listener on UDP port 67...")

	// Run cache cleaner in background
	go cleanCache()

	addr := net.UDPAddr{
		Port: 67,
		IP:   net.ParseIP("0.0.0.0"),
	}

	conn, err := net.ListenUDP("udp", &addr)
	if err != nil {
		log.Printf("⚠️ Failed to bind to DHCP port 67 (Are you root? Is dnsmasq running?): %v. DHCP Discovery disabled.", err)
		return
	}
	defer conn.Close()

	// Max DHCP packet size is typically around 576-1500 bytes.
	buf := make([]byte, 2048)

	for {
		select {
		case <-ctx.Done():
			log.Println("🛑 Stopping DHCP Listener...")
			return
		default:
			// Non-blocking read would be better, but ListenUDP blocks.
			// Setting a read deadline allows us to periodically check ctx.Done().
			err := conn.SetReadDeadline(time.Now().Add(3 * time.Second))
			if err != nil {
				continue
			}

			n, remoteAddr, err := conn.ReadFromUDP(buf)
			if err != nil {
				if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
					continue
				}
				log.Printf("⚠️ Error reading from UDP posrt 67: %v", err)
				continue
			}

			mac, isDiscover := parseDHCPDiscover(buf[:n])
			if isDiscover && mac != "" {
				handleDiscoveredMAC(client, satelliteID, mac, remoteAddr.IP.String())
			}
		}
	}
}

// parseDHCPDiscover looks at the raw UDP payload to see if it's a BOOTP/DHCP message.
func parseDHCPDiscover(data []byte) (string, bool) {
	if len(data) < 240 {
		return "", false
	}

	// byte 0 is op (1 = BootRequest)
	if data[0] != BootRequest {
		return "", false
	}

	// byte 1 is htype (1 = Ethernet)
	if data[1] != 1 {
		return "", false
	}

	// byte 2 is hlen (6 = MAC length)
	hlen := int(data[2])
	if hlen != 6 {
		return "", false
	}

	// bytes 28-34 typically contain the client hardware address (CHADDR)
	chaddr := data[28 : 28+hlen]
	macAddr := fmt.Sprintf("%02X:%02X:%02X:%02X:%02X:%02X",
		chaddr[0], chaddr[1], chaddr[2], chaddr[3], chaddr[4], chaddr[5])

	// Check Magic Cookie at offset 236
	cookie := uint32(data[236])<<24 | uint32(data[237])<<16 | uint32(data[238])<<8 | uint32(data[239])
	if cookie != MagicCookie {
		return "", false
	}

	// Note: Fully parsing options to ensure Option 53 (Message Type) == 1 (Discover)
	// requires walking the TLV options list. For simplicity and proof of concept,
	// any BootRequest with the MagicCookie from a zero-IP is usually a Discover/Request.
	return macAddr, true
}

func handleDiscoveredMAC(client pb.LcmServiceClient, satelliteID string, mac string, sourceIP string) {
	cacheMu.Lock()
	defer cacheMu.Unlock()

	// Rate limit: If we've seen this MAC in the last minute, ignore it
	entry, exists := macCache[mac]
	if exists && time.Since(entry.lastSeen) < 1*time.Minute {
		return
	}

	macCache[mac] = dhcpCacheEntry{lastSeen: time.Now()}

	log.Printf("🎉 Discovered new bare-metal machine via DHCP! MAC: %s (Source: %s)", mac, sourceIP)

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		_, err := client.ReportDiscovery(ctx, &pb.DiscoveryRequest{
			SatelliteId:     satelliteID,
			DiscoveredIp:    sourceIP,
			MacAddress:      mac,
			DiscoveryMethod: "DHCP",
		})
		if err != nil {
			log.Printf("❌ Failed to report discovered MAC %s to Core: %v", mac, err)
		} else {
			log.Printf("✅ Successfully reported MAC %s to Core.", mac)
		}
	}()
}
