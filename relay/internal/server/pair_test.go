package server

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/phonesync/relay/internal/store"
)

func newTestServer(t *testing.T) *Server {
	t.Helper()
	dir := t.TempDir()
	b, err := store.OpenBolt(filepath.Join(dir, "relay.db"))
	if err != nil {
		t.Fatalf("open bolt: %v", err)
	}
	t.Cleanup(func() { _ = b.Close() })
	return NewWithStore(b)
}

func TestPairInitAndComplete(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	initBody := map[string]any{
		"pair_token":  "tok-01234567890123456789",
		"device_id":   "devA",
		"enc_pubkey":  base64.StdEncoding.EncodeToString([]byte("Aenc-32bytes-placeholder00000000")),
		"sign_pubkey": base64.StdEncoding.EncodeToString([]byte("Asig-32bytes-placeholder00000000")),
	}
	body, _ := json.Marshal(initBody)
	resp, err := http.Post(ts.URL+"/pair/init", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("init: %v", err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("init status %d", resp.StatusCode)
	}

	// Task 4 adds signature verification. For now, placeholder sig.
	completeBody := map[string]any{
		"pair_token":       "tok-01234567890123456789",
		"device_id":        "devB",
		"enc_pubkey":       base64.StdEncoding.EncodeToString([]byte("Benc-32bytes-placeholder00000000")),
		"sign_pubkey":      base64.StdEncoding.EncodeToString([]byte("Bsig-32bytes-placeholder00000000")),
		"confirmation_sig": base64.StdEncoding.EncodeToString([]byte("skip-for-task-2-tests")),
	}
	cb, _ := json.Marshal(completeBody)
	resp2, err := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(cb))
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	if resp2.StatusCode != 200 {
		t.Fatalf("complete status %d", resp2.StatusCode)
	}

	pk, err := srv.pairStore.SignPubkeyFor("devB")
	if err != nil || len(pk) == 0 {
		t.Fatalf("sign pubkey for devB: %v", err)
	}
}

func TestPairComplete_InvalidToken(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	completeBody := map[string]any{
		"pair_token":       "does-not-exist-token-padding123",
		"device_id":        "devB",
		"enc_pubkey":       base64.StdEncoding.EncodeToString([]byte("Benc-32bytes-placeholder00000000")),
		"sign_pubkey":      base64.StdEncoding.EncodeToString([]byte("Bsig-32bytes-placeholder00000000")),
		"confirmation_sig": base64.StdEncoding.EncodeToString([]byte("nope")),
	}
	cb, _ := json.Marshal(completeBody)
	resp, _ := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(cb))
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", resp.StatusCode)
	}
}
