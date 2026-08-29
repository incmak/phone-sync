package server

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
)

// dialPairNotify opens an authenticated WebSocket to /pair/notify.
// Returns the connection (caller must close) or fatals on error.
func dialPairNotify(t *testing.T, baseURL, token, role, deviceID string, privateKey ed25519.PrivateKey) *websocket.Conn {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(pairNotifyURL(baseURL, token, role), signedPairNotifyHeaders(token, role, deviceID, privateKey))
	if err != nil {
		t.Fatalf("ws dial (role=%s): %v", role, err)
	}
	return conn
}

func pairNotifyURL(baseURL, token, role string) string {
	return strings.Replace(baseURL, "http://", "ws://", 1) + "/pair/notify?token=" + token + "&role=" + role
}

func signedPairNotifyHeaders(token, role, deviceID string, privateKey ed25519.PrivateKey) http.Header {
	canonical := []byte("twinotify-pair-notify-v1\n" + token + "\n" + role + "\n" + deviceID)
	headers := make(http.Header)
	headers.Set("X-Twinotify-Device-ID", deviceID)
	headers.Set("X-Twinotify-Pair-Signature", base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, canonical)))
	return headers
}

func TestBeginShutdownClosesPairNotifyWithServiceRestartAndWaitsForReaderCleanup(t *testing.T) {
	srv := newTestServer(t)
	readerStarted := make(chan struct{})
	readerExiting := make(chan struct{})
	releaseReader := make(chan struct{})
	var startedOnce sync.Once
	var exitingOnce sync.Once
	var releaseOnce sync.Once
	defer releaseOnce.Do(func() { close(releaseReader) })
	srv.pairNotifyReaderStarted = func() {
		startedOnce.Do(func() { close(readerStarted) })
	}
	srv.pairNotifyReaderBeforeExit = func() {
		exitingOnce.Do(func() { close(readerExiting) })
		<-releaseReader
	}

	httpServer := httptest.NewServer(srv.Handler())
	defer httpServer.Close()
	encPublicKey, signPublicKey, signPrivateKey := ed25519Keypair(t)
	const pairToken = "tok-shutdown-drain"
	const deviceID = "devA-shutdown-drain"
	initPair(t, httpServer.URL, pairToken, deviceID, encPublicKey, signPublicKey)
	connection := dialPairNotify(t, httpServer.URL, pairToken, "A", deviceID, signPrivateKey)
	defer connection.Close()

	select {
	case <-readerStarted:
	case <-time.After(time.Second):
		t.Fatal("pair-notify reader did not start")
	}
	drainDone := srv.BeginShutdown()
	_ = connection.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err := connection.ReadMessage()
	var closeError *websocket.CloseError
	if !errors.As(err, &closeError) || closeError.Code != websocket.CloseServiceRestart {
		t.Fatalf("pair-notify shutdown close = %v, want WebSocket code %d", err, websocket.CloseServiceRestart)
	}
	select {
	case <-readerExiting:
	case <-time.After(time.Second):
		t.Fatal("pair-notify reader did not reach cleanup")
	}
	select {
	case <-drainDone:
		t.Fatal("WebSocket drain completed before the pair-notify reader exited")
	default:
	}

	releaseOnce.Do(func() { close(releaseReader) })
	select {
	case <-drainDone:
	case <-time.After(time.Second):
		t.Fatal("WebSocket drain did not complete after the pair-notify reader exited")
	}
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
	bEncPk, bSignPk, bSignSk := ed25519Keypair(t)
	pairToken := "tok-bidir-happy"

	// 1. Device A calls /pair/init
	initPair(t, ts.URL, pairToken, "devA-bidir", aEncPk, aSignPk)

	// 2. Device A subscribes role=A (waiting for peer.hello)
	wsA := dialPairNotify(t, ts.URL, pairToken, "A", "devA-bidir", aSignSk)
	defer wsA.Close()

	// 3. Device B POSTs /pair/hello
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

	// 4. Device B proves possession of its now-persisted signing key and subscribes.
	wsB := dialPairNotify(t, ts.URL, pairToken, "B", "devB-bidir", bSignSk)
	defer wsB.Close()

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

func TestPairNotifyLateSubscriberReceivesPersistedSignatureAndCompletion(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, bSignSk := ed25519Keypair(t)
	pairToken := "tok-late-subscriber"

	initPair(t, ts.URL, pairToken, "devA-late", aEncPk, aSignPk)

	helloBody, _ := json.Marshal(map[string]any{
		"pair_token":  pairToken,
		"device_id":   "devB-late",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	})
	helloResp, err := http.Post(ts.URL+"/pair/hello", "application/json", bytes.NewReader(helloBody))
	if err != nil {
		t.Fatalf("pair/hello: %v", err)
	}
	helloResp.Body.Close()
	if helloResp.StatusCode != http.StatusOK {
		t.Fatalf("pair/hello status %d", helloResp.StatusCode)
	}

	message := append([]byte(pairToken), aEncPk...)
	message = append(message, aSignPk...)
	message = append(message, bEncPk...)
	message = append(message, bSignPk...)
	signature := ed25519.Sign(aSignSk, message)
	signatureBase64 := base64.StdEncoding.EncodeToString(signature)
	sendSigBody, _ := json.Marshal(map[string]any{
		"pair_token":       pairToken,
		"confirmation_sig": signatureBase64,
	})
	sendSigResp, err := http.Post(ts.URL+"/pair/send_sig", "application/json", bytes.NewReader(sendSigBody))
	if err != nil {
		t.Fatalf("pair/send_sig: %v", err)
	}
	sendSigResp.Body.Close()
	if sendSigResp.StatusCode != http.StatusOK {
		t.Fatalf("pair/send_sig status %d", sendSigResp.StatusCode)
	}

	wsB := dialPairNotify(t, ts.URL, pairToken, "B", "devB-late", bSignSk)
	defer wsB.Close()
	_ = wsB.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	_, data, err := wsB.ReadMessage()
	if err != nil {
		t.Fatalf("late Device B read persisted pair.sig: %v", err)
	}
	var sigFrame map[string]any
	if err := json.Unmarshal(data, &sigFrame); err != nil {
		t.Fatalf("unmarshal pair.sig: %v", err)
	}
	if sigFrame["type"] != "pair.sig" || sigFrame["confirmation_sig"] != signatureBase64 {
		t.Fatalf("first persisted frame = %#v, want pair.sig", sigFrame)
	}

	completeBody, _ := json.Marshal(map[string]any{
		"pair_token":       pairToken,
		"device_id":        "devB-late",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPk),
		"confirmation_sig": signatureBase64,
	})
	completeResp, err := http.Post(ts.URL+"/pair/complete", "application/json", bytes.NewReader(completeBody))
	if err != nil {
		t.Fatalf("pair/complete: %v", err)
	}
	defer completeResp.Body.Close()
	if completeResp.StatusCode != http.StatusOK {
		t.Fatalf("pair/complete status %d", completeResp.StatusCode)
	}
	var completed map[string]any
	if err := json.NewDecoder(completeResp.Body).Decode(&completed); err != nil {
		t.Fatalf("decode pair/complete: %v", err)
	}
	pairID, ok := completed["pair_id"].(string)
	if !ok || pairID == "" {
		t.Fatalf("pair/complete response = %#v", completed)
	}

	wsA := dialPairNotify(t, ts.URL, pairToken, "A", "devA-late", aSignSk)
	defer wsA.Close()
	_ = wsA.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	for {
		_, data, err = wsA.ReadMessage()
		if err != nil {
			t.Fatalf("reconnected Device A read pair.complete: %v", err)
		}
		var frame map[string]any
		if err := json.Unmarshal(data, &frame); err != nil {
			t.Fatalf("unmarshal Device A frame: %v", err)
		}
		if frame["type"] == "pair.complete" {
			if frame["pair_id"] != pairID {
				t.Fatalf("pair.complete pair_id = %v, want %s", frame["pair_id"], pairID)
			}
			break
		}
	}
}

