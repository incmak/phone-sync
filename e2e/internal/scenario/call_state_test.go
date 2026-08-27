package scenario_test

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

func TestCallStateUsesStandardScenarioResultWithRouteEvidence(t *testing.T) {
	result, err := runDirectSemantic(t, "lan-direct-call-state", "")
	if err != nil {
		t.Fatal(err)
	}
	if result.Status != "passed" || result.Route.Route != "lan" || result.Route.Phase != "authenticated" {
		t.Fatalf("result=%+v", result)
	}
	payload, err := json.Marshal(result)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(payload), "11111111-1111-4111-8111-111111111111") || strings.Contains(string(payload), "call_session_id") {
		t.Fatalf("standard evidence retained a raw call identifier: %s", payload)
	}
}

func TestLegacyCallStateBypassIsNotAPlan(t *testing.T) {
	if _, err := scenario.Plan("call-state"); err == nil {
		t.Fatal("legacy call-state bypass remains available")
	}
}
