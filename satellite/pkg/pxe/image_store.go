package pxe

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
)

var errInvalidImageName = errors.New("invalid image name")

type ImageMetadata struct {
	Name                string `json:"name"`
	SizeBytes           int64  `json:"sizeBytes"`
	ContentType         string `json:"contentType"`
	LastModifiedEpochMs int64  `json:"lastModifiedEpochMs"`
}

type ImageStore struct {
	dir string
}

func NewImageStore(dir string) (*ImageStore, error) {
	if strings.TrimSpace(dir) == "" {
		return nil, errors.New("image directory must not be empty")
	}
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, err
	}
	return &ImageStore{dir: dir}, nil
}

func (s *ImageStore) List() ([]ImageMetadata, error) {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return nil, err
	}

	images := make([]ImageMetadata, 0, len(entries))
	for _, entry := range entries {
		if !entry.Type().IsRegular() {
			continue
		}

		info, err := entry.Info()
		if err != nil {
			return nil, err
		}
		images = append(images, metadataFromFileInfo(entry.Name(), info))
	}

	slices.SortFunc(images, func(left, right ImageMetadata) int {
		return strings.Compare(left.Name, right.Name)
	})

	return images, nil
}

func (s *ImageStore) Save(name string, content io.Reader) (ImageMetadata, error) {
	cleanName, err := sanitizeImageName(name)
	if err != nil {
		return ImageMetadata{}, err
	}

	tmpFile, err := os.CreateTemp(s.dir, "upload-*")
	if err != nil {
		return ImageMetadata{}, err
	}
	tmpPath := tmpFile.Name()
	defer os.Remove(tmpPath)

	if _, err := io.Copy(tmpFile, content); err != nil {
		tmpFile.Close()
		return ImageMetadata{}, err
	}
	if err := tmpFile.Close(); err != nil {
		return ImageMetadata{}, err
	}

	target := filepath.Join(s.dir, cleanName)
	if err := os.Rename(tmpPath, target); err != nil {
		return ImageMetadata{}, err
	}

	info, err := os.Stat(target)
	if err != nil {
		return ImageMetadata{}, err
	}

	return metadataFromFileInfo(cleanName, info), nil
}

func (s *ImageStore) Delete(name string) error {
	cleanName, err := sanitizeImageName(name)
	if err != nil {
		return err
	}

	return os.Remove(filepath.Join(s.dir, cleanName))
}

func metadataFromFileInfo(name string, info os.FileInfo) ImageMetadata {
	contentType := mime.TypeByExtension(strings.ToLower(filepath.Ext(name)))
	if contentType == "" {
		contentType = "application/octet-stream"
	}

	return ImageMetadata{
		Name:                name,
		SizeBytes:           info.Size(),
		ContentType:         contentType,
		LastModifiedEpochMs: info.ModTime().UnixMilli(),
	}
}

func sanitizeImageName(name string) (string, error) {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return "", errInvalidImageName
	}
	if trimmed != filepath.Base(trimmed) {
		return "", errInvalidImageName
	}
	if strings.Contains(trimmed, "..") || strings.ContainsAny(trimmed, `/\`) {
		return "", errInvalidImageName
	}
	return trimmed, nil
}

func newPXEHTTPHandler(cfg ServerConfig) (http.Handler, error) {
	store, err := NewImageStore(cfg.ImageDir)
	if err != nil {
		return nil, err
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ipxe", handleIpxeScript)
	mux.HandleFunc("/cloud-init/user-data", handleCloudInit)
	mux.HandleFunc("/cloud-init/meta-data", handleMetaData)
	mux.HandleFunc("/api/images", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			handleListImages(store, w)
		case http.MethodPost:
			handleUploadImage(store, w, r)
		default:
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/api/images/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		name := strings.TrimPrefix(r.URL.Path, "/api/images/")
		handleDeleteImage(store, w, name)
	})

	return mux, nil
}

func handleListImages(store *ImageStore, w http.ResponseWriter) {
	images, err := store.List()
	if err != nil {
		http.Error(w, "failed to list images", http.StatusInternalServerError)
		return
	}

	writeJSON(w, http.StatusOK, images)
}

func handleUploadImage(store *ImageStore, w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(32 << 20); err != nil {
		http.Error(w, "failed to parse multipart payload", http.StatusBadRequest)
		return
	}
	defer func() {
		if r.MultipartForm != nil {
			_ = r.MultipartForm.RemoveAll()
		}
	}()

	file, header, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "missing multipart file field 'file'", http.StatusBadRequest)
		return
	}
	defer file.Close()

	image, err := store.Save(header.Filename, file)
	if err != nil {
		status := http.StatusInternalServerError
		if errors.Is(err, errInvalidImageName) {
			status = http.StatusBadRequest
		}
		http.Error(w, err.Error(), status)
		return
	}

	writeJSON(w, http.StatusCreated, image)
}

func handleDeleteImage(store *ImageStore, w http.ResponseWriter, name string) {
	err := store.Delete(name)
	if err == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	status := http.StatusInternalServerError
	if errors.Is(err, errInvalidImageName) {
		status = http.StatusBadRequest
	} else if errors.Is(err, os.ErrNotExist) {
		status = http.StatusNotFound
	}
	http.Error(w, err.Error(), status)
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}
