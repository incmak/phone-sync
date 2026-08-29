package server

import (
	"crypto/ed25519"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
)

func TestJTICacheBudgetFailsClosedAndCleanupIsBatched(t *testing.T) {
	now := time.Unix(5000, 0)
	cache := NewJTICacheWithConfig(JTICacheConfig{TTL: time.Minute, MaxEntries: 3, CleanupBatch: 1})
	for _, jti := range []string{"a", "b", "c"} {
		if err := cache.CheckAndSet(jti, now); err != nil {
			t.Fatalf("admit %s: %v", jti, err)
		}
	}
	if err := cache.CheckAndSet("overflow", now); !errors.Is(err, ErrJTICapacity) {
		t.Fatalf("overflow error = %v, want ErrJTICapacity", err)
	}
	if got := cache.EntryCount(); got != 3 {
		t.Fatalf("JTI cardinality = %d, want 3", got)
	}
	if err := cache.CheckAndSet("a", now); !errors.Is(err, ErrJTIReplay) {
		t.Fatalf("existing JTI after overflow = %v, want replay", err)
	}
	if inspected := cache.Cleanup(now.Add(2*time.Minute + time.Nanosecond)); inspected != 1 {
		t.Fatalf("cleanup inspected = %d, want 1", inspected)
	}
	if got := cache.EntryCount(); got != 2 {
		t.Fatalf("JTI cardinality after batch = %d, want 2", got)
	}
}

func TestJTICacheConcurrentUniqueFloodNeverExceedsBudget(t *testing.T) {
	cache := NewJTICacheWithConfig(JTICacheConfig{TTL: time.Minute, MaxEntries: 16, CleanupBatch: 2})
	now := time.Unix(5100, 0)
	var wg sync.WaitGroup
	for index := 0; index < 128; index++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			_ = cache.CheckAndSet(fmt.Sprintf("jti-%d", index), now)
		}(index)
	}
	wg.Wait()
	if got := cache.EntryCount(); got != 16 {
		t.Fatalf("JTI cardinality after flood = %d, want 16", got)
	}
}

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
	issuedAt := time.Now()
	return signJWTClaims(t, priv, jwt.MapClaims{
		"sub": deviceID,
		"jti": jti,
		"iat": issuedAt.Unix(),
		"exp": issuedAt.Add(60 * time.Second).Unix(),
	})
}

func signJWTClaims(t *testing.T, priv ed25519.PrivateKey, claims jwt.MapClaims) string {
	t.Helper()
	tok := jwt.NewWithClaims(jwt.SigningMethodEdDSA, claims)
	s, err := tok.SignedString(priv)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return s
}

func TestJWTClaimsRejectMalformedOrOverlongValues(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	tests := map[string]func() jwt.MapClaims{
		"missing issued-at": func() jwt.MapClaims {
			return jwt.MapClaims{
				"sub": "devA", "jti": uuid.NewString(), "exp": now.Add(time.Minute).Unix(),
			}
		},
		"missing expiration": func() jwt.MapClaims {
			return jwt.MapClaims{
				"sub": "devA", "jti": uuid.NewString(), "iat": now.Unix(),
			}
		},
		"non-UUID JWT ID": func() jwt.MapClaims {
			return jwt.MapClaims{
				"sub": "devA", "jti": "predictable", "iat": now.Unix(), "exp": now.Add(time.Minute).Unix(),
			}
		},
		"lifetime exceeds maximum": func() jwt.MapClaims {
			return jwt.MapClaims{
				"sub": "devA", "jti": uuid.NewString(), "iat": now.Unix(), "exp": now.Add(time.Minute + time.Second).Unix(),
			}
		},
		"issued too far in future": func() jwt.MapClaims {
			return jwt.MapClaims{
				"sub": "devA", "jti": uuid.NewString(), "iat": now.Add(31 * time.Second).Unix(), "exp": now.Add(time.Minute).Unix(),
			}
		},
	}

	for name, claims := range tests {
		t.Run(name, func(t *testing.T) {
			config := DefaultConfig()
			config.Now = func() time.Time { return now }
			server := newTestServerWithConfig(t, config)
			deviceID, privateKey := registerPair(t, server.pairStore)
			candidate := claims()
			candidate["sub"] = deviceID
			token := signJWTClaims(t, privateKey, candidate)
			if status := authenticatedStatus(t, server, token); status != http.StatusUnauthorized {
				t.Fatalf("malformed claims status = %d, want %d", status, http.StatusUnauthorized)
			}
		})
	}
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
