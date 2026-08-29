package scenario

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func validRoute() RouteEvidence {
	return RouteEvidence{
		Route:       "lan",
		Phase:       "authenticated",
		Generation:  3,
		QueuedCount: 0,
		QueuedBytes: 0,
		ReceiptAtMs: 1_700_000_000_000,
	}
}

func TestRouteEvidenceAllowsNoRouteObservedButNotAHalfClaim(t *testing.T) {
	if err := (RouteEvidence{}).Validate(); err != nil {
		t.Fatalf("a scenario that observed no route must not be forced to invent one: %v", err)
	}
	// Any single field set means a claim was started and must be complete.
	if err := (RouteEvidence{QueuedCount: 2}).Validate(); err == nil {
		t.Fatal("expected a half-filled route record to be rejected")
	}
}

func TestRouteEvidenceAcceptsSanitizedRecord(t *testing.T) {
	if err := validRoute().Validate(); err != nil {
		t.Fatalf("expected a sanitized record to validate, got %v", err)
	}
}

func TestRouteEvidenceRejectsUnknownRouteOrPhase(t *testing.T) {
	bad := validRoute()
	bad.Route = "bluetooth"
	if err := bad.Validate(); err == nil {
		t.Fatal("expected an unknown route to be rejected")
	}

	bad = validRoute()
	bad.Phase = "vibing"
	if err := bad.Validate(); err == nil {
		t.Fatal("expected an unknown phase to be rejected")
	}
}

func TestRouteEvidenceRejectsNegativeCounters(t *testing.T) {
	for name, mutate := range map[string]func(*RouteEvidence){
		"generation": func(r *RouteEvidence) { r.Generation = -1 },
		"count":      func(r *RouteEvidence) { r.QueuedCount = -1 },
		"bytes":      func(r *RouteEvidence) { r.QueuedBytes = -1 },
		"receipt":    func(r *RouteEvidence) { r.ReceiptAtMs = -1 },
	} {
		record := validRoute()
		mutate(&record)
		if err := record.Validate(); err == nil {
			t.Fatalf("expected a negative %s to be rejected", name)
		}
	}
}

func TestRouteEvidenceRejectsAnUnstableErrorCode(t *testing.T) {
	record := validRoute()
	record.ErrorCode = "Connection refused to 192.168.1.4"
	if err := record.Validate(); err == nil {
		t.Fatal("expected a free-form error code to be rejected")
	}

	record.ErrorCode = "lan_tls_pin_mismatch"
	if err := record.Validate(); err != nil {
		t.Fatalf("expected a stable code to validate, got %v", err)
	}
}

func TestRejectSensitiveEvidenceCatchesNetworkIdentifiers(t *testing.T) {
	cases := map[string]any{
		"ipv4":       map[string]string{"note": "peer at 192.168.29.135"},
		"ipv6":       map[string]string{"note": "peer at fe80::1c2d:3e4f:5a6b:7c8d"},
		"relay url":  map[string]string{"note": "wss://relay.twinotify.app/ws"},
		"http url":   map[string]string{"note": "http://10.0.0.2:8080/health"},
		"ssid":       map[string]string{"ssid": "HomeWifi"},
		"mac":        map[string]string{"note": "a4:83:e7:1b:2c:3d"},
		"host:port":  map[string]string{"endpoint": "twinotify.local:41779"},
		"nested":     map[string]any{"outer": map[string]any{"inner": "192.168.0.1"}},
		"in a slice": map[string]any{"events": []string{"ok", "192.168.0.1"}},
	}

	for name, payload := range cases {
		if err := RejectSensitiveEvidence(payload); err == nil {
			t.Fatalf("expected %s to be rejected", name)
		}
	}
}