func TestPairHandshakeRetriesAreIdempotentAndConflictsReturn409(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	conflictingBEncPk, _, _ := ed25519Keypair(t)
	pairToken := "tok-idempotent-handshake"
	initPair(t, ts.URL, pairToken, "devA-idempotent", aEncPk, aSignPk)

	hello := map[string]any{
		"pair_token": pairToken, "device_id": "devB-idempotent",
		"enc_pubkey":   base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":  base64.StdEncoding.EncodeToString(bSignPk),
		"display_name": "Phone B",
	}
	for attempt := 0; attempt < 2; attempt++ {
		resp := postPairJSON(t, ts.URL+"/pair/hello", hello)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("identical hello attempt %d status = %d", attempt+1, resp.StatusCode)
		}
	}
	conflictingHello := clonePairRequest(hello)
	conflictingHello["enc_pubkey"] = base64.StdEncoding.EncodeToString(conflictingBEncPk)
	resp := postPairJSON(t, ts.URL+"/pair/hello", conflictingHello)
	resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("conflicting hello status = %d, want 409", resp.StatusCode)
	}

	message := append([]byte(pairToken), aEncPk...)
	message = append(message, aSignPk...)
	message = append(message, bEncPk...)
	message = append(message, bSignPk...)
	signature := ed25519.Sign(aSignSk, message)
	signatureBase64 := base64.StdEncoding.EncodeToString(signature)
	sendSig := map[string]any{"pair_token": pairToken, "confirmation_sig": signatureBase64}
	for attempt := 0; attempt < 2; attempt++ {
		resp = postPairJSON(t, ts.URL+"/pair/send_sig", sendSig)
		resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("identical send_sig attempt %d status = %d", attempt+1, resp.StatusCode)
		}
	}
	conflictingSig := clonePairRequest(sendSig)
	conflictingSig["confirmation_sig"] = base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{0xa5}, ed25519.SignatureSize))
	resp = postPairJSON(t, ts.URL+"/pair/send_sig", conflictingSig)
	resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("conflicting send_sig status = %d, want 409", resp.StatusCode)
	}

	complete := map[string]any{
		"pair_token": pairToken, "device_id": "devB-idempotent",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPk),
		"confirmation_sig": signatureBase64, "display_name": "Phone B",
	}
	var originalPairID string
	for attempt := 0; attempt < 2; attempt++ {
		resp = postPairJSON(t, ts.URL+"/pair/complete", complete)
		if resp.StatusCode != http.StatusOK {
			resp.Body.Close()
			t.Fatalf("identical complete attempt %d status = %d", attempt+1, resp.StatusCode)
		}
		var body map[string]any
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			resp.Body.Close()
			t.Fatal(err)
		}
		resp.Body.Close()
		pairID, _ := body["pair_id"].(string)
		if pairID == "" {
			t.Fatalf("complete attempt %d body = %#v", attempt+1, body)
		}
		if attempt == 0 {
			originalPairID = pairID
		} else if pairID != originalPairID {
			t.Fatalf("complete retry pair_id = %q, want %q", pairID, originalPairID)
		}
	}

	resp = postPairJSON(t, ts.URL+"/pair/hello", hello)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("identical hello after completion status = %d", resp.StatusCode)
	}
	resp = postPairJSON(t, ts.URL+"/pair/send_sig", sendSig)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("identical send_sig after completion status = %d", resp.StatusCode)
	}

	conflictingComplete := clonePairRequest(complete)
	conflictingComplete["enc_pubkey"] = base64.StdEncoding.EncodeToString(conflictingBEncPk)
	resp = postPairJSON(t, ts.URL+"/pair/complete", conflictingComplete)
	resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("conflicting complete status = %d, want 409", resp.StatusCode)
	}
	state, err := srv.pairStore.PendingState(pairToken)
	if err != nil || state != store.PairCommitted {
		t.Fatalf("state after conflicts = %q, %v", state, err)
	}
	confirmed, err := srv.pairStore.Get(originalPairID)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(confirmed.BEncPubkey, bEncPk) {
		t.Fatalf("conflicting requests mutated confirmed pair: %#v", confirmed)
	}
}

