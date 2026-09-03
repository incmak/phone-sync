package scenario_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

// The synthetic call-control scenario must produce evidence that proves
// dispatch without retaining any capability, invocation, or caller material.
func TestCallControlScenarioRedactsCapabilityAndCallerData(t *testing.T) {
	result, err := runDirectSemantic(t, "call-control-answer", "")
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "passed" {
		t.Fatalf("result=%+v", result)
	}
	raw, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	text := strings.ToLower(string(raw))
	for _, forbidden := range []string{
		"control_id", "invocation_id", "phone_number", "caller", "call_session_id",
		"11111111-1111-4111-8111-111111111111", "pending_intent", "package",
	} {
		if strings.Contains(text, forbidden) {
			t.Fatalf("evidence retained %q: %s", forbidden, raw)
		}
	}
	if !contains(result.Events, "control:B:call-control-tap") || !contains(result.Events, "control:A:call-control-await") {
		t.Fatalf("events=%v", result.Events)
	}
	after := result.After["A"]
	if !after.CallControlsEnabled || after.CallControlDispatches["answer"] != 1 || after.CallControlDispatches["hang_up"] != 1 {
		t.Fatalf("after A=%+v", after)
	}
	dir := t.TempDir()
	if err := scenario.WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	content, err := os.ReadFile(filepath.Join(dir, "scenario-result.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(content), `"call_control_dispatches"`) || strings.Contains(string(content), "canonical_call_controls") {
		t.Fatalf("artifact shape: %s", content)
	}
}

func TestCallControlCorrectnessComposesThreeChildrenInOrder(t *testing.T) {
	plan, err := scenario.Plan("call-control-correctness")
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Steps) != 0 || len(plan.Children) != 3 {
		t.Fatalf("plan=%+v", plan)
	}
	for index, want := range []string{"call-control-answer", "call-control-decline", "call-control-duplicate"} {
		if plan.Children[index].Name != want {
			t.Fatalf("child %d = %q, want %q", index, plan.Children[index].Name, want)
		}
	}
	if err := scenario.ValidateExecutablePlan(plan); err != nil {
		t.Fatal(err)
	}
	result, err := runDirectSemantic(t, "call-control-correctness", "")
	if err != nil || result.Status != "passed" || len(result.Children) != 3 {
		t.Fatalf("result=%+v err=%v", result, err)
	}
}

func TestCallControlDeclineAndDuplicatePlansPass(t *testing.T) {
	for _, name := range []string{"call-control-decline", "call-control-duplicate"} {
		result, err := runDirectSemantic(t, name, "")
		if err != nil || result.Status != "passed" {
			t.Fatalf("%s: result=%+v err=%v", name, result, err)
		}
	}
	decline, _ := runDirectSemantic(t, "call-control-decline", "")
	if decline.After["A"].CallControlDispatches["decline"] != 1 || decline.After["A"].CallControlDispatches["answer"] != 0 {
		t.Fatalf("decline after A=%+v", decline.After["A"])
	}
}

// Call-control plans prove capability dispatch over whatever route is
// authenticated. They make no route claim, so the evidence must pass on relay
// and must not invent a route record.
func TestCallControlPlansNeverRequireADirectRoute(t *testing.T) {
	result, err := runDirectSemantic(t, "call-control-answer", "route")
	if err != nil || result.Status != "passed" || !result.Route.IsZero() || result.After["A"].Route != "relay" {
		t.Fatalf("status=%s route=%+v err=%v", result.Status, result.Route, err)
	}
	for _, step := range append(append([]scenario.Step(nil), callControlStepsForTest(t, "call-control-decline")...), callControlStepsForTest(t, "call-control-duplicate")...) {
		if strings.Contains(step.Predicate, ".route.") {
			t.Fatalf("call-control plan claims a route: %+v", step)
		}
	}
}

func callControlStepsForTest(t *testing.T, name string) []scenario.Step {
	t.Helper()
	plan, err := scenario.Plan(name)
	if err != nil {
		t.Fatal(err)
	}
	return plan.Steps
}

