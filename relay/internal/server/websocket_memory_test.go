package server

import (
	"bytes"
	"fmt"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/twinotify/relay/internal/store"
)

// This unit test exercises exact arithmetic boundaries. Writer ownership and
// release timing are proven with real blocked sockets in metrics_test.go.
func TestClientHubOutboundAdmissionBoundaryIsByteBounded(t *testing.T) {
	const frameBytes = 1 << 20
	frameCharge := uint64(frameBytes + webSocketFrameMemoryOverhead)
	hub := newClientHubWithMemoryLimits(2000, 128<<20, 2*frameCharge, 3*frameCharge, nil)
	first := hub.Register("first", make(chan []byte, outboundQueueSize))
	second := hub.Register("second", make(chan []byte, outboundQueueSize))

	frame := bytes.Repeat([]byte{'x'}, frameBytes)
	if !hub.Send("first", frame) || !hub.Send("first", frame) {
		t.Fatal("per-connection byte budget rejected an admitted maximum-size pair")
	}
	if hub.Send("first", frame) {
		t.Fatal("per-connection byte budget admitted a third maximum-size frame")
	}
	if !hub.Send("second", frame) {
		t.Fatal("process byte budget rejected its remaining maximum-size frame")
	}
	if hub.Send("second", []byte("x")) {
		t.Fatal("process byte budget admitted bytes beyond its exact limit")
	}

	hub.releaseOutbound(first, outboundFrameCharge(<-first.outbound))
	if !hub.Send("second", frame) {
		t.Fatal("released writer bytes did not restore process admission")
	}
	hub.Unregister(first)
	hub.Unregister(second)
	if got := hub.outboundBytesCharged(); got != 0 {
		t.Fatalf("outbound bytes after unregister = %d, want 0", got)
	}
}

func TestWebSocketMemoryConfigFailsClosed(t *testing.T) {
	valid := DefaultConfig()
	cases := map[string]func(*Config){
		"zero connection queue":          func(c *Config) { c.WebSocketQueueMaxBytes = 0 },
		"zero process queue":             func(c *Config) { c.WebSocketProcessQueueMaxBytes = 0 },
		"zero transfer workspace":        func(c *Config) { c.DurableTransferMaxBytes = 0 },
		"connection below maximum frame": func(c *Config) { c.WebSocketQueueMaxBytes = maxRelayDeliverFrameBytes - 1 },
		"process below connection":       func(c *Config) { c.WebSocketProcessQueueMaxBytes = c.WebSocketQueueMaxBytes - 1 },
		"unsafe total memory margin":     func(c *Config) { c.WebSocketProcessQueueMaxBytes = 250 << 20 },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			config := valid
			mutate(&config)
			b, err := store.OpenBolt(filepath.Join(t.TempDir(), "memory-config.db"))
			if err != nil {
				t.Fatal(err)
			}
			defer b.Close()
			if _, err := NewWithConfigChecked(b, config); err == nil {
				t.Fatal("unsafe WebSocket memory configuration accepted")
			}
		})
	}
}

func TestClientHubRawV1ByteFullReturnsOfflineFailure(t *testing.T) {
	frame := bytes.Repeat([]byte{'x'}, 1<<20)
	charge := outboundFrameCharge(frame)
	hub := newClientHubWithMemoryLimits(2000, 128<<20, charge, charge, nil)
	client := hub.Register("legacy", make(chan []byte, outboundQueueSize))
	if !hub.SetProtocol(client, protocolLegacy) || !hub.SendRawV1("legacy", frame) {
		t.Fatal("first v1 maximum frame rejected")
	}
	if hub.SendRawV1("legacy", frame) {
		t.Fatal("byte-full v1 queue reported online delivery")
	}
	select {
	case <-client.done:
		t.Fatal("online-only v1 byte pressure stopped the connection")
	default:
	}
}

func TestWebSocketAdmissionMetricsTrackBytesAndAnonymousSaturation(t *testing.T) {
	frame := bytes.Repeat([]byte{'x'}, 1<<20)
	charge := outboundFrameCharge(frame)
	metrics := newRelayMetrics()
	hub := newClientHubWithMemoryLimits(2000, 128<<20, charge, charge, metrics)
	client := hub.Register("secret-device", make(chan []byte, 2))
	if !hub.Send("secret-device", frame) {
		t.Fatal("first frame rejected")
	}
	if got := metrics.websocketOutboundBytes.Load(); got != int64(charge) {
		t.Fatalf("outbound gauge = %d, want %d", got, charge)
	}
	if hub.Send("secret-device", frame) {
		t.Fatal("saturated frame admitted")
	}
	if got := metrics.websocketAdmissionRejected.Load(); got != 1 {
		t.Fatalf("admission rejection counter = %d, want 1", got)
	}
	hub.Unregister(client)
	if got := metrics.websocketOutboundBytes.Load(); got != 0 {
		t.Fatalf("outbound gauge after cleanup = %d, want 0", got)
	}
}

