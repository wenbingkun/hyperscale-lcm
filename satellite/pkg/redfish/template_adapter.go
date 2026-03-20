package redfish

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// TemplateAdapter executes Redfish reads from an imported JSON mapping.
type TemplateAdapter struct {
	config   Config
	template Template
	client   *http.Client
}

func NewTemplateAdapter(config Config, template Template) *TemplateAdapter {
	return &TemplateAdapter{
		config:   config,
		template: template,
		client:   config.HTTPClient(),
	}
}

func (a *TemplateAdapter) Name() string {
	return "template:" + a.template.Name
}

func (a *TemplateAdapter) CollectStaticInfo() (*Info, error) {
	system, err := a.fetchPrimaryResource(a.template.Resources.Systems)
	if err != nil {
		return nil, err
	}

	bmcMAC := "Unknown"
	manager, err := a.fetchPrimaryResource(a.template.Resources.Managers)
	if err == nil {
		if eth, ethErr := a.fetchManagerEthernet(manager); ethErr == nil {
			if value, ok := extractStringPaths(eth, a.template.Fields.BmcMac, "MACAddress"); ok {
				bmcMAC = value
			}
		}
	}

	serial, _ := extractStringPaths(system, a.template.Fields.SystemSerial, "SerialNumber")
	model, _ := extractStringPaths(system, a.template.Fields.SystemModel, "Model")
	powerState, _ := extractStringPaths(system, a.template.Fields.PowerState, "PowerState")

	return &Info{
		BMCIP:        a.config.Endpoint,
		BMCMAC:       bmcMAC,
		SystemSerial: serial,
		SystemModel:  model,
		PowerState:   powerState,
	}, nil
}

func (a *TemplateAdapter) CollectDynamicTelemetry() (string, int32) {
	system, err := a.fetchPrimaryResource(a.template.Resources.Systems)
	if err != nil {
		return "Unknown", 0
	}

	powerState, ok := extractStringPaths(system, a.template.Fields.PowerState, "PowerState")
	if !ok || powerState == "" {
		powerState = "Unknown"
	}

	tempC := int32(0)
	chassis, err := a.fetchPrimaryResource(a.template.Resources.Chassis)
	if err == nil {
		if thermal, thermalErr := a.fetchThermal(chassis); thermalErr == nil {
			if value, valueOK := extractInt32Paths(thermal,
				a.template.Fields.SystemTemperatureCelsius, "Temperatures.0.ReadingCelsius"); valueOK {
				tempC = value
			}
		}
	}

	return powerState, tempC
}

