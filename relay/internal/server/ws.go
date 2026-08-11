package server

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/http"
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
	pongWait                 = 60 * time.Second
	pingPeriod               = (pongWait * 9) / 10
	writeWait                = 10 * time.Second
)

var upgrader = websocket.Upgrader{
	// /ws is gated by JWT authentication against the paired device signing key.
	// Browser origin is not an identity boundary for the native clients.
	CheckOrigin: func(r *http.Request) bool { return true },
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
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

	conn.SetReadLimit(maxRelayControlFrameSize)
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

	outbound := make(chan []byte, mailboxBatchSize)
	client := s.clientHub.Register(deviceID, outbound)
	defer s.clientHub.Unregister(client)
	connectionLifetime := make(chan struct{})
	defer close(connectionLifetime)
	go func() {
		select {
		case <-client.done:
			_ = conn.Close()
		case <-connectionLifetime:
		}
	}()

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
			if err := s.handleLegacyFrame(deviceID, client, &protocol, msg); err != nil {
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
				if err := s.handleRelayHello(deviceID, client, typed, writeFrame, func(ids []string) {
					drainedIDs = ids
				}); err != nil {
					log.Printf("relay hello for %s: %v", deviceID, err)
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
				s.handleRelayPut(deviceID, typed, writeFrame, writeRejected)
			case RelayAck:
				if protocol != protocolV2 {
					_ = writeRejected(typed.MsgID, "invalid_frame")
					continue
				}
				if err := s.handleRelayAck(deviceID, typed); err != nil {
					_ = writeRejected(typed.MsgID, relayAckErrorCode(err))
				}
			}
		default:
			_ = writeRejected(relayFrameMsgID(msg), "invalid_frame")
		}
	}
}

func (s *Server) handleRelayAck(deviceID string, ack RelayAck) error {
	return s.mailbox.Ack(deviceID, ack.MsgID, ack.EnvelopeSHA256, time.Now())
}

