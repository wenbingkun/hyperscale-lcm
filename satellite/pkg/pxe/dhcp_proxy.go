package pxe

import (
	"context"
	"log"
	"net"
	"strings"
)

const (
	bootRequest           = 1
	bootReply             = 2
	dhcpMagicCookieOffset = 236
	dhcpOptionsOffset     = 240
	dhcpOptionMessageType = 53
	dhcpOptionRequestedIP = 50
	dhcpOptionServerID    = 54
	dhcpOptionVendorClass = 60
	dhcpOptionBootServer  = 66
	dhcpOptionBootFile    = 67
	dhcpOptionUserClass   = 77
	dhcpOptionArch        = 93
	dhcpOptionEnd         = 255
	dhcpMessageDiscover   = 1
	dhcpMessageOffer      = 2
	dhcpMessageRequest    = 3
	dhcpMessageAck        = 5
)

type dhcpRequest struct {
	messageType  byte
	transaction  [4]byte
	flags        [2]byte
	clientIP     net.IP
	requestedIP  net.IP
	serverIP     net.IP
	relayIP      net.IP
	clientHWAddr net.HardwareAddr
	vendorClass  string
	userClass    string
	hasArch      bool
}

func startDHCPProxyServer(ctx context.Context, cfg ServerConfig) error {
	conn, err := net.ListenPacket("udp4", cfg.DHCPProxyAddr)
	if err != nil {
		return err
	}
	defer conn.Close()

	go func() {
		<-ctx.Done()
		conn.Close()
	}()

	log.Printf("📡 PXE DHCP proxy listening on %s (boot server %s)", cfg.DHCPProxyAddr, cfg.BootServerHost)

	buf := make([]byte, 1500)
	for {
		n, addr, err := conn.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}

		req, ok := parsePXEDHCPRequest(buf[:n])
		if !ok {
			continue
		}

		reply, err := buildProxyDHCPResponse(req, cfg)
		if err != nil {
			log.Printf("PXE DHCP proxy: failed to build response: %v", err)
			continue
		}

		target := dhcpResponseTarget(addr)
		if _, err := conn.WriteTo(reply, target); err != nil {
			log.Printf("PXE DHCP proxy: failed to send response: %v", err)
			continue
		}

		log.Printf("PXE DHCP proxy: advertised boot file %q to %s", bootFileForClient(req, cfg), req.clientHWAddr)
	}
}

func parsePXEDHCPRequest(data []byte) (dhcpRequest, bool) {
	if len(data) < dhcpOptionsOffset {
		return dhcpRequest{}, false
	}
	if data[0] != bootRequest || data[1] != 1 || data[2] != 6 {
		return dhcpRequest{}, false
	}
	if data[dhcpMagicCookieOffset] != 99 || data[dhcpMagicCookieOffset+1] != 130 ||
		data[dhcpMagicCookieOffset+2] != 83 || data[dhcpMagicCookieOffset+3] != 99 {
		return dhcpRequest{}, false
	}

	req := dhcpRequest{
		clientIP:     net.IP(data[12:16]).To4(),
		serverIP:     net.IP(data[20:24]).To4(),
		relayIP:      net.IP(data[24:28]).To4(),
		clientHWAddr: append(net.HardwareAddr(nil), data[28:34]...),
	}
	copy(req.transaction[:], data[4:8])
	copy(req.flags[:], data[10:12])

	if req.clientHWAddr.String() == "00:00:00:00:00:00" || req.clientHWAddr.String() == "ff:ff:ff:ff:ff:ff" {
		return dhcpRequest{}, false
	}

	options := data[dhcpOptionsOffset:]
	if messageType, ok := findOption(options, dhcpOptionMessageType); ok && len(messageType) == 1 {
		req.messageType = messageType[0]
	}
	if req.messageType != dhcpMessageDiscover && req.messageType != dhcpMessageRequest {
		return dhcpRequest{}, false
	}

	if requestedIP, ok := findOption(options, dhcpOptionRequestedIP); ok && len(requestedIP) == 4 {
		req.requestedIP = net.IP(requestedIP).To4()
	}
	if vendorClass, ok := findOption(options, dhcpOptionVendorClass); ok {
		req.vendorClass = string(vendorClass)
	}
	if userClass, ok := findOption(options, dhcpOptionUserClass); ok {
		req.userClass = string(userClass)
	}
	if _, ok := findOption(options, dhcpOptionArch); ok {
		req.hasArch = true
	}

	if !isPXEClient(req) {
		return dhcpRequest{}, false
	}

	return req, true
}

