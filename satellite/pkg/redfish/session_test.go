package redfish

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestSessionManagerReuseAcrossRequests(t *testing.T) {
	t.Parallel()

	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      true,
		sessionTimeout:   600,
	})
	transport := newTestTransport(server, TransportOptions{
		AuthMode: AuthModeSessionPreferred,
	})

	for range 2 {
		response, err := transport.Do(context.Background(), TransportRequest{
			Method:   http.MethodGet,
			Path:     defaultServiceRoot + "/Systems",
			ReadOnly: true,
		})
		if err != nil {
			t.Fatalf("Do() error = %v", err)
		}
		if response.StatusCode != http.StatusOK {
			t.Fatalf("unexpected status: %d", response.StatusCode)
		}
	}

	if got := server.sessionCreations(); got != 1 {
		t.Fatalf("expected 1 session creation, got %d", got)
	}
}

func TestSessionManagerConcurrentSingleBuild(t *testing.T) {
	t.Parallel()

	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      true,
		sessionTimeout:   600,
	})
	transport := newTestTransport(server, TransportOptions{
		AuthMode: AuthModeSessionPreferred,
	})

	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := transport.Do(context.Background(), TransportRequest{
				Method:   http.MethodGet,
				Path:     defaultServiceRoot + "/Systems",
				ReadOnly: true,
			}); err != nil {
				t.Errorf("Do() error = %v", err)
			}
		}()
	}
	wg.Wait()

	if got := server.sessionCreations(); got != 1 {
		t.Fatalf("expected 1 concurrent session creation, got %d", got)
	}
}

func TestSessionExpiryTriggersRebuild(t *testing.T) {
	t.Parallel()

	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      true,
		sessionTimeout:   60,
	})
	now := time.Date(2026, 4, 15, 10, 0, 0, 0, time.UTC)
	manager := NewSessionManager()
	manager.now = func() time.Time { return now }
	transport := newTestTransportWithManager(server, TransportOptions{
		AuthMode: AuthModeSessionPreferred,
	}, manager)

	if _, err := transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	}); err != nil {
		t.Fatalf("first Do() error = %v", err)
	}

	now = now.Add(61 * time.Second)
	if _, err := transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	}); err != nil {
		t.Fatalf("second Do() error = %v", err)
	}

	if got := server.sessionCreations(); got != 2 {
		t.Fatalf("expected 2 session creations after expiry, got %d", got)
	}
}

func TestSessionUnauthorizedRebuildsAndReplays(t *testing.T) {
	t.Parallel()

	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      true,
		sessionTimeout:   600,
		unauthorizedOnce: map[string]bool{
			defaultServiceRoot + "/Systems": true,
		},
	})
	transport := newTestTransport(server, TransportOptions{
		AuthMode: AuthModeSessionPreferred,
	})

	response, err := transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	})
	if err != nil {
		t.Fatalf("Do() error = %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status: %d", response.StatusCode)
	}

	if got := server.sessionCreations(); got != 2 {
		t.Fatalf("expected 2 session creations after 401 reauth, got %d", got)
	}
	if got := server.resourceRequests(defaultServiceRoot + "/Systems"); got != 2 {
		t.Fatalf("expected 2 resource requests after replay, got %d", got)
	}
}

func TestSessionOnlyFailsWhenSessionUnsupported(t *testing.T) {
	t.Parallel()

	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: false,
	})
	transport := newTestTransport(server, TransportOptions{
		AuthMode: AuthModeSessionOnly,
	})

	_, err := transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	})
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
	if got := server.basicRequests(); got != 0 {
		t.Fatalf("expected no basic fallback, got %d requests", got)
	}
}

