package server

import (
	"crypto/ed25519"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
)

type revokeTestPair struct {
	pair  store.ConfirmedPair
	privA ed25519.PrivateKey
	privB ed25519.PrivateKey
}

func registerRevokeTestPair(t *testing.T, srv *Server, pairID string) revokeTestPair {
	t.Helper()
	aPub, aPriv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	bPub, bPriv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	pair := store.ConfirmedPair{
		PairID: pairID, DeviceA: "revoke-dev-a", DeviceB: "revoke-dev-b",
		AEncPubkey: []byte("a-enc"), ASignPubkey: aPub,
		BEncPubkey: []byte("b-enc"), BSignPubkey: bPub,
	}
	if err := srv.pairStore.Confirm(pair); err != nil {
		t.Fatal(err)
	}
	return revokeTestPair{pair: pair, privA: aPriv, privB: bPriv}
}

func postRevoke(t *testing.T, srv *Server, deviceID string, privateKey ed25519.PrivateKey) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/pair/revoke", strings.NewReader(`{"reason":"user_unpair"}`))
	req.Header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, privateKey, ""))
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	return rec
}

func TestRevokeAllowsEitherPairedDevice(t *testing.T) {
	for _, revoker := range []string{"a", "b"} {
		t.Run(revoker, func(t *testing.T) {
			srv := newTestServer(t)
			fixture := registerRevokeTestPair(t, srv, "either-pair")
			deviceID, privateKey := fixture.pair.DeviceA, fixture.privA
			if revoker == "b" {
				deviceID, privateKey = fixture.pair.DeviceB, fixture.privB
			}
			rec := postRevoke(t, srv, deviceID, privateKey)
			if rec.Code != http.StatusNoContent {
				t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
			}
			for _, device := range []string{fixture.pair.DeviceA, fixture.pair.DeviceB} {
				if _, err := srv.pairStore.PeerFor(device); !errors.Is(err, store.ErrNotFound) {
					t.Fatalf("peer %s still bound: %v", device, err)
				}
			}
		})
	}
}

func TestRevokeRejectsWrongOrUnpairedJWTWithoutMutation(t *testing.T) {
	for _, test := range []struct {
		name     string
		subject  string
		wrongKey bool
	}{
		{name: "wrong signing key", subject: "revoke-dev-a", wrongKey: true},
		{name: "unpaired subject", subject: "never-paired"},
	} {
		t.Run(test.name, func(t *testing.T) {
			srv := newTestServer(t)
			fixture := registerRevokeTestPair(t, srv, "auth-pair")
			privateKey := fixture.privA
			if test.wrongKey || test.subject == "never-paired" {
				_, privateKey, _ = ed25519.GenerateKey(nil)
			}
			rec := postRevoke(t, srv, test.subject, privateKey)
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
			}
			peer, err := srv.pairStore.PeerFor(fixture.pair.DeviceA)
			if err != nil || peer != fixture.pair.DeviceB {
				t.Fatalf("rejected request mutated pair: peer=%q err=%v", peer, err)
			}
		})
	}
}

func TestRevokePurgesMailboxesDisconnectsSocketsRejectsOldJWTAndAllowsNewKeys(t *testing.T) {
	srv := newTestServer(t)
	fixture := registerRevokeTestPair(t, srv, "full-revoke-pair")
	forward := store.MailboxRecord{
		RecipientDevice: fixture.pair.DeviceB, SenderDevice: fixture.pair.DeviceA,
		MsgID: "11111111-1111-4111-8111-111111111111", EnvelopeSHA256: strings.Repeat("a", 64), Envelope: []byte(`{"v":2}`),
	}
	reverse := store.MailboxRecord{
		RecipientDevice: fixture.pair.DeviceA, SenderDevice: fixture.pair.DeviceB,
		MsgID: "22222222-2222-4222-8222-222222222222", EnvelopeSHA256: strings.Repeat("b", 64), Envelope: []byte(`{"v":2}`),
	}
	for _, rec := range []store.MailboxRecord{forward, reverse} {
		if _, err := srv.mailbox.Put(rec, time.Now()); err != nil {
			t.Fatal(err)
		}
	}

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	dial := func(deviceID string, privateKey ed25519.PrivateKey) *websocket.Conn {
		t.Helper()
		header := http.Header{}
		header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, privateKey, ""))
		conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(ts.URL, "http")+"/ws", header)
		if err != nil {
			t.Fatalf("dial %s: %v", deviceID, err)
		}
		return conn
	}
	aConn := dial(fixture.pair.DeviceA, fixture.privA)
	defer aConn.Close()
	bConn := dial(fixture.pair.DeviceB, fixture.privB)
	defer bConn.Close()

	req, err := http.NewRequest(http.MethodPost, ts.URL+"/pair/revoke", strings.NewReader(`{"reason":"user_unpair"}`))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+mintJWT(t, fixture.pair.DeviceA, fixture.privA, ""))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("status=%d", resp.StatusCode)
	}
	for _, deviceID := range []string{fixture.pair.DeviceA, fixture.pair.DeviceB} {
		pending, err := srv.mailbox.Pending(deviceID, 10)
		if err != nil || len(pending) != 0 {
			t.Fatalf("pending mailbox for %s = %#v, %v", deviceID, pending, err)
		}
	}
	for name, conn := range map[string]*websocket.Conn{"a": aConn, "b": bConn} {
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, _, err := conn.ReadMessage(); err == nil {
			t.Fatalf("socket %s remained open after revocation", name)
		}
	}

	oldKeyRetry := postRevoke(t, srv, fixture.pair.DeviceB, fixture.privB)
	if oldKeyRetry.Code != http.StatusUnauthorized {
		t.Fatalf("old JWT status=%d body=%s", oldKeyRetry.Code, oldKeyRetry.Body.String())
	}
	newAPub, newAPriv, _ := ed25519.GenerateKey(nil)
	newBPub, _, _ := ed25519.GenerateKey(nil)
	if err := srv.pairStore.Confirm(store.ConfirmedPair{
		PairID: "new-key-pair", DeviceA: fixture.pair.DeviceA, DeviceB: fixture.pair.DeviceB,
		AEncPubkey: []byte("new-a-enc"), ASignPubkey: newAPub,
		BEncPubkey: []byte("new-b-enc"), BSignPubkey: newBPub,
	}); err != nil {
		t.Fatalf("rebind with new keys: %v", err)
	}
	newKeyRevoke := postRevoke(t, srv, fixture.pair.DeviceA, newAPriv)
	if newKeyRevoke.Code != http.StatusNoContent {
		t.Fatalf("new key authentication status=%d body=%s", newKeyRevoke.Code, newKeyRevoke.Body.String())
	}
}
