package server

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/twinotify/relay/internal/store"
)

const (
	maxMessageSize           = 1 << 20
	maxRelayControlFrameSize = maxMessageSize + (4 << 10)
	mailboxBatchSize         = 64
	outboundQueueSize        = 128
	pongWait                 = 60 * time.Second
	pingPeriod               = (pongWait * 9) / 10
	writeWait                = 10 * time.Second
)

var upgrader = websocket.Upgrader{
	// /ws is gated by JWT authentication against the paired device signing key.
	// Browser origin is not an identity boundary for the native clients.
	CheckOrigin: func(r *http.Request) bool { return true },
}

func requireWebSocketUpgrade(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !websocket.IsWebSocketUpgrade(r) {
			w.Header().Set("Upgrade", "websocket")
			http.Error(w, "WebSocket upgrade required", http.StatusUpgradeRequired)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	deviceID, ok := DeviceIDFromContext(r.Context())
	if !ok || deviceID == "" {
		http.Error(w, "no device id", http.StatusUnauthorized)
		return
	}
	pairID, ok := PairIDFromContext(r.Context())
	if !ok || pairID == "" {
		http.Error(w, "no pair id", http.StatusUnauthorized)
		return
	}

	registration, lifecycleRegistered := s.webSockets.register()
	if !lifecycleRegistered {
		writeShutdownUnavailable(w)
		return
	}
	defer registration.unregister()

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		slog.Warn("websocket_upgrade_failed")
		return
	}
	s.metrics.connectionOpened()
	defer s.metrics.connectionClosed()
	defer conn.Close()

	conn.SetReadLimit(maxRelayControlFrameSize)
	_ = conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	var writeMu sync.Mutex
	writeMsg := func(mt int, data []byte) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		if s.webSocketWriteAfterLock != nil {
			s.webSocketWriteAfterLock()
		}
		_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
		return conn.WriteMessage(mt, data)
	}
	writeFrame := func(frame any) error {
		raw, err := json.Marshal(frame)
		if err != nil {
			return err
		}
		return writeMsg(websocket.TextMessage, raw)
	}
	writeRejected := func(msgID, reason string) error {
		return writeFrame(RelayRejected{V: 2, Type: "relay.rejected", MsgID: normalizedRelayMsgID(msgID), Reason: reason})
	}

	outbound := make(chan []byte, outboundQueueSize)
	if s.webSocketBeforeRegister != nil {
		s.webSocketBeforeRegister(deviceID, pairID)
	}
	client, registered := s.clientHub.registerPair(deviceID, pairID, outbound)
	if !registered {
		select {
		case signal := <-registration.drain:
			deadline := time.Now().Add(writeWait)
			_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(signal.code, signal.reason), deadline)
		default:
		}
		s.clientHub.Unregister(client)
		return
	}
	connectionLifetime := make(chan struct{})
	stopPinger := make(chan struct{})
	var connectionWorkers sync.WaitGroup
	defer func() {
		close(connectionLifetime)
		close(stopPinger)
		client.stop()
		_ = conn.Close()
		connectionWorkers.Wait()
		s.clientHub.Unregister(client)
	}()
	connectionWorkers.Add(1)
	go func() {
		defer connectionWorkers.Done()
		select {
		case signal := <-registration.drain:
			deadline := time.Now().Add(writeWait)
			_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(signal.code, signal.reason), deadline)
			_ = conn.Close()
		case signal := <-client.drain:
			deadline := time.Now().Add(writeWait)
			_ = conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(signal.code, signal.reason), deadline)
			_ = conn.Close()
		case <-client.done:
			_ = conn.Close()
		case <-connectionLifetime:
		}
	}()

	if err := s.pairStore.ValidateSession(deviceID, pairID); err != nil {
		return
	}
	connectionWorkers.Add(1)
	go func() {
		defer connectionWorkers.Done()
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

	connectionWorkers.Add(1)
	go func() {
		defer connectionWorkers.Done()
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

	protocol := protocolUnknown
	for {
		messageType, msg, err := conn.ReadMessage()
		if err != nil {
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(pongWait))
		if messageType != websocket.TextMessage {
			_ = writeRejected(invalidFrameMsgID, "invalid_frame")
			continue
		}
		if len(msg) > maxMessageSize && !isBoundedRelayPut(msg) {
			return
		}

		var header RelayHeader
		if err := json.Unmarshal(msg, &header); err != nil {
			_ = writeRejected(relayFrameMsgID(msg), "invalid_frame")
			continue
		}
		switch header.V {
		case 1:
			if err := s.handleLegacyFrameForPair(deviceID, pairID, client, &protocol, msg); err != nil {
				_ = writeRejected(relayFrameMsgID(msg), "invalid_frame")
			}
		case 2:
			frame, err := parseRelayFrame(s.validator, msg)
			if err != nil {
				protocolErr := asRelayProtocolError(err)
				_ = writeRejected(protocolErr.MsgID, protocolErr.Code)
				continue
			}
			if protocol == protocolLegacy {
				_ = writeRejected(relayFrameMsgID(msg), "invalid_frame")
				continue
			}
			switch typed := frame.(type) {
			case RelayHello:
				if protocol != protocolUnknown {
					_ = writeRejected(invalidFrameMsgID, "invalid_frame")
					continue
				}
				protocol = protocolV2
				if !s.clientHub.SetProtocolAndCapabilities(client, protocolV2Handshake, typed.Protocols) {
					return
				}
				var drainedIDs []string
				if err := s.handleRelayHelloForPair(deviceID, pairID, client, typed, writeFrame, func(ids []string) {
					drainedIDs = ids
				}); err != nil {
					slog.Error("relay_hello_failed")
					return
				}
				if s.relayHelloBeforeActivate != nil {
					s.relayHelloBeforeActivate(deviceID)
				}
				if !s.clientHub.FlushOrActivateV2(client, drainedIDs) {
					return
				}
			case RelayPut:
				if protocol != protocolV2 {
					_ = writeRejected(relayFrameMsgID(msg), "invalid_frame")
					continue
				}
				s.handleRelayPutForPair(deviceID, pairID, typed, writeFrame, writeRejected)
			case RelayAck:
				if protocol != protocolV2 {
					_ = writeRejected(typed.MsgID, "invalid_frame")
					continue
				}
				if err := s.handleRelayAckForPair(deviceID, pairID, typed); err != nil {
					_ = writeRejected(typed.MsgID, relayAckErrorCode(err))
				}
			}
		default:
			_ = writeRejected(relayFrameMsgID(msg), "invalid_frame")
		}
	}
}

func (s *Server) handleRelayAck(deviceID string, ack RelayAck) error {
	session, err := s.pairStore.SessionFor(deviceID)
	if err != nil {
		return err
	}
	return s.handleRelayAckForPair(deviceID, session.PairID, ack)
}

func (s *Server) handleRelayAckForPair(deviceID, pairID string, ack RelayAck) error {
	if s.relayAckBeforeStore != nil {
		s.relayAckBeforeStore(deviceID, pairID, ack.MsgID)
	}
	releaseMutation, admitted := s.acquireMutationAdmission()
	if !admitted {
		return ErrServerCapacity
	}
	err := s.mailbox.AckForPair(pairID, deviceID, ack.MsgID, ack.EnvelopeSHA256, time.Now())
	releaseMutation()
	return err
}

func (s *Server) handleLegacyFrame(deviceID string, client *wsClient, protocol *connectionProtocol, raw []byte) error {
	session, err := s.pairStore.SessionFor(deviceID)
	if err != nil {
		return err
	}
	return s.handleLegacyFrameForPair(deviceID, session.PairID, client, protocol, raw)
}

func (s *Server) handleLegacyFrameForPair(deviceID, pairID string, client *wsClient, protocol *connectionProtocol, raw []byte) error {
	if *protocol == protocolV2 {
		return errors.New("mixed protocol")
	}
	_, _, floor, err := s.pairStore.CapabilitiesForPair(deviceID, pairID)
	if err != nil {
		return err
	}
	if floor >= 2 {
		return errors.New("v1 below negotiated protocol floor")
	}
	if err := s.validator.ValidateEnvelope(raw); err != nil {
		return err
	}
	var envelope encryptedEnvelopeHeader
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return err
	}
	if envelope.OriginDevice != "" && envelope.OriginDevice != deviceID {
		return errors.New("origin mismatch")
	}
	if *protocol == protocolUnknown {
		*protocol = protocolLegacy
		if !s.clientHub.SetProtocol(client, protocolLegacy) {
			return errors.New("connection replaced")
		}
	}
	peerID, err := s.pairStore.PeerForPair(deviceID, pairID)
	if err != nil || peerID == "" {
		return errors.New("not paired")
	}
	_ = s.clientHub.SendRawV1ForPair(peerID, pairID, raw)
	return nil
}

