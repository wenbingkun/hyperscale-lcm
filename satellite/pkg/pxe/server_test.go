package pxe

import (
	"bytes"
	"io"
	"mime/multipart"
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
	req := httptest.NewRequest(http.MethodGet, "/ipxe", nil)
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

	if !strings.Contains(string(data), "unknown") {
		t.Errorf("expected script to fall back to unknown MAC")
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
