package scenario

import (
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"regexp"
	"strings"
)

// RouteEvidence records how a scenario was actually delivered.
//
// Every field is a bounded enum, a counter, or a timestamp. There is deliberately
// no endpoint, address, interface, or peer identifier, because evidence files are
// uploaded from CI and shared, and a route record is not worth leaking a network
// map for.
type RouteEvidence struct {
	Route             string `json:"route"`
	Phase             string `json:"phase"`
	Generation        int    `json:"route_generation"`
	QueuedCount       int    `json:"queued_count"`
	QueuedBytes       int64  `json:"queued_bytes"`
	PeerEvidence      string `json:"peer_evidence"`
	PendingLocalCount int    `json:"pending_local_count"`
	AwaitingPeerCount int    `json:"awaiting_peer_count"`
	HeldByRelayCount  int    `json:"held_by_relay_count"`
	DeliveryReason    string `json:"delivery_reason"`
	UserContentKind   string `json:"user_content_kind"`
	ReceiptAtMs       int64  `json:"receipt_at_ms,omitempty"`
	ErrorCode         string `json:"error_code,omitempty"`
}

var (
	routeKinds  = map[string]bool{"lan": true, "relay": true, "none": true}
	routePhases = map[string]bool{
		"idle": true, "connecting": true, "authenticated": true, "reconnecting": true,
	}
	peerEvidenceKinds = map[string]bool{"direct": true, "recent": true, "stale": true, "unknown": true}
	deliveryReasons   = map[string]bool{
		"none": true, "no_route": true, "waiting_for_peer": true, "relay_holding": true,
		"lan_bootstrap_waiting": true, "lan_binding_conflict": true, "peer_version_incompatible": true,
	}
	userContentKinds  = map[string]bool{"notifications": true, "sync_updates": true}
	stableCodePattern = regexp.MustCompile(`^[a-z][a-z0-9_]{0,63}$`)
)

// IsZero reports a scenario that observed no route at all. That is legal: a
// scenario predating route evidence must not be made to invent one.
func (r RouteEvidence) IsZero() bool { return r == RouteEvidence{} }

func (r RouteEvidence) MarshalJSON() ([]byte, error) {
	if r.IsZero() {
		return []byte("{}"), nil
	}
	type wireRouteEvidence RouteEvidence
	return json.Marshal(wireRouteEvidence(r))
}

// Validate reports whether the record is one this project is willing to persist.
// A wholly absent record passes; a partially filled one does not, so a route
// cannot be half-claimed.
func (r RouteEvidence) Validate() error {
	if r.IsZero() {
		return nil
	}
	if !routeKinds[r.Route] {
		return fmt.Errorf("unknown route %q", r.Route)
	}
	if !routePhases[r.Phase] {
		return fmt.Errorf("unknown route phase %q", r.Phase)
	}
	if !peerEvidenceKinds[r.PeerEvidence] {
		return fmt.Errorf("unknown peer evidence %q", r.PeerEvidence)
	}
	if !deliveryReasons[r.DeliveryReason] {
		return fmt.Errorf("unknown delivery reason %q", r.DeliveryReason)
	}
	if !userContentKinds[r.UserContentKind] {
		return fmt.Errorf("unknown user content kind %q", r.UserContentKind)
	}
	if r.Generation < 0 || r.QueuedCount < 0 || r.QueuedBytes < 0 || r.ReceiptAtMs < 0 ||
		r.PendingLocalCount < 0 || r.AwaitingPeerCount < 0 || r.HeldByRelayCount < 0 {
		return fmt.Errorf("route evidence counters must not be negative")
	}
	if r.QueuedCount != r.PendingLocalCount {
		return errors.New("queued_count must equal pending_local_count")
	}
	if r.ErrorCode != "" && !stableCodePattern.MatchString(r.ErrorCode) {
		return fmt.Errorf("error code %q is not a stable code", r.ErrorCode)
	}
	return nil
}

// VerifyAutomaticPromotion validates the content-free route sequence emitted by
// the fallback/return scenario. A single enum-valued route per generation is
// also the host-side evidence that no observation claims two active drainers.
func VerifyAutomaticPromotion(transitions []RouteEvidence) error {
	want := []string{"relay", "lan", "relay", "lan"}
	if len(transitions) != len(want) {
		return fmt.Errorf("automatic promotion requires %d transitions", len(want))
	}
	for index, transition := range transitions {
		if err := transition.Validate(); err != nil {
			return fmt.Errorf("transition %d: %w", index, err)
		}
		if transition.Route != want[index] || transition.Phase != "authenticated" {
			return fmt.Errorf("transition %d is not authenticated %s", index, want[index])
		}
		if index > 0 && transition.Generation <= transitions[index-1].Generation {
			return errors.New("route generations are not strictly increasing")
		}
		if transition.Route == "lan" && transition.PeerEvidence != "direct" {
			return errors.New("authenticated LAN lacks direct peer evidence")
		}
		if transition.Route == "relay" && transition.PeerEvidence == "direct" {
			return errors.New("relay must not claim direct peer evidence")
		}
	}
	return nil
}