func (s *Server) handleRelayHello(
	deviceID string,
	client *wsClient,
	hello RelayHello,
	writeFrame func(any) error,
	recordDrained func([]string),
) error {
	session, err := s.pairStore.SessionFor(deviceID)
	if err != nil {
		return err
	}
	return s.handleRelayHelloForPair(deviceID, session.PairID, client, hello, writeFrame, recordDrained)
}

func (s *Server) handleRelayHelloForPair(
	deviceID, pairID string,
	client *wsClient,
	hello RelayHello,
	writeFrame func(any) error,
	recordDrained func([]string),
) error {
	peerID, err := s.pairStore.PeerForPair(deviceID, pairID)
	if err != nil || peerID == "" {
		return errors.New("not paired")
	}
	priorPeerSelf, priorPeerView, priorPeerFloor, err := s.pairStore.CapabilitiesForPair(peerID, pairID)
	if err != nil {
		return err
	}
	priorPeerFrame := relayCapabilitiesSnapshot(priorPeerSelf, priorPeerView, priorPeerFloor)
	releaseMutation, admitted := s.acquireMutationAdmission()
	if !admitted {
		return ErrServerCapacity
	}
	err = s.pairStore.UpdateCapabilitiesForPair(deviceID, pairID, hello.Protocols, hello.AppVersion)
	releaseMutation()
	if err != nil {
		return err
	}
	selfCapabilities, peerCapabilities, floor, err := s.pairStore.CapabilitiesForPair(deviceID, pairID)
	if err != nil {
		return err
	}
	capabilitiesFrame := relayCapabilitiesSnapshot(selfCapabilities, peerCapabilities, floor)
	peerSelf, peerPeer, peerFloor, err := s.pairStore.CapabilitiesForPair(peerID, pairID)
	if err != nil {
		return err
	}
	peerCapabilitiesFrame := relayCapabilitiesSnapshot(peerSelf, peerPeer, peerFloor)
	if !relayCapabilitiesEqual(priorPeerFrame, peerCapabilitiesFrame) {
		peerFrame, err := json.Marshal(peerCapabilitiesFrame)
		if err != nil {
			return err
		}
		s.clientHub.SendCapabilitiesForPair(peerID, pairID, peerCapabilitiesFrame.Self, peerFrame)
	}
	if err := writeFrame(capabilitiesFrame); err != nil {
		return err
	}

	now := time.Now()
	if s.relayHelloBeforeMailboxStore != nil {
		s.relayHelloBeforeMailboxStore("expiry")
	}
	releaseMutation, admitted = s.acquireMutationAdmission()
	if !admitted {
		return ErrServerCapacity
	}
	_, err = s.mailbox.ExpireForPair(pairID, deviceID, now)
	var statuses []store.DeliveryStatus
	if err == nil {
		statuses, err = s.mailbox.ExpiryStatusesForPair(pairID, deviceID, peerID, mailboxBatchSize, now)
	}
	releaseMutation()
	if err != nil {
		return err
	}
	for _, status := range statuses {
		if err := writeFrame(RelayExpired{
			V: 2, Type: "relay.expired", MsgID: status.MsgID, ExpiredAt: status.OccurredAt,
		}); err != nil {
			return err
		}
	}
	if len(statuses) > 0 {
		if s.relayHelloBeforeMailboxStore != nil {
			s.relayHelloBeforeMailboxStore("cursor")
		}
		releaseMutation, admitted = s.acquireMutationAdmission()
		if !admitted {
			return ErrServerCapacity
		}
		err = s.mailbox.AdvanceExpiryStatusCursorForPair(pairID, deviceID, peerID, statuses[len(statuses)-1].MsgID)
		releaseMutation()
		if err != nil {
			return err
		}
	}

	pending, err := s.mailbox.PendingForPair(pairID, deviceID, mailboxBatchSize)
	if err != nil {
		return err
	}
	notifications := make([]queuedV2Notification, 0, len(pending))
	for _, rec := range pending {
		if rec.SenderDevice != peerID {
			continue
		}
		notifications = append(notifications, queuedV2Notification{
			msgID: rec.MsgID, sequence: rec.AcceptanceSequence, byteSize: rec.ByteSize,
		})
	}
	drainedIDs, err := s.transferDurableRecords(pairID, deviceID, notifications, func(notices []queuedV2Notification, frames [][]byte) bool {
		return s.clientHub.TransferHandshakeV2Batch(client, notices, frames)
	})
	if err != nil {
		return err
	}
	recordDrained(drainedIDs)
	return nil
}

