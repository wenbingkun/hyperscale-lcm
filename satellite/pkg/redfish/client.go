package redfish

import (
	"log"
	"os"

	lcmpb "github.com/sc-lcm/satellite/pkg/grpc"
	"github.com/stmcginnis/gofish"
)

// Info holds the extracted Redfish details
type Info struct {
	BMCIP        string
	BMCMAC       string
	SystemSerial string
	SystemModel  string
	PowerState   string
	SystemTempC  int32
}

// Collector is the Redfish client wrapper
type Collector struct {
	config gofish.ClientConfig
	mock   bool
}

// NewCollector initializes a new Redfish telemetry collector
func NewCollector() *Collector {
	bmcIP := os.Getenv("LCM_BMC_IP")
	bmcUser := os.Getenv("LCM_BMC_USER")
	bmcPass := os.Getenv("LCM_BMC_PASSWORD")

	if bmcIP == "" {
		log.Println("⚠️ LCM_BMC_IP not set. Redfish collector will run in MOCK mode.")
		return &Collector{mock: true}
	}

	config := gofish.ClientConfig{
		Endpoint: "https://" + bmcIP,
		Username: bmcUser,
		Password: bmcPass,
		Insecure: true, // Typically true for internal BMC self-signed certs
	}

	return &Collector{
		config: config,
		mock:   false,
	}
}

// Collect basic hardware info, typically called once at registration
func (c *Collector) CollectStaticInfo() (*Info, error) {
	if c.mock {
		return &Info{
			BMCIP:        "192.168.100.150",
			BMCMAC:       "aa:bb:cc:dd:ee:ff",
			SystemSerial: "SGH1234567X",
			SystemModel:  "PowerEdge R740",
			PowerState:   "Mock-Power-State",
		}, nil
	}

	client, err := gofish.Connect(c.config)
	if err != nil {
		return nil, err
	}
	defer client.Logout()

	systems, err := client.Service.Systems()
	if err != nil || len(systems) == 0 {
		return nil, err
	}

	system := systems[0]

	// Attempt to get Managers (BMC) MAC
	bmcMAC := "Unknown"
	managers, err := client.Service.Managers()
	if err == nil && len(managers) > 0 {
		manager := managers[0]
		ethInterfaces, err := manager.EthernetInterfaces()
		if err == nil && len(ethInterfaces) > 0 {
			bmcMAC = ethInterfaces[0].MACAddress
		}
	}

	return &Info{
		SystemSerial: system.SerialNumber,
		SystemModel:  system.Model,
		PowerState:   string(system.PowerState),
		BMCIP:        c.config.Endpoint, // Simplified
		BMCMAC:       bmcMAC,
	}, nil
}

// Collect dynamic telemetry, typically called during heartbeat
func (c *Collector) CollectDynamicTelemetry() (string, int32) {
	if c.mock {
		return "On", 35
	}

	client, err := gofish.ConnectDefault(c.config.Endpoint) // simplified connect without full session
	if err != nil {
		client, err = gofish.Connect(c.config)
		if err != nil {
			return "Unknown", 0
		}
	}
	defer client.Logout()

	systems, err := client.Service.Systems()
	if err != nil || len(systems) == 0 {
		return "Unknown", 0
	}
	system := systems[0]

	// Fetch thermal info from chassis instead of system
	tempC := int32(0)
	chassisList, err := client.Service.Chassis()
	if err == nil && len(chassisList) > 0 {
		thermals, err := chassisList[0].Thermal()
		if err == nil && thermals != nil && len(thermals.Temperatures) > 0 {
			if thermals.Temperatures[0].ReadingCelsius != nil {
				tempC = int32(*thermals.Temperatures[0].ReadingCelsius)
			}
		}
	}

	return string(system.PowerState), tempC
}

// EnrichSpecs enriches the gRPC HardwareSpecs with Redfish info
func (c *Collector) EnrichSpecs(specs *lcmpb.HardwareSpecs) {
	info, err := c.CollectStaticInfo()
	if err != nil {
		log.Printf("⚠️ Redfish static collection failed: %v", err)
		return
	}

	specs.BmcIp = info.BMCIP
	specs.BmcMac = info.BMCMAC
	specs.SystemSerial = info.SystemSerial
	specs.SystemModel = info.SystemModel
}
