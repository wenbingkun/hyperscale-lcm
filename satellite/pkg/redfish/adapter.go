package redfish

import (
	"context"
	"fmt"
	"log"
	"strings"
)

// Adapter captures one Redfish variant implementation.
type Adapter interface {
	Name() string
	CollectStaticInfo() (*Info, error)
	CollectDynamicTelemetry() (string, int32)
}

// MockAdapter keeps current local-development behavior unchanged.
type MockAdapter struct{}

func (MockAdapter) Name() string {
	return "mock"
}

func (MockAdapter) CollectStaticInfo() (*Info, error) {
	return &Info{
		BMCIP:        "192.168.100.150",
		BMCMAC:       "aa:bb:cc:dd:ee:ff",
		SystemSerial: "SGH1234567X",
		SystemModel:  "PowerEdge R740",
		PowerState:   "Mock-Power-State",
	}, nil
}

func (MockAdapter) CollectDynamicTelemetry() (string, int32) {
	return "On", 35
}

// AdapterRegistry resolves the adapter in a compatibility-first way:
// explicit template selection when configured, otherwise OpenBMC baseline.
type AdapterRegistry struct {
	config              Config
	templates           []Template
	transport           *Transport
	fingerprintDetector func() (string, string, error)
}

func NewAdapterRegistry(config Config, transport *Transport) (*AdapterRegistry, error) {
	if transport == nil {
		transport = NewTransport(config, TransportOptions{
			AuthMode: AuthModeBasicOnly,
		}, nil)
	}

	templates, err := LoadTemplates(config.TemplateDir)
	if err != nil {
		return &AdapterRegistry{
			config:    config,
			transport: transport,
		}, err
	}

	return &AdapterRegistry{
		config:    config,
		templates: templates,
		transport: transport,
	}, nil
}

func (r *AdapterRegistry) Build() (Adapter, error) {
	if strings.TrimSpace(r.config.TemplateName) == "" {
		if template, ok := r.detectTemplate(); ok {
			return NewTemplateAdapter(r.config, template, r.transport), nil
		}
		return NewOpenBMCAdapter(r.config, r.transport), nil
	}

	template, ok := r.findTemplate(r.config.TemplateName)
	if !ok {
		return nil, fmt.Errorf("template %q not found in %s", r.config.TemplateName, r.config.TemplateDir)
	}

	return NewTemplateAdapter(r.config, template, r.transport), nil
}

func (r *AdapterRegistry) findTemplate(name string) (Template, bool) {
	for _, template := range r.templates {
		if strings.EqualFold(template.Name, name) {
			return template, true
		}
	}
	return Template{}, false
}

func (r *AdapterRegistry) detectTemplate() (Template, bool) {
	if len(r.templates) == 0 {
		return Template{}, false
	}

	fingerprintDetector := r.fingerprintDetector
	if fingerprintDetector == nil {
		fingerprintDetector = r.fetchFingerprint
	}

	manufacturer, model, err := fingerprintDetector()
	if err != nil {
		log.Printf("⚠️ Redfish template auto-detection failed: %v", err)
		return Template{}, false
	}

	for _, template := range r.templates {
		if template.Matches(manufacturer, model) {
			log.Printf("🔎 Auto-selected Redfish template %s for manufacturer=%q model=%q",
				template.Name, manufacturer, model)
			return template, true
		}
	}

	log.Printf("ℹ️ No Redfish template matched manufacturer=%q model=%q, using OpenBMC baseline.", manufacturer, model)
	return Template{}, false
}

func (r *AdapterRegistry) fetchFingerprint() (string, string, error) {
	system, err := r.fetchPrimaryResource(defaultServiceRoot + "/Systems")
	if err != nil {
		return "", "", err
	}

	manufacturer, _ := extractString(system, "Manufacturer")
	model, _ := extractString(system, "Model")
	return manufacturer, model, nil
}

func (r *AdapterRegistry) fetchPrimaryResource(collectionPath string) (map[string]any, error) {
	document, err := r.getJSON(collectionPath)
	if err != nil {
		return nil, err
	}
	if memberURI := firstMemberURI(document); memberURI != "" {
		return r.getJSON(memberURI)
	}
	return document, nil
}

func (r *AdapterRegistry) getJSON(path string) (map[string]any, error) {
	resp, err := r.transport.Do(context.Background(), TransportRequest{
		Method:   "GET",
		Path:     path,
		ReadOnly: true,
	})
	if err != nil {
		return nil, err
	}

	return resp.JSON()
}