func TestPairHandshakeConcurrentIdenticalTransitionsReturnOneCommittedPair(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	pairToken := "tok-concurrent-handshake"
	initPair(t, ts.URL, pairToken, "devA-concurrent", aEncPk, aSignPk)
	hello := map[string]any{
		"pair_token": pairToken, "device_id": "devB-concurrent",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	}
	assertConcurrentPairResponses(t, ts.URL+"/pair/hello", hello, 8, false)

	message := append([]byte(pairToken), aEncPk...)
	message = append(message, aSignPk...)
	message = append(message, bEncPk...)
	message = append(message, bSignPk...)
	signatureBase64 := base64.StdEncoding.EncodeToString(ed25519.Sign(aSignSk, message))
	sendSig := map[string]any{"pair_token": pairToken, "confirmation_sig": signatureBase64}
	assertConcurrentPairResponses(t, ts.URL+"/pair/send_sig", sendSig, 8, false)

	complete := map[string]any{
		"pair_token": pairToken, "device_id": "devB-concurrent",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPk),
		"confirmation_sig": signatureBase64,
	}
	results := assertConcurrentPairResponses(t, ts.URL+"/pair/complete", complete, 8, true)
	firstPairID := results[0].pairID
	for index, result := range results[1:] {
		if result.pairID != firstPairID {
			t.Fatalf("complete result %d pair_id = %q, want %q", index+1, result.pairID, firstPairID)
		}
	}
}

