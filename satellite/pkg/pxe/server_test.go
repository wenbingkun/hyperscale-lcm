package pxe

import (
	"bytes"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestHandleIpxeScript(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/ipxe?mac=AA:BB:CC:DD:EE:FF&hostname=node-1", nil)
	w := httptest.NewRecorder()

	newIpxeHandler(ConfigFromEnv(ServerConfig{
		HTTPAddr:          ":8090",
		BootServerHost:    "10.0.0.15",
		InstallRepoURL:    "http://mirror.local/rocky/9/BaseOS/x86_64/os",
		InstallKernelURL:  "http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/vmlinuz",
		InstallInitrdURL:  "http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/initrd.img",
		InstallKernelArgs: "console=ttyS1,115200n8",
	})).ServeHTTP(w, req)
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
	if !strings.Contains(body, "http://10.0.0.15:8090/kickstart?mac=AA%3ABB%3ACC%3ADD%3AEE%3AFF&hostname=node-1") {
		t.Errorf("expected script to reference node-specific kickstart URL, got '%s'", body)
	}
	if !strings.Contains(body, "inst.repo=http://mirror.local/rocky/9/BaseOS/x86_64/os") {
		t.Errorf("expected script to contain repo URL, got '%s'", body)
	}
}

func TestHandleIpxeScriptMissingMac(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/ipxe", nil)
	w := httptest.NewRecorder()

	newIpxeHandler(ConfigFromEnv(ServerConfig{
		HTTPAddr:         ":8090",
		BootServerHost:   "10.0.0.15",
		InstallRepoURL:   "http://mirror.local/rocky/9/BaseOS/x86_64/os",
		InstallKernelURL: "http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/vmlinuz",
		InstallInitrdURL: "http://mirror.local/rocky/9/BaseOS/x86_64/os/images/pxeboot/initrd.img",
	})).ServeHTTP(w, req)
	res := w.Result()
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		t.Errorf("expected status OK; got %v", res.Status)
	}

	data, err := io.ReadAll(res.Body)
	if err != nil {
		t.Fatalf("expected error to be nil got %v", err)
	}

	if !strings.Contains(string(data), "${net0/mac}") {
		t.Errorf("expected script to fall back to iPXE MAC variable")
	}
	if !strings.Contains(string(data), "/kickstart?mac=${net0/mac}&hostname=${hostname}") {
		t.Errorf("expected script to keep node-specific kickstart placeholders")
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

func TestImageAPIUploadListAndDelete(t *testing.T) {
	handler, err := newPXEHTTPHandler(ServerConfig{
		HTTPAddr: ":8090",
		ImageDir: t.TempDir(),
	})
	if err != nil {
		t.Fatalf("expected handler to be created, got %v", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", "ubuntu-24.04.iso")
	if err != nil {
		t.Fatalf("expected multipart file, got %v", err)
	}
	if _, err := part.Write([]byte("iso-data")); err != nil {
		t.Fatalf("expected write to succeed, got %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("expected writer close to succeed, got %v", err)
	}

	uploadReq := httptest.NewRequest(http.MethodPost, "/api/images", &body)
	uploadReq.Header.Set("Content-Type", writer.FormDataContentType())
	uploadResp := httptest.NewRecorder()
	handler.ServeHTTP(uploadResp, uploadReq)

	if uploadResp.Code != http.StatusCreated {
		t.Fatalf("expected created response, got %d", uploadResp.Code)
	}

	listReq := httptest.NewRequest(http.MethodGet, "/api/images", nil)
	listResp := httptest.NewRecorder()
	handler.ServeHTTP(listResp, listReq)

	if listResp.Code != http.StatusOK {
		t.Fatalf("expected ok list response, got %d", listResp.Code)
	}
	if !strings.Contains(listResp.Body.String(), "ubuntu-24.04.iso") {
		t.Fatalf("expected uploaded image to be listed, got %s", listResp.Body.String())
	}

	deleteReq := httptest.NewRequest(http.MethodDelete, "/api/images/ubuntu-24.04.iso", nil)
	deleteResp := httptest.NewRecorder()
	handler.ServeHTTP(deleteResp, deleteReq)

	if deleteResp.Code != http.StatusNoContent {
		t.Fatalf("expected no content delete response, got %d", deleteResp.Code)
	}
}

func TestKickstartEndpointRendersNodeSpecificTemplate(t *testing.T) {
	templatePath := filepath.Join(t.TempDir(), "kickstart.tmpl")
	templateBody := `hostname={{ .Hostname }}
mac={{ .MAC }}
repo={{ .RepoURL }}
satellite={{ .SatelliteAddress }}
`
	if err := os.WriteFile(templatePath, []byte(templateBody), 0o600); err != nil {
		t.Fatalf("expected template to be written, got %v", err)
	}

	handler, err := newPXEHTTPHandler(ServerConfig{
		HTTPAddr:          ":8090",
		ImageDir:          t.TempDir(),
		BootServerHost:    "10.0.0.15",
		InstallRepoURL:    "http://mirror.local/rocky/9/BaseOS/x86_64/os",
		KickstartTemplate: templatePath,
	})
	if err != nil {
		t.Fatalf("expected handler to be created, got %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/kickstart?mac=AA:BB:CC:DD:EE:FF&hostname=node-01", nil)
	resp := httptest.NewRecorder()
	handler.ServeHTTP(resp, req)

	if resp.Code != http.StatusOK {
		t.Fatalf("expected kickstart response, got %d", resp.Code)
	}

	body := resp.Body.String()
	if !strings.Contains(body, "hostname=node-01") {
		t.Fatalf("expected hostname to be rendered, got %s", body)
	}
	if !strings.Contains(body, "mac=AA:BB:CC:DD:EE:FF") {
		t.Fatalf("expected MAC to be rendered, got %s", body)
	}
	if !strings.Contains(body, "satellite=10.0.0.15:8090") {
		t.Fatalf("expected satellite address to be rendered, got %s", body)
	}
}

func TestKickstartEndpointFallsBackToMacDerivedHostname(t *testing.T) {
	handler, err := newPXEHTTPHandler(ServerConfig{
		HTTPAddr:       ":8090",
		ImageDir:       t.TempDir(),
		BootServerHost: "10.0.0.15",
		InstallRepoURL: "http://mirror.local/rocky/9/BaseOS/x86_64/os",
	})
	if err != nil {
		t.Fatalf("expected handler to be created, got %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/kickstart?mac=AA:BB:CC:DD:EE:FF", nil)
	resp := httptest.NewRecorder()
	handler.ServeHTTP(resp, req)

	if resp.Code != http.StatusOK {
		t.Fatalf("expected kickstart response, got %d", resp.Code)
	}
	if !strings.Contains(resp.Body.String(), "network --bootproto=dhcp --device=link --activate --hostname=node-aabbccddeeff") {
		t.Fatalf("expected derived hostname in kickstart, got %s", resp.Body.String())
	}
}

func TestNewPXEHTTPHandlerFailsForMissingKickstartTemplate(t *testing.T) {
	_, err := newPXEHTTPHandler(ServerConfig{
		HTTPAddr:          ":8090",
		ImageDir:          t.TempDir(),
		KickstartTemplate: filepath.Join(t.TempDir(), "missing-kickstart.tmpl"),
	})
	if err == nil {
		t.Fatalf("expected missing kickstart template to fail handler creation")
	}
}
