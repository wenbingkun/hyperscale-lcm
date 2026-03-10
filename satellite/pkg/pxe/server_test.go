package pxe

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHandleIpxeScript(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/ipxe?mac=AA:BB:CC:DD:EE:FF", nil)
	w := httptest.NewRecorder()

	handleIpxeScript(w, req)
	res := w.Result()
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		t.Errorf("expected status OK; got %v", res.Status)
	}

	data, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatalf("expected error to be nil got %v", err)
	}
	body := string(data)

	if !strings.HasPrefix(body, "#!ipxe") {
		t.Errorf("expected script to start with #!ipxe, got '%s'", body)
	}

	if !strings.Contains(body, "AA:BB:CC:DD:EE:FF") {
		t.Errorf("expected script to contain MAC address, got '%s'", body)
	}
}

func TestHandleIpxeScriptMissingMac(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/ipxe", nil) // missing mac parameter
	w := httptest.NewRecorder()

	handleIpxeScript(w, req)
	res := w.Result()
	defer res.Body.Close()

	if res.StatusCode != http.StatusBadRequest {
		t.Errorf("expected status BadRequest; got %v", res.Status)
	}
}

func TestHandleCloudInit(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/cloud-init/user-data", nil)
	w := httptest.NewRecorder()

	handleCloudInit(w, req)
	res := w.Result()
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		t.Errorf("expected status OK; got %v", res.Status)
	}

	data, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatalf("expected error to be nil got %v", err)
	}

	if !strings.Contains(string(data), "#cloud-config") {
		t.Errorf("expected response to be a cloud config payload")
	}
}

func TestHandleMetaData(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/cloud-init/meta-data", nil)
	w := httptest.NewRecorder()

	handleMetaData(w, req)
	res := w.Result()
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		t.Errorf("expected status OK; got %v", res.Status)
	}

	data, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatalf("expected error to be nil got %v", err)
	}

	if !strings.Contains(string(data), "instance-id") {
		t.Errorf("expected response to contain instance-id")
	}
}
