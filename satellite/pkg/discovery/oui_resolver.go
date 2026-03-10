package discovery

import (
	"net"
	"strings"
)

// ouiTable maps the first 3 bytes of a MAC address (OUI) to the vendor name.
// This covers major server/network hardware vendors commonly found in data centers.
var ouiTable = map[string]string{
	// Dell Technologies
	"00:14:22": "Dell",
	"24:6E:96": "Dell",
	"F8:BC:12": "Dell",
	"F8:DB:88": "Dell",
	"B0:83:FE": "Dell",
	"34:17:EB": "Dell",
	"18:66:DA": "Dell",

	// HPE / HP
	"00:17:A4": "HPE",
	"3C:D9:2B": "HPE",
	"94:57:A5": "HPE",
	"A0:D3:C1": "HPE",
	"EC:B1:D7": "HPE",
	"14:02:EC": "HP",
	"D4:C9:EF": "HP",

	// Lenovo / IBM
	"00:06:29": "IBM",
	"40:F2:E9": "Lenovo",
	"98:FA:9B": "Lenovo",
	"70:5A:0F": "Lenovo",
	"C8:CB:B8": "Lenovo",

	// Supermicro
	"00:25:90": "Supermicro",
	"0C:C4:7A": "Supermicro",
	"AC:1F:6B": "Supermicro",

	// Inspur
	"6C:92:BF": "Inspur",
	"D4:A1:48": "Inspur",

	// Huawei
	"00:E0:FC": "Huawei",
	"24:44:27": "Huawei",
	"48:46:FB": "Huawei",
	"88:44:77": "Huawei",
	"C8:B4:4D": "Huawei",

	// Cisco
	"00:1A:A1": "Cisco",
	"00:26:CB": "Cisco",
	"34:DB:FD": "Cisco",
	"F4:CF:E2": "Cisco",

	// NVIDIA (DGX, networking)
	"04:3F:72": "NVIDIA",
	"00:04:4B": "NVIDIA",
	"A0:88:C2": "NVIDIA",

	// Intel (NICs, server boards)
	"00:1B:21": "Intel",
	"3C:FD:FE": "Intel",
	"A4:BF:01": "Intel",
	"68:05:CA": "Intel",

	// Mellanox (now NVIDIA)
	"00:02:C9": "Mellanox",
	"E4:1D:2D": "Mellanox",
	"7C:FE:90": "Mellanox",

	// Broadcom
	"00:10:18": "Broadcom",
	"D8:12:65": "Broadcom",

	// Quanta / QCT
	"00:1E:68": "Quanta",
	"54:AB:3A": "Quanta",

	// Fujitsu
	"00:0B:5D": "Fujitsu",
	"00:17:42": "Fujitsu",

	// ZTE
	"00:1E:73": "ZTE",
	"74:A7:EA": "ZTE",

	// H3C
	"3C:8C:40": "H3C",
	"70:BA:EF": "H3C",
}

// ResolveOUI returns the vendor name for the given MAC address based on its OUI prefix.
// Returns "Unknown" if the OUI is not in the database.
func ResolveOUI(mac net.HardwareAddr) string {
	if len(mac) < 3 {
		return "Unknown"
	}

	// Build OUI prefix string (XX:XX:XX) in uppercase
	prefix := strings.ToUpper(mac.String()[:8])

	if vendor, ok := ouiTable[prefix]; ok {
		return vendor
	}
	return "Unknown"
}