func TestRejectSensitiveEvidenceCatchesSecretMaterial(t *testing.T) {
	cases := map[string]any{
		"key field":    map[string]string{"lan_secret": "abc"},
		"token field":  map[string]string{"auth_token": "abc"},
		"private key":  map[string]string{"note": "-----BEGIN PRIVATE KEY-----"},
		"bearer":       map[string]string{"note": "Authorization: Bearer eyJhbGciOi"},
		"long base64":  map[string]string{"blob": "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9w"},
		"pin material": map[string]string{"tls_pin": "aa"},
	}

	for name, payload := range cases {
		if err := RejectSensitiveEvidence(payload); err == nil {
			t.Fatalf("expected %s to be rejected", name)
		}
	}
}

func TestRejectSensitiveEvidenceAllowsOrdinaryScenarioEvidence(t *testing.T) {
	result := ScenarioResult{
		Scenario: "lan-direct-delivery",
		Status:   "passed",
		Events:   []string{"post", "accepted", "receipt"},
		Route:    validRoute(),
		Before:   map[string]Observation{"A": {Health: "stopped", Route: "none", RoutePhase: "idle"}, "B": {Health: "stopped", Route: "none", RoutePhase: "idle"}},
		After:    map[string]Observation{"A": {Health: "stopped", Route: "none", RoutePhase: "idle"}, "B": {Health: "stopped", Route: "none", RoutePhase: "idle"}},
	}

	if err := RejectSensitiveEvidence(result); err != nil {
		t.Fatalf("expected ordinary evidence to pass, got %v", err)
	}
}

func TestEvidenceSanitizerAllowsBoundedSnapshotCommitCount(t *testing.T) {
	result := ScenarioResult{
		Scenario: "lan-direct-snapshot-receipt", Status: "passed", Events: []string{"predicate:B.snapshot.commit.delta:1"}, Route: validRoute(),
		Before: map[string]Observation{
			"A": {Health: "stopped", Route: "none", RoutePhase: "idle"},
			"B": {Health: "stopped", Route: "none", RoutePhase: "idle"},
		},
		After: map[string]Observation{
			"A": {Health: "stopped", Route: "none", RoutePhase: "idle"},
			"B": {Health: "stopped", Route: "none", RoutePhase: "idle", SnapshotCommitCount: 1},
		},
	}
	if err := RejectSensitiveEvidence(result); err != nil {
		t.Fatalf("bounded content-free snapshot commit count was rejected: %v", err)
	}
}

func TestEvidenceSanitizerRejectsUnknownAndOutOfRangeSnapshotCommitEvidence(t *testing.T) {
	observation := func() map[string]any {
		return map[string]any{
			"health": "connected", "call_capture_enabled": false, "outbox": 0,
			"active_inbound": 0, "pending_materialization": 0, "mirror": false,
			"sequence": 0, "terminal": true, "loop_events": 0, "route": "lan",
			"route_phase": "authenticated", "queued_bytes": 0, "route_generation": 1,
		}
	}
	base := func() map[string]any {
		return map[string]any{
			"scenario": "post", "status": "passed", "events": []any{"post"},
			"before": map[string]any{"A": observation(), "B": observation()},
			"after":  map[string]any{"A": observation(), "B": observation()},
			"route":  map[string]any{"route": "lan", "phase": "authenticated", "route_generation": 1, "queued_count": 0, "queued_bytes": 0},
		}
	}
	for name, value := range map[string]any{
		"negative": -1, "above bound": 1_000_000_001, "wrong type": "one",
	} {
		t.Run(name, func(t *testing.T) {
			payload := base()
			payload["after"].(map[string]any)["B"].(map[string]any)["snapshot_commit_count"] = value
			if err := RejectSensitiveEvidence(payload); err == nil {
				t.Fatal("invalid snapshot commit count passed")
			}
		})
	}
	payload := base()
	payload["after"].(map[string]any)["B"].(map[string]any)["snapshot_commit_detail"] = "looks-harmless"
	if err := RejectSensitiveEvidence(payload); err == nil {
		t.Fatal("unknown snapshot commit evidence passed")
	}
}

