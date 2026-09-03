package scenario

import (
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/control"
)

// Stock incoming-call control scenarios. Device A runs a debug fixture that
// posts a real local CallStyle notification with test PendingIntents and injects
// telephony state; device B renders the mirrored native controls. The host only
// ever names closed enums (state, kind) and reads back counts and status.
//
// The plans run over whatever route is authenticated: they prove capability
// dispatch and one-use semantics, not the route that carried the invoke.
var callControlScenarioOrder = []string{
	"call-control-answer",
	"call-control-decline",
	"call-control-duplicate",
}

var callControlSteps = map[string][]Step{
	"call-control-answer": {
		{Action: "A.control.call-capture-enable", Predicate: "A.call-capture.enabled"},
		{Action: "A.control.call-controls-enable", Predicate: "A.call-controls.enabled"},
		{Action: "A.control.call-control-source:ringing", Predicate: "B.call.semantic:RINGING"},
		{Predicate: "B.call.controls:answer,decline"},
		{Action: "B.control.call-control-tap:answer"},
		{Action: "A.control.call-control-await:answer", Predicate: "A.call-control.dispatched:answer:1"},
		{Action: "A.control.call-control-source:active", Predicate: "B.call.semantic:ACTIVE"},
		{Predicate: "B.call.controls:hang_up"},
		{Action: "B.control.call-control-tap:hang_up"},
		{Action: "A.control.call-control-await:hang_up", Predicate: "A.call-control.dispatched:hang_up:1"},
		{Action: "A.control.call-control-source:idle", Predicate: "B.call.semantic:IDLE"},
		{Predicate: "B.call.controls:none"},
	},
	"call-control-decline": {
		{Action: "A.control.call-capture-enable", Predicate: "A.call-capture.enabled"},
		{Action: "A.control.call-controls-enable", Predicate: "A.call-controls.enabled"},
		{Action: "A.control.call-control-source:ringing", Predicate: "B.call.semantic:RINGING"},
		{Predicate: "B.call.controls:answer,decline"},
		{Action: "B.control.call-control-tap:decline"},
		{Action: "A.control.call-control-await:decline", Predicate: "A.call-control.dispatched:decline:1"},
		{Predicate: "A.call-control.dispatched:answer:0"},
		{Action: "A.control.call-control-source:idle", Predicate: "B.call.semantic:IDLE"},
		{Predicate: "B.call.controls:none"},
	},
	"call-control-duplicate": {
		{Action: "A.control.call-capture-enable", Predicate: "A.call-capture.enabled"},
		{Action: "A.control.call-controls-enable", Predicate: "A.call-controls.enabled"},
		{Action: "A.control.call-control-source:ringing", Predicate: "B.call.semantic:RINGING"},
		{Predicate: "B.call.controls:answer,decline"},
		{Action: "B.control.call-control-tap:answer"},
		{Action: "A.control.call-control-await:answer", Predicate: "A.call-control.dispatched:answer:1"},
		{Action: "B.control.call-control-tap:replay"},
		{Action: "A.control.call-control-await:answer", Predicate: "A.call-control.dispatched:answer:1"},
		{Action: "A.control.call-control-source:idle", Predicate: "B.call.semantic:IDLE"},
		{Predicate: "B.call.controls:none"},
	},
}

func callControlPlan(name string) (ScenarioPlan, bool) {
	if name == "call-control-correctness" {
		children := make([]ScenarioPlan, 0, len(callControlScenarioOrder))
		for _, childName := range callControlScenarioOrder {
			children = append(children, ScenarioPlan{Name: childName, Steps: append([]Step(nil), callControlSteps[childName]...)})
		}
		return ScenarioPlan{Name: name, Children: children}, true
	}
	steps, ok := callControlSteps[name]
	if !ok {
		return ScenarioPlan{}, false
	}
	return ScenarioPlan{Name: name, Steps: append([]Step(nil), steps...)}, true
}

func isCallControlPlan(name string) bool {
	return strings.HasPrefix(name, "call-control-")
}

// callControlKinds is the complete control vocabulary. A ringing incoming call
// advertises answer plus decline; an answered incoming call advertises hang_up.
var callControlKinds = map[string]bool{"answer": true, "decline": true, "hang_up": true}