func VerifyRelayEvidenceStaled(recent, stale RouteEvidence) error {
	if err := recent.Validate(); err != nil {
		return err
	}
	if err := stale.Validate(); err != nil {
		return err
	}
	if recent.Route != "relay" || stale.Route != "relay" || recent.Phase != "authenticated" || stale.Phase != "authenticated" {
		return errors.New("peer-stale evidence requires authenticated relay observations")
	}
	if recent.PeerEvidence != "recent" || stale.PeerEvidence != "stale" {
		return errors.New("relay peer evidence did not transition recent to stale")
	}
	if stale.Generation < recent.Generation {
		return errors.New("stale peer evidence cannot come from an older route generation")
	}
	return nil
}

// Patterns that must never appear in a persisted evidence file. These are matched
// against the serialized form, so a value nested anywhere is still caught.
var sensitiveValuePatterns = []struct {
	name    string
	pattern *regexp.Regexp
}{
	{"ipv4 address", regexp.MustCompile(`\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b`)},
	{"ipv6 address", regexp.MustCompile(`\b[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){3,7}\b`)},
	{"url", regexp.MustCompile(`(?i)\b(wss?|https?)://`)},
	{"mac address", regexp.MustCompile(`\b[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}\b`)},
	{"host and port", regexp.MustCompile(`\b[a-zA-Z0-9][a-zA-Z0-9.\-]*\.[a-zA-Z]{2,}:\d{2,5}\b`)},
	{"private key block", regexp.MustCompile(`-----BEGIN [A-Z ]*PRIVATE KEY-----`)},
	{"bearer token", regexp.MustCompile(`(?i)bearer\s+[A-Za-z0-9._\-]{8,}`)},
	// A long base64 run is key or ciphertext material. Hex digests are allowed
	// separately below because they are bounded, non-reversible, and useful.
	{"base64 blob", regexp.MustCompile(`[A-Za-z0-9+/]{40,}={0,2}`)},
}

// Field names that must never carry a value, whatever that value looks like.
var sensitiveKeyPattern = regexp.MustCompile(
	`(?i)(secret|token|password|passphrase|private_?key|seed|nonce|ssid|bssid|pin|credential|cookie|signature|certificate|ciphertext|notification_?text|title)`,
)

var sensitivePortKeyPattern = regexp.MustCompile(`(?i)(^|_)port($|_)`)

var hexDigestPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// RejectSensitiveEvidence walks any evidence value and refuses to let it be
// persisted if it carries a network identifier or secret material.
func RejectSensitiveEvidence(value any) error {
	encoded, err := json.Marshal(value)
	if err != nil {
		return fmt.Errorf("evidence is not serializable: %w", err)
	}
	var decoded any
	if err := json.Unmarshal(encoded, &decoded); err != nil {
		return fmt.Errorf("evidence is not inspectable: %w", err)
	}
	if root, ok := decoded.(map[string]any); ok {
		if _, isScenario := root["scenario"]; isScenario {
			if err := inspectClosedWorldScenario(root); err != nil {
				return err
			}
		}
	}
	return inspectEvidence("", decoded)
}

