package discovery

import (
	"context"
	"log"
	"net"
	"time"
)

// ListenDHCP listens for DHCP broadcast traffic on the given interface
// using a raw UDP socket on port 67 (DHCP server port).
//
// When a DHCP DISCOVER or REQUEST packet is captured, the client's MAC
// address and requested/offered IP are extracted and sent to the events channel.
//
// This approach uses raw UDP sockets instead of libpcap/gopacket to avoid
// CGO dependencies, making it easier to cross-compile and deploy.
func ListenDHCP(ctx context.Context, iface string, events chan<- Event) {
	log.Printf("Starting DHCP listener on interface %s (UDP :67)", iface)

	for {
		// Retry loop in case the socket fails to bind
		err := listenDHCPOnce(ctx, iface, events)
		if ctx.Err() != nil {
			return // Context cancelled, clean shutdown
		}
		log.Printf("DHCP listener error: %v, retrying in 5s...", err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}

func listenDHCPOnce(ctx context.Context, iface string, events chan<- Event) error {
	// Determine listen address: bind to specific interface IP if provided,
	// otherwise listen on all interfaces.
	listenAddr := ":67"
	if iface != "" {
		if ifc, err := net.InterfaceByName(iface); err == nil {
			if addrs, err := ifc.Addrs(); err == nil {
				for _, addr := range addrs {
					if ipnet, ok := addr.(*net.IPNet); ok && ipnet.IP.To4() != nil {
						listenAddr = ipnet.IP.String() + ":67"
						log.Printf("DHCP listener binding to %s (%s)", listenAddr, iface)
						break
					}
				}
			}
		} else {
			log.Printf("Warning: interface %s not found, listening on all interfaces", iface)
		}
	}

	conn, err := net.ListenPacket("udp4", listenAddr)
	if err != nil {
		return err
	}
	defer conn.Close()

	// Close socket when context is cancelled
	go func() {
		<-ctx.Done()
		conn.Close()
	}()

	buf := make([]byte, 1500)
	for {
		n, _, err := conn.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}

		if n < 240 {
			continue // Too short to be a valid DHCP packet
		}

		ev, ok := parseDHCPPacket(buf[:n])
		if !ok {
			continue
		}

		select {
		case events <- ev:
		default:
			log.Println("Discovery event channel full, dropping DHCP event")
		}
	}
}

// parseDHCPPacket extracts MAC and IP from a raw DHCP (BOOTP) packet.
//
// BOOTP/DHCP packet layout (RFC 2131):
//   Offset  Field
//   0       op      (1=REQUEST, 2=REPLY)
//   1       htype   (1=Ethernet)
//   2       hlen    (6 for Ethernet)
//   12      xid     (transaction ID)
//   16      ciaddr  (client IP)
//   20      yiaddr  (your IP — assigned by server)
//   24      siaddr  (server IP)
//   28      chaddr  (client hardware address, 16 bytes)
//   236     magic cookie (99.130.83.99)
//   240+    options
func parseDHCPPacket(data []byte) (Event, bool) {
	if len(data) < 240 {
		return Event{}, false
	}

	htype := data[1]
	hlen := data[2]

	// Only handle Ethernet (htype=1, hlen=6)
	if htype != 1 || hlen != 6 {
		return Event{}, false
	}

	// Verify DHCP magic cookie at offset 236
	if data[236] != 99 || data[237] != 130 || data[238] != 83 || data[239] != 99 {
		return Event{}, false
	}

	// Extract client MAC from chaddr (offset 28, 6 bytes for Ethernet)
	mac := net.HardwareAddr(make([]byte, 6))
	copy(mac, data[28:34])

	// Skip broadcast/null MACs
	if mac.String() == "00:00:00:00:00:00" || mac.String() == "ff:ff:ff:ff:ff:ff" {
		return Event{}, false
	}

	// Determine IP: prefer yiaddr (server-assigned), fallback to ciaddr (client's own)
	ip := net.IP(data[16:20]) // ciaddr
	yiaddr := net.IP(data[20:24])
	if !yiaddr.IsUnspecified() {
		ip = yiaddr
	}

	// Try to find Requested IP Address from DHCP options (option 50) if ciaddr is 0.0.0.0
	if ip.IsUnspecified() {
		if reqIP, ok := findDHCPOption(data[240:], 50); ok && len(reqIP) == 4 {
			ip = net.IP(reqIP)
		}
	}

	// If we still have no usable IP, skip — we'll catch this device on the DHCP ACK
	if ip.IsUnspecified() {
		return Event{}, false
	}

	// Determine DHCP message type from option 53
	method := "DHCP"
	if msgType, ok := findDHCPOption(data[240:], 53); ok && len(msgType) == 1 {
		switch msgType[0] {
		case 1:
			method = "DHCP_DISCOVER"
		case 2:
			method = "DHCP_OFFER"
		case 3:
			method = "DHCP_REQUEST"
		case 5:
			method = "DHCP_ACK"
		}
	}

	return Event{
		IP:     ip,
		MAC:    mac,
		Method: method,
	}, true
}

// findDHCPOption searches DHCP options for the given option code and returns its value.
func findDHCPOption(options []byte, code byte) ([]byte, bool) {
	i := 0
	for i < len(options) {
		if options[i] == 255 {
			break // End of options
		}
		if options[i] == 0 {
			i++ // Padding
			continue
		}
		if i+1 >= len(options) {
			break
		}
		optCode := options[i]
		optLen := int(options[i+1])
		i += 2
		if i+optLen > len(options) {
			break
		}
		if optCode == code {
			val := make([]byte, optLen)
			copy(val, options[i:i+optLen])
			return val, true
		}
		i += optLen
	}
	return nil, false
}
