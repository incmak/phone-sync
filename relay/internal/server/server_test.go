package server

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

const validEnvelope = `{"v":1,"type":"ping","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"devA","ts":1713600000000}`

func TestHealthEndpoint(t *testing.T) {
	srv := newTestServer(t)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	expected := `{"status":"ok"}`
	if rec.Body.String() != expected {
		t.Fatalf("expected body %q, got %q", expected, rec.Body.String())
	}
}

func TestWebSocketEcho(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	c, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	if err := c.WriteMessage(websocket.TextMessage, []byte(validEnvelope)); err != nil {
		t.Fatalf("write: %v", err)
	}
	_, msg, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(msg) != validEnvelope {
		t.Fatalf("expected echo, got %q", string(msg))
	}
}

func TestWebSocketRejectsInvalid(t *testing.T) {
	srv := newTestServer(t)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	c, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	if err := c.WriteMessage(websocket.TextMessage, []byte(`{"garbage":true}`)); err != nil {
		t.Fatalf("write: %v", err)
	}
	_, msg, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !strings.Contains(string(msg), "invalid envelope") {
		t.Fatalf("expected error reply, got %q", string(msg))
	}
}
