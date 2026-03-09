package discovery

import (
	"net"
	"testing"
)

// buildDHCPPacket constructs a minimal DHCP packet for testing.
func buildDHCPPacket(op, htype, hlen byte, ciaddr, yiaddr [4]byte, chaddr [6]byte, options []byte) []byte {
	pkt := make([]byte, 240+len(options))
	pkt[0] = op
	pkt[1] = htype
	pkt[2] = hlen

	copy(pkt[16:20], ciaddr[:])
	copy(pkt[20:24], yiaddr[:])
	copy(pkt[28:34], chaddr[:])

	// DHCP magic cookie
	pkt[236] = 99
	pkt[237] = 130
	pkt[238] = 83
	pkt[239] = 99

	copy(pkt[240:], options)
	return pkt
}

func TestParseDHCPPacket_ValidACK(t *testing.T) {
	mac := [6]byte{0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF}
	yiaddr := [4]byte{192, 168, 1, 100}
	ciaddr := [4]byte{0, 0, 0, 0}

	// Option 53 (DHCP Message Type) = 5 (ACK), then End
	options := []byte{53, 1, 5, 255}

	pkt := buildDHCPPacket(2, 1, 6, ciaddr, yiaddr, mac, options)

	ev, ok := parseDHCPPacket(pkt)
	if !ok {
		t.Fatal("expected valid parse")
	}
	if ev.IP.String() != "192.168.1.100" {
		t.Errorf("expected IP 192.168.1.100, got %s", ev.IP)
	}
	if ev.MAC.String() != "aa:bb:cc:dd:ee:ff" {
		t.Errorf("expected MAC aa:bb:cc:dd:ee:ff, got %s", ev.MAC)
	}
	if ev.Method != "DHCP_ACK" {
		t.Errorf("expected method DHCP_ACK, got %s", ev.Method)
	}
}

func TestParseDHCPPacket_RequestWithOption50(t *testing.T) {
	mac := [6]byte{0x00, 0x25, 0x90, 0x01, 0x02, 0x03}
	ciaddr := [4]byte{0, 0, 0, 0}
	yiaddr := [4]byte{0, 0, 0, 0}

	// Option 53 = 3 (REQUEST), Option 50 (Requested IP) = 10.0.0.50, End
	options := []byte{
		53, 1, 3, // DHCP REQUEST
		50, 4, 10, 0, 0, 50, // Requested IP Address
		255, // End
	}

	pkt := buildDHCPPacket(1, 1, 6, ciaddr, yiaddr, mac, options)

	ev, ok := parseDHCPPacket(pkt)
	if !ok {
		t.Fatal("expected valid parse")
	}
	if ev.IP.String() != "10.0.0.50" {
		t.Errorf("expected IP 10.0.0.50, got %s", ev.IP)
	}
	if ev.Method != "DHCP_REQUEST" {
		t.Errorf("expected method DHCP_REQUEST, got %s", ev.Method)
	}
}

func TestParseDHCPPacket_TooShort(t *testing.T) {
	_, ok := parseDHCPPacket(make([]byte, 100))
	if ok {
		t.Error("expected parse failure for short packet")
	}
}

func TestParseDHCPPacket_BadMagicCookie(t *testing.T) {
	pkt := make([]byte, 300)
	pkt[1] = 1
	pkt[2] = 6
	// Wrong magic cookie
	pkt[236] = 0
	_, ok := parseDHCPPacket(pkt)
	if ok {
		t.Error("expected parse failure for bad magic cookie")
	}
}

func TestParseDHCPPacket_BroadcastMAC(t *testing.T) {
	mac := [6]byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}
	yiaddr := [4]byte{192, 168, 1, 1}
	options := []byte{53, 1, 5, 255}
	pkt := buildDHCPPacket(2, 1, 6, [4]byte{}, yiaddr, mac, options)

	_, ok := parseDHCPPacket(pkt)
	if ok {
		t.Error("expected parse failure for broadcast MAC")
	}
}

func TestParseDHCPPacket_NullMAC(t *testing.T) {
	mac := [6]byte{0, 0, 0, 0, 0, 0}
	yiaddr := [4]byte{192, 168, 1, 1}
	options := []byte{53, 1, 5, 255}
	pkt := buildDHCPPacket(2, 1, 6, [4]byte{}, yiaddr, mac, options)

	_, ok := parseDHCPPacket(pkt)
	if ok {
		t.Error("expected parse failure for null MAC")
	}
}

func TestFindDHCPOption(t *testing.T) {
	// Option 53 = 5 (ACK), Option 54 (Server ID) = 10.0.0.1, End
	options := []byte{53, 1, 5, 54, 4, 10, 0, 0, 1, 255}

	val, ok := findDHCPOption(options, 53)
	if !ok || len(val) != 1 || val[0] != 5 {
		t.Errorf("expected option 53 = [5], got %v", val)
	}

	val, ok = findDHCPOption(options, 54)
	if !ok || len(val) != 4 {
		t.Fatalf("expected option 54, got ok=%v len=%d", ok, len(val))
	}
	if net.IP(val).String() != "10.0.0.1" {
		t.Errorf("expected 10.0.0.1, got %s", net.IP(val))
	}

	_, ok = findDHCPOption(options, 99)
	if ok {
		t.Error("expected option 99 not found")
	}
}
