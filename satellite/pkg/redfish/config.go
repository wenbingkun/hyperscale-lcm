package redfish

import (
	"crypto/tls"
	"net/http"
	"os"
	"strings"
	"time"
)

const defaultServiceRoot = "/redfish/v1"

// Config centralizes Redfish endpoint and adapter settings.
type Config struct {
	Endpoint     string
	Username     string
	Password     string
	Insecure     bool
	TemplateDir  string
	TemplateName string
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
		Endpoint:     strings.TrimRight(endpoint, "/"),
		Username:     strings.TrimSpace(os.Getenv("LCM_BMC_USER")),
		Password:     os.Getenv("LCM_BMC_PASSWORD"),
		Insecure:     parseBoolDefault(os.Getenv("LCM_BMC_INSECURE"), true),
		TemplateDir:  strings.TrimSpace(os.Getenv("LCM_REDFISH_TEMPLATE_DIR")),
		TemplateName: strings.TrimSpace(os.Getenv("LCM_REDFISH_TEMPLATE_NAME")),
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

func (c Config) HTTPClient() *http.Client {
	return &http.Client{
		Timeout: 10 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: c.Insecure},
		},
	}
}
