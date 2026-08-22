package scenario

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/twinotify/phone-sync/e2e/internal/adb"
	"github.com/twinotify/phone-sync/e2e/internal/control"
)

var ErrUnsupportedEnvironment = errors.New("scenario requires a connected two-device environment")

type actionKind uint8

const (
	actionPost actionKind = iota + 1
	actionCancel
	actionNetwork
	actionForceStop
	actionReconcile
	actionUnsupported
)

type action struct {
	kind     actionKind
	device   string
	tag      string
	text     string
	enabled  bool
	original string
}

func (a action) eventID() string {
	switch a.kind {
	case actionPost:
		return "post:" + a.device + ":" + a.tag
	case actionCancel:
		return "cancel:" + a.device + ":" + a.tag
	case actionNetwork:
		state := "off"
		if a.enabled {
			state = "on"
		}
		return "network:" + a.device + ":" + state
	case actionForceStop:
		return "force-stop:" + a.device
	case actionReconcile:
		return "reconcile:" + a.device
	default:
		return "unsupported"
	}
}

func parseAction(raw string) (action, error) {
	switch raw {
	case "B.network.off":
		return action{kind: actionNetwork, device: "B", enabled: false, original: raw}, nil
	case "B.network.on":
		return action{kind: actionNetwork, device: "B", enabled: true, original: raw}, nil
	case "A.network.off":
		return action{kind: actionNetwork, device: "A", enabled: false, original: raw}, nil
	case "A.network.on":
		return action{kind: actionNetwork, device: "A", enabled: true, original: raw}, nil
	case "A.force-stop", "A.kill":
		return action{kind: actionForceStop, device: "A", original: raw}, nil
	case "B.force-stop", "B.kill":
		return action{kind: actionForceStop, device: "B", original: raw}, nil
	case "A.reconcile":
		return action{kind: actionReconcile, device: "A", original: raw}, nil
	case "B.reconcile":
		return action{kind: actionReconcile, device: "B", original: raw}, nil
	case "A.restart", "B.restart", "B.reboot", "relay.sigterm", "relay.restart.same-db", "B.ui.xml.find-mirror:n1", "B.ui.swipe-dismiss:n1", "relay.drop.receipt:n1", "relay.accepted:n1", "relay.expire.mailbox", "relay.snapshot", "B.listener.rebind":
		return action{kind: actionUnsupported, original: raw}, nil
	}
	parts := splitAction(raw)
	if len(parts) >= 2 && (parts[0] == "A.shell.post" || parts[0] == "A.shell.cancel") {
		device := string(parts[0][0])
		operation := parts[0]
		tag := parts[1]
		if tag == "" {
			return action{}, fmt.Errorf("invalid scenario action %q", raw)
		}
		if operation == "A.shell.post" {
			if len(parts) == 2 {
				return action{kind: actionPost, device: device, tag: tag, text: tag, original: raw}, nil
			}
			if len(parts) == 3 {
				return action{kind: actionPost, device: device, tag: tag, text: parts[2], original: raw}, nil
			}
		}
		if operation == "A.shell.cancel" && len(parts) == 2 {
			return action{kind: actionCancel, device: device, tag: tag, original: raw}, nil
		}
		return action{}, fmt.Errorf("invalid scenario action %q", raw)
	}
	return action{}, fmt.Errorf("unknown scenario action %q", raw)
}

func splitAction(raw string) []string {
	var result []string
	start := 0
	for i := 0; i <= len(raw); i++ {
		if i == len(raw) || raw[i] == ':' {
			result = append(result, raw[start:i])
			start = i + 1
		}
	}
	return result
}

