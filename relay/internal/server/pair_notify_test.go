package server

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

// dialPairNotify opens a WebSocket to /pair/notify with the given token and role.
// Returns the connection (caller must close) or fatals on error.
func dialPairNotify(t *testing.T, baseURL, token, role string) *websocket.Conn {
	t.Helper()
	wsURL := strings.Replace(baseURL, "http://", "ws://", 1) +
		"/pair/notify?token=" + token + "&role=" + role
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("ws dial (role=%s): %v", role, err)
	}
	return conn
}

// initPair calls /pair/init and fatals on non-200.
func initPair(t *testing.T, baseURL, pairToken, deviceID string, encPk, signPk []byte) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{
		"pair_token":  pairToken,
		"device_id":   deviceID,
		"enc_pubkey":  base64.StdEncoding.EncodeToString(encPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(signPk),
	})
	resp, err := http.Post(baseURL+"/pair/init", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("pair/init: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/init status %d", resp.StatusCode)
	}
}

// TestBidirectional_HappyPath exercises the full Phase-4 automated handshake:
//
//	A subscribes role=A → B POSTs /pair/hello → A receives peer.hello
//	A POSTs /pair/send_sig → B (role=B) receives pair.sig → B POSTs /pair/complete → 200
func TestBidirectional_HappyPath(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	pairToken := "tok-bidir-happy"

	// 1. Device A calls /pair/init
	initPair(t, ts.URL, pairToken, "devA-bidir", aEncPk, aSignPk)

	// 2. Device A subscribes role=A (waiting for peer.hello)
	wsA := dialPairNotify(t, ts.URL, pairToken, "A")
	defer wsA.Close()

	// 3. Device B subscribes role=B (waiting for pair.sig) — do this before send_sig
	wsB := dialPairNotify(t, ts.URL, pairToken, "B")
	defer wsB.Close()

	// 4. Device B POSTs /pair/hello
	helloBody, _ := json.Marshal(map[string]any{
		"pair_token":   pairToken,
		"device_id":    "devB-bidir",
		"enc_pubkey":   base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":  base64.StdEncoding.EncodeToString(bSignPk),
		"display_name": "Phone B",
	})
	resp, err := http.Post(ts.URL+"/pair/hello", "application/json", bytes.NewReader(helloBody))
	if err != nil {
		t.Fatalf("pair/hello: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/hello status %d", resp.StatusCode)
	}

	// 5. Device A receives peer.hello
	_ = wsA.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, data, err := wsA.ReadMessage()
	if err != nil {
		t.Fatalf("ws A read peer.hello: %v", err)
	}
	var helloFrame map[string]any
	if err := json.Unmarshal(data, &helloFrame); err != nil {
		t.Fatalf("unmarshal peer.hello: %v", err)
	}
	if helloFrame["type"] != "peer.hello" {
		t.Fatalf("expected peer.hello, got %v", helloFrame["type"])
	}
	if helloFrame["device_id"] != "devB-bidir" {
		t.Fatalf("device_id = %v", helloFrame["device_id"])
	}

	// 6. Device A signs and POSTs /pair/send_sig
	msg := append([]byte(pairToken), aEncPk...)
	msg = append(msg, aSignPk...)
	msg = append(msg, bEncPk...)
	msg = append(msg, bSignPk...)
	sig := ed25519.Sign(aSignSk, msg)

	sendSigBody, _ := json.Marshal(map[string]any{
		"pair_token":       pairToken,
		"confirmation_sig": base64.StdEncoding.EncodeToString(sig),
	})
	resp2, err := http.Post(ts.URL+"/pair/send_sig", "application/json", bytes.NewReader(sendSigBody))
	if err != nil {
		t.Fatalf("pair/send_sig: %v", err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("pair/send_sig status %d", resp2.StatusCode)
	}

	// 7. Device B receives pair.sig
	_ = wsB.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, data2, err := wsB.ReadMessage()
	if err != nil {
		t.Fatalf("ws B read pair.sig: %v", err)
	}
	var sigFrame map[string]any
	if err := json.Unmarshal(data2, &sigFrame); err != nil {
		t.Fatalf("unmarshal pair.sig: %v", err)
	}
	if sigFrame["type"] != "pair.sig" {
		t.Fatalf("expected pair.sig, got %v", sigFrame["type"])
	}
	if _, ok := sigFrame["confirmation_sig"].(string); !ok {
		t.Fatal("confirmation_sig missing in pair.sig frame")
	}

	// 8. Device B POSTs /pair/complete and gets a pair_id
	completeBody, _ := json.Marshal(map[string]any{
		"pair_token":       pairToken,
		"device_id":        "devB-bidir",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPk),
		"confirmation_sig": base64.StdEncoding.EncodeToString(sig),
	})
	resp3, err := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(completeBody))
	if err != nil {
		t.Fatalf("pair/complete: %v", err)
	}
	defer resp3.Body.Close()
	if resp3.StatusCode != http.StatusOK {
		t.Fatalf("pair/complete status %d", resp3.StatusCode)
	}
	var completeResp map[string]any
	_ = json.NewDecoder(resp3.Body).Decode(&completeResp)
	if _, ok := completeResp["pair_id"].(string); !ok {
		t.Fatal("pair_id missing from /pair/complete response")
	}
}

