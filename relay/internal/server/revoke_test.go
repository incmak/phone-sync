package server

import (
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
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

func assertRevokePeerClosed(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, _, err := conn.ReadMessage()
	if err == nil {
		t.Fatal("socket remained open after revocation")
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		t.Fatalf("socket only reached its read deadline instead of closing: %v", err)
	}
	if !errors.Is(err, io.EOF) && !websocket.IsCloseError(err,
		websocket.CloseNormalClosure,
		websocket.CloseGoingAway,
		websocket.CloseNoStatusReceived,
		websocket.CloseAbnormalClosure,
	) {
		t.Fatalf("socket returned non-terminal read error: %v", err)
	}
}

func dialRevokeTestWS(t *testing.T, ts *httptest.Server, deviceID string, privateKey ed25519.PrivateKey) *websocket.Conn {
	t.Helper()
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, privateKey, ""))
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(ts.URL, "http")+"/ws", header)
	if err != nil {
		t.Fatalf("dial %s: %v", deviceID, err)
	}
	return conn
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
	aConn := dialRevokeTestWS(t, ts, fixture.pair.DeviceA, fixture.privA)
	defer aConn.Close()
	bConn := dialRevokeTestWS(t, ts, fixture.pair.DeviceB, fixture.privB)
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
		t.Run("peer close "+name, func(t *testing.T) {
			assertRevokePeerClosed(t, conn)
		})
	}
	for _, deviceID := range []string{fixture.pair.DeviceA, fixture.pair.DeviceB} {
		if _, _, online := srv.clientHub.ConnectionForPair(deviceID, fixture.pair.PairID); online {
			t.Fatalf("old registration for %s survived revocation", deviceID)
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

func TestRevokeRejectsAuthenticatedSocketRegisteringAfterCommit(t *testing.T) {
	srv := newTestServer(t)
	fixture := registerRevokeTestPair(t, srv, "auth-register-old")
	beforeRegister := make(chan struct{})
	releaseRegister := make(chan struct{})
	var once sync.Once
	srv.webSocketBeforeRegister = func(deviceID, pairID string) {
		if deviceID == fixture.pair.DeviceA {
			once.Do(func() { close(beforeRegister) })
			<-releaseRegister
		}
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	type dialResult struct {
		conn *websocket.Conn
		err  error
	}
	dialed := make(chan dialResult, 1)
	go func() {
		header := http.Header{}
		header.Set("Authorization", "Bearer "+mintJWT(t, fixture.pair.DeviceA, fixture.privA, ""))
		conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(ts.URL, "http")+"/ws", header)
		dialed <- dialResult{conn: conn, err: err}
	}()
	<-beforeRegister
	if rec := postRevoke(t, srv, fixture.pair.DeviceB, fixture.privB); rec.Code != http.StatusNoContent {
		t.Fatalf("revoke status=%d body=%s", rec.Code, rec.Body.String())
	}
	close(releaseRegister)
	result := <-dialed
	if result.err != nil {
		t.Fatalf("authenticated upgrade failed before generation revalidation: %v", result.err)
	}
	defer result.conn.Close()
	assertRevokePeerClosed(t, result.conn)
	if _, _, online := srv.clientHub.ConnectionForPair(fixture.pair.DeviceA, fixture.pair.PairID); online {
		t.Fatal("old generation registered after revocation")
	}
}

func TestDelayedRevokedRegistrationCannotEvictReboundGeneration(t *testing.T) {
	srv := newTestServer(t)
	old := registerRevokeTestPair(t, srv, "delayed-register-old")
	beforeOldRegister := make(chan struct{})
	releaseOldRegister := make(chan struct{})
	var releaseOnce sync.Once
	t.Cleanup(func() { releaseOnce.Do(func() { close(releaseOldRegister) }) })
	srv.webSocketBeforeRegister = func(deviceID, pairID string) {
		if deviceID == old.pair.DeviceA && pairID == old.pair.PairID {
			close(beforeOldRegister)
			<-releaseOldRegister
		}
	}

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	type dialResult struct {
		conn *websocket.Conn
		err  error
	}
	oldDialed := make(chan dialResult, 1)
	oldToken := mintJWT(t, old.pair.DeviceA, old.privA, "")
	go func() {
		header := http.Header{}
		header.Set("Authorization", "Bearer "+oldToken)
		conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(ts.URL, "http")+"/ws", header)
		oldDialed <- dialResult{conn: conn, err: err}
	}()
	select {
	case <-beforeOldRegister:
	case <-time.After(2 * time.Second):
		t.Fatal("old generation did not reach the pre-registration barrier")
	}
	oldResult := <-oldDialed
	if oldResult.err != nil {
		t.Fatalf("old generation upgrade: %v", oldResult.err)
	}
	defer oldResult.conn.Close()

	if rec := postRevoke(t, srv, old.pair.DeviceB, old.privB); rec.Code != http.StatusNoContent {
		t.Fatalf("revoke status=%d body=%s", rec.Code, rec.Body.String())
	}
	newAPub, newAPriv, _ := ed25519.GenerateKey(nil)
	newBPub, _, _ := ed25519.GenerateKey(nil)
	rebound := store.ConfirmedPair{
		PairID: "delayed-register-new", DeviceA: old.pair.DeviceA, DeviceB: old.pair.DeviceB,
		AEncPubkey: []byte("new-a-enc"), ASignPubkey: newAPub,
		BEncPubkey: []byte("new-b-enc"), BSignPubkey: newBPub,
	}
	if err := srv.pairStore.Confirm(rebound); err != nil {
		t.Fatalf("confirm rebound pair: %v", err)
	}
	newConn := dialRevokeTestWS(t, ts, rebound.DeviceA, newAPriv)
	defer newConn.Close()
	if _, _, online := srv.clientHub.ConnectionForPair(rebound.DeviceA, rebound.PairID); !online {
		t.Fatal("rebound generation did not register")
	}

	beforeFrame := []byte(`{"generation":"p2-before"}`)
	if !srv.clientHub.SendRawV1ForPair(rebound.DeviceA, rebound.PairID, beforeFrame) {
		t.Fatal("rebound generation rejected its own frame before stale registration resumed")
	}
	_ = newConn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, raw, err := newConn.ReadMessage(); err != nil || string(raw) != string(beforeFrame) {
		t.Fatalf("rebound frame before stale resume = %q, %v", raw, err)
	}
	_ = newConn.SetReadDeadline(time.Time{})

	releaseOnce.Do(func() { close(releaseOldRegister) })
	assertRevokePeerClosed(t, oldResult.conn)
	if _, _, online := srv.clientHub.ConnectionForPair(old.pair.DeviceA, old.pair.PairID); online {
		t.Fatal("old generation remained registered after failed validation")
	}
	if _, _, online := srv.clientHub.ConnectionForPair(rebound.DeviceA, rebound.PairID); !online {
		t.Fatal("delayed old generation evicted the rebound registration")
	}
	if srv.clientHub.SendRawV1ForPair(rebound.DeviceA, old.pair.PairID, []byte(`{"generation":"p1"}`)) {
		t.Fatal("old generation frame crossed into rebound registration")
	}
	afterFrame := []byte(`{"generation":"p2-after"}`)
	if !srv.clientHub.SendRawV1ForPair(rebound.DeviceA, rebound.PairID, afterFrame) {
		t.Fatal("rebound generation was unusable after stale registration resumed")
	}
	_ = newConn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, raw, err := newConn.ReadMessage(); err != nil || string(raw) != string(afterFrame) {
		t.Fatalf("rebound frame after stale resume = %q, %v", raw, err)
	}
}

func TestPairScopedHubOperationsRejectUnboundRegistration(t *testing.T) {
	hub := NewClientHub()
	client := hub.Register("dev-a", make(chan []byte, 1))
	if !hub.SetProtocol(client, protocolV2) {
		t.Fatal("set protocol")
	}
	if _, _, online := hub.ConnectionForPair("dev-a", "pair-a"); online {
		t.Fatal("pair-scoped lookup accepted an unbound registration")
	}
}

func TestClientHubRegisterPairReplacesOnlySameGeneration(t *testing.T) {
	hub := NewClientHub()
	firstOutbound := make(chan []byte, 1)
	first := hub.RegisterPair("dev-a", "pair-a", firstOutbound)
	secondOutbound := make(chan []byte, 1)
	second := hub.RegisterPair("dev-a", "pair-a", secondOutbound)
	select {
	case <-first.done:
	default:
		t.Fatal("same-generation reconnect did not stop the prior registration")
	}
	hub.Unregister(first)
	if _, _, online := hub.ConnectionForPair("dev-a", "pair-a"); !online {
		t.Fatal("old same-generation unregister removed the replacement")
	}
	frame := []byte(`{"same_generation":true}`)
	if !hub.SendRawV1ForPair("dev-a", "pair-a", frame) {
		t.Fatal("same-generation replacement rejected delivery")
	}
	select {
	case got := <-secondOutbound:
		if string(got) != string(frame) {
			t.Fatalf("replacement frame = %q, want %q", got, frame)
		}
	default:
		t.Fatal("same-generation replacement received no frame")
	}
	select {
	case got := <-firstOutbound:
		t.Fatalf("stopped same-generation registration received frame %q", got)
	default:
	}
	hub.Unregister(second)
	if _, _, online := hub.ConnectionForPair("dev-a", "pair-a"); online {
		t.Fatal("replacement unregister left an orphaned registration")
	}
}

func TestClientHubRegisterPairRejectsDifferentGenerationWithoutEviction(t *testing.T) {
	hub := NewClientHub()
	oldOutbound := make(chan []byte, 1)
	old := hub.RegisterPair("dev-a", "pair-old", oldOutbound)
	rebound := hub.RegisterPair("dev-a", "pair-new", make(chan []byte, 1))
	select {
	case <-rebound.done:
	default:
		t.Fatal("different-generation registration was not rejected")
	}
	select {
	case <-old.done:
		t.Fatal("different-generation registration stopped the current client")
	default:
	}
	hub.Unregister(rebound)
	if _, _, online := hub.ConnectionForPair("dev-a", "pair-old"); !online {
		t.Fatal("rejected registration orphaned the current old generation")
	}
	if _, _, online := hub.ConnectionForPair("dev-a", "pair-new"); online {
		t.Fatal("rejected generation became current")
	}
	frame := []byte(`{"old_generation":true}`)
	if !hub.SendRawV1ForPair("dev-a", "pair-old", frame) {
		t.Fatal("current generation became unusable after rejecting a different generation")
	}
	select {
	case got := <-oldOutbound:
		if string(got) != string(frame) {
			t.Fatalf("current generation frame = %q, want %q", got, frame)
		}
	default:
		t.Fatal("current generation received no frame")
	}
	hub.Unregister(old)
}

func TestRevokeDisconnectDoesNotCloseReboundGeneration(t *testing.T) {
	srv := newTestServer(t)
	old := registerRevokeTestPair(t, srv, "disconnect-old")
	afterCommit := make(chan struct{})
	releaseDisconnect := make(chan struct{})
	srv.revokeAfterCommit = func(pairID string) {
		if pairID == old.pair.PairID {
			close(afterCommit)
			<-releaseDisconnect
		}
	}
	revokeDone := make(chan *httptest.ResponseRecorder, 1)
	go func() { revokeDone <- postRevoke(t, srv, old.pair.DeviceA, old.privA) }()
	<-afterCommit

	newAPub, newAPriv, _ := ed25519.GenerateKey(nil)
	newBPub, _, _ := ed25519.GenerateKey(nil)
	newPair := store.ConfirmedPair{
		PairID: "disconnect-new", DeviceA: old.pair.DeviceA, DeviceB: old.pair.DeviceB,
		AEncPubkey: []byte("new-a-enc"), ASignPubkey: newAPub,
		BEncPubkey: []byte("new-b-enc"), BSignPubkey: newBPub,
	}
	if err := srv.pairStore.Confirm(newPair); err != nil {
		t.Fatalf("confirm rebound pair: %v", err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	newConn := dialRevokeTestWS(t, ts, newPair.DeviceA, newAPriv)
	defer newConn.Close()
	close(releaseDisconnect)
	if rec := <-revokeDone; rec.Code != http.StatusNoContent {
		t.Fatalf("revoke status=%d body=%s", rec.Code, rec.Body.String())
	}
	if _, _, online := srv.clientHub.ConnectionForPair(newPair.DeviceA, newPair.PairID); !online {
		t.Fatal("new generation registration was disconnected")
	}
	if err := newConn.WriteMessage(websocket.TextMessage, []byte(`{"garbage":true}`)); err != nil {
		t.Fatalf("write to rebound connection: %v", err)
	}
	_ = newConn.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := newConn.ReadMessage(); err != nil {
		t.Fatalf("rebound connection did not answer after old disconnect: %v", err)
	}
}

func TestRevokeRejectsInFlightOldSessionPutAfterRebind(t *testing.T) {
	srv := newTestServer(t)
	old := registerRevokeTestPair(t, srv, "put-old")
	if err := srv.pairStore.UpdateCapabilities(old.pair.DeviceA, []int{2, 1}, "old-a"); err != nil {
		t.Fatal(err)
	}
	if err := srv.pairStore.UpdateCapabilities(old.pair.DeviceB, []int{2, 1}, "old-b"); err != nil {
		t.Fatal(err)
	}
	beforeStore := make(chan struct{})
	releaseStore := make(chan struct{})
	srv.relayPutBeforeStore = func(deviceID, pairID, msgID string) {
		close(beforeStore)
		<-releaseStore
	}
	msgID := "77777777-7777-4777-8777-777777777777"
	rejected := make(chan string, 1)
	putDone := make(chan struct{})
	go func() {
		defer close(putDone)
		srv.handleRelayPutForPair(old.pair.DeviceA, old.pair.PairID, RelayPut{
			V: 2, Type: "relay.put", Envelope: json.RawMessage(`{"v":2,"type":"enc","msg_id":"` + msgID + `","origin_device":"revoke-dev-a","created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`),
		}, func(any) error { return nil }, func(_ string, reason string) error {
			rejected <- reason
			return nil
		})
	}()
	<-beforeStore
	if rec := postRevoke(t, srv, old.pair.DeviceB, old.privB); rec.Code != http.StatusNoContent {
		t.Fatalf("revoke status=%d body=%s", rec.Code, rec.Body.String())
	}
	newPair := store.ConfirmedPair{PairID: "put-new", DeviceA: old.pair.DeviceA, DeviceB: old.pair.DeviceB}
	if err := srv.pairStore.Confirm(newPair); err != nil {
		t.Fatal(err)
	}
	if err := srv.pairStore.UpdateCapabilities(newPair.DeviceA, []int{2, 1}, "new-a"); err != nil {
		t.Fatal(err)
	}
	if err := srv.pairStore.UpdateCapabilities(newPair.DeviceB, []int{2, 1}, "new-b"); err != nil {
		t.Fatal(err)
	}
	close(releaseStore)
	<-putDone
	select {
	case reason := <-rejected:
		if reason != "not_recipient" {
			t.Fatalf("old put rejection=%q, want not_recipient", reason)
		}
	default:
		t.Fatal("old generation put was not rejected")
	}
	pending, err := srv.mailbox.Pending(newPair.DeviceB, 10)
	if err != nil || len(pending) != 0 {
		t.Fatalf("old generation recreated mailbox after purge: %#v, %v", pending, err)
	}
}

func TestRevokeRejectsInFlightOldSessionAckAgainstReboundMailbox(t *testing.T) {
	srv := newTestServer(t)
	old := registerRevokeTestPair(t, srv, "ack-old")
	beforeStore := make(chan struct{})
	releaseStore := make(chan struct{})
	srv.relayAckBeforeStore = func(deviceID, pairID, msgID string) {
		close(beforeStore)
		<-releaseStore
	}
	msgID := "88888888-8888-4888-8888-888888888888"
	digest := strings.Repeat("a", 64)
	ackDone := make(chan error, 1)
	go func() {
		ackDone <- srv.handleRelayAckForPair(old.pair.DeviceB, old.pair.PairID, RelayAck{
			V: 2, Type: "relay.ack", MsgID: msgID, EnvelopeSHA256: digest,
		})
	}()
	<-beforeStore
	if rec := postRevoke(t, srv, old.pair.DeviceA, old.privA); rec.Code != http.StatusNoContent {
		t.Fatalf("revoke status=%d body=%s", rec.Code, rec.Body.String())
	}
	newPair := store.ConfirmedPair{PairID: "ack-new", DeviceA: old.pair.DeviceA, DeviceB: old.pair.DeviceB}
	if err := srv.pairStore.Confirm(newPair); err != nil {
		t.Fatal(err)
	}
	if _, err := srv.mailbox.PutForPair(newPair.PairID, store.MailboxRecord{
		RecipientDevice: newPair.DeviceB, SenderDevice: newPair.DeviceA,
		MsgID: msgID, EnvelopeSHA256: digest, Envelope: []byte(`{"v":2}`),
	}, time.Now()); err != nil {
		t.Fatalf("put rebound mailbox item: %v", err)
	}
	close(releaseStore)
	if err := <-ackDone; !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("old generation ack error=%v, want ErrNotFound", err)
	}
	pending, err := srv.mailbox.Pending(newPair.DeviceB, 10)
	if err != nil || len(pending) != 1 || pending[0].MsgID != msgID {
		t.Fatalf("old generation ack mutated rebound mailbox: %#v, %v", pending, err)
	}
}