func relayCapabilitiesSnapshot(self, peer store.DeviceCapabilities, floor int) RelayCapabilities {
	peerProtocols := append([]int(nil), peer.Protocols...)
	if len(peerProtocols) == 0 {
		peerProtocols = []int{1}
	}
	return RelayCapabilities{
		V: 2, Type: "relay.capabilities", Self: append([]int(nil), self.Protocols...), Peer: peerProtocols, Floor: floor,
	}
}

func relayCapabilitiesEqual(a, b RelayCapabilities) bool {
	return a.Floor == b.Floor && slices.Equal(a.Self, b.Self) && slices.Equal(a.Peer, b.Peer)
}

func (s *Server) handleRelayPut(
	deviceID string,
	put RelayPut,
	writeFrame func(any) error,
	writeRejected func(string, string) error,
) {
	session, err := s.pairStore.SessionFor(deviceID)
	if err != nil {
		s.metrics.recordRelayPutRejected("not_recipient")
		_ = writeRejected(relayFrameMsgID(mustWrapEnvelope(put.Envelope)), "not_recipient")
		return
	}
	s.handleRelayPutForPair(deviceID, session.PairID, put, writeFrame, writeRejected)
}

func (s *Server) handleRelayPutForPair(
	deviceID, pairID string,
	put RelayPut,
	writeFrame func(any) error,
	writeRejected func(string, string) error,
) {
	reject := func(msgID, reason string) {
		s.metrics.recordRelayPutRejected(reason)
		_ = writeRejected(msgID, reason)
	}
	if s.shuttingDown.Load() {
		reject(relayFrameMsgID(mustWrapEnvelope(put.Envelope)), "server_capacity")
		return
	}
	var envelope encryptedEnvelopeHeader
	if err := s.validator.ValidateEncryptedEnvelope(put.Envelope); err != nil {
		reject(relayFrameMsgID(mustWrapEnvelope(put.Envelope)), "invalid_frame")
		return
	}
	if err := json.Unmarshal(put.Envelope, &envelope); err != nil || envelope.Type != "enc" {
		reject(envelope.MsgID, "invalid_frame")
		return
	}
	if envelope.OriginDevice != deviceID {
		reject(envelope.MsgID, "invalid_frame")
		return
	}
	peerID, err := s.pairStore.PeerForPair(deviceID, pairID)
	if err != nil || peerID == "" {
		reject(envelope.MsgID, "not_recipient")
		return
	}

	_, persistedPeerCapabilities, floor, err := s.pairStore.CapabilitiesForPair(deviceID, pairID)
	if err != nil {
		reject(envelope.MsgID, "not_recipient")
		return
	}
	peerProtocol, peerProtocols, peerOnline := s.clientHub.ConnectionForPair(peerID, pairID)
	if envelope.V == 1 {
		if floor >= 2 {
			reject(envelope.MsgID, "peer_legacy")
			return
		}
		if peerOnline {
			switch peerProtocol {
			case protocolLegacy:
				if s.clientHub.SendLegacyForPair(peerID, pairID, put.Envelope) {
					_ = writeFrame(RelayLegacyForwarded{V: 2, Type: "relay.legacy_forwarded", MsgID: envelope.MsgID})
					return
				}
				reject(envelope.MsgID, "peer_legacy")
				return
			case protocolV2, protocolV2Handshake:
				if !supportsProtocol(peerProtocols, 1) {
					reject(envelope.MsgID, "peer_legacy")
					return
				}
			default:
				reject(envelope.MsgID, "peer_legacy")
				return
			}
		} else if !supportsProtocol(persistedPeerCapabilities.Protocols, 1) {
			reject(envelope.MsgID, "peer_legacy")
			return
		}
	} else if floor < 2 {
		reject(envelope.MsgID, "peer_legacy")
		return
	}
	digest := sha256.Sum256(put.Envelope)
	handoff := s.handoffs.acquire(peerID)
	defer s.handoffs.release(peerID, handoff)
	handoff.commitMu.Lock()
	if s.relayPutBeforeStore != nil {
		s.relayPutBeforeStore(deviceID, pairID, envelope.MsgID)
	}
	releaseMutation, admitted := s.acquireMutationAdmission()
	if !admitted {
		handoff.commitMu.Unlock()
		reject(envelope.MsgID, "server_capacity")
		return
	}
	result, err := s.mailbox.PutForPairWithAdmission(pairID, store.MailboxRecord{
		RecipientDevice: peerID,
		SenderDevice:    deviceID,
		MsgID:           envelope.MsgID,
		EnvelopeSHA256:  hex.EncodeToString(digest[:]),
		Envelope:        append([]byte(nil), put.Envelope...),
	}, time.Now(), store.MailboxPutAdmission(s.capacityCheck))
	releaseMutation()
	var notification *durableNotification
	if err == nil && !result.Terminal {
		var overflow bool
		notification, overflow = s.handoffs.record(handoff, pairID, peerID, envelope.MsgID, result.AcceptanceSequence, uint64(len(put.Envelope)))
		if overflow {
			s.clientHub.StopPair(peerID, pairID)
		}
	}
	handoff.commitMu.Unlock()
	if err != nil {
		reject(envelope.MsgID, relayPutErrorCode(err))
		return
	}
	s.metrics.recordRelayPutAccepted()

	writeErr := writeFrame(RelayAccepted{
		V: 2, Type: "relay.accepted", MsgID: envelope.MsgID, AcceptedAt: result.AcceptedAt,
	})
	if notification != nil {
		handoff.complete(notification, s.dispatchDurableNotifications)
	}
	if writeErr != nil || result.Terminal {
		return
	}
}

