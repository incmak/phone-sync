package scenario

import "testing"

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