func TestPairNotifyLiveReplayInterleavingsDeduplicateTransitions(t *testing.T) {
	for iteration := 0; iteration < 10; iteration++ {
		t.Run(fmt.Sprintf("iteration-%d", iteration), func(t *testing.T) {
			srv := newTestServer(t)
			ts := httptest.NewServer(srv.Handler())
			defer ts.Close()

			aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
			bEncPk, bSignPk, bSignSk := ed25519Keypair(t)
			pairToken := fmt.Sprintf("tok-interleave-%d", iteration)
			initPair(t, ts.URL, pairToken, "devA-interleave", aEncPk, aSignPk)

			hello := map[string]any{
				"pair_token": pairToken, "device_id": "devB-interleave",
				"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
				"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
			}
			helloResult := postPairJSONAsync(t, ts.URL+"/pair/hello", hello)
			wsA := dialPairNotify(t, ts.URL, pairToken, "A", "devA-interleave", aSignSk)
			readPairFramesThrough(t, wsA, []string{"peer.hello"})
			wsA.Close()
			assertAsyncPairStatus(t, helloResult, http.StatusOK)

			message := append([]byte(pairToken), aEncPk...)
			message = append(message, aSignPk...)
			message = append(message, bEncPk...)
			message = append(message, bSignPk...)
			signature := ed25519.Sign(aSignSk, message)
			signatureBase64 := base64.StdEncoding.EncodeToString(signature)
			sendSig := map[string]any{"pair_token": pairToken, "confirmation_sig": signatureBase64}
			sigResult := postPairJSONAsync(t, ts.URL+"/pair/send_sig", sendSig)
			wsB := dialPairNotify(t, ts.URL, pairToken, "B", "devB-interleave", bSignSk)
			readPairFramesThrough(t, wsB, []string{"pair.sig"})
			wsB.Close()
			assertAsyncPairStatus(t, sigResult, http.StatusOK)

			complete := map[string]any{
				"pair_token": pairToken, "device_id": "devB-interleave",
				"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPk),
				"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPk),
				"confirmation_sig": signatureBase64,
			}
			completeResult := postPairJSONAsync(t, ts.URL+"/pair/complete", complete)
			wsA = dialPairNotify(t, ts.URL, pairToken, "A", "devA-interleave", aSignSk)
			wsB = dialPairNotify(t, ts.URL, pairToken, "B", "devB-interleave", bSignSk)
			readPairFramesThrough(t, wsA, []string{"peer.hello", "pair.complete"})
			readPairFramesThrough(t, wsB, []string{"pair.sig", "pair.complete"})
			wsA.Close()
			wsB.Close()
			assertAsyncPairStatus(t, completeResult, http.StatusOK)
		})
	}
}

