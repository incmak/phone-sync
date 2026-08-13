package scenario_test

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

type fakeBridge struct {
	events []string
	states map[string]scenario.Observation
}

func (f *fakeBridge) Control(_ context.Context, device, name string, _ map[string]string) (control.Result, error) {
	f.events = append(f.events, device+"."+name)
	return control.Result{}, nil
}
func (f *fakeBridge) Post(_ context.Context, device, tag, _ string) error {
	f.events = append(f.events, device+".shell.post:"+tag)
	f.states[device] = scenario.Observation{Outbox: 1, Canonical: map[string]string{"new-hash": "ACTIVE"}}
	return nil
}
func (f *fakeBridge) Cancel(_ context.Context, device, tag string) error {
	f.events = append(f.events, device+".shell.cancel:"+tag)
	return nil
}
func (f *fakeBridge) SetNetwork(_ context.Context, device string, enabled bool) error {
	f.events = append(f.events, device+".network")
	f.states[device] = scenario.Observation{Health: map[bool]string{true: "connected", false: "offline"}[enabled]}
	if device == "B" && enabled {
		f.states[device] = scenario.Observation{Health: "connected", Canonical: map[string]string{"new-hash": "ACTIVE"}, Mirror: true}
	}
	return nil
}
func (f *fakeBridge) ForceStop(_ context.Context, device string) error {
	f.events = append(f.events, device+".force-stop")
	return nil
}
func (f *fakeBridge) Reconcile(_ context.Context, device string) error {
	f.events = append(f.events, device+".reconcile")
	return nil
}
func (f *fakeBridge) Snapshot(_ context.Context, device string) (scenario.Observation, error) {
	state := f.states[device]
	state.Terminal = true
	if device == "B" {
		state.Mirror = len(state.Canonical) > 0
	}
	if device == "A" {
		state.Outbox = 0
	}
	return state, nil
}

func TestExecutorUsesRealBridgeActionsAndBoundedPredicates(t *testing.T) {
	bridge := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	err := scenario.NewExecutor(bridge, 50*time.Millisecond).Run(context.Background(), "offline")
	if err != nil {
		t.Fatal(err)
	}
	if len(bridge.events) == 0 || bridge.events[0] != "B.network" {
		t.Fatalf("events=%v", bridge.events)
	}
}

func TestExecutorRejectsUnavailableUIAutomation(t *testing.T) {
	bridge := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	err := scenario.NewExecutor(bridge, 10*time.Millisecond).Run(context.Background(), "dismiss-peer")
	if !errors.Is(err, scenario.ErrUnsupportedEnvironment) {
		t.Fatalf("error=%v", err)
	}
}

func TestOfflinePlanOrdersFaultStimulusAndConvergence(t *testing.T) {
	plan, err := scenario.Plan("offline")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"B.network.off", "B.health.offline", "A.shell.post:n1", "A.outbox.nonzero",
		"B.network.on", "B.health.connected", "B.mirror.active:n1", "A.outbox.zero",
	}
	if got := plan.Actions(); !equal(got, want) {
		t.Fatalf("actions=%v want=%v", got, want)
	}
}

func TestCorePlansHaveTerminalPredicates(t *testing.T) {
	for _, name := range []string{"post", "update", "dismiss-origin", "dismiss-peer", "rapid-post-update-cancel", "offline", "ack-loss", "sender-offline-after-acceptance", "relay-restart", "sender-kill", "receiver-kill", "reboot", "expiry-snapshot", "all-correctness"} {
		plan, err := scenario.Plan(name)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if len(plan.Steps) < 2 || plan.Steps[len(plan.Steps)-1].Predicate == "" {
			t.Fatalf("%s has no terminal predicate: %#v", name, plan.Steps)
		}
	}
}

func TestUnknownScenarioRejected(t *testing.T) {
	if _, err := scenario.Plan("not-real"); err == nil {
		t.Fatal("expected unknown scenario error")
	}
}

func TestParseObservationUsesProviderSchema(t *testing.T) {
	state, err := scenario.ParseObservation([]byte(`{"health":{"service":"connected"},"active_outbox":0,"active_inbound":0,"pending_materialization":0,"canonical":[{"canon_id_hash":"new-hash","state":"ACTIVE","sequence":3}],"activity":[{"event_type":"delivery_loop"}]}`))
	if err != nil {
		t.Fatal(err)
	}
	if state.Health != "connected" || !state.Mirror || state.Sequence != 3 || state.LoopEvents != 1 || !state.Terminal {
		t.Fatalf("state=%+v", state)
	}
}

func TestParseObservationRejectsInventedOrIncompleteSchema(t *testing.T) {
	if _, err := scenario.ParseObservation([]byte(`{"status":"connected"}`)); err == nil {
		t.Fatal("expected missing provider health error")
	}
}

func TestEventuallyPollsAtBoundedInterval(t *testing.T) {
	checks := 0
	err := scenario.Eventually(context.Background(), time.Millisecond, 100*time.Millisecond, func() (bool, error) {
		checks++
		return checks >= 3, nil
	})
	if err != nil || checks < 3 {
		t.Fatalf("err=%v checks=%d", err, checks)
	}
}

func TestFailureArtifactRedactsSecretsAndWritesTimeline(t *testing.T) {
	dir := t.TempDir()
	path, err := scenario.WriteFailureArtifact(dir, "offline/bad", scenario.FailureArtifact{
		RelayLog: "token=secret-token ciphertext=abc nonce=def\n  android.title=Private title\n  text=Private body\n  extras={key=value}",
		Timeline: []string{"B.network.off", "A.shell.post:n1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(path, "relay.log"))
	if err != nil {
		t.Fatal(err)
	}
	if string(content) == "" || strings.Contains(string(content), "Private title") || strings.Contains(string(content), "Private body") || strings.Contains(string(content), "key=value") {
		t.Fatalf("artifact was not sanitized: %q", content)
	}
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