// TestPairNotify_MissingRole verifies that omitting ?role= returns 400.
func TestPairNotify_MissingRole(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, _ := ed25519Keypair(t)
	initPair(t, ts.URL, "tok-missing-role", "devA-mr", aEncPk, aSignPk)

	wsURL := strings.Replace(ts.URL, "http://", "ws://", 1) + "/pair/notify?token=tok-missing-role"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		t.Fatal("expected dial error for missing role")
	}
	if resp == nil || resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %v", resp)
	}
}

// TestPairNotify_InvalidRole verifies that ?role=C returns 400.
func TestPairNotify_InvalidRole(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, _ := ed25519Keypair(t)
	initPair(t, ts.URL, "tok-invalid-role", "devA-ir", aEncPk, aSignPk)

	wsURL := strings.Replace(ts.URL, "http://", "ws://", 1) + "/pair/notify?token=tok-invalid-role&role=C"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		t.Fatal("expected dial error for invalid role")
	}
	if resp == nil || resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %v", resp)
	}
}

// TestPairNotify_UnknownTokenReturns404 verifies an unknown pair token is rejected.
func TestPairNotify_UnknownTokenReturns404(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	wsURL := strings.Replace(ts.URL, "http://", "ws://", 1) + "/pair/notify?token=does-not-exist&role=A"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		t.Fatal("expected dial error for unknown token")
	}
	if resp == nil || resp.StatusCode != http.StatusNotFound {
		t.Fatalf("expected 404, got %v", resp)
	}
}

// TestPairSendSig_BeforeHello verifies that /pair/send_sig before /pair/hello returns 409.
func TestPairSendSig_BeforeHello(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	pairToken := "tok-sig-before-hello"
	initPair(t, ts.URL, pairToken, "devA-sbh", aEncPk, aSignPk)

	// Sign with dummy B keys (they won't match, but the store check fires first)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	msg := append([]byte(pairToken), aEncPk...)
	msg = append(msg, aSignPk...)
	msg = append(msg, bEncPk...)
	msg = append(msg, bSignPk...)
	sig := ed25519.Sign(aSignSk, msg)

	body, _ := json.Marshal(map[string]any{
		"pair_token":       pairToken,
		"confirmation_sig": base64.StdEncoding.EncodeToString(sig),
	})
	resp, err := http.Post(ts.URL+"/pair/send_sig", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("send_sig: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("expected 409 Conflict, got %d", resp.StatusCode)
	}
}

// TestPairSendSig_BadSig verifies that a tampered signature is rejected with 400.
func TestPairSendSig_BadSig(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, _ := ed25519Keypair(t)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	_, attackerPriv, _ := ed25519.GenerateKey(nil)
	pairToken := "tok-bad-sig"

	initPair(t, ts.URL, pairToken, "devA-bs", aEncPk, aSignPk)

	// Store B side via /pair/hello
	helloBody, _ := json.Marshal(map[string]any{
		"pair_token":  pairToken,
		"device_id":   "devB-bs",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	})
	resp, _ := http.Post(ts.URL+"/pair/hello", "application/json", bytes.NewReader(helloBody))
	resp.Body.Close()

	// Sign with wrong key
	msg := append([]byte(pairToken), aEncPk...)
	msg = append(msg, aSignPk...)
	msg = append(msg, bEncPk...)
	msg = append(msg, bSignPk...)
	wrongSig := ed25519.Sign(attackerPriv, msg)

	body, _ := json.Marshal(map[string]any{
		"pair_token":       pairToken,
		"confirmation_sig": base64.StdEncoding.EncodeToString(wrongSig),
	})
	resp2, err := http.Post(ts.URL+"/pair/send_sig", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("send_sig: %v", err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 for bad sig, got %d", resp2.StatusCode)
	}
}

// TestPairHello_UnknownToken verifies that /pair/hello with an unknown token returns 404.
func TestPairHello_UnknownToken(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	_, bSignPk, _ := ed25519Keypair(t)
	bEncPk := make([]byte, 32)

	body, _ := json.Marshal(map[string]any{
		"pair_token":  "no-such-token",
		"device_id":   "devB",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	})
	resp, err := http.Post(ts.URL+"/pair/hello", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("pair/hello: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", resp.StatusCode)
	}
}

// TestPairNotify_DoubleSubscribeRace verifies the identity-guarded unsubscribe
// does not panic or double-close when two goroutines race to subscribe the same (token, role).
func TestPairNotify_DoubleSubscribeRace(t *testing.T) {
	hub := NewPairHub()
	token := "tok-race"

	const n = 100
	var wg sync.WaitGroup
	wg.Add(n)
	for i := 0; i < n; i++ {
		go func() {
			defer wg.Done()
			subscription := hub.Subscribe(token, "A")
			// Small yield to allow interleaving
			time.Sleep(time.Microsecond)
			hub.Unsubscribe(token, "A", subscription)
		}()
	}
	wg.Wait()
	// If we reach here without panic, the identity guard works.
}