func inspectClosedWorldScenario(root map[string]any) error {
	allowedRoot := map[string]bool{
		"scenario": true, "status": true, "events": true, "before": true, "after": true,
		"error_code": true, "route": true, "route_transitions": true,
	}
	for key := range root {
		if !allowedRoot[key] {
			return fmt.Errorf("evidence field %q is not allowlisted", key)
		}
	}
	scenarioName, scenarioOK := root["scenario"].(string)
	status, statusOK := root["status"].(string)
	if !scenarioOK || scenarioName == "" || !statusOK || (status != "passed" && status != "failed") {
		return errors.New("evidence scenario/status shape is invalid")
	}
	if events, ok := root["events"].([]any); !ok {
		if !(status == "failed" && root["events"] == nil) {
			return errors.New("evidence events must be an array")
		}
	} else {
		for _, event := range events {
			if _, ok := event.(string); !ok {
				return errors.New("evidence event must be a string")
			}
		}
	}
	allowedObservation := map[string]bool{
		"health": true, "transport": true, "call_capture_enabled": true, "call_capture_health_code": true,
		"outbox": true, "active_inbound": true, "pending_materialization": true,
		"mirror": true, "sequence": true, "terminal": true, "loop_events": true,
		"route": true, "route_phase": true, "queued_bytes": true, "route_generation": true,
		"peer_evidence": true, "pending_local_count": true, "awaiting_peer_count": true,
		"held_by_relay_count": true, "delivery_reason": true, "user_content_kind": true,
		"receipt_at_ms": true, "error_code": true,
		"paired": true, "custody_counts": true, "peer_receipt_count": true,
		"snapshot_digest_count": true, "snapshot_begin_count": true,
		"snapshot_end_count": true, "snapshot_commit_count": true, "user_dismiss_count": true,
		"unpair_inbound_count": true, "unpair_outcome": true,
		"active_queue_count": true, "active_queue_bytes": true,
		"peak_queue_count": true, "peak_queue_bytes": true,
		"fixture_available": true, "fixture_reply_count": true, "fixture_mark_read_count": true,
		"fixture_generation": true, "fixture_status": true, "action_invocation_counts": true,
		"action_execution_claimed": true, "action_execution_completed": true,
		"detail_active": true, "detail_cancelled": true, "latest_action_terminal": true,
		"foreground_package": true,
	}
	for _, groupName := range []string{"before", "after"} {
		group, ok := root[groupName].(map[string]any)
		if !ok {
			return fmt.Errorf("evidence %s must be an object", groupName)
		}
		if status == "passed" && (group["A"] == nil || group["B"] == nil) {
			return fmt.Errorf("passed evidence %s requires A and B", groupName)
		}
		for device, raw := range group {
			if device != "A" && device != "B" {
				return fmt.Errorf("evidence device %q is not allowlisted", device)
			}
			observation, ok := raw.(map[string]any)
			if !ok {
				return fmt.Errorf("evidence %s.%s must be an object", groupName, device)
			}
			for key := range observation {
				if !allowedObservation[key] {
					return fmt.Errorf("evidence field %q is not allowlisted", groupName+"."+device+"."+key)
				}
			}
			for _, key := range []string{"health", "outbox", "active_inbound", "pending_materialization", "mirror", "sequence", "terminal", "loop_events", "route", "route_phase", "queued_bytes", "route_generation"} {
				if _, present := observation[key]; !present {
					return fmt.Errorf("evidence %s.%s missing %s", groupName, device, key)
				}
			}
			if err := validateObservationShape(observation); err != nil {
				return fmt.Errorf("evidence %s.%s: %w", groupName, device, err)
			}
		}
	}
	allowedRoute := map[string]bool{
		"route": true, "phase": true, "route_generation": true, "queued_count": true, "queued_bytes": true,
		"peer_evidence": true, "pending_local_count": true, "awaiting_peer_count": true,
		"held_by_relay_count": true, "delivery_reason": true, "user_content_kind": true,
		"receipt_at_ms": true, "error_code": true,
	}
	route, ok := root["route"].(map[string]any)
	if !ok {
		if status == "failed" && root["route"] == nil {
			return nil
		}
		return errors.New("evidence route must be an object")
	}
	if route != nil {
		for key := range route {
			if !allowedRoute[key] {
				return fmt.Errorf("evidence route field %q is not allowlisted", key)
			}
		}
		if len(route) != 0 {
			for _, key := range []string{
				"route", "phase", "route_generation", "queued_count", "queued_bytes", "peer_evidence",
				"pending_local_count", "awaiting_peer_count", "held_by_relay_count", "delivery_reason", "user_content_kind",
			} {
				if _, present := route[key]; !present {
					return fmt.Errorf("evidence route missing %s", key)
				}
			}
			encoded, _ := json.Marshal(route)
			var typed RouteEvidence
			if err := json.Unmarshal(encoded, &typed); err != nil {
				return fmt.Errorf("evidence route shape is invalid: %w", err)
			}
			if err := typed.Validate(); err != nil {
				return fmt.Errorf("evidence route is invalid: %w", err)
			}
		}
	}
	if rawTransitions, present := root["route_transitions"]; present {
		transitions, ok := rawTransitions.([]any)
		if !ok {
			return errors.New("evidence route_transitions must be an array")
		}
		for index, raw := range transitions {
			transition, ok := raw.(map[string]any)
			if !ok {
				return fmt.Errorf("evidence route transition %d must be an object", index)
			}
			for key := range transition {
				if !allowedRoute[key] {
					return fmt.Errorf("evidence route transition field %q is not allowlisted", key)
				}
			}
			encoded, _ := json.Marshal(transition)
			var typed RouteEvidence
			if err := json.Unmarshal(encoded, &typed); err != nil {
				return fmt.Errorf("evidence route transition %d has invalid shape", index)
			}
			if err := typed.Validate(); err != nil {
				return fmt.Errorf("evidence route transition %d is invalid: %w", index, err)
			}
		}
	}
	return nil
}

