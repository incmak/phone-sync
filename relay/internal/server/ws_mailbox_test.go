package server

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
	"go.etcd.io/bbolt"
)

type testFrame struct {
	V        int             `json:"v"`
	Type     string          `json:"type"`
	MsgID    string          `json:"msg_id"`
	Envelope json.RawMessage `json:"envelope"`
	Self     []int           `json:"self"`
	Peer     []int           `json:"peer"`
	Floor    int             `json:"floor"`
}

type testRejectedFrame struct {
	V      int    `json:"v"`
	Type   string `json:"type"`
	MsgID  string `json:"msg_id"`
	Reason string `json:"reason"`
}

type testCapabilitiesFrame struct {
	V     int    `json:"v"`
	Type  string `json:"type"`
	Self  []int  `json:"self"`
	Peer  []int  `json:"peer"`
	Floor int    `json:"floor"`
}

type mailboxTestPair struct {
	deviceA string
	deviceB string
	privA   ed25519.PrivateKey
	privB   ed25519.PrivateKey
}

func registerMailboxTestPair(t *testing.T, srv *Server) mailboxTestPair {
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	if err := srv.pairStore.UpdateCapabilities(pair.deviceA, []int{2, 1}, "test"); err != nil {
		t.Fatalf("advertise mailbox device A capabilities: %v", err)
	}
	if err := srv.pairStore.UpdateCapabilities(pair.deviceB, []int{2, 1}, "test"); err != nil {
		t.Fatalf("advertise mailbox device B capabilities: %v", err)
	}
	return pair
}

func registerMailboxTestPairWithoutCapabilities(t *testing.T, srv *Server) mailboxTestPair {
	t.Helper()
	aPub, aPriv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("generate A key: %v", err)
	}
	bPub, bPriv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatalf("generate B key: %v", err)
	}
	pair := mailboxTestPair{
		deviceA: "mailbox-device-a",
		deviceB: "mailbox-device-b",
		privA:   aPriv,
		privB:   bPriv,
	}
	if err := srv.pairStore.Confirm(store.ConfirmedPair{
		PairID:      "mailbox-test-pair",
		DeviceA:     pair.deviceA,
		DeviceB:     pair.deviceB,
		AEncPubkey:  []byte{1},
		ASignPubkey: aPub,
		BEncPubkey:  []byte{2},
		BSignPubkey: bPub,
	}); err != nil {
		t.Fatalf("confirm mailbox test pair: %v", err)
	}
	return pair
}

func dialMailboxWS(t *testing.T, ts *httptest.Server, deviceID string, priv ed25519.PrivateKey) *websocket.Conn {
	t.Helper()
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, priv, ""))
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		t.Fatalf("dial %s: %v", deviceID, err)
	}
	return conn
}

func newMailboxTestServerWithLimits(t *testing.T, limits store.MailboxLimits) *Server {
	t.Helper()
	b, err := store.OpenBolt(filepath.Join(t.TempDir(), "relay.db"))
	if err != nil {
		t.Fatalf("open bolt: %v", err)
	}
	t.Cleanup(func() { _ = b.Close() })
	return NewWithDependencies(b, limits)
}

func newMailboxTestServerWithBolt(t *testing.T) (*Server, *store.Bolt) {
	t.Helper()
	b, err := store.OpenBolt(filepath.Join(t.TempDir(), "relay.db"))
	if err != nil {
		t.Fatalf("open bolt: %v", err)
	}
	t.Cleanup(func() { _ = b.Close() })
	return NewWithStore(b), b
}

func corruptMailboxPairFloor(t *testing.T, b *store.Bolt, encoding []byte) {
	t.Helper()
	if err := b.Update(func(tx *bbolt.Tx) error {
		bucket, err := tx.CreateBucketIfNotExists([]byte("pair_protocol_floor"))
		if err != nil {
			return err
		}
		return bucket.Put([]byte("mailbox-test-pair"), encoding)
	}); err != nil {
		t.Fatalf("corrupt protocol floor: %v", err)
	}
}

func validMailboxEnvelope(origin, msgID string) json.RawMessage {
	return json.RawMessage(fmt.Sprintf(`{"v":2,"type":"enc","msg_id":%q,"origin_device":%q,"created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`, msgID, origin))
}

func validLegacyMailboxEnvelope(origin, msgID string) json.RawMessage {
	return json.RawMessage(fmt.Sprintf(`{"v":1,"type":"enc","msg_id":%q,"origin_device":%q,"ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`, msgID, origin))
}

func maxSizeMailboxEnvelope(t *testing.T, origin, msgID string) json.RawMessage {
	t.Helper()
	for padding := 0; padding < 4; padding++ {
		prefix := fmt.Sprintf(`{%s"v":2,"type":"enc","msg_id":%q,"origin_device":%q,"created_at":1786267348000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"`, strings.Repeat(" ", padding), msgID, origin)
		remaining := maxMessageSize - len(prefix) - len(`"}`)
		if remaining > 0 && remaining%4 == 0 {
			envelope := make([]byte, 0, maxMessageSize)
			envelope = append(envelope, prefix...)
			envelope = append(envelope, strings.Repeat("A", remaining)...)
			envelope = append(envelope, '"', '}')
			if len(envelope) != maxMessageSize {
				t.Fatalf("max envelope length = %d, want %d", len(envelope), maxMessageSize)
			}
			return envelope
		}
	}
	t.Fatal("could not construct aligned maximum envelope")
	return nil
}

func waitForMailboxProtocol(t *testing.T, srv *Server, deviceID string, want connectionProtocol) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if got, online := srv.clientHub.ProtocolFor(deviceID); online && got == want {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("device %s did not select protocol %d", deviceID, want)
}

func waitForMailboxOffline(t *testing.T, srv *Server, deviceID string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if _, online := srv.clientHub.ProtocolFor(deviceID); !online {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("device %s remained online", deviceID)
}

func putMailboxRecord(t *testing.T, srv *Server, recipient, sender string, envelope json.RawMessage, now time.Time) {
	t.Helper()
	var header encryptedEnvelopeHeader
	if err := json.Unmarshal(envelope, &header); err != nil {
		t.Fatalf("decode envelope header: %v", err)
	}
	digest := sha256.Sum256(envelope)
	if _, err := srv.mailbox.Put(store.MailboxRecord{
		RecipientDevice: recipient,
		SenderDevice:    sender,
		MsgID:           header.MsgID,
		EnvelopeSHA256:  hex.EncodeToString(digest[:]),
		Envelope:        envelope,
	}, now); err != nil {
		t.Fatalf("put mailbox record: %v", err)
	}
}

func waitForPendingCount(t *testing.T, srv *Server, recipient string, want int) []store.MailboxRecord {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		pending, err := srv.mailbox.Pending(recipient, 100)
		if err != nil {
			t.Fatalf("read mailbox for %s: %v", recipient, err)
		}
		if len(pending) == want {
			return pending
		}
		if time.Now().After(deadline) {
			t.Fatalf("mailbox for %s has %d records, want %d: %#v", recipient, len(pending), want, pending)
		}
		time.Sleep(time.Millisecond)
	}
}

func writeMailboxFrame(t *testing.T, conn *websocket.Conn, frame any) {
	t.Helper()
	raw, err := json.Marshal(frame)
	if err != nil {
		t.Fatalf("marshal frame: %v", err)
	}
	if err := conn.WriteMessage(websocket.TextMessage, raw); err != nil {
		t.Fatalf("write frame: %v", err)
	}
}

func readMailboxFrame(t *testing.T, conn *websocket.Conn) testFrame {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, raw, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read frame: %v", err)
	}
	var frame testFrame
	if err := json.Unmarshal(raw, &frame); err != nil {
		t.Fatalf("decode typed frame %q: %v", string(raw), err)
	}
	return frame
}

func readMailboxRejected(t *testing.T, conn *websocket.Conn) testRejectedFrame {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, raw, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read rejected frame: %v", err)
	}
	var frame testRejectedFrame
	if err := json.Unmarshal(raw, &frame); err != nil {
		t.Fatalf("decode rejected frame %q: %v", string(raw), err)
	}
	if frame.V != 2 || frame.Type != "relay.rejected" {
		t.Fatalf("response = %s, want relay.rejected", raw)
	}
	return frame
}

func sendMailboxHello(t *testing.T, conn *websocket.Conn) {
	t.Helper()
	capabilities := sendMailboxHelloWithProtocols(t, conn, []int{2, 1})
	if capabilities.V != 2 || capabilities.Type != "relay.capabilities" {
		t.Fatalf("hello response = %#v, want relay.capabilities", capabilities)
	}
}

func sendMailboxHelloWithProtocols(t *testing.T, conn *websocket.Conn, protocols []int) testCapabilitiesFrame {
	t.Helper()
	writeMailboxFrame(t, conn, map[string]any{
		"v":           2,
		"type":        "relay.hello",
		"protocols":   protocols,
		"app_version": "test",
	})
	_ = conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	_, raw, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read capabilities: %v", err)
	}
	var frame testCapabilitiesFrame
	if err := json.Unmarshal(raw, &frame); err != nil {
		t.Fatalf("decode capabilities %q: %v", raw, err)
	}
	return frame
}

func TestWebSocketMailboxOfflineDelivery(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	envelope := validMailboxEnvelope(pair.deviceA, "11111111-1111-4111-8111-111111111111")
	digest := sha256.Sum256(envelope)

	a := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	sendMailboxHello(t, a)
	writeMailboxFrame(t, a, map[string]any{
		"v":        2,
		"type":     "relay.put",
		"envelope": envelope,
	})
	accepted := readMailboxFrame(t, a)
	if accepted.V != 2 || accepted.Type != "relay.accepted" || accepted.MsgID != "11111111-1111-4111-8111-111111111111" {
		t.Fatalf("put response = %#v, want relay.accepted", accepted)
	}
	if err := a.Close(); err != nil {
		t.Fatalf("close sender: %v", err)
	}

	b := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	sendMailboxHello(t, b)
	delivered := readMailboxFrame(t, b)
	if delivered.V != 2 || delivered.Type != "relay.deliver" {
		t.Fatalf("mailbox response = %#v, want relay.deliver", delivered)
	}
	if string(delivered.Envelope) != string(envelope) {
		t.Fatalf("delivered envelope = %s, want exact bytes %s", delivered.Envelope, envelope)
	}
	writeMailboxFrame(t, b, map[string]any{
		"v":               2,
		"type":            "relay.ack",
		"msg_id":          "11111111-1111-4111-8111-111111111111",
		"envelope_sha256": hex.EncodeToString(digest[:]),
	})
	waitForPendingCount(t, srv, pair.deviceB, 0)
	if err := b.Close(); err != nil {
		t.Fatalf("close recipient: %v", err)
	}

	reconnected := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer reconnected.Close()
	sendMailboxHello(t, reconnected)
	_ = reconnected.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	if _, raw, err := reconnected.ReadMessage(); err == nil {
		t.Fatalf("acked message was redelivered: %s", raw)
	}
}

