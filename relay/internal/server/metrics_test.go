package server

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
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

	server.BeginShutdown()
	_ = connection.SetReadDeadline(time.Now().Add(time.Second))
	_, _, err = connection.ReadMessage()
	var closeError *websocket.CloseError
	if !errors.As(err, &closeError) || closeError.Code != websocket.CloseServiceRestart {
		t.Fatalf("shutdown close = %v, want WebSocket code %d", err, websocket.CloseServiceRestart)
	}
	if server.metrics.activeConnections() != 0 {
		t.Fatalf("active WebSocket metric = %d, want 0", server.metrics.activeConnections())
	}
}
