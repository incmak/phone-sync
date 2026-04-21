package server

import (
	"net/http"
	"time"

	"github.com/gorilla/websocket"
)

const pairNotifyMaxDuration = 5 * time.Minute

// handlePairNotify accepts unauthenticated WebSocket subscriptions from Device A
// during pairing. Holds open up to pair_token TTL (5 min), pushes the pair.sig
// frame when /pair/complete fires, then closes.
func (s *Server) handlePairNotify(w http.ResponseWriter, r *http.Request) {
	pairToken := r.URL.Query().Get("token")
	if pairToken == "" {
		http.Error(w, "missing token", http.StatusBadRequest)
		return
	}
	// Verify pair_token exists as a pending pair. Deters unbounded subscribe-without-init.
	if _, err := s.pairStore.GetPending(pairToken); err != nil {
		http.Error(w, "unknown pair_token", http.StatusNotFound)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()

	conn.SetReadLimit(256)
	_ = conn.SetReadDeadline(time.Now().Add(pairNotifyMaxDuration))

	ch := s.pairHub.Subscribe(pairToken)
	defer s.pairHub.Unsubscribe(pairToken, ch)

	// Reader goroutine: detect client disconnect.
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			_, _, err := conn.ReadMessage()
			if err != nil {
				return
			}
		}
	}()

	timer := time.NewTimer(pairNotifyMaxDuration)
	defer timer.Stop()

	select {
	case frame, ok := <-ch:
		if !ok {
			return
		}
		_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
		_ = conn.WriteMessage(websocket.TextMessage, frame)
		// After pushing, close normally.
		_ = conn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, "sig delivered"))
	case <-timer.C:
		_ = conn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, "pair token expired"))
	case <-done:
		// Client disconnected.
	}
}