func TestWebSocketAcceptsOnlyAfterDurablePut(t *testing.T) {
	srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 0, MaxBytes: 1024, Retention: time.Hour})
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)

	msgID := "21111111-1111-4111-8111-111111111111"
	writeMailboxFrame(t, sender, map[string]any{
		"v": 2, "type": "relay.put", "envelope": validMailboxEnvelope(pair.deviceA, msgID),
	})
	rejected := readMailboxRejected(t, sender)
	if rejected.MsgID != msgID || rejected.Reason != "mailbox_full" {
		t.Fatalf("rejection = %#v, want mailbox_full for %s", rejected, msgID)
	}
	_ = recipient.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	if _, raw, err := recipient.ReadMessage(); err == nil {
		t.Fatalf("recipient received non-durable frame: %s", raw)
	}
}

func TestWebSocketRedeliversAfterDisconnectBeforeAck(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	msgID := "31111111-1111-4111-8111-111111111111"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	sendMailboxHello(t, sender)
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": envelope})
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" {
		t.Fatalf("put response = %#v, want relay.accepted", accepted)
	}
	_ = sender.Close()

	first := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	sendMailboxHello(t, first)
	if delivered := readMailboxFrame(t, first); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
		t.Fatalf("first delivery = %#v, want exact relay.deliver", delivered)
	}
	_ = first.Close()

	second := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer second.Close()
	sendMailboxHello(t, second)
	if delivered := readMailboxFrame(t, second); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
		t.Fatalf("redelivery = %#v, want exact relay.deliver", delivered)
	}
}

func TestWebSocketAckDigestMismatchKeepsMessage(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	msgID := "41111111-1111-4111-8111-111111111111"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	sendMailboxHello(t, sender)
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": envelope})
	_ = readMailboxFrame(t, sender)
	_ = sender.Close()

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	sendMailboxHello(t, recipient)
	_ = readMailboxFrame(t, recipient)
	writeMailboxFrame(t, recipient, map[string]any{
		"v": 2, "type": "relay.ack", "msg_id": msgID, "envelope_sha256": strings.Repeat("0", 64),
	})
	rejected := readMailboxRejected(t, recipient)
	if rejected.MsgID != msgID || rejected.Reason != "digest_mismatch" {
		t.Fatalf("rejection = %#v, want digest_mismatch", rejected)
	}
	_ = recipient.Close()

	reconnected := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer reconnected.Close()
	sendMailboxHello(t, reconnected)
	if delivered := readMailboxFrame(t, reconnected); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
		t.Fatalf("post-mismatch delivery = %#v, want exact relay.deliver", delivered)
	}
}

func TestWebSocketMailboxFullReturnsRejectedAndKeepsFirst(t *testing.T) {
	srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 1, MaxBytes: 1 << 20, Retention: time.Hour})
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	firstID := "51111111-1111-4111-8111-111111111111"
	firstEnvelope := validMailboxEnvelope(pair.deviceA, firstID)
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": firstEnvelope})
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" || accepted.MsgID != firstID {
		t.Fatalf("first response = %#v, want relay.accepted", accepted)
	}
	secondID := "52222222-2222-4222-8222-222222222222"
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": validMailboxEnvelope(pair.deviceA, secondID)})
	if rejected := readMailboxRejected(t, sender); rejected.MsgID != secondID || rejected.Reason != "mailbox_full" {
		t.Fatalf("second response = %#v, want mailbox_full", rejected)
	}

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	if delivered := readMailboxFrame(t, recipient); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(firstEnvelope) {
		t.Fatalf("retained delivery = %#v, want first envelope", delivered)
	}
	_ = recipient.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	if _, raw, err := recipient.ReadMessage(); err == nil {
		t.Fatalf("unexpected evicted/rejected delivery: %s", raw)
	}
}

func TestWebSocketRejectsOriginDifferentFromJWTSubject(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	msgID := "61111111-1111-4111-8111-111111111111"
	writeMailboxFrame(t, sender, map[string]any{
		"v": 2, "type": "relay.put", "envelope": validMailboxEnvelope(pair.deviceB, msgID),
	})
	if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "invalid_frame" {
		t.Fatalf("origin rejection = %#v, want invalid_frame", rejected)
	}
}

func TestWebSocketRejectsMixedLegacyAndV2Frames(t *testing.T) {
	t.Run("legacy then v2", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		conn := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer conn.Close()
		legacy := `{"v":1,"type":"enc","msg_id":"71111111-1111-4111-8111-111111111111","origin_device":"mailbox-device-a","ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`
		if err := conn.WriteMessage(websocket.TextMessage, []byte(legacy)); err != nil {
			t.Fatalf("write legacy frame: %v", err)
		}
		writeMailboxFrame(t, conn, map[string]any{"v": 2, "type": "relay.hello", "protocols": []int{2, 1}, "app_version": "test"})
		if rejected := readMailboxRejected(t, conn); rejected.Reason != "invalid_frame" {
			t.Fatalf("mixed rejection = %#v, want invalid_frame", rejected)
		}
	})

	t.Run("v2 then legacy", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		conn := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer conn.Close()
		sendMailboxHello(t, conn)
		legacyID := "72222222-2222-4222-8222-222222222222"
		legacy := fmt.Sprintf(`{"v":1,"type":"enc","msg_id":%q,"origin_device":%q,"ts":1713600000000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}`, legacyID, pair.deviceA)
		if err := conn.WriteMessage(websocket.TextMessage, []byte(legacy)); err != nil {
			t.Fatalf("write legacy frame: %v", err)
		}
		if rejected := readMailboxRejected(t, conn); rejected.MsgID != legacyID || rejected.Reason != "invalid_frame" {
			t.Fatalf("mixed rejection = %#v, want invalid_frame", rejected)
		}
	})
}

func TestParseRelayFrameStrictValidation(t *testing.T) {
	validator, err := NewValidator()
	if err != nil {
		t.Fatalf("validator: %v", err)
	}
	tests := []string{
		`{"v":2,"type":"relay.hello","protocols":[2,1],"app_version":"test","extra":true}`,
		`{"v":2,"type":"relay.accepted","msg_id":"81111111-1111-4111-8111-111111111111","accepted_at":1}`,
		`{"v":2,"type":"relay.unknown"}`,
	}
	for _, raw := range tests {
		if _, err := parseRelayFrame(validator, []byte(raw)); err == nil {
			t.Fatalf("parseRelayFrame accepted invalid client frame: %s", raw)
		} else if protocolErr := asRelayProtocolError(err); protocolErr.Code != "invalid_frame" {
			t.Fatalf("protocol error = %#v, want invalid_frame", protocolErr)
		}
	}
}

func TestWebSocketAckIsIdempotent(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	msgID := "82111111-1111-4111-8111-111111111111"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, envelope, time.Now())
	digest := sha256.Sum256(envelope)
	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	_ = readMailboxFrame(t, recipient)
	ack := map[string]any{"v": 2, "type": "relay.ack", "msg_id": msgID, "envelope_sha256": hex.EncodeToString(digest[:])}
	writeMailboxFrame(t, recipient, ack)
	writeMailboxFrame(t, recipient, ack)
	if err := recipient.WriteMessage(websocket.TextMessage, []byte(`{"garbage":true}`)); err != nil {
		t.Fatalf("write sentinel invalid frame: %v", err)
	}
	rejected := readMailboxRejected(t, recipient)
	if rejected.Reason != "invalid_frame" {
		t.Fatalf("duplicate ACK produced rejection %#v", rejected)
	}
}

func TestWebSocketDuplicateAckRejectsDifferentDigest(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	msgID := "82222222-2222-4222-8222-222222222222"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, envelope, time.Now())
	digest := sha256.Sum256(envelope)
	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	_ = readMailboxFrame(t, recipient)
	writeMailboxFrame(t, recipient, map[string]any{
		"v": 2, "type": "relay.ack", "msg_id": msgID, "envelope_sha256": hex.EncodeToString(digest[:]),
	})
	waitForPendingCount(t, srv, pair.deviceB, 0)
	writeMailboxFrame(t, recipient, map[string]any{
		"v": 2, "type": "relay.ack", "msg_id": msgID, "envelope_sha256": strings.Repeat("0", 64),
	})
	if rejected := readMailboxRejected(t, recipient); rejected.MsgID != msgID || rejected.Reason != "digest_mismatch" {
		t.Fatalf("duplicate wrong-digest ACK = %#v, want digest_mismatch", rejected)
	}
}

func TestWebSocketTerminalDuplicatePutDoesNotRedeliver(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)

	msgID := "82333333-3333-4333-8333-333333333333"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": envelope})
	firstAccepted := readMailboxFrame(t, sender)
	if firstAccepted.Type != "relay.accepted" {
		t.Fatalf("first put = %#v, want accepted", firstAccepted)
	}
	if delivered := readMailboxFrame(t, recipient); delivered.Type != "relay.deliver" {
		t.Fatalf("first delivery = %#v, want deliver", delivered)
	}
	digest := sha256.Sum256(envelope)
	writeMailboxFrame(t, recipient, map[string]any{
		"v": 2, "type": "relay.ack", "msg_id": msgID, "envelope_sha256": hex.EncodeToString(digest[:]),
	})
	waitForPendingCount(t, srv, pair.deviceB, 0)

	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": envelope})
	duplicateAccepted := readMailboxFrame(t, sender)
	if duplicateAccepted.Type != "relay.accepted" || duplicateAccepted.MsgID != msgID {
		t.Fatalf("terminal duplicate put = %#v, want idempotent accepted", duplicateAccepted)
	}
	_ = recipient.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	if _, raw, err := recipient.ReadMessage(); err == nil {
		t.Fatalf("terminal duplicate resurrected live delivery: %s", raw)
	}
}

func TestWebSocketHelloSendsExpiryBeforeMailboxDelivery(t *testing.T) {
	srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 10, MaxBytes: 1 << 20, Retention: time.Hour})
	pair := registerMailboxTestPair(t, srv)
	expiredID := "83111111-1111-4111-8111-111111111111"
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, expiredID), time.Now().Add(-2*time.Hour))
	pendingID := "83222222-2222-4222-8222-222222222222"
	pendingEnvelope := validMailboxEnvelope(pair.deviceB, pendingID)
	putMailboxRecord(t, srv, pair.deviceA, pair.deviceB, pendingEnvelope, time.Now())

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	status := readMailboxFrame(t, sender)
	if status.Type != "relay.expired" || status.MsgID != expiredID {
		t.Fatalf("first post-capabilities frame = %#v, want expired status", status)
	}
	delivered := readMailboxFrame(t, sender)
	if delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(pendingEnvelope) {
		t.Fatalf("second post-capabilities frame = %#v, want mailbox delivery", delivered)
	}
}

