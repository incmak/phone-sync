package scenario

import (
	"context"
	"errors"
	"fmt"
	"strconv"
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
	actionRestart
	actionReconcile
	actionLanAvailability
	actionControl
	actionBurstStart
	actionUnsupported
)

type action struct {
	kind     actionKind
	device   string
	tag      string
	text     string
	enabled  bool
	original string
	command  string
	params   map[string]string
	delivery bool
	count    int
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
	case actionRestart:
		return "restart:" + a.device
	case actionReconcile:
		return "reconcile:" + a.device
	case actionLanAvailability:
		return "lan-available:" + a.device + ":" + fmt.Sprint(a.enabled)
	case actionControl:
		return "control:" + a.device + ":" + strings.ToLower(strings.ReplaceAll(a.command, "_", "-"))
	case actionBurstStart:
		return fmt.Sprintf("burst-start:%s:%d", a.device, a.count)
	default:
		return "unsupported"
	}
}

func parseAction(raw string) (action, error) {
	if strings.HasPrefix(raw, "A.burst.start:") {
		count, err := strconv.Atoi(strings.TrimPrefix(raw, "A.burst.start:"))
		if err != nil || count < MinBurstCount || count > MaxBurstCount {
			return action{}, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{kind: actionBurstStart, device: "A", count: count, delivery: true, original: raw}, nil
	}
	switch raw {
	case "A.control.call-capture-enable":
		return action{kind: actionControl, device: "A", command: "CALL_CAPTURE_ENABLE", original: raw}, nil
	case "B.control.dismiss-newest-mirror":
		return action{kind: actionControl, device: "B", command: "DISMISS_NEWEST_MIRROR", delivery: true, original: raw}, nil
	case "A.control.emit-snapshot":
		return action{kind: actionControl, device: "A", command: "EMIT_SNAPSHOT", delivery: true, original: raw}, nil
	case "A.control.force-repair-snapshot":
		return action{kind: actionControl, device: "A", command: "FORCE_REPAIR_SNAPSHOT", delivery: true, original: raw}, nil
	case "A.control.local-unpair":
		return action{kind: actionControl, device: "A", command: "LOCAL_UNPAIR", delivery: true, original: raw}, nil
	}
	if strings.HasPrefix(raw, "A.control.call-state:") {
		state := strings.TrimPrefix(raw, "A.control.call-state:")
		if state != "ringing" && state != "active" && state != "idle" {
			return action{}, fmt.Errorf("invalid scenario action %q", raw)
		}
		return action{kind: actionControl, device: "A", command: "CALL_STATE", params: map[string]string{"state": state}, delivery: true, original: raw}, nil
	}
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
	case "A.lan.fail", "B.lan.fail", "A.lan.restore", "B.lan.restore":
		return action{kind: actionLanAvailability, device: raw[:1], enabled: strings.HasSuffix(raw, ".restore"), original: raw}, nil
	case "A.restart", "B.restart":
		return action{kind: actionRestart, device: raw[:1], original: raw}, nil
	case "B.reboot", "relay.sigterm", "relay.restart.same-db", "B.ui.xml.find-mirror:n1", "B.ui.swipe-dismiss:n1", "relay.drop.receipt:n1", "relay.accepted:n1", "relay.expire.mailbox", "relay.snapshot", "B.listener.rebind":
		return action{kind: actionUnsupported, original: raw}, nil
	}
	parts := splitAction(raw)
	if len(parts) >= 2 && (parts[0] == "A.shell.post" || parts[0] == "A.shell.cancel" || parts[0] == "B.shell.post" || parts[0] == "B.shell.cancel") {
		device := string(parts[0][0])
		operation := parts[0]
		tag := parts[1]
		if tag == "" {
			return action{}, fmt.Errorf("invalid scenario action %q", raw)
		}
		if operation == "A.shell.post" || operation == "B.shell.post" {
			if len(parts) == 2 {
				return action{kind: actionPost, device: device, tag: tag, text: tag, original: raw}, nil
			}
			if len(parts) == 3 {
				return action{kind: actionPost, device: device, tag: tag, text: parts[2], original: raw}, nil
			}
		}
		if (operation == "A.shell.cancel" || operation == "B.shell.cancel") && len(parts) == 2 {
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
	if _, _, ok := parseTrackedSequencePredicate(predicate); ok {
		return true
	}
	if _, _, _, _, ok := parseCustodyPredicate(predicate); ok {
		return true
	}
	for _, prefix := range []string{"B.mirror.active:", "B.mirror.absent:", "B.no-resurrection:", "A.source.absent:"} {
		if strings.HasPrefix(predicate, prefix) {
			return strings.TrimPrefix(predicate, prefix) != ""
		}
	}
	for _, prefix := range []string{
		"A.peer-receipt.delta:", "B.peer-receipt.delta:",
		"B.user-dismiss.delta:", "B.call.semantic:",
		"B.snapshot.digest.delta:", "B.snapshot.begin.delta:", "B.snapshot.end.delta:",
		"B.snapshot.commit.delta:", "B.burst.unique:", "B.unpair.inbound.delta:",
	} {
		if strings.HasPrefix(predicate, prefix) {
			return strings.TrimPrefix(predicate, prefix) != ""
		}
	}
	switch predicate {
	case "terminal.converged", "A.outbox.zero", "A.outbox.nonzero", "B.mirror.active:n1", "B.mirror.absent:n1", "B.mirror.sequence:3", "B.no-resurrection:n1", "B.health.connected", "B.health.offline", "A.source.absent:n1", "B.user-dismiss.reason":
		return true
	case "A.route.lan", "B.route.lan", "A.route.relay", "B.route.relay", "A.route.queued", "B.route.queued":
		return true
	case "A.call-capture.enabled", "A.tracked.cancelled", "B.tracked.cancelled", "B.tracked.no-resurrection", "direct.terminal",
		"A.queue.peak-bounded", "A.active-queue.nonzero", "A.unpair.custody", "both.unpaired.stable":
		return true
	default:
		return false
	}
}

func parseTrackedSequencePredicate(predicate string) (device string, want int, ok bool) {
	parts := strings.Split(predicate, ":")
	if len(parts) != 2 || (parts[0] != "A.tracked.sequence" && parts[0] != "B.tracked.sequence") {
		return "", 0, false
	}
	want, err := strconv.Atoi(parts[1])
	if err != nil || want <= 0 || want > MaxBurstCount {
		return "", 0, false
	}
	return parts[0][:1], want, true
}

func parseCustodyPredicate(predicate string) (device, route, event string, want int64, ok bool) {
	parts := strings.Split(predicate, ":")
	if len(parts) != 3 {
		return "", "", "", 0, false
	}
	head := strings.Split(parts[0], ".")
	if len(head) != 3 || (head[0] != "A" && head[0] != "B") || head[1] != "custody" || (head[2] != "lan" && head[2] != "relay") {
		return "", "", "", 0, false
	}
	allowed := map[string]bool{
		"notif_post": true, "notif_update": true, "notif_cancel": true, "call_state": true,
		"state_digest": true, "state_snapshot_begin": true, "state_snapshot_item": true,
		"state_snapshot_end": true, "unpair": true, "peer_receipt": true,
	}
	if !allowed[parts[1]] {
		return "", "", "", 0, false
	}
	want, err := strconv.ParseInt(parts[2], 10, 64)
	if err != nil || want <= 0 || want > MaxBurstCount {
		return "", "", "", 0, false
	}
	return head[0], head[2], parts[1], want, true
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
	forceStopped := map[string]bool{"A": false, "B": false}
	for _, step := range plan.Steps {
		if step.Action != "" {
			a, err := parseAction(step.Action)
			if err != nil {
				return err
			}
			if a.kind == actionUnsupported {
				return fmt.Errorf("%w: %s", ErrUnsupportedEnvironment, step.Action)
			}
			if a.kind == actionForceStop {
				forceStopped[a.device] = true
			}
			if a.kind == actionRestart {
				if !forceStopped[a.device] {
					return fmt.Errorf("restart %s requires a preceding force-stop", a.device)
				}
				forceStopped[a.device] = false
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
	Restart(context.Context, string) error
	Reconcile(context.Context, string) error
	Snapshot(context.Context, string) (Observation, error)
}

type Executor struct {
	bridge                Bridge
	stepTimeout           time.Duration
	baseline              map[string]map[string]string
	baselineState         map[string]Observation
	lanFaulted            map[string]bool
	deliveryRoute         *RouteEvidence
	trackedHash           string
	trackedTag            string
	trackedHashes         map[string]bool
	direct                bool
	unpairedStableSamples int
	burstCancel           context.CancelFunc
	burstDone             <-chan error
}

func NewExecutor(bridge Bridge, stepTimeout time.Duration) *Executor {
	if bridge == nil || stepTimeout <= 0 {
		panic("scenario bridge and positive timeout are required")
	}
	return &Executor{bridge: bridge, stepTimeout: stepTimeout, baseline: map[string]map[string]string{}, baselineState: map[string]Observation{}, lanFaulted: map[string]bool{}}
}

func (e *Executor) Run(ctx context.Context, name string) error {
	_, err := e.RunResult(ctx, name)
	return err
}

// RunResult records only action identifiers and provider snapshots. Notification
// text is intentionally absent from the result so it can be retained as evidence.
func (e *Executor) RunResult(ctx context.Context, name string) (result ScenarioResult, runErr error) {
	return e.RunResultWithBurstCount(ctx, name, DefaultBurstCount)
}

func (e *Executor) RunResultWithBurstCount(ctx context.Context, name string, burstCount int) (result ScenarioResult, runErr error) {
	plan, err := PlanWithBurstCount(name, burstCount)
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
	if len(plan.Children) == 0 {
		// Executor instances are reusable, but evidence and cleanup responsibility
		// belong to exactly one public run.
		e.baseline = map[string]map[string]string{}
		e.baselineState = map[string]Observation{}
		e.lanFaulted = map[string]bool{}
		e.deliveryRoute = nil
		e.trackedHash = ""
		e.trackedTag = ""
		e.trackedHashes = map[string]bool{}
		e.unpairedStableSamples = 0
		e.burstCancel = nil
		e.burstDone = nil
		e.direct = strings.HasPrefix(plan.Name, "lan-direct-") || plan.Name == "lan-restart-persistence"
	}
	if err := ValidateExecutablePlan(plan); err != nil {
		result.ErrorCode = errorCode(err)
		return result, err
	}
	if len(plan.Children) != 0 {
		return e.runAggregate(ctx, plan)
	}
	defer func() {
		if err := e.stopActiveBurst(ctx); runErr == nil && err != nil {
			runErr = err
		}
		for device, faulted := range e.lanFaulted {
			if faulted {
				cleanupCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), e.stepTimeout)
				_, err := e.bridge.Control(cleanupCtx, device, "SET_LAN_AVAILABLE", map[string]string{"available": "true"})
				cancel()
				if runErr == nil && err != nil {
					runErr = fmt.Errorf("restore LAN fault %s: %w", device, err)
				}
			}
		}
		var finalSnapshotErr error
		for _, device := range []string{"A", "B"} {
			snapshotCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), e.stepTimeout)
			state, err := e.bridge.Snapshot(snapshotCtx, device)
			cancel()
			if err == nil {
				result.After[device] = state
			} else if finalSnapshotErr == nil {
				finalSnapshotErr = fmt.Errorf("scenario terminal snapshot %s: %w", device, err)
			}
		}
		if runErr == nil && finalSnapshotErr != nil {
			runErr = finalSnapshotErr
		}
		if runErr == nil {
			var evidenceErr error
			result.Route, evidenceErr = deriveRouteEvidence(plan, result.Before, result.After, e.deliveryRoute)
			if evidenceErr != nil {
				runErr = evidenceErr
			}
		}
		if runErr != nil {
			result.ErrorCode = scenarioExecutionErrorCode(runErr)
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
		e.baselineState[device] = cloneObservation(state)
		result.Before[device] = cloneObservation(state)
	}
	for _, step := range plan.Steps {
		if step.Action != "" {
			a, err := parseAction(step.Action)
			if err != nil {
				return result, err
			}
			result.Events = append(result.Events, a.eventID())
			actionCtx, cancel := context.WithTimeout(ctx, e.stepTimeout)
			routeEvent, err := e.action(actionCtx, step.Action)
			cancel()
			if err != nil {
				if cause := context.Cause(ctx); cause != nil {
					err = cause
				}
				return result, fmt.Errorf("%s action %s: %w", plan.Name, step.Action, err)
			}
			if routeEvent != "" {
				result.Events = append(result.Events, routeEvent)
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

func deriveRouteEvidence(plan ScenarioPlan, before, states map[string]Observation, delivery *RouteEvidence) (RouteEvidence, error) {
	hasRouteClaim := false
	for _, step := range plan.Steps {
		hasRouteClaim = hasRouteClaim || strings.Contains(step.Predicate, ".route.")
	}
	if !hasRouteClaim {
		return RouteEvidence{}, nil
	}
	if delivery == nil {
		return RouteEvidence{}, errors.New("route-claiming scenario observed no delivery action")
	}
	a, okA := states["A"]
	b, okB := states["B"]
	if !okA || !okB {
		return RouteEvidence{}, errors.New("route evidence requires both terminal observations")
	}
	if a.Route != b.Route || a.RoutePhase != b.RoutePhase {
		return RouteEvidence{}, fmt.Errorf("mixed terminal route observations: %s/%s", a.Route, b.Route)
	}
	receipt := int64(0)
	if a.ReceiptAtMs > before["A"].ReceiptAtMs {
		receipt = a.ReceiptAtMs
	}
	result := *delivery
	result.ReceiptAtMs = receipt
	result.ErrorCode = a.ErrorCode
	return result, nil
}

func cloneCanonical(value map[string]string) map[string]string {
	clone := make(map[string]string, len(value))
	for key, state := range value {
		clone[key] = state
	}
	return clone
}

func cloneObservation(value Observation) Observation {
	value.Canonical = cloneCanonical(value.Canonical)
	value.CanonicalSequences = cloneIntMap(value.CanonicalSequences)
	value.CanonicalSemanticStates = cloneCanonical(value.CanonicalSemanticStates)
	value.CanonicalMaterializedSequences = cloneIntMap(value.CanonicalMaterializedSequences)
	sourceCustodyCounts := value.CustodyCounts
	value.CustodyCounts = map[string]map[string]int64{}
	for route, counts := range sourceCustodyCounts {
		copyCounts := make(map[string]int64, len(counts))
		for event, count := range counts {
			copyCounts[event] = count
		}
		value.CustodyCounts[route] = copyCounts
	}
	return value
}

func cloneIntMap(value map[string]int) map[string]int {
	clone := make(map[string]int, len(value))
	for key, item := range value {
		clone[key] = item
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
		result.Children = append(result.Children, childResult)
		if !childResult.Route.IsZero() {
			result.Route = childResult.Route
		}
		if err != nil {
			result.ErrorCode = childResult.ErrorCode
			return result, err
		}
	}
	result.Status = "passed"
	return result, nil
}

func (e *Executor) action(ctx context.Context, raw string) (string, error) {
	a, err := parseAction(raw)
	if err != nil {
		return "", err
	}
	switch a.kind {
	case actionPost:
		if e.trackedTag != a.tag {
			e.trackedHash = ""
			e.trackedTag = a.tag
		}
		routeEvent, err := e.observeDeliveryRoute(ctx, a)
		if err != nil {
			return "", err
		}
		return routeEvent, e.bridge.Post(ctx, a.device, a.tag, a.text)
	case actionCancel:
		routeEvent, err := e.observeDeliveryRoute(ctx, a)
		if err != nil {
			return "", err
		}
		return routeEvent, e.bridge.Cancel(ctx, a.device, a.tag)
	case actionNetwork:
		return "", e.bridge.SetNetwork(ctx, a.device, a.enabled)
	case actionForceStop:
		return "", e.bridge.ForceStop(ctx, a.device)
	case actionRestart:
		return "", e.bridge.Restart(ctx, a.device)
	case actionReconcile:
		return "", e.bridge.Reconcile(ctx, a.device)
	case actionLanAvailability:
		if !a.enabled {
			e.lanFaulted[a.device] = true
		}
		_, err := e.bridge.Control(ctx, a.device, "SET_LAN_AVAILABLE", map[string]string{"available": fmt.Sprint(a.enabled)})
		if err == nil && a.enabled {
			e.lanFaulted[a.device] = false
		}
		return "", err
	case actionControl:
		if a.command == "LOCAL_UNPAIR" {
			if err := e.stopActiveBurst(ctx); err != nil {
				return "", err
			}
		}
		routeEvent := ""
		if a.delivery {
			var routeErr error
			routeEvent, routeErr = e.observeDeliveryRoute(ctx, a)
			if routeErr != nil {
				return "", routeErr
			}
		}
		response, err := e.bridge.Control(ctx, a.device, a.command, a.params)
		if err != nil {
			return "", err
		}
		if response.Code != "ok" {
			return "", oracleFailure("control_rejected")
		}
		if a.command == "CALL_STATE" {
			hash, sequence, err := parseCallStateControlResult(response, a.params["state"])
			if err != nil {
				return "", err
			}
			if e.trackedHash == "" {
				e.trackedHash = hash
			}
			if e.trackedHash != hash || sequence != map[string]int{"ringing": 1, "active": 2, "idle": 3}[a.params["state"]] {
				return "", oracleFailure("invalid_call_control")
			}
		}
		return routeEvent, nil
	case actionBurstStart:
		routeEvent, err := e.observeDeliveryRoute(ctx, a)
		if err != nil {
			return "", err
		}
		return routeEvent, e.startActiveBurst(ctx, a)
	case actionUnsupported:
		return "", fmt.Errorf("%w: %s", ErrUnsupportedEnvironment, raw)
	default:
		return "", fmt.Errorf("unknown scenario action %q", raw)
	}
}

func (e *Executor) startActiveBurst(ctx context.Context, a action) error {
	if e.burstCancel != nil || e.burstDone != nil {
		return errors.New("burst producer is already active")
	}
	producerCtx, cancel := context.WithCancel(ctx)
	done := make(chan error, 1)
	e.burstCancel, e.burstDone = cancel, done
	go func() {
		for index := 0; index < a.count; index++ {
			tag := fmt.Sprintf("unpair-%04d", index+1)
			if err := e.bridge.Post(producerCtx, a.device, tag, tag); err != nil {
				if errors.Is(err, context.Canceled) || errors.Is(producerCtx.Err(), context.Canceled) {
					done <- nil
					return
				}
				done <- err
				return
			}
		}
		done <- nil
	}()
	err := Eventually(ctx, 10*time.Millisecond, e.stepTimeout, func() (bool, error) {
		state, snapshotErr := e.bridge.Snapshot(ctx, a.device)
		if snapshotErr != nil {
			return false, snapshotErr
		}
		if state.ActiveQueueCount > 0 && state.ActiveQueueBytes > 0 {
			return true, nil
		}
		select {
		case workerErr := <-done:
			e.burstCancel, e.burstDone = nil, nil
			cancel()
			if workerErr != nil {
				return false, workerErr
			}
			return false, oracleFailure("missing_nonzero_traffic")
		default:
			return false, nil
		}
	})
	if err != nil {
		if cleanupErr := e.stopActiveBurst(ctx); cleanupErr != nil {
			return fmt.Errorf("%w; burst cleanup: %v", err, cleanupErr)
		}
	}
	return err
}

func (e *Executor) stopActiveBurst(ctx context.Context) error {
	if e.burstCancel == nil && e.burstDone == nil {
		return nil
	}
	cancel, done := e.burstCancel, e.burstDone
	e.burstCancel, e.burstDone = nil, nil
	if cancel != nil {
		cancel()
	}
	if done == nil {
		return nil
	}
	waitCtx, waitCancel := context.WithTimeout(context.WithoutCancel(ctx), e.stepTimeout)
	defer waitCancel()
	select {
	case err := <-done:
		if errors.Is(err, context.Canceled) {
			return nil
		}
		return err
	case <-waitCtx.Done():
		return errors.New("burst producer cleanup timed out")
	}
}

func (e *Executor) observeDeliveryRoute(ctx context.Context, a action) (string, error) {
	observed, err := e.bridge.Snapshot(ctx, a.device)
	if err != nil {
		return "", err
	}
	if e.direct && (observed.Route != "lan" || observed.RoutePhase != "authenticated") {
		return "", oracleFailure("missing_authenticated_lan")
	}
	if observed.Route != "" && observed.Route != "none" && observed.RoutePhase != "authenticated" {
		return "", errors.New("delivery action has no authenticated route observation")
	}
	if observed.Route == "" || observed.Route == "none" {
		return "", nil
	}
	e.deliveryRoute = &RouteEvidence{Route: observed.Route, Phase: observed.RoutePhase, Generation: observed.RouteGeneration, QueuedCount: observed.Outbox, QueuedBytes: observed.QueuedBytes}
	return fmt.Sprintf("route:%s:%s:g%d", a.eventID(), observed.Route, observed.RouteGeneration), nil
}

func (e *Executor) waitPredicate(ctx context.Context, name, predicate string) error {
	err := Eventually(ctx, 200*time.Millisecond, e.stepTimeout, func() (bool, error) {
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
	if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
		return oracleFailure(oracleCode(predicate))
	}
	return err
}

func (e *Executor) predicateSatisfied(name, predicate string, a, b Observation) bool {
	if device, want, ok := parseTrackedSequencePredicate(predicate); ok {
		state, stateOK := namedObservation(device, a, b)
		if !stateOK {
			return false
		}
		if e.trackedHash == "" {
			candidate := ""
			for hash, sequence := range state.CanonicalSequences {
				if sequence == want && e.baselineState[device].CanonicalSequences[hash] != sequence && !e.trackedHashes[hash] {
					if candidate != "" {
						return false
					}
					candidate = hash
				}
			}
			if candidate == "" {
				return false
			}
			e.trackedHash = candidate
			if e.trackedHashes == nil {
				e.trackedHashes = map[string]bool{}
			}
			e.trackedHashes[candidate] = true
		}
		return state.Canonical[e.trackedHash] == "ACTIVE" && state.CanonicalSequences[e.trackedHash] == want &&
			state.CanonicalMaterializedSequences[e.trackedHash] == want
	}
	if strings.HasPrefix(predicate, "B.burst.unique:") {
		want, err := strconv.Atoi(strings.TrimPrefix(predicate, "B.burst.unique:"))
		if err != nil || want < MinBurstCount || want > MaxBurstCount {
			return false
		}
		unique := 0
		for hash, state := range b.Canonical {
			if e.baselineState["B"].Canonical[hash] == state || state != "ACTIVE" {
				continue
			}
			if b.CanonicalSequences[hash] != 1 || b.CanonicalMaterializedSequences[hash] != 1 {
				return false
			}
			unique++
		}
		return unique == want
	}
	if strings.HasPrefix(predicate, "B.call.semantic:") {
		want := strings.TrimPrefix(predicate, "B.call.semantic:")
		sequence := map[string]int{"RINGING": 1, "ACTIVE": 2, "IDLE": 3}[want]
		return e.trackedHash != "" && b.CanonicalSemanticStates[e.trackedHash] == want &&
			b.CanonicalSequences[e.trackedHash] == sequence && b.CanonicalMaterializedSequences[e.trackedHash] == sequence
	}
	if strings.HasSuffix(predicate, ".tracked.cancelled") {
		state := a
		if strings.HasPrefix(predicate, "B.") {
			state = b
		}
		return e.trackedHash != "" && state.Canonical[e.trackedHash] == "CANCELLED"
	}
	if predicate == "B.tracked.no-resurrection" {
		return e.trackedHash != "" && b.Canonical[e.trackedHash] == "CANCELLED"
	}
	if device, route, event, want, ok := parseCustodyPredicate(predicate); ok {
		state, stateOK := namedObservation(device, a, b)
		if !stateOK {
			return false
		}
		return custodyCount(state, route, event)-custodyCount(e.baselineState[device], route, event) >= want
	}
	if strings.HasPrefix(predicate, "A.peer-receipt.delta:") || strings.HasPrefix(predicate, "B.peer-receipt.delta:") {
		want, err := strconv.ParseInt(predicate[strings.LastIndex(predicate, ":")+1:], 10, 64)
		if err != nil || want <= 0 {
			return false
		}
		device := predicate[:1]
		state := a
		if device == "B" {
			state = b
		}
		return state.PeerReceiptCount-e.baselineState[device].PeerReceiptCount >= want
	}
	if strings.HasPrefix(predicate, "B.user-dismiss.delta:") {
		want, err := strconv.ParseInt(strings.TrimPrefix(predicate, "B.user-dismiss.delta:"), 10, 64)
		return err == nil && b.UserDismissCount-e.baselineState["B"].UserDismissCount >= want
	}
	if strings.HasPrefix(predicate, "B.unpair.inbound.delta:") {
		want, err := strconv.ParseInt(strings.TrimPrefix(predicate, "B.unpair.inbound.delta:"), 10, 64)
		return err == nil && want > 0 && b.UnpairInboundCount-e.baselineState["B"].UnpairInboundCount >= want
	}
	if strings.HasPrefix(predicate, "B.snapshot.") {
		parts := strings.Split(predicate, ":")
		if len(parts) != 2 {
			return false
		}
		want, err := strconv.ParseInt(parts[1], 10, 64)
		if err != nil || want <= 0 {
			return false
		}
		before := e.baselineState["B"]
		switch parts[0] {
		case "B.snapshot.digest.delta":
			return b.SnapshotDigestCount-before.SnapshotDigestCount >= want
		case "B.snapshot.begin.delta":
			return b.SnapshotBeginCount-before.SnapshotBeginCount >= want
		case "B.snapshot.end.delta":
			return b.SnapshotEndCount-before.SnapshotEndCount >= want
		case "B.snapshot.commit.delta":
			return b.SnapshotCommitCount-before.SnapshotCommitCount >= want
		}
	}
	if predicate == "A.call-capture.enabled" {
		return a.CallCaptureEnabled
	}
	if predicate == "direct.terminal" {
		return a.Terminal && b.Terminal && a.Outbox == 0 && b.Outbox == 0 &&
			a.ActiveInbound == 0 && b.ActiveInbound == 0 &&
			a.PendingMaterialization == 0 && b.PendingMaterialization == 0 &&
			a.ActiveQueueCount == 0 && b.ActiveQueueCount == 0 &&
			a.ActiveQueueBytes == 0 && b.ActiveQueueBytes == 0 &&
			a.LoopEvents == 0 && b.LoopEvents == 0
	}
	if predicate == "A.queue.peak-bounded" {
		return a.PeakQueueCount > 0 && a.PeakQueueCount <= 2_000 &&
			a.PeakQueueBytes > 0 && a.PeakQueueBytes <= 128<<20
	}
	if predicate == "A.active-queue.nonzero" {
		return a.ActiveQueueCount > 0 && a.ActiveQueueBytes > 0
	}
	if predicate == "A.unpair.custody" {
		return a.UnpairOutcome == "lan"
	}
	if predicate == "both.unpaired.stable" {
		clean := func(state Observation) bool {
			return !state.Paired && state.Health == "stopped" && state.Outbox == 0 &&
				state.ActiveInbound == 0 && state.PendingMaterialization == 0 &&
				state.ActiveQueueCount == 0 && state.ActiveQueueBytes == 0 && len(state.Canonical) == 0
		}
		if !clean(a) || !clean(b) {
			e.unpairedStableSamples = 0
			return false
		}
		e.unpairedStableSamples++
		return e.unpairedStableSamples >= 3
	}
	if strings.HasPrefix(predicate, "B.mirror.active:") {
		return hasNewActive(e.baseline["B"], b.Canonical)
	}
	if strings.HasPrefix(predicate, "B.mirror.absent:") || strings.HasPrefix(predicate, "B.no-resurrection:") {
		return !hasNewActive(e.baseline["B"], b.Canonical)
	}
	return predicateSatisfied(name, predicate, a, b)
}

func namedObservation(device string, a, b Observation) (Observation, bool) {
	switch device {
	case "A":
		return a, true
	case "B":
		return b, true
	default:
		return Observation{}, false
	}
}

func custodyCount(state Observation, route, event string) int64 {
	if state.CustodyCounts == nil || state.CustodyCounts[route] == nil {
		return 0
	}
	return state.CustodyCounts[route][event]
}

type oracleFailure string

func (e oracleFailure) Error() string { return string(e) }

func scenarioExecutionErrorCode(err error) string {
	var oracle oracleFailure
	if errors.As(err, &oracle) {
		return string(oracle)
	}
	return errorCode(err)
}

func oracleCode(predicate string) string {
	switch {
	case strings.Contains(predicate, ".route."):
		return "missing_authenticated_lan"
	case strings.Contains(predicate, "tracked.sequence"):
		return "missing_sequence_transition"
	case strings.Contains(predicate, "call.semantic"):
		return "missing_call_state_transition"
	case strings.Contains(predicate, "user-dismiss"):
		return "missing_user_dismissal"
	case strings.Contains(predicate, "tracked.cancelled"):
		return "missing_exact_cancellation"
	case strings.Contains(predicate, "tracked.no-resurrection"):
		return "missing_no_resurrection"
	case strings.Contains(predicate, "snapshot.digest"):
		return "missing_snapshot_digest"
	case strings.Contains(predicate, "snapshot.begin"):
		return "missing_snapshot_begin"
	case strings.Contains(predicate, "snapshot.end"):
		return "missing_snapshot_end"
	case strings.Contains(predicate, "snapshot.commit"):
		return "missing_snapshot_commit"
	case strings.Contains(predicate, ".custody.relay:"):
		return "missing_relay_custody"
	case strings.Contains(predicate, "custody"):
		return "missing_lan_custody"
	case strings.Contains(predicate, "peer-receipt"):
		return "missing_peer_receipt"
	case strings.Contains(predicate, "burst.unique"):
		return "missing_unique_burst_results"
	case strings.Contains(predicate, "queue.peak"):
		return "missing_bounded_queue_peak"
	case strings.Contains(predicate, "active-queue"):
		return "missing_nonzero_traffic"
	case strings.Contains(predicate, "unpair.custody"):
		return "missing_unpair_custody"
	case strings.Contains(predicate, "unpair.inbound"):
		return "missing_inbound_unpair"
	case strings.Contains(predicate, "unpaired.stable"):
		return "missing_stable_unpaired_terminal"
	case predicate == "direct.terminal":
		return "missing_terminal_convergence"
	default:
		return "missing_required_observation"
	}
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
func (b ADBBridge) Restart(ctx context.Context, device string) error {
	_, a, err := b.client(device)
	if err != nil {
		return err
	}
	return a.StartPackage(ctx, b.Package)
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
