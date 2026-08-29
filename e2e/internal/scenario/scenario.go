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

const (
	DefaultBurstCount = 256
	MinBurstCount     = 2
	MaxBurstCount     = 1000
)

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
	"lan-direct-reverse-delivery": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "B.shell.post:n1", Predicate: "A.tracked.sequence:1"},
		{Predicate: "B.custody.lan:notif_post:1"},
		{Predicate: "B.peer-receipt.delta:1"},
		{Predicate: "direct.terminal"},
	},
	"lan-direct-dismiss": {
		{Predicate: "A.route.lan"},
		{Action: "A.shell.post:n1"}, {Predicate: "B.mirror.active:n1"},
		{Action: "A.shell.cancel:n1"},
		{Predicate: "B.mirror.absent:n1"},
		{Predicate: "B.route.lan"},
		{Predicate: "terminal.converged"},
	},
	"lan-direct-update": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.shell.post:n1:v1", Predicate: "B.tracked.sequence:1"},
		{Action: "A.shell.post:n1:v2", Predicate: "B.tracked.sequence:2"},
		{Action: "A.shell.post:n1:v3", Predicate: "B.tracked.sequence:3"},
		{Predicate: "A.custody.lan:notif_post:1"},
		{Predicate: "A.custody.lan:notif_update:2"},
		{Predicate: "A.peer-receipt.delta:3"},
		{Predicate: "direct.terminal"},
	},
	"lan-direct-peer-dismiss": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.shell.post:n1:v1", Predicate: "B.tracked.sequence:1"},
		{Action: "B.control.dismiss-newest-mirror", Predicate: "B.user-dismiss.delta:1"},
		{Predicate: "A.tracked.cancelled"}, {Predicate: "B.tracked.cancelled"},
		{Predicate: "B.custody.lan:notif_cancel:1"},
		{Predicate: "B.peer-receipt.delta:1"},
		{Predicate: "direct.terminal"},
		{Predicate: "B.tracked.no-resurrection"},
	},
	"lan-direct-call-state": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.control.call-capture-enable", Predicate: "A.call-capture.enabled"},
		{Action: "A.control.call-state:ringing", Predicate: "B.call.semantic:RINGING"},
		{Action: "A.control.call-state:active", Predicate: "B.call.semantic:ACTIVE"},
		{Action: "A.control.call-state:idle", Predicate: "B.call.semantic:IDLE"},
		{Predicate: "A.custody.lan:call_state:3"},
		{Predicate: "A.peer-receipt.delta:3"},
		{Predicate: "direct.terminal"},
	},
	"lan-direct-snapshot-receipt": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.shell.post:n1:v1", Predicate: "B.tracked.sequence:1"},
		{Action: "A.control.emit-snapshot", Predicate: "B.snapshot.digest.delta:1"},
		{Action: "A.control.force-repair-snapshot", Predicate: "B.snapshot.begin.delta:1"},
		{Predicate: "B.snapshot.end.delta:1"}, {Predicate: "B.snapshot.commit.delta:1"},
		{Predicate: "A.custody.lan:state_digest:1"},
		{Predicate: "A.custody.lan:state_snapshot_begin:1"},
		{Predicate: "A.custody.lan:state_snapshot_item:1"},
		{Predicate: "A.custody.lan:state_snapshot_end:1"},
		{Predicate: "direct.terminal"},
	},
	"lan-relay-fallback-return": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.lan.fail"}, {Action: "B.lan.fail"},
		{Predicate: "A.route.relay"}, {Predicate: "B.route.relay"},
		{Action: "A.shell.post:n1-relay", Predicate: "B.tracked.sequence:1"},
		{Predicate: "A.custody.relay:notif_post:1"}, {Predicate: "A.peer-receipt.delta:1"},
		{Action: "A.lan.restore"}, {Action: "B.lan.restore"},
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.shell.post:n1-lan", Predicate: "B.tracked.sequence:1"},
		{Predicate: "A.custody.lan:notif_post:1"}, {Predicate: "A.peer-receipt.delta:2"},
		{Predicate: "direct.terminal"},
	},
	"lan-restart-persistence": {
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.shell.post:n1-before-a-restart", Predicate: "A.outbox.nonzero"},
		{Action: "A.force-stop"}, {Action: "A.restart"},
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Predicate: "B.tracked.sequence:1"},
		{Predicate: "A.custody.lan:notif_post:1"}, {Predicate: "A.peer-receipt.delta:1"},
		{Action: "B.force-stop"}, {Action: "B.restart"},
		{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"},
		{Action: "A.shell.post:n1-after-b-restart", Predicate: "B.tracked.sequence:1"},
		{Predicate: "A.custody.lan:notif_post:2"}, {Predicate: "A.peer-receipt.delta:2"},
		{Predicate: "direct.terminal"},
	},
	"expiry-snapshot": {
		{Action: "A.shell.post:n1"}, {Action: "relay.expire.mailbox"}, {Action: "relay.snapshot"},
		{Predicate: "B.mirror.active:n1"}, {Predicate: "terminal.converged"},
	},
}