func knownPredicate(predicate string) bool {
	for _, prefix := range []string{"B.mirror.active:", "B.mirror.absent:", "B.no-resurrection:", "A.source.absent:"} {
		if strings.HasPrefix(predicate, prefix) {
			return strings.TrimPrefix(predicate, prefix) != ""
		}
	}
	switch predicate {
	case "terminal.converged", "A.outbox.zero", "A.outbox.nonzero", "B.mirror.active:n1", "B.mirror.absent:n1", "B.mirror.sequence:3", "B.no-resurrection:n1", "B.health.connected", "B.health.offline", "A.source.absent:n1", "B.user-dismiss.reason":
		return true
	case "A.route.lan", "B.route.lan", "A.route.relay", "B.route.relay", "A.route.queued", "B.route.queued":
		return true
	default:
		return false
	}
}

// ValidateExecutablePlan performs all plan parsing before a bridge is created or
// contacted. It is intentionally shared by the CLI and Executor.
func ValidateExecutablePlan(plan ScenarioPlan) error {
	if len(plan.Children) != 0 {
		if len(plan.Steps) != 0 {
			return errors.New("aggregate scenario cannot contain direct steps")
		}
		for _, child := range plan.Children {
			if err := ValidateExecutablePlan(child); err != nil {
				return err
			}
		}
		return nil
	}
	for _, step := range plan.Steps {
		if step.Action != "" {
			a, err := parseAction(step.Action)
			if err != nil {
				return err
			}
			if a.kind == actionUnsupported {
				return fmt.Errorf("%w: %s", ErrUnsupportedEnvironment, step.Action)
			}
		}
		if step.Predicate != "" && !knownPredicate(step.Predicate) {
			return fmt.Errorf("unknown scenario predicate %q", step.Predicate)
		}
		if step.Action == "" && step.Predicate == "" {
			return errors.New("scenario step is empty")
		}
	}
	return nil
}

// Bridge is the real host/device seam. Implementations must use the typed
// control and ADB clients; tests provide a deterministic fake at this seam.
type Bridge interface {
	Control(context.Context, string, string, map[string]string) (control.Result, error)
	Post(context.Context, string, string, string) error
	Cancel(context.Context, string, string) error
	SetNetwork(context.Context, string, bool) error
	ForceStop(context.Context, string) error
	Reconcile(context.Context, string) error
	Snapshot(context.Context, string) (Observation, error)
}

type Executor struct {
	bridge      Bridge
	stepTimeout time.Duration
	baseline    map[string]map[string]string
}

func NewExecutor(bridge Bridge, stepTimeout time.Duration) *Executor {
	if bridge == nil || stepTimeout <= 0 {
		panic("scenario bridge and positive timeout are required")
	}
	return &Executor{bridge: bridge, stepTimeout: stepTimeout, baseline: map[string]map[string]string{}}
}

func (e *Executor) Run(ctx context.Context, name string) error {
	_, err := e.RunResult(ctx, name)
	return err
}

// RunResult records only action identifiers and provider snapshots. Notification
// text is intentionally absent from the result so it can be retained as evidence.
func (e *Executor) RunResult(ctx context.Context, name string) (result ScenarioResult, runErr error) {
	plan, err := Plan(name)
	if err != nil {
		result = failedResult(name, err)
		return result, err
	}
	return e.runPlan(ctx, plan)
}

func failedResult(name string, err error) ScenarioResult {
	return ScenarioResult{Scenario: name, Status: "failed", Before: map[string]Observation{}, After: map[string]Observation{}, ErrorCode: errorCode(err)}
}

