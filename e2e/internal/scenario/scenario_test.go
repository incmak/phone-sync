package scenario_test

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

func TestParseObservationRejectsMissingUnknownNullAndMalformedProductObservations(t *testing.T) {
	base := validObservationPayloadForTest()
	mutations := map[string]func(map[string]any){
		"missing":                   func(v map[string]any) { delete(v["product_observations"].(map[string]any), "paired") },
		"unknown":                   func(v map[string]any) { v["product_observations"].(map[string]any)["peer_id"] = "hidden" },
		"null":                      func(v map[string]any) { v["product_observations"].(map[string]any)["peer_receipt_count"] = nil },
		"malformed":                 func(v map[string]any) { v["product_observations"].(map[string]any)["active_queue_count"] = -1 },
		"malformed snapshot commit": func(v map[string]any) { v["product_observations"].(map[string]any)["snapshot_commit_count"] = -1 },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			value := cloneJSONMap(t, base)
			mutate(value)
			payload, err := json.Marshal(value)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := scenario.ParseObservation(payload); err == nil {
				t.Fatal("malformed observation passed")
			}
		})
	}
}

func TestParseObservationReadsClosedWorldCallSemanticState(t *testing.T) {
	payload := validObservationPayloadForTest()
	hash := strings.Repeat("a", 64)
	payload["canonical"] = []any{map[string]any{
		"canon_id_hash": hash, "state": "ACTIVE", "sequence": 1.0,
		"materialized_sequence": 1.0, "semantic_state": "RINGING",
	}}
	encoded, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	state, err := scenario.ParseObservation(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if state.CanonicalSemanticStates[hash] != "RINGING" {
		t.Fatalf("semantic states=%v", state.CanonicalSemanticStates)
	}
}

func TestParseObservationRejectsMalformedCallSemanticState(t *testing.T) {
	base := validObservationPayloadForTest()
	hash := strings.Repeat("a", 64)
	mutations := map[string]map[string]any{
		"unknown semantic":          {"canon_id_hash": hash, "state": "ACTIVE", "sequence": 1.0, "materialized_sequence": 1.0, "semantic_state": "DIALING"},
		"semantic durable mismatch": {"canon_id_hash": hash, "state": "CANCELLED", "sequence": 1.0, "materialized_sequence": 1.0, "semantic_state": "ACTIVE"},
		"unknown field":             {"canon_id_hash": hash, "state": "ACTIVE", "sequence": 1.0, "materialized_sequence": 1.0, "semantic_state": "ACTIVE", "title": "forbidden"},
		"invalid hash":              {"canon_id_hash": "raw-call-id", "state": "ACTIVE", "sequence": 1.0, "materialized_sequence": 1.0, "semantic_state": "ACTIVE"},
	}
	for name, canonical := range mutations {
		t.Run(name, func(t *testing.T) {
			payload := cloneJSONMap(t, base)
			payload["canonical"] = []any{canonical}
			encoded, err := json.Marshal(payload)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := scenario.ParseObservation(encoded); err == nil {
				t.Fatal("malformed semantic state passed")
			}
		})
	}
}

func validObservationPayloadForTest() map[string]any {
	counts := map[string]any{"notif_post": 0.0, "notif_update": 0.0, "notif_cancel": 0.0, "call_state": 0.0, "state_digest": 0.0, "state_snapshot_begin": 0.0, "state_snapshot_item": 0.0, "state_snapshot_end": 0.0, "unpair": 0.0, "peer_receipt": 0.0}
	return map[string]any{
		"offline_pairing": map[string]any{},
		"health":          map[string]any{"service": "connected", "callCaptureEnabled": false, "callCaptureHealthCode": "call_capture_disabled"},
		"route":           map[string]any{"route": "lan", "phase": "authenticated"},
		"route_evidence":  map[string]any{"route": "lan", "phase": "authenticated", "route_generation": 1.0, "queued_count": 0.0, "queued_bytes": 0.0, "receipt_at_ms": 0.0, "error_code": ""},
		"outbox_bytes":    0.0, "active_outbox": 0.0, "active_inbound": 0.0, "pending_materialization": 0.0,
		"canonical": []any{}, "activity": []any{},
		"notification_action_fixture": map[string]any{
			"available": true, "reply_dispatch_count": 0.0, "mark_read_dispatch_count": 0.0,
			"last_fixture_generation": 0.0, "last_terminal_status": "none",
		},
		"notification_action_observations": map[string]any{
			"invocation_pending": 0.0, "invocation_dispatched": 0.0, "invocation_outcome_unknown": 0.0,
			"invocation_failed": 0.0, "invocation_action_gone": 0.0, "invocation_notification_gone": 0.0,
			"invocation_expired": 0.0, "execution_claimed": 0.0, "execution_completed": 0.0,
			"detail_active": 0.0, "detail_cancelled": 0.0, "latest_terminal_status": "none",
		},
		"product_observations": map[string]any{
			"paired":             true,
			"custody_counts":     map[string]any{"lan": counts, "relay": cloneMap(counts)},
			"peer_receipt_count": 0.0, "snapshot_digest_count": 0.0, "snapshot_begin_count": 0.0,
			"snapshot_end_count": 0.0, "snapshot_commit_count": 0.0, "user_dismiss_count": 0.0, "unpair_inbound_count": 0.0,
			"unpair_outcome": "none", "active_queue_count": 0.0, "active_queue_bytes": 0.0,
			"peak_queue_count": 0.0, "peak_queue_bytes": 0.0,
		},
	}
}

func TestParseObservationReadsContentFreeNotificationActionEvidence(t *testing.T) {
	payload := validObservationPayloadForTest()
	payload["notification_action_fixture"].(map[string]any)["reply_dispatch_count"] = 2.0
	payload["notification_action_observations"].(map[string]any)["invocation_dispatched"] = 3.0
	encoded, _ := json.Marshal(payload)
	state, err := scenario.ParseObservation(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if state.FixtureReplyCount != 2 || state.ActionInvocationCounts["DISPATCHED"] != 3 {
		t.Fatalf("state=%+v", state)
	}
	for _, forbidden := range []string{"reply_text", "title", "text", "canon_id", "action_id"} {
		broken := cloneJSONMap(t, payload)
		broken["notification_action_observations"].(map[string]any)[forbidden] = "secret"
		raw, _ := json.Marshal(broken)
		if _, err := scenario.ParseObservation(raw); err == nil {
			t.Fatalf("accepted forbidden notification field %s", forbidden)
		}
	}
}

func cloneJSONMap(t *testing.T, value map[string]any) map[string]any {
	t.Helper()
	payload, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var clone map[string]any
	if err := json.Unmarshal(payload, &clone); err != nil {
		t.Fatal(err)
	}
	return clone
}

func cloneMap(value map[string]any) map[string]any {
	clone := make(map[string]any, len(value))
	for key, item := range value {
		clone[key] = item
	}
	return clone
}

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

type blockingTerminalSnapshotBridge struct {
	*fakeBridge
	snapshots int
	blocked   map[string]bool
}

func (b *blockingTerminalSnapshotBridge) Snapshot(ctx context.Context, device string) (scenario.Observation, error) {
	b.snapshots++
	if b.snapshots >= 10 {
		b.blocked[device] = true
		<-ctx.Done()
		return scenario.Observation{}, ctx.Err()
	}
	return b.fakeBridge.Snapshot(ctx, device)
}

type faultFailureBridge struct{ *fakeBridge }

type routeAfterPostBridge struct {
	*fakeBridge
	setLanAfterPost bool
}

func (b *routeAfterPostBridge) Post(ctx context.Context, device, tag, text string) error {
	if err := b.fakeBridge.Post(ctx, device, tag, text); err != nil {
		return err
	}
	if b.setLanAfterPost {
		for _, peer := range []string{"A", "B"} {
			state := b.states[peer]
			state.Route = "lan"
			state.RoutePhase = "authenticated"
			b.states[peer] = state
		}
	}
	return nil
}

type blockingRestoreBridge struct {
	*fakeBridge
	restoreAttempts map[string]bool
}

func (b *blockingRestoreBridge) Control(ctx context.Context, device, name string, params map[string]string) (control.Result, error) {
	if name == "SET_LAN_AVAILABLE" && params["available"] == "true" {
		b.restoreAttempts[device] = true
		<-ctx.Done()
		return control.Result{}, ctx.Err()
	}
	return b.fakeBridge.Control(ctx, device, name, params)
}

func (b *blockingRestoreBridge) Post(context.Context, string, string, string) error {
	return errors.New("delivery interrupted")
}

func (f *faultFailureBridge) Control(ctx context.Context, device, name string, params map[string]string) (control.Result, error) {
	if device == "B" && name == "SET_LAN_AVAILABLE" && params["available"] == "false" {
		_, _ = f.fakeBridge.Control(ctx, device, name, params)
		return control.Result{}, errors.New("fault injection interrupted")
	}
	return f.fakeBridge.Control(ctx, device, name, params)
}

func (f *terminalSnapshotFailureBridge) Snapshot(ctx context.Context, device string) (scenario.Observation, error) {
	f.snapshots++
	if f.snapshots >= 10 && device == "B" {
		return scenario.Observation{}, errors.New("terminal snapshot unavailable")
	}
	return f.fakeBridge.Snapshot(ctx, device)
}

func (f *fakeBridge) Control(_ context.Context, device, name string, params map[string]string) (control.Result, error) {
	f.calls++
	f.events = append(f.events, device+"."+name)
	if name == "SET_LAN_AVAILABLE" {
		state := f.states[device]
		if params["available"] == "true" {
			state.Route = "lan"
		} else {
			state.Route = "relay"
		}
		state.RoutePhase = "authenticated"
		state.RouteGeneration++
		f.states[device] = state
	}
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
	stateA.ReceiptAtMs++
	stateA.Canonical[tag] = "ACTIVE"
	if stateA.CanonicalSequences == nil {
		stateA.CanonicalSequences = map[string]int{}
	}
	stateA.CanonicalSequences[tag] = f.sequences[tag]
	if stateA.CanonicalMaterializedSequences == nil {
		stateA.CanonicalMaterializedSequences = map[string]int{}
	}
	stateA.CanonicalMaterializedSequences[tag] = f.sequences[tag]
	if stateA.CustodyCounts == nil {
		stateA.CustodyCounts = completeCustodyCounts()
	}
	event := "notif_post"
	if f.sequences[tag] > 1 {
		event = "notif_update"
	}
	if stateA.Route == "lan" || stateA.Route == "relay" {
		stateA.CustodyCounts[stateA.Route][event]++
	}
	stateA.PeerReceiptCount++
	f.states[device] = stateA
	recipient := map[string]string{"A": "B", "B": "A"}[device]
	if recipient != "" {
		stateB := f.states[recipient]
		if stateB.Canonical == nil {
			stateB.Canonical = map[string]string{}
		}
		if stateB.CanonicalSequences == nil {
			stateB.CanonicalSequences = map[string]int{}
		}
		if stateB.CanonicalMaterializedSequences == nil {
			stateB.CanonicalMaterializedSequences = map[string]int{}
		}
		stateB.Canonical[tag] = "ACTIVE"
		stateB.CanonicalSequences[tag] = f.sequences[tag]
		stateB.CanonicalMaterializedSequences[tag] = f.sequences[tag]
		stateB.Mirror = true
		stateB.Sequence = highestSequence(f.sequences)
		f.states[recipient] = stateB
	}
	return nil
}

func completeCustodyCounts() map[string]map[string]int64 {
	result := map[string]map[string]int64{"lan": {}, "relay": {}}
	for route := range result {
		for _, event := range []string{"notif_post", "notif_update", "notif_cancel", "call_state", "state_digest", "state_snapshot_begin", "state_snapshot_item", "state_snapshot_end", "unpair", "peer_receipt"} {
			result[route][event] = 0
		}
	}
	return result
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
func (f *fakeBridge) Restart(_ context.Context, device string) error {
	f.calls++
	f.events = append(f.events, device+".restart")
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
	if state.Health == "" {
		state.Health = "stopped"
	}
	if state.Route == "" {
		state.Route, state.RoutePhase = "none", "idle"
	}
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

func TestExecutablePlanAcceptsOnlyPairedForceStopRestartSequences(t *testing.T) {
	for _, device := range []string{"A", "B"} {
		plan := scenario.ScenarioPlan{Name: "restart", Steps: []scenario.Step{{Action: device + ".force-stop"}, {Action: device + ".restart"}}}
		if err := scenario.ValidateExecutablePlan(plan); err != nil {
			t.Fatalf("%s valid restart: %v", device, err)
		}
		for name, steps := range map[string][]scenario.Step{
			"restart-before-force-stop": {{Action: device + ".restart"}, {Action: device + ".force-stop"}},
			"duplicate-restart":         {{Action: device + ".force-stop"}, {Action: device + ".restart"}, {Action: device + ".restart"}},
		} {
			t.Run(device+"/"+name, func(t *testing.T) {
				if err := scenario.ValidateExecutablePlan(scenario.ScenarioPlan{Name: name, Steps: steps}); err == nil {
					t.Fatal("unordered restart passed preflight")
				}
			})
		}
	}
	for _, token := range []string{"B.reboot", "relay.sigterm", "relay.restart.same-db", "B.ui.xml.find-mirror:n1"} {
		if err := scenario.ValidateExecutablePlan(scenario.ScenarioPlan{Name: "unsupported", Steps: []scenario.Step{{Action: token}}}); !errors.Is(err, scenario.ErrUnsupportedEnvironment) {
			t.Fatalf("%s error=%v", token, err)
		}
	}
}

type blockingRestartBridge struct{ *fakeBridge }

func (b *blockingRestartBridge) Restart(ctx context.Context, _ string) error {
	<-ctx.Done()
	return ctx.Err()
}

func TestRestartActionTimeoutIsBounded(t *testing.T) {
	lan := scenario.Observation{Health: "connected", Route: "lan", RoutePhase: "authenticated", Terminal: true}
	bridge := &blockingRestartBridge{fakeBridge: &fakeBridge{states: map[string]scenario.Observation{"A": lan, "B": lan}}}
	executor := scenario.NewExecutor(bridge, 20*time.Millisecond)
	started := time.Now()
	result, err := executor.RunResult(context.Background(), "lan-restart-persistence")
	if err == nil || result.ErrorCode != "execution_failed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if elapsed := time.Since(started); elapsed > 270*time.Millisecond {
		t.Fatalf("restart action exceeded bound: %s", elapsed)
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

func TestLanDirectBurstCountIsBounded(t *testing.T) {
	for _, count := range []int{2, 256, 1000} {
		plan, err := scenario.PlanWithBurstCount("lan-direct-burst-backpressure", count)
		if err != nil {
			t.Fatalf("count %d: %v", count, err)
		}
		posts := 0
		for _, action := range plan.Actions() {
			if strings.HasPrefix(action, "A.shell.post:") {
				posts++
			}
		}
		if posts != count {
			t.Fatalf("count %d produced %d posts", count, posts)
		}
	}
	for _, count := range []int{-1, 0, 1, 1001, 2000} {
		if _, err := scenario.PlanWithBurstCount("lan-direct-burst-backpressure", count); err == nil {
			t.Fatalf("count %d passed", count)
		}
	}
}

func TestLanProductCorrectnessHasUniqueChildrenAndUnpairLast(t *testing.T) {
	plan, err := scenario.PlanWithBurstCount("lan-product-correctness", 8)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"lan-direct-delivery", "lan-direct-reverse-delivery", "lan-direct-dismiss", "lan-direct-update",
		"lan-direct-peer-dismiss", "lan-direct-call-state", "lan-direct-snapshot-receipt",
		"lan-relay-fallback-return", "lan-restart-persistence",
		"lan-direct-burst-backpressure", "lan-direct-unpair-during-traffic",
	}
	if len(plan.Children) != len(want) {
		t.Fatalf("children=%v", plan.Children)
	}
	seenNames, seenTags := map[string]bool{}, map[string]string{}
	for index, child := range plan.Children {
		if child.Name != want[index] || seenNames[child.Name] {
			t.Fatalf("child %d=%q seen=%v", index, child.Name, seenNames)
		}
		seenNames[child.Name] = true
		for _, action := range child.Actions() {
			if !strings.HasPrefix(action, "A.shell.post:") && !strings.HasPrefix(action, "B.shell.post:") {
				continue
			}
			tag := strings.Split(action, ":")[1]
			if owner := seenTags[tag]; owner != "" && owner != child.Name {
				t.Fatalf("tag %q reused by %s and %s", tag, owner, child.Name)
			}
			seenTags[tag] = child.Name
		}
	}
}

func TestLanProductNewScenarioPlansCarryRequiredDirectionalRouteAndRestartProof(t *testing.T) {
	planText := func(plan scenario.ScenarioPlan) string {
		var values []string
		for _, step := range plan.Steps {
			values = append(values, step.Action, step.Predicate)
		}
		return strings.Join(values, "|")
	}
	reverse, err := scenario.Plan("lan-direct-reverse-delivery")
	if err != nil {
		t.Fatal(err)
	}
	reverseActions := planText(reverse)
	for _, required := range []string{"B.shell.post:n1", "A.tracked.sequence:1", "B.custody.lan:notif_post:1", "B.peer-receipt.delta:1", "direct.terminal"} {
		if !strings.Contains(reverseActions, required) {
			t.Fatalf("reverse plan missing %q: %s", required, reverseActions)
		}
	}
	fallback, err := scenario.Plan("lan-relay-fallback-return")
	if err != nil {
		t.Fatal(err)
	}
	fallbackText := planText(fallback)
	for _, required := range []string{"A.custody.relay:notif_post:1", "A.custody.lan:notif_post:1", "A.peer-receipt.delta:2", "direct.terminal"} {
		if !strings.Contains(fallbackText, required) {
			t.Fatalf("fallback plan missing %q: %s", required, fallbackText)
		}
	}
	restart, err := scenario.Plan("lan-restart-persistence")
	if err != nil {
		t.Fatal(err)
	}
	restartText := planText(restart)
	for _, required := range []string{"A.outbox.nonzero", "A.force-stop", "A.restart", "B.force-stop", "B.restart", "A.custody.lan:notif_post:2", "A.peer-receipt.delta:2", "direct.terminal"} {
		if !strings.Contains(restartText, required) {
			t.Fatalf("restart plan missing %q: %s", required, restartText)
		}
	}
}

type task5Bridge struct {
	*directSemanticBridge
	mu               sync.Mutex
	failTag          string
	postsAfterUnpair int
	unpaired         bool
	expectedBurst    int
	burstPosts       int
	burstSnapshots   int
	pendingOutbox    bool
}

func newTask5Bridge(omit string) *task5Bridge {
	return &task5Bridge{directSemanticBridge: newDirectSemanticBridge(omit)}
}

func (b *task5Bridge) Post(ctx context.Context, device, tag, text string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.unpaired {
		b.postsAfterUnpair++
	}
	if tag == b.failTag {
		return errors.New("fixture child failed")
	}
	if !strings.HasPrefix(tag, "burst-") && !strings.HasPrefix(tag, "unpair-") {
		b.pendingOutbox = true
		return b.directSemanticBridge.Post(ctx, device, tag, text)
	}
	b.burstPosts++
	remote := b.states["B"]
	remote.Canonical[tag] = "ACTIVE"
	remote.CanonicalSequences[tag] = 1
	remote.CanonicalMaterializedSequences[tag] = 1
	b.states["B"] = remote
	local := b.states["A"]
	local.CustodyCounts["lan"]["notif_post"]++
	local.PeerReceiptCount++
	local.ActiveQueueCount++
	local.ActiveQueueBytes += 256
	if local.ActiveQueueCount > local.PeakQueueCount {
		local.PeakQueueCount = local.ActiveQueueCount
		local.PeakQueueBytes = local.ActiveQueueBytes
	}
	b.states["A"] = local
	return nil
}

func (b *task5Bridge) Control(ctx context.Context, device, name string, params map[string]string) (control.Result, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if name != "LOCAL_UNPAIR" {
		return b.directSemanticBridge.Control(ctx, device, name, params)
	}
	b.unpaired = true
	a := b.states["A"]
	a.UnpairOutcome = "lan"
	a.Paired, a.Health, a.Terminal = false, "stopped", false
	a.ActiveQueueCount, a.ActiveQueueBytes, a.Outbox, a.ActiveInbound, a.PendingMaterialization = 0, 0, 0, 0, 0
	a.Canonical, a.CanonicalSequences, a.CanonicalMaterializedSequences = map[string]string{}, map[string]int{}, map[string]int{}
	b.states["A"] = a
	remote := b.states["B"]
	remote.UnpairInboundCount++
	remote.Paired, remote.Health, remote.Terminal = false, "stopped", false
	remote.ActiveQueueCount, remote.ActiveQueueBytes, remote.Outbox, remote.ActiveInbound, remote.PendingMaterialization = 0, 0, 0, 0, 0
	remote.Canonical, remote.CanonicalSequences, remote.CanonicalMaterializedSequences = map[string]string{}, map[string]int{}, map[string]int{}
	b.states["B"] = remote
	return control.Result{Code: "ok"}, nil
}

func (b *task5Bridge) Snapshot(ctx context.Context, device string) (scenario.Observation, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	state, err := b.directSemanticBridge.Snapshot(ctx, device)
	if err != nil {
		return state, err
	}
	if !b.unpaired && device == "A" && b.pendingOutbox {
		state.Outbox = 1
		b.pendingOutbox = false
	}
	if !b.unpaired && device == "A" && state.ActiveQueueCount > 0 {
		if b.expectedBurst > 0 && b.burstPosts == b.expectedBurst {
			b.burstSnapshots++
			if b.burstSnapshots >= 5 {
				stored := b.states["A"]
				stored.ActiveQueueCount, stored.ActiveQueueBytes = 0, 0
				b.states["A"] = stored
				state.ActiveQueueCount, state.ActiveQueueBytes = 0, 0
			}
		}
		state.Outbox = state.ActiveQueueCount
	}
	return state, nil
}

func TestLanDirectBurstObservesUniqueResultsBoundsAndTerminalZero(t *testing.T) {
	bridge := newTask5Bridge("")
	bridge.expectedBurst = 8
	result, err := scenario.NewExecutor(bridge, 50*time.Millisecond).RunResultWithBurstCount(context.Background(), "lan-direct-burst-backpressure", 8)
	if err != nil || result.Status != "passed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	a := result.After["A"]
	if a.PeakQueueCount == 0 || a.PeakQueueCount > 2_000 || a.PeakQueueBytes > 128<<20 || a.ActiveQueueCount != 0 || a.ActiveQueueBytes != 0 {
		t.Fatalf("queue evidence=%+v", a)
	}
	if got := result.After["A"].PeerReceiptCount - result.Before["A"].PeerReceiptCount; got != 8 {
		t.Fatalf("terminal receipts=%d", got)
	}
}

func TestLanDirectUnpairDuringTrafficOrdersCustodyBeforeStableWipe(t *testing.T) {
	bridge := newTask5Bridge("")
	bridge.expectedBurst = 8
	result, err := scenario.NewExecutor(bridge, 700*time.Millisecond).RunResultWithBurstCount(context.Background(), "lan-direct-unpair-during-traffic", 8)
	if err != nil || result.Status != "passed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	events := strings.Join(result.Events, "\n")
	if strings.Index(events, "predicate:A.active-queue.nonzero") > strings.Index(events, "control:A:local-unpair") ||
		strings.Index(events, "predicate:A.unpair.custody") > strings.Index(events, "predicate:both.unpaired.stable") {
		t.Fatalf("ordering=%v", result.Events)
	}
	for _, device := range []string{"A", "B"} {
		state := result.After[device]
		if state.Paired || state.Health != "stopped" || state.ActiveQueueCount != 0 || state.Outbox != 0 || len(state.Canonical) != 0 {
			t.Fatalf("%s recreated state: %+v", device, state)
		}
	}
	if bridge.postsAfterUnpair != 0 {
		t.Fatalf("posts after wipe=%d", bridge.postsAfterUnpair)
	}
}

func TestLanDirectUnpairUsesOneBoundedProducerBeforeUnpair(t *testing.T) {
	plan, err := scenario.PlanWithBurstCount("lan-direct-unpair-during-traffic", 8)
	if err != nil {
		t.Fatal(err)
	}
	actions := plan.Actions()
	if len(actions) != 2 || actions[0] != "A.burst.start:8" || actions[1] != "A.control.local-unpair" {
		t.Fatalf("actions=%v", actions)
	}
}

func TestLanProductAggregateStopsAtFailureAndRetainsCompletedChildren(t *testing.T) {
	bridge := newTask5Bridge("")
	bridge.failTag = "n-lan-direct-delivery"
	result, err := scenario.NewExecutor(bridge, 20*time.Millisecond).RunResultWithBurstCount(context.Background(), "lan-product-correctness", 8)
	if err == nil || result.Status != "failed" || len(result.Children) != 1 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if result.Children[0].Status != "failed" {
		t.Fatalf("children=%+v", result.Children)
	}
}

func TestResultEvidenceIsDerivedFromObservedSnapshots(t *testing.T) {
	bridge := &fakeBridge{states: map[string]scenario.Observation{
		"A": {Health: "connected", Route: "lan", RoutePhase: "authenticated", RouteGeneration: 7, QueuedBytes: 42, ReceiptAtMs: 1700000000000},
		"B": {Health: "connected", Route: "lan", RoutePhase: "authenticated", RouteGeneration: 8},
	}}
	result, err := scenario.NewExecutor(bridge, 50*time.Millisecond).RunResult(context.Background(), "lan-direct-delivery")
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "passed" || result.After["B"].Health != "connected" || len(result.Events) == 0 {
		t.Fatalf("result=%+v", result)
	}
	if result.Route.Route != "lan" || result.Route.Phase != "authenticated" || result.Route.Generation != 7 || result.Route.ReceiptAtMs != 1700000000001 {
		t.Fatalf("route evidence was not derived from the observed executor state: %+v", result.Route)
	}
	dir := t.TempDir()
	if err := scenario.WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(filepath.Join(dir, "state.json"))
	if err != nil {
		t.Fatal(err)
	}
	result.After["B"] = scenario.Observation{Health: "offline", Route: "none", RoutePhase: "idle", Outbox: 9}
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

func TestLanFallbackAndReturnUsesRoutePreferenceWithoutChangingRadios(t *testing.T) {
	plan, err := scenario.Plan("lan-relay-fallback-return")
	if err != nil {
		t.Fatal(err)
	}
	if err := scenario.ValidateExecutablePlan(plan); err != nil {
		t.Fatal(err)
	}
	for _, action := range plan.Actions() {
		if strings.Contains(action, "network.") {
			t.Fatalf("route fallback must not alter radios: %s", action)
		}
	}
}

func TestFallbackEvidenceRecordsRelayDeliveryThenLanReturn(t *testing.T) {
	bridge := newDirectSemanticBridge("")
	result, err := scenario.NewExecutor(bridge, 50*time.Millisecond).RunResult(context.Background(), "lan-relay-fallback-return")
	if err != nil {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if result.Route.Route != "lan" || result.After["A"].Route != "lan" || result.After["B"].Route != "lan" {
		t.Fatalf("delivery and return evidence disagree: route=%+v after=%+v", result.Route, result.After)
	}
	events := strings.Join(result.Events, "\n")
	relayEvent := strings.Index(events, "route:post:A:n1-relay:relay:")
	lanEvent := strings.Index(events, "route:post:A:n1-lan:lan:")
	if relayEvent < 0 || lanEvent < 0 || relayEvent >= lanEvent {
		t.Fatalf("ordered route events absent: %v", result.Events)
	}
}

func TestExecutorReuseDoesNotInheritDeliveryRoute(t *testing.T) {
	base := &fakeBridge{states: map[string]scenario.Observation{
		"A": {Health: "connected", Route: "lan", RoutePhase: "authenticated", RouteGeneration: 1},
		"B": {Health: "connected", Route: "lan", RoutePhase: "authenticated", RouteGeneration: 1},
	}}
	bridge := &routeAfterPostBridge{fakeBridge: base}
	executor := scenario.NewExecutor(bridge, 50*time.Millisecond)
	if _, err := executor.RunResult(context.Background(), "lan-relay-fallback-return"); err != nil {
		t.Fatal(err)
	}
	for _, peer := range []string{"A", "B"} {
		state := base.states[peer]
		state.Route = "none"
		state.RoutePhase = "idle"
		base.states[peer] = state
	}
	bridge.setLanAfterPost = true

	result, err := executor.RunResult(context.Background(), "lan-direct-delivery")
	if err == nil || result.Route.Route != "" {
		t.Fatalf("later run inherited delivery route: result=%+v err=%v", result, err)
	}
}

func TestExecutorReuseDoesNotRepeatPriorLanFaultCleanup(t *testing.T) {
	base := &fakeBridge{states: map[string]scenario.Observation{
		"A": {Health: "connected", Route: "lan", RoutePhase: "authenticated"},
		"B": {Health: "connected", Route: "lan", RoutePhase: "authenticated"},
	}}
	executor := scenario.NewExecutor(&faultFailureBridge{base}, 50*time.Millisecond)
	if _, err := executor.RunResult(context.Background(), "lan-relay-fallback-return"); err == nil {
		t.Fatal("expected interrupted first run")
	}
	base.events = nil

	if _, err := executor.RunResult(context.Background(), "post"); err != nil {
		t.Fatal(err)
	}
	for _, event := range base.events {
		if strings.Contains(event, "SET_LAN_AVAILABLE") {
			t.Fatalf("later run repeated stale cleanup: %v", base.events)
		}
	}
}

func TestInterruptedFallbackRestoresAnyAppliedLanFault(t *testing.T) {
	base := &fakeBridge{states: map[string]scenario.Observation{
		"A": {Health: "connected", Route: "lan", RoutePhase: "authenticated"},
		"B": {Health: "connected", Route: "lan", RoutePhase: "authenticated"},
	}}
	_, err := scenario.NewExecutor(&faultFailureBridge{base}, 50*time.Millisecond).RunResult(context.Background(), "lan-relay-fallback-return")
	if err == nil {
		t.Fatal("expected interrupted fault setup")
	}
	if base.states["A"].Route != "lan" || base.states["B"].Route != "lan" {
		t.Fatalf("LAN fault was stranded: %+v", base.states)
	}
}

func TestBlockingFallbackRestoresAreBoundedAndBestEffortForBothDevices(t *testing.T) {
	base := &fakeBridge{states: map[string]scenario.Observation{
		"A": {Health: "connected", Route: "lan", RoutePhase: "authenticated"},
		"B": {Health: "connected", Route: "lan", RoutePhase: "authenticated"},
	}}
	bridge := &blockingRestoreBridge{fakeBridge: base, restoreAttempts: map[string]bool{}}
	started := time.Now()
	result, err := scenario.NewExecutor(bridge, 20*time.Millisecond).RunResult(context.Background(), "lan-relay-fallback-return")
	if err == nil || result.Status != "failed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if elapsed := time.Since(started); elapsed > 250*time.Millisecond {
		t.Fatalf("bounded cleanup took %s", elapsed)
	}
	if !bridge.restoreAttempts["A"] || !bridge.restoreAttempts["B"] {
		t.Fatalf("cleanup did not attempt both devices: %+v", bridge.restoreAttempts)
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

func TestBlockingTerminalSnapshotsAreBoundedAndAttemptBothDevices(t *testing.T) {
	base := &fakeBridge{states: map[string]scenario.Observation{"A": {}, "B": {}}}
	bridge := &blockingTerminalSnapshotBridge{fakeBridge: base, blocked: map[string]bool{}}
	started := time.Now()
	result, err := scenario.NewExecutor(bridge, 20*time.Millisecond).RunResult(context.Background(), "post")
	if err == nil || result.Status != "failed" || result.ErrorCode != "execution_failed" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if elapsed := time.Since(started); elapsed > 250*time.Millisecond {
		t.Fatalf("bounded terminal snapshots took %s", elapsed)
	}
	if !bridge.blocked["A"] || !bridge.blocked["B"] {
		t.Fatalf("terminal evidence did not attempt both devices: %+v", bridge.blocked)
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
	payload := validObservationPayloadForTest()
	payload["canonical"] = []any{map[string]any{"canon_id_hash": strings.Repeat("a", 64), "state": "ACTIVE", "sequence": 3, "materialized_sequence": 3}}
	payload["activity"] = []any{map[string]any{"event_type": "delivery_loop"}}
	encoded, marshalErr := json.Marshal(payload)
	if marshalErr != nil {
		t.Fatal(marshalErr)
	}
	state, err := scenario.ParseObservation(encoded)
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

type directSemanticBridge struct {
	states                 map[string]scenario.Observation
	omit                   string
	sequence               int
	hash                   string
	session                string
	delivered              int
	dismissed              bool
	bSnapshotsAfterDismiss int
	notificationSequences  map[string]int
}

func newDirectSemanticBridge(omit string) *directSemanticBridge {
	counts := func() map[string]map[string]int64 {
		keys := []string{"notif_post", "notif_update", "notif_cancel", "call_state", "state_digest", "state_snapshot_begin", "state_snapshot_item", "state_snapshot_end", "unpair", "peer_receipt"}
		result := map[string]map[string]int64{"lan": {}, "relay": {}}
		for route := range result {
			for _, key := range keys {
				result[route][key] = 0
			}
		}
		return result
	}
	state := func() scenario.Observation {
		return scenario.Observation{
			Health: "connected", Route: "lan", RoutePhase: "authenticated", RouteGeneration: 7,
			Terminal: true, Paired: true, CustodyCounts: counts(), Canonical: map[string]string{},
			CanonicalSequences: map[string]int{}, CanonicalSemanticStates: map[string]string{},
			CanonicalMaterializedSequences: map[string]int{},
		}
	}
	bridge := &directSemanticBridge{
		states: map[string]scenario.Observation{"A": state(), "B": state()}, omit: omit,
		hash: strings.Repeat("a", 64), session: "11111111-1111-4111-8111-111111111111",
		notificationSequences: map[string]int{},
	}
	if omit == "route" {
		a := bridge.states["A"]
		a.Route = "relay"
		bridge.states["A"] = a
	}
	return bridge
}

func (b *directSemanticBridge) Control(_ context.Context, device, name string, params map[string]string) (control.Result, error) {
	switch name {
	case "SET_LAN_AVAILABLE":
		state := b.states[device]
		state.Route = map[bool]string{true: "lan", false: "relay"}[params["available"] == "true"]
		state.RoutePhase = "authenticated"
		state.RouteGeneration++
		b.states[device] = state
		return control.Result{Code: "ok"}, nil
	case "CALL_CAPTURE_ENABLE":
		state := b.states["A"]
		state.CallCaptureEnabled = true
		b.states["A"] = state
		return control.Result{Code: "ok"}, nil
	case "CALL_STATE":
		b.sequence++
		semantic := strings.ToUpper(params["state"])
		hash := callHashForTest(b.session)
		if b.omit != "call-"+strings.ToLower(semantic) {
			remote := b.states["B"]
			remote.Canonical[hash] = map[bool]string{true: "CANCELLED", false: "ACTIVE"}[semantic == "IDLE"]
			remote.CanonicalSequences[hash] = b.sequence
			remote.CanonicalSemanticStates[hash] = semantic
			if b.omit != "stale-call-"+strings.ToLower(semantic) {
				remote.CanonicalMaterializedSequences[hash] = b.sequence
			}
			b.states["B"] = remote
		}
		b.recordCustody("A", "call_state")
		b.recordReceipt("A")
		b.afterDelivery()
		payload, _ := json.Marshal(map[string]any{"call_session_id": b.session, "state": params["state"], "sequence": b.sequence})
		return control.Result{Code: "ok", Payload: payload}, nil
	case "DISMISS_NEWEST_MIRROR":
		b.dismissed = true
		if b.omit != "dismissal" {
			remote := b.states["B"]
			remote.UserDismissCount++
			b.states["B"] = remote
		}
		if b.omit != "exact-cancel" {
			origin := b.states["A"]
			origin.Canonical[b.hash] = "CANCELLED"
			origin.CanonicalSequences[b.hash] = 2
			origin.CanonicalMaterializedSequences[b.hash] = 2
			b.states["A"] = origin
		}
		if b.omit != "no-resurrection" {
			remote := b.states["B"]
			remote.Canonical[b.hash] = "CANCELLED"
			remote.CanonicalSequences[b.hash] = 2
			remote.CanonicalMaterializedSequences[b.hash] = 2
			b.states["B"] = remote
		}
		b.recordCustody("B", "notif_cancel")
		b.recordReceipt("B")
		b.afterDelivery()
		return control.Result{Code: "ok"}, nil
	case "EMIT_SNAPSHOT":
		if b.omit != "snapshot-digest" {
			remote := b.states["B"]
			remote.SnapshotDigestCount++
			b.states["B"] = remote
		}
		b.recordCustody("A", "state_digest")
		b.afterDelivery()
		return control.Result{Code: "ok"}, nil
	case "FORCE_REPAIR_SNAPSHOT":
		remote := b.states["B"]
		if b.omit != "snapshot-begin" {
			remote.SnapshotBeginCount++
		}
		if b.omit != "snapshot-end" {
			remote.SnapshotEndCount++
		}
		if b.omit != "snapshot-commit" {
			remote.SnapshotCommitCount++
		}
		b.states["B"] = remote
		for _, event := range []string{"state_snapshot_begin", "state_snapshot_item", "state_snapshot_end"} {
			b.recordCustody("A", event)
		}
		b.afterDelivery()
		return control.Result{Code: "ok"}, nil
	default:
		return control.Result{Code: "ok"}, nil
	}
}

func (b *directSemanticBridge) recordCustody(device, event string) {
	if b.omit == "custody-"+event {
		return
	}
	state := b.states[device]
	route := state.Route
	state.CustodyCounts[route][event]++
	b.states[device] = state
}

func (b *directSemanticBridge) recordReceipt(device string) {
	if b.omit == "receipt" {
		return
	}
	state := b.states[device]
	state.PeerReceiptCount++
	b.states[device] = state
}

func (b *directSemanticBridge) afterDelivery() {
	b.delivered++
	if b.omit != "route-after-first" || b.delivered != 1 {
		return
	}
	for _, device := range []string{"A", "B"} {
		state := b.states[device]
		state.Route = "relay"
		state.RouteGeneration++
		b.states[device] = state
	}
}

func (b *directSemanticBridge) Post(_ context.Context, device, tag, _ string) error {
	b.notificationSequences[tag]++
	b.sequence = b.notificationSequences[tag]
	b.hash = fmt.Sprintf("%x", sha256.Sum256([]byte("notification:"+tag)))
	recipient := map[string]string{"A": "B", "B": "A"}[device]
	if b.omit != fmt.Sprintf("sequence-%d", b.sequence) {
		remote := b.states[recipient]
		remote.Canonical[b.hash] = "ACTIVE"
		remote.CanonicalSequences[b.hash] = b.sequence
		if b.omit != fmt.Sprintf("stale-sequence-%d", b.sequence) {
			remote.CanonicalMaterializedSequences[b.hash] = b.sequence
		}
		b.states[recipient] = remote
	}
	event := "notif_post"
	if b.sequence > 1 {
		event = "notif_update"
	}
	b.recordCustody(device, event)
	b.recordReceipt(device)
	origin := b.states[device]
	origin.Outbox = 1
	b.states[device] = origin
	b.afterDelivery()
	return nil
}

func (b *directSemanticBridge) Cancel(_ context.Context, device, _ string) error {
	recipient := map[string]string{"A": "B", "B": "A"}[device]
	remote := b.states[recipient]
	remote.Canonical[b.hash] = "CANCELLED"
	b.states[recipient] = remote
	return nil
}
func (b *directSemanticBridge) SetNetwork(context.Context, string, bool) error { return nil }
func (b *directSemanticBridge) ForceStop(context.Context, string) error        { return nil }
func (b *directSemanticBridge) Restart(context.Context, string) error          { return nil }
func (b *directSemanticBridge) Reconcile(context.Context, string) error        { return nil }
func (b *directSemanticBridge) Snapshot(_ context.Context, device string) (scenario.Observation, error) {
	if device == "B" && b.dismissed {
		b.bSnapshotsAfterDismiss++
		if b.omit == "cancel-then-resurrect" && b.bSnapshotsAfterDismiss == 6 {
			state := b.states["B"]
			state.Canonical[b.hash] = "ACTIVE"
			state.CanonicalSequences[b.hash] = 3
			state.CanonicalMaterializedSequences[b.hash] = 3
			b.states["B"] = state
		}
	}
	state := b.states[device]
	if state.Outbox > 0 {
		stored := state
		stored.Outbox = 0
		b.states[device] = stored
	}
	if b.omit == "terminal" {
		state.Terminal = false
	}
	return state, nil
}

func callHashForTest(session string) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte("call:"+session)))
}

func runDirectSemantic(t *testing.T, name, omit string) (scenario.ScenarioResult, error) {
	t.Helper()
	return scenario.NewExecutor(newDirectSemanticBridge(omit), 20*time.Millisecond).RunResult(context.Background(), name)
}

func TestLanDirectUpdate(t *testing.T) {
	result, err := runDirectSemantic(t, "lan-direct-update", "")
	if err != nil || result.Status != "passed" || result.Route.Route != "lan" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
}

func TestLanDirectReverseDelivery(t *testing.T) {
	result, err := runDirectSemantic(t, "lan-direct-reverse-delivery", "")
	if err != nil || result.Status != "passed" || result.Route.Route != "lan" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	if !contains(result.Events, "post:B:n1") || !contains(result.Events, "predicate:A.tracked.sequence:1") {
		t.Fatalf("missing reverse evidence: %v", result.Events)
	}
}

func TestLanDirectReverseDeliveryRejectsMissingObservations(t *testing.T) {
	assertDirectMissing(t, "lan-direct-reverse-delivery", map[string]string{
		"route": "missing_authenticated_lan", "sequence-1": "missing_sequence_transition",
		"stale-sequence-1": "missing_sequence_transition", "custody-notif_post": "missing_lan_custody",
		"receipt": "missing_peer_receipt", "terminal": "missing_terminal_convergence",
	})
}

func TestLanDirectPeerDismiss(t *testing.T) {
	result, err := runDirectSemantic(t, "lan-direct-peer-dismiss", "")
	if err != nil || result.Status != "passed" || result.Route.Route != "lan" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
}

func TestLanDirectCallState(t *testing.T) {
	result, err := runDirectSemantic(t, "lan-direct-call-state", "")
	if err != nil || result.Status != "passed" || result.Route.Route != "lan" {
		t.Fatalf("result=%+v err=%v", result, err)
	}
	for _, state := range []string{"RINGING", "ACTIVE", "IDLE"} {
		if !contains(result.Events, "predicate:B.call.semantic:"+state) {
			t.Fatalf("missing %s evidence: %v", state, result.Events)
		}
	}
}

func TestLanDirectSnapshotReceipt(t *testing.T) {
	result, err := runDirectSemantic(t, "lan-direct-snapshot-receipt", "")
	if err != nil || result.Status != "passed" || result.Route.Route != "lan" || result.After["B"].SnapshotCommitCount != 1 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
}

func TestLanDirectUpdateRejectsMissingObservations(t *testing.T) {
	assertDirectMissing(t, "lan-direct-update", map[string]string{
		"route": "missing_authenticated_lan", "route-after-first": "missing_authenticated_lan",
		"sequence-1": "missing_sequence_transition", "sequence-2": "missing_sequence_transition", "sequence-3": "missing_sequence_transition",
		"stale-sequence-1": "missing_sequence_transition", "stale-sequence-2": "missing_sequence_transition", "stale-sequence-3": "missing_sequence_transition",
		"custody-notif_update": "missing_lan_custody", "receipt": "missing_peer_receipt", "terminal": "missing_terminal_convergence",
	})
}

func TestLanDirectPeerDismissRejectsMissingObservations(t *testing.T) {
	assertDirectMissing(t, "lan-direct-peer-dismiss", map[string]string{
		"route": "missing_authenticated_lan", "route-after-first": "missing_authenticated_lan",
		"dismissal": "missing_user_dismissal", "exact-cancel": "missing_exact_cancellation",
		"no-resurrection": "missing_exact_cancellation", "custody-notif_cancel": "missing_lan_custody",
		"cancel-then-resurrect": "missing_no_resurrection",
		"receipt":               "missing_peer_receipt", "terminal": "missing_terminal_convergence",
	})
}

func TestLanDirectCallStateRejectsMissingObservations(t *testing.T) {
	assertDirectMissing(t, "lan-direct-call-state", map[string]string{
		"route": "missing_authenticated_lan", "route-after-first": "missing_authenticated_lan",
		"call-ringing": "missing_call_state_transition", "call-active": "missing_call_state_transition", "call-idle": "missing_call_state_transition",
		"stale-call-ringing": "missing_call_state_transition", "stale-call-active": "missing_call_state_transition", "stale-call-idle": "missing_call_state_transition",
		"custody-call_state": "missing_lan_custody", "receipt": "missing_peer_receipt", "terminal": "missing_terminal_convergence",
	})
}

func TestLanDirectSnapshotReceiptRejectsMissingObservations(t *testing.T) {
	assertDirectMissing(t, "lan-direct-snapshot-receipt", map[string]string{
		"route": "missing_authenticated_lan", "route-after-first": "missing_authenticated_lan",
		"snapshot-digest": "missing_snapshot_digest", "snapshot-begin": "missing_snapshot_begin",
		"snapshot-end":         "missing_snapshot_end",
		"snapshot-commit":      "missing_snapshot_commit",
		"custody-state_digest": "missing_lan_custody", "custody-state_snapshot_begin": "missing_lan_custody",
		"custody-state_snapshot_item": "missing_lan_custody", "custody-state_snapshot_end": "missing_lan_custody",
		"terminal": "missing_terminal_convergence",
	})
}

func assertDirectMissing(t *testing.T, name string, omissions map[string]string) {
	t.Helper()
	for omission, wantCode := range omissions {
		t.Run(omission, func(t *testing.T) {
			result, err := runDirectSemantic(t, name, omission)
			if err == nil || result.Status == "passed" || result.ErrorCode != wantCode || strings.Contains(result.ErrorCode, "n1") {
				t.Fatalf("omission=%s result=%+v err=%v", omission, result, err)
			}
		})
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
