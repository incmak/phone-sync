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
	return newTestServerWithConfig(t, DefaultConfig())
}

func newTestServerWithConfig(t *testing.T, config Config) *Server {
	t.Helper()
	dir := t.TempDir()
	b, err := store.OpenBolt(filepath.Join(dir, "relay.db"))
	if err != nil {
		t.Fatalf("open bolt: %v", err)
	}
	t.Cleanup(func() { _ = b.Close() })
	server, err := NewWithConfigChecked(b, config)
	if err != nil {
		t.Fatalf("new server: %v", err)
	}
	return server
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

func TestPairCompleteRequiresResponderSignatureWhenConfigured(t *testing.T) {
	config := DefaultConfig()
	config.RequireMutualPairSignatures = true
	srv := newTestServerWithConfig(t, config)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPub, aSignPub, aSignPriv := ed25519Keypair(t)
	bEncPub, bSignPub, _ := ed25519Keypair(t)
	token := "tok-mutual-required"

	initPair(t, ts.URL, token, "devA-mutual", aEncPub, aSignPub)
	message := append([]byte(token), aEncPub...)
	message = append(message, aSignPub...)
	message = append(message, bEncPub...)
	message = append(message, bSignPub...)
	aSignature := ed25519.Sign(aSignPriv, message)

	body, err := json.Marshal(map[string]any{
		"pair_token":       token,
		"device_id":        "devB-mutual",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPub),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPub),
		"confirmation_sig": base64.StdEncoding.EncodeToString(aSignature),
	})
	if err != nil {
		t.Fatal(err)
	}
	response, err := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("pair/complete without responder signature = %d, want 400", response.StatusCode)
	}
}

func TestPairCompleteRejectsResponderSignatureFromWrongKey(t *testing.T) {
	config := DefaultConfig()
	config.RequireMutualPairSignatures = true
	srv := newTestServerWithConfig(t, config)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPub, aSignPub, aSignPriv := ed25519Keypair(t)
	bEncPub, bSignPub, _ := ed25519Keypair(t)
	_, _, attackerSignPriv := ed25519Keypair(t)
	token := "tok-mutual-wrong-key"
	initPair(t, ts.URL, token, "devA-mutual-wrong", aEncPub, aSignPub)

	aMessage := append([]byte(token), aEncPub...)
	aMessage = append(aMessage, aSignPub...)
	aMessage = append(aMessage, bEncPub...)
	aMessage = append(aMessage, bSignPub...)
	aSignature := ed25519.Sign(aSignPriv, aMessage)
	bMessage := append([]byte("twinotify-pair-confirm-b-v1\n"), aMessage...)
	bMessage = append(bMessage, aSignature...)
	wrongBSignature := ed25519.Sign(attackerSignPriv, bMessage)

	response := postPairJSON(t, ts.URL+"/pair/complete", map[string]any{
		"pair_token":                 token,
		"device_id":                  "devB-mutual-wrong",
		"enc_pubkey":                 base64.StdEncoding.EncodeToString(bEncPub),
		"sign_pubkey":                base64.StdEncoding.EncodeToString(bSignPub),
		"confirmation_sig":           base64.StdEncoding.EncodeToString(aSignature),
		"responder_confirmation_sig": base64.StdEncoding.EncodeToString(wrongBSignature),
	})
	defer response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("pair/complete with wrong responder key = %d, want 400", response.StatusCode)
	}
}

func TestPairCompleteAcceptsAndPersistsMutualConfirmation(t *testing.T) {
	config := DefaultConfig()
	config.RequireMutualPairSignatures = true
	srv := newTestServerWithConfig(t, config)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPub, aSignPub, aSignPriv := ed25519Keypair(t)
	bEncPub, bSignPub, bSignPriv := ed25519Keypair(t)
	token := "tok-mutual-valid"
	initPair(t, ts.URL, token, "devA-mutual-valid", aEncPub, aSignPub)

	aMessage := append([]byte(token), aEncPub...)
	aMessage = append(aMessage, aSignPub...)
	aMessage = append(aMessage, bEncPub...)
	aMessage = append(aMessage, bSignPub...)
	aSignature := ed25519.Sign(aSignPriv, aMessage)
	bMessage := append([]byte("twinotify-pair-confirm-b-v1\n"), aMessage...)
	bMessage = append(bMessage, aSignature...)
	bSignature := ed25519.Sign(bSignPriv, bMessage)
	request := map[string]any{
		"pair_token":                 token,
		"device_id":                  "devB-mutual-valid",
		"enc_pubkey":                 base64.StdEncoding.EncodeToString(bEncPub),
		"sign_pubkey":                base64.StdEncoding.EncodeToString(bSignPub),
		"confirmation_sig":           base64.StdEncoding.EncodeToString(aSignature),
		"responder_confirmation_sig": base64.StdEncoding.EncodeToString(bSignature),
	}

	var pairID string
	for attempt := 0; attempt < 2; attempt++ {
		response := postPairJSON(t, ts.URL+"/pair/complete", request)
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			t.Fatalf("mutual completion attempt %d = %d, want 200", attempt+1, response.StatusCode)
		}
		var body struct {
			PairID string `json:"pair_id"`
		}
		if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
			response.Body.Close()
			t.Fatal(err)
		}
		response.Body.Close()
		if pairID == "" {
			pairID = body.PairID
		} else if body.PairID != pairID {
			t.Fatalf("retry pair id = %q, want %q", body.PairID, pairID)
		}
	}

	pending, err := srv.pairStore.GetPending(token)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(pending.ResponderConfirmationSig, bSignature) {
		t.Fatal("responder confirmation signature was not retained for retry auditing")
	}
}
