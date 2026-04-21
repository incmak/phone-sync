package store

import (
	"path/filepath"
	"testing"
)

func TestBoltPutGetDelete(t *testing.T) {
	dir := t.TempDir()
	s, err := OpenBolt(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer s.Close()

	if err := s.Put("bucket1", "key1", []byte("hello")); err != nil {
		t.Fatalf("put: %v", err)
	}
	got, err := s.Get("bucket1", "key1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if string(got) != "hello" {
		t.Fatalf("expected hello, got %q", string(got))
	}
	if err := s.Delete("bucket1", "key1"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	got, err = s.Get("bucket1", "key1")
	if err != nil {
		t.Fatalf("get after delete: %v", err)
	}
	if got != nil {
		t.Fatalf("expected nil after delete, got %q", string(got))
	}
}
