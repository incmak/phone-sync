package scenario_test

import (
	"reflect"
	"strings"
	"testing"

	"github.com/twinotify/phone-sync/e2e/internal/scenario"
)

var notificationActionScenarioNames = []string{
	"action-reply",
	"action-mark-read",
	"action-origin-offline-90s",
	"action-origin-offline-expired",
	"action-duplicate-invoke",
	"action-origin-kill-after-claim",
	"action-mirror-kill-pending",
	"action-update-stale-generation",
	"action-cancel-before-invoke",
	"action-tap-installed",
	"action-tap-fallback",
	"action-auto-cancel",
	"action-non-auto-cancel",
}

func TestNotificationActionScenariosAreRegisteredAndExecutable(t *testing.T) {
	for _, name := range notificationActionScenarioNames {
		plan, err := scenario.Plan(name)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if plan.Name != name || len(plan.Steps) == 0 || len(plan.Children) != 0 {
			t.Fatalf("%s plan=%+v", name, plan)
		}
		if err := scenario.ValidateExecutablePlan(plan); err != nil {
			t.Fatalf("%s is not executable: %v", name, err)
		}
		for _, action := range plan.Actions() {
			if strings.ContainsAny(action, " \t\r\n") || strings.Contains(strings.ToLower(action), "reply_text") {
				t.Fatalf("%s has unsafe action identifier %q", name, action)
			}
		}
	}
}

func TestNotificationActionsCorrectnessUsesExactFailFastChildOrder(t *testing.T) {
	plan, err := scenario.Plan("notification-actions-correctness")
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Steps) != 0 {
		t.Fatalf("aggregate has direct steps: %+v", plan.Steps)
	}
	got := make([]string, 0, len(plan.Children))
	for _, child := range plan.Children {
		got = append(got, child.Name)
	}
	if !reflect.DeepEqual(got, notificationActionScenarioNames) {
		t.Fatalf("children=%v", got)
	}
	if err := scenario.ValidateExecutablePlan(plan); err != nil {
		t.Fatal(err)
	}
}

func TestNotificationActionPlansUseOnlyFixedFixtureAndMirrorOperations(t *testing.T) {
	allowedFixture := map[string]bool{"reply": true, "mark_read": true, "auto_cancel": true, "persistent": true}
	allowedOperation := map[string]bool{"post": true, "update": true, "cancel": true, "reset_counters": true}
	allowedMirror := map[string]bool{"invoke_reply": true, "invoke_mark_read": true, "replay_last_invoke": true, "arm_reply": true, "arm_mark_read": true, "invoke_armed": true, "tap": true}
	for _, name := range notificationActionScenarioNames {
		plan, err := scenario.Plan(name)
		if err != nil {
			t.Fatal(err)
		}
		for _, raw := range plan.Actions() {
			parts := strings.Split(raw, ":")
			if len(parts) == 4 && parts[1] == "fixture" {
				if !allowedFixture[parts[2]] || !allowedOperation[parts[3]] {
					t.Fatalf("%s has open fixture action %q", name, raw)
				}
			}
			if len(parts) == 3 && parts[1] == "mirror" && !allowedMirror[parts[2]] {
				t.Fatalf("%s has open mirror action %q", name, raw)
			}
		}
	}
}