func TestCallControlAnswerRejectsMissingOrLeakingObservations(t *testing.T) {
	assertDirectMissing(t, "call-control-answer", map[string]string{
		"controls-enable":   "missing_call_controls_enabled",
		"controls-ringing":  "missing_call_controls",
		"controls-active":   "missing_call_controls",
		"controls-linger":   "missing_call_controls",
		"dispatch-answer":   "missing_call_control_dispatch",
		"dispatch-hang_up":  "missing_call_control_dispatch",
		"await-wrong-kind":  "invalid_call_control_await",
		"await-leak":        "invalid_call_control_await",
		"tap-leak":          "invalid_call_control_tap",
		"source-leak":       "invalid_call_control_source",
		"call-ringing":      "missing_call_state_transition",
		"stale-call-active": "missing_call_state_transition",
		"call-idle":         "missing_call_state_transition",
	})
}

func TestCallControlDuplicateTapDispatchesOnce(t *testing.T) {
	assertDirectMissing(t, "call-control-duplicate", map[string]string{
		"duplicate-dispatch": "duplicate_call_control_dispatch",
	})
}

func TestCallControlDeclineRejectsMissingDispatch(t *testing.T) {
	assertDirectMissing(t, "call-control-decline", map[string]string{
		"dispatch-decline": "missing_call_control_dispatch",
	})
}

func TestCallControlPlanLanguageIsClosed(t *testing.T) {
	for _, step := range []scenario.Step{
		{Action: "A.control.call-control-source:hold"},
		{Action: "A.control.call-control-source:"},
		{Action: "B.control.call-control-tap:mute"},
		{Action: "B.control.call-control-tap:answer:extra"},
		{Action: "A.control.call-control-await:replay"},
		{Action: "B.control.call-controls-enable"},
		{Predicate: "B.call.controls:answer"},
		{Predicate: "B.call.controls:decline,answer"},
		{Predicate: "B.call.controls:answer,decline,hang_up"},
		{Predicate: "A.call-control.dispatched:answer:2"},
		{Predicate: "A.call-control.dispatched:mute:1"},
		{Predicate: "B.call-control.dispatched:answer:1"},
	} {
		if err := scenario.ValidateExecutablePlan(scenario.ScenarioPlan{Name: "call-control-probe", Steps: []scenario.Step{step}}); err == nil {
			t.Fatalf("accepted %+v", step)
		}
	}
	for _, step := range []scenario.Step{
		{Action: "A.control.call-controls-enable", Predicate: "A.call-controls.enabled"},
		{Action: "A.control.call-control-source:ringing", Predicate: "B.call.controls:answer,decline"},
		{Action: "B.control.call-control-tap:replay", Predicate: "B.call.controls:hang_up"},
		{Action: "A.control.call-control-await:hang_up", Predicate: "A.call-control.dispatched:hang_up:0"},
		{Predicate: "B.call.controls:none"},
	} {
		if err := scenario.ValidateExecutablePlan(scenario.ScenarioPlan{Name: "call-control-probe", Steps: []scenario.Step{step}}); err != nil {
			t.Fatalf("%+v: %v", step, err)
		}
	}
}