var callControlSets = map[string][]string{
	"answer,decline": {"answer", "decline"},
	"hang_up":        {"hang_up"},
}

var callControlSetSemantic = map[string]string{"answer,decline": "RINGING", "hang_up": "ACTIVE"}

const callControlAwaitTimeout = 10 * time.Second

// Every debug call-control payload is a closed key set. Anything that could
// name a capability, an invocation, a session, an intent, a package, or a
// person fails the oracle even when the device reports success.
var forbiddenCallControlFields = append(append([]string(nil), forbiddenCallFields...),
	"control_id", "invocation_id", "call_session_id", "session_id", "session", "pending_intent", "intent",
	"package", "package_name", "component", "number", "name", "text", "uuid",
)

var rawUUIDAnywherePattern = regexp.MustCompile(`[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}`)

func containsForbiddenCallControlMaterial(raw json.RawMessage) bool {
	text := strings.ToLower(string(raw))
	for _, field := range forbiddenCallControlFields {
		if strings.Contains(text, `"`+field+`"`) {
			return true
		}
	}
	return rawUUIDAnywherePattern.MatchString(text)
}

func exactCallControlFields(result control.Result, keys ...string) (map[string]json.RawMessage, bool) {
	if result.Code != "ok" || containsForbiddenCallControlMaterial(result.Payload) {
		return nil, false
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(result.Payload, &fields); err != nil || len(fields) != len(keys) {
		return nil, false
	}
	for _, key := range keys {
		if value, ok := fields[key]; !ok || string(value) == "null" {
			return nil, false
		}
	}
	return fields, true
}

func parseCallControlsEnableResult(result control.Result) error {
	fields, ok := exactCallControlFields(result, "enabled")
	if !ok {
		return oracleFailure("invalid_call_controls_enable")
	}
	var enabled bool
	if err := json.Unmarshal(fields["enabled"], &enabled); err != nil || !enabled {
		return oracleFailure("invalid_call_controls_enable")
	}
	return nil
}

// parseCallControlSourceResult returns the injected transition sequence. The
// fixture does not reveal a session identifier; correlation happens through
// the mirror's canonical observation instead.
func parseCallControlSourceResult(result control.Result, expectedState string, previousSequence int) (int, error) {
	if _, ok := exactCallControlFields(result, "state", "sequence"); !ok {
		return 0, oracleFailure("invalid_call_control_source")
	}
	var payload struct {
		State    string `json:"state"`
		Sequence int    `json:"sequence"`
	}
	if err := json.Unmarshal(result.Payload, &payload); err != nil || payload.State != expectedState ||
		payload.Sequence <= 0 || payload.Sequence > 1_000_000_000 || payload.Sequence <= previousSequence {
		return 0, oracleFailure("invalid_call_control_source")
	}
	return payload.Sequence, nil
}

func parseCallControlTapResult(result control.Result, expectedKind string) error {
	if _, ok := exactCallControlFields(result, "kind", "status"); !ok {
		return oracleFailure("invalid_call_control_tap")
	}
	var payload struct {
		Kind   string `json:"kind"`
		Status string `json:"status"`
	}
	if err := json.Unmarshal(result.Payload, &payload); err != nil || payload.Kind != expectedKind || payload.Status != "sent" {
		return oracleFailure("invalid_call_control_tap")
	}
	return nil
}

// parseCallControlAwaitResult enforces the one-use contract: a timeout is a
// missing dispatch, more than one dispatch is a duplicate, and a dispatched
// status with no count is malformed.
func parseCallControlAwaitResult(result control.Result, expectedKind string) (int64, error) {
	if _, ok := exactCallControlFields(result, "kind", "count", "status", "elapsed_ms"); !ok {
		return 0, oracleFailure("invalid_call_control_await")
	}
	var payload struct {
		Kind      string `json:"kind"`
		Count     int64  `json:"count"`
		Status    string `json:"status"`
		ElapsedMs int64  `json:"elapsed_ms"`
	}
	if err := json.Unmarshal(result.Payload, &payload); err != nil || payload.Kind != expectedKind ||
		payload.Count < 0 || payload.Count > 1_000_000_000 ||
		payload.ElapsedMs < 0 || payload.ElapsedMs > control.MaxCallControlAwait.Milliseconds() ||
		(payload.Status != "dispatched" && payload.Status != "timeout") {
		return 0, oracleFailure("invalid_call_control_await")
	}
	switch {
	case payload.Status == "timeout" && payload.Count == 0:
		return 0, oracleFailure("missing_call_control_dispatch")
	case payload.Count > 1:
		return payload.Count, oracleFailure("duplicate_call_control_dispatch")
	case payload.Status != "dispatched" || payload.Count != 1:
		return payload.Count, oracleFailure("invalid_call_control_await")
	}
	return payload.Count, nil
}

// Scenario language for call controls.

func parseCallControlAction(raw string) (action, bool, error) {
	switch {
	case raw == "A.control.call-controls-enable":
		command, err := control.NewCallControlsEnableCommand("plan")
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{kind: actionControl, device: "A", command: command.Name, original: raw}, true, nil
	case strings.HasPrefix(raw, "A.control.call-control-source:"):
		command, err := control.NewCallControlSourceCommand("plan", strings.TrimPrefix(raw, "A.control.call-control-source:"))
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{kind: actionControl, device: "A", command: command.Name, params: command.Params, delivery: true, original: raw}, true, nil
	case strings.HasPrefix(raw, "B.control.call-control-tap:"):
		command, err := control.NewCallControlTapCommand("plan", strings.TrimPrefix(raw, "B.control.call-control-tap:"))
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{kind: actionControl, device: "B", command: command.Name, params: command.Params, delivery: true, original: raw}, true, nil
	case strings.HasPrefix(raw, "A.control.call-control-await:"):
		command, err := control.NewCallControlAwaitCommand("plan", strings.TrimPrefix(raw, "A.control.call-control-await:"), callControlAwaitTimeout)
		if err != nil {
			return action{}, true, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{kind: actionControl, device: "A", command: command.Name, params: command.Params, original: raw}, true, nil
	}
	return action{}, false, nil
}

func parseCallControlsPredicate(predicate string) (kinds []string, semantic string, ok bool) {
	if !strings.HasPrefix(predicate, "B.call.controls:") {
		return nil, "", false
	}
	spec := strings.TrimPrefix(predicate, "B.call.controls:")
	if spec == "none" {
		return nil, "", true
	}
	set, known := callControlSets[spec]
	if !known {
		return nil, "", false
	}
	return set, callControlSetSemantic[spec], true
}

func parseCallControlDispatchPredicate(predicate string) (kind string, want int64, ok bool) {
	parts := strings.Split(predicate, ":")
	if len(parts) != 3 || parts[0] != "A.call-control.dispatched" || !callControlKinds[parts[1]] {
		return "", 0, false
	}
	want, err := strconv.ParseInt(parts[2], 10, 64)
	if err != nil || want < 0 || want > 1 {
		return "", 0, false
	}
	return parts[1], want, true
}

func knownCallControlPredicate(predicate string) bool {
	if predicate == "A.call-controls.enabled" {
		return true
	}
	if _, _, ok := parseCallControlsPredicate(predicate); ok {
		return true
	}
	_, _, ok := parseCallControlDispatchPredicate(predicate)
	return ok
}

func callControlOracleCode(predicate string) (string, bool) {
	switch {
	case predicate == "A.call-controls.enabled":
		return "missing_call_controls_enabled", true
	case strings.HasPrefix(predicate, "B.call.controls:"):
		return "missing_call_controls", true
	case strings.HasPrefix(predicate, "A.call-control.dispatched:"):
		return "missing_call_control_dispatch", true
	}
	return "", false
}

// resolveCallControlHash binds the executor to the single live call canon that
// appeared on B since the baseline. The fixture never returns a session id, so
// the mirror's content-free canonical observation is the only correlation.
func (e *Executor) resolveCallControlHash(b Observation) bool {
	if e.trackedHash != "" {
		return true
	}
	candidate := ""
	for hash := range b.CanonicalSemanticStates {
		if _, seen := e.baselineState["B"].CanonicalSemanticStates[hash]; seen {
			continue
		}
		if candidate != "" {
			return false
		}
		candidate = hash
	}
	if candidate == "" {
		return false
	}
	e.trackedHash = candidate
	return true
}

func (e *Executor) callControlSemanticSatisfied(want string, b Observation) bool {
	if !e.resolveCallControlHash(b) {
		return false
	}
	sequence := b.CanonicalSequences[e.trackedHash]
	return b.CanonicalSemanticStates[e.trackedHash] == want && sequence > 0 &&
		b.CanonicalMaterializedSequences[e.trackedHash] == sequence
}

func (e *Executor) callControlsSatisfied(kinds []string, semantic string, b Observation) bool {
	if kinds == nil {
		return len(b.CanonicalCallControls) == 0
	}
	if !e.resolveCallControlHash(b) || len(b.CanonicalCallControls) != 1 {
		return false
	}
	advertised, ok := b.CanonicalCallControls[e.trackedHash]
	return ok && equalStrings(advertised, kinds) &&
		b.Canonical[e.trackedHash] == "ACTIVE" && b.CanonicalSemanticStates[e.trackedHash] == semantic
}

// callControlDispatchSatisfied requires the exact count on three consecutive
// samples so that a late duplicate dispatch cannot slip past a single reading.
// The executor resets the sample counter whenever a new predicate wait begins.
func (e *Executor) callControlDispatchSatisfied(kind string, want int64, a Observation) bool {
	if a.CallControlDispatches[kind] != want {
		e.callControlStableSamples = 0
		return false
	}
	e.callControlStableSamples++
	return e.callControlStableSamples >= 3
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for index := range a {
		if a[index] != b[index] {
			return false
		}
	}
	return true
}

// STATUS observation fields for call controls. All three are required at the
// root of the E2E state and are closed-world: unknown kinds, unsorted sets, or
// non-hash keys fail the parse rather than being ignored.

type callControlObservation struct {
	Enabled    bool
	Controls   map[string][]string
	Dispatches map[string]int64
}

func parseCallControlObservations(enabledRaw, controlsRaw, dispatchesRaw json.RawMessage) (callControlObservation, error) {
	var enabled bool
	if err := json.Unmarshal(enabledRaw, &enabled); err != nil {
		return callControlObservation{}, errors.New("E2E state malformed call_controls_enabled")
	}
	var controlsFields map[string]json.RawMessage
	if err := json.Unmarshal(controlsRaw, &controlsFields); err != nil || controlsFields == nil || len(controlsFields) > 1_000 {
		return callControlObservation{}, errors.New("E2E state malformed canonical_call_controls")
	}
	controls := make(map[string][]string, len(controlsFields))
	for hash, raw := range controlsFields {
		if !canonicalHashPattern.MatchString(hash) {
			return callControlObservation{}, errors.New("E2E state malformed canonical_call_controls")
		}
		var kinds []string
		if err := json.Unmarshal(raw, &kinds); err != nil || len(kinds) == 0 || len(kinds) > len(callControlKinds) || !sort.StringsAreSorted(kinds) {
			return callControlObservation{}, errors.New("E2E state malformed canonical_call_controls")
		}
		for index, kind := range kinds {
			if !callControlKinds[kind] || (index > 0 && kinds[index-1] == kind) {
				return callControlObservation{}, errors.New("E2E state malformed canonical_call_controls")
			}
		}
		controls[hash] = kinds
	}
	var dispatchFields map[string]json.RawMessage
	if err := json.Unmarshal(dispatchesRaw, &dispatchFields); err != nil || dispatchFields == nil {
		return callControlObservation{}, errors.New("E2E state malformed call_control_dispatches")
	}
	dispatches := make(map[string]int64, len(dispatchFields))
	for kind, raw := range dispatchFields {
		var count int64
		if !callControlKinds[kind] || json.Unmarshal(raw, &count) != nil || count < 0 || count > 1_000_000_000 {
			return callControlObservation{}, errors.New("E2E state malformed call_control_dispatches")
		}
		dispatches[kind] = count
	}
	return callControlObservation{Enabled: enabled, Controls: controls, Dispatches: dispatches}, nil
}

func cloneStringSliceMap(value map[string][]string) map[string][]string {
	clone := make(map[string][]string, len(value))
	for key, item := range value {
		clone[key] = append([]string(nil), item...)
	}
	return clone
}