func TestWebSocketHelloPagesExpiryStatusesUnderTerminalChurn(t *testing.T) {
	const (
		ackCount     = 70
		expiredCount = 70
		statusPage   = 64
	)
	now := time.Now().Truncate(time.Millisecond)
	srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 80, MaxBytes: 1 << 20, Retention: time.Hour})
	pair := registerMailboxTestPair(t, srv)

	for i := 0; i < ackCount; i++ {
		msgID := fmt.Sprintf("92%06x-1111-4111-8111-%012x", i, i)
		envelope := validMailboxEnvelope(pair.deviceA, msgID)
		putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, envelope, now.Add(-30*time.Minute))
		digest := sha256.Sum256(envelope)
		if err := srv.mailbox.Ack(pair.deviceB, msgID, hex.EncodeToString(digest[:]), now); err != nil {
			t.Fatalf("ack churn item %d: %v", i, err)
		}
	}
	for i := 0; i < expiredCount; i++ {
		msgID := fmt.Sprintf("93%06x-1111-4111-8111-%012x", i, i)
		putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), now.Add(-2*time.Hour))
	}
	if expired, err := srv.mailbox.Expire(now); err != nil || len(expired) != expiredCount {
		t.Fatalf("expire churn = %d, %v; want %d", len(expired), err, expiredCount)
	}

	statuses, err := srv.mailbox.Statuses(pair.deviceA, time.UnixMilli(0))
	if err != nil || len(statuses) != ackCount+expiredCount {
		t.Fatalf("terminal statuses = %d, %v; want %d", len(statuses), err, ackCount+expiredCount)
	}
	for _, status := range statuses {
		if status.SenderDevice != pair.deviceA || status.RecipientDevice != pair.deviceB ||
			status.AcceptedAt == 0 || status.EnvelopeSHA256 == "" || status.MailboxExpiresAt == 0 ||
			status.ExpiresAt != now.Add(24*time.Hour).UnixMilli() {
			t.Fatalf("terminal identity changed: %#v", status)
		}
		raw, err := json.Marshal(status)
		if err != nil || strings.Contains(string(raw), "ciphertext") {
			t.Fatalf("terminal status leaked ciphertext: %s, %v", raw, err)
		}
	}

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	readPage := func() []string {
		conn := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		sendMailboxHello(t, conn)
		msgIDs := []string{}
		for {
			_ = conn.SetReadDeadline(time.Now().Add(150 * time.Millisecond))
			_, raw, err := conn.ReadMessage()
			if err != nil {
				_ = conn.Close()
				return msgIDs
			}
			var frame testFrame
			if err := json.Unmarshal(raw, &frame); err != nil || frame.Type != "relay.expired" {
				t.Fatalf("hello status frame = %s, %v; want relay.expired", raw, err)
			}
			msgIDs = append(msgIDs, frame.MsgID)
		}
	}
	expiryID := func(i int) string {
		return fmt.Sprintf("93%06x-1111-4111-8111-%012x", i, i)
	}
	wantPage := func(start int) []string {
		want := make([]string, 0, statusPage)
		for i := 0; i < statusPage; i++ {
			want = append(want, expiryID((start+i)%expiredCount))
		}
		return want
	}
	for pageNumber, tc := range []struct {
		start int
		want  []string
	}{
		{start: 0, want: wantPage(0)},
		{start: 64, want: wantPage(64)},
		{start: 58, want: wantPage(58)},
	} {
		if got := readPage(); !reflect.DeepEqual(got, tc.want) {
			t.Fatalf("hello expiry rotation page %d from %d = %#v, want %#v", pageNumber+1, tc.start, got, tc.want)
		}
	}
	statuses, err = srv.mailbox.Statuses(pair.deviceA, time.UnixMilli(0))
	if err != nil || len(statuses) != ackCount+expiredCount {
		t.Fatalf("paging changed 24h tombstones: %d, %v", len(statuses), err)
	}
}

func TestWebSocketHelloRetriesExpiryAfterSuccessfulWriteAndClientDeath(t *testing.T) {
	now := time.Now().Truncate(time.Millisecond)
	srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 2, MaxBytes: 1 << 20, Retention: time.Hour})
	pair := registerMailboxTestPair(t, srv)
	msgID := "93888888-8888-4888-8888-888888888888"
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), now.Add(-2*time.Hour))
	if expired, err := srv.mailbox.Expire(now); err != nil || len(expired) != 1 {
		t.Fatalf("expire retry item = %#v, %v", expired, err)
	}

	helloCompleted := make(chan struct{})
	releaseFirst := make(chan struct{})
	var first sync.Once
	srv.relayHelloBeforeActivate = func(deviceID string) {
		if deviceID == pair.deviceA {
			first.Do(func() {
				close(helloCompleted)
				<-releaseFirst
			})
		}
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	deadClient := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	writeMailboxFrame(t, deadClient, map[string]any{
		"v": 2, "type": "relay.hello", "protocols": []int{2, 1}, "app_version": "test",
	})
	select {
	case <-helloCompleted:
	case <-time.After(2 * time.Second):
		t.Fatal("first hello did not finish server-side status writes")
	}
	_ = deadClient.Close()
	close(releaseFirst)

	reconnected := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer reconnected.Close()
	sendMailboxHello(t, reconnected)
	_ = reconnected.SetReadDeadline(time.Now().Add(750 * time.Millisecond))
	_, raw, err := reconnected.ReadMessage()
	if err != nil {
		t.Fatalf("expiry lost after successful write and client death: %v", err)
	}
	var retried testFrame
	if err := json.Unmarshal(raw, &retried); err != nil || retried.Type != "relay.expired" || retried.MsgID != msgID {
		t.Fatalf("retried expiry = %s, %v; want %s", raw, err, msgID)
	}
}

func TestWebSocketHelloPartialExpiryWriteDoesNotAdvanceCursor(t *testing.T) {
	now := time.Now().Truncate(time.Millisecond)
	srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 3, MaxBytes: 1 << 20, Retention: time.Hour})
	pair := registerMailboxTestPair(t, srv)
	wantIDs := []string{
		"93999999-9999-4999-8999-999999999991",
		"93999999-9999-4999-8999-999999999992",
		"93999999-9999-4999-8999-999999999993",
	}
	for _, msgID := range wantIDs {
		putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), now.Add(-2*time.Hour))
	}
	if expired, err := srv.mailbox.Expire(now); err != nil || len(expired) != len(wantIDs) {
		t.Fatalf("expire partial-write items = %#v, %v", expired, err)
	}

	client := srv.clientHub.Register(pair.deviceA, make(chan []byte, mailboxBatchSize))
	defer srv.clientHub.Unregister(client)
	if !srv.clientHub.SetProtocolAndCapabilities(client, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set handshake protocol")
	}
	stopWrite := errors.New("stop after one expiry frame")
	writes := 0
	err := srv.handleRelayHello(pair.deviceA, client, RelayHello{
		V: 2, Type: "relay.hello", Protocols: []int{2, 1}, AppVersion: "test",
	}, func(any) error {
		writes++
		if writes == 3 { // capabilities and one expiry succeeded; second expiry fails.
			return stopWrite
		}
		return nil
	}, func([]string) {})
	if !errors.Is(err, stopWrite) {
		t.Fatalf("partial hello error = %v, want injected write failure", err)
	}
	page, err := srv.mailbox.ExpiryStatuses(pair.deviceA, pair.deviceB, mailboxBatchSize, now)
	if err != nil {
		t.Fatal(err)
	}
	gotIDs := make([]string, 0, len(page))
	for _, status := range page {
		gotIDs = append(gotIDs, status.MsgID)
	}
	if !reflect.DeepEqual(gotIDs, wantIDs) {
		t.Fatalf("partial write advanced past unseen statuses: %#v, want %#v", gotIDs, wantIDs)
	}
}

func TestWebSocketMailboxDrainIsBoundedTo64(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	for i := 1; i <= 65; i++ {
		msgID := fmt.Sprintf("%08x-1111-4111-8111-111111111111", i)
		putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, msgID), time.Now().Add(time.Duration(i)*time.Nanosecond))
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	for i := 1; i <= mailboxBatchSize; i++ {
		if frame := readMailboxFrame(t, recipient); frame.Type != "relay.deliver" {
			t.Fatalf("drain frame %d = %#v, want relay.deliver", i, frame)
		}
	}
	_ = recipient.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	if _, raw, err := recipient.ReadMessage(); err == nil {
		t.Fatalf("mailbox drain exceeded %d: %s", mailboxBatchSize, raw)
	}
}

func TestWebSocketMailboxDrainPreservesSameMillisecondAcceptanceOrder(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	acceptedAt := time.Now().Truncate(time.Millisecond)
	firstID := "f3333333-3333-4333-8333-333333333333"
	secondID := "13333333-3333-4333-8333-333333333333"
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, firstID), acceptedAt)
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, validMailboxEnvelope(pair.deviceA, secondID), acceptedAt)

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	for i, wantID := range []string{firstID, secondID} {
		delivered := readMailboxFrame(t, recipient)
		var header encryptedEnvelopeHeader
		if err := json.Unmarshal(delivered.Envelope, &header); err != nil {
			t.Fatalf("decode delivery %d: %v", i, err)
		}
		if delivered.Type != "relay.deliver" || header.MsgID != wantID {
			t.Fatalf("delivery %d = %#v with msg_id %q, want %s", i, delivered, header.MsgID, wantID)
		}
	}
}

func TestWebSocketHelloDrainHandoffDeliversConcurrentPutOnce(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	initialID := "83555555-5555-4555-8555-555555555555"
	initialEnvelope := validMailboxEnvelope(pair.deviceA, initialID)
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, initialEnvelope, time.Now())

	handoffReached := make(chan struct{})
	releaseHandoff := make(chan struct{})
	srv.relayHelloBeforeActivate = func(deviceID string) {
		if deviceID != pair.deviceB {
			return
		}
		close(handoffReached)
		<-releaseHandoff
	}

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	writeMailboxFrame(t, recipient, map[string]any{
		"v": 2, "type": "relay.hello", "protocols": []int{2, 1}, "app_version": "test",
	})
	if frame := readMailboxFrame(t, recipient); frame.Type != "relay.capabilities" {
		t.Fatalf("first hello frame = %#v, want capabilities", frame)
	}
	if frame := readMailboxFrame(t, recipient); frame.Type != "relay.deliver" || string(frame.Envelope) != string(initialEnvelope) {
		t.Fatalf("initial drain frame = %#v, want first durable envelope", frame)
	}
	select {
	case <-handoffReached:
	case <-time.After(2 * time.Second):
		t.Fatal("hello did not reach drain-to-live handoff")
	}

	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	liveID := "83666666-6666-4666-8666-666666666666"
	liveEnvelope := validMailboxEnvelope(pair.deviceA, liveID)
	writeMailboxFrame(t, sender, map[string]any{
		"v": 2, "type": "relay.put", "envelope": liveEnvelope,
	})
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" || accepted.MsgID != liveID {
		t.Fatalf("concurrent put response = %#v, want accepted", accepted)
	}
	close(releaseHandoff)

	if delivered := readMailboxFrame(t, recipient); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(liveEnvelope) {
		t.Fatalf("handoff delivery = %#v, want concurrent envelope", delivered)
	}
	_ = recipient.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	if _, raw, err := recipient.ReadMessage(); err == nil {
		t.Fatalf("handoff delivered a duplicate: %s", raw)
	}
}

