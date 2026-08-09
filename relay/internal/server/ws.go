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

	outbound := make(chan []byte, 32)
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
				if !s.clientHub.SetProtocol(client, protocolV2Handshake) {
					return
				}
				if err := s.handleRelayHello(deviceID, typed, writeFrame, writeMsg); err != nil {
					log.Printf("relay hello for %s: %v", deviceID, err)
					return
				}
				if !s.clientHub.SetProtocol(client, protocolV2) {
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
	err := s.mailbox.Ack(deviceID, ack.MsgID, ack.EnvelopeSHA256, time.Now())
	if !errors.Is(err, store.ErrNotFound) {
		return err
	}
	peerID, peerErr := s.pairStore.PeerFor(deviceID)
	if peerErr != nil {
		return err
	}
	statuses, statusErr := s.mailbox.Statuses(peerID, time.UnixMilli(0))
	if statusErr != nil {
		return statusErr
	}
	for _, status := range statuses {
		if status.MsgID == ack.MsgID && status.Status == "acknowledged" && status.RecipientDevice == deviceID {
			return nil
		}
	}
	return err
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
	hello RelayHello,
	writeFrame func(any) error,
	writeMsg func(int, []byte) error,
) error {
	peerID, err := s.pairStore.PeerFor(deviceID)
	if err != nil || peerID == "" {
		return errors.New("not paired")
	}
	peerProtocols := []int{1}
	if peerProtocol, online := s.clientHub.ProtocolFor(peerID); online && peerProtocol == protocolV2 {
		peerProtocols = []int{2, 1}
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
	if err := s.mailbox.ExpireStatuses(now); err != nil {
		return err
	}
	statuses, err := s.mailbox.Statuses(deviceID, time.UnixMilli(0))
	if err != nil {
		return err
	}
	for _, status := range statuses {
		if status.Status != "expired" || status.RecipientDevice != peerID {
			continue
		}
		if err := writeFrame(RelayExpired{
			V: 2, Type: "relay.expired", MsgID: status.MsgID, ExpiredAt: status.OccurredAt,
		}); err != nil {
			return err
		}
	}

	pending, err := s.mailbox.Pending(deviceID, mailboxBatchSize)
	if err != nil {
		return err
	}
	for _, rec := range pending {
		if rec.SenderDevice != peerID {
			continue
		}
		if err := writeMsg(websocket.TextMessage, marshalRelayDeliver(rec)); err != nil {
			return err
		}
	}
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

	peerProtocol, peerOnline := s.clientHub.ProtocolFor(peerID)
	if envelope.V == 1 {
		if peerProtocol != protocolV2 {
			if peerOnline && peerProtocol == protocolLegacy && s.clientHub.SendLegacy(peerID, put.Envelope) {
				_ = writeFrame(RelayLegacyForwarded{V: 2, Type: "relay.legacy_forwarded", MsgID: envelope.MsgID})
				return
			}
			_ = writeRejected(envelope.MsgID, "peer_legacy")
			return
		}
	} else if peerOnline && peerProtocol == protocolLegacy {
		_ = writeRejected(envelope.MsgID, "peer_legacy")
		return
	}

	digest := sha256.Sum256(put.Envelope)
	result, err := s.mailbox.Put(store.MailboxRecord{
		RecipientDevice: peerID,
		SenderDevice:    deviceID,
		MsgID:           envelope.MsgID,
		EnvelopeSHA256:  hex.EncodeToString(digest[:]),
		Envelope:        append([]byte(nil), put.Envelope...),
	}, time.Now())
	if err != nil {
		_ = writeRejected(envelope.MsgID, relayPutErrorCode(err))
		return
	}

	if err := writeFrame(RelayAccepted{
		V: 2, Type: "relay.accepted", MsgID: envelope.MsgID, AcceptedAt: result.AcceptedAt,
	}); err != nil {
		return
	}
	_ = s.clientHub.SendV2(peerID, marshalRelayDeliver(store.MailboxRecord{
		AcceptedAt: result.AcceptedAt,
		Envelope:   put.Envelope,
	}))
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
