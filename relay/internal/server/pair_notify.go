package server

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
)

const pairNotifyMaxDuration = 5 * time.Minute

// handlePairNotify accepts unauthenticated WebSocket subscriptions during pairing.
// Device A (role=A) waits for a peer.hello frame; Device B (role=B) waits for pair.sig.
// Holds open through the pair token's original TTL and replays every applicable
// persisted transition before waiting for live change signals.
func (s *Server) handlePairNotify(w http.ResponseWriter, r *http.Request) {
	pairToken := r.URL.Query().Get("token")
	role := r.URL.Query().Get("role")
	if pairToken == "" || (role != "A" && role != "B") {
		http.Error(w, "missing or invalid token/role", http.StatusBadRequest)
		return
	}
	// Validate and snapshot before upgrading. This deters unbounded subscriptions
	// and ensures the first replay does not depend on an in-memory hub frame.
	initial, err := s.pairStore.GetPending(pairToken)
	if err != nil {
		http.Error(w, "unknown pair_token", http.StatusNotFound)
		return
	}
	if pairTokenExpired(initial, time.Now()) {
		_ = s.pairStore.DeletePending(pairToken)
		http.Error(w, "unknown pair_token", http.StatusNotFound)
		return
	}
	initialFrames, err := pairTransitionFrames(initial, role)
	if err != nil {
		http.Error(w, "pair state unavailable", http.StatusInternalServerError)
		return
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()

	conn.SetReadLimit(256)
	expiresAt := time.Unix(initial.CreatedAt, 0).Add(pairNotifyMaxDuration)
	_ = conn.SetReadDeadline(expiresAt)

	sent := make(map[string]struct{}, 3)
	complete, err := writePairTransitionFrames(conn, initialFrames, sent)
	if err != nil {
		return
	}
	if complete {
		writePairClose(conn, "pair complete")
		return
	}

	// Subscribe only after replay, then reread durable state once. Any transition
	// in the snapshot/subscribe window is now either in this reread or queued as
	// a live signal. Per-connection type/token dedupe makes both safe.
	subscription := s.pairHub.Subscribe(pairToken, role)
	defer s.pairHub.Unsubscribe(pairToken, role, subscription)
	complete, err = s.replayPairTransitions(conn, pairToken, role, sent)
	if err != nil {
		return
	}
	if complete {
		writePairClose(conn, "pair complete")
		return
	}

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

	remaining := time.Until(expiresAt)
	if remaining < 0 {
		remaining = 0
	}
	timer := time.NewTimer(remaining)
	defer timer.Stop()

	for {
		select {
		case <-subscription.done:
			return
		case <-subscription.outbound:
			complete, err := s.replayPairTransitions(conn, pairToken, role, sent)
			if err != nil {
				return
			}
			if complete {
				writePairClose(conn, "pair complete")
				return
			}
		case <-timer.C:
			writePairClose(conn, "pair token expired")
			return
		case <-done:
			return
		}
	}
}

type pairTransitionFrame struct {
	pairToken string
	typeName  string
	payload   []byte
}

func (s *Server) replayPairTransitions(conn *websocket.Conn, pairToken, role string, sent map[string]struct{}) (bool, error) {
	if _, err := s.pairStore.PendingState(pairToken); err != nil {
		return false, err
	}
	pending, err := s.pairStore.GetPending(pairToken)
	if err != nil {
		return false, err
	}
	frames, err := pairTransitionFrames(pending, role)
	if err != nil {
		return false, err
	}
	return writePairTransitionFrames(conn, frames, sent)
}

func pairTransitionFrames(pending *store.PendingPair, role string) ([]pairTransitionFrame, error) {
	frames := make([]pairTransitionFrame, 0, 2)
	if role == "A" && pending.DeviceBID != "" && len(pending.BEncPubkey) != 0 && len(pending.BSignPubkey) != 0 {
		payload, err := marshalPeerHello(pending)
		if err != nil {
			return nil, err
		}
		frames = append(frames, pairTransitionFrame{pairToken: pending.PairToken, typeName: "peer.hello", payload: payload})
	}
	if role == "B" && len(pending.ConfirmationSig) != 0 {
		payload, err := marshalPairSignature(pending)
		if err != nil {
			return nil, err
		}
		frames = append(frames, pairTransitionFrame{pairToken: pending.PairToken, typeName: "pair.sig", payload: payload})
	}
	if pending.PairID != "" {
		payload, err := marshalPairComplete(pending)
		if err != nil {
			return nil, err
		}
		frames = append(frames, pairTransitionFrame{pairToken: pending.PairToken, typeName: "pair.complete", payload: payload})
	}
	return frames, nil
}

func marshalPeerHello(pending *store.PendingPair) ([]byte, error) {
	return json.Marshal(map[string]any{
		"v": 1, "type": "peer.hello", "pair_token": pending.PairToken,
		"device_id":    pending.DeviceBID,
		"enc_pubkey":   base64.StdEncoding.EncodeToString(pending.BEncPubkey),
		"sign_pubkey":  base64.StdEncoding.EncodeToString(pending.BSignPubkey),
		"display_name": pending.BDisplayName,
	})
}

func marshalPairSignature(pending *store.PendingPair) ([]byte, error) {
	return json.Marshal(map[string]any{
		"v": 1, "type": "pair.sig", "pair_token": pending.PairToken,
		"confirmation_sig": base64.StdEncoding.EncodeToString(pending.ConfirmationSig),
	})
}

func marshalPairComplete(pending *store.PendingPair) ([]byte, error) {
	return json.Marshal(map[string]any{
		"v": 1, "type": "pair.complete", "pair_token": pending.PairToken, "pair_id": pending.PairID,
	})
}

func writePairTransitionFrames(conn *websocket.Conn, frames []pairTransitionFrame, sent map[string]struct{}) (bool, error) {
	complete := false
	for _, frame := range frames {
		transitionKey := frame.pairToken + "\x00" + frame.typeName
		if _, duplicate := sent[transitionKey]; duplicate {
			continue
		}
		_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
		if err := conn.WriteMessage(websocket.TextMessage, frame.payload); err != nil {
			return false, err
		}
		sent[transitionKey] = struct{}{}
		complete = complete || frame.typeName == "pair.complete"
	}
	return complete, nil
}

func writePairClose(conn *websocket.Conn, reason string) {
	_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
	_ = conn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, reason))
}
