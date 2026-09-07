package store

import (
	"context"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// Opt in with an independently built, supported pre-migration binary. CI can
// build the release base separately; no production database is ever opened.
func TestPreviousRelayBinaryRefusesMigratedDatabase(t *testing.T) {
	binary := os.Getenv("TWINOTIFY_PREVIOUS_RELAY")
	if binary == "" {
		t.Skip("set TWINOTIFY_PREVIOUS_RELAY to the supported old relay executable")
	}
	path := filepath.Join(t.TempDir(), "relay.db")
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	_ = listener.Close()
	env := append(os.Environ(), "TWINOTIFY_ENV=development", "BOLT_PATH="+path, "LISTEN_ADDR="+address, "BACKUP_DIR=")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	old := exec.CommandContext(ctx, binary)
	old.Env = env
	if err := old.Start(); err != nil {
		t.Fatal(err)
	}
	stopped := false
	defer func() {
		if !stopped {
			_ = old.Process.Kill()
			_ = old.Wait()
		}
	}()
	client := http.Client{Timeout: 100 * time.Millisecond}
	ready := false
	for start := time.Now(); time.Since(start) < 5*time.Second; {
		response, err := client.Get("http://" + address + "/health")
		if err == nil {
			_, _ = io.Copy(io.Discard, response.Body)
			_ = response.Body.Close()
			if response.StatusCode == 200 {
				ready = true
				break
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !ready {
		t.Fatal("old binary did not start on its own database")
	}
	_ = old.Process.Signal(os.Interrupt)
	if err := old.Wait(); err != nil {
		t.Fatal("old baseline shutdown", err)
	}
	stopped = true
	b, err := OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := OpenPairStore(b); err != nil {
		_ = b.Close()
		t.Fatal(err)
	}
	if _, err := OpenMailboxStore(b, DefaultMailboxLimits()); err != nil {
		_ = b.Close()
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	refuseCtx, refuseCancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer refuseCancel()
	previous := exec.CommandContext(refuseCtx, binary)
	previous.Env = env
	output, err := previous.CombinedOutput()
	if refuseCtx.Err() != nil || err == nil {
		t.Fatalf("old binary failed to refuse migrated storage promptly: %v %s", err, output)
	}
	if exit, ok := err.(*exec.ExitError); !ok || exit.ExitCode() != 1 {
		t.Fatalf("unexpected refusal: %v %s", err, output)
	}
	b, err = OpenBolt(path)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if _, err := OpenPairStore(b); err != nil {
		t.Fatal("old open damaged migrated storage", err)
	}
}