func Plan(name string) (ScenarioPlan, error) {
	return PlanWithBurstCount(name, DefaultBurstCount)
}

func PlanWithBurstCount(name string, burstCount int) (ScenarioPlan, error) {
	if (name == "lan-direct-burst-backpressure" || name == "lan-direct-unpair-during-traffic" || name == "lan-product-correctness") &&
		(burstCount < MinBurstCount || burstCount > MaxBurstCount) {
		return ScenarioPlan{}, fmt.Errorf("burst count must be within %d..%d", MinBurstCount, MaxBurstCount)
	}
	if name == "all-correctness" {
		var steps []Step
		for _, child := range []string{"post", "update", "dismiss-origin", "offline", "ack-loss", "sender-offline-after-acceptance", "relay-restart", "sender-kill", "receiver-kill", "reboot", "expiry-snapshot"} {
			steps = append(steps, plans[child]...)
		}
		return ScenarioPlan{Name: name, Steps: steps}, nil
	}
	if plan, ok := notificationActionPlan(name); ok {
		return plan, nil
	}
	if name == "core-correctness" {
		children := make([]ScenarioPlan, 0, 5)
		for _, child := range []string{"post", "update", "dismiss-origin", "rapid-post-update-cancel", "offline"} {
			children = append(children, ScenarioPlan{Name: child, Steps: stepsWithTag(plans[child], "n-core-"+child)})
		}
		return ScenarioPlan{Name: name, Children: children}, nil
	}
	if name == "lan-direct-burst-backpressure" {
		steps := []Step{{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"}}
		for index := 0; index < burstCount; index++ {
			steps = append(steps, Step{Action: fmt.Sprintf("A.shell.post:burst-%04d", index+1)})
		}
		steps = append(steps,
			Step{Predicate: fmt.Sprintf("B.burst.unique:%d", burstCount)},
			Step{Predicate: fmt.Sprintf("A.custody.lan:notif_post:%d", burstCount)},
			Step{Predicate: fmt.Sprintf("A.peer-receipt.delta:%d", burstCount)},
			Step{Predicate: "A.queue.peak-bounded"},
			Step{Predicate: "direct.terminal"},
		)
		return ScenarioPlan{Name: name, Steps: steps}, nil
	}
	if name == "lan-direct-unpair-during-traffic" {
		steps := []Step{{Predicate: "A.route.lan"}, {Predicate: "B.route.lan"}, {Action: fmt.Sprintf("A.burst.start:%d", burstCount)}}
		steps = append(steps,
			Step{Predicate: "A.active-queue.nonzero"},
			Step{Action: "A.control.local-unpair"},
			Step{Predicate: "A.unpair.custody"},
			Step{Predicate: "B.unpair.inbound.delta:1"},
			Step{Predicate: "both.unpaired.stable"},
		)
		return ScenarioPlan{Name: name, Steps: steps}, nil
	}
	if name == "lan-product-correctness" {
		names := []string{
			"lan-direct-delivery", "lan-direct-reverse-delivery", "lan-direct-dismiss", "lan-direct-update",
			"lan-direct-peer-dismiss", "lan-direct-call-state", "lan-direct-snapshot-receipt",
			"lan-relay-fallback-return", "lan-restart-persistence",
			"lan-direct-burst-backpressure", "lan-direct-unpair-during-traffic",
		}
		children := make([]ScenarioPlan, 0, len(names))
		for _, childName := range names {
			child, err := PlanWithBurstCount(childName, burstCount)
			if err != nil {
				return ScenarioPlan{}, err
			}
			child.Steps = stepsWithTag(child.Steps, "n-"+childName)
			children = append(children, child)
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
	Health                         string                      `json:"health"`
	Transport                      string                      `json:"transport,omitempty"`
	CallCaptureEnabled             bool                        `json:"call_capture_enabled"`
	CallCaptureHealthCode          string                      `json:"call_capture_health_code,omitempty"`
	Outbox                         int                         `json:"outbox"`
	ActiveInbound                  int                         `json:"active_inbound"`
	PendingMaterialization         int                         `json:"pending_materialization"`
	Mirror                         bool                        `json:"mirror"`
	Sequence                       int                         `json:"sequence"`
	Terminal                       bool                        `json:"terminal"`
	LoopEvents                     int                         `json:"loop_events"`
	Route                          string                      `json:"route"`
	RoutePhase                     string                      `json:"route_phase"`
	QueuedBytes                    int64                       `json:"queued_bytes"`
	RouteGeneration                int                         `json:"route_generation"`
	ReceiptAtMs                    int64                       `json:"receipt_at_ms,omitempty"`
	ErrorCode                      string                      `json:"error_code,omitempty"`
	Paired                         bool                        `json:"paired"`
	CustodyCounts                  map[string]map[string]int64 `json:"custody_counts,omitempty"`
	PeerReceiptCount               int64                       `json:"peer_receipt_count"`
	SnapshotDigestCount            int64                       `json:"snapshot_digest_count"`
	SnapshotBeginCount             int64                       `json:"snapshot_begin_count"`
	SnapshotEndCount               int64                       `json:"snapshot_end_count"`
	SnapshotCommitCount            int64                       `json:"snapshot_commit_count"`
	UserDismissCount               int64                       `json:"user_dismiss_count"`
	UnpairInboundCount             int64                       `json:"unpair_inbound_count"`
	UnpairOutcome                  string                      `json:"unpair_outcome,omitempty"`
	ActiveQueueCount               int                         `json:"active_queue_count"`
	ActiveQueueBytes               int64                       `json:"active_queue_bytes"`
	PeakQueueCount                 int                         `json:"peak_queue_count"`
	PeakQueueBytes                 int64                       `json:"peak_queue_bytes"`
	Canonical                      map[string]string           `json:"-"`
	CanonicalSequences             map[string]int              `json:"-"`
	CanonicalSemanticStates        map[string]string           `json:"-"`
	CanonicalMaterializedSequences map[string]int              `json:"-"`
	CanonicalMirrorIdentityHashes  map[string]string           `json:"-"`
	CanonicalActionSetHashes       map[string]string           `json:"-"`
	FixtureAvailable               bool                        `json:"fixture_available,omitempty"`
	FixtureReplyCount              int64                       `json:"fixture_reply_count,omitempty"`
	FixtureMarkReadCount           int64                       `json:"fixture_mark_read_count,omitempty"`
	FixtureGeneration              int                         `json:"fixture_generation,omitempty"`
	FixtureStatus                  string                      `json:"fixture_status,omitempty"`
	ActionInvocationCounts         map[string]int64            `json:"action_invocation_counts,omitempty"`
	ActionExecutionClaimed         int64                       `json:"action_execution_claimed,omitempty"`
	ActionExecutionCompleted       int64                       `json:"action_execution_completed,omitempty"`
	DetailActive                   int64                       `json:"detail_active,omitempty"`
	DetailCancelled                int64                       `json:"detail_cancelled,omitempty"`
	LatestActionTerminal           string                      `json:"latest_action_terminal,omitempty"`
	ForegroundPackage              string                      `json:"foreground_package,omitempty"`
}

func ParseObservation(payload []byte) (Observation, error) {
	var root map[string]json.RawMessage
	if err := json.Unmarshal(payload, &root); err != nil {
		return Observation{}, fmt.Errorf("decode E2E state: %w", err)
	}
	allowedRoot := map[string]bool{
		"device_id_hash": true, "paired_peer_hash": true,
		"offline_pairing": true, "health": true, "route": true, "route_evidence": true,
		"outbox_bytes": true, "active_outbox": true, "active_inbound": true,
		"pending_materialization": true, "canonical": true, "activity": true,
		"product_observations": true, "notification_action_fixture": true,
		"notification_action_observations": true,
	}
	for key := range root {
		if !allowedRoot[key] {
			return Observation{}, fmt.Errorf("E2E state unknown field %q", key)
		}
	}
	for _, key := range []string{
		"offline_pairing", "health", "route", "route_evidence",
		"outbox_bytes", "active_outbox", "active_inbound", "pending_materialization",
		"canonical", "activity", "product_observations", "notification_action_fixture",
		"notification_action_observations",
	} {
		if raw, ok := root[key]; !ok || string(raw) == "null" {
			return Observation{}, fmt.Errorf("E2E state missing %s", key)
		}
	}
	for _, key := range []string{"device_id_hash", "paired_peer_hash"} {
		raw, ok := root[key]
		if !ok || string(raw) == "null" {
			continue
		}
		var value string
		if err := json.Unmarshal(raw, &value); err != nil || !validHash(value) {
			return Observation{}, fmt.Errorf("E2E state malformed %s", key)
		}
	}
	var raw struct {
		Health struct {
			Service               string `json:"service"`
			Transport             string `json:"transport"`
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
		OutboxBytes            int64             `json:"outbox_bytes"`
		ActiveOutbox           int               `json:"active_outbox"`
		ActiveInbound          int               `json:"active_inbound"`
		PendingMaterialization int               `json:"pending_materialization"`
		Canonical              []json.RawMessage `json:"canonical"`
		Activity               []struct {
			EventType string `json:"event_type"`
		} `json:"activity"`
		Product             json.RawMessage `json:"product_observations"`
		NotificationFixture json.RawMessage `json:"notification_action_fixture"`
		NotificationActions json.RawMessage `json:"notification_action_observations"`
	}
	if err := json.Unmarshal(payload, &raw); err != nil {
		return Observation{}, fmt.Errorf("decode E2E state: %w", err)
	}
	if raw.Health.Service == "" {
		return Observation{}, errors.New("E2E state missing health.service")
	}
	if !map[string]bool{"offline": true, "connecting": true, "online": true}[raw.Health.Transport] {
		return Observation{}, errors.New("E2E state malformed health.transport")
	}
	product, err := parseProductObservations(raw.Product)
	if err != nil {
		return Observation{}, err
	}
	fixture, err := parseNotificationFixture(raw.NotificationFixture)
	if err != nil {
		return Observation{}, err
	}
	actions, err := parseNotificationActionObservations(raw.NotificationActions)
	if err != nil {
		return Observation{}, err
	}
	state := Observation{
		Health:                         raw.Health.Service,
		Transport:                      raw.Health.Transport,
		CallCaptureEnabled:             raw.Health.CallCaptureEnabled,
		CallCaptureHealthCode:          raw.Health.CallCaptureHealthCode,
		Outbox:                         raw.ActiveOutbox,
		ActiveInbound:                  raw.ActiveInbound,
		PendingMaterialization:         raw.PendingMaterialization,
		Route:                          raw.RouteEvidence.Route,
		RoutePhase:                     raw.RouteEvidence.Phase,
		QueuedBytes:                    raw.RouteEvidence.QueuedBytes,
		RouteGeneration:                raw.RouteEvidence.Generation,
		ReceiptAtMs:                    raw.RouteEvidence.ReceiptAtMs,
		ErrorCode:                      raw.RouteEvidence.ErrorCode,
		Paired:                         product.Paired,
		CustodyCounts:                  product.CustodyCounts,
		PeerReceiptCount:               product.PeerReceiptCount,
		SnapshotDigestCount:            product.SnapshotDigestCount,
		SnapshotBeginCount:             product.SnapshotBeginCount,
		SnapshotEndCount:               product.SnapshotEndCount,
		SnapshotCommitCount:            product.SnapshotCommitCount,
		UserDismissCount:               product.UserDismissCount,
		UnpairInboundCount:             product.UnpairInboundCount,
		UnpairOutcome:                  product.UnpairOutcome,
		ActiveQueueCount:               product.ActiveQueueCount,
		ActiveQueueBytes:               product.ActiveQueueBytes,
		PeakQueueCount:                 product.PeakQueueCount,
		PeakQueueBytes:                 product.PeakQueueBytes,
		Terminal:                       raw.Health.Service == "connected" && raw.ActiveOutbox == 0 && raw.ActiveInbound == 0 && raw.PendingMaterialization == 0,
		Canonical:                      map[string]string{},
		CanonicalSequences:             map[string]int{},
		CanonicalSemanticStates:        map[string]string{},
		CanonicalMaterializedSequences: map[string]int{},
		CanonicalMirrorIdentityHashes:  map[string]string{},
		CanonicalActionSetHashes:       map[string]string{},
		FixtureAvailable:               fixture.Available, FixtureReplyCount: fixture.ReplyCount,
		FixtureMarkReadCount: fixture.MarkReadCount, FixtureGeneration: fixture.Generation,
		FixtureStatus: fixture.Status, ActionInvocationCounts: actions.InvocationCounts,
		ActionExecutionClaimed: actions.ExecutionClaimed, ActionExecutionCompleted: actions.ExecutionCompleted,
		DetailActive: actions.DetailActive, DetailCancelled: actions.DetailCancelled,
		LatestActionTerminal: actions.LatestTerminal,
	}
	for _, encoded := range raw.Canonical {
		item, err := parseCanonicalObservation(encoded)
		if err != nil {
			return Observation{}, err
		}
		state.Canonical[item.CanonIDHash] = item.State
		state.CanonicalSequences[item.CanonIDHash] = item.Sequence
		state.CanonicalMaterializedSequences[item.CanonIDHash] = item.Materialized
		if item.MirrorIdentityHash != "" {
			state.CanonicalMirrorIdentityHashes[item.CanonIDHash] = item.MirrorIdentityHash
		}
		if item.ActionSetHash != "" {
			state.CanonicalActionSetHashes[item.CanonIDHash] = item.ActionSetHash
		}
		if item.SemanticState != "" {
			state.CanonicalSemanticStates[item.CanonIDHash] = item.SemanticState
		}
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

type canonicalObservation struct {
	CanonIDHash        string  `json:"canon_id_hash"`
	State              string  `json:"state"`
	Sequence           int     `json:"sequence"`
	Materialized       int     `json:"materialized_sequence"`
	SemanticStateRaw   *string `json:"semantic_state,omitempty"`
	SemanticState      string  `json:"-"`
	MirrorIdentityHash string  `json:"mirror_identity_hash,omitempty"`
	ActionSetHash      string  `json:"action_set_hash,omitempty"`
}

var canonicalHashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func parseCanonicalObservation(payload json.RawMessage) (canonicalObservation, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(payload, &fields); err != nil {
		return canonicalObservation{}, errors.New("E2E state malformed canonical observation")
	}
	allowed := map[string]bool{
		"canon_id_hash": true, "state": true, "sequence": true,
		"materialized_sequence": true, "semantic_state": true,
		"mirror_identity_hash": true, "action_set_hash": true,
	}
	for key := range fields {
		if !allowed[key] {
			return canonicalObservation{}, fmt.Errorf("E2E state unknown canonical observation %q", key)
		}
	}
	for _, key := range []string{"canon_id_hash", "state", "sequence", "materialized_sequence"} {
		if value, ok := fields[key]; !ok || string(value) == "null" {
			return canonicalObservation{}, fmt.Errorf("E2E state missing canonical observation %s", key)
		}
	}
	var item canonicalObservation
	if err := json.Unmarshal(payload, &item); err != nil ||
		!canonicalHashPattern.MatchString(item.CanonIDHash) ||
		!map[string]bool{"ACTIVE": true, "CANCELLED": true}[item.State] ||
		item.Sequence <= 0 || item.Sequence > 1_000_000_000 ||
		item.Materialized < 0 || item.Materialized > item.Sequence {
		return canonicalObservation{}, errors.New("E2E state malformed canonical observation")
	}
	if (item.MirrorIdentityHash != "" && !canonicalHashPattern.MatchString(item.MirrorIdentityHash)) ||
		(item.ActionSetHash != "" && !canonicalHashPattern.MatchString(item.ActionSetHash)) {
		return canonicalObservation{}, errors.New("E2E state malformed canonical action hashes")
	}
	if item.SemanticStateRaw != nil {
		semantic := *item.SemanticStateRaw
		valid := (item.State == "ACTIVE" && (semantic == "RINGING" || semantic == "ACTIVE")) ||
			(item.State == "CANCELLED" && semantic == "IDLE")
		if !valid {
			return canonicalObservation{}, errors.New("E2E state malformed canonical semantic_state")
		}
		item.SemanticState = semantic
	}
	return item, nil
}

type notificationFixtureObservation struct {
	Available                 bool
	ReplyCount, MarkReadCount int64
	Generation                int
	Status                    string
}

func parseNotificationFixture(payload []byte) (notificationFixtureObservation, error) {
	var fields map[string]json.RawMessage
	if json.Unmarshal(payload, &fields) != nil || len(fields) != 5 {
		return notificationFixtureObservation{}, errors.New("E2E state malformed notification_action_fixture")
	}
	allowed := map[string]bool{"available": true, "reply_dispatch_count": true, "mark_read_dispatch_count": true, "last_fixture_generation": true, "last_terminal_status": true}
	for key, value := range fields {
		if !allowed[key] || string(value) == "null" {
			return notificationFixtureObservation{}, errors.New("E2E state malformed notification_action_fixture")
		}
	}
	var raw struct {
		Available  bool   `json:"available"`
		Reply      int64  `json:"reply_dispatch_count"`
		Mark       int64  `json:"mark_read_dispatch_count"`
		Generation int    `json:"last_fixture_generation"`
		Status     string `json:"last_terminal_status"`
	}
	if json.Unmarshal(payload, &raw) != nil || raw.Reply < 0 || raw.Reply > 1_000_000_000 || raw.Mark < 0 || raw.Mark > 1_000_000_000 || raw.Generation < 0 || raw.Generation > 1_000_000_000 || !map[string]bool{"none": true, "posted": true, "updated": true, "cancelled": true, "counters_reset": true, "reply_dispatched": true, "mark_read_dispatched": true}[raw.Status] {
		return notificationFixtureObservation{}, errors.New("E2E state malformed notification_action_fixture")
	}
	return notificationFixtureObservation{raw.Available, raw.Reply, raw.Mark, raw.Generation, raw.Status}, nil
}

type notificationActionObservation struct {
	InvocationCounts                                                    map[string]int64
	ExecutionClaimed, ExecutionCompleted, DetailActive, DetailCancelled int64
	LatestTerminal                                                      string
}

func parseNotificationActionObservations(payload []byte) (notificationActionObservation, error) {
	keys := map[string]string{"invocation_pending": "PENDING", "invocation_dispatched": "DISPATCHED", "invocation_outcome_unknown": "OUTCOME_UNKNOWN", "invocation_failed": "FAILED", "invocation_action_gone": "ACTION_GONE", "invocation_notification_gone": "NOTIFICATION_GONE", "invocation_expired": "EXPIRED"}
	allowed := map[string]bool{"execution_claimed": true, "execution_completed": true, "detail_active": true, "detail_cancelled": true, "latest_terminal_status": true}
	for key := range keys {
		allowed[key] = true
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(payload, &fields) != nil || len(fields) != len(allowed) {
		return notificationActionObservation{}, errors.New("E2E state malformed notification_action_observations")
	}
	read := func(key string) (int64, bool) {
		var v int64
		raw, ok := fields[key]
		return v, ok && string(raw) != "null" && json.Unmarshal(raw, &v) == nil && v >= 0 && v <= 1_000_000_000
	}
	counts := map[string]int64{}
	for key, state := range keys {
		value, ok := read(key)
		if !ok {
			return notificationActionObservation{}, errors.New("E2E state malformed notification_action_observations")
		}
		counts[state] = value
	}
	claimed, ok1 := read("execution_claimed")
	completed, ok2 := read("execution_completed")
	active, ok3 := read("detail_active")
	cancelled, ok4 := read("detail_cancelled")
	var terminal string
	ok5 := json.Unmarshal(fields["latest_terminal_status"], &terminal) == nil && map[string]bool{"none": true, "PENDING": true, "DISPATCHED": true, "OUTCOME_UNKNOWN": true, "FAILED": true, "ACTION_GONE": true, "NOTIFICATION_GONE": true, "EXPIRED": true}[terminal]
	for key := range fields {
		if !allowed[key] {
			return notificationActionObservation{}, errors.New("E2E state malformed notification_action_observations")
		}
	}
	if !ok1 || !ok2 || !ok3 || !ok4 || !ok5 {
		return notificationActionObservation{}, errors.New("E2E state malformed notification_action_observations")
	}
	return notificationActionObservation{counts, claimed, completed, active, cancelled, terminal}, nil
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
	SnapshotCommitCount int64
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
		"snapshot_commit_count": true,
		"user_dismiss_count":    true, "unpair_inbound_count": true, "unpair_outcome": true,
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
	commits, err := readInt("snapshot_commit_count", 1_000_000_000)
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
		SnapshotCommitCount: commits,
		UserDismissCount:    dismisses, UnpairInboundCount: unpairs, UnpairOutcome: outcome,
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
