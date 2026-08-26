package scenario

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

type Step struct {
	Action    string
	Predicate string
}

type ScenarioPlan struct {
	Name     string
	Steps    []Step
	Children []ScenarioPlan
}

func (p ScenarioPlan) Actions() []string {
	result := make([]string, 0, len(p.Steps))
	for _, child := range p.Children {
		result = append(result, child.Actions()...)
	}
	for _, step := range p.Steps {
		if step.Action != "" {
			result = append(result, step.Action)
		}
	}
	return result
}

var plans = map[string][]Step{
	"post": {
		{Action: "A.shell.post:n1", Predicate: "A.outbox.nonzero"},
		{Predicate: "B.mirror.active:n1"},
		{Predicate: "A.outbox.zero"},
	},
	"update": {
		{Action: "A.shell.post:n1:v1"}, {Action: "A.shell.post:n1:v2"}, {Action: "A.shell.post:n1:v3"},
		{Predicate: "B.mirror.sequence:3"},
		{Predicate: "A.outbox.zero"},
	},
	"dismiss-origin": {
		{Action: "A.shell.post:n1"}, {Predicate: "B.mirror.active:n1"},
		{Action: "A.shell.cancel:n1", Predicate: "A.outbox.nonzero"},
		{Predicate: "B.mirror.absent:n1"},
		{Predicate: "A.outbox.zero"},
	},
	"dismiss-peer": {
		{Action: "A.shell.post:n1"}, {Predicate: "B.mirror.active:n1"},
		{Action: "B.ui.xml.find-mirror:n1"}, {Action: "B.ui.swipe-dismiss:n1", Predicate: "B.user-dismiss.reason"},
		{Predicate: "A.source.absent:n1"},
		{Predicate: "B.mirror.absent:n1"}, {Predicate: "A.outbox.zero"},
	},
	"rapid-post-update-cancel": {
		{Action: "A.shell.post:n1:v1"}, {Action: "A.shell.post:n1:v2"}, {Action: "A.shell.cancel:n1"},
		{Predicate: "B.no-resurrection:n1"},
		{Predicate: "A.outbox.zero"},
	},
	"offline": {
		{Action: "B.network.off", Predicate: "B.health.offline"},
		{Action: "A.shell.post:n1", Predicate: "A.outbox.nonzero"},
		{Action: "B.network.on", Predicate: "B.health.connected"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "A.outbox.zero"}, {Predicate: "terminal.converged"},
	},
	"relay-restart": {
		{Action: "A.shell.post:n1"}, {Action: "relay.sigterm"}, {Action: "relay.restart.same-db"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "A.outbox.zero"}, {Predicate: "terminal.converged"},
	},
	"sender-kill": {
		{Action: "A.shell.post:n1"}, {Action: "A.force-stop"}, {Action: "A.restart"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "A.outbox.zero"}, {Predicate: "terminal.converged"},
	},
	"receiver-kill": {
		{Action: "A.shell.post:n1"}, {Action: "B.force-stop"}, {Action: "B.restart"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "A.outbox.zero"}, {Predicate: "terminal.converged"},
	},
	"reboot": {
		{Action: "A.shell.post:n1"}, {Action: "B.reboot"}, {Action: "B.listener.rebind"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "A.outbox.zero"}, {Predicate: "terminal.converged"},
	},
	"ack-loss": {
		{Action: "A.shell.post:n1"}, {Action: "relay.drop.receipt:n1"}, {Predicate: "B.mirror.active:n1"},
		{Action: "A.reconcile", Predicate: "A.outbox.zero"}, {Predicate: "B.mirror.active:n1"}, {Predicate: "terminal.converged"},
	},
	"sender-offline-after-acceptance": {
		{Action: "A.shell.post:n1"}, {Action: "relay.accepted:n1"}, {Action: "A.network.off"},
		{Action: "A.network.on"}, {Action: "A.reconcile", Predicate: "terminal.converged"},
	},
	// Direct delivery proves the notification arrived AND that the direct route
	// carried it. Asserting only the mirror would pass just as well over relay.
	"lan-direct-delivery": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.shell.post:n1", Predicate: "A.outbox.nonzero"},
		{Predicate: "B.mirror.active:n1"},
		{Predicate: "A.outbox.zero"},
		{Predicate: "B.route.lan"},
		{Predicate: "terminal.converged"},
	},
	"lan-direct-dismiss": {
		{Predicate: "A.route.lan"},
		{Action: "A.shell.post:n1"}, {Predicate: "B.mirror.active:n1"},
		{Action: "A.shell.cancel:n1"},
		{Predicate: "B.mirror.absent:n1"},
		{Predicate: "B.route.lan"},
		{Predicate: "terminal.converged"},
	},
	"lan-relay-fallback-return": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.lan.fail"}, {Action: "B.lan.fail"},
		{Predicate: "A.route.relay"}, {Predicate: "B.route.relay"},
		{Action: "A.shell.post:n1"}, {Predicate: "B.mirror.active:n1"}, {Predicate: "A.outbox.zero"},
		{Action: "A.lan.restore"}, {Action: "B.lan.restore"},
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"}, {Predicate: "terminal.converged"},
	},
	"lan-restart-persistence": {
		{Predicate: "A.route.lan"},
		{Action: "A.shell.post:n1"}, {Action: "A.force-stop"}, {Action: "A.restart"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "A.outbox.zero"},
		{Predicate: "terminal.converged"},
	},
	"expiry-snapshot": {
		{Action: "A.shell.post:n1"}, {Action: "relay.expire.mailbox"}, {Action: "relay.snapshot"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "terminal.converged"},
	},
}

