package pxe

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestImageStoreSaveListAndDelete(t *testing.T) {
	store, err := NewImageStore(t.TempDir())
	if err != nil {
		t.Fatalf("expected store to be created, got %v", err)
	}

	image, err := store.Save("ubuntu-24.04.iso", bytes.NewBufferString("iso-data"))
	if err != nil {
		t.Fatalf("expected save to succeed, got %v", err)
	}
	if image.Name != "ubuntu-24.04.iso" {
		t.Fatalf("expected saved image name, got %q", image.Name)
	}

	images, err := store.List()
	if err != nil {
		t.Fatalf("expected list to succeed, got %v", err)
	}
	if len(images) != 1 {
		t.Fatalf("expected one image, got %d", len(images))
	}

	if err := store.Delete("ubuntu-24.04.iso"); err != nil {
		t.Fatalf("expected delete to succeed, got %v", err)
	}
	images, err = store.List()
	if err != nil {
		t.Fatalf("expected list after delete to succeed, got %v", err)
	}
	if len(images) != 0 {
		t.Fatalf("expected no images after delete, got %d", len(images))
	}
}

func TestImageStoreRejectsPathTraversalNames(t *testing.T) {
	store, err := NewImageStore(t.TempDir())
	if err != nil {
		t.Fatalf("expected store to be created, got %v", err)
	}

	_, err = store.Save("../secrets.txt", bytes.NewBufferString("bad"))
	if !errors.Is(err, errInvalidImageName) {
		t.Fatalf("expected invalid image name, got %v", err)
	}

	if _, statErr := os.Stat(filepath.Join(store.dir, "../secrets.txt")); !errors.Is(statErr, os.ErrNotExist) {
		t.Fatalf("expected no file outside store, got %v", statErr)
	}
}