func TestParseObservationReadsClosedCallControlEvidence(t *testing.T) {
	hash := strings.Repeat("b", 64)
	payload := validObservationPayloadForTest()
	payload["call_controls_enabled"] = true
	payload["canonical_call_controls"] = map[string]any{hash: []any{"answer", "decline"}}
	payload["call_control_dispatches"] = map[string]any{"answer": 1.0, "hang_up": 0.0}
	encoded, _ := json.Marshal(payload)
	state, err := scenario.ParseObservation(encoded)
	if err != nil {
		t.Fatal(err)
	}
	if !state.CallControlsEnabled || len(state.CanonicalCallControls[hash]) != 2 || state.CallControlDispatches["answer"] != 1 {
		t.Fatalf("state=%+v", state)
	}
	mutations := map[string]func(map[string]any){
		"missing enabled":    func(p map[string]any) { delete(p, "call_controls_enabled") },
		"missing controls":   func(p map[string]any) { delete(p, "canonical_call_controls") },
		"missing dispatches": func(p map[string]any) { delete(p, "call_control_dispatches") },
		"enabled not bool":   func(p map[string]any) { p["call_controls_enabled"] = "yes" },
		"unknown kind":       func(p map[string]any) { p["canonical_call_controls"] = map[string]any{hash: []any{"answer", "mute"}} },
		"unsorted kinds": func(p map[string]any) {
			p["canonical_call_controls"] = map[string]any{hash: []any{"decline", "answer"}}
		},
		"duplicate kinds": func(p map[string]any) { p["canonical_call_controls"] = map[string]any{hash: []any{"answer", "answer"}} },
		"empty kinds":     func(p map[string]any) { p["canonical_call_controls"] = map[string]any{hash: []any{}} },
		"raw canon key":   func(p map[string]any) { p["canonical_call_controls"] = map[string]any{"call:1111": []any{"hang_up"}} },
		"control id in kinds": func(p map[string]any) {
			p["canonical_call_controls"] = map[string]any{hash: []any{"33333333-3333-4333-8333-333333333333"}}
		},
		"dispatch kind":       func(p map[string]any) { p["call_control_dispatches"] = map[string]any{"control_id": 1.0} },
		"dispatch negative":   func(p map[string]any) { p["call_control_dispatches"] = map[string]any{"answer": -1.0} },
		"dispatch not int":    func(p map[string]any) { p["call_control_dispatches"] = map[string]any{"answer": "1"} },
		"controls not object": func(p map[string]any) { p["canonical_call_controls"] = []any{} },
	}
	for name, mutate := range mutations {
		t.Run(name, func(t *testing.T) {
			broken := cloneJSONMap(t, payload)
			mutate(broken)
			raw, _ := json.Marshal(broken)
			if _, err := scenario.ParseObservation(raw); err == nil {
				t.Fatal("malformed call control observation passed")
			}
		})
	}
}

func TestCallControlEvidenceAllowlistIsExact(t *testing.T) {
	base := scenario.ScenarioResult{Scenario: "call-control-answer", Status: "failed", Events: []string{}, Before: map[string]scenario.Observation{}, After: map[string]scenario.Observation{}}
	if err := scenario.RejectSensitiveEvidence(base); err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(base)
	var root map[string]any
	if err := json.Unmarshal(encoded, &root); err != nil {
		t.Fatal(err)
	}
	observation := map[string]any{
		"health": "connected", "call_capture_enabled": true, "call_controls_enabled": true, "outbox": 0.0,
		"active_inbound": 0.0, "pending_materialization": 0.0, "mirror": false, "sequence": 0.0, "terminal": true,
		"loop_events": 0.0, "route": "lan", "route_phase": "authenticated", "queued_bytes": 0.0, "route_generation": 1.0,
		"call_control_dispatches": map[string]any{"answer": 1.0},
	}
	root["after"] = map[string]any{"A": observation}
	if err := scenario.RejectSensitiveEvidence(root); err != nil {
		t.Fatal(err)
	}
	for name, mutate := range map[string]func(map[string]any){
		"unknown kind":     func(o map[string]any) { o["call_control_dispatches"] = map[string]any{"mute": 1.0} },
		"control id key":   func(o map[string]any) { o["call_control_dispatches"] = map[string]any{"control_id": 1.0} },
		"non integer":      func(o map[string]any) { o["call_control_dispatches"] = map[string]any{"answer": 1.5} },
		"enabled string":   func(o map[string]any) { o["call_controls_enabled"] = "true" },
		"canon controls":   func(o map[string]any) { o["canonical_call_controls"] = map[string]any{} },
		"invocation field": func(o map[string]any) { o["invocation_id"] = "x" },
	} {
		t.Run(name, func(t *testing.T) {
			broken := cloneJSONMap(t, observation)
			mutate(broken)
			root["after"] = map[string]any{"A": broken}
			if err := scenario.RejectSensitiveEvidence(root); err == nil {
				t.Fatal("accepted malformed call control evidence")
			}
		})
	}
}