func buildProxyDHCPResponse(req dhcpRequest, cfg ServerConfig) ([]byte, error) {
	cfg = ConfigFromEnv(cfg)

	serverIP := net.ParseIP(cfg.BootServerHost).To4()
	if serverIP == nil {
		serverIP = net.ParseIP(detectLocalIPv4()).To4()
	}

	packet := make([]byte, dhcpOptionsOffset)
	packet[0] = bootReply
	packet[1] = 1
	packet[2] = 6
	copy(packet[4:8], req.transaction[:])
	copy(packet[10:12], req.flags[:])

	if ip := req.clientIP.To4(); ip != nil {
		copy(packet[12:16], ip)
	}
	if serverIP != nil {
		copy(packet[20:24], serverIP)
	}
	if ip := req.relayIP.To4(); ip != nil {
		copy(packet[24:28], ip)
	}
	copy(packet[28:34], req.clientHWAddr)

	bootFile := bootFileForClient(req, cfg)
	copy(packet[108:236], []byte(bootFile))
	copy(packet[dhcpMagicCookieOffset:dhcpOptionsOffset], []byte{99, 130, 83, 99})

	responseType := byte(dhcpMessageOffer)
	if req.messageType == dhcpMessageRequest {
		responseType = dhcpMessageAck
	}

	options := make([]byte, 0, 128)
	options = appendOption(options, dhcpOptionMessageType, []byte{responseType})
	if serverIP != nil {
		options = appendOption(options, dhcpOptionServerID, serverIP)
	}
	options = appendOption(options, dhcpOptionVendorClass, []byte("PXEClient"))
	options = appendOption(options, dhcpOptionBootServer, []byte(cfg.BootServerHost))
	options = appendOption(options, dhcpOptionBootFile, []byte(bootFile))
	options = append(options, dhcpOptionEnd)

	return append(packet, options...), nil
}

func bootFileForClient(req dhcpRequest, cfg ServerConfig) string {
	if isIPXEClient(req) {
		return cfg.IPXEBootScriptURL
	}
	return cfg.LegacyPXEBootFile
}

func isPXEClient(req dhcpRequest) bool {
	vendorClass := strings.ToLower(req.vendorClass)
	userClass := strings.ToLower(req.userClass)
	return strings.Contains(vendorClass, "pxeclient") || strings.Contains(userClass, "ipxe") || req.hasArch
}

func isIPXEClient(req dhcpRequest) bool {
	return strings.Contains(strings.ToLower(req.userClass), "ipxe") ||
		strings.Contains(strings.ToLower(req.vendorClass), "ipxe")
}

func appendOption(options []byte, code byte, value []byte) []byte {
	options = append(options, code, byte(len(value)))
	options = append(options, value...)
	return options
}

func findOption(options []byte, code byte) ([]byte, bool) {
	for i := 0; i < len(options); {
		switch options[i] {
		case dhcpOptionEnd:
			return nil, false
		case 0:
			i++
			continue
		}

		if i+1 >= len(options) {
			return nil, false
		}
		length := int(options[i+1])
		start := i + 2
		end := start + length
		if end > len(options) {
			return nil, false
		}
		if options[i] == code {
			value := make([]byte, length)
			copy(value, options[start:end])
			return value, true
		}
		i = end
	}

	return nil, false
}

func dhcpResponseTarget(addr net.Addr) net.Addr {
	if udpAddr, ok := addr.(*net.UDPAddr); ok {
		if ip := udpAddr.IP.To4(); ip != nil && !ip.IsUnspecified() {
			return &net.UDPAddr{IP: ip, Port: 68}
		}
	}
	return &net.UDPAddr{IP: net.IPv4bcast, Port: 68}
}