func TestSessionPreferredFallsBackOnMissingToken(t *testing.T) {
	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      false,
	})
	transport := newTestTransport(server, TransportOptions{
		AuthMode: AuthModeSessionPreferred,
	})

	logBuffer, restore := captureStandardLogger(t)
	defer restore()

	response, err := transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	})
	if err != nil {
		t.Fatalf("Do() error = %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status: %d", response.StatusCode)
	}
	if got := server.basicRequests(); got != 1 {
		t.Fatalf("expected 1 basic fallback request, got %d", got)
	}
	if !strings.Contains(logBuffer.String(), "falling back to Basic") {
		t.Fatalf("expected fallback log, got %q", logBuffer.String())
	}
}

func TestSessionCloseDeletesKnownSessionsBestEffort(t *testing.T) {
	t.Parallel()

	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      true,
		sessionTimeout:   600,
	})
	transport := newTestTransport(server, TransportOptions{
		AuthMode: AuthModeSessionPreferred,
	})

	if _, err := transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	}); err != nil {
		t.Fatalf("Do() error = %v", err)
	}

	if err := transport.Close(context.Background()); err != nil {
		t.Fatalf("Close() error = %v", err)
	}

	if got := server.sessionDeletes(); got != 1 {
		t.Fatalf("expected 1 session delete, got %d", got)
	}
}

func TestBasicOnlyNeverCreatesSession(t *testing.T) {
	t.Parallel()

	server := newSessionProtocolServer(t, sessionProtocolOptions{
		sessionSupported: true,
		returnToken:      true,
	})
	transport := newTestTransport(server, TransportOptions{
		AuthMode: AuthModeBasicOnly,
	})

	response, err := transport.Do(context.Background(), TransportRequest{
		Method:   http.MethodGet,
		Path:     defaultServiceRoot + "/Systems",
		ReadOnly: true,
	})
	if err != nil {
		t.Fatalf("Do() error = %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status: %d", response.StatusCode)
	}
	if got := server.sessionCreations(); got != 0 {
		t.Fatalf("expected no session creation in BASIC_ONLY, got %d", got)
	}
	if got := server.basicRequests(); got != 1 {
		t.Fatalf("expected one basic request, got %d", got)
	}
}

type sessionProtocolOptions struct {
	sessionSupported bool
	returnToken      bool
	sessionTimeout   int64
	unauthorizedOnce map[string]bool
}

type sessionProtocolServer struct {
	server *httptest.Server

	username string
	password string

	mu               sync.Mutex
	tokenCounter     int64
	sessionCreationCount int
	sessionDeleteCount   int
	basicRequestCount    int
	requestCount     map[string]int
	unauthorizedOnce map[string]bool
	validTokens      map[string]string
}

