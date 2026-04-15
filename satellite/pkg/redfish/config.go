package redfish

import (
	"crypto/tls"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

const defaultServiceRoot = "/redfish/v1"

// Config centralizes Redfish endpoint and adapter settings.
type Config struct {
	Endpoint              string
	Username              string
	Password              string
	Insecure              bool
	TemplateDir           string
	TemplateName          string
	AuthMode              AuthMode
	SessionTTLSecondsMax  int64
}

func loadConfigFromEnv() (Config, bool) {
	endpoint := strings.TrimSpace(os.Getenv("LCM_BMC_IP"))
	if endpoint == "" {
		return Config{Insecure: true}, true
	}

	if !strings.HasPrefix(endpoint, "https://") && !strings.HasPrefix(endpoint, "http://") {
		endpoint = "https://" + endpoint
	}

	return Config{
		Endpoint:             strings.TrimRight(endpoint, "/"),
		Username:             strings.TrimSpace(os.Getenv("LCM_BMC_USER")),
		Password:             os.Getenv("LCM_BMC_PASSWORD"),
		Insecure:             parseBoolDefault(os.Getenv("LCM_BMC_INSECURE"), true),
		TemplateDir:          strings.TrimSpace(os.Getenv("LCM_REDFISH_TEMPLATE_DIR")),
		TemplateName:         strings.TrimSpace(os.Getenv("LCM_REDFISH_TEMPLATE_NAME")),
		AuthMode:             ParseAuthMode(os.Getenv("LCM_BMC_AUTH_MODE")),
		SessionTTLSecondsMax: parseInt64Default("LCM_BMC_SESSION_TTL_SECONDS_MAX", os.Getenv("LCM_BMC_SESSION_TTL_SECONDS_MAX"), defaultSessionTTLSecondsMax),
	}, false
}

func parseBoolDefault(raw string, fallback bool) bool {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "1", "true", "yes", "y", "on":
		return true
	case "0", "false", "no", "n", "off":
		return false
	default:
		return fallback
	}
}

func parseInt64Default(name string, raw string, fallback int64) int64 {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return fallback
	}

	parsed, err := strconv.ParseInt(trimmed, 10, 64)
	if err != nil || parsed <= 0 {
		log.Printf("⚠️ Invalid %s=%q, falling back to %d", name, raw, fallback)
		return fallback
	}
	return parsed
}

func (c Config) HTTPClient() *http.Client {
	return &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: c.Insecure},
		},
	}
}
