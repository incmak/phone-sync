package scenario

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
)

type claimPauseCleanupBridge struct {
	controls []string
	network  []string
}

func (b *claimPauseCleanupBridge) Control(_ context.Context, _ string, name string, params map[string]string) (control.Result, error) {
	operation := params["operation"]
	b.controls = append(b.controls, name+":"+operation)
	if name == "NOTIFICATION_FIXTURE" {
		return control.Result{}, errors.New("injected fixture failure")
	}
	return control.Result{Code: "ok"}, nil
}

func (*claimPauseCleanupBridge) Post(context.Context, string, string, string) error { return nil }
func (*claimPauseCleanupBridge) Cancel(context.Context, string, string) error       { return nil }
func (b *claimPauseCleanupBridge) SetNetwork(_ context.Context, device string, enabled bool) error {
	b.network = append(b.network, device+map[bool]string{false: ":off", true: ":on"}[enabled])
	return nil
}
func (*claimPauseCleanupBridge) ForceStop(context.Context, string) error { return nil }
func (*claimPauseCleanupBridge) Restart(context.Context, string) error   { return nil }
func (*claimPauseCleanupBridge) Reconcile(context.Context, string) error { return nil }
func (*claimPauseCleanupBridge) Snapshot(context.Context, string) (Observation, error) {
	return Observation{}, nil
}

func TestInterruptedActionScenarioReleasesOriginClaimPause(t *testing.T) {
	bridge := &claimPauseCleanupBridge{}
	plan := ScenarioPlan{Name: "claim-pause-cleanup", Steps: []Step{
		{Action: "A.origin:pause_after_claim"},
		{Action: "A.fixture:reply:post"},
	}}

	_, err := NewExecutor(bridge, 50*time.Millisecond).runPlan(context.Background(), plan)
	if err == nil {
		t.Fatal("expected injected scenario failure")
	}
	want := []string{
		"NOTIFICATION_ORIGIN:pause_after_claim",
		"NOTIFICATION_FIXTURE:post",
		"NOTIFICATION_ORIGIN:release_claim_pause",
	}
	if len(bridge.controls) != len(want) {
		t.Fatalf("controls=%v want=%v", bridge.controls, want)
	}
	for index := range want {
		if bridge.controls[index] != want[index] {
			t.Fatalf("controls=%v want=%v", bridge.controls, want)
		}
	}
}

func TestInterruptedScenarioRestoresEveryDisabledNetwork(t *testing.T) {
	bridge := &claimPauseCleanupBridge{}
	plan := ScenarioPlan{Name: "network-cleanup", Steps: []Step{
		{Action: "A.network.off"},
		{Action: "A.fixture:reply:post"},
	}}

	_, err := NewExecutor(bridge, 50*time.Millisecond).runPlan(context.Background(), plan)
	if err == nil {
		t.Fatal("expected injected scenario failure")
	}
	want := []string{"A:off", "A:on"}
	if len(bridge.network) != len(want) {
		t.Fatalf("network operations=%v want=%v", bridge.network, want)
	}
	for index := range want {
		if bridge.network[index] != want[index] {
			t.Fatalf("network operations=%v want=%v", bridge.network, want)
		}
	}
}