func (e *Executor) runPlan(ctx context.Context, plan ScenarioPlan) (result ScenarioResult, runErr error) {
	result = ScenarioResult{Scenario: plan.Name, Status: "failed", Before: map[string]Observation{}, After: map[string]Observation{}}
	if err := ValidateExecutablePlan(plan); err != nil {
		result.ErrorCode = errorCode(err)
		return result, err
	}
	if len(plan.Children) != 0 {
		return e.runAggregate(ctx, plan)
	}
	defer func() {
		var finalSnapshotErr error
		for _, device := range []string{"A", "B"} {
			state, err := e.bridge.Snapshot(context.WithoutCancel(ctx), device)
			if err == nil {
				result.After[device] = state
			} else if finalSnapshotErr == nil {
				finalSnapshotErr = fmt.Errorf("scenario terminal snapshot %s: %w", device, err)
			}
		}
		if runErr == nil && finalSnapshotErr != nil {
			runErr = finalSnapshotErr
		}
		if runErr != nil {
			result.ErrorCode = errorCode(runErr)
		} else {
			result.Status = "passed"
		}
	}()
	for _, device := range []string{"A", "B"} {
		state, snapshotErr := e.bridge.Snapshot(ctx, device)
		if snapshotErr != nil {
			return result, fmt.Errorf("scenario baseline %s: %w", device, snapshotErr)
		}
		e.baseline[device] = cloneCanonical(state.Canonical)
		result.Before[device] = state
	}
	for _, step := range plan.Steps {
		if step.Action != "" {
			a, err := parseAction(step.Action)
			if err != nil {
				return result, err
			}
			result.Events = append(result.Events, a.eventID())
			if err := e.action(ctx, step.Action); err != nil {
				return result, fmt.Errorf("%s action %s: %w", plan.Name, step.Action, err)
			}
		}
		if step.Predicate != "" {
			result.Events = append(result.Events, "predicate:"+step.Predicate)
			if err := e.waitPredicate(ctx, plan.Name, step.Predicate); err != nil {
				return result, err
			}
		}
	}
	return result, nil
}

func cloneCanonical(value map[string]string) map[string]string {
	clone := make(map[string]string, len(value))
	for key, state := range value {
		clone[key] = state
	}
	return clone
}

func (e *Executor) runAggregate(ctx context.Context, plan ScenarioPlan) (ScenarioResult, error) {
	result := ScenarioResult{Scenario: plan.Name, Status: "failed", Before: map[string]Observation{}, After: map[string]Observation{}}
	for index, child := range plan.Children {
		childResult, err := NewExecutor(e.bridge, e.stepTimeout).runPlan(ctx, child)
		if index == 0 {
			result.Before = childResult.Before
		}
		result.After = childResult.After
		result.Events = append(result.Events, "scenario:"+child.Name)
		result.Events = append(result.Events, childResult.Events...)
		if err != nil {
			result.ErrorCode = childResult.ErrorCode
			return result, err
		}
	}
	result.Status = "passed"
	return result, nil
}

func (e *Executor) action(ctx context.Context, action string) error {
	a, err := parseAction(action)
	if err != nil {
		return err
	}
	switch a.kind {
	case actionPost:
		return e.bridge.Post(ctx, a.device, a.tag, a.text)
	case actionCancel:
		return e.bridge.Cancel(ctx, a.device, a.tag)
	case actionNetwork:
		return e.bridge.SetNetwork(ctx, a.device, a.enabled)
	case actionForceStop:
		return e.bridge.ForceStop(ctx, a.device)
	case actionReconcile:
		return e.bridge.Reconcile(ctx, a.device)
	case actionUnsupported:
		return fmt.Errorf("%w: %s", ErrUnsupportedEnvironment, action)
	default:
		return fmt.Errorf("unknown scenario action %q", action)
	}
}

func (e *Executor) waitPredicate(ctx context.Context, name, predicate string) error {
	return Eventually(ctx, 200*time.Millisecond, e.stepTimeout, func() (bool, error) {
		stateA, err := e.bridge.Snapshot(ctx, "A")
		if err != nil {
			return false, err
		}
		stateB, err := e.bridge.Snapshot(ctx, "B")
		if err != nil {
			return false, err
		}
		return e.predicateSatisfied(name, predicate, stateA, stateB), nil
	})
}

func (e *Executor) predicateSatisfied(name, predicate string, a, b Observation) bool {
	if strings.HasPrefix(predicate, "B.mirror.active:") {
		return hasNewActive(e.baseline["B"], b.Canonical)
	}
	if strings.HasPrefix(predicate, "B.mirror.absent:") || strings.HasPrefix(predicate, "B.no-resurrection:") {
		return !hasNewActive(e.baseline["B"], b.Canonical)
	}
	return predicateSatisfied(name, predicate, a, b)
}