func TestClientHubV2ByteBackpressureStopsSlowWriterForReconnect(t *testing.T) {
	const frameBytes = 1 << 20
	frameCharge := uint64(frameBytes + webSocketFrameMemoryOverhead)
	hub := newClientHubWithMemoryLimits(2000, 128<<20, frameCharge, 4*frameCharge, nil)
	client := hub.Register("recipient", make(chan []byte, outboundQueueSize))
	if !hub.SetProtocolAndCapabilities(client, protocolV2, []int{2, 1}) {
		t.Fatal("set v2 protocol")
	}
	frame := bytes.Repeat([]byte{'x'}, frameBytes)
	notice := queuedV2Notification{msgID: "86111111-1111-4111-8111-111111111111", sequence: 1, byteSize: frameBytes}
	if !hub.TransferV2Batch("recipient", []queuedV2Notification{notice}, [][]byte{frame}) {
		t.Fatal("first maximum-size durable delivery rejected")
	}
	notice.msgID = "86222222-2222-4222-8222-222222222222"
	if hub.TransferV2Batch("recipient", []queuedV2Notification{notice}, [][]byte{frame}) {
		t.Fatal("slow writer accepted durable delivery beyond byte limit")
	}
	select {
	case <-client.done:
	default:
		t.Fatal("byte-backpressured v2 client was not stopped for reconnect drain")
	}
}

func TestClientHubReplacementKeepsOldBytesChargedUntilExactCleanup(t *testing.T) {
	frame := bytes.Repeat([]byte{'x'}, 1<<20)
	charge := outboundFrameCharge(frame)
	hub := newClientHubWithMemoryLimits(2000, 128<<20, 2*charge, charge, nil)
	old := hub.Register("device", make(chan []byte, outboundQueueSize))
	if !hub.Send("device", frame) {
		t.Fatal("queue old connection frame")
	}
	replacement := hub.Register("device", make(chan []byte, outboundQueueSize))
	if hub.Send("device", frame) {
		t.Fatal("replacement bypassed process budget held by old connection")
	}
	hub.Unregister(old)
	if !hub.Send("device", frame) {
		t.Fatal("exact old cleanup did not restore replacement admission")
	}
	hub.Unregister(replacement)
}

func TestClientHubPendingCapabilitiesRemainChargedThroughActivation(t *testing.T) {
	first := []byte(`{"v":2,"type":"relay.capabilities","self":[2,1],"peer":[1],"floor":1}`)
	second := []byte(`{"v":2,"type":"relay.capabilities","self":[2,1],"peer":[2,1],"floor":2}`)
	hub := newClientHubWithMemoryLimits(2000, 128<<20, 1<<20, 1<<20, nil)
	client := hub.Register("device", make(chan []byte, 1))
	if !hub.SetProtocolAndCapabilities(client, protocolV2Handshake, []int{2, 1}) {
		t.Fatal("set handshake protocol")
	}
	hub.SendCapabilities("device", []int{2, 1}, first)
	if got := hub.outboundBytesCharged(); got != outboundFrameCharge(first) {
		t.Fatalf("pending capability charge = %d, want %d", got, outboundFrameCharge(first))
	}
	hub.SendCapabilities("device", []int{2, 1}, second)
	if got := hub.outboundBytesCharged(); got != outboundFrameCharge(second) {
		t.Fatalf("replacement capability charge = %d, want %d", got, outboundFrameCharge(second))
	}
	if !hub.FlushOrActivateV2(client, nil) {
		t.Fatal("activate handshake")
	}
	if got := hub.outboundBytesCharged(); got != outboundFrameCharge(second) {
		t.Fatalf("activated capability charge = %d, want %d", got, outboundFrameCharge(second))
	}
	frame := <-client.outbound
	hub.releaseOutbound(client, outboundFrameCharge(frame))
	if got := hub.outboundBytesCharged(); got != 0 {
		t.Fatalf("capability bytes after write = %d, want 0", got)
	}
}

