package server

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
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
	if !server.clientHub.Send(deviceID, []byte(`{"type":"blocked-writer"}`)) {
		t.Fatal("failed to queue frame for the active WebSocket writer")
	}
	select {
	case <-writerEntered:
	case <-time.After(time.Second):
		t.Fatal("active WebSocket writer did not reach the deterministic barrier")
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
}
