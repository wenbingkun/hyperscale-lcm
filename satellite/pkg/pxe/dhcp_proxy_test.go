package pxe

import (
	"net"
	"testing"
)

func TestParsePXEDHCPRequestRecognizesPXEClient(t *testing.T) {
	packet := buildDHCPRequestPacket(t, dhcpMessageDiscover, "PXEClient:Arch:00000:UNDI:003001", "", [6]byte{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff})

	req, ok := parsePXEDHCPRequest(packet)
	if !ok {
		t.Fatalf("expected PXE request to be parsed")
	}
	if req.messageType != dhcpMessageDiscover {
		t.Fatalf("expected DHCP discover, got %d", req.messageType)
	}
	if req.clientHWAddr.String() != "aa:bb:cc:dd:ee:ff" {
		t.Fatalf("unexpected mac address %s", req.clientHWAddr)
	}
}

func TestBuildProxyDHCPResponseAdvertisesLegacyBootFile(t *testing.T) {
	cfg := ConfigFromEnv(ServerConfig{
		BootServerHost:    "10.0.0.20",
		HTTPAddr:          ":8090",
		LegacyPXEBootFile: "undionly.kpxe",
	})
	req := dhcpRequest{
		messageType:  dhcpMessageDiscover,
		clientHWAddr: net.HardwareAddr{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff},
		vendorClass:  "PXEClient:Arch:00000:UNDI:003001",
	}

	reply, err := buildProxyDHCPResponse(req, cfg)
	if err != nil {
		t.Fatalf("expected response to build, got %v", err)
	}

	bootServer, ok := findOption(reply[dhcpOptionsOffset:], dhcpOptionBootServer)
	if !ok || string(bootServer) != "10.0.0.20" {
		t.Fatalf("expected option 66 to advertise boot server, got %q", string(bootServer))
	}

	bootFile, ok := findOption(reply[dhcpOptionsOffset:], dhcpOptionBootFile)
	if !ok || string(bootFile) != "undionly.kpxe" {
		t.Fatalf("expected option 67 to advertise legacy boot file, got %q", string(bootFile))
	}
}

func TestBuildProxyDHCPResponseChainloadsIPXEClientsToHTTPScript(t *testing.T) {
	cfg := ConfigFromEnv(ServerConfig{
		BootServerHost:    "10.0.0.30",
		HTTPAddr:          ":8090",
		IPXEBootScriptURL: "http://10.0.0.30:8090/ipxe",
	})
	req := dhcpRequest{
		messageType:  dhcpMessageRequest,
		clientHWAddr: net.HardwareAddr{0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff},
		userClass:    "iPXE",
	}

	reply, err := buildProxyDHCPResponse(req, cfg)
	if err != nil {
		t.Fatalf("expected response to build, got %v", err)
	}

	bootFile, ok := findOption(reply[dhcpOptionsOffset:], dhcpOptionBootFile)
	if !ok {
		t.Fatalf("expected option 67 in proxy response")
	}
	if string(bootFile) != "http://10.0.0.30:8090/ipxe" {
		t.Fatalf("expected iPXE clients to receive HTTP script, got %q", string(bootFile))
	}

	messageType, ok := findOption(reply[dhcpOptionsOffset:], dhcpOptionMessageType)
	if !ok || len(messageType) != 1 || messageType[0] != dhcpMessageAck {
		t.Fatalf("expected DHCP ACK for request flow, got %v", messageType)
	}
}

func TestDHCPResponseTargetFallsBackToBroadcast(t *testing.T) {
	target := dhcpResponseTarget(&net.UDPAddr{IP: net.IPv4zero, Port: 67})
	udpTarget, ok := target.(*net.UDPAddr)
	if !ok {
		t.Fatalf("expected UDP response target")
	}
	if !udpTarget.IP.Equal(net.IPv4bcast) || udpTarget.Port != 68 {
		t.Fatalf("expected broadcast response target, got %s:%d", udpTarget.IP, udpTarget.Port)
	}
}

func buildDHCPRequestPacket(t *testing.T, messageType byte, vendorClass string, userClass string, mac [6]byte) []byte {
	t.Helper()

	packet := make([]byte, dhcpOptionsOffset)
	packet[0] = bootRequest
	packet[1] = 1
	packet[2] = 6
	copy(packet[4:8], []byte{0x12, 0x34, 0x56, 0x78})
	copy(packet[28:34], mac[:])
	copy(packet[dhcpMagicCookieOffset:dhcpOptionsOffset], []byte{99, 130, 83, 99})

	options := []byte{dhcpOptionMessageType, 1, messageType}
	if vendorClass != "" {
		options = appendOption(options, dhcpOptionVendorClass, []byte(vendorClass))
	}
	if userClass != "" {
		options = appendOption(options, dhcpOptionUserClass, []byte(userClass))
	}
	options = append(options, dhcpOptionEnd)

	return append(packet, options...)
}
