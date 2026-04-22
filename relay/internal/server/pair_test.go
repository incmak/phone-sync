package server

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/google/uuid"
	"github.com/twinotify/relay/internal/store"
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

// ed25519Keypair generates a random X25519-like enc pubkey (32 random bytes) and
// a real Ed25519 signing keypair, returning (encPub, signPub, signPriv).
func ed25519Keypair(t *testing.T) (encPub []byte, signPub ed25519.PublicKey, signPriv ed25519.PrivateKey) {
	t.Helper()
	encPub = make([]byte, 32)
	if _, err := rand.Read(encPub); err != nil {
		t.Fatalf("rand: %v", err)
	}
	var err error
	signPub, signPriv, err = ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("ed25519: %v", err)
	}
	return
}

// TestPairInitAndComplete_WithSig replaces the Task-2 placeholder test.
// Device A generates an Ed25519 keypair, signs the canonical pair message, and the
// server verifies it.
func TestPairInitAndComplete_WithSig(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPub := make([]byte, 32)
	_, _ = rand.Read(aEncPub)
	aSignPub, aSignPriv, _ := ed25519.GenerateKey(nil)

	bEncPub := make([]byte, 32)
	_, _ = rand.Read(bEncPub)
	bSignPub, _, _ := ed25519.GenerateKey(nil)

	token := "tok-" + uuid.NewString()

	initBody, _ := json.Marshal(map[string]any{
		"pair_token":  token,
		"device_id":   "devA",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(aEncPub),
		"sign_pubkey": base64.StdEncoding.EncodeToString(aSignPub),
	})
	resp, err := http.Post(ts.URL+"/pair/init", "application/json", bytes.NewReader(initBody))
	if err != nil {
		t.Fatalf("init: %v", err)
	}
	if resp.StatusCode != 200 {
		t.Fatalf("init status %d", resp.StatusCode)
	}

	msg := append([]byte(token), aEncPub...)
	msg = append(msg, aSignPub...)
	msg = append(msg, bEncPub...)
	msg = append(msg, bSignPub...)
	sig := ed25519.Sign(aSignPriv, msg)

	completeBody, _ := json.Marshal(map[string]any{
		"pair_token":       token,
		"device_id":        "devB",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPub),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPub),
		"confirmation_sig": base64.StdEncoding.EncodeToString(sig),
	})
	resp2, err := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(completeBody))
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
	body, _ := json.Marshal(map[string]any{
		"pair_token":       "does-not-exist-token-padding123",
		"device_id":        "devB",
		"enc_pubkey":       base64.StdEncoding.EncodeToString([]byte("Benc-32bytes-placeholder00000000")),
		"sign_pubkey":      base64.StdEncoding.EncodeToString([]byte("Bsig-32bytes-placeholder00000000")),
		"confirmation_sig": base64.StdEncoding.EncodeToString([]byte("nope")),
	})
	resp, _ := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(body))
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", resp.StatusCode)
	}
}

// TestPairComplete_BadSig: attacker submits /pair/complete with a valid Ed25519 sig
// but signed by a key NOT equal to Device A's registered sign_pubkey. Must be rejected.
func TestPairComplete_BadSig(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPub := make([]byte, 32)
	_, _ = rand.Read(aEncPub)
	aSignPub, _, _ := ed25519.GenerateKey(nil)
	bEncPub := make([]byte, 32)
	_, _ = rand.Read(bEncPub)
	bSignPub, _, _ := ed25519.GenerateKey(nil)
	_, attackerPriv, _ := ed25519.GenerateKey(nil)
	token := "tok-" + uuid.NewString()

	initBody, _ := json.Marshal(map[string]any{
		"pair_token":  token,
		"device_id":   "devA",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(aEncPub),
		"sign_pubkey": base64.StdEncoding.EncodeToString(aSignPub),
	})
	http.Post(ts.URL+"/pair/init", "application/json", bytes.NewReader(initBody))

	msg := append([]byte(token), aEncPub...)
	msg = append(msg, aSignPub...)
	msg = append(msg, bEncPub...)
	msg = append(msg, bSignPub...)
	wrongSig := ed25519.Sign(attackerPriv, msg)

	completeBody, _ := json.Marshal(map[string]any{
		"pair_token":       token,
		"device_id":        "devB",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPub),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPub),
		"confirmation_sig": base64.StdEncoding.EncodeToString(wrongSig),
	})
	resp, _ := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(completeBody))
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for bad sig, got %d", resp.StatusCode)
	}
}