func (s *Server) dispatchDurableNotifications(notifications []durableNotification) {
	for start := 0; start < len(notifications); {
		end := start + 1
		for end < len(notifications) && notifications[end].pairID == notifications[start].pairID {
			end++
		}
		s.dispatchDurableNotificationBatch(notifications[start:end])
		start = end
	}
}

func (s *Server) dispatchDurableNotificationBatch(notifications []durableNotification) {
	pairID := notifications[0].pairID
	recipient := notifications[0].recipient
	queued := make([]queuedV2Notification, 0, len(notifications))
	for _, notification := range notifications {
		queued = append(queued, queuedV2Notification{
			msgID: notification.msgID, sequence: notification.sequence, byteSize: notification.byteSize,
		})
	}
	_, err := s.transferDurableRecords(pairID, recipient, queued, func(notices []queuedV2Notification, frames [][]byte) bool {
		// Live queue pressure affects latency only. TransferV2Batch stops an
		// overfull current client so its reconnect drains Bolt in exact order.
		_ = s.clientHub.TransferV2BatchForPair(recipient, pairID, notices, frames)
		return true
	})
	if err != nil {
		slog.Error("relay_delivery_transfer_failed")
		s.clientHub.StopPair(recipient, pairID)
	}
}

func (s *Server) transferHandoffFrames(client *wsClient, notifications []queuedV2Notification) error {
	_, err := s.transferDurableRecords(client.pairID, client.deviceID, notifications, func(notices []queuedV2Notification, frames [][]byte) bool {
		return s.clientHub.TransferHandshakeV2Batch(client, notices, frames)
	})
	return err
}

