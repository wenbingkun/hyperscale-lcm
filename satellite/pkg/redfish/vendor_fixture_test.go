package redfish

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestVendorTemplateFixtures(t *testing.T) {
	t.Parallel()

	templates := loadDocumentationTemplates(t)
	cases := []struct {
		name       string
		wantSerial string
		wantModel  string
		wantMAC    string
		wantPower  string
		wantTemp   int32
	}{
		{
			name:       "openbmc-baseline",
			wantSerial: "OBMC-001",
			wantModel:  "OpenBMC Reference Board",
			wantMAC:    "02:00:00:00:00:01",
			wantPower:  "On",
			wantTemp:   31,
		},
		{
			name:       "dell-idrac",
			wantSerial: "DELL-760-001",
			wantModel:  "PowerEdge R760",
			wantMAC:    "10:20:30:40:50:60",
			wantPower:  "On",
			wantTemp:   28,
		},
		{
			name:       "hpe-ilo",
			wantSerial: "HPE-380-001",
			wantModel:  "ProLiant DL380 Gen11",
			wantMAC:    "AA:BB:CC:DD:EE:01",
			wantPower:  "On",
			wantTemp:   33,
		},
		{
			name:       "lenovo-xcc",
			wantSerial: "LEN-SR650-001",
			wantModel:  "ThinkSystem SR650 V3",
			wantMAC:    "66:55:44:33:22:11",
			wantPower:  "Off",
			wantTemp:   26,
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			template, ok := findTemplateByName(templates, tc.name)
			if !ok {
				t.Fatalf("template %s not found", tc.name)
			}

			adapter := NewTemplateAdapter(Config{
				Endpoint: "https://bmc.test",
				Username: "admin",
				Password: "password",
				Insecure: true,
			}, template)
			fixtures := loadVendorFixtureBundle(t, tc.name)
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
			if info.SystemSerial != tc.wantSerial {
				t.Fatalf("unexpected serial: got %s want %s", info.SystemSerial, tc.wantSerial)
			}
			if info.SystemModel != tc.wantModel {
				t.Fatalf("unexpected model: got %s want %s", info.SystemModel, tc.wantModel)
			}
			if info.BMCMAC != tc.wantMAC {
				t.Fatalf("unexpected BMC MAC: got %s want %s", info.BMCMAC, tc.wantMAC)
			}

			powerState, temp := adapter.CollectDynamicTelemetry()
			if powerState != tc.wantPower {
				t.Fatalf("unexpected power state: got %s want %s", powerState, tc.wantPower)
			}
			if temp != tc.wantTemp {
				t.Fatalf("unexpected temperature: got %d want %d", temp, tc.wantTemp)
			}
		})
	}
}

func loadDocumentationTemplates(t *testing.T) []Template {
	t.Helper()

	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("failed to resolve runtime caller")
	}

	templateDir := filepath.Clean(filepath.Join(filepath.Dir(currentFile), "..", "..", "..", "documentation", "redfish-templates"))
	templates, err := LoadTemplates(templateDir)
	if err != nil {
		t.Fatalf("LoadTemplates(%s) error = %v", templateDir, err)
	}
	return templates
}

func loadVendorFixtureBundle(t *testing.T, name string) map[string]map[string]any {
	t.Helper()

	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("failed to resolve runtime caller")
	}

	path := filepath.Join(filepath.Dir(currentFile), "testdata", "vendor-fixtures", name+".json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read vendor fixture %s: %v", path, err)
	}

	var fixtures map[string]map[string]any
	if err := json.Unmarshal(data, &fixtures); err != nil {
		t.Fatalf("parse vendor fixture %s: %v", path, err)
	}
	return fixtures
}

func findTemplateByName(templates []Template, name string) (Template, bool) {
	for _, template := range templates {
		if strings.EqualFold(template.Name, name) {
			return template, true
		}
	}
	return Template{}, false
}
