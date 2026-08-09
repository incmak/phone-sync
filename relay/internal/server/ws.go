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
	// authMiddleware populated context with the sender's device_id (JWT sub).
	deviceID, ok := DeviceIDFromContext(r.Context())
	if !ok || deviceID == "" {
		http.Error(w, "no device id", http.StatusUnauthorized)
		return
	}

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

	// Register this connection in the hub so the PEER can route frames to us.
	// Bounded buffer: slow reader on our end → relay drops frames rather than stalling
	// the peer's send path. Lost frames will be re-sent by peer's OutboundQueue on reconnect.
	outbound := make(chan []byte, 32)
	client := s.clientHub.Register(deviceID, outbound)
	defer s.clientHub.Unregister(client)

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

	// Writer goroutine: exits when this registration is cancelled or when it
	// receives a frame for the current connection.
	go func() {
		for {
			select {
			case <-client.done:
				return
			case frame := <-client.outbound:
				if err := writeMsg(websocket.TextMessage, frame); err != nil {
					return
				}
			}
		}
	}()

	// Reader: validate incoming envelope, look up paired peer, forward via hub.
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
		peerID, err := s.pairStore.PeerFor(deviceID)
		if err != nil || peerID == "" {
			// Sender isn't paired (shouldn't happen — authMiddleware ensures SignPubkey exists,
			// which only happens for confirmed pairs — but defensive).
			_ = writeMsg(websocket.TextMessage, []byte(`{"error":"not paired"}`))
			continue
		}
		// Forward to peer. If peer is offline or buffer is full, drop silently —
		// sender's OutboundQueue will retry on next reconnect.
		_ = s.clientHub.Send(peerID, msg)
	}
}