func (s *Server) transferDurableRecords(
	pairID, recipient string,
	notifications []queuedV2Notification,
	enqueue func([]queuedV2Notification, [][]byte) bool,
) ([]string, error) {
	if s.relayBeforeDeliveryTransfer != nil {
		s.relayBeforeDeliveryTransfer(recipient)
	}
	msgIDs := make([]string, 0, len(notifications))
	for _, notification := range notifications {
		msgIDs = append(msgIDs, notification.msgID)
	}
	transferred := []string{}
	err := s.mailbox.TransferLiveByIDsForPair(pairID, recipient, msgIDs, time.Now(), func(records []store.MailboxRecord) error {
		byID := make(map[string]store.MailboxRecord, len(records))
		for _, rec := range records {
			byID[rec.MsgID] = rec
		}
		liveNotices := make([]queuedV2Notification, 0, len(records))
		frames := make([][]byte, 0, len(records))
		for _, notification := range notifications {
			if rec, ok := byID[notification.msgID]; ok {
				liveNotices = append(liveNotices, notification)
				frames = append(frames, marshalRelayDeliver(rec))
			}
		}
		if !enqueue(liveNotices, frames) {
			return errors.New("delivery queue unavailable")
		}
		for _, notification := range liveNotices {
			transferred = append(transferred, notification.msgID)
		}
		return nil
	})
	return transferred, err
}