func TestWebSocketWrappedV1PersistsWhileProtocolOnePeerHandshakes(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	handoffReached := make(chan struct{})
	releaseHandoff := make(chan struct{})
	srv.relayHelloBeforeActivate = func(deviceID string) {
		if deviceID != pair.deviceB {
			return
		}
		close(handoffReached)
		<-releaseHandoff
	}

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHelloWithProtocols(t, recipient, []int{1})
	select {
	case <-handoffReached:
	case <-time.After(2 * time.Second):
		t.Fatal("protocol-one peer did not reach typed handshake barrier")
	}

	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHelloWithProtocols(t, sender, []int{2, 1})
	msgID := "83677777-7777-4777-8777-777777777777"
	envelope := validLegacyMailboxEnvelope(pair.deviceA, msgID)
	writeMailboxFrame(t, sender, map[string]any{
		"v": 2, "type": "relay.put", "envelope": envelope,
	})
	response := readMailboxFrame(t, sender)
	close(releaseHandoff)
	if response.Type != "relay.accepted" || response.MsgID != msgID {
		t.Fatalf("wrapped v1 during typed [1] handshake = %#v, want relay.accepted", response)
	}
	var sawCapabilities, sawDelivery bool
	for range 2 {
		frame := readMailboxFrame(t, recipient)
		switch frame.Type {
		case "relay.capabilities":
			if frame.Floor != 1 || !reflect.DeepEqual(frame.Self, []int{1}) || !reflect.DeepEqual(frame.Peer, []int{2, 1}) {
				t.Fatalf("wrapped-v1 handshake capabilities = %#v", frame)
			}
			sawCapabilities = true
		case "relay.deliver":
			if string(frame.Envelope) != string(envelope) {
				t.Fatalf("wrapped v1 handshake envelope = %q, want %q", frame.Envelope, envelope)
			}
			sawDelivery = true
		default:
			t.Fatalf("wrapped-v1 handshake frame = %#v", frame)
		}
	}
	if !sawCapabilities || !sawDelivery {
		t.Fatalf("wrapped-v1 handshake saw capabilities=%v delivery=%v", sawCapabilities, sawDelivery)
	}
}

func TestWebSocketHelloHandoffSurvivesFailedAcceptedWrite(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	outbound := make(chan []byte, 4)
	recipient := srv.clientHub.Register(pair.deviceB, outbound)
	defer srv.clientHub.Unregister(recipient)
	if !srv.clientHub.SetProtocolAndCapabilities(recipient, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set recipient handshake protocol")
	}

	msgID := "83777777-7777-4777-8777-777777777777"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: envelope}, func(frame any) error {
		if _, ok := frame.(RelayAccepted); !ok {
			t.Fatalf("sender response = %#v, want relay.accepted", frame)
		}
		return errors.New("deterministic sender write failure")
	}, func(gotID, reason string) error {
		t.Fatalf("unexpected rejection for %s: %s", gotID, reason)
		return nil
	})

	if activated := srv.clientHub.FlushOrActivateV2(recipient, nil); !activated {
		t.Fatal("handoff after failed accepted write did not activate after queue transfer")
	}
	var raw []byte
	select {
	case raw = <-outbound:
	default:
		t.Fatal("handoff after failed accepted write queued no delivery")
	}
	if delivered := decodeMailboxFrame(t, raw); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
		t.Fatalf("handoff delivery = %#v, want exact durable envelope", delivered)
	}
	if got := len(srv.handoffs.recipients); got != 0 {
		t.Fatalf("idle handoff lanes after failed accepted write = %d, want 0", got)
	}
}

func TestWebSocketHelloHandoffPreservesSequenceAcrossReverseAcceptedWrites(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	outbound := make(chan []byte, 4)
	recipient := srv.clientHub.Register(pair.deviceB, outbound)
	defer srv.clientHub.Unregister(recipient)
	if !srv.clientHub.SetProtocolAndCapabilities(recipient, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set recipient handshake protocol")
	}

	firstID := "83888888-8888-4888-8888-888888888888"
	secondID := "83999999-9999-4999-8999-999999999999"
	firstAcceptedStarted := make(chan struct{})
	releaseFirstAccepted := make(chan struct{})
	firstDone := make(chan struct{})
	go func() {
		defer close(firstDone)
		srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, firstID)}, func(any) error {
			close(firstAcceptedStarted)
			<-releaseFirstAccepted
			return nil
		}, func(string, string) error { return errors.New("unexpected rejection") })
	}()
	select {
	case <-firstAcceptedStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("first accepted write did not start")
	}

	secondDone := make(chan struct{})
	go func() {
		defer close(secondDone)
		srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, secondID)}, func(any) error {
			return nil
		}, func(string, string) error { return errors.New("unexpected rejection") })
	}()
	select {
	case <-secondDone:
	case <-time.After(2 * time.Second):
		t.Fatal("second accepted write did not complete first")
	}

	if activated := srv.clientHub.FlushOrActivateV2(recipient, nil); !activated {
		t.Fatal("empty reverse-completion handoff did not activate")
	}
	close(releaseFirstAccepted)
	select {
	case <-firstDone:
	case <-time.After(2 * time.Second):
		t.Fatal("first accepted write did not complete")
	}

	for i, wantID := range []string{firstID, secondID} {
		select {
		case raw := <-outbound:
			delivered := decodeMailboxFrame(t, raw)
			var header encryptedEnvelopeHeader
			if err := json.Unmarshal(delivered.Envelope, &header); err != nil {
				t.Fatalf("decode delivery %d: %v", i, err)
			}
			if header.MsgID != wantID {
				t.Fatalf("delivery %d msg_id = %s, want %s", i, header.MsgID, wantID)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out waiting for delivery %d", i)
		}
	}
	if got := len(srv.handoffs.recipients); got != 0 {
		t.Fatalf("idle handoff lanes after overlapping puts = %d, want 0", got)
	}
}

func TestWebSocketDurableHandoffReclaimsManySequentialRecipients(t *testing.T) {
	srv := newTestServer(t)
	for i := 0; i < 128; i++ {
		sender := fmt.Sprintf("lane-sender-%03d", i)
		recipient := fmt.Sprintf("lane-recipient-%03d", i)
		if err := srv.pairStore.Confirm(store.ConfirmedPair{
			PairID: fmt.Sprintf("lane-pair-%03d", i), DeviceA: sender, DeviceB: recipient,
		}); err != nil {
			t.Fatalf("confirm pair %d: %v", i, err)
		}
		if err := srv.pairStore.UpdateCapabilities(sender, []int{2, 1}, "test"); err != nil {
			t.Fatalf("advertise sender %d capabilities: %v", i, err)
		}
		if err := srv.pairStore.UpdateCapabilities(recipient, []int{2, 1}, "test"); err != nil {
			t.Fatalf("advertise recipient %d capabilities: %v", i, err)
		}
		msgID := fmt.Sprintf("83dddddd-dddd-4ddd-8ddd-%012d", i)
		srv.handleRelayPut(sender, RelayPut{V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(sender, msgID)}, func(any) error {
			return nil
		}, func(gotID, reason string) error {
			t.Fatalf("recipient %d rejected %s: %s", i, gotID, reason)
			return nil
		})
	}
	if got := len(srv.handoffs.recipients); got != 0 {
		t.Fatalf("idle handoff lanes after sequential recipients = %d, want 0", got)
	}
}

type atomicClientDeliveryTransfer interface {
	TransferV2Batch(string, []queuedV2Notification, [][]byte) bool
	TransferHandshakeV2Batch(*wsClient, []queuedV2Notification, [][]byte) bool
}

func TestWebSocketDeliveryTransferUsesTypedTransportForProtocolOneEnvelope(t *testing.T) {
	for _, handshake := range []bool{false, true} {
		name := "live"
		if handshake {
			name = "handshake"
		}
		t.Run(name, func(t *testing.T) {
			hub := NewClientHub()
			outbound := make(chan []byte, 1)
			client := hub.Register("typed-protocol-one", outbound)
			protocol := protocolV2
			if handshake {
				protocol = protocolV2Handshake
			}
			if !hub.SetProtocolAndCapabilities(client, protocol, []int{1}) {
				t.Fatal("set typed protocol-one connection")
			}
			notices := []queuedV2Notification{{msgID: "83eeeeee-eeee-4eee-8eee-000000000001", sequence: 1, byteSize: 1}}
			frames := [][]byte{[]byte("v1-control-delivery")}
			var transferred bool
			if handshake {
				transferred = hub.TransferHandshakeV2Batch(client, notices, frames)
			} else {
				transferred = hub.TransferV2Batch(client.deviceID, notices, frames)
			}
			if !transferred {
				t.Fatal("typed protocol-one connection rejected relay control delivery")
			}
			select {
			case got := <-outbound:
				if string(got) != "v1-control-delivery" {
					t.Fatalf("queued control frame = %q", got)
				}
			default:
				t.Fatal("typed protocol-one connection queued no control delivery")
			}
		})
	}
}

func TestWebSocketDeliveryTransferLinearizesConnectionReplacement(t *testing.T) {
	for _, handshake := range []bool{false, true} {
		name := "live"
		if handshake {
			name = "handshake"
		}
		t.Run(name, func(t *testing.T) {
			srv := newTestServer(t)
			pair := registerMailboxTestPair(t, srv)
			msgID := "83eeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
			envelope := validMailboxEnvelope(pair.deviceA, msgID)
			putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, envelope, time.Now())
			pending, err := srv.mailbox.Pending(pair.deviceB, 1)
			if err != nil || len(pending) != 1 {
				t.Fatalf("pending transfer record = %#v, %v", pending, err)
			}
			sequence := pending[0].AcceptanceSequence

			oldOutbound := make(chan []byte, 2)
			old := srv.clientHub.Register(pair.deviceB, oldOutbound)
			protocol := protocolV2
			if handshake {
				protocol = protocolV2Handshake
			}
			if !srv.clientHub.SetProtocolAndCapabilities(old, protocol, []int{2, 1}) {
				t.Fatal("set old connection protocol")
			}
			atomicHub, ok := any(srv.clientHub).(atomicClientDeliveryTransfer)
			if !ok {
				t.Fatal("ClientHub lacks atomic bounded delivery-transfer API")
			}

			var replacement *wsClient
			replacementOutbound := make(chan []byte, 2)
			err = srv.mailbox.TransferLiveByIDs(pair.deviceB, []string{msgID}, time.Now(), func(records []store.MailboxRecord) error {
				if len(records) != 1 {
					t.Fatalf("validated records = %d, want 1", len(records))
				}
				replacement = srv.clientHub.Register(pair.deviceB, replacementOutbound)
				if !srv.clientHub.SetProtocolAndCapabilities(replacement, protocol, []int{2, 1}) {
					t.Fatal("set replacement protocol")
				}
				notices := []queuedV2Notification{{msgID: msgID, sequence: sequence, byteSize: uint64(len(envelope))}}
				frames := [][]byte{marshalRelayDeliver(records[0])}
				if handshake {
					if atomicHub.TransferHandshakeV2Batch(old, notices, frames) {
						t.Fatal("replaced handshaking connection accepted transfer")
					}
				} else if !atomicHub.TransferV2Batch(pair.deviceB, notices, frames) {
					t.Fatal("current live replacement rejected transfer")
				}
				return nil
			})
			if err != nil {
				t.Fatalf("atomic replacement transfer: %v", err)
			}
			select {
			case raw := <-oldOutbound:
				t.Fatalf("old connection received post-replacement frame: %s", raw)
			default:
			}
			select {
			case raw := <-replacementOutbound:
				if handshake {
					t.Fatalf("new handshake received old transfer: %s", raw)
				}
				got := decodeMailboxFrame(t, raw)
				var header encryptedEnvelopeHeader
				if err := json.Unmarshal(got.Envelope, &header); err != nil || header.MsgID != msgID {
					t.Fatalf("replacement delivery = %#v with header %#v, %v; want %s", got, header, err, msgID)
				}
			default:
				if !handshake {
					t.Fatal("current live replacement received no transfer")
				}
			}
			srv.clientHub.Unregister(replacement)
		})
	}
}

