package redfish

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestTemplateAdapterCollectsStaticAndDynamicData(t *testing.T) {
	t.Parallel()

	fixtures := map[string]map[string]any{
		"/redfish/v1/Systems": {
			"Members": []map[string]any{{"@odata.id": "/redfish/v1/Systems/system-1"}},
		},
		"/redfish/v1/Systems/system-1": {
			"@odata.id":    "/redfish/v1/Systems/system-1",
			"SerialNumber": "SER-001",
			"Model":        "Vendor X1000",
			"PowerState":   "On",
		},
		"/redfish/v1/Managers": {
			"Members": []map[string]any{{"@odata.id": "/redfish/v1/Managers/bmc-1"}},
		},
		"/redfish/v1/Managers/bmc-1": {
			"@odata.id": "/redfish/v1/Managers/bmc-1",
			"EthernetInterfaces": map[string]any{
				"@odata.id": "/redfish/v1/Managers/bmc-1/EthernetInterfaces",
			},
		},
		"/redfish/v1/Managers/bmc-1/EthernetInterfaces": {
			"Members": []map[string]any{{"@odata.id": "/redfish/v1/Managers/bmc-1/EthernetInterfaces/eth0"}},
		},
		"/redfish/v1/Managers/bmc-1/EthernetInterfaces/eth0": {
			"MACAddress": "00:11:22:33:44:55",
		},
		"/redfish/v1/Chassis": {
			"Members": []map[string]any{{"@odata.id": "/redfish/v1/Chassis/chassis-1"}},
		},
		"/redfish/v1/Chassis/chassis-1": {
			"@odata.id": "/redfish/v1/Chassis/chassis-1",
			"Thermal": map[string]any{
				"@odata.id": "/redfish/v1/Chassis/chassis-1/Thermal",
			},
		},
		"/redfish/v1/Chassis/chassis-1/Thermal": {
			"Temperatures": []map[string]any{{"ReadingCelsius": 42}},
		},
	}

	config := Config{
		Endpoint: "https://bmc.test",
		Username: "admin",
		Password: "password",
		Insecure: true,
	}
	adapter := NewTemplateAdapter(config, Template{
		Name: "vendor-x",
		Resources: TemplateResources{
			Systems:  PathSpec{"/redfish/v1/Systems"},
			Managers: PathSpec{"/redfish/v1/Managers"},
			Chassis:  PathSpec{"/redfish/v1/Chassis"},
		},
	})
	adapter.client.Transport = roundTripFunc(func(req *http.Request) (*http.Response, error) {
		payload, ok := fixtures[req.URL.Path]
		if !ok {
			return &http.Response{
				StatusCode: http.StatusNotFound,
				Body:       io.NopCloser(strings.NewReader(`{"error":"not found"}`)),
				Header:     make(http.Header),
				Request:    req,
			}, nil
		}

		body, err := json.Marshal(payload)
		if err != nil {
			return nil, err
		}

		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader(string(body))),
			Header:     make(http.Header),
			Request:    req,
		}, nil
	})

	info, err := adapter.CollectStaticInfo()
	if err != nil {
		t.Fatalf("CollectStaticInfo() error = %v", err)
	}
	if info.SystemSerial != "SER-001" {
		t.Fatalf("unexpected serial: %s", info.SystemSerial)
	}
	if info.BMCMAC != "00:11:22:33:44:55" {
		t.Fatalf("unexpected bmc mac: %s", info.BMCMAC)
	}

	powerState, temp := adapter.CollectDynamicTelemetry()
	if powerState != "On" {
		t.Fatalf("unexpected power state: %s", powerState)
	}
	if temp != 42 {
		t.Fatalf("unexpected temperature: %d", temp)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(req *http.Request) (*http.Response, error) {
	return f(req)
}
