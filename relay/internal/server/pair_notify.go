package server

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
)

const pairNotifyMaxDuration = 5 * time.Minute

const (
	pairNotifyDeviceIDHeader       = "X-Twinotify-Device-ID"
	pairNotifySignatureHeader      = "X-Twinotify-Pair-Signature"
	pairNotifyDeviceIDMaxBytes     = 256
	pairNotifySignatureBase64Bytes = 88
	pairNotifyProofDomain          = "twinotify-pair-notify-v1\n"
)

// handlePairNotify accepts signing-key-authenticated WebSocket subscriptions during pairing.
// Device A (role=A) waits for a peer.hello frame; Device B (role=B) waits for pair.sig.
// Holds open through the pair token's original TTL and replays every applicable
// persisted transition before waiting for live change signals.
func (s *Server) handlePairNotify(w http.ResponseWriter, r *http.Request) {
	pairToken := r.URL.Query().Get("token")
	role := r.URL.Query().Get("role")
	if pairToken == "" || (role != "A" && role != "B") {
		http.Error(w, "invalid request", http.StatusBadRequest)
		return
	}
	if !s.allowPairToken(w, pairToken) {
		return
	}
	// Validate and snapshot before upgrading. This deters unbounded subscriptions
	// and ensures the first replay does not depend on an in-memory hub frame.
	initial, err := s.pairStore.GetPending(pairToken)
	if err != nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if pairTokenExpired(initial, s.now()) {
		releaseMutation, admitted := s.acquireMutationAdmission()
		if !admitted {
			writeShutdownUnavailable(w)
			return
		}
		_ = s.pairStore.DeletePending(pairToken)
		releaseMutation()
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if !authorizePairNotify(r, initial, pairToken, role) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	initialFrames, err := pairTransitionFrames(initial, role)
	if err != nil {
		http.Error(w, "pair state unavailable", http.StatusInternalServerError)
		return
	}

	registration, registered := s.webSockets.register()
	if !registered {
		writeShutdownUnavailable(w)
		return
	}
	defer registration.unregister()

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	conn.SetReadLimit(256)
	expiresAt := time.Unix(initial.CreatedAt, 0).Add(pairNotifyMaxDuration)
	_ = conn.SetReadDeadline(expiresAt)

	connectionLifetime := make(chan struct{})
	readerDone := make(chan struct{})
	shutdownWorkerDone := make(chan struct{})
	go func() {
		if s.pairNotifyReaderStarted != nil {
			s.pairNotifyReaderStarted()
		}
		defer func() {
			if s.pairNotifyReaderBeforeExit != nil {
				s.pairNotifyReaderBeforeExit()
			}
			close(readerDone)
		}()
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				return
			}
		}
	}()
	go func() {
		defer close(shutdownWorkerDone)
		select {
		case signal := <-registration.drain:
			deadline := time.Now().Add(writeWait)
			_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(signal.code, signal.reason), deadline)
			_ = conn.Close()
		case <-connectionLifetime:
		}
	}()
	defer func() {
		close(connectionLifetime)
		_ = conn.Close()
		<-readerDone
		<-shutdownWorkerDone
	}()

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
		case <-readerDone:
			return
		}
	}
}

func authorizePairNotify(r *http.Request, pending *store.PendingPair, pairToken, role string) bool {
	deviceIDs := r.Header.Values(pairNotifyDeviceIDHeader)
	signatures := r.Header.Values(pairNotifySignatureHeader)
	if len(deviceIDs) != 1 || len(signatures) != 1 {
		return false
	}
	deviceID := deviceIDs[0]
	signatureBase64 := signatures[0]
	if deviceID == "" || len(deviceID) > pairNotifyDeviceIDMaxBytes || len(signatureBase64) != pairNotifySignatureBase64Bytes {
		return false
	}

	var expectedDeviceID string
	var publicKey []byte
	switch role {
	case "A":
		expectedDeviceID = pending.DeviceAID
		publicKey = pending.ASignPubkey
	case "B":
		expectedDeviceID = pending.DeviceBID
		publicKey = pending.BSignPubkey
	default:
		return false
	}
	if expectedDeviceID == "" || deviceID != expectedDeviceID || len(publicKey) != ed25519.PublicKeySize {
		return false
	}
	signature, err := base64.StdEncoding.DecodeString(signatureBase64)
	if err != nil || len(signature) != ed25519.SignatureSize {
		return false
	}
	canonical := []byte(pairNotifyProofDomain + pairToken + "\n" + role + "\n" + deviceID)
	return ed25519.Verify(ed25519.PublicKey(publicKey), canonical, signature)
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