func TestDurableTransferBatchesAreByteBounded(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	notifications := make([]queuedV2Notification, 0, 8)
	for i := 0; i < 8; i++ {
		msgID := fmt.Sprintf("86000000-0000-4000-8000-%012d", i)
		envelope := maxSizeMailboxEnvelope(t, pair.deviceA, msgID)
		putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, envelope, srv.now())
		pending, err := srv.mailbox.PendingForPair(pair.pairID, pair.deviceB, 20)
		if err != nil {
			t.Fatal(err)
		}
		var sequence uint64
		for _, record := range pending {
			if record.MsgID == msgID {
				sequence = record.AcceptanceSequence
				break
			}
		}
		notifications = append(notifications, queuedV2Notification{msgID: msgID, sequence: sequence, byteSize: uint64(len(envelope))})
	}

	var batches int
	_, err := srv.transferDurableRecords(pair.pairID, pair.deviceB, notifications, func(_ []queuedV2Notification, frames [][]byte) bool {
		batches++
		var batchBytes uint64
		for _, frame := range frames {
			batchBytes += uint64(len(frame))
		}
		if batchBytes > defaultDurableTransferMaxBytes {
			t.Fatalf("durable transfer batch bytes = %d, limit %d", batchBytes, defaultDurableTransferMaxBytes)
		}
		return true
	})
	if err != nil {
		t.Fatal(err)
	}
	if batches < 2 {
		t.Fatalf("durable transfer batches = %d, want byte-driven split", batches)
	}
}

func TestDurableTransferWorkspaceIsProcessSerialized(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	msgID := "86999999-9999-4999-8999-999999999999"
	envelope := maxSizeMailboxEnvelope(t, pair.deviceA, msgID)
	putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, envelope, srv.now())
	pending, err := srv.mailbox.PendingForPair(pair.pairID, pair.deviceB, 1)
	if err != nil || len(pending) != 1 {
		t.Fatalf("pending record = %d, %v", len(pending), err)
	}
	notices := []queuedV2Notification{{msgID: msgID, sequence: pending[0].AcceptanceSequence, byteSize: pending[0].ByteSize}}

	var callers atomic.Int32
	secondStarted := make(chan struct{})
	firstAdmitted := make(chan struct{})
	secondAdmitted := make(chan struct{})
	releaseFirst := make(chan struct{})
	srv.relayDeliveryTransferBeforeAdmission = func() {
		if callers.Add(1) == 2 {
			close(secondStarted)
		}
	}
	srv.relayBeforeDeliveryTransfer = func(string) {
		if callers.Load() == 1 {
			close(firstAdmitted)
			<-releaseFirst
			return
		}
		close(secondAdmitted)
	}
	transfer := func(done chan<- error) {
		_, err := srv.transferDurableRecords(pair.pairID, pair.deviceB, notices, func([]queuedV2Notification, [][]byte) bool { return true })
		done <- err
	}
	firstDone := make(chan error, 1)
	go transfer(firstDone)
	<-firstAdmitted
	secondDone := make(chan error, 1)
	go transfer(secondDone)
	<-secondStarted
	select {
	case <-secondAdmitted:
		t.Fatal("second transfer entered the process workspace concurrently")
	default:
	}
	close(releaseFirst)
	for index, done := range []<-chan error{firstDone, secondDone} {
		select {
		case err := <-done:
			if err != nil {
				t.Fatalf("transfer %d: %v", index, err)
			}
		case <-time.After(time.Second):
			t.Fatalf("transfer %d did not finish", index)
		}
	}
}

func TestPendingHandshakeSnapshotDoesNotRetainCiphertextBeforeTransfer(t *testing.T) {
	srv := newTestServer(t)
	pair := registerMailboxTestPair(t, srv)
	const records = 6
	for i := 0; i < records; i++ {
		msgID := fmt.Sprintf("87000000-0000-4000-8000-%012d", i)
		putMailboxRecord(t, srv, pair.deviceB, pair.deviceA, maxSizeMailboxEnvelope(t, pair.deviceA, msgID), srv.now())
	}
	pending, err := srv.mailbox.PendingMetadataForPair(pair.pairID, pair.deviceB, mailboxBatchSize)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending) != records {
		t.Fatalf("metadata-only pending records = %d, want %d", len(pending), records)
	}
	for _, record := range pending {
		if record.ByteSize != maxMessageSize {
			t.Fatalf("metadata byte size = %d, want %d", record.ByteSize, maxMessageSize)
		}
	}
}