func Plan(name string) (ScenarioPlan, error) {
	if name == "call-state" {
		// This scenario uses the typed synthetic control flow in call_state.go rather than
		// the notification shell plans below.
		return ScenarioPlan{Name: name}, nil
	}
	if name == "all-correctness" {
		var steps []Step
		for _, child := range []string{"post", "update", "dismiss-origin", "offline", "ack-loss", "sender-offline-after-acceptance", "relay-restart", "sender-kill", "receiver-kill", "reboot", "expiry-snapshot"} {
			steps = append(steps, plans[child]...)
		}
		return ScenarioPlan{Name: name, Steps: steps}, nil
	}
	if name == "core-correctness" {
		children := make([]ScenarioPlan, 0, 5)
		for _, child := range []string{"post", "update", "dismiss-origin", "rapid-post-update-cancel", "offline"} {
			children = append(children, ScenarioPlan{Name: child, Steps: stepsWithTag(plans[child], "n-core-"+child)})
		}
		return ScenarioPlan{Name: name, Children: children}, nil
	}
	steps, ok := plans[name]
	if !ok {
		return ScenarioPlan{}, fmt.Errorf("unknown E2E scenario %q", name)
	}
	copySteps := append([]Step(nil), steps...)
	return ScenarioPlan{Name: name, Steps: copySteps}, nil
}

func stepsWithTag(steps []Step, tag string) []Step {
	result := make([]Step, len(steps))
	for index, step := range steps {
		result[index] = Step{
			Action:    strings.ReplaceAll(step.Action, "n1", tag),
			Predicate: strings.ReplaceAll(step.Predicate, "n1", tag),
		}
	}
	return result
}

type Observation struct {
	Health                 string                      `json:"health"`
	CallCaptureEnabled     bool                        `json:"call_capture_enabled"`
	CallCaptureHealthCode  string                      `json:"call_capture_health_code,omitempty"`
	Outbox                 int                         `json:"outbox"`
	ActiveInbound          int                         `json:"active_inbound"`
	PendingMaterialization int                         `json:"pending_materialization"`
	Mirror                 bool                        `json:"mirror"`
	Sequence               int                         `json:"sequence"`
	Terminal               bool                        `json:"terminal"`
	LoopEvents             int                         `json:"loop_events"`
	Route                  string                      `json:"route"`
	RoutePhase             string                      `json:"route_phase"`
	QueuedBytes            int64                       `json:"queued_bytes"`
	RouteGeneration        int                         `json:"route_generation"`
	ReceiptAtMs            int64                       `json:"receipt_at_ms,omitempty"`
	ErrorCode              string                      `json:"error_code,omitempty"`
	Paired                 bool                        `json:"paired"`
	CustodyCounts          map[string]map[string]int64 `json:"custody_counts,omitempty"`
	PeerReceiptCount       int64                       `json:"peer_receipt_count"`
	SnapshotDigestCount    int64                       `json:"snapshot_digest_count"`
	SnapshotBeginCount     int64                       `json:"snapshot_begin_count"`
	SnapshotEndCount       int64                       `json:"snapshot_end_count"`
	UserDismissCount       int64                       `json:"user_dismiss_count"`
	UnpairInboundCount     int64                       `json:"unpair_inbound_count"`
	UnpairOutcome          string                      `json:"unpair_outcome,omitempty"`
	ActiveQueueCount       int                         `json:"active_queue_count"`
	ActiveQueueBytes       int64                       `json:"active_queue_bytes"`
	PeakQueueCount         int                         `json:"peak_queue_count"`
	PeakQueueBytes         int64                       `json:"peak_queue_bytes"`
	Canonical              map[string]string           `json:"-"`
	CanonicalSequences     map[string]int              `json:"-"`
}

