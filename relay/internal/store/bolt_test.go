package store

import (
	"os"
	"path/filepath"
	"sync"
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

func TestSnapshotRemainsConsistentDuringConcurrentMutation(t *testing.T) {
	directory := t.TempDir()
	bolt, err := OpenBolt(filepath.Join(directory, "source.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer bolt.Close()
	if err := bolt.Put("records", "before", []byte("present")); err != nil {
		t.Fatal(err)
	}

	var writers sync.WaitGroup
	writers.Add(1)
	go func() {
		defer writers.Done()
		for index := 0; index < 100; index++ {
			_ = bolt.Put("concurrent", string(rune(index+1)), []byte("value"))
		}
	}()
	snapshotPath := filepath.Join(directory, "snapshot.db")
	if err := bolt.Snapshot(snapshotPath); err != nil {
		t.Fatalf("snapshot: %v", err)
	}
	writers.Wait()
	if err := ValidateBolt(snapshotPath); err != nil {
		t.Fatalf("validate snapshot: %v", err)
	}
	snapshot, err := OpenBolt(snapshotPath)
	if err != nil {
		t.Fatal(err)
	}
	defer snapshot.Close()
	value, err := snapshot.Get("records", "before")
	if err != nil || string(value) != "present" {
		t.Fatalf("snapshot durable value = %q, %v", value, err)
	}
	if mode := fileMode(t, snapshotPath); mode.Perm() != 0600 {
		t.Fatalf("snapshot mode = %o, want 600", mode.Perm())
	}
}

func TestValidateBoltRejectsCorruptFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "corrupt.db")
	if err := os.WriteFile(path, []byte("not a bolt database"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateBolt(path); err == nil {
		t.Fatal("corrupt Bolt file passed validation")
	}
}

func fileMode(t *testing.T, path string) os.FileMode {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info.Mode()
}
