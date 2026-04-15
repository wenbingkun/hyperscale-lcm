package redfish

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
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
			server := newFixtureTLSServer(t, fixtures, "admin", "password", fixtureServerOptions{})
			adapter := NewTemplateAdapter(Config{
				Endpoint: server.URL(),
				Username: "admin",
				Password: "password",
				Insecure: true,
			}, template, NewTransport(Config{
				Endpoint: server.URL(),
				Username: "admin",
				Password: "password",
				Insecure: true,
			}, TransportOptions{
				AuthMode: AuthModeBasicOnly,
				Client:   server.Client(),
			}, nil))

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

func TestVendorFixturesBasicAndSessionModes(t *testing.T) {
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
	modes := []AuthMode{AuthModeBasicOnly, AuthModeSessionPreferred}

	for _, tc := range cases {
		tc := tc
		for _, mode := range modes {
			mode := mode
			t.Run(tc.name+"-"+string(mode), func(t *testing.T) {
				t.Parallel()

				template, ok := findTemplateByName(templates, tc.name)
				if !ok {
					t.Fatalf("template %s not found", tc.name)
				}

				fixtures := loadVendorFixtureBundle(t, tc.name)
				server := newFixtureTLSServer(t, fixtures, "admin", "password", fixtureServerOptions{
					sessionSupported: mode == AuthModeSessionPreferred,
					returnToken:      true,
				})
				config := Config{
					Endpoint:             server.URL(),
					Username:             "admin",
					Password:             "password",
					Insecure:             true,
					AuthMode:             mode,
					SessionTTLSecondsMax: defaultSessionTTLSecondsMax,
				}
				adapter := NewTemplateAdapter(config, template, NewTransport(config, TransportOptions{
					AuthMode:             mode,
					SessionTTLSecondsMax: defaultSessionTTLSecondsMax,
					Client:               server.Client(),
				}, nil))

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

				if mode == AuthModeSessionPreferred {
					if _, ok := server.findRequest(http.MethodPost, sessionsPath); !ok {
						t.Fatal("expected session creation request")
					}
					t.Logf("executed via %s path", mode)
					return
				}

				if _, ok := server.findRequest(http.MethodPost, sessionsPath); ok {
					t.Fatal("did not expect session creation request in BASIC_ONLY mode")
				}
				t.Logf("executed via %s path", mode)
			})
		}
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

type fixtureServerOptions struct {
	sessionSupported bool
	returnToken      bool
}

type fixtureRequest struct {
	method        string
	path          string
	authorization string
	token         string
}

type fixtureTLSServer struct {
	server   *httptest.Server
	username string
	password string

	mu          sync.Mutex
	requests    []fixtureRequest
	validTokens map[string]string
	tokenID     int
}

func newFixtureTLSServer(
	t *testing.T,
	fixtures map[string]map[string]any,
	username string,
	password string,
	options fixtureServerOptions,
) *fixtureTLSServer {
	t.Helper()

	server := &fixtureTLSServer{
		username:    username,
		password:    password,
		validTokens: make(map[string]string),
	}
	handler := http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		server.capture(request)

		switch {
		case request.Method == http.MethodPost && request.URL.Path == sessionsPath:
			if !options.sessionSupported {
				writer.WriteHeader(http.StatusNotFound)
				return
			}

			var payload map[string]string
			if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
				t.Fatalf("decode session payload: %v", err)
			}
			if payload["UserName"] != username || payload["Password"] != password {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}

			server.mu.Lock()
			server.tokenID++
			token := "session-token-" + strconv.Itoa(server.tokenID)
			location := sessionsPath + "/" + strconv.Itoa(server.tokenID)
			server.validTokens[token] = location
			server.mu.Unlock()

			writer.Header().Set("Content-Type", "application/json")
			writer.Header().Set("Location", location)
			if options.returnToken {
				writer.Header().Set("X-Auth-Token", token)
			}
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"@odata.id": location,
			})
			return
		case request.Method == http.MethodDelete && strings.HasPrefix(request.URL.Path, sessionsPath+"/"):
			if !server.tokenMatchesPath(request.Header.Get("X-Auth-Token"), request.URL.Path) {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}
			writer.WriteHeader(http.StatusNoContent)
			return
		}

		if !server.authorized(request) {
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
	})

	server.server = httptest.NewTLSServer(handler)
	t.Cleanup(server.server.Close)
	return server
}

func (s *fixtureTLSServer) URL() string {
	return s.server.URL
}

func (s *fixtureTLSServer) Client() *http.Client {
	return s.server.Client()
}

func (s *fixtureTLSServer) capture(request *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.requests = append(s.requests, fixtureRequest{
		method:        request.Method,
		path:          request.URL.Path,
		authorization: request.Header.Get("Authorization"),
		token:         request.Header.Get("X-Auth-Token"),
	})
}

func (s *fixtureTLSServer) findRequest(method, path string) (fixtureRequest, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, request := range s.requests {
		if request.method == method && request.path == path {
			return request, true
		}
	}
	return fixtureRequest{}, false
}

func (s *fixtureTLSServer) authorized(request *http.Request) bool {
	if token := request.Header.Get("X-Auth-Token"); token != "" {
		return s.tokenValid(token)
	}
	return request.Header.Get("Authorization") == basicAuth(s.username, s.password)
}

func (s *fixtureTLSServer) tokenValid(token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.validTokens[token]
	return ok
}

func (s *fixtureTLSServer) tokenMatchesPath(token, path string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.validTokens[token] == path
}

func equalFold(left string, right string) bool {
	return strings.EqualFold(left, right)
}
