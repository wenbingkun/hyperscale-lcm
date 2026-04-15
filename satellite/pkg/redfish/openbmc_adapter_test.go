package redfish

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestOpenBMCAdapterCollectStaticInfoViaTransport(t *testing.T) {
	t.Parallel()

	server := newOpenBMCTestServer(t, openBMCTestServerOptions{})
	adapter := NewOpenBMCAdapter(server.config(), NewTransport(server.config(), TransportOptions{
		AuthMode: AuthModeBasicOnly,
		Client:   server.client(),
	}, nil))

	info, err := adapter.CollectStaticInfo()
	if err != nil {
		t.Fatalf("CollectStaticInfo() error = %v", err)
	}
	if info.SystemSerial != "OBMC-001" {
		t.Fatalf("unexpected serial: %s", info.SystemSerial)
	}
	if info.SystemModel != "OpenBMC Reference Board" {
		t.Fatalf("unexpected model: %s", info.SystemModel)
	}
	if info.PowerState != "On" {
		t.Fatalf("unexpected power state: %s", info.PowerState)
	}
	if info.BMCMAC != "02:00:00:00:00:01" {
		t.Fatalf("unexpected BMC MAC: %s", info.BMCMAC)
	}
}

func TestOpenBMCAdapterHonorsSessionPreferred(t *testing.T) {
	t.Run("supports session", func(t *testing.T) {
		t.Parallel()

		server := newOpenBMCTestServer(t, openBMCTestServerOptions{
			sessionSupported: true,
			returnToken:      true,
			sessionTimeout:   600,
		})
		adapter := NewOpenBMCAdapter(server.config(), NewTransport(server.config(), TransportOptions{
			AuthMode: AuthModeSessionPreferred,
			Client:   server.client(),
		}, nil))

		if _, err := adapter.CollectStaticInfo(); err != nil {
			t.Fatalf("CollectStaticInfo() error = %v", err)
		}

		if _, ok := server.findRequest(http.MethodPost, sessionsPath); !ok {
			t.Fatal("expected session creation request")
		}

		request, ok := server.findRequest(http.MethodGet, defaultServiceRoot+"/Systems/system")
		if !ok {
			t.Fatal("expected system member request")
		}
		if !strings.HasPrefix(request.token, "session-token-") {
			t.Fatalf("expected token-backed request, got %q", request.token)
		}
	})

	t.Run("falls back to basic when session unsupported", func(t *testing.T) {
		t.Parallel()

		server := newOpenBMCTestServer(t, openBMCTestServerOptions{
			sessionSupported: false,
		})
		adapter := NewOpenBMCAdapter(server.config(), NewTransport(server.config(), TransportOptions{
			AuthMode: AuthModeSessionPreferred,
			Client:   server.client(),
		}, nil))

		if _, err := adapter.CollectStaticInfo(); err != nil {
			t.Fatalf("CollectStaticInfo() error = %v", err)
		}

		request, ok := server.findRequest(http.MethodGet, defaultServiceRoot+"/Systems/system")
		if !ok {
			t.Fatal("expected system member request")
		}
		if !strings.HasPrefix(request.authorization, "Basic ") {
			t.Fatalf("expected basic fallback request, got %q", request.authorization)
		}
	})
}

func TestOpenBMCAdapterHonorsSessionOnlyFailure(t *testing.T) {
	t.Parallel()

	server := newOpenBMCTestServer(t, openBMCTestServerOptions{
		sessionSupported: false,
	})
	adapter := NewOpenBMCAdapter(server.config(), NewTransport(server.config(), TransportOptions{
		AuthMode: AuthModeSessionOnly,
		Client:   server.client(),
	}, nil))

	_, err := adapter.CollectStaticInfo()
	if err == nil {
		t.Fatal("expected session-only request to fail")
	}

	transportErr, ok := err.(*TransportError)
	if !ok {
		t.Fatalf("expected TransportError, got %T", err)
	}
	if transportErr.FailureCode != "SESSION_UNSUPPORTED" {
		t.Fatalf("unexpected failure code: %s", transportErr.FailureCode)
	}
}

type capturedRequest struct {
	method        string
	path          string
	authorization string
	token         string
}

type openBMCTestServerOptions struct {
	sessionSupported bool
	returnToken      bool
	sessionTimeout   int64
}

type openBMCTestServer struct {
	server   *httptest.Server
	username string
	password string

	mu          sync.Mutex
	requests    []capturedRequest
	validTokens map[string]string
	tokenID     int
}

