package redfish

import (
	"crypto/sha256"
	"encoding/hex"
	"log"
	"strings"
	"sync"
	"time"
)

const (
	defaultSessionTTLSecondsMax = int64(1800)
)

type AuthMode string

const (
	AuthModeBasicOnly        AuthMode = "BASIC_ONLY"
	AuthModeSessionPreferred AuthMode = "SESSION_PREFERRED"
	AuthModeSessionOnly      AuthMode = "SESSION_ONLY"
)

func ParseAuthMode(raw string) AuthMode {
	switch strings.TrimSpace(strings.ToUpper(raw)) {
	case string(AuthModeBasicOnly):
		return AuthModeBasicOnly
	case string(AuthModeSessionOnly):
		return AuthModeSessionOnly
	case "", string(AuthModeSessionPreferred):
		return AuthModeSessionPreferred
	default:
		log.Printf("⚠️ Unknown Redfish auth mode %q, falling back to %s", raw, AuthModeSessionPreferred)
		return AuthModeSessionPreferred
	}
}

type SessionKey struct {
	Endpoint     string
	Username     string
	PasswordHash string
	Insecure     bool
}

type CachedSession struct {
	Token     string
	SessionURI string
	ExpiresAt time.Time
}

func (s CachedSession) isExpired(now time.Time) bool {
	return s.ExpiresAt.IsZero() || !s.ExpiresAt.After(now)
}

type SessionFactory func() (CachedSession, error)

type SessionManager struct {
	mu       sync.Mutex
	sessions map[SessionKey]CachedSession
	locks    map[SessionKey]*sync.Mutex
	now      func() time.Time
}

func NewSessionManager() *SessionManager {
	return &SessionManager{
		sessions: make(map[SessionKey]CachedSession),
		locks:    make(map[SessionKey]*sync.Mutex),
		now:      time.Now,
	}
}

func NewSessionKey(config Config) SessionKey {
	return SessionKey{
		Endpoint:     normalizeEndpoint(config.Endpoint),
		Username:     normalizeUsername(config.Username),
		PasswordHash: hashSecret(config.Password),
		Insecure:     config.Insecure,
	}
}

func (m *SessionManager) GetValid(key SessionKey) (CachedSession, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.getValidLocked(key)
}

func (m *SessionManager) GetOrCreate(key SessionKey, factory SessionFactory) (CachedSession, error) {
	if session, ok := m.GetValid(key); ok {
		return session, nil
	}

	lock := m.lockFor(key)
	lock.Lock()
	defer lock.Unlock()

	if session, ok := m.GetValid(key); ok {
		return session, nil
	}

	session, err := factory()
	if err != nil {
		return CachedSession{}, err
	}

	m.mu.Lock()
	m.sessions[key] = session
	m.mu.Unlock()
	return session, nil
}

func (m *SessionManager) Invalidate(key SessionKey) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.sessions, key)
}

func (m *SessionManager) Snapshot() map[SessionKey]CachedSession {
	m.mu.Lock()
	defer m.mu.Unlock()

	snapshot := make(map[SessionKey]CachedSession, len(m.sessions))
	for key, session := range m.sessions {
		snapshot[key] = session
	}
	return snapshot
}

func (m *SessionManager) getValidLocked(key SessionKey) (CachedSession, bool) {
	session, ok := m.sessions[key]
	if !ok {
		return CachedSession{}, false
	}
	if session.isExpired(m.now()) {
		delete(m.sessions, key)
		return CachedSession{}, false
	}
	return session, true
}

func (m *SessionManager) lockFor(key SessionKey) *sync.Mutex {
	m.mu.Lock()
	defer m.mu.Unlock()

	lock, ok := m.locks[key]
	if ok {
		return lock
	}

	lock = &sync.Mutex{}
	m.locks[key] = lock
	return lock
}

func normalizeEndpoint(endpoint string) string {
	return strings.TrimRight(strings.TrimSpace(endpoint), "/")
}

func normalizeUsername(username string) string {
	return strings.TrimSpace(username)
}

func hashSecret(secret string) string {
	sum := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(sum[:])
}
