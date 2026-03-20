package redfish

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Template defines one importable Redfish mapping profile.
type Template struct {
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Priority    int               `json:"priority"`
	Match       TemplateMatch     `json:"match"`
	Resources   TemplateResources `json:"resources"`
	Fields      TemplateFields    `json:"fields"`
}

type PathSpec []string

type TemplateMatch struct {
	ManufacturerPatterns []string `json:"manufacturer_patterns"`
	ModelPatterns        []string `json:"model_patterns"`
}

type TemplateResources struct {
	Systems                   PathSpec `json:"systems"`
	Managers                  PathSpec `json:"managers"`
	ManagerEthernetInterfaces PathSpec `json:"manager_ethernet_interfaces"`
	Chassis                   PathSpec `json:"chassis"`
	Thermal                   PathSpec `json:"thermal"`
}

type TemplateFields struct {
	SystemSerial             PathSpec `json:"system_serial"`
	SystemModel              PathSpec `json:"system_model"`
	PowerState               PathSpec `json:"power_state"`
	SystemTemperatureCelsius PathSpec `json:"system_temperature_celsius"`
	BmcMac                   PathSpec `json:"bmc_mac"`
}

// LoadTemplates loads template JSON files from a local directory.
func LoadTemplates(dir string) ([]Template, error) {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return nil, nil
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}

	templates := make([]Template, 0, len(entries))
	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}

		path := filepath.Join(dir, entry.Name())
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}

		var template Template
		if err := json.Unmarshal(data, &template); err != nil {
			return nil, fmt.Errorf("parse template %s: %w", path, err)
		}

		template.normalize()
		if template.Name == "" {
			return nil, fmt.Errorf("template %s missing name", path)
		}

		templates = append(templates, template)
	}

	sort.SliceStable(templates, func(i, j int) bool {
		if templates[i].Priority == templates[j].Priority {
			return templates[i].Name < templates[j].Name
		}
		return templates[i].Priority > templates[j].Priority
	})

	return templates, nil
}

func (t Template) Matches(manufacturer, model string) bool {
	manufacturerMatched := matchAnyPattern(manufacturer, t.Match.ManufacturerPatterns)
	modelMatched := matchAnyPattern(model, t.Match.ModelPatterns)

	if strings.TrimSpace(manufacturer) != "" && len(t.Match.ManufacturerPatterns) > 0 && !manufacturerMatched {
		return false
	}
	if strings.TrimSpace(model) != "" && len(t.Match.ModelPatterns) > 0 && !modelMatched {
		return false
	}

	return manufacturerMatched || modelMatched
}

func (t *Template) normalize() {
	if t.Resources.Systems.Empty() {
		t.Resources.Systems = PathSpec{defaultServiceRoot + "/Systems"}
	}
	if t.Resources.Managers.Empty() {
		t.Resources.Managers = PathSpec{defaultServiceRoot + "/Managers"}
	}
	if t.Resources.Chassis.Empty() {
		t.Resources.Chassis = PathSpec{defaultServiceRoot + "/Chassis"}
	}
}

func (p *PathSpec) UnmarshalJSON(data []byte) error {
	if len(data) == 0 || string(data) == "null" {
		*p = nil
		return nil
	}

	var single string
	if err := json.Unmarshal(data, &single); err == nil {
		if trimmed := strings.TrimSpace(single); trimmed != "" {
			*p = PathSpec{trimmed}
		} else {
			*p = nil
		}
		return nil
	}

	var many []string
	if err := json.Unmarshal(data, &many); err == nil {
		normalized := make([]string, 0, len(many))
		for _, candidate := range many {
			if trimmed := strings.TrimSpace(candidate); trimmed != "" {
				normalized = append(normalized, trimmed)
			}
		}
		*p = PathSpec(normalized)
		return nil
	}

	return fmt.Errorf("path spec must be a string or string array")
}

func (p PathSpec) Empty() bool {
	return len(p.Candidates()) == 0
}

func (p PathSpec) First() string {
	candidates := p.Candidates()
	if len(candidates) == 0 {
		return ""
	}
	return candidates[0]
}

func (p PathSpec) Candidates(fallback ...string) []string {
	candidates := make([]string, 0, len(p)+len(fallback))
	seen := make(map[string]struct{}, len(p)+len(fallback))

	appendCandidate := func(value string) {
		trimmed := strings.TrimSpace(value)
		if trimmed == "" {
			return
		}
		if _, ok := seen[trimmed]; ok {
			return
		}
		seen[trimmed] = struct{}{}
		candidates = append(candidates, trimmed)
	}

	for _, candidate := range p {
		appendCandidate(candidate)
	}
	for _, candidate := range fallback {
		appendCandidate(candidate)
	}

	return candidates
}

func matchAnyPattern(value string, patterns []string) bool {
	value = strings.TrimSpace(value)
	if value == "" || len(patterns) == 0 {
		return false
	}

	for _, pattern := range patterns {
		pattern = strings.TrimSpace(pattern)
		if pattern == "" {
			continue
		}

		re, err := regexp.Compile("(?i)" + pattern)
		if err == nil && re.MatchString(value) {
			return true
		}
		if strings.Contains(strings.ToLower(value), strings.ToLower(pattern)) {
			return true
		}
	}

	return false
}
