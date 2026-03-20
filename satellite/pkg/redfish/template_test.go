package redfish

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadTemplates(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	templatePath := filepath.Join(dir, "vendor-a.json")
	templateContent := `{
	  "name": "vendor-a",
	  "priority": 200,
	  "resources": {
	    "systems": "/redfish/v1/Systems",
	    "managers": "/redfish/v1/Managers",
	    "chassis": "/redfish/v1/Chassis"
	  },
	  "fields": {
	    "system_serial": "SerialNumber",
	    "system_model": "Model"
	  }
	}`
	if err := os.WriteFile(templatePath, []byte(templateContent), 0o600); err != nil {
		t.Fatalf("write template: %v", err)
	}

	templates, err := LoadTemplates(dir)
	if err != nil {
		t.Fatalf("LoadTemplates() error = %v", err)
	}
	if len(templates) != 1 {
		t.Fatalf("expected 1 template, got %d", len(templates))
	}
	if templates[0].Name != "vendor-a" {
		t.Fatalf("expected template name vendor-a, got %s", templates[0].Name)
	}
	if templates[0].Resources.Systems.First() != "/redfish/v1/Systems" {
		t.Fatalf("unexpected systems path: %s", templates[0].Resources.Systems.First())
	}
}

func TestLoadTemplatesSupportsFallbackPathArrays(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	templatePath := filepath.Join(dir, "vendor-b.json")
	templateContent := `{
	  "name": "vendor-b",
	  "resources": {
	    "chassis": ["/redfish/v1/Chassis", "/redfish/v1/ThermalChassis"]
	  },
	  "fields": {
	    "system_model": ["Model", "Oem.Vendor.ProductName"]
	  }
	}`
	if err := os.WriteFile(templatePath, []byte(templateContent), 0o600); err != nil {
		t.Fatalf("write template: %v", err)
	}

	templates, err := LoadTemplates(dir)
	if err != nil {
		t.Fatalf("LoadTemplates() error = %v", err)
	}
	if got := templates[0].Resources.Chassis.Candidates(); len(got) != 2 || got[1] != "/redfish/v1/ThermalChassis" {
		t.Fatalf("unexpected chassis fallbacks: %#v", got)
	}
	if got := templates[0].Fields.SystemModel.Candidates(); len(got) != 2 || got[1] != "Oem.Vendor.ProductName" {
		t.Fatalf("unexpected model field fallbacks: %#v", got)
	}
}

func TestTemplateMatches(t *testing.T) {
	t.Parallel()

	template := Template{
		Name: "dell-idrac",
		Match: TemplateMatch{
			ManufacturerPatterns: []string{"Dell", "iDRAC"},
			ModelPatterns:        []string{"PowerEdge", "R7[0-9]{2}"},
		},
	}

	if !template.Matches("Dell Inc.", "PowerEdge R760") {
		t.Fatalf("expected template to match Dell PowerEdge fingerprint")
	}
	if !template.Matches("Dell Inc.", "") {
		t.Fatalf("expected template to match vendor-only fingerprint")
	}
	if template.Matches("Lenovo", "ThinkSystem SR650") {
		t.Fatalf("did not expect template to match Lenovo fingerprint")
	}
}
