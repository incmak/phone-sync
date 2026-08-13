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
	plan, err := Plan(name)
	if err != nil {
		return err
	}
	for _, device := range []string{"A", "B"} {
		state, snapshotErr := e.bridge.Snapshot(ctx, device)
		if snapshotErr != nil {
			return fmt.Errorf("scenario baseline %s: %w", device, snapshotErr)
		}
		e.baseline[device] = state.Canonical
	}
	for _, step := range plan.Steps {
		if err := e.action(ctx, step.Action); err != nil {
			return fmt.Errorf("%s action %s: %w", name, step.Action, err)
		}
		if step.Predicate != "" {
			if err := e.waitPredicate(ctx, name, step.Predicate); err != nil {
				return err
			}
		}
	}
	return nil
}

func (e *Executor) action(ctx context.Context, action string) error {
	parts := strings.SplitN(action, ":", 2)
	base, value := parts[0], ""
	if len(parts) == 2 {
		value = parts[1]
	}
	switch {
	case base == "A.shell.post":
		return e.bridge.Post(ctx, "A", value, value)
	case base == "A.shell.cancel":
		return e.bridge.Cancel(ctx, "A", value)
	case base == "B.network.off":
		return e.bridge.SetNetwork(ctx, "B", false)
	case base == "B.network.on":
		return e.bridge.SetNetwork(ctx, "B", true)
	case base == "A.network.off":
		return e.bridge.SetNetwork(ctx, "A", false)
	case base == "A.network.on":
		return e.bridge.SetNetwork(ctx, "A", true)
	case base == "A.force-stop", base == "A.kill":
		return e.bridge.ForceStop(ctx, "A")
	case base == "B.force-stop", base == "B.kill":
		return e.bridge.ForceStop(ctx, "B")
	case base == "A.reconcile":
		return e.bridge.Reconcile(ctx, "A")
	case base == "B.reconcile":
		return e.bridge.Reconcile(ctx, "B")
	case base == "A.restart", base == "B.restart", base == "B.reboot":
		return ErrUnsupportedEnvironment
	case base == "relay.sigterm", base == "relay.restart.same-db", base == "B.ui.xml.find-mirror", base == "B.ui.swipe-dismiss":
		return ErrUnsupportedEnvironment
	case strings.HasPrefix(base, "relay."):
		return ErrUnsupportedEnvironment
	case strings.Contains(base, "outbox."), strings.Contains(base, "health."), strings.Contains(base, "mirror."), strings.Contains(base, "receipt."), strings.Contains(base, "source."), strings.Contains(base, "listener."), strings.Contains(base, "user-dismiss."), strings.Contains(base, "no-resurrection"):
		return nil
	case strings.HasPrefix(base, "B.") || strings.HasPrefix(base, "A."):
		_, err := e.bridge.Control(ctx, strings.TrimPrefix(strings.TrimPrefix(base, "A."), "B."), "STATUS", nil)
		return err
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
	if predicate == "B.mirror.active:n1" {
		return hasNewActive(e.baseline["B"], b.Canonical)
	}
	if predicate == "B.mirror.absent:n1" || predicate == "B.no-resurrection:n1" {
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
