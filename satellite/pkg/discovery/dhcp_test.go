package discovery

import (
	"testing"
)

func TestParseDHCPDiscover(t *testing.T) {
	tests := []struct {
		name        string
		payload     []byte
		expectedMAC string
		expectedOk  bool
	}{
		{
			name:        "Empty payload",
			payload:     []byte{},
			expectedMAC: "",
			expectedOk:  false,
		},
		{
			name:        "Too short payload",
			payload:     make([]byte, 100), // Less than 240
			expectedMAC: "",
			expectedOk:  false,
		},
		{
			name: "Valid DHCP Discover Template",
			payload: func() []byte {
				buf := make([]byte, 240)
				buf[0] = BootRequest // op
				buf[1] = 1           // htype (Ethernet)
				buf[2] = 6           // hlen

				// Mock Client MAC Address (CHADDR): AA:BB:CC:DD:EE:FF
				mac := []byte{0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF}
				copy(buf[28:], mac)

				// Magic Cookie at 236
				buf[236] = 99
				buf[237] = 130
				buf[238] = 83
				buf[239] = 99

				return buf
			}(),
			expectedMAC: "AA:BB:CC:DD:EE:FF",
			expectedOk:  true,
		},
		{
			name: "Invalid OpCode (BootReply instead of BootRequest)",
			payload: func() []byte {
				buf := make([]byte, 240)
				buf[0] = BootReply // NOT BootRequest
				buf[1] = 1
				buf[2] = 6
				buf[236] = 99
				buf[237] = 130
				buf[238] = 83
				buf[239] = 99
				return buf
			}(),
			expectedMAC: "",
			expectedOk:  false,
		},
		{
			name: "Invalid Magic Cookie",
			payload: func() []byte {
				buf := make([]byte, 240)
				buf[0] = BootRequest
				buf[1] = 1
				buf[2] = 6
				// Missing the exact cookie match
				buf[236] = 99
				buf[237] = 130
				buf[238] = 83
				buf[239] = 100 // Invalid
				return buf
			}(),
			expectedMAC: "",
			expectedOk:  false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			mac, ok := parseDHCPDiscover(tt.payload)
			if ok != tt.expectedOk {
				t.Errorf("expected Ok = %v, got %v", tt.expectedOk, ok)
			}
			if mac != tt.expectedMAC {
				t.Errorf("expected MAC = %q, got %q", tt.expectedMAC, mac)
			}
		})
	}
}
