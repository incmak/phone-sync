package server

import (
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	maxMessageSize = 1 << 20
	pongWait       = 60 * time.Second
	pingPeriod     = (pongWait * 9) / 10
	writeWait      = 10 * time.Second
)

var upgrader = websocket.Upgrader{
	// CheckOrigin: `/ws` is gated by JWT authMiddleware (Phase 2 onward) — JWT is verified
	// against the paired device's stored sign_pubkey before any message flows. `/pair/notify`
	// and `/pair/hello`/`/pair/send_sig` are intentionally unauthenticated (pre-pair state)
	// but gated by pair_token which has a 5-min TTL. Therefore allowing any origin on
	// upgrade is safe: the cryptographic gates live at the handler layer, not at the
	// WebSocket handshake layer.
	CheckOrigin: func(r *http.Request) bool { return true },
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("upgrade: %v", err)
		return
	}
	defer conn.Close()

	conn.SetReadLimit(maxMessageSize)
	_ = conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	var writeMu sync.Mutex
	writeMsg := func(mt int, data []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
		return conn.WriteMessage(mt, data)
	}

	stopPinger := make(chan struct{})
	defer close(stopPinger)
	go func() {
		ticker := time.NewTicker(pingPeriod)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if err := writeMsg(websocket.PingMessage, nil); err != nil {
					return
				}
			case <-stopPinger:
				return
			}
		}
	}()

	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(pongWait))
		if err := s.validator.ValidateEnvelope(msg); err != nil {
			_ = writeMsg(websocket.TextMessage, []byte(`{"error":"invalid envelope"}`))
			continue
		}
		_ = writeMsg(websocket.TextMessage, msg)
	}
}