func (a *TemplateAdapter) fetchPrimaryResource(collectionPath PathSpec) (map[string]any, error) {
	var lastErr error
	for _, candidate := range collectionPath.Candidates() {
		document, err := a.getJSON(candidate)
		if err != nil {
			lastErr = err
			continue
		}
		resource, err := a.unwrapMember(document)
		if err != nil {
			lastErr = err
			continue
		}
		return resource, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("resource path not configured")
}

func (a *TemplateAdapter) fetchManagerEthernet(manager map[string]any) (map[string]any, error) {
	resourceURIs := resolveResourceURIs(
		getLink(manager, "@odata.id"),
		a.template.Resources.ManagerEthernetInterfaces,
		getLink(manager, "EthernetInterfaces"),
	)
	if len(resourceURIs) == 0 {
		return nil, fmt.Errorf("manager ethernet resource not found")
	}

	var lastErr error
	for _, resourceURI := range resourceURIs {
		document, err := a.getJSON(resourceURI)
		if err != nil {
			lastErr = err
			continue
		}
		resource, err := a.unwrapMember(document)
		if err != nil {
			lastErr = err
			continue
		}
		return resource, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("manager ethernet resource not found")
}

func (a *TemplateAdapter) fetchThermal(chassis map[string]any) (map[string]any, error) {
	resourceURIs := resolveResourceURIs(
		getLink(chassis, "@odata.id"),
		a.template.Resources.Thermal,
		getLink(chassis, "Thermal"),
	)
	if len(resourceURIs) == 0 {
		return nil, fmt.Errorf("thermal resource not found")
	}

	var lastErr error
	for _, resourceURI := range resourceURIs {
		document, err := a.getJSON(resourceURI)
		if err != nil {
			lastErr = err
			continue
		}
		return document, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("thermal resource not found")
}

func (a *TemplateAdapter) unwrapMember(document map[string]any) (map[string]any, error) {
	if memberURI := firstMemberURI(document); memberURI != "" {
		return a.getJSON(memberURI)
	}
	return document, nil
}

func (a *TemplateAdapter) getJSON(path string) (map[string]any, error) {
	req, err := http.NewRequest(http.MethodGet, absoluteURL(a.config.Endpoint, path), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	if a.config.Username != "" {
		req.SetBasicAuth(a.config.Username, a.config.Password)
	}

	resp, err := a.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= http.StatusBadRequest {
		return nil, fmt.Errorf("GET %s returned %d", path, resp.StatusCode)
	}

	var payload map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	return payload, nil
}

func firstMemberURI(document map[string]any) string {
	members, ok := document["Members"].([]any)
	if !ok || len(members) == 0 {
		return ""
	}

	first, ok := members[0].(map[string]any)
	if !ok {
		return ""
	}
	uri, _ := first["@odata.id"].(string)
	return uri
}

func getLink(document map[string]any, key string) string {
	raw, ok := document[key]
	if !ok {
		return ""
	}

	switch value := raw.(type) {
	case string:
		return value
	case map[string]any:
		uri, _ := value["@odata.id"].(string)
		return uri
	default:
		return ""
	}
}

func resolveResourceURIs(base string, configured PathSpec, fallback ...string) []string {
	resolved := make([]string, 0)
	seen := make(map[string]struct{})

	appendResolved := func(candidate string) {
		candidate = strings.TrimSpace(candidate)
		if candidate == "" {
			return
		}

		var resolvedCandidate string
		if strings.HasPrefix(candidate, "http://") || strings.HasPrefix(candidate, "https://") {
			resolvedCandidate = candidate
		} else if strings.HasPrefix(candidate, "/") || base == "" {
			resolvedCandidate = candidate
		} else {
			resolvedCandidate = strings.TrimRight(base, "/") + "/" + strings.TrimLeft(candidate, "/")
		}

		if _, ok := seen[resolvedCandidate]; ok {
			return
		}
		seen[resolvedCandidate] = struct{}{}
		resolved = append(resolved, resolvedCandidate)
	}

	for _, candidate := range configured.Candidates() {
		appendResolved(candidate)
	}
	for _, candidate := range fallback {
		appendResolved(candidate)
	}

	return resolved
}

func absoluteURL(endpoint, path string) string {
	if strings.HasPrefix(path, "http://") || strings.HasPrefix(path, "https://") {
		return path
	}
	if strings.HasPrefix(path, "/") {
		return endpoint + path
	}
	return endpoint + "/" + strings.TrimLeft(path, "/")
}

func extractStringPaths(document map[string]any, paths PathSpec, fallback string) (string, bool) {
	for _, path := range paths.Candidates(fallback) {
		value, ok := extractString(document, path)
		if ok {
			return value, true
		}
	}
	return "", false
}

func extractInt32Paths(document map[string]any, paths PathSpec, fallback string) (int32, bool) {
	for _, path := range paths.Candidates(fallback) {
		value, ok := extractInt32(document, path)
		if ok {
			return value, true
		}
	}
	return 0, false
}

func extractString(document map[string]any, path string) (string, bool) {
	value, ok := lookupPath(document, path)
	if !ok {
		return "", false
	}

	switch typed := value.(type) {
	case string:
		return typed, true
	case json.Number:
		return typed.String(), true
	case float64:
		return strconv.FormatFloat(typed, 'f', -1, 64), true
	default:
		return fmt.Sprintf("%v", typed), true
	}
}

func extractInt32(document map[string]any, path string) (int32, bool) {
	value, ok := lookupPath(document, path)
	if !ok {
		return 0, false
	}

	switch typed := value.(type) {
	case float64:
		return int32(typed), true
	case int:
		return int32(typed), true
	case int32:
		return typed, true
	case json.Number:
		parsed, err := typed.Int64()
		if err != nil {
			return 0, false
		}
		return int32(parsed), true
	case string:
		parsed, err := strconv.Atoi(typed)
		if err != nil {
			return 0, false
		}
		return int32(parsed), true
	default:
		return 0, false
	}
}

func lookupPath(document any, path string) (any, bool) {
	current := document
	for _, segment := range strings.Split(path, ".") {
		switch typed := current.(type) {
		case map[string]any:
			next, ok := typed[segment]
			if !ok {
				return nil, false
			}
			current = next
		case []any:
			index, err := strconv.Atoi(segment)
			if err != nil || index < 0 || index >= len(typed) {
				return nil, false
			}
			current = typed[index]
		default:
			return nil, false
		}
	}
	return current, true
}
