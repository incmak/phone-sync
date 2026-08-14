package scenario_test

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

func TestSyntheticCallStateReturnsUnsupportedWhenDebugSurfaceUnavailable(t *testing.T) {
	bridge := &callStateBridge{captureCode: "forbidden"}
	_, err := scenario.RunSyntheticCallState(context.Background(), bridge, 20*time.Millisecond)
	if !errors.Is(err, scenario.ErrUnsupportedEnvironment) {
		t.Fatalf("error=%v, want ErrUnsupportedEnvironment", err)
	}
}

func TestSyntheticCallStateConvergesWithoutPhoneFields(t *testing.T) {
	bridge := &callStateBridge{captureCode: "ok", health: "connected"}
	result, err := scenario.RunSyntheticCallState(context.Background(), bridge, 100*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if !result.Synthetic || result.SessionID == "" || len(result.States) != 3 {
		t.Fatalf("result=%+v", result)
	}
	if bridge.forbiddenAccepted {
		t.Fatal("forbidden phone field was accepted")
	}
}

type callStateBridge struct {
	captureCode       string
	health            string
	session           string
	sequence          int
	canonical         map[string]string
	forbiddenAccepted bool
}

func (b *callStateBridge) Control(_ context.Context, _ string, name string, params map[string]string) (control.Result, error) {
	switch name {
	case "CALL_CAPTURE_ENABLE":
		return control.Result{Code: b.captureCode}, nil
	case "CALL_STATE":
		if params["phone_number"] != "" {
			return control.Result{Code: "invalid", Detail: "phone_number is not allowed"}, nil
		}
		b.session = "11111111-1111-4111-8111-111111111111"
		b.sequence++
		state := params["state"]
		if b.canonical == nil {
			b.canonical = map[string]string{}
		}
		if state == "idle" {
			b.canonical[callStateCanonHash(b.session)] = "CANCELLED"
		} else {
			b.canonical[callStateCanonHash(b.session)] = "ACTIVE"
		}
		payload, _ := json.Marshal(map[string]any{"call_session_id": b.session, "state": state, "sequence": b.sequence})
		return control.Result{Code: "ok", Payload: payload}, nil
	case "STATUS":
		return control.Result{Code: "ok"}, nil
	default:
		return control.Result{Code: "ok"}, nil
	}
}

func (b *callStateBridge) Post(context.Context, string, string, string) error { return nil }
func (b *callStateBridge) Cancel(context.Context, string, string) error       { return nil }
func (b *callStateBridge) SetNetwork(context.Context, string, bool) error     { return nil }
func (b *callStateBridge) ForceStop(context.Context, string) error            { return nil }
func (b *callStateBridge) Reconcile(context.Context, string) error            { return nil }
func (b *callStateBridge) Snapshot(_ context.Context, device string) (scenario.Observation, error) {
	canonical := map[string]string{}
	for key, value := range b.canonical {
		canonical[key] = value
	}
	return scenario.Observation{
		Health: b.health, Outbox: 0, ActiveInbound: 0, PendingMaterialization: 0,
		Canonical: canonical, CanonicalSequences: map[string]int{callStateCanonHash(b.session): b.sequence},
		CallCaptureEnabled: device == "A" && b.captureCode == "ok",
		Terminal:           b.health == "connected",
	}, nil
}

func callStateCanonHash(session string) string {
	// The implementation under test is expected to use SHA-256 over call:<UUID>.
	return fmt.Sprintf("%x", sha256.Sum256([]byte("call:"+session)))
}