func ParseObservation(payload []byte) (Observation, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(payload, &root); err != nil {
		return Observation{}, fmt.Errorf("decode E2E state: %w", err)
	}
	allowedRoot := map[string]bool{
		"offline_pairing": true, "health": true, "route": true, "route_evidence": true,
		"outbox_bytes": true, "active_outbox": true, "active_inbound": true,
		"pending_materialization": true, "canonical": true, "activity": true,
		"product_observations": true,
	}
	for key := range root {
		if !allowedRoot[key] {
			return Observation{}, fmt.Errorf("E2E state unknown field %q", key)
		}
	}
	for key := range allowedRoot {
		if raw, ok := root[key]; !ok || string(raw) == "null" {
			return Observation{}, fmt.Errorf("E2E state missing %s", key)
		}
	}
	var raw struct {
		Health struct {
			Service               string `json:"service"`
			CallCaptureEnabled    bool   `json:"callCaptureEnabled"`
			CallCaptureHealthCode string `json:"callCaptureHealthCode"`
		} `json:"health"`
		Route struct {
			Route string `json:"route"`
			Phase string `json:"phase"`
		} `json:"route"`
		RouteEvidence struct {
			Route       string `json:"route"`
			Phase       string `json:"phase"`
			Generation  int    `json:"route_generation"`
			QueuedCount int    `json:"queued_count"`
			QueuedBytes int64  `json:"queued_bytes"`
			ReceiptAtMs int64  `json:"receipt_at_ms"`
			ErrorCode   string `json:"error_code"`
		} `json:"route_evidence"`
		OutboxBytes            int64 `json:"outbox_bytes"`
		ActiveOutbox           int   `json:"active_outbox"`
		ActiveInbound          int   `json:"active_inbound"`
		PendingMaterialization int   `json:"pending_materialization"`
		Canonical              []struct {
			CanonIDHash string `json:"canon_id_hash"`
			State       string `json:"state"`
			Sequence    int    `json:"sequence"`
		} `json:"canonical"`
		Activity []struct {
			EventType string `json:"event_type"`
		} `json:"activity"`
		Product json.RawMessage `json:"product_observations"`
	}
	if err := json.Unmarshal(payload, &raw); err != nil {
		return Observation{}, fmt.Errorf("decode E2E state: %w", err)
	}
	if raw.Health.Service == "" {
		return Observation{}, errors.New("E2E state missing health.service")
	}
	product, err := parseProductObservations(raw.Product)
	if err != nil {
		return Observation{}, err
	}
	state := Observation{
		Health:                 raw.Health.Service,
		CallCaptureEnabled:     raw.Health.CallCaptureEnabled,
		CallCaptureHealthCode:  raw.Health.CallCaptureHealthCode,
		Outbox:                 raw.ActiveOutbox,
		ActiveInbound:          raw.ActiveInbound,
		PendingMaterialization: raw.PendingMaterialization,
		Route:                  raw.RouteEvidence.Route,
		RoutePhase:             raw.RouteEvidence.Phase,
		QueuedBytes:            raw.RouteEvidence.QueuedBytes,
		RouteGeneration:        raw.RouteEvidence.Generation,
		ReceiptAtMs:            raw.RouteEvidence.ReceiptAtMs,
		ErrorCode:              raw.RouteEvidence.ErrorCode,
		Paired:                 product.Paired,
		CustodyCounts:          product.CustodyCounts,
		PeerReceiptCount:       product.PeerReceiptCount,
		SnapshotDigestCount:    product.SnapshotDigestCount,
		SnapshotBeginCount:     product.SnapshotBeginCount,
		SnapshotEndCount:       product.SnapshotEndCount,
		UserDismissCount:       product.UserDismissCount,
		UnpairInboundCount:     product.UnpairInboundCount,
		UnpairOutcome:          product.UnpairOutcome,
		ActiveQueueCount:       product.ActiveQueueCount,
		ActiveQueueBytes:       product.ActiveQueueBytes,
		PeakQueueCount:         product.PeakQueueCount,
		PeakQueueBytes:         product.PeakQueueBytes,
		Terminal:               raw.Health.Service == "connected" && raw.ActiveOutbox == 0 && raw.ActiveInbound == 0 && raw.PendingMaterialization == 0,
		Canonical:              map[string]string{},
		CanonicalSequences:     map[string]int{},
	}
	for _, item := range raw.Canonical {
		state.Canonical[item.CanonIDHash] = item.State
		state.CanonicalSequences[item.CanonIDHash] = item.Sequence
		if item.State == "ACTIVE" {
			state.Mirror = true
		}
		if item.Sequence > state.Sequence {
			state.Sequence = item.Sequence
		}
	}
	for _, event := range raw.Activity {
		if strings.Contains(strings.ToLower(event.EventType), "loop") {
			state.LoopEvents++
		}
	}
	return state, nil
}

