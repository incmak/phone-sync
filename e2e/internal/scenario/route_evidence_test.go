package scenario

import "testing"

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
	}

	if err := RejectSensitiveEvidence(result); err != nil {
		t.Fatalf("expected ordinary evidence to pass, got %v", err)
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