func TestRejectSensitiveEvidenceAllowsADigestButNotAKey(t *testing.T) {
	// A hex digest is a legitimate, non-reversible evidence field.
	digest := map[string]string{
		"envelope_sha256": "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899",
	}
	if err := RejectSensitiveEvidence(digest); err != nil {
		t.Fatalf("expected a hex digest to be allowed, got %v", err)
	}
}

func TestRejectSensitiveEvidenceIsClosedWorld(t *testing.T) {
	result := ScenarioResult{Scenario: "post", Status: "passed"}
	encoded := map[string]any{
		"scenario":              result.Scenario,
		"status":                result.Status,
		"events":                []string{},
		"before":                map[string]any{},
		"after":                 map[string]any{},
		"route":                 map[string]any{},
		"unexpected_diagnostic": "looks harmless",
	}
	if err := RejectSensitiveEvidence(encoded); err == nil {
		t.Fatal("unknown evidence field must fail closed")
	}
}

func TestAggregateEvidenceWritesEachCompletedChildSeparately(t *testing.T) {
	result := ScenarioResult{
		Scenario: "lan-product-correctness", Status: "failed", ErrorCode: "fixture_failure",
		Before: map[string]Observation{}, After: map[string]Observation{},
		Children: []ScenarioResult{
			{Scenario: "lan-direct-delivery", Status: "passed", Events: []string{"predicate:direct.terminal"}, Route: validRoute(), Before: terminalPair(), After: terminalPair()},
			{Scenario: "lan-direct-update", Status: "failed", ErrorCode: "missing_sequence_transition", Before: terminalPair(), After: terminalPair()},
		},
	}
	dir := t.TempDir()
	if err := WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatal(err)
	}
	for _, relative := range []string{"children/01-lan-direct-delivery/scenario-result.json", "children/02-lan-direct-update/scenario-result.json"} {
		if info, err := os.Stat(filepath.Join(dir, relative)); err != nil || info.Size() == 0 {
			t.Fatalf("%s missing: %v", relative, err)
		}
	}
}

func TestPassedNonRouteEvidenceWritesEmptyRouteObject(t *testing.T) {
	result := ScenarioResult{
		Scenario: "action-reply", Status: "passed", Events: []string{},
		Before: terminalPair(), After: terminalPair(),
	}
	dir := t.TempDir()
	if err := WriteEvidenceArtifacts(dir, result); err != nil {
		t.Fatalf("passed non-route evidence rejected: %v", err)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "scenario-result.json"))
	if err != nil {
		t.Fatal(err)
	}
	var encoded map[string]any
	if err := json.Unmarshal(raw, &encoded); err != nil {
		t.Fatal(err)
	}
	route, ok := encoded["route"].(map[string]any)
	if !ok || len(route) != 0 {
		t.Fatalf("zero route must be encoded as an empty object: %s", raw)
	}
}

func TestInvalidAggregateChildCannotPartiallyReplaceExistingEvidence(t *testing.T) {
	dir := t.TempDir()
	validChild := ScenarioResult{Scenario: "lan-direct-delivery", Status: "passed", Events: []string{"original"}, Route: validRoute(), Before: terminalPair(), After: terminalPair()}
	valid := ScenarioResult{Scenario: "lan-product-correctness", Status: "passed", Events: []string{}, Route: validRoute(), Before: terminalPair(), After: terminalPair(), Children: []ScenarioResult{validChild}}
	if err := WriteEvidenceArtifacts(dir, valid); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "children/01-lan-direct-delivery/scenario-result.json")
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	badRoute := validRoute()
	badRoute.Route = "raw-endpoint"
	invalid := valid
	invalid.Children = []ScenarioResult{
		{Scenario: "lan-direct-delivery", Status: "passed", Events: []string{"replacement"}, Route: validRoute(), Before: terminalPair(), After: terminalPair()},
		{Scenario: "lan-direct-update", Status: "passed", Route: badRoute, Before: terminalPair(), After: terminalPair()},
	}
	if err := WriteEvidenceArtifacts(dir, invalid); err == nil {
		t.Fatal("invalid child passed")
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(before) {
		t.Fatalf("existing child evidence was partially replaced: %s", after)
	}
}