var productEventKeys = map[string]bool{
	"notif_post": true, "notif_update": true, "notif_cancel": true, "call_state": true,
	"state_digest": true, "state_snapshot_begin": true, "state_snapshot_item": true,
	"state_snapshot_end": true, "unpair": true, "peer_receipt": true,
}

type productObservations struct {
	Paired              bool
	CustodyCounts       map[string]map[string]int64
	PeerReceiptCount    int64
	SnapshotDigestCount int64
	SnapshotBeginCount  int64
	SnapshotEndCount    int64
	UserDismissCount    int64
	UnpairInboundCount  int64
	UnpairOutcome       string
	ActiveQueueCount    int
	ActiveQueueBytes    int64
	PeakQueueCount      int
	PeakQueueBytes      int64
}

func parseProductObservations(payload []byte) (productObservations, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(payload, &fields); err != nil {
		return productObservations{}, errors.New("E2E state malformed product_observations")
	}
	allowed := map[string]bool{
		"paired": true, "custody_counts": true, "peer_receipt_count": true,
		"snapshot_digest_count": true, "snapshot_begin_count": true, "snapshot_end_count": true,
		"user_dismiss_count": true, "unpair_inbound_count": true, "unpair_outcome": true,
		"active_queue_count": true, "active_queue_bytes": true, "peak_queue_count": true,
		"peak_queue_bytes": true,
	}
	for key := range fields {
		if !allowed[key] {
			return productObservations{}, fmt.Errorf("E2E state unknown product observation %q", key)
		}
	}
	for key := range allowed {
		if raw, ok := fields[key]; !ok || string(raw) == "null" {
			return productObservations{}, fmt.Errorf("E2E state missing product observation %s", key)
		}
	}
	var paired bool
	if err := json.Unmarshal(fields["paired"], &paired); err != nil {
		return productObservations{}, errors.New("E2E state malformed paired")
	}
	readInt := func(key string, maximum int64) (int64, error) {
		var value int64
		if err := json.Unmarshal(fields[key], &value); err != nil || value < 0 || value > maximum {
			return 0, fmt.Errorf("E2E state malformed %s", key)
		}
		return value, nil
	}
	peerReceipts, err := readInt("peer_receipt_count", 1_000_000_000)
	if err != nil {
		return productObservations{}, err
	}
	digests, err := readInt("snapshot_digest_count", 1_000_000_000)
	if err != nil {
		return productObservations{}, err
	}
	begins, err := readInt("snapshot_begin_count", 1_000_000_000)
	if err != nil {
		return productObservations{}, err
	}
	ends, err := readInt("snapshot_end_count", 1_000_000_000)
	if err != nil {
		return productObservations{}, err
	}
	dismisses, err := readInt("user_dismiss_count", 1_000_000_000)
	if err != nil {
		return productObservations{}, err
	}
	unpairs, err := readInt("unpair_inbound_count", 1_000_000_000)
	if err != nil {
		return productObservations{}, err
	}
	activeCount, err := readInt("active_queue_count", 2_000)
	if err != nil {
		return productObservations{}, err
	}
	activeBytes, err := readInt("active_queue_bytes", 134_217_728)
	if err != nil {
		return productObservations{}, err
	}
	peakCount, err := readInt("peak_queue_count", 2_000)
	if err != nil {
		return productObservations{}, err
	}
	peakBytes, err := readInt("peak_queue_bytes", 134_217_728)
	if err != nil {
		return productObservations{}, err
	}
	var outcome string
	if err := json.Unmarshal(fields["unpair_outcome"], &outcome); err != nil || !map[string]bool{"none": true, "lan": true, "relay": true, "timeout": true, "unavailable": true, "delivery_failed": true, "no_peer": true}[outcome] {
		return productObservations{}, errors.New("E2E state malformed unpair_outcome")
	}
	var custodyRaw map[string]map[string]json.RawMessage
	if err := json.Unmarshal(fields["custody_counts"], &custodyRaw); err != nil || len(custodyRaw) != 2 {
		return productObservations{}, errors.New("E2E state malformed custody_counts")
	}
	custody := map[string]map[string]int64{}
	for _, route := range []string{"lan", "relay"} {
		counts, ok := custodyRaw[route]
		if !ok || len(counts) != len(productEventKeys) {
			return productObservations{}, fmt.Errorf("E2E state malformed custody_counts.%s", route)
		}
		custody[route] = map[string]int64{}
		for key := range counts {
			if !productEventKeys[key] {
				return productObservations{}, fmt.Errorf("E2E state unknown custody event %q", key)
			}
		}
		for key := range productEventKeys {
			raw, ok := counts[key]
			var value int64
			if !ok || string(raw) == "null" || json.Unmarshal(raw, &value) != nil || value < 0 || value > 1_000_000_000 {
				return productObservations{}, fmt.Errorf("E2E state malformed custody_counts.%s.%s", route, key)
			}
			custody[route][key] = value
		}
	}
	return productObservations{
		Paired: paired, CustodyCounts: custody, PeerReceiptCount: peerReceipts,
		SnapshotDigestCount: digests, SnapshotBeginCount: begins, SnapshotEndCount: ends,
		UserDismissCount: dismisses, UnpairInboundCount: unpairs, UnpairOutcome: outcome,
		ActiveQueueCount: int(activeCount), ActiveQueueBytes: activeBytes,
		PeakQueueCount: int(peakCount), PeakQueueBytes: peakBytes,
	}, nil
}