func TestPairNotifyReplaysCommittedStateAfterStoreReopen(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "relay.db")
	bolt, err := store.OpenBolt(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	srv := NewWithStore(bolt)
	ts := httptest.NewServer(srv.Handler())

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, bSignSk := ed25519Keypair(t)
	pairToken := "tok-reopen-completed"
	initPair(t, ts.URL, pairToken, "devA-reopen", aEncPk, aSignPk)
	hello := map[string]any{
		"pair_token": pairToken, "device_id": "devB-reopen",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	}
	resp := postPairJSON(t, ts.URL+"/pair/hello", hello)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/hello status = %d", resp.StatusCode)
	}
	message := append([]byte(pairToken), aEncPk...)
	message = append(message, aSignPk...)
	message = append(message, bEncPk...)
	message = append(message, bSignPk...)
	signature := ed25519.Sign(aSignSk, message)
	signatureBase64 := base64.StdEncoding.EncodeToString(signature)
	sendSig := map[string]any{"pair_token": pairToken, "confirmation_sig": signatureBase64}
	resp = postPairJSON(t, ts.URL+"/pair/send_sig", sendSig)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/send_sig status = %d", resp.StatusCode)
	}
	complete := map[string]any{
		"pair_token": pairToken, "device_id": "devB-reopen",
		"enc_pubkey":       base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey":      base64.StdEncoding.EncodeToString(bSignPk),
		"confirmation_sig": signatureBase64,
	}
	resp = postPairJSON(t, ts.URL+"/pair/complete", complete)
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		t.Fatalf("pair/complete status = %d", resp.StatusCode)
	}
	var completed map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&completed); err != nil {
		resp.Body.Close()
		t.Fatal(err)
	}
	resp.Body.Close()
	originalPairID, _ := completed["pair_id"].(string)
	ts.Close()
	if err := bolt.Close(); err != nil {
		t.Fatal(err)
	}

	bolt, err = store.OpenBolt(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer bolt.Close()
	srv = NewWithStore(bolt)
	ts = httptest.NewServer(srv.Handler())
	defer ts.Close()

	wsB := dialPairNotify(t, ts.URL, pairToken, "B", "devB-reopen", bSignSk)
	readPairFramesThrough(t, wsB, []string{"pair.sig", "pair.complete"})
	wsB.Close()
	resp = postPairJSON(t, ts.URL+"/pair/complete", complete)
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		t.Fatalf("pair/complete retry after reopen status = %d", resp.StatusCode)
	}
	var retried map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&retried); err != nil {
		resp.Body.Close()
		t.Fatal(err)
	}
	resp.Body.Close()
	if retried["pair_id"] != originalPairID {
		t.Fatalf("reopened complete pair_id = %v, want %v", retried["pair_id"], originalPairID)
	}
}

