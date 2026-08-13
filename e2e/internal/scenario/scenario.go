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
	Name  string
	Steps []Step
}

func (p ScenarioPlan) Actions() []string {
	result := make([]string, 0, len(p.Steps))
	for _, step := range p.Steps {
		result = append(result, step.Action)
	}
	return result
}

var plans = map[string][]Step{
	"post": {
		{Action: "A.shell.post:n1", Predicate: "A.outbox.nonzero"},
		{Action: "B.health.connected", Predicate: "B.mirror.active:n1"},
		{Action: "A.receipt.accepted:n1", Predicate: "A.outbox.zero"},
	},
	"update": {
		{Action: "A.shell.post:n1:v1"}, {Action: "A.shell.post:n1:v2"}, {Action: "A.shell.post:n1:v3"},
		{Action: "B.mirror.active:n1", Predicate: "B.mirror.sequence:3"},
		{Action: "A.receipt.accepted:n1", Predicate: "A.outbox.zero"},
	},
	"dismiss-origin": {
		{Action: "A.shell.post:n1"}, {Action: "B.mirror.active:n1"},
		{Action: "A.shell.cancel:n1", Predicate: "A.outbox.nonzero"},
		{Action: "B.mirror.dismissed:n1", Predicate: "B.mirror.absent:n1"},
		{Action: "A.receipt.accepted:n1", Predicate: "A.outbox.zero"},
	},
	"dismiss-peer": {
		{Action: "A.shell.post:n1"}, {Action: "B.mirror.active:n1"},
		{Action: "B.ui.xml.find-mirror:n1"}, {Action: "B.ui.swipe-dismiss:n1", Predicate: "B.user-dismiss.reason"},
		{Action: "A.source.dismissed:n1", Predicate: "A.source.absent:n1"},
		{Action: "B.mirror.absent:n1", Predicate: "A.outbox.zero"},
	},
	"rapid-post-update-cancel": {
		{Action: "A.shell.post:n1:v1"}, {Action: "A.shell.post:n1:v2"}, {Action: "A.shell.cancel:n1"},
		{Action: "B.mirror.absent:n1", Predicate: "B.no-resurrection:n1"},
		{Action: "A.receipt.accepted:n1", Predicate: "A.outbox.zero"},
	},
	"offline": {
		{Action: "B.network.off"}, {Action: "B.health.offline"},
		{Action: "A.shell.post:n1"}, {Action: "A.outbox.nonzero"},
		{Action: "B.network.on"}, {Action: "B.health.connected"},
		{Action: "B.mirror.active:n1"}, {Action: "A.outbox.zero", Predicate: "terminal.converged"},
	},
	"relay-restart": {
		{Action: "A.shell.post:n1"}, {Action: "relay.sigterm"}, {Action: "relay.restart.same-db"},
		{Action: "B.mirror.active:n1"}, {Action: "A.outbox.zero", Predicate: "terminal.converged"},
	},
	"sender-kill": {
		{Action: "A.shell.post:n1"}, {Action: "A.force-stop"}, {Action: "A.restart"},
		{Action: "B.mirror.active:n1"}, {Action: "A.outbox.zero", Predicate: "terminal.converged"},
	},
	"receiver-kill": {
		{Action: "A.shell.post:n1"}, {Action: "B.force-stop"}, {Action: "B.restart"},
		{Action: "B.mirror.active:n1"}, {Action: "A.outbox.zero", Predicate: "terminal.converged"},
	},
	"reboot": {
		{Action: "A.shell.post:n1"}, {Action: "B.reboot"}, {Action: "B.listener.rebind"},
		{Action: "B.mirror.active:n1"}, {Action: "A.outbox.zero", Predicate: "terminal.converged"},
	},
	"ack-loss": {
		{Action: "A.shell.post:n1"}, {Action: "relay.drop.receipt:n1"}, {Action: "B.mirror.active:n1"},
		{Action: "A.reconcile", Predicate: "A.outbox.zero"}, {Action: "B.mirror.active:n1", Predicate: "terminal.converged"},
	},
	"sender-offline-after-acceptance": {
		{Action: "A.shell.post:n1"}, {Action: "relay.accepted:n1"}, {Action: "A.network.off"},
		{Action: "A.network.on"}, {Action: "A.reconcile", Predicate: "terminal.converged"},
	},
	"expiry-snapshot": {
		{Action: "A.shell.post:n1"}, {Action: "relay.expire.mailbox"}, {Action: "relay.snapshot"},
		{Action: "B.mirror.active:n1", Predicate: "terminal.converged"},
	},
}

func Plan(name string) (ScenarioPlan, error) {
	if name == "all-correctness" {
		var steps []Step
		for _, child := range []string{"post", "update", "dismiss-origin", "offline", "ack-loss", "sender-offline-after-acceptance", "relay-restart", "sender-kill", "receiver-kill", "reboot", "expiry-snapshot"} {
			steps = append(steps, plans[child]...)
		}
		return ScenarioPlan{Name: name, Steps: steps}, nil
	}
	steps, ok := plans[name]
	if !ok {
		return ScenarioPlan{}, fmt.Errorf("unknown E2E scenario %q", name)
	}
	copySteps := append([]Step(nil), steps...)
	return ScenarioPlan{Name: name, Steps: copySteps}, nil
}

type Observation struct {
	Health                 string            `json:"health"`
	Outbox                 int               `json:"outbox"`
	ActiveInbound          int               `json:"active_inbound"`
	PendingMaterialization int               `json:"pending_materialization"`
	Mirror                 bool              `json:"mirror"`
	Sequence               int               `json:"sequence"`
	Terminal               bool              `json:"terminal"`
	LoopEvents             int               `json:"loop_events"`
	Canonical              map[string]string `json:"-"`
}

func ParseObservation(payload []byte) (Observation, error) {
	var raw struct {
		Health struct {
			Service string `json:"service"`
		} `json:"health"`
		ActiveOutbox           int `json:"active_outbox"`
		ActiveInbound          int `json:"active_inbound"`
		PendingMaterialization int `json:"pending_materialization"`
		Canonical              []struct {
			CanonIDHash string `json:"canon_id_hash"`
			State       string `json:"state"`
			Sequence    int    `json:"sequence"`
		} `json:"canonical"`
		Activity []struct {
			EventType string `json:"event_type"`
		} `json:"activity"`
	}
	if err := json.Unmarshal(payload, &raw); err != nil {
		return Observation{}, fmt.Errorf("decode E2E state: %w", err)
	}
	if raw.Health.Service == "" {
		return Observation{}, errors.New("E2E state missing health.service")
	}
	state := Observation{Health: raw.Health.Service, Outbox: raw.ActiveOutbox, ActiveInbound: raw.ActiveInbound, PendingMaterialization: raw.PendingMaterialization, Terminal: raw.Health.Service == "connected" && raw.ActiveOutbox == 0 && raw.ActiveInbound == 0 && raw.PendingMaterialization == 0, Canonical: map[string]string{}}
	for _, item := range raw.Canonical {
		state.Canonical[item.CanonIDHash] = item.State
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