func (s *Server) handleLegacyFrame(deviceID string, client *wsClient, protocol *connectionProtocol, raw []byte) error {
	if *protocol == protocolV2 {
		return errors.New("mixed protocol")
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
	peerID, err := s.pairStore.PeerFor(deviceID)
	if err != nil || peerID == "" {
		return errors.New("not paired")
	}
	_ = s.clientHub.SendRawV1(peerID, raw)
	return nil
}

func (s *Server) handleRelayHello(
	deviceID string,
	client *wsClient,
	hello RelayHello,
	writeFrame func(any) error,
	recordDrained func([]string),
) error {
	peerID, err := s.pairStore.PeerFor(deviceID)
	if err != nil || peerID == "" {
		return errors.New("not paired")
	}
	peerProtocols := []int{1}
	if peerProtocol, advertised, online := s.clientHub.ConnectionFor(peerID); online {
		switch peerProtocol {
		case protocolLegacy:
			peerProtocols = []int{1}
		case protocolV2Handshake, protocolV2:
			peerProtocols = advertised
		}
	}
	if err := writeFrame(RelayCapabilities{
		V: 2, Type: "relay.capabilities", Self: append([]int(nil), hello.Protocols...), Peer: peerProtocols, Floor: 1,
	}); err != nil {
		return err
	}

	now := time.Now()
	if _, err := s.mailbox.Expire(now); err != nil {
		return err
	}
	statuses, err := s.mailbox.ExpiryStatuses(deviceID, peerID, mailboxBatchSize)
	if err != nil {
		return err
	}
	reportedIDs := make([]string, 0, len(statuses))
	for _, status := range statuses {
		if err := writeFrame(RelayExpired{
			V: 2, Type: "relay.expired", MsgID: status.MsgID, ExpiredAt: status.OccurredAt,
		}); err != nil {
			return err
		}
		reportedIDs = append(reportedIDs, status.MsgID)
	}
	if err := s.mailbox.MarkExpiryStatusesReported(deviceID, peerID, reportedIDs); err != nil {
		return err
	}

	pending, err := s.mailbox.Pending(deviceID, mailboxBatchSize)
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
	drainedIDs, err := s.transferDurableRecords(deviceID, notifications, func(notices []queuedV2Notification, frames [][]byte) bool {
		return s.clientHub.TransferHandshakeV2Batch(client, notices, frames)
	})
	if err != nil {
		return err
	}
	recordDrained(drainedIDs)
	return nil
}

func (s *Server) handleRelayPut(
	deviceID string,
	put RelayPut,
	writeFrame func(any) error,
	writeRejected func(string, string) error,
) {
	var envelope encryptedEnvelopeHeader
	if err := s.validator.ValidateEncryptedEnvelope(put.Envelope); err != nil {
		_ = writeRejected(relayFrameMsgID(mustWrapEnvelope(put.Envelope)), "invalid_frame")
		return
	}
	if err := json.Unmarshal(put.Envelope, &envelope); err != nil || envelope.Type != "enc" {
		_ = writeRejected(envelope.MsgID, "invalid_frame")
		return
	}
	if envelope.OriginDevice != deviceID {
		_ = writeRejected(envelope.MsgID, "invalid_frame")
		return
	}
	peerID, err := s.pairStore.PeerFor(deviceID)
	if err != nil || peerID == "" {
		_ = writeRejected(envelope.MsgID, "not_recipient")
		return
	}

	peerProtocol, peerProtocols, peerOnline := s.clientHub.ConnectionFor(peerID)
	if envelope.V == 1 {
		if peerProtocol != protocolV2 && peerProtocol != protocolV2Handshake {
			if peerOnline && peerProtocol == protocolLegacy && s.clientHub.SendLegacy(peerID, put.Envelope) {
				_ = writeFrame(RelayLegacyForwarded{V: 2, Type: "relay.legacy_forwarded", MsgID: envelope.MsgID})
				return
			}
			_ = writeRejected(envelope.MsgID, "peer_legacy")
			return
		}
		if !supportsProtocol(peerProtocols, 1) {
			_ = writeRejected(envelope.MsgID, "peer_legacy")
			return
		}
	} else if peerOnline && !supportsProtocol(peerProtocols, 2) {
		_ = writeRejected(envelope.MsgID, "peer_legacy")
		return
	}

	digest := sha256.Sum256(put.Envelope)
	handoff := s.handoffs.acquire(peerID)
	defer s.handoffs.release(peerID, handoff)
	handoff.commitMu.Lock()
	result, err := s.mailbox.Put(store.MailboxRecord{
		RecipientDevice: peerID,
		SenderDevice:    deviceID,
		MsgID:           envelope.MsgID,
		EnvelopeSHA256:  hex.EncodeToString(digest[:]),
		Envelope:        append([]byte(nil), put.Envelope...),
	}, time.Now())
	var notification *durableNotification
	if err == nil && !result.Terminal {
		var overflow bool
		notification, overflow = s.handoffs.record(handoff, peerID, envelope.MsgID, result.AcceptanceSequence, uint64(len(put.Envelope)))
		if overflow {
			s.clientHub.Stop(peerID)
		}
	}
	handoff.commitMu.Unlock()
	if err != nil {
		_ = writeRejected(envelope.MsgID, relayPutErrorCode(err))
		return
	}

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
	if len(notifications) == 0 {
		return
	}
	recipient := notifications[0].recipient
	queued := make([]queuedV2Notification, 0, len(notifications))
	for _, notification := range notifications {
		queued = append(queued, queuedV2Notification{
			msgID: notification.msgID, sequence: notification.sequence, byteSize: notification.byteSize,
		})
	}
	_, err := s.transferDurableRecords(recipient, queued, func(notices []queuedV2Notification, frames [][]byte) bool {
		// Live queue pressure affects latency only. TransferV2Batch stops an
		// overfull current client so its reconnect drains Bolt in exact order.
		_ = s.clientHub.TransferV2Batch(recipient, notices, frames)
		return true
	})
	if err != nil {
		log.Printf("transfer durable relay delivery for %s: %v", recipient, err)
		s.clientHub.Stop(recipient)
	}
}

func (s *Server) transferHandoffFrames(client *wsClient, notifications []queuedV2Notification) error {
	_, err := s.transferDurableRecords(client.deviceID, notifications, func(notices []queuedV2Notification, frames [][]byte) bool {
		return s.clientHub.TransferHandshakeV2Batch(client, notices, frames)
	})
	return err
}

func (s *Server) transferDurableRecords(
	recipient string,
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
	err := s.mailbox.TransferLiveByIDs(recipient, msgIDs, time.Now(), func(records []store.MailboxRecord) error {
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
	case errors.Is(err, store.ErrMailboxFull):
		return "mailbox_full"
	case errors.Is(err, store.ErrMessageIDConflict):
		return "id_conflict"
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