func AssertConverged(name string, state Observation) error {
	if !state.Terminal || state.Outbox != 0 || state.LoopEvents != 0 {
		return fmt.Errorf("%s did not converge: %+v", name, state)
	}
	if name == "post" || name == "update" {
		if !state.Mirror || (name == "update" && state.Sequence != 3) {
			return fmt.Errorf("%s mirror oracle failed: %+v", name, state)
		}
	} else if state.Mirror {
		return fmt.Errorf("%s left a visible mirror: %+v", name, state)
	}
	return nil
}

func Eventually(ctx context.Context, interval, timeout time.Duration, predicate func() (bool, error)) error {
	if interval <= 0 || timeout <= 0 {
		return errors.New("eventual interval and timeout must be positive")
	}
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		ok, err := predicate()
		if err != nil {
			return err
		}
		if ok {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return fmt.Errorf("eventual assertion timed out after %s", timeout)
		case <-ticker.C:
		}
	}
}

type FailureArtifact struct {
	RelayLog  string
	HealthA   string
	HealthB   string
	State     string
	DumpsysA  string
	DumpsysB  string
	Processes string
	Timeline  []string
}

func WriteFailureArtifact(root, scenario string, artifact FailureArtifact) (string, error) {
	if strings.TrimSpace(root) == "" || strings.TrimSpace(scenario) == "" {
		return "", errors.New("artifact root and scenario are required")
	}
	dir := filepath.Join(root, "scenario-"+safeName(scenario))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	files := map[string]string{
		"relay.log": artifact.RelayLog, "health-a.json": artifact.HealthA, "health-b.json": artifact.HealthB,
		"state.json": artifact.State, "dumpsys-a.txt": artifact.DumpsysA, "dumpsys-b.txt": artifact.DumpsysB,
		"processes.txt": artifact.Processes, "timeline.json": marshalTimeline(artifact.Timeline),
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(Sanitize(content)), 0o600); err != nil {
			return "", err
		}
	}
	return dir, nil
}

var secretPattern = regexp.MustCompile(`(?i)(jwt|token|ciphertext|nonce|private[_ -]?key|secret)\s*[:=]\s*[^,\s}]+`)
var notificationContentPattern = regexp.MustCompile(`(?im)^\s*[^\n]*(title|text|extras|payload|android\.text|android\.title)[^\n]*$`)

func Sanitize(value string) string {
	value = notificationContentPattern.ReplaceAllString(value, "<notification-content-redacted>")
	value = secretPattern.ReplaceAllString(value, `$1:<redacted>`)
	return value
}

func marshalTimeline(timeline []string) string {
	encoded, _ := json.Marshal(timeline)
	return string(encoded)
}

func safeName(name string) string {
	return regexp.MustCompile(`[^A-Za-z0-9._-]`).ReplaceAllString(name, "_")
}
