package device

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestMacDoesNotExposeAndroidSourceCapabilities(t *testing.T) {
	var receiver Receiver = &Mac{}
	if _, ok := receiver.(interface {
		Post(context.Context, string, string) error
	}); ok {
		t.Fatal("Mac must remain receive-only")
	}
}

func TestMacRejectsSharedOrSymlinkControlDirectory(t *testing.T) {
	root := t.TempDir()
	if err := os.Chmod(root, 0755); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenMac(root); err == nil {
		t.Fatal("shared directory accepted")
	}
	if err := os.Chmod(root, 0700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(t.TempDir(), "link")
	if err := os.Symlink(root, link); err != nil {
		t.Fatal(err)
	}
	if _, err := OpenMac(link); err == nil {
		t.Fatal("symlink accepted")
	}
}

func TestClaimedMacOperationHasUnknownResultAfterTimeoutAndIsNotReplayed(t *testing.T) {
	root := t.TempDir()
	if err := os.Chmod(root, 0700); err != nil {
		t.Fatal(err)
	}
	root, _ = filepath.EvalSymlinks(root)
	m, err := OpenMac(root)
	if err != nil {
		t.Fatal(err)
	}
	claimed := make(chan string, 1)
	go func() {
		for i := 0; i < 100; i++ {
			files, _ := filepath.Glob(filepath.Join(root, "*.request.json"))
			if len(files) == 1 {
				raw, _ := os.ReadFile(files[0])
				var request struct {
					ID string `json:"id"`
				}
				_ = json.Unmarshal(raw, &request)
				path := filepath.Join(root, request.ID+".claimed.json")
				_ = os.Rename(files[0], path)
				claimed <- path
				return
			}
			time.Sleep(time.Millisecond)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	if _, err := m.Execute(ctx, "remove_peer", "selected"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected unknown timeout, got %v", err)
	}
	select {
	case path := <-claimed:
		if _, err := os.Stat(path); err != nil {
			t.Fatal("claimed operation was erased")
		}
	case <-time.After(time.Second):
		t.Fatal("request was not claimed")
	}
	files, _ := filepath.Glob(filepath.Join(root, "*.request.json"))
	if len(files) != 0 {
		t.Fatal("timed out request was requeued")
	}
}
