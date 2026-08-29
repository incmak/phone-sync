package server

import (
	"bytes"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestMetricsUseOnlyClosedLabelsAndNeverRenderIdentifiers(t *testing.T) {
	server := newTestServer(t)
	server.metrics.connectionOpened()
	server.metrics.connectionClosed()
	server.metrics.recordRelayPutAccepted()
	server.metrics.recordRelayPutRejected("mailbox_full")
	server.metrics.recordRelayPutRejected("secret-device-and-message-id")
	server.metrics.recordPairMutation(pairStageInit, http.StatusOK)
	server.metrics.recordPairMutation(pairStageComplete, http.StatusServiceUnavailable)
	server.metrics.recordAuthRejected(authRejectReplay)
	server.metrics.recordAuthRejected(authRejectRateLimited)
	server.metrics.recordMaintenance(maintenanceMailbox, nil)
	server.metrics.recordMaintenance(maintenanceJTI, errors.New("secret-storage-detail"))
	server.metrics.recordWebSocketAdmissionRejected()

	request := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	response := httptest.NewRecorder()
	server.Handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("metrics status = %d, want 200", response.Code)
	}
	body := response.Body.String()
	for _, secret := range []string{"secret-device", "message-id", "secret-storage-detail"} {
		if strings.Contains(body, secret) {
			t.Fatalf("metrics exposed dynamic value %q: %s", secret, body)
		}
	}
	for _, expected := range []string{
		"twinotify_websocket_connections 0",
		"twinotify_websocket_outbound_bytes 0",
		"twinotify_websocket_admission_rejected_total 1",
		"twinotify_relay_put_accepted_total 1",
		`reason="mailbox_full"} 1`,
		`reason="invalid_frame"} 1`,
		`stage="init",result="accepted"} 1`,
		`reason="replay"} 1`,
		`reason="rate_limited"} 1`,
		`operation="mailbox_expiry",result="success"} 1`,
		`operation="jti_expiry",result="failure"} 1`,
	} {
		if !strings.Contains(body, expected) {
			t.Fatalf("metrics omitted %q:\n%s", expected, body)
		}
	}
}

func TestBlockedWriterReplacementKeepsExactConnectionByteOwnership(t *testing.T) {
	config := DefaultConfig()
	frame := bytes.Repeat([]byte{'x'}, maxRelayDeliverFrameBytes)
	charge := outboundFrameCharge(frame)
	config.WebSocketQueueMaxBytes = charge
	config.WebSocketProcessQueueMaxBytes = charge
	server := newTestServerWithConfig(t, config)
	deviceID, privateKey := registerPair(t, server.pairStore)

	firstEntered := make(chan struct{})
	secondEntered := make(chan struct{})
	releaseFirst := make(chan struct{})
	releaseSecond := make(chan struct{})
	var writes atomic.Int32
	server.webSocketWriteAfterLock = func() {
		switch writes.Add(1) {
		case 1:
			close(firstEntered)
			<-releaseFirst
		case 2:
			close(secondEntered)
			<-releaseSecond
		}
	}

	httpServer := httptest.NewServer(server.Handler())
	defer httpServer.Close()
	dial := func() *websocket.Conn {
		header := http.Header{}
		header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, privateKey, ""))
		connection, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(httpServer.URL, "http")+"/ws", header)
		if err != nil {
			t.Fatal(err)
		}
		return connection
	}
	oldConnection := dial()
	defer oldConnection.Close()
	waitForRevokePairRegistration(t, server, deviceID, "pair-test-1")
	server.clientHub.mu.Lock()
	oldClient := server.clientHub.clients[deviceID]
	server.clientHub.mu.Unlock()
	if !server.clientHub.Send(deviceID, frame) {
		t.Fatal("queue old maximum frame")
	}
	<-firstEntered

	replacementConnection := dial()
	defer replacementConnection.Close()
	replacementDeadline := time.Now().Add(time.Second)
	for {
		server.clientHub.mu.Lock()
		current := server.clientHub.clients[deviceID]
		server.clientHub.mu.Unlock()
		if current != nil && current != oldClient {
			break
		}
		if time.Now().After(replacementDeadline) {
			t.Fatal("replacement registration did not supersede old client")
		}
		time.Sleep(time.Millisecond)
	}
	if server.clientHub.Send(deviceID, frame) {
		t.Fatal("replacement spent bytes still owned by blocked old writer")
	}
	close(releaseFirst)
	select {
	case <-oldClient.closed:
	case <-time.After(time.Second):
		t.Fatal("old connection did not complete exact cleanup")
	}
	if !server.clientHub.Send(deviceID, frame) {
		t.Fatal("replacement could not spend bytes after old writer completed")
	}
	<-secondEntered
	charged := server.metrics.websocketOutboundBytes.Load()
	if charged != int64(charge) || server.metrics.websocketAdmissionRejected.Load() == 0 {
		t.Fatalf("replacement metrics bytes/rejections = %d/%d, want %d/positive", charged, server.metrics.websocketAdmissionRejected.Load(), charge)
	}
	server.clientHub.Unregister(oldClient)
	if got := server.metrics.websocketOutboundBytes.Load(); got != charged {
		t.Fatalf("stale old cleanup changed replacement charge: %d to %d", charged, got)
	}
	close(releaseSecond)
	_ = replacementConnection.SetReadDeadline(time.Now().Add(time.Second))
	_, delivered, err := replacementConnection.ReadMessage()
	if err != nil || len(delivered) != len(frame) {
		t.Fatalf("replacement frame after release = %d bytes, %v", len(delivered), err)
	}
	deadline := time.Now().Add(time.Second)
	for server.metrics.websocketOutboundBytes.Load() != 0 {
		if time.Now().After(deadline) {
			t.Fatalf("replacement gauge after completed write = %d, want 0", server.metrics.websocketOutboundBytes.Load())
		}
		time.Sleep(time.Millisecond)
	}
}

