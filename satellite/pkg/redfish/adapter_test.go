package redfish

import "testing"

func TestAdapterRegistryBuildAutoDetectsTemplate(t *testing.T) {
	t.Parallel()

	registry := &AdapterRegistry{
		config: Config{
			Endpoint: "https://bmc.example",
			Insecure: true,
		},
		templates: []Template{
			{
				Name: "dell-idrac",
				Match: TemplateMatch{
					ManufacturerPatterns: []string{"Dell"},
					ModelPatterns:        []string{"PowerEdge"},
				},
			},
		},
		fingerprintDetector: func() (string, string, error) {
			return "Dell Inc.", "PowerEdge R760", nil
		},
	}

	adapter, err := registry.Build()
	if err != nil {
		t.Fatalf("Build() error = %v", err)
	}
	if adapter.Name() != "template:dell-idrac" {
		t.Fatalf("expected template adapter, got %s", adapter.Name())
	}
}
