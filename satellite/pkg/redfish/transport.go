package redfish

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

const (
	sessionServicePath = defaultServiceRoot + "/SessionService"
	sessionsPath       = sessionServicePath + "/Sessions"
	sessionCloseBudget = 2 * time.Second
)

type TransportOptions struct {
	AuthMode             AuthMode
	SessionTTLSecondsMax int64
	Client               *http.Client
}

type TransportRequest struct {
	Method   string
	Path     string
	Body     []byte
	Headers  map[string]string
	ReadOnly bool
}

type TransportResponse struct {
	StatusCode int
	Headers    http.Header
	Body       []byte
}

func (r *TransportResponse) JSON() (map[string]any, error) {
	if len(r.Body) == 0 {
		return nil, nil
	}

	var payload map[string]any
	if err := json.Unmarshal(r.Body, &payload); err != nil {
		return nil, err
	}
	return payload, nil
}

type TransportError struct {
	FailureCode string
	StatusCode  int
	Message     string
}

func (e *TransportError) Error() string {
	return e.Message
}

type Transport struct {
	config   Config
	options  TransportOptions
	sessions *SessionManager
	client   *http.Client
}

func NewTransport(config Config, options TransportOptions, sessions *SessionManager) *Transport {
	if sessions == nil {
		sessions = NewSessionManager()
	}
	if options.AuthMode == "" {
		options.AuthMode = AuthModeBasicOnly
	}
	if options.SessionTTLSecondsMax <= 0 {
		options.SessionTTLSecondsMax = defaultSessionTTLSecondsMax
	}

	client := options.Client
	if client == nil {
		client = config.HTTPClient()
	}

	return &Transport{
		config:   config,
		options:  options,
		sessions: sessions,
		client:   client,
	}
}

func (t *Transport) Do(ctx context.Context, req TransportRequest) (*TransportResponse, error) {
	if ctx == nil {
		ctx = context.Background()
	}

	method := strings.TrimSpace(strings.ToUpper(req.Method))
	if method == "" {
		method = http.MethodGet
	}

	req.Method = method
	authMode := t.options.AuthMode
	if authMode == AuthModeBasicOnly {
		return t.executeWithBasic(ctx, req)
	}

	key := NewSessionKey(t.config)
	if _, err := t.sessions.GetOrCreate(key, func() (CachedSession, error) {
		return t.createSession(ctx)
	}); err != nil {
		if authMode == AuthModeSessionOnly || !isSessionFallbackCandidate(err) {
			return nil, err
		}
		log.Printf("ℹ️ Redfish session auth unavailable for %s, falling back to Basic: %v",
			t.config.Endpoint, err)
		return t.executeWithBasic(ctx, req)
	}

	return t.executeWithSession(ctx, req, key, true)
}

func (t *Transport) Close(ctx context.Context) error {
	if ctx == nil {
		ctx = context.Background()
	}

	if deadline, ok := ctx.Deadline(); !ok || time.Until(deadline) > sessionCloseBudget {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, sessionCloseBudget)
		defer cancel()
	}

	var closeErr error
	for key, session := range t.sessions.Snapshot() {
		if err := t.deleteSession(ctx, session); err != nil {
			log.Printf("⚠️ Best-effort Redfish session cleanup failed for %s: %v", key.Endpoint, err)
			if closeErr == nil {
				closeErr = err
			}
		}
		t.sessions.Invalidate(key)
		if ctx.Err() != nil {
			return ctx.Err()
		}
	}

	return closeErr
}

func (t *Transport) executeWithSession(
	ctx context.Context,
	req TransportRequest,
	key SessionKey,
	allowReauth bool,
) (*TransportResponse, error) {
	session, err := t.sessions.GetOrCreate(key, func() (CachedSession, error) {
		return t.createSession(ctx)
	})
	if err != nil {
		return nil, err
	}

	response, err := t.send(ctx, req, map[string]string{
		"X-Auth-Token": session.Token,
	})
	if err != nil {
		return nil, err
	}
	if response.StatusCode == http.StatusUnauthorized && allowReauth && req.ReadOnly {
		t.sessions.Invalidate(key)
		if _, err := t.sessions.GetOrCreate(key, func() (CachedSession, error) {
			return t.createSession(ctx)
		}); err != nil {
			return nil, err
		}
		return t.executeWithSession(ctx, req, key, false)
	}
	if !isSuccessStatus(response.StatusCode) {
		return nil, t.asTransportError(req.Method, req.Path, response)
	}
	return response, nil
}

func (t *Transport) executeWithBasic(ctx context.Context, req TransportRequest) (*TransportResponse, error) {
	response, err := t.send(ctx, req, map[string]string{
		"Authorization": basicAuth(t.config.Username, t.config.Password),
	})
	if err != nil {
		return nil, err
	}
	if !isSuccessStatus(response.StatusCode) {
		return nil, t.asTransportError(req.Method, req.Path, response)
	}
	return response, nil
}

