package server

import (
	"crypto/ed25519"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/phonesync/relay/internal/store"
)

// registerPair puts a confirmed pair directly into the PairStore and returns
// device A's id + its Ed25519 private key for JWT minting in tests.
func registerPair(t *testing.T, ps *store.PairStore) (string, ed25519.PrivateKey) {
	t.Helper()
	aPub, aPriv, _ := ed25519.GenerateKey(nil)
	bPub, _, _ := ed25519.GenerateKey(nil)
	err := ps.Confirm(store.ConfirmedPair{
		PairID:      "pair-test-1",
		DeviceA:     "devA",
		DeviceB:     "devB",
		AEncPubkey:  []byte{1, 2, 3},
		ASignPubkey: aPub,
		BEncPubkey:  []byte{4, 5, 6},
		BSignPubkey: bPub,
	})
	if err != nil {
		t.Fatalf("confirm pair: %v", err)
	}
	return "devA", aPriv
}

func mintJWT(t *testing.T, deviceID string, priv ed25519.PrivateKey, jti string) string {
	t.Helper()
	if jti == "" {
		jti = uuid.NewString()
	}
	tok := jwt.NewWithClaims(jwt.SigningMethodEdDSA, jwt.MapClaims{
		"sub": deviceID,
		"jti": jti,
		"iat": time.Now().Unix(),
		"exp": time.Now().Add(60 * time.Second).Unix(),
	})
	s, err := tok.SignedString(priv)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return s
}

// dialWSAuthed registers a pair, mints a JWT, and dials /ws with the Authorization header.
func dialWSAuthed(t *testing.T, ts *httptest.Server, srv *Server) *websocket.Conn {
	t.Helper()
	deviceID, priv := registerPair(t, srv.pairStore)
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, priv, ""))
	c, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatalf("dialWSAuthed: %v", err)
	}
	return c
}

func TestWebSocketRequiresAuth(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		t.Fatal("expected dial to fail without auth")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401, got resp=%v err=%v", resp, err)
	}
}

func TestWebSocketValidJWT(t *testing.T) {
	srv := newTestServer(t)
	deviceID, priv := registerPair(t, srv.pairStore)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, priv, ""))
	c, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
}

func TestJTIReplayRejected(t *testing.T) {
	srv := newTestServer(t)
	deviceID, priv := registerPair(t, srv.pairStore)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"

	// Same jti => replay
	sharedJti := uuid.NewString()
	token := mintJWT(t, deviceID, priv, sharedJti)
	header := http.Header{}
	header.Set("Authorization", "Bearer "+token)

	c1, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatalf("first dial: %v", err)
	}
	c1.Close()

	_, resp, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err == nil {
		t.Fatal("expected replay dial to fail")
	}
	if resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 on replay, got resp=%v err=%v", resp, err)
	}
}

func TestJTICacheEvictionAfter2TTL(t *testing.T) {
	cache := NewJTICache(100 * time.Millisecond)
	now := time.Now()
	if err := cache.CheckAndSet("jti-1", now); err != nil {
		t.Fatalf("first set: %v", err)
	}
	// immediate replay rejected
	if err := cache.CheckAndSet("jti-1", now); err == nil {
		t.Fatal("expected replay reject immediately")
	}
	// after 2*ttl, entry evicted and jti can be reused
	if err := cache.CheckAndSet("jti-1", now.Add(300*time.Millisecond)); err != nil {
		t.Fatalf("expected accept after 2*ttl, got %v", err)
	}
}