func isBoundedRelayPut(raw []byte) bool {
	var put RelayPut
	if err := json.Unmarshal(raw, &put); err != nil {
		return false
	}
	return put.V == 2 && put.Type == "relay.put" && len(put.Envelope) <= maxMessageSize
}

func relayPutErrorCode(err error) string {
	switch {
	case errors.Is(err, store.ErrNotFound):
		return "not_recipient"
	case errors.Is(err, store.ErrMailboxFull):
		return "mailbox_full"
	case errors.Is(err, store.ErrMessageIDConflict):
		return "id_conflict"
	case errors.Is(err, ErrServerCapacity):
		return "server_capacity"
	default:
		return "invalid_frame"
	}
}

func relayAckErrorCode(err error) string {
	switch {
	case errors.Is(err, store.ErrDigestMismatch):
		return "digest_mismatch"
	case errors.Is(err, store.ErrNotFound):
		return "not_recipient"
	case errors.Is(err, ErrServerCapacity):
		return "server_capacity"
	default:
		return "invalid_frame"
	}
}

func marshalRelayDeliver(rec store.MailboxRecord) []byte {
	// Keep the envelope token byte-for-byte identical to the accepted input.
	// encoding/json compacts RawMessage values, so this typed frame is serialized
	// explicitly around the already validated opaque envelope.
	prefix := `{"v":2,"type":"relay.deliver","accepted_at":` + strconv.FormatInt(rec.AcceptedAt, 10) + `,"envelope":`
	frame := make([]byte, 0, len(prefix)+len(rec.Envelope)+1)
	frame = append(frame, prefix...)
	frame = append(frame, rec.Envelope...)
	frame = append(frame, '}')
	return frame
}

func mustWrapEnvelope(envelope json.RawMessage) []byte {
	frame := make([]byte, 0, len(envelope)+13)
	frame = append(frame, `{"envelope":`...)
	frame = append(frame, envelope...)
	frame = append(frame, '}')
	return frame
}