func (t *Transport) createSession(ctx context.Context) (CachedSession, error) {
	body, err := json.Marshal(map[string]string{
		"UserName": t.config.Username,
		"Password": t.config.Password,
	})
	if err != nil {
		return CachedSession{}, err
	}

	response, err := t.send(ctx, TransportRequest{
		Method:   http.MethodPost,
		Path:     sessionsPath,
		Body:     body,
		Headers:  map[string]string{"Content-Type": "application/json"},
		ReadOnly: true,
	}, nil)
	if err != nil {
		return CachedSession{}, err
	}

	if !isSuccessStatus(response.StatusCode) {
		return CachedSession{}, t.asSessionCreationError(response)
	}

	token := strings.TrimSpace(response.Headers.Get("X-Auth-Token"))
	if token == "" {
		return CachedSession{}, &TransportError{
			FailureCode: "SESSION_TOKEN_MISSING",
			StatusCode:  http.StatusBadGateway,
			Message:     "Redfish session creation succeeded but response did not include X-Auth-Token.",
		}
	}

	sessionURI := strings.TrimSpace(response.Headers.Get("Location"))
	if sessionURI == "" {
		payload, parseErr := response.JSON()
		if parseErr == nil {
			if value, ok := extractString(payload, "@odata.id"); ok {
				sessionURI = value
			}
		}
	}

	return CachedSession{
		Token:      token,
		SessionURI: sessionURI,
		ExpiresAt:  t.sessions.now().Add(time.Duration(t.resolveSessionTTLSeconds(ctx, token)) * time.Second),
	}, nil
}

func (t *Transport) resolveSessionTTLSeconds(ctx context.Context, token string) int64 {
	response, err := t.send(ctx, TransportRequest{
		Method:   http.MethodGet,
		Path:     sessionServicePath,
		ReadOnly: true,
	}, map[string]string{
		"X-Auth-Token": token,
	})
	if err != nil || !isSuccessStatus(response.StatusCode) {
		return t.options.SessionTTLSecondsMax
	}

	payload, err := response.JSON()
	if err != nil {
		return t.options.SessionTTLSecondsMax
	}

	rawTimeout, ok := payload["SessionTimeout"]
	if !ok {
		return t.options.SessionTTLSecondsMax
	}

	switch typed := rawTimeout.(type) {
	case float64:
		return minInt64(t.options.SessionTTLSecondsMax, int64(typed))
	case int:
		return minInt64(t.options.SessionTTLSecondsMax, int64(typed))
	case int64:
		return minInt64(t.options.SessionTTLSecondsMax, typed)
	default:
		return t.options.SessionTTLSecondsMax
	}
}

func (t *Transport) deleteSession(ctx context.Context, session CachedSession) error {
	if strings.TrimSpace(session.SessionURI) == "" || strings.TrimSpace(session.Token) == "" {
		return nil
	}

	response, err := t.send(ctx, TransportRequest{
		Method: http.MethodDelete,
		Path:   session.SessionURI,
	}, map[string]string{
		"X-Auth-Token": session.Token,
	})
	if err != nil {
		return err
	}
	if !isSuccessStatus(response.StatusCode) {
		return t.asTransportError(http.MethodDelete, session.SessionURI, response)
	}
	return nil
}

func (t *Transport) send(ctx context.Context, req TransportRequest, authHeaders map[string]string) (*TransportResponse, error) {
	body := req.Body
	if body == nil {
		body = []byte{}
	}

	request, err := http.NewRequestWithContext(ctx, req.Method, absoluteURL(t.config.Endpoint, req.Path), bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	for name, value := range req.Headers {
		request.Header.Set(name, value)
	}
	for name, value := range authHeaders {
		request.Header.Set(name, value)
	}

	response, err := t.client.Do(request)
	if err != nil {
		return nil, &TransportError{
			FailureCode: "IO_ERROR",
			StatusCode:  http.StatusBadGateway,
			Message:     fmt.Sprintf("%s %s failed: %v", req.Method, req.Path, err),
		}
	}
	defer response.Body.Close()

	payload, err := io.ReadAll(response.Body)
	if err != nil {
		return nil, err
	}

	return &TransportResponse{
		StatusCode: response.StatusCode,
		Headers:    response.Header.Clone(),
		Body:       payload,
	}, nil
}

func (t *Transport) asSessionCreationError(response *TransportResponse) error {
	err := t.asTransportError(http.MethodPost, sessionsPath, response)
	transportErr, ok := err.(*TransportError)
	if !ok {
		return err
	}
	if transportErr.StatusCode == http.StatusNotFound ||
		transportErr.StatusCode == http.StatusMethodNotAllowed ||
		transportErr.StatusCode == http.StatusNotImplemented {
		return &TransportError{
			FailureCode: "SESSION_UNSUPPORTED",
			StatusCode:  transportErr.StatusCode,
			Message:     transportErr.Message,
		}
	}
	return transportErr
}

func (t *Transport) asTransportError(method, path string, response *TransportResponse) error {
	message := fmt.Sprintf("%s %s returned HTTP %d", method, path, response.StatusCode)
	bodyText := strings.TrimSpace(string(response.Body))
	if bodyText != "" {
		message += ": " + bodyText
	}
	return &TransportError{
		FailureCode: fmt.Sprintf("HTTP_%d", response.StatusCode),
		StatusCode:  response.StatusCode,
		Message:     message,
	}
}

func isSessionFallbackCandidate(err error) bool {
	transportErr, ok := err.(*TransportError)
	if !ok {
		return false
	}
	return transportErr.FailureCode == "SESSION_UNSUPPORTED" || transportErr.FailureCode == "SESSION_TOKEN_MISSING"
}

func isSuccessStatus(statusCode int) bool {
	return statusCode == http.StatusOK ||
		statusCode == http.StatusCreated ||
		statusCode == http.StatusAccepted ||
		statusCode == http.StatusNoContent
}

func basicAuth(username, password string) string {
	raw := username + ":" + password
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(raw))
}

func minInt64(left, right int64) int64 {
	if left < right {
		return left
	}
	return right
}
