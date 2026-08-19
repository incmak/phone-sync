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
	events        []string
	states        map[string]scenario.Observation
	calls         int
	pendingOutbox bool
	sequences     map[string]int
}

type terminalSnapshotFailureBridge struct {
	*fakeBridge
	snapshots int
}

func (f *terminalSnapshotFailureBridge) Snapshot(ctx context.Context, device string) (scenario.Observation, error) {
	f.snapshots++
	if f.snapshots >= 10 && device == "B" {
		return scenario.Observation{}, errors.New("terminal snapshot unavailable")
	}
	return f.fakeBridge.Snapshot(ctx, device)
}

func (f *fakeBridge) Control(_ context.Context, device, name string, _ map[string]string) (control.Result, error) {
	f.calls++
	f.events = append(f.events, device+"."+name)
	return control.Result{}, nil
}
func (f *fakeBridge) Post(_ context.Context, device, tag, text string) error {
	f.calls++
	f.events = append(f.events, device+".shell.post:"+tag+":"+text)
	f.pendingOutbox = true
	if f.sequences == nil {
		f.sequences = map[string]int{}
	}
	f.sequences[tag]++
	stateA := f.states[device]
	if stateA.Canonical == nil {
		stateA.Canonical = map[string]string{}
	}
	stateA.Outbox = 1
	stateA.Canonical[tag] = "ACTIVE"
	f.states[device] = stateA
	if device == "A" {
		stateB := f.states["B"]
		if stateB.Canonical == nil {
			stateB.Canonical = map[string]string{}
		}
		stateB.Canonical[tag] = "ACTIVE"
		stateB.Mirror = true
		stateB.Sequence = highestSequence(f.sequences)
		f.states["B"] = stateB
	}
	return nil
}
func (f *fakeBridge) Cancel(_ context.Context, device, tag string) error {
	f.calls++
	f.events = append(f.events, device+".shell.cancel:"+tag)
	f.pendingOutbox = true
	stateB := f.states["B"]
	delete(stateB.Canonical, tag)
	stateB.Mirror = len(stateB.Canonical) != 0
	f.states["B"] = stateB
	return nil
}
func (f *fakeBridge) SetNetwork(_ context.Context, device string, enabled bool) error {
	f.calls++
	f.events = append(f.events, device+".network")
	state := f.states[device]
	state.Health = map[bool]string{true: "connected", false: "offline"}[enabled]
	f.states[device] = state
	if device == "B" && enabled {
		state = f.states[device]
		state.Mirror = len(state.Canonical) != 0
		state.Sequence = highestSequence(f.sequences)
		f.states[device] = state
	}
	return nil
}

func highestSequence(values map[string]int) int {
	result := 0
	for _, value := range values {
		if value > result {
			result = value
		}
	}
	return result
}
func (f *fakeBridge) ForceStop(_ context.Context, device string) error {
	f.calls++
	f.events = append(f.events, device+".force-stop")
	return nil
}
func (f *fakeBridge) Reconcile(_ context.Context, device string) error {
	f.calls++
	f.events = append(f.events, device+".reconcile")
	return nil
}
func (f *fakeBridge) Snapshot(_ context.Context, device string) (scenario.Observation, error) {
	f.calls++
	state := f.states[device]
	state.Terminal = true
	if device == "B" {
		state.Mirror = len(state.Canonical) > 0
	}
	if device == "A" {
		if f.pendingOutbox {
			state.Outbox = 1
			f.pendingOutbox = false
		} else {
			state.Outbox = 0
		}
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
	if bridge.calls != 0 {
		t.Fatalf("unsupported scenario touched bridge %d times", bridge.calls)
	}
}

func TestUpdatePlanPostsThreePayloadsToTheSameTag(t *testing.T) {
	plan, err := scenario.Plan("update")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"A.shell.post:n1:v1", "A.shell.post:n1:v2", "A.shell.post:n1:v3"}
	if got := plan.Actions()[:3]; !equal(got, want) {
		t.Fatalf("actions=%v want=%v", got, want)
	}
	bridge := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	if err := scenario.NewExecutor(bridge, 50*time.Millisecond).Run(context.Background(), "update"); err != nil {
		t.Fatal(err)
	}
	if got, want := bridge.events[:3], []string{"A.shell.post:n1:v1", "A.shell.post:n1:v2", "A.shell.post:n1:v3"}; !equal(got, want) {
		t.Fatalf("bridge posts=%v want=%v", got, want)
	}
}