func TestBeginShutdownClosesWebSocketWithServiceRestart(t *testing.T) {
	server := newTestServer(t)
	deviceID, privateKey := registerPair(t, server.pairStore)
	httpServer := httptest.NewServer(server.Handler())
	defer httpServer.Close()
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, privateKey, ""))
	connection, _, err := websocket.DefaultDialer.Dial(
		"ws"+strings.TrimPrefix(httpServer.URL, "http")+"/ws", header,
	)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()

	drainDone := server.BeginShutdown()
	_ = connection.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err = connection.ReadMessage()
	var closeError *websocket.CloseError
	if !errors.As(err, &closeError) || closeError.Code != websocket.CloseServiceRestart {
		t.Fatalf("shutdown close = %v, want WebSocket code %d", err, websocket.CloseServiceRestart)
	}
	select {
	case <-drainDone:
	case <-time.After(time.Second):
		t.Fatal("WebSocket drain did not complete after the service-restart close")
	}
	if server.metrics.activeConnections() != 0 {
		t.Fatalf("active WebSocket metric = %d, want 0", server.metrics.activeConnections())
	}
}

func TestBeginShutdownClosesWebSocketWhileWriterIsBlockedAndWaitsForCleanup(t *testing.T) {
	server := newTestServer(t)
	deviceID, privateKey := registerPair(t, server.pairStore)
	writerEntered := make(chan struct{})
	releaseWriter := make(chan struct{})
	var enteredOnce sync.Once
	var releaseOnce sync.Once
	defer releaseOnce.Do(func() { close(releaseWriter) })
	server.webSocketWriteAfterLock = func() {
		enteredOnce.Do(func() { close(writerEntered) })
		<-releaseWriter
	}

	httpServer := httptest.NewServer(server.Handler())
	defer httpServer.Close()
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, privateKey, ""))
	connection, _, err := websocket.DefaultDialer.Dial(
		"ws"+strings.TrimPrefix(httpServer.URL, "http")+"/ws", header,
	)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	waitForRevokePairRegistration(t, server, deviceID, "pair-test-1")
	blockedFrame := []byte(`{"type":"blocked-writer"}`)
	if !server.clientHub.Send(deviceID, blockedFrame) {
		t.Fatal("failed to queue frame for the active WebSocket writer")
	}
	select {
	case <-writerEntered:
	case <-time.After(time.Second):
		t.Fatal("active WebSocket writer did not reach the deterministic barrier")
	}
	if got := server.clientHub.outboundBytesCharged(); got != outboundFrameCharge(blockedFrame) {
		t.Fatalf("bytes charged during blocked WriteMessage = %d, want %d", got, outboundFrameCharge(blockedFrame))
	}

	drainDone := server.BeginShutdown()
	_ = connection.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err = connection.ReadMessage()
	var closeError *websocket.CloseError
	if !errors.As(err, &closeError) || closeError.Code != websocket.CloseServiceRestart {
		t.Fatalf("shutdown close with blocked writer = %v, want WebSocket code %d", err, websocket.CloseServiceRestart)
	}
	select {
	case <-drainDone:
		t.Fatal("WebSocket drain completed before the blocked writer exited")
	default:
	}

	releaseOnce.Do(func() { close(releaseWriter) })
	select {
	case <-drainDone:
	case <-time.After(time.Second):
		t.Fatal("WebSocket drain did not complete after the writer exited")
	}
	if got := server.clientHub.outboundBytesCharged(); got != 0 {
		t.Fatalf("bytes charged after blocked writer cleanup = %d, want 0", got)
	}
	if server.metrics.activeConnections() != 0 {
		t.Fatalf("active WebSocket metric = %d, want 0", server.metrics.activeConnections())
	}
}

