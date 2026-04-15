package redfish

import (
	"context"
	"fmt"
	"net/http"
)

// OpenBMCAdapter is the baseline implementation aligned with standard Redfish.
type OpenBMCAdapter struct {
	config    Config
	transport *Transport
}

func NewOpenBMCAdapter(config Config, transport *Transport) *OpenBMCAdapter {
	if transport == nil {
		transport = NewTransport(config, TransportOptions{
			AuthMode: AuthModeBasicOnly,
		}, nil)
	}

	return &OpenBMCAdapter{
		config:    config,
		transport: transport,
	}
}

func (a *OpenBMCAdapter) Name() string {
	return "openbmc-baseline"
}

func (a *OpenBMCAdapter) CollectStaticInfo() (*Info, error) {
	system, err := a.fetchPrimaryResource(defaultServiceRoot + "/Systems")
	if err != nil {
		return nil, err
	}

	bmcMAC := "Unknown"
	manager, err := a.fetchPrimaryResource(defaultServiceRoot + "/Managers")
	if err == nil {
		if eth, ethErr := a.fetchManagerEthernet(manager); ethErr == nil {
			if value, ok := extractString(eth, "MACAddress"); ok {
				bmcMAC = value
			}
		}
	}

	serial, _ := extractString(system, "SerialNumber")
	model, _ := extractString(system, "Model")
	powerState, _ := extractString(system, "PowerState")

	return &Info{
		BMCIP:        a.config.Endpoint,
		BMCMAC:       bmcMAC,
		SystemSerial: serial,
		SystemModel:  model,
		PowerState:   powerState,
	}, nil
}

func (a *OpenBMCAdapter) CollectDynamicTelemetry() (string, int32) {
	system, err := a.fetchPrimaryResource(defaultServiceRoot + "/Systems")
	if err != nil {
		return "Unknown", 0
	}

	powerState, ok := extractString(system, "PowerState")
	if !ok || powerState == "" {
		powerState = "Unknown"
	}

	tempC := int32(0)
	chassis, err := a.fetchPrimaryResource(defaultServiceRoot + "/Chassis")
	if err == nil {
		if thermal, thermalErr := a.fetchThermal(chassis); thermalErr == nil {
			if value, valueOK := extractInt32(thermal, "Temperatures.0.ReadingCelsius"); valueOK {
				tempC = value
			}
		}
	}

	return powerState, tempC
}

func (a *OpenBMCAdapter) fetchPrimaryResource(collectionPath string) (map[string]any, error) {
	document, err := a.getJSON(collectionPath)
	if err != nil {
		return nil, err
	}

	if memberURI := firstMemberURI(document); memberURI != "" {
		return a.getJSON(memberURI)
	}
	return document, nil
}

func (a *OpenBMCAdapter) fetchManagerEthernet(manager map[string]any) (map[string]any, error) {
	resourceURIs := resolveResourceURIs(
		getLink(manager, "@odata.id"),
		nil,
		getLink(manager, "EthernetInterfaces"),
	)
	if len(resourceURIs) == 0 {
		return nil, fmt.Errorf("manager ethernet resource not found")
	}

	var lastErr error
	for _, resourceURI := range resourceURIs {
		document, err := a.getJSON(resourceURI)
		if err != nil {
			lastErr = err
			continue
		}

		if memberURI := firstMemberURI(document); memberURI != "" {
			return a.getJSON(memberURI)
		}
		return document, nil
	}

	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("manager ethernet resource not found")
}

func (a *OpenBMCAdapter) fetchThermal(chassis map[string]any) (map[string]any, error) {
	resourceURIs := resolveResourceURIs(
		getLink(chassis, "@odata.id"),
		nil,
		getLink(chassis, "Thermal"),
	)
	if len(resourceURIs) == 0 {
		return nil, fmt.Errorf("thermal resource not found")
	}

	var lastErr error
	for _, resourceURI := range resourceURIs {
		document, err := a.getJSON(resourceURI)
		if err != nil {
			lastErr = err
			continue
		}
		return document, nil
	}

	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("thermal resource not found")
}

func (a *OpenBMCAdapter) getJSON(path string) (map[string]any, error) {
	response, err := a.transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     path,
		ReadOnly: true,
	})
	if err != nil {
		return nil, err
	}

	payload, err := response.JSON()
	if err != nil {
		return nil, err
	}
	return payload, nil
}
