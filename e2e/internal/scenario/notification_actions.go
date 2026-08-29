package scenario

var notificationFixtures = map[string]bool{
	"reply": true, "mark_read": true, "auto_cancel": true, "persistent": true,
}

var notificationFixtureOperations = map[string]bool{
	"post": true, "update": true, "cancel": true, "reset_counters": true,
}

var notificationMirrorOperations = map[string]bool{
	"invoke_reply": true, "invoke_mark_read": true, "replay_last_invoke": true,
	"arm_reply": true, "arm_mark_read": true, "invoke_armed": true, "tap": true,
}

var notificationActionScenarioOrder = []string{
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

var notificationActionSteps = map[string][]Step{
	"action-reply": {
		{Action: "A.fixture:reply:reset_counters", Predicate: "A.fixture.reply.zero"},
		{Action: "A.fixture:reply:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:invoke_reply", Predicate: "A.fixture.reply.delta:1"},
		{Predicate: "B.action.terminal:DISPATCHED"},
		{Action: "A.fixture:reply:cancel", Predicate: "both.tracked.cancelled"},
		{Predicate: "terminal.converged"},
	},
	"action-mark-read": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:invoke_mark_read", Predicate: "A.fixture.mark_read.delta:1"},
		{Predicate: "B.action.terminal:DISPATCHED"},
		{Predicate: "both.tracked.cancelled"},
		{Predicate: "terminal.converged"},
	},
	"action-origin-offline-90s": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "A.network.off", Predicate: "A.health.offline"},
		{Action: "B.mirror:invoke_mark_read"},
		{Action: "wait:90s"},
		{Action: "A.network.on", Predicate: "A.health.connected"},
		{Predicate: "A.fixture.mark_read.delta:1"},
		{Predicate: "B.action.terminal:DISPATCHED"},
		{Predicate: "both.tracked.cancelled"},
	},
	"action-origin-offline-expired": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "A.network.off", Predicate: "A.health.offline"},
		{Action: "B.mirror:invoke_mark_read"},
		{Action: "wait:125s", Predicate: "B.action.terminal:EXPIRED"},
		{Action: "A.network.on", Predicate: "A.health.connected"},
		{Predicate: "A.fixture.mark_read.unchanged"},
		{Action: "A.fixture:mark_read:cancel", Predicate: "both.tracked.cancelled"},
	},
	"action-duplicate-invoke": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:invoke_mark_read"},
		{Action: "B.mirror:replay_last_invoke"},
		{Predicate: "A.fixture.mark_read.delta:1"},
		{Predicate: "A.execution.completed.delta:1"},
		{Predicate: "B.action.terminal:DISPATCHED"},
		{Predicate: "both.tracked.cancelled"},
	},
	"action-origin-kill-after-claim": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "A.origin:pause_after_claim"},
		{Action: "B.mirror:invoke_mark_read", Predicate: "A.execution.claimed.delta:1"},
		{Action: "A.force-stop"},
		{Action: "A.restart"},
		{Action: "wait:65s", Predicate: "B.action.terminal:OUTCOME_UNKNOWN"},
		{Predicate: "A.fixture.mark_read.unchanged"},
		{Action: "A.fixture:mark_read:cancel", Predicate: "both.tracked.cancelled"},
	},
	"action-mirror-kill-pending": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "A.network.off", Predicate: "A.health.offline"},
		{Action: "B.mirror:invoke_mark_read", Predicate: "B.action.pending.delta:1"},
		{Action: "B.force-stop"},
		{Action: "B.restart"},
		{Action: "A.network.on", Predicate: "A.health.connected"},
		{Predicate: "A.fixture.mark_read.delta:1"},
		{Predicate: "B.action.terminal:DISPATCHED"},
		{Predicate: "both.tracked.cancelled"},
	},
	"action-update-stale-generation": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:arm_mark_read"},
		{Action: "A.fixture:mark_read:update", Predicate: "B.tracked.sequence:2"},
		{Predicate: "B.tracked.action-set-rotated"},
		{Predicate: "B.tracked.mirror-identity-stable"},
		{Action: "B.mirror:invoke_armed", Predicate: "B.action.terminal:ACTION_GONE"},
		{Predicate: "A.fixture.mark_read.unchanged"},
		{Action: "A.fixture:mark_read:cancel", Predicate: "both.tracked.cancelled"},
	},
	"action-cancel-before-invoke": {
		{Action: "A.fixture:mark_read:reset_counters", Predicate: "A.fixture.mark_read.zero"},
		{Action: "A.fixture:mark_read:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:arm_mark_read"},
		{Action: "A.fixture:mark_read:cancel", Predicate: "both.tracked.cancelled"},
		{Action: "B.mirror:invoke_armed", Predicate: "B.action.terminal:ACTION_GONE_OR_NOTIFICATION_GONE"},
		{Predicate: "A.fixture.mark_read.unchanged"},
	},
	"action-tap-installed": {
		{Action: "A.fixture:persistent:reset_counters"},
		{Action: "A.fixture:persistent:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:tap", Predicate: "B.foreground:co.twinotify.fixture"},
		{Predicate: "both.tracked.active"},
		{Action: "A.fixture:persistent:cancel", Predicate: "both.tracked.cancelled"},
	},
	"action-tap-fallback": {
		{Action: "A.fixture:persistent:reset_counters"},
		{Action: "A.fixture:persistent:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.fixture-app:uninstall"},
		{Action: "B.mirror:tap", Predicate: "B.foreground:com.twinotify.app"},
		{Predicate: "B.detail.active"},
		{Action: "B.fixture-app:install"},
		{Action: "A.fixture:persistent:cancel", Predicate: "both.tracked.cancelled"},
	},
	"action-auto-cancel": {
		{Action: "A.fixture:auto_cancel:reset_counters"},
		{Action: "A.fixture:auto_cancel:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:tap", Predicate: "both.tracked.cancelled"},
		{Predicate: "B.detail.cancelled.delta:1"},
		{Predicate: "terminal.converged"},
	},
	"action-non-auto-cancel": {
		{Action: "A.fixture:persistent:reset_counters"},
		{Action: "A.fixture:persistent:post", Predicate: "B.tracked.sequence:1"},
		{Action: "B.mirror:tap", Predicate: "both.tracked.active"},
		{Action: "A.fixture:persistent:cancel", Predicate: "both.tracked.cancelled"},
		{Predicate: "terminal.converged"},
	},
}

func notificationActionPlan(name string) (ScenarioPlan, bool) {
	if name == "notification-actions-correctness" {
		children := make([]ScenarioPlan, 0, len(notificationActionScenarioOrder))
		for _, childName := range notificationActionScenarioOrder {
			children = append(children, ScenarioPlan{Name: childName, Steps: append([]Step(nil), notificationActionSteps[childName]...)})
		}
		return ScenarioPlan{Name: name, Children: children}, true
	}
	steps, ok := notificationActionSteps[name]
	if !ok {
		return ScenarioPlan{}, false
	}
	return ScenarioPlan{Name: name, Steps: append([]Step(nil), steps...)}, true
}