func TestBeginShutdownClosesWebSocketAdmittedBeforeRelayHubRegistration(t *testing.T) {
	server := newTestServer(t)
	deviceID, privateKey := registerPair(t, server.pairStore)
	beforeRelayRegistration := make(chan struct{})
	releaseRelayRegistration := make(chan struct{})
	var enteredOnce sync.Once
	var releaseOnce sync.Once
	defer releaseOnce.Do(func() { close(releaseRelayRegistration) })
	server.webSocketBeforeRegister = func(string, string) {
		enteredOnce.Do(func() { close(beforeRelayRegistration) })
		<-releaseRelayRegistration
	}

	httpServer := httptest.NewServer(server.Handler())
	defer httpServer.Close()
	header := http.Header{}
	header.Set("Authorization", "Bearer "+mintJWT(t, deviceID, privateKey, ""))
	connection, _, err := websocket.DefaultDialer.Dial(
		"ws"+strings.TrimPrefix(httpServer.URL, "http")+"/ws", header,
	)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	select {
	case <-beforeRelayRegistration:
	case <-time.After(time.Second):
		t.Fatal("WebSocket did not reach the relay registration barrier")
	}
	if got := server.metrics.activeConnections(); got != 1 {
		t.Fatalf("active WebSocket metric before relay registration = %d, want 1", got)
	}

	drainDone := server.BeginShutdown()
	select {
	case <-drainDone:
		t.Fatal("WebSocket drain completed before the admitted handler exited")
	default:
	}
	releaseOnce.Do(func() { close(releaseRelayRegistration) })

	_ = connection.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err = connection.ReadMessage()
	var closeError *websocket.CloseError
	if !errors.As(err, &closeError) || closeError.Code != websocket.CloseServiceRestart {
		t.Fatalf("shutdown close before relay registration = %v, want WebSocket code %d", err, websocket.CloseServiceRestart)
	}
	select {
	case <-drainDone:
	case <-time.After(time.Second):
		t.Fatal("WebSocket drain did not complete after relay registration was released")
	}
	if got := server.metrics.activeConnections(); got != 0 {
		t.Fatalf("active WebSocket metric after relay cleanup = %d, want 0", got)
	}
}

func TestPairNotifyCountsUpgradedWebSocketUntilJoinedCleanup(t *testing.T) {
	server := newTestServer(t)
	readerStarted := make(chan struct{})
	var startedOnce sync.Once
	server.pairNotifyReaderStarted = func() {
		startedOnce.Do(func() { close(readerStarted) })
	}

	httpServer := httptest.NewServer(server.Handler())
	defer httpServer.Close()
	encPublicKey, signPublicKey, signPrivateKey := ed25519Keypair(t)
	const pairToken = "tok-pair-notify-metric"
	const deviceID = "devA-pair-notify-metric"
	initPair(t, httpServer.URL, pairToken, deviceID, encPublicKey, signPublicKey)
	connection := dialPairNotify(t, httpServer.URL, pairToken, "A", deviceID, signPrivateKey)
	defer connection.Close()

	select {
	case <-readerStarted:
	case <-time.After(time.Second):
		t.Fatal("pair-notify reader did not start")
	}
	if got := server.metrics.activeConnections(); got != 1 {
		t.Fatalf("active WebSocket metric for pair notify = %d, want 1", got)
	}

	drainDone := server.BeginShutdown()
	_ = connection.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err := connection.ReadMessage()
	var closeError *websocket.CloseError
	if !errors.As(err, &closeError) || closeError.Code != websocket.CloseServiceRestart {
		t.Fatalf("pair-notify shutdown close = %v, want WebSocket code %d", err, websocket.CloseServiceRestart)
	}
	select {
	case <-drainDone:
	case <-time.After(time.Second):
		t.Fatal("pair-notify drain did not join cleanup")
	}
	if got := server.metrics.activeConnections(); got != 0 {
		t.Fatalf("active WebSocket metric after pair-notify cleanup = %d, want 0", got)
	}
}
