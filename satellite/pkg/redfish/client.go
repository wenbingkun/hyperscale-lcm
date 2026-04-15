package redfish

import (
	"context"
	"log"

	lcmpb "github.com/sc-lcm/satellite/pkg/grpc"
)

// Info holds the extracted Redfish details.
type Info struct {
	BMCIP        string
	BMCMAC       string
	SystemSerial string
	SystemModel  string
	PowerState   string
	SystemTempC  int32
}

// Collector is the Redfish client wrapper used by registration and heartbeat.
type Collector struct {
	adapter   Adapter
	transport *Transport
}

// NewCollector initializes a new Redfish telemetry collector.
func NewCollector() *Collector {
	config, mock := loadConfigFromEnv()
	if mock {
		log.Println("⚠️ LCM_BMC_IP not set. Redfish collector will run in MOCK mode.")
		return &Collector{adapter: MockAdapter{}}
	}

	transport := NewTransport(config, TransportOptions{
		AuthMode: AuthModeBasicOnly,
	}, nil)
	registry, err := NewAdapterRegistry(config, transport)
	if err != nil {
		log.Printf("⚠️ Failed to load Redfish templates: %v", err)
	}

	adapter, buildErr := registry.Build()
	if buildErr != nil {
		log.Printf("⚠️ Failed to build Redfish adapter: %v. Falling back to OpenBMC baseline.", buildErr)
		adapter = NewOpenBMCAdapter(config, transport)
	}

	log.Printf("🔌 Redfish collector initialized with adapter: %s", adapter.Name())
	return &Collector{
		adapter:   adapter,
		transport: transport,
	}
}

// Collect basic hardware info, typically called once at registration.
func (c *Collector) CollectStaticInfo() (*Info, error) {
	return c.adapter.CollectStaticInfo()
}

// Collect dynamic telemetry, typically called during heartbeat.
func (c *Collector) CollectDynamicTelemetry() (string, int32) {
	return c.adapter.CollectDynamicTelemetry()
}

func (c *Collector) Close(ctx context.Context) error {
	if c == nil || c.transport == nil {
		return nil
	}
	return c.transport.Close(ctx)
}

// EnrichSpecs enriches the gRPC HardwareSpecs with Redfish info.
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
