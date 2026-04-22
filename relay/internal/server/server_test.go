package server

import (
	"crypto/ed25519"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
)


const validEnvelope = `{"v":1,"type":"ping","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000}`

func TestHealthEndpoint(t *testing.T) {
	srv := newTestServer(t)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	expected := `{"status":"ok"}`
	if rec.Body.String() != expected {
		t.Fatalf("expected body %q, got %q", expected, rec.Body.String())
	}
}

// TestWebSocketRoutesToPeer asserts a message sent by device A is forwarded to
// device B's WebSocket (not echoed back to A). Uses two separate JWT-authed
// connections for the same pair.
func TestWebSocketRoutesToPeer(t *testing.T) {
	srv := newTestServer(t)
	// Pre-populate the pair so both devices have matching sign pubkeys on the relay.
	aPub, aPriv, _ := ed25519.GenerateKey(nil)
	bPub, bPriv, _ := ed25519.GenerateKey(nil)
	if err := srv.pairStore.Confirm(store.ConfirmedPair{
		PairID:      "pair-routing-test",
		DeviceA:     "devA-r",
		DeviceB:     "devB-r",
		AEncPubkey:  []byte{1, 2, 3},
		ASignPubkey: aPub,
		BEncPubkey:  []byte{4, 5, 6},
		BSignPubkey: bPub,
	}); err != nil {
		t.Fatalf("confirm pair: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"

	dial := func(deviceID string, priv ed25519.PrivateKey) *websocket.Conn {
		t.Helper()
		h := http.Header{}
		h.Set("Authorization", "Bearer "+mintJWT(t, deviceID, priv, ""))
		c, _, err := websocket.DefaultDialer.Dial(wsURL, h)
		if err != nil {
			t.Fatalf("dial %s: %v", deviceID, err)
		}
		return c
	}
	a := dial("devA-r", aPriv)
	defer a.Close()
	b := dial("devB-r", bPriv)
	defer b.Close()

	// Give B a short read deadline so the test fails fast on no delivery.
	_ = b.SetReadDeadline(time.Now().Add(2 * time.Second))

	msgFromA := `{"v":1,"type":"ping","msg_id":"22222222-2222-4222-8222-222222222222","origin_device":"devA-r","ts":1713600000000}`
	if err := a.WriteMessage(websocket.TextMessage, []byte(msgFromA)); err != nil {
		t.Fatalf("write A: %v", err)
	}
	_, got, err := b.ReadMessage()
	if err != nil {
		t.Fatalf("B read (peer routing): %v", err)
	}
	if string(got) != msgFromA {
		t.Fatalf("B received %q, want %q", string(got), msgFromA)
	}

	// Sanity: A must NOT receive an echo of its own message.
	_ = a.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
	_, echo, err := a.ReadMessage()
	if err == nil {
		t.Fatalf("A received unexpected echo: %q", string(echo))
	}
}

func TestWebSocketRejectsInvalid(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	c := dialWSAuthed(t, ts, srv)
	defer c.Close()
	if err := c.WriteMessage(websocket.TextMessage, []byte(`{"garbage":true}`)); err != nil {
		t.Fatalf("write: %v", err)
	}
	_, msg, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !strings.Contains(string(msg), "invalid envelope") {
		t.Fatalf("expected error reply, got %q", string(msg))
	}
}