func TestClientHubSendCapabilitiesIsTypedBoundedAndReplacementSafe(t *testing.T) {
	update := []byte(`{"v":2,"type":"relay.capabilities","self":[2,1],"peer":[2,1],"floor":2}`)

	t.Run("current active typed connection", func(t *testing.T) {
		hub := NewClientHub()
		oldOutbound := make(chan []byte, 1)
		old := hub.Register("device", oldOutbound)
		if !hub.SetProtocolAndCapabilities(old, protocolV2, []int{2, 1}) {
			t.Fatal("set old typed protocol")
		}
		currentOutbound := make(chan []byte, 1)
		current := hub.Register("device", currentOutbound)
		if !hub.SetProtocolAndCapabilities(current, protocolV2, []int{2, 1}) {
			t.Fatal("set current typed protocol")
		}

		hub.SendCapabilities("device", []int{2, 1}, update)
		select {
		case raw := <-oldOutbound:
			t.Fatalf("replaced connection received capabilities: %s", raw)
		default:
		}
		select {
		case raw := <-currentOutbound:
			if string(raw) != string(update) {
				t.Fatalf("current connection update = %q, want %q", raw, update)
			}
		default:
			t.Fatal("current typed connection received no capability update")
		}
	})

	t.Run("matching handshake update follows mandatory sequence", func(t *testing.T) {
		hub := NewClientHub()
		outbound := make(chan []byte, 4)
		client := hub.Register("device", outbound)
		if !hub.SetProtocolAndCapabilities(client, protocolV2Handshake, []int{2, 1}) {
			t.Fatal("set handshake protocol")
		}
		mandatory := [][]byte{[]byte("initial"), []byte("status"), []byte("delivery")}
		for _, frame := range mandatory {
			outbound <- frame
		}
		hub.SendCapabilities("device", []int{2, 1}, update)
		if got := len(outbound); got != len(mandatory) {
			t.Fatalf("handshake queue length before activation = %d, want %d", got, len(mandatory))
		}
		if !hub.FlushOrActivateV2(client, nil) {
			t.Fatal("activate handshake")
		}
		for i, want := range append(mandatory, update) {
			select {
			case raw := <-outbound:
				if string(raw) != string(want) {
					t.Fatalf("activated frame %d = %q, want %q", i, raw, want)
				}
			default:
				t.Fatalf("activated handshake missing frame %d", i)
			}
		}
	})

	t.Run("reconnect rejects stale non-empty handshake self", func(t *testing.T) {
		hub := NewClientHub()
		outbound := make(chan []byte, 1)
		client := hub.Register("device", outbound)
		if !hub.SetProtocolAndCapabilities(client, protocolV2Handshake, []int{2, 1}) {
			t.Fatal("set reconnect handshake protocol")
		}
		stale := []byte(`{"v":2,"type":"relay.capabilities","self":[1],"peer":[2,1],"floor":1}`)
		hub.SendCapabilities("device", []int{1}, stale)
		if !hub.FlushOrActivateV2(client, nil) {
			t.Fatal("activate reconnect handshake")
		}
		select {
		case raw := <-outbound:
			t.Fatalf("reconnect emitted stale capabilities: %s", raw)
		default:
		}
	})

	t.Run("raw legacy receives no typed control", func(t *testing.T) {
		hub := NewClientHub()
		outbound := make(chan []byte, 1)
		client := hub.Register("device", outbound)
		if !hub.SetProtocol(client, protocolLegacy) {
			t.Fatal("set legacy protocol")
		}
		hub.SendCapabilities("device", []int{2, 1}, update)
		select {
		case raw := <-outbound:
			t.Fatalf("raw legacy received typed capabilities: %s", raw)
		default:
		}
		select {
		case <-client.done:
			t.Fatal("raw legacy was disconnected")
		default:
		}
	})

	t.Run("full typed queue forces reconnect", func(t *testing.T) {
		hub := NewClientHub()
		outbound := make(chan []byte, 1)
		client := hub.Register("device", outbound)
		if !hub.SetProtocolAndCapabilities(client, protocolV2, []int{2, 1}) {
			t.Fatal("set typed protocol")
		}
		outbound <- []byte("occupied")
		hub.SendCapabilities("device", []int{2, 1}, update)
		select {
		case <-client.done:
		case <-time.After(time.Second):
			t.Fatal("full typed queue did not force reconnect")
		}
	})
}

func TestWebSocketReconnectHandshakeRejectsStalePersistedSelfPropagation(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	if err := srv.pairStore.UpdateCapabilities(pair.deviceA, []int{1}, "old-a"); err != nil {
		t.Fatal(err)
	}
	if err := srv.pairStore.UpdateCapabilities(pair.deviceB, []int{1}, "old-b"); err != nil {
		t.Fatal(err)
	}

	aOutbound := make(chan []byte, 2)
	reconnectingA := srv.clientHub.Register(pair.deviceA, aOutbound)
	defer srv.clientHub.Unregister(reconnectingA)
	if !srv.clientHub.SetProtocolAndCapabilities(reconnectingA, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set A reconnect handshake")
	}
	bOutbound := make(chan []byte, 2)
	b := srv.clientHub.Register(pair.deviceB, bOutbound)
	defer srv.clientHub.Unregister(b)
	if !srv.clientHub.SetProtocolAndCapabilities(b, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set B peer handshake")
	}

	var bInitial RelayCapabilities
	if err := srv.handleRelayHello(pair.deviceB, b, RelayHello{
		V: 2, Type: "relay.hello", Protocols: []int{2, 1}, AppVersion: "new-b",
	}, func(frame any) error {
		capabilities, ok := frame.(RelayCapabilities)
		if !ok {
			t.Fatalf("B hello frame = %#v, want relay.capabilities", frame)
		}
		bInitial = capabilities
		return nil
	}, func([]string) {}); err != nil {
		t.Fatalf("B peer hello: %v", err)
	}
	if bInitial.Floor != 1 || !reflect.DeepEqual(bInitial.Self, []int{2, 1}) || !reflect.DeepEqual(bInitial.Peer, []int{1}) {
		t.Fatalf("B initial capabilities = %#v", bInitial)
	}

	if err := srv.pairStore.UpdateCapabilities(pair.deviceA, []int{2, 1}, "new-a"); err != nil {
		t.Fatal(err)
	}
	self, peer, floor, err := srv.pairStore.CapabilitiesFor(pair.deviceA)
	if err != nil {
		t.Fatal(err)
	}
	validInitial := relayCapabilitiesSnapshot(self, peer, floor)
	if validInitial.Floor != 2 || !reflect.DeepEqual(validInitial.Self, []int{2, 1}) {
		t.Fatalf("A valid post-persistence initial = %#v", validInitial)
	}
	if !srv.clientHub.FlushOrActivateV2(reconnectingA, nil) {
		t.Fatal("activate A reconnect")
	}
	select {
	case raw := <-aOutbound:
		t.Fatalf("A reconnect emitted stale persisted-self capabilities after valid initial: %s", raw)
	default:
	}
}

func TestWebSocketLiveDeliveryMutationWinsBeforeTransfer(t *testing.T) {
	mutations := []struct {
		name string
		run  func(*Server, mailboxTestPair, string, json.RawMessage) error
	}{
		{name: "ack", run: func(s *Server, pair mailboxTestPair, msgID string, envelope json.RawMessage) error {
			digest := sha256.Sum256(envelope)
			return s.mailbox.Ack(pair.deviceB, msgID, hex.EncodeToString(digest[:]), time.Now())
		}},
		{name: "expiry", run: func(s *Server, _ mailboxTestPair, _ string, _ json.RawMessage) error {
			_, err := s.mailbox.Expire(time.Now().Add(2 * time.Hour))
			return err
		}},
		{name: "purge", run: func(s *Server, pair mailboxTestPair, _ string, _ json.RawMessage) error {
			return s.mailbox.PurgePair(pair.deviceA, pair.deviceB)
		}},
	}
	for index, mutation := range mutations {
		t.Run(mutation.name, func(t *testing.T) {
			srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 4, MaxBytes: 1 << 20, Retention: time.Hour})
			pair := registerMailboxTestPair(t, srv)
			outbound := make(chan []byte, 2)
			recipient := srv.clientHub.Register(pair.deviceB, outbound)
			defer srv.clientHub.Unregister(recipient)
			if !srv.clientHub.SetProtocolAndCapabilities(recipient, protocolV2, []int{2, 1}) {
				t.Fatal("set live recipient protocol")
			}
			beforeTransfer := make(chan struct{})
			releaseTransfer := make(chan struct{})
			srv.relayBeforeDeliveryTransfer = func(deviceID string) {
				if deviceID == pair.deviceB {
					close(beforeTransfer)
					<-releaseTransfer
				}
			}
			msgID := fmt.Sprintf("83ffffff-ffff-4fff-8fff-%012d", index)
			envelope := validMailboxEnvelope(pair.deviceA, msgID)
			putDone := make(chan struct{})
			go func() {
				defer close(putDone)
				srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: envelope}, func(any) error {
					return nil
				}, func(gotID, reason string) error {
					t.Errorf("unexpected rejection for %s: %s", gotID, reason)
					return nil
				})
			}()
			<-beforeTransfer
			if err := mutation.run(srv, pair, msgID, envelope); err != nil {
				t.Fatalf("commit %s before transfer: %v", mutation.name, err)
			}
			close(releaseTransfer)
			<-putDone
			select {
			case raw := <-outbound:
				t.Fatalf("%s-winning live race emitted later frame: %s", mutation.name, raw)
			default:
			}
		})
	}
}

func TestWebSocketHelloHandoffDoesNotEmitExpiredBufferedItem(t *testing.T) {
	srv := newMailboxTestServerWithLimits(t, store.MailboxLimits{MaxItems: 2, MaxBytes: 1 << 20, Retention: time.Hour})
	pair := registerMailboxTestPair(t, srv)
	outbound := make(chan []byte, 4)
	recipient := srv.clientHub.Register(pair.deviceB, outbound)
	defer srv.clientHub.Unregister(recipient)
	if !srv.clientHub.SetProtocolAndCapabilities(recipient, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set recipient handshake protocol")
	}

	msgID := "83aaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, msgID)}, func(any) error {
		return nil
	}, func(string, string) error { return errors.New("unexpected rejection") })
	beforeTransfer := make(chan struct{})
	releaseTransfer := make(chan struct{})
	srv.relayBeforeDeliveryTransfer = func(deviceID string) {
		if deviceID == pair.deviceB {
			close(beforeTransfer)
			<-releaseTransfer
		}
	}
	activated := make(chan bool, 1)
	go func() { activated <- srv.clientHub.FlushOrActivateV2(recipient, nil) }()
	<-beforeTransfer
	if _, err := srv.mailbox.Expire(time.Now().Add(2 * time.Hour)); err != nil {
		t.Fatalf("expire buffered item: %v", err)
	}
	close(releaseTransfer)

	if !<-activated {
		t.Fatal("expired-only handoff did not activate")
	}
	select {
	case raw := <-outbound:
		t.Fatalf("expired handoff emitted stale ciphertext: %s", raw)
	default:
	}
}