func TestPairNotifyRequiresRoleSpecificSigningProofBeforeUpgrade(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	_, _, attackerSignSk := ed25519Keypair(t)
	pairToken := "tok-notify-auth"
	deviceA := "devA-notify-auth"
	deviceB := "devB-notify-auth"
	initPair(t, ts.URL, pairToken, deviceA, aEncPk, aSignPk)
	hello := map[string]any{
		"pair_token": pairToken, "device_id": deviceB,
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	}
	resp := postPairJSON(t, ts.URL+"/pair/hello", hello)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/hello status = %d", resp.StatusCode)
	}

	validA := signedPairNotifyHeaders(pairToken, "A", deviceA, aSignSk)
	tests := []struct {
		name    string
		token   string
		role    string
		headers http.Header
	}{
		{name: "missing device", token: pairToken, role: "A", headers: withoutPairNotifyHeader(validA, "X-Twinotify-Device-ID")},
		{name: "oversized device", token: pairToken, role: "A", headers: signedPairNotifyHeaders(pairToken, "A", strings.Repeat("d", 257), aSignSk)},
		{name: "duplicate device", token: pairToken, role: "A", headers: withDuplicatePairNotifyHeader(validA, "X-Twinotify-Device-ID", deviceA)},
		{name: "missing signature", token: pairToken, role: "A", headers: withoutPairNotifyHeader(validA, "X-Twinotify-Pair-Signature")},
		{name: "oversized signature", token: pairToken, role: "A", headers: withPairNotifyHeader(validA, "X-Twinotify-Pair-Signature", strings.Repeat("A", 89))},
		{name: "duplicate signature", token: pairToken, role: "A", headers: withDuplicatePairNotifyHeader(validA, "X-Twinotify-Pair-Signature", validA.Get("X-Twinotify-Pair-Signature"))},
		{name: "malformed signature", token: pairToken, role: "A", headers: withPairNotifyHeader(validA, "X-Twinotify-Pair-Signature", "not-base64")},
		{name: "wrong signature length", token: pairToken, role: "A", headers: withPairNotifyHeader(validA, "X-Twinotify-Pair-Signature", base64.StdEncoding.EncodeToString(make([]byte, ed25519.SignatureSize-1)))},
		{name: "device mismatch", token: pairToken, role: "A", headers: signedPairNotifyHeaders(pairToken, "A", "other-device", aSignSk)},
		{name: "wrong private key", token: pairToken, role: "A", headers: signedPairNotifyHeaders(pairToken, "A", deviceA, attackerSignSk)},
		{name: "wrong token", token: pairToken + "-wrong", role: "A", headers: validA.Clone()},
		{name: "cross-role replay", token: pairToken, role: "B", headers: validA.Clone()},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			assertPairNotifyRejected(t, ts.URL, tt.token, tt.role, tt.headers, http.StatusUnauthorized)
		})
	}
}

func TestPairNotifyRejectsRoleBBeforeHello(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, _ := ed25519Keypair(t)
	_, _, bSignSk := ed25519Keypair(t)
	pairToken := "tok-role-b-before-hello"
	initPair(t, ts.URL, pairToken, "devA-before-hello", aEncPk, aSignPk)
	headers := signedPairNotifyHeaders(pairToken, "B", "devB-before-hello", bSignSk)
	assertPairNotifyRejected(t, ts.URL, pairToken, "B", headers, http.StatusUnauthorized)
}

func TestPairNotifyRejectedProofCannotReplaceAuthenticatedSubscriber(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	_, _, attackerSignSk := ed25519Keypair(t)
	pairToken := "tok-auth-replacement-guard"
	deviceA := "devA-auth-replacement"
	initPair(t, ts.URL, pairToken, deviceA, aEncPk, aSignPk)
	legitimate := dialPairNotify(t, ts.URL, pairToken, "A", deviceA, aSignSk)
	defer legitimate.Close()

	attackerHeaders := signedPairNotifyHeaders(pairToken, "A", deviceA, attackerSignSk)
	assertPairNotifyRejected(t, ts.URL, pairToken, "A", attackerHeaders, http.StatusUnauthorized)
	hello := map[string]any{
		"pair_token": pairToken, "device_id": "devB-auth-replacement",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	}
	resp := postPairJSON(t, ts.URL+"/pair/hello", hello)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/hello status = %d", resp.StatusCode)
	}
	readPairFramesThrough(t, legitimate, []string{"peer.hello"})
}

