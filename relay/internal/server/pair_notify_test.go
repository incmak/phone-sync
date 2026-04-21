package server

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

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

func TestPairNotify_PushesSigOnComplete(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	// 1. Device A initiates pairing with valid keys.
	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	pairToken := "tok-notify-1"
	aDeviceID := "devA-notify"
	initBody, _ := json.Marshal(map[string]any{
		"pair_token":  pairToken,
		"device_id":   aDeviceID,
		"enc_pubkey":  base64.StdEncoding.EncodeToString(aEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(aSignPk),
	})
	resp, err := http.Post(ts.URL+"/pair/init", "application/json", bytes.NewReader(initBody))
	if err != nil {
		t.Fatalf("pair/init: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/init status %d", resp.StatusCode)
	}

	// 2. Device A opens /pair/notify?token=...
	wsURL := strings.Replace(ts.URL, "http://", "ws://", 1) + "/pair/notify?token=" + pairToken
	wsConn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("ws dial: %v", err)
	}
	defer wsConn.Close()

	// 3. Device B submits /pair/complete with a valid sig bound to all 4 pubkeys.
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	// msg = pair_token || A_enc || A_sign || B_enc || B_sign
	msg := append([]byte(pairToken), aEncPk...)
	msg = append(msg, aSignPk...)
	msg = append(msg, bEncPk...)
	msg = append(msg, bSignPk...)
	sig := ed25519.Sign(aSignSk, msg)

	completeBody, _ := json.Marshal(map[string]any{
		"pair_token":       pairToken,
		"device_id":        "devB-notify",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPk),
		"confirmation_sig": base64.StdEncoding.EncodeToString(sig),
	})
	resp2, err := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(completeBody))
	if err != nil {
		t.Fatalf("pair/complete: %v", err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("pair/complete status %d", resp2.StatusCode)
	}

	// 4. Assert Device A receives pair.sig frame within 2s.
	_ = wsConn.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, data, err := wsConn.ReadMessage()
	if err != nil {
		t.Fatalf("ws read: %v", err)
	}

	var frame map[string]any
	if err := json.Unmarshal(data, &frame); err != nil {
		t.Fatalf("unmarshal frame: %v", err)
	}
	if frame["type"] != "pair.sig" {
		t.Fatalf("type = %v, want pair.sig", frame["type"])
	}
	if frame["pair_token"] != pairToken {
		t.Fatalf("pair_token = %v, want %v", frame["pair_token"], pairToken)
	}
	if _, ok := frame["confirmation_sig"].(string); !ok {
		t.Fatal("confirmation_sig missing or not a string")
	}
	if frame["v"] != float64(1) {
		t.Fatalf("v = %v, want 1", frame["v"])
	}
}

func TestPairNotify_UnknownTokenReturns404(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	wsURL := strings.Replace(ts.URL, "http://", "ws://", 1) + "/pair/notify?token=does-not-exist"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		t.Fatal("expected dial error for unknown token")
	}
	if resp == nil || resp.StatusCode != http.StatusNotFound {
		t.Fatalf("expected 404, got %v", resp)
	}
}