func TestWebSocketHelloHandoffDoesNotEmitPurgedBufferedItem(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	outbound := make(chan []byte, 4)
	recipient := srv.clientHub.Register(pair.deviceB, outbound)
	defer srv.clientHub.Unregister(recipient)
	if !srv.clientHub.SetProtocolAndCapabilities(recipient, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set recipient handshake protocol")
	}

	msgID := "83aaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaab"
	srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, msgID)}, func(any) error {
		return nil
	}, func(string, string) error { return errors.New("unexpected rejection") })
	if err := srv.mailbox.PurgePair(pair.deviceA, pair.deviceB); err != nil {
		t.Fatalf("purge buffered item: %v", err)
	}

	if !srv.clientHub.FlushOrActivateV2(recipient, nil) {
		t.Fatal("purged-only handoff did not activate")
	}
	select {
	case raw := <-outbound:
		t.Fatalf("purged handoff emitted stale ciphertext: %s", raw)
	default:
	}
}

func TestWebSocketHelloHandoffExpireRefillClosesAtConfiguredBound(t *testing.T) {
	limits := store.MailboxLimits{MaxItems: 1, MaxBytes: 1 << 20, Retention: time.Hour}
	srv := newMailboxTestServerWithLimits(t, limits)
	pair := registerMailboxTestPair(t, srv)
	recipient := srv.clientHub.Register(pair.deviceB, make(chan []byte, 4))
	defer srv.clientHub.Unregister(recipient)
	if !srv.clientHub.SetProtocolAndCapabilities(recipient, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set recipient handshake protocol")
	}

	for i := 0; i < 4; i++ {
		msgID := fmt.Sprintf("83bbbbbb-bbbb-4bbb-8bbb-%012d", i)
		srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: validMailboxEnvelope(pair.deviceA, msgID)}, func(any) error {
			return nil
		}, func(string, string) error { return errors.New("unexpected rejection") })
		if _, err := srv.mailbox.Expire(time.Now().Add(2 * time.Hour)); err != nil {
			t.Fatalf("expire refill %d: %v", i, err)
		}
	}
	select {
	case <-recipient.done:
	default:
		t.Fatal("handshaking recipient remained open after repeated refill exceeded configured one-item bound")
	}
	if got := len(srv.handoffs.recipients); got != 0 {
		t.Fatalf("idle handoff lanes after overflow = %d, want 0", got)
	}
}

func TestWebSocketHelloHandoffClosesAtConfiguredByteBound(t *testing.T) {
	firstEnvelope := validMailboxEnvelope("mailbox-device-a", "83cccccc-cccc-4ccc-8ccc-000000000001")
	secondEnvelope := validMailboxEnvelope("mailbox-device-a", "83cccccc-cccc-4ccc-8ccc-000000000002")
	limits := store.MailboxLimits{
		MaxItems:  10,
		MaxBytes:  uint64(len(firstEnvelope) + len(secondEnvelope) - 1),
		Retention: time.Hour,
	}
	srv := newMailboxTestServerWithLimits(t, limits)
	pair := registerMailboxTestPair(t, srv)
	recipient := srv.clientHub.Register(pair.deviceB, make(chan []byte, 4))
	defer srv.clientHub.Unregister(recipient)
	if !srv.clientHub.SetProtocolAndCapabilities(recipient, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set recipient handshake protocol")
	}

	srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: firstEnvelope}, func(any) error {
		return nil
	}, func(string, string) error { return errors.New("unexpected rejection") })
	if _, err := srv.mailbox.Expire(time.Now().Add(2 * time.Hour)); err != nil {
		t.Fatalf("expire first buffered item: %v", err)
	}
	srv.handleRelayPut(pair.deviceA, RelayPut{V: 2, Type: "relay.put", Envelope: secondEnvelope}, func(any) error {
		return nil
	}, func(string, string) error { return errors.New("unexpected rejection") })

	select {
	case <-recipient.done:
	default:
		t.Fatal("handshaking recipient remained open after metadata exceeded configured mailbox byte bound")
	}
}

func decodeMailboxFrame(t *testing.T, raw []byte) testFrame {
	t.Helper()
	var frame testFrame
	if err := json.Unmarshal(raw, &frame); err != nil {
		t.Fatalf("decode typed frame %q: %v", raw, err)
	}
	return frame
}

func TestWebSocketHashesAndDeliversExactEnvelopeBytes(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	msgID := "84111111-1111-4111-8111-111111111111"
	envelope := json.RawMessage(`{ "v": 2, "type": "enc", "msg_id": "84111111-1111-4111-8111-111111111111", "origin_device": "mailbox-device-a", "created_at": 1786267348000, "nonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "ciphertext": "Y2lwaGVydGV4dA==" }`)
	put := append([]byte(`{"v":2,"type":"relay.put","envelope":`), envelope...)
	put = append(put, '}')
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	sendMailboxHello(t, sender)
	if err := sender.WriteMessage(websocket.TextMessage, put); err != nil {
		t.Fatalf("write exact-byte put: %v", err)
	}
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" || accepted.MsgID != msgID {
		t.Fatalf("put response = %#v, want relay.accepted", accepted)
	}
	_ = sender.Close()

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	delivered := readMailboxFrame(t, recipient)
	if delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
		t.Fatalf("delivered envelope bytes = %q, want %q", delivered.Envelope, envelope)
	}
	digest := sha256.Sum256(envelope)
	writeMailboxFrame(t, recipient, map[string]any{"v": 2, "type": "relay.ack", "msg_id": msgID, "envelope_sha256": hex.EncodeToString(digest[:])})
	waitForPendingCount(t, srv, pair.deviceB, 0)
}

func TestWebSocketCapabilitiesBeforePeerHello(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	client := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer client.Close()
	capabilities := sendMailboxHelloWithProtocols(t, client, []int{2, 1})
	if !reflect.DeepEqual(capabilities.Self, []int{2, 1}) {
		t.Fatalf("self capabilities = %v, want [2 1]", capabilities.Self)
	}
	if !reflect.DeepEqual(capabilities.Peer, []int{1}) {
		t.Fatalf("peer capabilities = %v, want legacy default [1]", capabilities.Peer)
	}
	if capabilities.Floor != 1 {
		t.Fatalf("protocol floor = %d, want 1", capabilities.Floor)
	}
}

func TestWebSocketWrappedV1PersistsForOfflineTypedPeerAtFloorOne(t *testing.T) {
	tests := []struct {
		name            string
		peerProtocols   []int
		senderProtocols []int
		msgID           string
	}{
		{
			name:          "typed protocol one",
			peerProtocols: []int{1}, senderProtocols: []int{2, 1},
			msgID: "84811111-1111-4111-8111-111111111111",
		},
		{
			name:          "typed dual read",
			peerProtocols: []int{2, 1}, senderProtocols: []int{1},
			msgID: "84822222-2222-4222-8222-222222222222",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			srv := newTestServer(t)
			pair := registerMailboxTestPairWithoutCapabilities(t, srv)
			ts := httptest.NewServer(srv.Handler())
			defer ts.Close()

			peer := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
			if capabilities := sendMailboxHelloWithProtocols(t, peer, test.peerProtocols); capabilities.Floor != 1 {
				t.Fatalf("peer hello floor = %d, want 1", capabilities.Floor)
			}
			if err := peer.Close(); err != nil {
				t.Fatalf("close typed peer: %v", err)
			}
			waitForMailboxOffline(t, srv, pair.deviceB)

			sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
			defer sender.Close()
			if capabilities := sendMailboxHelloWithProtocols(t, sender, test.senderProtocols); capabilities.Floor != 1 {
				t.Fatalf("sender hello floor = %d, want 1", capabilities.Floor)
			}
			envelope := validLegacyMailboxEnvelope(pair.deviceA, test.msgID)
			writeMailboxFrame(t, sender, map[string]any{
				"v": 2, "type": "relay.put", "envelope": envelope,
			})
			if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" || accepted.MsgID != test.msgID {
				t.Fatalf("offline typed wrapped-v1 response = %#v, want relay.accepted", accepted)
			}
			pending := waitForPendingCount(t, srv, pair.deviceB, 1)
			if string(pending[0].Envelope) != string(envelope) {
				t.Fatalf("stored envelope = %q, want exact %q", pending[0].Envelope, envelope)
			}

			reconnected := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
			defer reconnected.Close()
			if capabilities := sendMailboxHelloWithProtocols(t, reconnected, test.peerProtocols); capabilities.Floor != 1 {
				t.Fatalf("reconnected peer floor = %d, want 1", capabilities.Floor)
			}
			if delivered := readMailboxFrame(t, reconnected); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
				t.Fatalf("reconnected wrapped-v1 delivery = %#v, want exact envelope", delivered)
			}
		})
	}
}

func TestWebSocketV2RequiresBothPersistentAdvertisements(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHelloWithProtocols(t, sender, []int{2, 1})
	firstID := "84911111-1111-4111-8111-111111111111"
	writeMailboxFrame(t, sender, map[string]any{
		"v": 2, "type": "relay.put", "envelope": validMailboxEnvelope(pair.deviceA, firstID),
	})
	if rejected := readMailboxRejected(t, sender); rejected.MsgID != firstID || rejected.Reason != "peer_legacy" {
		t.Fatalf("v2 before peer advertisement = %#v, want peer_legacy", rejected)
	}
	waitForPendingCount(t, srv, pair.deviceB, 0)

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	capabilities := sendMailboxHelloWithProtocols(t, recipient, []int{2, 1})
	if !reflect.DeepEqual(capabilities.Peer, []int{2, 1}) || capabilities.Floor != 2 {
		t.Fatalf("second hello capabilities = %#v, want persisted peer [2 1] and floor 2", capabilities)
	}
	if propagated := readMailboxFrame(t, sender); propagated.Type != "relay.capabilities" {
		t.Fatalf("sender propagation = %#v, want relay.capabilities", propagated)
	}

	secondID := "84922222-2222-4222-8222-222222222222"
	secondEnvelope := validMailboxEnvelope(pair.deviceA, secondID)
	writeMailboxFrame(t, sender, map[string]any{
		"v": 2, "type": "relay.put", "envelope": secondEnvelope,
	})
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" || accepted.MsgID != secondID {
		t.Fatalf("v2 after both advertisements = %#v, want relay.accepted", accepted)
	}
	if delivered := readMailboxFrame(t, recipient); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(secondEnvelope) {
		t.Fatalf("v2 delivery after negotiation = %#v", delivered)
	}
}

