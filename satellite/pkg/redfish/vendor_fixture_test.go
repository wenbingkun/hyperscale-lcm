package redfish

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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

			fixtures := loadVendorFixtureBundle(t, tc.name)
			server := newFixtureTLSServer(t, fixtures, "admin", "password")
			adapter := NewTemplateAdapter(Config{
				Endpoint: server.URL,
				Username: "admin",
				Password: "password",
				Insecure: true,
			}, template)

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
		if equalFold(template.Name, name) {
			return template, true
		}
	}
	return Template{}, false
}

func newFixtureTLSServer(t *testing.T, fixtures map[string]map[string]any, username string, password string) *httptest.Server {
	t.Helper()

	expectedAuthorization := "Basic " + base64.StdEncoding.EncodeToString([]byte(username+":"+password))
	server := httptest.NewTLSServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != expectedAuthorization {
			writer.Header().Set("WWW-Authenticate", `Basic realm="redfish-fixture"`)
			writer.WriteHeader(http.StatusUnauthorized)
			return
		}

		payload, ok := fixtures[request.URL.Path]
		if !ok {
			writer.WriteHeader(http.StatusNotFound)
			_ = json.NewEncoder(writer).Encode(map[string]any{"error": "not found"})
			return
		}

		writer.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(writer).Encode(payload); err != nil {
			t.Fatalf("encode fixture payload: %v", err)
		}
	}))
	t.Cleanup(server.Close)
	return server
}

func equalFold(left string, right string) bool {
	return strings.EqualFold(left, right)
}
