package scenario

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/twinotify/phone-sync/e2e/internal/control"
	"strings"
	"time"
)

// SyntheticCallStateResult is deliberately content-free. It records only the debug-generated
// session identifier and state sequence; it is not physical-call evidence.
type SyntheticCallStateResult struct {
	Synthetic bool
	SessionID string
	States    []string
}

type syntheticCallStatePayload struct {
	CallSessionID string `json:"call_session_id"`
	State         string `json:"state"`
	Sequence      int    `json:"sequence"`
}

var forbiddenCallFields = []string{
	"phone_number", "phone", "contact", "caller", "callee", "audio", "recording", "voicemail",
}

// RunSyntheticCallState drives the authenticated debug-only CALL_STATE surface. It returns
// ErrUnsupportedEnvironment when the installed debug build/control command is unavailable and
// never treats that outcome as a physical call pass.
func RunSyntheticCallState(ctx context.Context, bridge Bridge, timeout time.Duration) (SyntheticCallStateResult, error) {
	if bridge == nil || timeout <= 0 {
		return SyntheticCallStateResult{}, errors.New("call-state bridge and positive timeout are required")
	}
	enable, err := bridge.Control(ctx, "A", "CALL_CAPTURE_ENABLE", nil)
	if err != nil {
		return SyntheticCallStateResult{}, classifyCallStateError(err)
	}
	if enable.Code != "ok" {
		return SyntheticCallStateResult{}, classifyCallStateResult(enable)
	}
	if err := Eventually(ctx, 100*time.Millisecond, timeout, func() (bool, error) {
		state, err := bridge.Snapshot(ctx, "A")
		if err != nil {
			return false, err
		}
		return state.CallCaptureEnabled, nil
	}); err != nil {
		return SyntheticCallStateResult{}, classifyCallStateError(err)
	}

	// The negative probe is part of the contract: debug control accepts only the privacy-bounded
	// state fields and must reject phone/contact/audio data before any event is persisted.
	negative, err := bridge.Control(ctx, "A", "CALL_STATE", map[string]string{
		"state": "ringing", "phone_number": "+15551234567",
	})
	if err != nil {
		return SyntheticCallStateResult{}, classifyCallStateError(err)
	}
	if negative.Code != "invalid" && negative.Code != "forbidden" {
		return SyntheticCallStateResult{}, fmt.Errorf("CALL_STATE accepted forbidden phone field (code=%s)", negative.Code)
	}
	if containsForbiddenCallField(negative.Payload) {
		return SyntheticCallStateResult{}, errors.New("CALL_STATE negative response exposed forbidden phone field")
	}

	result := SyntheticCallStateResult{Synthetic: true}
	var sessionID string
	for _, state := range []string{"ringing", "active", "idle"} {
		response, err := bridge.Control(ctx, "A", "CALL_STATE", map[string]string{"state": state})
		if err != nil {
			return SyntheticCallStateResult{}, classifyCallStateError(err)
		}
		if response.Code != "ok" {
			return SyntheticCallStateResult{}, classifyCallStateResult(response)
		}
		if containsForbiddenCallField(response.Payload) {
			return SyntheticCallStateResult{}, errors.New("CALL_STATE response exposed forbidden phone field")
		}
		var payload syntheticCallStatePayload
		if err := json.Unmarshal(response.Payload, &payload); err != nil {
			return SyntheticCallStateResult{}, fmt.Errorf("decode CALL_STATE result: %w", err)
		}
		if payload.State != state || payload.CallSessionID == "" || payload.Sequence <= 0 {
			return SyntheticCallStateResult{}, fmt.Errorf("invalid CALL_STATE result: %+v", payload)
		}
		if sessionID == "" {
			sessionID = payload.CallSessionID
		} else if sessionID != payload.CallSessionID {
			return SyntheticCallStateResult{}, errors.New("CALL_STATE changed session identifier")
		}
		if len(result.States) > 0 && payload.Sequence != len(result.States)+1 {
			return SyntheticCallStateResult{}, fmt.Errorf("CALL_STATE sequence gap: got %d", payload.Sequence)
		}
		canonHash := callStateCanonHash(sessionID)
		expectedMirrorState := "ACTIVE"
		if state == "idle" {
			expectedMirrorState = "CANCELLED"
		}
		if err := Eventually(ctx, 100*time.Millisecond, timeout, func() (bool, error) {
			observation, err := bridge.Snapshot(ctx, "B")
			if err != nil {
				return false, err
			}
			return observation.Health == "connected" &&
				observation.ActiveInbound == 0 &&
				observation.PendingMaterialization == 0 &&
				observation.Canonical[canonHash] == expectedMirrorState &&
				observation.CanonicalSequences[canonHash] == payload.Sequence, nil
		}); err != nil {
			return SyntheticCallStateResult{}, fmt.Errorf("wait call state %s convergence: %w", state, classifyCallStateError(err))
		}
		result.SessionID = sessionID
		result.States = append(result.States, state)
	}
	return result, nil
}

func classifyCallStateResult(result control.Result) error {
	if result.Code == "forbidden" || result.Code == "unsupported" || result.Code == "unavailable" {
		return ErrUnsupportedEnvironment
	}
	if result.Detail == "" {
		return fmt.Errorf("call-state control failed: %s", result.Code)
	}
	return fmt.Errorf("call-state control failed: %s: %s", result.Code, result.Detail)
}

func classifyCallStateError(err error) error {
	if errors.Is(err, control.ErrDeviceOffline) || errors.Is(err, control.ErrTimeout) {
		return ErrUnsupportedEnvironment
	}
	return err
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
