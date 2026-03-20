package redfish

import (
	"fmt"

	"github.com/stmcginnis/gofish"
)

// OpenBMCAdapter is the baseline implementation aligned with standard Redfish.
type OpenBMCAdapter struct {
	config gofish.ClientConfig
}

func NewOpenBMCAdapter(config Config) *OpenBMCAdapter {
	return &OpenBMCAdapter{
		config: gofish.ClientConfig{
			Endpoint: config.Endpoint,
			Username: config.Username,
			Password: config.Password,
			Insecure: config.Insecure,
		},
	}
}

func (a *OpenBMCAdapter) Name() string {
	return "openbmc-baseline"
}

func (a *OpenBMCAdapter) CollectStaticInfo() (*Info, error) {
	client, err := gofish.Connect(a.config)
	if err != nil {
		return nil, err
	}
	defer client.Logout()

	systems, err := client.Service.Systems()
	if err != nil {
		return nil, err
	}
	if len(systems) == 0 {
		return nil, fmt.Errorf("no systems found via Redfish")
	}

	system := systems[0]

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
		BMCIP:        a.config.Endpoint,
		BMCMAC:       bmcMAC,
	}, nil
}

func (a *OpenBMCAdapter) CollectDynamicTelemetry() (string, int32) {
	client, err := gofish.ConnectDefault(a.config.Endpoint)
	if err != nil {
		client, err = gofish.Connect(a.config)
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