func TestWebSocketHelloPropagatesFloorToBothLiveTypedPeers(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	first := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer first.Close()
	firstCapabilities := sendMailboxHelloWithProtocols(t, first, []int{2, 1})
	if firstCapabilities.Floor != 1 || !reflect.DeepEqual(firstCapabilities.Peer, []int{1}) {
		t.Fatalf("first hello capabilities = %#v, want peer [1] and floor 1", firstCapabilities)
	}

	second := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer second.Close()
	secondCapabilities := sendMailboxHelloWithProtocols(t, second, []int{2, 1})
	if secondCapabilities.Floor != 2 || !reflect.DeepEqual(secondCapabilities.Peer, []int{2, 1}) {
		t.Fatalf("second hello capabilities = %#v, want peer [2 1] and floor 2", secondCapabilities)
	}

	_ = first.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	_, raw, err := first.ReadMessage()
	if err != nil {
		t.Fatalf("first peer did not receive propagated capabilities: %v", err)
	}
	var propagated testCapabilitiesFrame
	if err := json.Unmarshal(raw, &propagated); err != nil {
		t.Fatalf("decode propagated capabilities %q: %v", raw, err)
	}
	if propagated.V != 2 || propagated.Type != "relay.capabilities" || propagated.Floor != 2 ||
		!reflect.DeepEqual(propagated.Self, []int{2, 1}) || !reflect.DeepEqual(propagated.Peer, []int{2, 1}) {
		t.Fatalf("propagated capabilities = %#v, want both [2 1] and floor 2", propagated)
	}
}

func TestWebSocketSimultaneousFreshHellosNeverRegressCapabilities(t *testing.T) {
	srv, bolt := newMailboxTestServerWithBolt(t)
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	held := make(chan struct{})
	release := make(chan struct{})
	writeDone := make(chan error, 1)
	go func() {
		writeDone <- bolt.Update(func(*bbolt.Tx) error {
			close(held)
			<-release
			return nil
		})
	}()
	<-held

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	a := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer a.Close()
	b := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer b.Close()
	writeMailboxFrame(t, a, map[string]any{
		"v": 2, "type": "relay.hello", "protocols": []int{2, 1}, "app_version": "simultaneous-a",
	})
	writeMailboxFrame(t, b, map[string]any{
		"v": 2, "type": "relay.hello", "protocols": []int{2, 1}, "app_version": "simultaneous-b",
	})
	waitForMailboxProtocol(t, srv, pair.deviceA, protocolV2Handshake)
	waitForMailboxProtocol(t, srv, pair.deviceB, protocolV2Handshake)
	close(release)
	if err := <-writeDone; err != nil {
		t.Fatalf("release Bolt barrier: %v", err)
	}

	initialA := sendMailboxReadCapabilities(t, a, 2*time.Second)
	initialB := sendMailboxReadCapabilities(t, b, 2*time.Second)
	for device, initial := range map[string]testCapabilitiesFrame{"a": initialA, "b": initialB} {
		if initial.Type != "relay.capabilities" || !reflect.DeepEqual(initial.Self, []int{2, 1}) {
			t.Fatalf("%s initial capabilities = %#v, want self [2 1]", device, initial)
		}
	}

	updates := 0
	for device, connection := range map[string]*websocket.Conn{"a": a, "b": b} {
		initialFloor := initialA.Floor
		if device == "b" {
			initialFloor = initialB.Floor
		}
		_ = connection.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
		for {
			_, raw, err := connection.ReadMessage()
			if err != nil {
				break
			}
			var update testCapabilitiesFrame
			if err := json.Unmarshal(raw, &update); err != nil {
				t.Fatalf("decode %s post-initial frame %q: %v", device, raw, err)
			}
			if update.Type != "relay.capabilities" {
				t.Fatalf("%s post-initial frame = %#v", device, update)
			}
			updates++
			if !reflect.DeepEqual(update.Self, []int{2, 1}) || !reflect.DeepEqual(update.Peer, []int{2, 1}) || update.Floor < initialFloor {
				t.Fatalf("%s regressive post-initial capabilities = %#v after floor %d", device, update, initialFloor)
			}
		}
	}
	if updates == 0 {
		t.Fatal("simultaneous hellos produced no valid peer update")
	}
}

func sendMailboxReadCapabilities(t *testing.T, conn *websocket.Conn, timeout time.Duration) testCapabilitiesFrame {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	_, raw, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read capabilities: %v", err)
	}
	var capabilities testCapabilitiesFrame
	if err := json.Unmarshal(raw, &capabilities); err != nil {
		t.Fatalf("decode capabilities %q: %v", raw, err)
	}
	return capabilities
}

func TestWebSocketProtocolFloorTwoRejectsV1AndSurvivesReconnect(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPairWithoutCapabilities(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	a := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	sendMailboxHelloWithProtocols(t, a, []int{2, 1})
	b := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer b.Close()
	if capabilities := sendMailboxHelloWithProtocols(t, b, []int{2, 1}); capabilities.Floor != 2 {
		t.Fatalf("negotiated floor = %d, want 2", capabilities.Floor)
	}
	if err := a.Close(); err != nil {
		t.Fatalf("close first sender: %v", err)
	}

	reconnected := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	capabilities := sendMailboxHelloWithProtocols(t, reconnected, []int{2, 1})
	if capabilities.Floor != 2 || !reflect.DeepEqual(capabilities.Peer, []int{2, 1}) {
		t.Fatalf("reconnected capabilities = %#v, want persisted floor 2", capabilities)
	}
	if err := reconnected.Close(); err != nil {
		t.Fatalf("close reconnected sender: %v", err)
	}

	rawSender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer rawSender.Close()
	rawID := "84933333-3333-4333-8333-333333333333"
	if err := rawSender.WriteMessage(websocket.TextMessage, validLegacyMailboxEnvelope(pair.deviceA, rawID)); err != nil {
		t.Fatalf("write raw v1 after floor 2: %v", err)
	}
	if rejected := readMailboxRejected(t, rawSender); rejected.MsgID != rawID || rejected.Reason != "invalid_frame" {
		t.Fatalf("raw v1 after floor 2 = %#v, want invalid_frame", rejected)
	}
	_ = b.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
	if _, raw, err := b.ReadMessage(); err == nil {
		t.Fatalf("floor-2 peer received raw v1 downgrade: %s", raw)
	}

	typedSender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer typedSender.Close()
	sendMailboxHelloWithProtocols(t, typedSender, []int{2, 1})
	wrappedID := "84944444-4444-4444-8444-444444444444"
	writeMailboxFrame(t, typedSender, map[string]any{
		"v": 2, "type": "relay.put", "envelope": validLegacyMailboxEnvelope(pair.deviceA, wrappedID),
	})
	if rejected := readMailboxRejected(t, typedSender); rejected.MsgID != wrappedID || rejected.Reason != "peer_legacy" {
		t.Fatalf("wrapped v1 after floor 2 = %#v, want peer_legacy", rejected)
	}
	waitForPendingCount(t, srv, pair.deviceB, 0)
}

func TestWebSocketCorruptProtocolFloorFailsClosed(t *testing.T) {
	t.Run("hello control", func(t *testing.T) {
		srv, bolt := newMailboxTestServerWithBolt(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		corruptMailboxPairFloor(t, bolt, []byte{3})
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()

		client := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer client.Close()
		writeMailboxFrame(t, client, map[string]any{
			"v": 2, "type": "relay.hello", "protocols": []int{2, 1}, "app_version": "test",
		})
		_ = client.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
		if _, raw, err := client.ReadMessage(); err == nil {
			t.Fatalf("hello with corrupt floor received control response: %s", raw)
		}
		waitForMailboxOffline(t, srv, pair.deviceA)
	})

	t.Run("raw v1", func(t *testing.T) {
		srv, bolt := newMailboxTestServerWithBolt(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		peer := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer peer.Close()
		sendMailboxHelloWithProtocols(t, peer, []int{1})
		corruptMailboxPairFloor(t, bolt, []byte{1})

		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		msgID := "84955555-5555-4555-8555-555555555555"
		if err := sender.WriteMessage(websocket.TextMessage, validLegacyMailboxEnvelope(pair.deviceA, msgID)); err != nil {
			t.Fatalf("write raw v1: %v", err)
		}
		_ = sender.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
		_, raw, err := sender.ReadMessage()
		if err != nil {
			t.Fatalf("raw v1 corrupt-floor rejection: %v", err)
		}
		var rejected testRejectedFrame
		if err := json.Unmarshal(raw, &rejected); err != nil {
			t.Fatalf("decode raw-v1 rejection %q: %v", raw, err)
		}
		if rejected.Type != "relay.rejected" || rejected.MsgID != msgID || rejected.Reason != "invalid_frame" {
			t.Fatalf("raw v1 corrupt-floor response = %#v, want invalid_frame", rejected)
		}
		_ = peer.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
		if _, raw, err := peer.ReadMessage(); err == nil {
			t.Fatalf("peer received raw v1 through corrupt floor: %s", raw)
		}
	})

	t.Run("relay put control", func(t *testing.T) {
		srv, bolt := newMailboxTestServerWithBolt(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()

		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		sendMailboxHelloWithProtocols(t, sender, []int{2, 1})
		peer := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer peer.Close()
		sendMailboxHelloWithProtocols(t, peer, []int{2, 1})
		if propagated := readMailboxFrame(t, sender); propagated.Type != "relay.capabilities" {
			t.Fatalf("precondition propagated frame = %#v", propagated)
		}
		corruptMailboxPairFloor(t, bolt, []byte{1})

		msgID := "84966666-6666-4666-8666-666666666666"
		writeMailboxFrame(t, sender, map[string]any{
			"v": 2, "type": "relay.put", "envelope": validLegacyMailboxEnvelope(pair.deviceA, msgID),
		})
		if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "not_recipient" {
			t.Fatalf("relay.put corrupt-floor response = %#v, want not_recipient", rejected)
		}
		waitForPendingCount(t, srv, pair.deviceB, 0)
	})
}

func TestWebSocketV1EnvelopeCompatibility(t *testing.T) {
	t.Run("typed peer advertises its actual live protocols and rejects v2 before put", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()

		peer := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer peer.Close()
		peerCapabilities := sendMailboxHelloWithProtocols(t, peer, []int{1})
		if !reflect.DeepEqual(peerCapabilities.Self, []int{1}) {
			t.Fatalf("peer self capabilities = %v, want [1]", peerCapabilities.Self)
		}
		waitForMailboxProtocol(t, srv, pair.deviceB, protocolV2)

		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		senderCapabilities := sendMailboxHelloWithProtocols(t, sender, []int{2, 1})
		if !reflect.DeepEqual(senderCapabilities.Peer, []int{1}) {
			t.Fatalf("advertised live peer protocols = %v, want [1]", senderCapabilities.Peer)
		}

		msgID := "85011111-1111-4111-8111-111111111111"
		writeMailboxFrame(t, sender, map[string]any{
			"v": 2, "type": "relay.put", "envelope": validMailboxEnvelope(pair.deviceA, msgID),
		})
		if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "peer_legacy" {
			t.Fatalf("v2 to live [1] peer = %#v, want peer_legacy", rejected)
		}
		waitForPendingCount(t, srv, pair.deviceB, 0)
	})

	t.Run("raw v1 sender forwards to ready typed dual-read peer while floor is one", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()

		peer := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer peer.Close()
		sendMailboxHelloWithProtocols(t, peer, []int{2, 1})
		waitForMailboxProtocol(t, srv, pair.deviceB, protocolV2)

		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		envelope := validLegacyMailboxEnvelope(pair.deviceA, "85022222-2222-4222-8222-222222222222")
		if err := sender.WriteMessage(websocket.TextMessage, envelope); err != nil {
			t.Fatalf("write raw v1 envelope: %v", err)
		}
		_ = peer.SetReadDeadline(time.Now().Add(2 * time.Second))
		_, got, err := peer.ReadMessage()
		if err != nil {
			t.Fatalf("read forwarded raw v1 envelope: %v", err)
		}
		if string(got) != string(envelope) {
			t.Fatalf("typed dual-read peer received %q, want exact raw v1 %q", got, envelope)
		}
		waitForPendingCount(t, srv, pair.deviceB, 0)
	})

	t.Run("live legacy peer is raw online-only", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		legacyPeer := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer legacyPeer.Close()
		selection := validLegacyMailboxEnvelope(pair.deviceB, "85111111-1111-4111-8111-111111111111")
		if err := legacyPeer.WriteMessage(websocket.TextMessage, selection); err != nil {
			t.Fatalf("select legacy protocol: %v", err)
		}
		waitForMailboxProtocol(t, srv, pair.deviceB, protocolLegacy)

		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		sendMailboxHello(t, sender)
		msgID := "85222222-2222-4222-8222-222222222222"
		envelope := validLegacyMailboxEnvelope(pair.deviceA, msgID)
		writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": envelope})
		if response := readMailboxFrame(t, sender); response.Type != "relay.legacy_forwarded" || response.MsgID != msgID {
			t.Fatalf("compatibility response = %#v, want relay.legacy_forwarded", response)
		}
		_ = legacyPeer.SetReadDeadline(time.Now().Add(2 * time.Second))
		_, got, err := legacyPeer.ReadMessage()
		if err != nil {
			t.Fatalf("read raw legacy envelope: %v", err)
		}
		if string(got) != string(envelope) {
			t.Fatalf("legacy peer received %q, want exact raw envelope %q", got, envelope)
		}
		pending, err := srv.mailbox.Pending(pair.deviceB, 10)
		if err != nil || len(pending) != 0 {
			t.Fatalf("legacy online-only frame was persisted: %#v, %v", pending, err)
		}
	})

	t.Run("known live legacy peer rejects v2 envelope", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		legacyPeer := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer legacyPeer.Close()
		if err := legacyPeer.WriteMessage(websocket.TextMessage, validLegacyMailboxEnvelope(pair.deviceB, "85122222-2222-4222-8222-222222222222")); err != nil {
			t.Fatalf("select legacy protocol: %v", err)
		}
		waitForMailboxProtocol(t, srv, pair.deviceB, protocolLegacy)

		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		sendMailboxHello(t, sender)
		msgID := "85133333-3333-4333-8333-333333333333"
		writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": validMailboxEnvelope(pair.deviceA, msgID)})
		if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "peer_legacy" {
			t.Fatalf("known legacy v2 response = %#v, want peer_legacy", rejected)
		}
		waitForPendingCount(t, srv, pair.deviceB, 0)
	})

	t.Run("unselected peer is not reported as legacy forwarded", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		unselected := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer unselected.Close()
		waitForMailboxProtocol(t, srv, pair.deviceB, protocolUnknown)

		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		sendMailboxHello(t, sender)
		msgID := "85233333-3333-4333-8333-333333333333"
		writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": validLegacyMailboxEnvelope(pair.deviceA, msgID)})
		if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "peer_legacy" {
			t.Fatalf("unselected peer response = %#v, want peer_legacy", rejected)
		}
		_ = unselected.SetReadDeadline(time.Now().Add(250 * time.Millisecond))
		if _, raw, err := unselected.ReadMessage(); err == nil {
			t.Fatalf("unselected peer received raw v1 frame: %s", raw)
		}
	})

	t.Run("offline legacy peer is explicitly rejected", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		sendMailboxHello(t, sender)
		msgID := "85333333-3333-4333-8333-333333333333"
		writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": validLegacyMailboxEnvelope(pair.deviceA, msgID)})
		if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "peer_legacy" {
			t.Fatalf("offline legacy response = %#v, want peer_legacy", rejected)
		}
		pending, err := srv.mailbox.Pending(pair.deviceB, 10)
		if err != nil || len(pending) != 0 {
			t.Fatalf("offline legacy frame was persisted: %#v, %v", pending, err)
		}
	})

	t.Run("live v2 peer receives durable wrapped v1", func(t *testing.T) {
		srv := newTestServer(t)
		pair := registerMailboxTestPairWithoutCapabilities(t, srv)
		ts := httptest.NewServer(srv.Handler())
		defer ts.Close()
		recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
		defer recipient.Close()
		sendMailboxHello(t, recipient)
		waitForMailboxProtocol(t, srv, pair.deviceB, protocolV2)
		sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
		defer sender.Close()
		sendMailboxHelloWithProtocols(t, sender, []int{1})
		msgID := "85444444-4444-4444-8444-444444444444"
		envelope := validLegacyMailboxEnvelope(pair.deviceA, msgID)
		writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": envelope})
		if response := readMailboxFrame(t, sender); response.Type != "relay.accepted" || response.MsgID != msgID {
			t.Fatalf("v2 compatibility response = %#v, want relay.accepted", response)
		}
		if delivered := readMailboxFrame(t, recipient); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
			t.Fatalf("v2 compatibility delivery = %#v, want wrapped v1 envelope", delivered)
		}
		pending, err := srv.mailbox.Pending(pair.deviceB, 10)
		if err != nil || len(pending) != 1 || pending[0].MsgID != msgID {
			t.Fatalf("wrapped v1 envelope not durable: %#v, %v", pending, err)
		}
	})
}