func TestPairNotifyAuthenticatedConnectionReplacement(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	bEncPk, bSignPk, _ := ed25519Keypair(t)
	pairToken := "tok-authenticated-replacement"
	deviceA := "devA-authenticated-replacement"
	initPair(t, ts.URL, pairToken, deviceA, aEncPk, aSignPk)
	oldConnection := dialPairNotify(t, ts.URL, pairToken, "A", deviceA, aSignSk)
	defer oldConnection.Close()
	newConnection := dialPairNotify(t, ts.URL, pairToken, "A", deviceA, aSignSk)
	defer newConnection.Close()

	_ = oldConnection.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := oldConnection.ReadMessage(); err == nil {
		t.Fatal("replaced authenticated connection remained active")
	}
	hello := map[string]any{
		"pair_token": pairToken, "device_id": "devB-authenticated-replacement",
		"enc_pubkey":  base64.StdEncoding.EncodeToString(bEncPk),
		"sign_pubkey": base64.StdEncoding.EncodeToString(bSignPk),
	}
	resp := postPairJSON(t, ts.URL+"/pair/hello", hello)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair/hello status = %d", resp.StatusCode)
	}
	readPairFramesThrough(t, newConnection, []string{"peer.hello"})
}

func withoutPairNotifyHeader(headers http.Header, name string) http.Header {
	cloned := headers.Clone()
	cloned.Del(name)
	return cloned
}

func withPairNotifyHeader(headers http.Header, name, value string) http.Header {
	cloned := headers.Clone()
	cloned.Set(name, value)
	return cloned
}

func withDuplicatePairNotifyHeader(headers http.Header, name, value string) http.Header {
	cloned := headers.Clone()
	cloned.Add(name, value)
	return cloned
}

func assertPairNotifyRejected(t *testing.T, baseURL, token, role string, headers http.Header, wantStatus int) {
	t.Helper()
	connection, resp, err := websocket.DefaultDialer.Dial(pairNotifyURL(baseURL, token, role), headers)
	if connection != nil {
		connection.Close()
	}
	if err == nil {
		t.Fatal("pair notify unexpectedly upgraded")
	}
	if resp == nil {
		t.Fatalf("pair notify rejection had no HTTP response: %v", err)
	}
	defer resp.Body.Close()
	body, readErr := io.ReadAll(io.LimitReader(resp.Body, 65))
	if readErr != nil {
		t.Fatal(readErr)
	}
	if resp.StatusCode != wantStatus {
		t.Fatalf("pair notify status = %d, body %q; want %d", resp.StatusCode, body, wantStatus)
	}
	if string(body) != "unauthorized\n" {
		t.Fatalf("pair notify rejection body = %q, want bounded generic response", body)
	}
}

func TestPairNotifyRejectsAndCleansUpExpiredToken(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	pairToken := "tok-expired-notify"
	if err := srv.pairStore.PutPending(store.PendingPair{
		PairToken: pairToken, DeviceAID: "devA-expired", AEncPubkey: aEncPk,
		ASignPubkey: aSignPk, CreatedAt: time.Now().Add(-pairTokenTTL - time.Second).Unix(),
	}); err != nil {
		t.Fatal(err)
	}
	headers := signedPairNotifyHeaders(pairToken, "A", "devA-expired", aSignSk)
	assertPairNotifyRejected(t, ts.URL, pairToken, "A", headers, http.StatusUnauthorized)
	if _, err := srv.pairStore.GetPending(pairToken); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expired token remained persisted: %v", err)
	}
}

type asyncPairResult struct {
	status int
	pairID string
	err    error
}