func TestExecutablePlanRejectsAssertionLikeAction(t *testing.T) {
	err := scenario.ValidateExecutablePlan(scenario.ScenarioPlan{
		Name:  "invented",
		Steps: []scenario.Step{{Action: "A.outbox.nonzero"}},
	})
	if err == nil {
		t.Fatal("assertion-like action was accepted")
	}
}

func TestCoreCorrectnessContainsOnlyExecutableActions(t *testing.T) {
	plan, err := scenario.Plan("core-correctness")
	if err != nil {
		t.Fatal(err)
	}
	if err := scenario.ValidateExecutablePlan(plan); err != nil {
		t.Fatalf("core-correctness should be executable: %v", err)
	}
	all, err := scenario.Plan("all-correctness")
	if err != nil {
		t.Fatal(err)
	}
	if err := scenario.ValidateExecutablePlan(all); !errors.Is(err, scenario.ErrUnsupportedEnvironment) {
		t.Fatalf("all-correctness error=%v", err)
	}
}

func TestCoreCorrectnessRunsIndependentTaggedScenarios(t *testing.T) {
	bridge := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	result, err := scenario.NewExecutor(bridge, 50*time.Millisecond).RunResult(context.Background(), "core-correctness")
	if err != nil || result.Status != "passed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if !contains(bridge.events, "A.shell.post:n-core-update:v1") {
		t.Fatalf("core scenario did not isolate update tag: %v", bridge.events)
	}
}

func TestResultEvidenceIsDerivedFromObservedSnapshots(t *testing.T) {
	bridge := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	result, err := scenario.NewExecutor(bridge, 50*time.Millisecond).RunResult(context.Background(), "offline")
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "passed" || result.After["B"].Health != "connected" || len(result.Events) == 0 {
		t.Fatalf("result=%+v", result)
	}
	dir := t.TempDir()
	if err := scenario.WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	result.After["B"] = scenario.Observation{Health: "offline", Outbox: 9}
	if err := scenario.WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	after, err := os.ReadFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	if string(before) == string(after) || !strings.Contains(string(after), `"offline"`) || !strings.Contains(string(after), `"outbox":9`) {
		t.Fatalf("state evidence did not reflect observation: %s", after)
	}
}

func TestUnsupportedScenarioWritesFailedEvidenceOnly(t *testing.T) {
	bridge := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	result, err := scenario.NewExecutor(bridge, time.Second).RunResult(context.Background(), "dismiss-peer")
	if !errors.Is(err, scenario.ErrUnsupportedEnvironment) || result.Status != "failed" || result.ErrorCode != "unsupported_environment" || len(result.Events) != 0 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	dir := t.TempDir()
	if err := scenario.WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	state, err := os.ReadFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(state), `"passed"`) || !strings.Contains(string(state), `"failed"`) {
		t.Fatalf("unsupported scenario evidence=%s", state)
	}
}

func TestTerminalSnapshotFailureNeverProducesPassedEvidence(t *testing.T) {
	bridge := &terminalSnapshotFailureBridge{fakeBridge: &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}}
	result, err := scenario.NewExecutor(bridge, 50*time.Millisecond).RunResult(context.Background(), "post")
	if err == nil || result.Status != "failed" || result.ErrorCode != "execution_failed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	dir := t.TempDir()
	if err := scenario.WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	state, err := os.ReadFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(state), `"passed"`) || !strings.Contains(string(state), `"execution_failed"`) {
		t.Fatalf("terminal snapshot failure evidence=%s", state)
	}
}

func TestUnknownScenarioReturnsBoundedInvalidScenarioCode(t *testing.T) {
	bridge := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	result, err := scenario.NewExecutor(bridge, time.Second).RunResult(context.Background(), "not-real")
	if err == nil || result.ErrorCode != "invalid_scenario" || bridge.calls != 0 {
		t.Fatalf("result=%+v err=%v calls=%d", result, err, bridge.calls)
	}
}

func TestOfflinePlanOrdersFaultStimulusAndConvergence(t *testing.T) {
	plan, err := scenario.Plan("offline")
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"B.network.off", "A.shell.post:n1", "B.network.on",
	}
	if got := plan.Actions(); !equal(got, want) {
		t.Fatalf("actions=%v want=%v", got, want)
	}
	for _, predicate := range []string{"B.health.offline", "A.outbox.nonzero", "B.health.connected", "B.mirror.active:n1", "A.outbox.zero", "terminal.converged"} {
		found := false
		for _, step := range plan.Steps {
			found = found || step.Predicate == predicate
		}
		if !found {
			t.Fatalf("missing observable predicate %q", predicate)
		}
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

func contains(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