func newSessionProtocolServer(t *testing.T, options sessionProtocolOptions) *sessionProtocolServer {
	t.Helper()

	server := &sessionProtocolServer{
		username:         "admin",
		password:         "password",
		requestCount:     make(map[string]int),
		unauthorizedOnce: make(map[string]bool, len(options.unauthorizedOnce)),
		validTokens:      make(map[string]string),
	}
	for path, enabled := range options.unauthorizedOnce {
		server.unauthorizedOnce[path] = enabled
	}

	handler := http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		server.mu.Lock()
		server.requestCount[request.URL.Path]++
		server.mu.Unlock()

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
			if payload["UserName"] != server.username || payload["Password"] != server.password {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}

			server.mu.Lock()
			server.sessionCreationCount++
			tokenID := atomic.AddInt64(&server.tokenCounter, 1)
			token := fmt.Sprintf("session-token-%d", tokenID)
			sessionURI := fmt.Sprintf("%s/%d", sessionsPath, tokenID)
			server.validTokens[token] = sessionURI
			server.mu.Unlock()

			writer.Header().Set("Content-Type", "application/json")
			writer.Header().Set("Location", sessionURI)
			if options.returnToken {
				writer.Header().Set("X-Auth-Token", token)
			}
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"@odata.id": sessionURI,
			})
		case request.Method == http.MethodDelete && strings.HasPrefix(request.URL.Path, sessionsPath+"/"):
			token := request.Header.Get("X-Auth-Token")
			if !server.tokenMatchesPath(token, request.URL.Path) {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}

			server.mu.Lock()
			server.sessionDeleteCount++
			delete(server.validTokens, token)
			server.mu.Unlock()
			writer.WriteHeader(http.StatusNoContent)
		case request.Method == http.MethodGet && request.URL.Path == sessionServicePath:
			token := request.Header.Get("X-Auth-Token")
			if !server.tokenValid(token) {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}
			writer.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"SessionTimeout": options.sessionTimeout,
			})
		case request.Method == http.MethodGet && request.URL.Path == defaultServiceRoot+"/Systems":
			if unauthorized := server.shouldUnauthorizedOnce(request.URL.Path, request.Header.Get("X-Auth-Token")); unauthorized {
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}
			if !server.authorized(request) {
				writer.Header().Set("WWW-Authenticate", `Basic realm="redfish-fixture"`)
				writer.WriteHeader(http.StatusUnauthorized)
				return
			}

			writer.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(writer).Encode(map[string]any{
				"Members": []map[string]any{
					{"@odata.id": defaultServiceRoot + "/Systems/system"},
				},
			})
		default:
			writer.WriteHeader(http.StatusNotFound)
		}
	})

	testServer := httptest.NewTLSServer(handler)
	server.server = testServer
	t.Cleanup(testServer.Close)
	return server
}

func (s *sessionProtocolServer) sessionCreations() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sessionCreationCount
}

func (s *sessionProtocolServer) sessionDeletes() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sessionDeleteCount
}

func (s *sessionProtocolServer) basicRequests() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.basicRequestCount
}

func (s *sessionProtocolServer) resourceRequests(path string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.requestCount[path]
}

func (s *sessionProtocolServer) authorized(request *http.Request) bool {
	if token := request.Header.Get("X-Auth-Token"); token != "" {
		return s.tokenValid(token)
	}
	if request.Header.Get("Authorization") == basicAuth(s.username, s.password) {
		s.mu.Lock()
		s.basicRequestCount++
		s.mu.Unlock()
		return true
	}
	return false
}

func (s *sessionProtocolServer) tokenValid(token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.validTokens[token]
	return ok
}

func (s *sessionProtocolServer) tokenMatchesPath(token, path string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.validTokens[token] == path
}

func (s *sessionProtocolServer) shouldUnauthorizedOnce(path, token string) bool {
	if token == "" {
		return false
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.unauthorizedOnce[path] {
		return false
	}
	s.unauthorizedOnce[path] = false
	return true
}

func newTestTransport(server *sessionProtocolServer, options TransportOptions) *Transport {
	return newTestTransportWithManager(server, options, nil)
}

func newTestTransportWithManager(server *sessionProtocolServer, options TransportOptions, manager *SessionManager) *Transport {
	client := server.server.Client()
	transport, ok := client.Transport.(*http.Transport)
	if ok {
		transport = transport.Clone()
		transport.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
		client.Transport = transport
	}

	return NewTransport(Config{
		Endpoint: server.server.URL,
		Username: server.username,
		Password: server.password,
		Insecure: true,
	}, TransportOptions{
		AuthMode:             options.AuthMode,
		SessionTTLSecondsMax: maxInt64(options.SessionTTLSecondsMax, defaultSessionTTLSecondsMax),
		Client:               client,
	}, manager)
}

func captureStandardLogger(t *testing.T) (*strings.Builder, func()) {
	t.Helper()

	var buffer strings.Builder
	originalFlags := log.Flags()
	originalWriter := log.Writer()
	log.SetFlags(0)
	log.SetOutput(&buffer)

	return &buffer, func() {
		log.SetFlags(originalFlags)
		log.SetOutput(originalWriter)
	}
}

func maxInt64(left, right int64) int64 {
	if left > right {
		return left
	}
	return right
}
