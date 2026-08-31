package server

import (
	"encoding/json"
	"errors"

	"github.com/google/uuid"
)

const invalidFrameMsgID = "00000000-0000-4000-8000-000000000000"

type RelayHeader struct {
	V    int    `json:"v"`
	Type string `json:"type"`
}

type RelayHello struct {
	V          int      `json:"v"`
	Type       string   `json:"type"`
	Protocols  []int    `json:"protocols"`
	AppVersion string   `json:"app_version"`
	Features   []string `json:"features,omitempty"`
}

type RelayPut struct {
	V        int             `json:"v"`
	Type     string          `json:"type"`
	Envelope json.RawMessage `json:"envelope"`
}

type RelayAck struct {
	V              int    `json:"v"`
	Type           string `json:"type"`
	MsgID          string `json:"msg_id"`
	EnvelopeSHA256 string `json:"envelope_sha256"`
}

type RelayAccepted struct {
	V          int    `json:"v"`
	Type       string `json:"type"`
	MsgID      string `json:"msg_id"`
	AcceptedAt int64  `json:"accepted_at"`
}

type RelayLegacyForwarded struct {
	V     int    `json:"v"`
	Type  string `json:"type"`
	MsgID string `json:"msg_id"`
}

type RelayDeliver struct {
	V          int             `json:"v"`
	Type       string          `json:"type"`
	AcceptedAt int64           `json:"accepted_at"`
	Envelope   json.RawMessage `json:"envelope"`
}

type RelayRejected struct {
	V      int    `json:"v"`
	Type   string `json:"type"`
	MsgID  string `json:"msg_id"`
	Reason string `json:"reason"`
}

type RelayExpired struct {
	V         int    `json:"v"`
	Type      string `json:"type"`
	MsgID     string `json:"msg_id"`
	ExpiredAt int64  `json:"expired_at"`
}

type RelayCapabilities struct {
	V            int       `json:"v"`
	Type         string    `json:"type"`
	Self         []int     `json:"self"`
	Peer         []int     `json:"peer"`
	Floor        int       `json:"floor"`
	SelfFeatures *[]string `json:"self_features,omitempty"`
	PeerFeatures *[]string `json:"peer_features,omitempty"`
}

type encryptedEnvelopeHeader struct {
	V            int    `json:"v"`
	Type         string `json:"type"`
	MsgID        string `json:"msg_id"`
	OriginDevice string `json:"origin_device"`
}

type RelayProtocolError struct {
	Code  string
	MsgID string
}

func (e *RelayProtocolError) Error() string {
	return e.Code
}

func newInvalidFrameError(raw []byte) *RelayProtocolError {
	return &RelayProtocolError{Code: "invalid_frame", MsgID: relayFrameMsgID(raw)}
}

func parseRelayFrame(validator *Validator, raw []byte) (any, error) {
	var header RelayHeader
	if err := json.Unmarshal(raw, &header); err != nil {
		return nil, newInvalidFrameError(raw)
	}
	if err := validator.ValidateRelayControl(raw); err != nil {
		return nil, newInvalidFrameError(raw)
	}

	switch header.Type {
	case "relay.hello":
		var frame RelayHello
		if err := json.Unmarshal(raw, &frame); err != nil {
			return nil, newInvalidFrameError(raw)
		}
		return frame, nil
	case "relay.put":
		var frame RelayPut
		if err := json.Unmarshal(raw, &frame); err != nil {
			return nil, newInvalidFrameError(raw)
		}
		return frame, nil
	case "relay.ack":
		var frame RelayAck
		if err := json.Unmarshal(raw, &frame); err != nil {
			return nil, newInvalidFrameError(raw)
		}
		return frame, nil
	default:
		return nil, newInvalidFrameError(raw)
	}
}

func relayFrameMsgID(raw []byte) string {
	var frame struct {
		MsgID    string          `json:"msg_id"`
		Envelope json.RawMessage `json:"envelope"`
	}
	if err := json.Unmarshal(raw, &frame); err != nil {
		return invalidFrameMsgID
	}
	if _, err := uuid.Parse(frame.MsgID); err == nil {
		return frame.MsgID
	}
	if len(frame.Envelope) > 0 {
		var envelope encryptedEnvelopeHeader
		if err := json.Unmarshal(frame.Envelope, &envelope); err == nil {
			if _, err := uuid.Parse(envelope.MsgID); err == nil {
				return envelope.MsgID
			}
		}
	}
	return invalidFrameMsgID
}

func normalizedRelayMsgID(msgID string) string {
	if _, err := uuid.Parse(msgID); err == nil {
		return msgID
	}
	return invalidFrameMsgID
}

func asRelayProtocolError(err error) *RelayProtocolError {
	var protocolErr *RelayProtocolError
	if errors.As(err, &protocolErr) {
		return protocolErr
	}
	return &RelayProtocolError{Code: "invalid_frame", MsgID: invalidFrameMsgID}
}
