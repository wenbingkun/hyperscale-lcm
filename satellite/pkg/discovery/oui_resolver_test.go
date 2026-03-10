package discovery

import (
	"net"
	"testing"
)

func TestResolveOUI_KnownVendors(t *testing.T) {
	tests := []struct {
		mac    string
		vendor string
	}{
		{"00:25:90:01:02:03", "Supermicro"},
		{"24:6E:96:AA:BB:CC", "Dell"},
		{"3C:D9:2B:11:22:33", "HPE"},
		{"40:F2:E9:44:55:66", "Lenovo"},
		{"6C:92:BF:77:88:99", "Inspur"},
		{"00:E0:FC:AA:BB:CC", "Huawei"},
		{"04:3F:72:DD:EE:FF", "NVIDIA"},
		{"00:02:C9:11:22:33", "Mellanox"},
	}

	for _, tt := range tests {
		mac, err := net.ParseMAC(tt.mac)
		if err != nil {
			t.Fatalf("invalid MAC %s: %v", tt.mac, err)
		}
		got := ResolveOUI(mac)
		if got != tt.vendor {
			t.Errorf("ResolveOUI(%s) = %s, want %s", tt.mac, got, tt.vendor)
		}
	}
}

func TestResolveOUI_Unknown(t *testing.T) {
	mac, _ := net.ParseMAC("DE:AD:BE:EF:00:01")
	if got := ResolveOUI(mac); got != "Unknown" {
		t.Errorf("expected Unknown, got %s", got)
	}
}

func TestResolveOUI_ShortMAC(t *testing.T) {
	if got := ResolveOUI(net.HardwareAddr{0x00, 0x25}); got != "Unknown" {
		t.Errorf("expected Unknown for short MAC, got %s", got)
	}
}
