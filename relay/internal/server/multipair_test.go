package server

import (
	"bytes"
	"crypto/ed25519"
	"github.com/gorilla/websocket"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/twinotify/relay/internal/store"
)

func TestPairSelectorJWTQueryAndScopedRevoke(t *testing.T) {
	srv := newTestServer(t)
	aPub, aPriv, _ := ed25519.GenerateKey(nil)
	bPub, _, _ := ed25519.GenerateKey(nil)
	mPub, _, _ := ed25519.GenerateKey(nil)
	for _, cp := range []store.ConfirmedPair{
		{PairID: "ab", DeviceA: "a", DeviceB: "b", ASignPubkey: aPub, BSignPubkey: bPub},
		{PairID: "am", DeviceA: "a", DeviceB: "m", ASignPubkey: aPub, BSignPubkey: mPub},
	} {
		if err := srv.pairStore.Confirm(cp); err != nil {
			t.Fatal(err)
		}
	}
	for _, tc := range []struct {
		name, claim, query, want string
		status                   int
	}{
		{"ambiguous", "", "", "", 401},
		{"claim", "ab", "", "ab", 204},
		{"query", "", "?pair_id=am", "am", 204},
		{"agree", "am", "?pair_id=am", "am", 204},
		{"mismatch", "ab", "?pair_id=am", "", 401},
		{"foreign", "unknown", "", "", 401},
		{"repeated", "ab", "?pair_id=ab&pair_id=am", "", 401},
	} {
		t.Run(tc.name, func(t *testing.T) {
			now := time.Now()
			claims := jwt.MapClaims{"sub": "a", "iat": now.Unix(), "exp": now.Add(time.Minute).Unix(), "jti": uuid.NewString()}
			if tc.claim != "" {
				claims["pair_id"] = tc.claim
			}
			token, err := jwt.NewWithClaims(jwt.SigningMethodEdDSA, claims).SignedString(aPriv)
			if err != nil {
				t.Fatal(err)
			}
			request := httptest.NewRequest(http.MethodGet, "/ws"+tc.query, nil)
			request.Header.Set("Authorization", "Bearer "+token)
			response := httptest.NewRecorder()
			srv.authMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				selected, _ := PairIDFromContext(r.Context())
				if selected != tc.want {
					t.Errorf("selected %s", selected)
				}
				w.WriteHeader(http.StatusNoContent)
			})).ServeHTTP(response, request)
			if response.Code != tc.status {
				t.Fatalf("status %d: %s", response.Code, response.Body.String())
			}
		})
	}
	mismatch := httptest.NewRequest(http.MethodPost, "/pair/revoke?pair_id=am", strings.NewReader(`{"pair_id":"ab"}`))
	mismatch.Header.Set("Authorization", "Bearer "+mintJWT(t, "a", aPriv, ""))
	rejected := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rejected, mismatch)
	if rejected.Code != http.StatusBadRequest {
		t.Fatalf("mismatched revoke body: %d", rejected.Code)
	}
	req := httptest.NewRequest(http.MethodPost, "/pair/revoke?pair_id=am", strings.NewReader(`{"pair_id":"am"}`))
	req.Header.Set("Authorization", "Bearer "+mintJWT(t, "a", aPriv, ""))
	response := httptest.NewRecorder()
	srv.Handler().ServeHTTP(response, req)
	if response.Code != 204 {
		t.Fatalf("revoke: %d %s", response.Code, response.Body.String())
	}
	if _, err := srv.pairStore.SessionForPair("a", "ab"); err != nil {
		t.Fatal("other link revoked", err)
	}
}

func TestThreeDeviceWebSocketsKeepEachPairIndependent(t *testing.T) {
	srv := newTestServer(t)
	pubs := map[string]ed25519.PublicKey{}
	privs := map[string]ed25519.PrivateKey{}
	for _, device := range []string{"a", "b", "m"} {
		pub, priv, _ := ed25519.GenerateKey(nil)
		pubs[device] = pub
		privs[device] = priv
	}
	pairs := []struct{ id, a, b string }{{"ab", "a", "b"}, {"am", "a", "m"}, {"bm", "b", "m"}}
	for _, pair := range pairs {
		if err := srv.pairStore.Confirm(store.ConfirmedPair{PairID: pair.id, DeviceA: pair.a, DeviceB: pair.b, ASignPubkey: pubs[pair.a], BSignPubkey: pubs[pair.b]}); err != nil {
			t.Fatal(err)
		}
		for _, device := range []string{pair.a, pair.b} {
			if err := srv.pairStore.UpdateCapabilitiesForPair(device, pair.id, []int{2}, "test"); err != nil {
				t.Fatal(err)
			}
		}
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	sockets := map[string]*websocket.Conn{}
	for _, pair := range pairs {
		for _, device := range []string{pair.a, pair.b} {
			headers := http.Header{}
			headers.Set("Authorization", "Bearer "+mintJWT(t, device, privs[device], ""))
			connection, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(ts.URL, "http")+"/ws?pair_id="+pair.id, headers)
			if err != nil {
				t.Fatal(err)
			}
			defer connection.Close()
			sockets[device+pair.id] = connection
			writeMailboxFrame(t, connection, RelayHello{V: 2, Type: "relay.hello", Protocols: []int{2}, AppVersion: "test"})
			if frame := readMailboxFrame(t, connection); frame.Type != "relay.capabilities" {
				t.Fatalf("hello: %#v", frame)
			}
		}
	}
	next := func(connection *websocket.Conn, kind string) testFrame {
		for count := 0; count < 8; count++ {
			frame := readMailboxFrame(t, connection)
			if frame.Type == kind {
				return frame
			}
			if frame.Type != "relay.capabilities" {
				t.Fatalf("wanted %s: %#v", kind, frame)
			}
		}
		t.Fatal("too many capability frames")
		return testFrame{}
	}
	msgID := "88888888-8888-4888-8888-888888888888"
	for _, pair := range pairs {
		envelope := validMailboxEnvelope(pair.a, msgID)
		writeMailboxFrame(t, sockets[pair.a+pair.id], RelayPut{V: 2, Type: "relay.put", Envelope: envelope})
		next(sockets[pair.a+pair.id], "relay.accepted")
		delivered := next(sockets[pair.b+pair.id], "relay.deliver")
		if !bytes.Equal(delivered.Envelope, envelope) {
			t.Fatalf("wrong link delivered on %s: %s", pair.id, delivered.Envelope)
		}
	}
	request := httptest.NewRequest(http.MethodPost, "/pair/revoke?pair_id=am", strings.NewReader(`{"pair_id":"am"}`))
	request.Header.Set("Authorization", "Bearer "+mintJWT(t, "a", privs["a"], ""))
	response := httptest.NewRecorder()
	srv.Handler().ServeHTTP(response, request)
	if response.Code != 204 {
		t.Fatal(response.Code)
	}
	// The surviving phone link and B-to-Mac link keep their sockets and mailboxes.
	for _, pair := range []struct{ id, a, b string }{{"ab", "a", "b"}, {"bm", "b", "m"}} {
		envelope := validMailboxEnvelope(pair.a, "99999999-9999-4999-8999-999999999999")
		writeMailboxFrame(t, sockets[pair.a+pair.id], RelayPut{V: 2, Type: "relay.put", Envelope: envelope})
		next(sockets[pair.a+pair.id], "relay.accepted")
		if delivered := next(sockets[pair.b+pair.id], "relay.deliver"); !bytes.Equal(delivered.Envelope, envelope) {
			t.Fatal("surviving socket crossed links")
		}
	}
}