func newOpenBMCTestServer(t *testing.T, options openBMCTestServerOptions) *openBMCTestServer {
	t.Helper()

	mock := &openBMCTestServer{
		username:   "admin",
		password:   "password",
		validTokens: make(map[string]string),
	}

	fixtures := map[string]map[string]any{
		defaultServiceRoot + "/Systems": {
			"Members": []map[string]any{{"@odata.id": defaultServiceRoot + "/Systems/system"}},
		},
		defaultServiceRoot + "/Systems/system": {
			"@odata.id":    defaultServiceRoot + "/Systems/system",
			"Manufacturer": "OpenBMC",
			"SerialNumber": "OBMC-001",
			"Model":        "OpenBMC Reference Board",
			"PowerState":   "On",
		},
		defaultServiceRoot + "/Managers": {
			"Members": []map[string]any{{"@odata.id": defaultServiceRoot + "/Managers/bmc"}},
		},
		defaultServiceRoot + "/Managers/bmc": {
			"@odata.id": defaultServiceRoot + "/Managers/bmc",
			"EthernetInterfaces": map[string]any{
				"@odata.id": defaultServiceRoot + "/Managers/bmc/EthernetInterfaces",
			},
		},
		defaultServiceRoot + "/Managers/bmc/EthernetInterfaces": {
			"Members": []map[string]any{{"@odata.id": defaultServiceRoot + "/Managers/bmc/EthernetInterfaces/eth0"}},
		},
		defaultServiceRoot + "/Managers/bmc/EthernetInterfaces/eth0": {
			"MACAddress": "02:00:00:00:00:01",
		},
		defaultServiceRoot + "/Chassis": {
			"Members": []map[string]any{{"@odata.id": defaultServiceRoot + "/Chassis/chassis"}},
		},
		defaultServiceRoot + "/Chassis/chassis": {
			"@odata.id": defaultServiceRoot + "/Chassis/chassis",
			"Thermal": map[string]any{
				"@odata.id": defaultServiceRoot + "/Chassis/chassis/Thermal",
			},
		},
		defaultServiceRoot + "/Chassis/chassis/Thermal": {
			"Temperatures": []map[string]any{{"ReadingCelsius": 31}},
		},
	}

	handler := http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		mock.capture(request)

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
			if payload["UserName"] != mock.username || payload["Password"] != mock.password {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}

			mock.mu.Lock()
			mock.tokenID++
			token := fmt.Sprintf("session-token-%d", mock.tokenID)
			location := fmt.Sprintf("%s/%d", sessionsPath, mock.tokenID)
			mock.validTokens[token] = location
			mock.mu.Unlock()

			writer.Header().Set("Content-Type", "application/json")
			writer.Header().Set("Location", location)
			if options.returnToken {
				writer.Header().Set("X-Auth-Token", token)
			}
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"@odata.id": location,
			})
		case request.Method == http.MethodGet && request.URL.Path == sessionServicePath:
			if !mock.validToken(request.Header.Get("X-Auth-Token")) {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}
			writer.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"SessionTimeout": options.sessionTimeout,
			})
		default:
			if !mock.authorized(request) {
				writer.Header().Set("WWW-Authenticate", `Basic realm="openbmc-test"`)
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}

			payload, ok := fixtures[request.URL.Path]
			if !ok {
				writer.WriteHeader(http.StatusNotFound)
				return
			}
			writer.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(writer).Encode(payload)
		}
	})

	mock.server = httptest.NewTLSServer(handler)
	t.Cleanup(mock.server.Close)
	return mock
}

func (s *openBMCTestServer) config() Config {
	return Config{
		Endpoint: s.server.URL,
		Username: s.username,
		Password: s.password,
		Insecure: true,
	}
}

func (s *openBMCTestServer) client() *http.Client {
	client := s.server.Client()
	if transport, ok := client.Transport.(*http.Transport); ok {
		cloned := transport.Clone()
		cloned.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
		client.Transport = cloned
	}
	return client
}

func (s *openBMCTestServer) capture(request *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.requests = append(s.requests, capturedRequest{
		method:        request.Method,
		path:          request.URL.Path,
		authorization: request.Header.Get("Authorization"),
		token:         request.Header.Get("X-Auth-Token"),
	})
}

func (s *openBMCTestServer) findRequest(method, path string) (capturedRequest, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()

	for _, request := range s.requests {
		if request.method == method && request.path == path {
			return request, true
		}
	}
	return capturedRequest{}, false
}

func (s *openBMCTestServer) authorized(request *http.Request) bool {
	if token := request.Header.Get("X-Auth-Token"); token != "" {
		return s.validToken(token)
	}
	return request.Header.Get("Authorization") == basicAuth(s.username, s.password)
}

func (s *openBMCTestServer) validToken(token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.validTokens[token]
	return ok
}

func (s *openBMCTestServer) closeSessions() error {
	return NewTransport(s.config(), TransportOptions{
		AuthMode: AuthModeSessionPreferred,
		Client:   s.client(),
	}, NewSessionManager()).Close(context.Background())
}