func assertConcurrentPairResponses(t *testing.T, url string, request map[string]any, count int, decodePairID bool) []asyncPairResult {
	t.Helper()
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	results := make(chan asyncPairResult, count)
	start := make(chan struct{})
	for worker := 0; worker < count; worker++ {
		go func() {
			<-start
			resp, err := http.Post(url, "application/json", bytes.NewReader(body))
			if err != nil {
				results <- asyncPairResult{err: err}
				return
			}
			result := asyncPairResult{status: resp.StatusCode}
			if decodePairID && resp.StatusCode == http.StatusOK {
				var responseBody map[string]any
				if err := json.NewDecoder(resp.Body).Decode(&responseBody); err != nil {
					result.err = err
				} else {
					result.pairID, _ = responseBody["pair_id"].(string)
				}
			}
			resp.Body.Close()
			results <- result
		}()
	}
	close(start)
	collected := make([]asyncPairResult, 0, count)
	for index := 0; index < count; index++ {
		result := <-results
		if result.err != nil || result.status != http.StatusOK || (decodePairID && result.pairID == "") {
			t.Fatalf("concurrent response %d = %#v", index, result)
		}
		collected = append(collected, result)
	}
	return collected
}

func postPairJSONAsync(t *testing.T, url string, request map[string]any) <-chan asyncPairResult {
	t.Helper()
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	result := make(chan asyncPairResult, 1)
	go func() {
		resp, err := http.Post(url, "application/json", bytes.NewReader(body))
		if err != nil {
			result <- asyncPairResult{err: err}
			return
		}
		resp.Body.Close()
		result <- asyncPairResult{status: resp.StatusCode}
	}()
	return result
}

func assertAsyncPairStatus(t *testing.T, result <-chan asyncPairResult, want int) {
	t.Helper()
	got := <-result
	if got.err != nil || got.status != want {
		t.Fatalf("async pair request = status %d, error %v; want %d", got.status, got.err, want)
	}
}

func readPairFramesThrough(t *testing.T, conn *websocket.Conn, want []string) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	for index, wantType := range want {
		_, payload, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read pair frame %d (%s): %v", index, wantType, err)
		}
		var frame map[string]any
		if err := json.Unmarshal(payload, &frame); err != nil {
			t.Fatalf("decode pair frame %d: %v", index, err)
		}
		if frame["type"] != wantType {
			t.Fatalf("pair frame %d type = %v, want %s", index, frame["type"], wantType)
		}
	}
}

func postPairJSON(t *testing.T, url string, request map[string]any) *http.Response {
	t.Helper()
	body, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func clonePairRequest(request map[string]any) map[string]any {
	cloned := make(map[string]any, len(request))
	for key, value := range request {
		cloned[key] = value
	}
	return cloned
}

// TestPairNotify_MissingRole verifies that omitting ?role= returns 400.
func TestPairNotify_MissingRole(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	initPair(t, ts.URL, "tok-missing-role", "devA-mr", aEncPk, aSignPk)

	wsURL := strings.Replace(ts.URL, "http://", "ws://", 1) + "/pair/notify?token=tok-missing-role"
	headers := signedPairNotifyHeaders("tok-missing-role", "", "devA-mr", aSignSk)
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, headers)
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

	aEncPk, aSignPk, aSignSk := ed25519Keypair(t)
	initPair(t, ts.URL, "tok-invalid-role", "devA-ir", aEncPk, aSignPk)

	wsURL := strings.Replace(ts.URL, "http://", "ws://", 1) + "/pair/notify?token=tok-invalid-role&role=C"
	headers := signedPairNotifyHeaders("tok-invalid-role", "C", "devA-ir", aSignSk)
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, headers)
	if err == nil {
		t.Fatal("expected dial error for invalid role")
	}
	if resp == nil || resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400, got %v", resp)
	}
}

// TestPairNotify_UnknownTokenIsGenericUnauthorized verifies token existence is not disclosed.
func TestPairNotify_UnknownTokenIsGenericUnauthorized(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	_, _, privateKey := ed25519Keypair(t)
	headers := signedPairNotifyHeaders("does-not-exist", "A", "unknown-device", privateKey)
	assertPairNotifyRejected(t, ts.URL, "does-not-exist", "A", headers, http.StatusUnauthorized)
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