func terminalPair() map[string]Observation {
	state := Observation{Health: "connected", Terminal: true, Route: "lan", RoutePhase: "authenticated", Paired: true}
	return map[string]Observation{"A": state, "B": state}
}

func TestRejectSensitiveEvidenceRejectsWrongShapesAndMissingRequiredFields(t *testing.T) {
	observation := func() map[string]any {
		return map[string]any{
			"health": "connected", "call_capture_enabled": false, "outbox": 0,
			"active_inbound": 0, "pending_materialization": 0, "mirror": false,
			"sequence": 0, "terminal": true, "loop_events": 0, "route": "lan",
			"route_phase": "authenticated", "queued_bytes": 0, "route_generation": 1,
		}
	}
	base := func() map[string]any {
		return map[string]any{
			"scenario": "post", "status": "passed", "events": []any{"post"},
			"before": map[string]any{"A": observation(), "B": observation()},
			"after":  map[string]any{"A": observation(), "B": observation()},
			"route":  map[string]any{"route": "lan", "phase": "authenticated", "route_generation": 1, "queued_count": 0, "queued_bytes": 0},
		}
	}
	for name, mutate := range map[string]func(map[string]any){
		"before scalar":       func(v map[string]any) { v["before"] = "not-an-object" },
		"events object":       func(v map[string]any) { v["events"] = map[string]any{} },
		"route array":         func(v map[string]any) { v["route"] = []any{} },
		"missing observation": func(v map[string]any) { delete(v["after"].(map[string]any)["A"].(map[string]any), "route_generation") },
		"wrong health type":   func(v map[string]any) { v["after"].(map[string]any)["A"].(map[string]any)["health"] = 7 },
		"negative queue":      func(v map[string]any) { v["after"].(map[string]any)["A"].(map[string]any)["outbox"] = -1 },
		"invalid route enum":  func(v map[string]any) { v["after"].(map[string]any)["A"].(map[string]any)["route"] = "bluetooth" },
		"missing route field": func(v map[string]any) { delete(v["route"].(map[string]any), "route_generation") },
		"wrong receipt type": func(v map[string]any) {
			v["after"].(map[string]any)["A"].(map[string]any)["receipt_at_ms"] = "yesterday"
		},
		"raw error text": func(v map[string]any) {
			v["after"].(map[string]any)["A"].(map[string]any)["error_code"] = "TLS failed at 192.0.2.1"
		},
	} {
		value := base()
		mutate(value)
		if err := RejectSensitiveEvidence(value); err == nil {
			t.Fatalf("%s passed", name)
		}
	}
}

func TestEvidenceSanitizerRejectsUnknownProductObservationKey(t *testing.T) {
	observation := map[string]any{
		"health": "connected", "call_capture_enabled": false, "outbox": 0,
		"active_inbound": 0, "pending_materialization": 0, "mirror": false,
		"sequence": 0, "terminal": true, "loop_events": 0, "route": "lan",
		"route_phase": "authenticated", "queued_bytes": 0, "route_generation": 1,
		"raw_peer_id": "looks-harmless",
	}
	value := map[string]any{
		"scenario": "post", "status": "passed", "events": []any{"post"},
		"before": map[string]any{"A": observation, "B": observation},
		"after":  map[string]any{"A": observation, "B": observation},
		"route":  map[string]any{"route": "lan", "phase": "authenticated", "route_generation": 1, "queued_count": 0, "queued_bytes": 0},
	}
	if err := RejectSensitiveEvidence(value); err == nil {
		t.Fatal("unknown product observation key passed")
	}
}
