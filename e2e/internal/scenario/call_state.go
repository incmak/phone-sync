package scenario

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"github.com/twinotify/phone-sync/e2e/internal/control"
)

type syntheticCallStatePayload struct {
	CallSessionID string `json:"call_session_id"`
	State         string `json:"state"`
	Sequence      int    `json:"sequence"`
}

var canonicalUUIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func parseCallStateControlResult(result control.Result, expectedState string) (string, int, error) {
	if result.Code != "ok" || containsForbiddenCallField(result.Payload) {
		return "", 0, oracleFailure("invalid_call_control")
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(result.Payload, &fields); err != nil || len(fields) != 3 {
		return "", 0, oracleFailure("invalid_call_control")
	}
	for _, key := range []string{"call_session_id", "state", "sequence"} {
		if value, ok := fields[key]; !ok || string(value) == "null" {
			return "", 0, oracleFailure("invalid_call_control")
		}
	}
	var payload syntheticCallStatePayload
	if err := json.Unmarshal(result.Payload, &payload); err != nil ||
		payload.State != expectedState || payload.Sequence <= 0 ||
		!canonicalUUIDPattern.MatchString(payload.CallSessionID) {
		return "", 0, oracleFailure("invalid_call_control")
	}
	return callStateCanonHash(payload.CallSessionID), payload.Sequence, nil
}

var forbiddenCallFields = []string{
	"phone_number", "phone", "contact", "caller", "callee", "audio", "recording", "voicemail",
}

func containsForbiddenCallField(raw json.RawMessage) bool {
	text := strings.ToLower(string(raw))
	for _, field := range forbiddenCallFields {
		if strings.Contains(text, `"`+field+`"`) {
			return true
		}
	}
	return false
}

func callStateCanonHash(sessionID string) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte("call:"+sessionID)))
}