func hasNewActive(baseline, current map[string]string) bool {
	for hash, state := range current {
		if state == "ACTIVE" && baseline[hash] != "ACTIVE" {
			return true
		}
	}
	return false
}

func predicateSatisfied(name, predicate string, a, b Observation) bool {
	switch {
	case predicate == "terminal.converged":
		return a.Terminal && b.Terminal && a.Outbox == 0 && b.Outbox == 0 && a.LoopEvents == 0 && b.LoopEvents == 0
	case predicate == "A.outbox.zero":
		return a.Outbox == 0
	case predicate == "A.outbox.nonzero":
		return a.Outbox > 0
	case predicate == "B.mirror.active:n1":
		return b.Mirror
	case predicate == "B.mirror.absent:n1":
		return !b.Mirror
	case predicate == "B.mirror.sequence:3":
		return b.Mirror && b.Sequence == 3
	case predicate == "B.no-resurrection:n1":
		return !b.Mirror && b.Terminal
	case predicate == "B.health.connected":
		return b.Health == "connected"
	case predicate == "B.health.offline":
		return b.Health == "offline"
	case predicate == "A.source.absent:n1":
		return !a.Mirror
	case strings.HasPrefix(predicate, "A.route."):
		return routeSatisfied(strings.TrimPrefix(predicate, "A.route."), a)
	case strings.HasPrefix(predicate, "B.route."):
		return routeSatisfied(strings.TrimPrefix(predicate, "B.route."), b)
	default:
		return false
	}
}

// routeSatisfied reads only the observed route, so a scenario can never claim
// direct delivery from a relay session that merely looked healthy.
func routeSatisfied(want string, o Observation) bool {
	switch want {
	case "lan", "relay":
		return o.Route == want && o.RoutePhase == "authenticated"
	case "queued":
		// Durable work with nothing carrying it. This is the state the product
		// reports as "Queued for delivery".
		return o.Route == "none" && o.Outbox > 0
	default:
		return false
	}
}

// ADBBridge connects the executor to the production typed clients.
type ADBBridge struct {
	A, B       *control.Client
	ADBA, ADBB *adb.Client
	Package    string
}

func (b ADBBridge) client(device string) (*control.Client, *adb.Client, error) {
	if device == "A" {
		return b.A, b.ADBA, nil
	}
	if device == "B" {
		return b.B, b.ADBB, nil
	}
	return nil, nil, fmt.Errorf("unknown device %s", device)
}
func (b ADBBridge) Control(ctx context.Context, device, name string, params map[string]string) (control.Result, error) {
	c, _, err := b.client(device)
	if err != nil {
		return control.Result{}, err
	}
	return c.Execute(ctx, control.Command{RequestID: fmt.Sprintf("scenario-%d", time.Now().UnixNano()), Name: name, Params: params})
}
func (b ADBBridge) Post(ctx context.Context, device, tag, text string) error {
	_, a, err := b.client(device)
	if err != nil {
		return err
	}
	return a.PostNotification(ctx, tag, text)
}
func (b ADBBridge) Cancel(ctx context.Context, device, tag string) error {
	_, a, err := b.client(device)
	if err != nil {
		return err
	}
	return a.CancelNotification(ctx, tag)
}
func (b ADBBridge) SetNetwork(ctx context.Context, device string, enabled bool) error {
	_, a, err := b.client(device)
	if err != nil {
		return err
	}
	return a.SetAirplaneMode(ctx, !enabled)
}
func (b ADBBridge) ForceStop(ctx context.Context, device string) error {
	_, a, err := b.client(device)
	if err != nil {
		return err
	}
	return a.ForceStop(ctx, b.Package)
}
func (b ADBBridge) Reconcile(ctx context.Context, device string) error {
	_, err := b.Control(ctx, device, "RECONCILE", nil)
	return err
}
func (b ADBBridge) Snapshot(ctx context.Context, device string) (Observation, error) {
	result, err := b.Control(ctx, device, "STATUS", nil)
	if err != nil {
		return Observation{}, err
	}
	return ParseObservation(result.Payload)
}