func validateObservationShape(value map[string]any) error {
	stringEnum := func(key string, allowed map[string]bool) error {
		text, ok := value[key].(string)
		if !ok || !allowed[text] {
			return fmt.Errorf("%s is invalid", key)
		}
		return nil
	}
	if err := stringEnum("health", map[string]bool{"stopped": true, "connecting": true, "connected": true, "degraded": true, "offline": true}); err != nil {
		return err
	}
	if _, present := value["transport"]; present {
		if err := stringEnum("transport", map[string]bool{"offline": true, "connecting": true, "online": true}); err != nil {
			return err
		}
	}
	if err := stringEnum("route", routeKinds); err != nil {
		return err
	}
	if err := stringEnum("route_phase", routePhases); err != nil {
		return err
	}
	for key, allowed := range map[string]map[string]bool{
		"peer_evidence": peerEvidenceKinds, "delivery_reason": deliveryReasons, "user_content_kind": userContentKinds,
	} {
		if raw, present := value[key]; present && raw != "" {
			if err := stringEnum(key, allowed); err != nil {
				return err
			}
		}
	}
	for _, key := range []string{"mirror", "terminal", "call_capture_enabled"} {
		if _, ok := value[key].(bool); !ok {
			return fmt.Errorf("%s must be boolean", key)
		}
	}
	for _, key := range []string{"outbox", "active_inbound", "pending_materialization", "sequence", "loop_events", "queued_bytes", "route_generation"} {
		number, ok := value[key].(float64)
		if !ok || number < 0 || number > math.MaxInt64 || number != float64(int64(number)) {
			return fmt.Errorf("%s must be a nonnegative integer", key)
		}
	}
	for _, key := range []string{"pending_local_count", "awaiting_peer_count", "held_by_relay_count"} {
		if raw, present := value[key]; present {
			number, ok := raw.(float64)
			if !ok || number < 0 || number > 2_000 || number != float64(int64(number)) {
				return fmt.Errorf("%s must be a production-bounded nonnegative integer", key)
			}
		}
	}
	if receipt, present := value["receipt_at_ms"]; present {
		number, ok := receipt.(float64)
		if !ok || number < 0 || number > math.MaxInt64 || number != float64(int64(number)) {
			return errors.New("receipt_at_ms must be a nonnegative integer")
		}
	}
	for _, key := range []string{"call_capture_health_code", "error_code"} {
		if raw, present := value[key]; present {
			code, ok := raw.(string)
			if !ok || !stableCodePattern.MatchString(code) {
				return fmt.Errorf("%s must be a stable code", key)
			}
		}
	}
	for _, key := range []string{
		"peer_receipt_count", "snapshot_digest_count", "snapshot_begin_count",
		"snapshot_end_count", "snapshot_commit_count", "user_dismiss_count", "unpair_inbound_count",
	} {
		if raw, present := value[key]; present {
			number, ok := raw.(float64)
			if !ok || number < 0 || number > 1_000_000_000 || number != float64(int64(number)) {
				return fmt.Errorf("%s must be a bounded nonnegative integer", key)
			}
		}
	}
	for _, key := range []string{"fixture_reply_count", "fixture_mark_read_count", "fixture_generation", "action_execution_claimed", "action_execution_completed", "detail_active", "detail_cancelled"} {
		if raw, present := value[key]; present {
			number, ok := raw.(float64)
			if !ok || number < 0 || number > 1_000_000_000 || number != float64(int64(number)) {
				return fmt.Errorf("%s must be a bounded nonnegative integer", key)
			}
		}
	}
	if raw, present := value["fixture_available"]; present {
		if _, ok := raw.(bool); !ok {
			return errors.New("fixture_available must be boolean")
		}
	}
	if raw, present := value["fixture_status"]; present {
		status, ok := raw.(string)
		if !ok || !map[string]bool{"none": true, "posted": true, "updated": true, "cancelled": true, "counters_reset": true, "reply_dispatched": true, "mark_read_dispatched": true}[status] {
			return errors.New("fixture_status is invalid")
		}
	}
	if raw, present := value["latest_action_terminal"]; present {
		status, ok := raw.(string)
		if !ok || !map[string]bool{"none": true, "PENDING": true, "DISPATCHED": true, "OUTCOME_UNKNOWN": true, "FAILED": true, "ACTION_GONE": true, "NOTIFICATION_GONE": true, "EXPIRED": true}[status] {
			return errors.New("latest_action_terminal is invalid")
		}
	}
	if raw, present := value["foreground_package"]; present {
		name, ok := raw.(string)
		if !ok || !map[string]bool{"none": true, "other": true, "co.twinotify.fixture": true, "com.twinotify.app": true}[name] {
			return errors.New("foreground_package is invalid")
		}
	}
	if raw, present := value["action_invocation_counts"]; present {
		counts, ok := raw.(map[string]any)
		allowed := map[string]bool{"PENDING": true, "DISPATCHED": true, "OUTCOME_UNKNOWN": true, "FAILED": true, "ACTION_GONE": true, "NOTIFICATION_GONE": true, "EXPIRED": true}
		if !ok || len(counts) != len(allowed) {
			return errors.New("action_invocation_counts is invalid")
		}
		for state, numberRaw := range counts {
			number, valid := numberRaw.(float64)
			if !allowed[state] || !valid || number < 0 || number > 1_000_000_000 || number != float64(int64(number)) {
				return errors.New("action_invocation_counts is invalid")
			}
		}
	}
	for key, maximum := range map[string]float64{
		"active_queue_count": 2_000, "peak_queue_count": 2_000,
		"active_queue_bytes": 134_217_728, "peak_queue_bytes": 134_217_728,
	} {
		if raw, present := value[key]; present {
			number, ok := raw.(float64)
			if !ok || number < 0 || number > maximum || number != float64(int64(number)) {
				return fmt.Errorf("%s must be a production-bounded nonnegative integer", key)
			}
		}
	}
	if raw, present := value["paired"]; present {
		if _, ok := raw.(bool); !ok {
			return errors.New("paired must be boolean")
		}
	}
	if raw, present := value["unpair_outcome"]; present {
		outcome, ok := raw.(string)
		allowed := map[string]bool{"none": true, "lan": true, "relay": true, "timeout": true, "unavailable": true, "delivery_failed": true, "no_peer": true}
		if !ok || !allowed[outcome] {
			return errors.New("unpair_outcome is invalid")
		}
	}
	if raw, present := value["custody_counts"]; present {
		routes, ok := raw.(map[string]any)
		if !ok || len(routes) != 2 {
			return errors.New("custody_counts must contain lan and relay")
		}
		events := map[string]bool{"notif_post": true, "notif_update": true, "notif_cancel": true, "call_state": true, "state_digest": true, "state_snapshot_begin": true, "state_snapshot_item": true, "state_snapshot_end": true, "unpair": true, "peer_receipt": true}
		for _, route := range []string{"lan", "relay"} {
			counts, ok := routes[route].(map[string]any)
			if !ok || len(counts) != len(events) {
				return fmt.Errorf("custody_counts.%s is invalid", route)
			}
			for event, rawCount := range counts {
				number, ok := rawCount.(float64)
				if !events[event] || !ok || number < 0 || number > 1_000_000_000 || number != float64(int64(number)) {
					return fmt.Errorf("custody_counts.%s.%s is invalid", route, event)
				}
			}
		}
	}
	return nil
}

func inspectEvidence(path string, value any) error {
	switch typed := value.(type) {
	case map[string]any:
		for key, nested := range typed {
			if sensitiveKeyPattern.MatchString(key) || sensitivePortKeyPattern.MatchString(key) {
				return fmt.Errorf("evidence field %q may not be persisted", join(path, key))
			}
			if err := inspectEvidence(join(path, key), nested); err != nil {
				return err
			}
		}
	case []any:
		for index, nested := range typed {
			if err := inspectEvidence(fmt.Sprintf("%s[%d]", path, index), nested); err != nil {
				return err
			}
		}
	case string:
		return inspectEvidenceString(path, typed)
	}
	return nil
}

func inspectEvidenceString(path, value string) error {
	if hexDigestPattern.MatchString(value) {
		return nil
	}
	for _, candidate := range sensitiveValuePatterns {
		if candidate.pattern.MatchString(value) {
			return fmt.Errorf("evidence at %q contains a %s", path, candidate.name)
		}
	}
	return nil
}

func join(path, key string) string {
	if path == "" {
		return key
	}
	return strings.Join([]string{path, key}, ".")
}
