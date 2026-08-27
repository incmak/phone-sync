package scenario

import (
	"testing"
)

func lanObservation() Observation {
	return Observation{Health: "connected", Route: "lan", RoutePhase: "authenticated"}
}

func relayObservation() Observation {
	return Observation{Health: "connected", Route: "relay", RoutePhase: "authenticated"}
}

func TestRoutePredicatesAreRegistered(t *testing.T) {
	for _, predicate := range []string{
		"A.route.lan", "B.route.lan",
		"A.route.relay", "B.route.relay",
		"A.route.queued", "B.route.queued",
	} {
		if !knownPredicate(predicate) {
			t.Fatalf("expected %q to be a known predicate", predicate)
		}
	}
}

func TestRoutePredicateRequiresAnAuthenticatedRoute(t *testing.T) {
	connecting := Observation{Health: "connected", Route: "lan", RoutePhase: "connecting"}

	if predicateSatisfied("lan", "A.route.lan", connecting, lanObservation()) {
		t.Fatal("a route that has not authenticated must not satisfy the direct predicate")
	}
	if !predicateSatisfied("lan", "A.route.lan", lanObservation(), lanObservation()) {
		t.Fatal("an authenticated direct route must satisfy the direct predicate")
	}
}

func TestRoutePredicateDistinguishesDirectFromRelay(t *testing.T) {
	if predicateSatisfied("lan", "A.route.lan", relayObservation(), relayObservation()) {
		t.Fatal("a relay route must not satisfy the direct predicate")
	}
	if !predicateSatisfied("lan", "A.route.relay", relayObservation(), relayObservation()) {
		t.Fatal("a relay route must satisfy the relay predicate")
	}
	if predicateSatisfied("lan", "B.route.relay", lanObservation(), lanObservation()) {
		t.Fatal("a direct route must not satisfy the relay predicate")
	}
}

func TestQueuedPredicateNeedsNoRouteAndDurableWork(t *testing.T) {
	queued := Observation{Health: "offline", Route: "none", RoutePhase: "reconnecting", Outbox: 3}
	idle := Observation{Health: "offline", Route: "none", RoutePhase: "reconnecting"}

	if !predicateSatisfied("lan", "A.route.queued", queued, queued) {
		t.Fatal("durable work with no route must satisfy the queued predicate")
	}
	if predicateSatisfied("lan", "A.route.queued", idle, idle) {
		t.Fatal("no queued work must not satisfy the queued predicate")
	}
	if predicateSatisfied("lan", "A.route.queued", lanObservation(), lanObservation()) {
		t.Fatal("a healthy direct route must not satisfy the queued predicate")
	}
}

func TestRoutePredicatesReadTheNamedDeviceOnly(t *testing.T) {
	if predicateSatisfied("lan", "B.route.lan", lanObservation(), relayObservation()) {
		t.Fatal("B.route.lan must read device B, not device A")
	}
	if !predicateSatisfied("lan", "B.route.lan", relayObservation(), lanObservation()) {
		t.Fatal("B.route.lan must be satisfied by device B's route")
	}
}

func TestLanDirectDeliveryScenarioIsExecutable(t *testing.T) {
	plan, err := Plan("lan-direct-delivery")
	if err != nil {
		t.Fatalf("expected the LAN scenario to exist: %v", err)
	}
	if err := ValidateExecutablePlan(plan); err != nil {
		t.Fatalf("expected the LAN scenario to be executable: %v", err)
	}
	var asserted bool
	for _, step := range plan.Steps {
		if step.Predicate == "B.route.lan" {
			asserted = true
		}
	}
	if !asserted {
		t.Fatal("a direct delivery scenario must assert the receiver is on the direct route")
	}
}

func TestLanDirectSemanticPlansAreExecutableAndRouteBound(t *testing.T) {
	for _, name := range []string{
		"lan-direct-update", "lan-direct-peer-dismiss",
		"lan-direct-call-state", "lan-direct-snapshot-receipt",
	} {
		plan, err := Plan(name)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if err := ValidateExecutablePlan(plan); err != nil {
			t.Fatalf("%s is not executable: %v", name, err)
		}
		if len(plan.Steps) == 0 {
			t.Fatalf("%s has no executor steps", name)
		}
	}
}

func TestDirectionalActionParserAcceptsOnlyNamedNotificationOperations(t *testing.T) {
	for _, raw := range []string{"A.shell.post:n1", "A.shell.post:n1:v1", "B.shell.post:n2", "B.shell.post:n2:v2", "A.shell.cancel:n1", "B.shell.cancel:n2"} {
		a, err := parseAction(raw)
		if err != nil || (a.device != "A" && a.device != "B") {
			t.Fatalf("%s parsed as %+v err=%v", raw, a, err)
		}
	}
	for _, raw := range []string{"C.shell.post:n1", "B.shell.post:", "B.shell.cancel:n1:extra", "B.shell.delete:n1", "B.shell.post:n1:v1:extra"} {
		if _, err := parseAction(raw); err == nil {
			t.Fatalf("invalid action %q passed", raw)
		}
	}
}

func TestDirectionalTrackedSequenceReadsNamedRecipientOnly(t *testing.T) {
	hash := "hash"
	executor := &Executor{baselineState: map[string]Observation{
		"A": {CanonicalSequences: map[string]int{}},
		"B": {CanonicalSequences: map[string]int{}},
	}}
	active := func(sequence int) Observation {
		return Observation{
			Canonical:                      map[string]string{hash: "ACTIVE"},
			CanonicalSequences:             map[string]int{hash: sequence},
			CanonicalMaterializedSequences: map[string]int{hash: sequence},
		}
	}
	if executor.predicateSatisfied("reverse", "A.tracked.sequence:1", Observation{}, active(1)) {
		t.Fatal("A predicate passed from B state")
	}
	if !executor.predicateSatisfied("reverse", "A.tracked.sequence:1", active(1), Observation{}) {
		t.Fatal("A predicate rejected exact materialization on A")
	}
	executor.trackedHash = ""
	executor.trackedHashes = map[string]bool{}
	if executor.predicateSatisfied("forward", "B.tracked.sequence:1", active(1), Observation{}) {
		t.Fatal("B predicate passed from A state")
	}
	if !executor.predicateSatisfied("forward", "B.tracked.sequence:1", Observation{}, active(1)) {
		t.Fatal("B predicate rejected exact materialization on B")
	}
}

func TestDirectionalCustodyGrammarIsClosedAndRouteSpecific(t *testing.T) {
	for _, predicate := range []string{"A.custody.lan:notif_post:1", "B.custody.relay:notif_post:2"} {
		if !knownPredicate(predicate) {
			t.Fatalf("known predicate rejected: %s", predicate)
		}
	}
	for _, predicate := range []string{
		"C.custody.lan:notif_post:1", "A.custody.wifi:notif_post:1", "A.custody.lan:unknown:1",
		"A.custody.lan:notif_post:0", "A.custody.relay:notif_post:-1", "A.custody.relay:notif_post:x",
	} {
		if knownPredicate(predicate) {
			t.Fatalf("invalid custody predicate passed: %s", predicate)
		}
	}
	if got := oracleCode("A.custody.relay:notif_post:1"); got != "missing_relay_custody" {
		t.Fatalf("relay oracle code=%q", got)
	}
}