func TestWebSocketAcceptsOneMiBEnvelopeInsideRelayPut(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	msgID := "89111111-1111-4111-8111-111111111111"
	envelope := maxSizeMailboxEnvelope(t, pair.deviceA, msgID)
	put := append([]byte(`{"v":2,"type":"relay.put","envelope":`), envelope...)
	put = append(put, '}')
	if len(put) <= maxMessageSize {
		t.Fatalf("wrapped frame length = %d, want greater than raw envelope limit", len(put))
	}
	if err := sender.WriteMessage(websocket.TextMessage, put); err != nil {
		t.Fatalf("write maximum envelope: %v", err)
	}
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" || accepted.MsgID != msgID {
		t.Fatalf("maximum envelope response = %#v, want relay.accepted", accepted)
	}
	pending := waitForPendingCount(t, srv, pair.deviceB, 1)
	if len(pending[0].Envelope) != maxMessageSize {
		t.Fatalf("stored envelope length = %d, want %d", len(pending[0].Envelope), maxMessageSize)
	}
}

func TestWebSocketClosesRelayPutEnvelopeOverOneMiB(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	msgID := "89222222-2222-4222-8222-222222222222"
	boundary := maxSizeMailboxEnvelope(t, pair.deviceA, msgID)
	oversized := make([]byte, 0, len(boundary)+4)
	oversized = append(oversized, boundary[:len(boundary)-2]...)
	oversized = append(oversized, "AAAA"...)
	oversized = append(oversized, '"', '}')
	if len(oversized) != maxMessageSize+4 {
		t.Fatalf("oversized envelope length = %d, want %d", len(oversized), maxMessageSize+4)
	}
	put := append([]byte(`{"v":2,"type":"relay.put","envelope":`), oversized...)
	put = append(put, '}')
	if err := sender.WriteMessage(websocket.TextMessage, put); err != nil {
		t.Fatalf("write oversized envelope: %v", err)
	}
	_ = sender.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, raw, err := sender.ReadMessage(); err == nil {
		t.Fatalf("oversized envelope received response instead of close: %s", raw)
	}
	waitForPendingCount(t, srv, pair.deviceB, 0)
}

func TestWebSocketWriterBackpressureDoesNotDeleteMailbox(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	blocked := make(chan []byte, 1)
	blocked <- []byte("occupied")
	fakeRecipient := srv.clientHub.Register(pair.deviceB, blocked)
	if !srv.clientHub.SetProtocolAndCapabilities(fakeRecipient, protocolV2, []int{2, 1}) {
		t.Fatal("mark fake recipient v2")
	}

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	msgID := "86111111-1111-4111-8111-111111111111"
	envelope := validMailboxEnvelope(pair.deviceA, msgID)
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": envelope})
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" || accepted.MsgID != msgID {
		t.Fatalf("backpressured put = %#v, want relay.accepted", accepted)
	}
	srv.clientHub.Unregister(fakeRecipient)

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	if delivered := readMailboxFrame(t, recipient); delivered.Type != "relay.deliver" || string(delivered.Envelope) != string(envelope) {
		t.Fatalf("backpressured mailbox delivery = %#v, want retained envelope", delivered)
	}
}

func TestWebSocketRejectsPutBeforeHello(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	msgID := "87111111-1111-4111-8111-111111111111"
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": validMailboxEnvelope(pair.deviceA, msgID)})
	if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "invalid_frame" {
		t.Fatalf("pre-hello response = %#v, want invalid_frame", rejected)
	}
	pending, err := srv.mailbox.Pending(pair.deviceB, 10)
	if err != nil || len(pending) != 0 {
		t.Fatalf("pre-hello put reached mailbox: %#v, %v", pending, err)
	}
}

func TestWebSocketMapsIDConflictAndNotRecipient(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	sender := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer sender.Close()
	sendMailboxHello(t, sender)
	msgID := "88111111-1111-4111-8111-111111111111"
	first := validMailboxEnvelope(pair.deviceA, msgID)
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": first})
	if accepted := readMailboxFrame(t, sender); accepted.Type != "relay.accepted" {
		t.Fatalf("first put = %#v, want accepted", accepted)
	}
	conflicting := json.RawMessage(strings.Replace(string(first), "Y2lwaGVydGV4dA==", "ZGlmZmVyZW50", 1))
	writeMailboxFrame(t, sender, map[string]any{"v": 2, "type": "relay.put", "envelope": conflicting})
	if rejected := readMailboxRejected(t, sender); rejected.MsgID != msgID || rejected.Reason != "id_conflict" {
		t.Fatalf("conflict response = %#v, want id_conflict", rejected)
	}

	recipient := dialMailboxWS(t, ts, pair.deviceB, pair.privB)
	defer recipient.Close()
	sendMailboxHello(t, recipient)
	_ = readMailboxFrame(t, recipient)
	unknownID := "88222222-2222-4222-8222-222222222222"
	writeMailboxFrame(t, recipient, map[string]any{
		"v": 2, "type": "relay.ack", "msg_id": unknownID, "envelope_sha256": strings.Repeat("0", 64),
	})
	if rejected := readMailboxRejected(t, recipient); rejected.MsgID != unknownID || rejected.Reason != "not_recipient" {
		t.Fatalf("unknown ACK response = %#v, want not_recipient", rejected)
	}
}

func TestWebSocketReplacementClosesOldConnection(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	old := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer old.Close()
	replacement := dialMailboxWS(t, ts, pair.deviceA, pair.privA)
	defer replacement.Close()

	_ = old.SetReadDeadline(time.Now().Add(500 * time.Millisecond))
	if _, _, err := old.ReadMessage(); err == nil {
		t.Fatal("replaced connection remained readable")
	} else {
		var netErr net.Error
		if errors.As(err, &netErr) && netErr.Timeout() {
			t.Fatalf("replaced connection remained open until timeout: %v", err)
		}
	}
}
