# Mirrored Notification Actions: Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for automation work and superpowers:verification-before-completion before any completion claim.

**Goal:** Prove action fidelity, at-most-once dispatch, expiry, tap routing, UI behavior, and OEM behavior with repeatable host tests, two-emulator scenarios, and recorded MI 11X plus POCO F1 evidence.

**Architecture:** Debug-only fixed fixtures create notifications with known reply and broadcast actions without accepting arbitrary content or intents. The Go E2E harness drives typed controls and reads sanitized state counters. A dedicated fail-closed evidence verifier binds physical results to the tested APK and commit. Automated tests supplement but never replace physical acceptance.

**Tech Stack:** Go E2E harness, ADB, Android debug controls, Kotlin instrumented tests, Bash evidence verifier, JSON/JQ, two Android emulators, Xiaomi MI 11X, POCO F1.

## Global Constraints

- Execute only after the protocol, origin, and approved mirror/UI plans pass and are committed.
- Work in the primary checkout. Do not create a worktree.
- Add failing host tests before changing the harness or evidence verifier.
- Debug controls accept only a closed fixture enum and operation enum. They never accept arbitrary notification content, package names, components, intent extras, reply text, or raw protocol frames.
- Evidence and logs contain counters, safe status codes, timestamps, build IDs, and route only. Never record notification text or reply text.
- Do not clear phone data, change radio state, unpair devices, or install/uninstall third-party user apps without explicit confirmation at the point of that destructive/disruptive step.
- Use Twinotify or a dedicated debug fixture package for install/uninstall routing tests.
- Physical evidence must name the actual MI 11X and POCO F1 serials/builds. Emulator evidence cannot satisfy physical rows.
- A failed, skipped, or unavailable physical row remains incomplete; report it honestly.

---

## Task 1: Add a fixed debug notification-action fixture

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/NotificationActionFixture.kt`
- Modify: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- Modify: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/NotificationActionFixtureTest.kt`

### Step 1: Specify the closed fixture surface

Add security tests that reject arbitrary title/text/package/component/action/intent extras. Permit only:

```text
fixture: reply | mark_read | auto_cancel | persistent
operation: post | update | cancel | reset_counters
```

The reply notification owns a real mutable `PendingIntent` plus free-form `RemoteInput`; the mark-read notification owns a real broadcast `PendingIntent`. Both update sanitized counters and emit a known follow-up notification update/removal so organic convergence is observable without exposing content.

Expected initial result: FAIL because the fixture control is unknown.

### Step 2: Implement debug-only fixtures

Keep the fixture under `src/debug`, never `src/main`. Use hard-coded local strings and action definitions. Expose only counters such as `replyDispatchCount`, `markReadDispatchCount`, `lastFixtureGeneration`, and safe terminal status through `E2eStateProvider`.

### Step 3: Verify release exclusion and commit

Run instrumented security/fixture tests and inspect the release source set or APK manifest to prove the receiver is absent from release.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/debug mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e
git commit -m "test(mobile): add fixed notification action fixtures"
```

---

## Task 2: Extend the E2E harness with action scenarios

**Files:**

- Modify: `e2e/internal/control/control.go`
- Modify: `e2e/internal/control/control_test.go`
- Create: `e2e/internal/scenario/notification_actions.go`
- Create: `e2e/internal/scenario/notification_actions_test.go`
- Modify: `e2e/internal/scenario/scenario.go`
- Modify: `e2e/cmd/twinotify-e2e/main.go`
- Modify: `e2e/cmd/twinotify-e2e/main_test.go`
- Modify: `e2e/README.md`
- Modify: `Makefile`

### Step 1: Add failing command and scenario tests

Define typed scenarios:

```text
action-reply
action-mark-read
action-origin-offline-90s
action-origin-offline-expired
action-duplicate-invoke
action-origin-kill-after-claim
action-mirror-kill-pending
action-update-stale-generation
action-cancel-before-invoke
action-tap-installed
action-tap-fallback
action-auto-cancel
action-non-auto-cancel
```

Host tests must assert exact ADB argument arrays, bounded polling, cleanup, sanitized evidence, and zero use of shell interpolation for reply content. Add a composite `notification-actions-correctness` scenario with fail-fast child ordering and evidence for every completed child.

Expected initial result: FAIL because the scenario names are unregistered.

### Step 2: Implement typed controls and predicates

Add only enum-based control methods. Observe invocation state, outbox custody, execution count, canonical sequence, materialization state, and app foreground package. For the 90-second and expiry scenarios, use explicit context deadlines and send progress output at least once per minute.

Provide a Make target:

```make
e2e-notification-actions:
	cd e2e && go run ./cmd/twinotify-e2e -scenario notification-actions-correctness
```

The target requires explicit device serials and a private evidence directory, matching the existing LAN product target conventions.

### Step 3: Run host tests and commit

Run:

```bash
cd e2e
go test ./... -race -count=1
cd ..
make host-verify
```

Expected: PASS without devices.

Commit:

```bash
git add e2e Makefile
git commit -m "test(e2e): automate notification action scenarios"
```

---

## Task 3: Add fail-closed action evidence validation

**Files:**

- Create: `scripts/verify-notification-action-evidence.sh`
- Create: `scripts/verify-notification-action-evidence_test.sh`
- Create: `docs/release-evidence/notification-actions-schema.json`
- Modify: `docs/test-scenarios.md`
- Modify: `Makefile`

### Step 1: Write verifier failure tests first

Use temporary fixture directories to prove the verifier rejects:

- missing MI 11X or POCO F1 device identity;
- APK hash or git-commit mismatch;
- missing reply, locked-phone, Doze/restriction, expiry, mid-claim-kill, rebind, installed tap, fallback tap, OEM-shaping, or 50-action sanity row;
- a row marked skipped/unknown;
- malformed UTC timestamps;
- absolute, `..`, or symlink artifact paths;
- content-bearing keys such as `title`, `text`, `reply_text`, or `payload` anywhere in the evidence JSON;
- empty operator notes or missing screenshots/log extracts.

Run the verifier self-test. Expected: FAIL because the verifier does not exist.

### Step 2: Implement the verifier and schema

Require a manifest bound to the current commit and exact APK SHA-256. Device entries must record role, ADB serial hash or redacted stable identifier, model, Android version, OEM build, app build, relay build, route, and network class. Required scenario IDs:

```text
PHY-ACTION-REPLY-LAN
PHY-ACTION-REPLY-RELAY
PHY-ACTION-MARK-READ
PHY-ACTION-LOCKED
PHY-ACTION-DOZE
PHY-ACTION-LATE
PHY-ACTION-MID-CLAIM
PHY-ACTION-REBIND
PHY-ACTION-TAP-INSTALLED
PHY-ACTION-TAP-FALLBACK
PHY-ACTION-OEM-SHAPING
PHY-ACTION-50-DAY-SANITY
```

All must equal `pass`. Keep this feature evidence gate separate from the older general release verifier, whose Pixel/Samsung requirements serve a different release matrix.

Add `make verify-notification-action-evidence ACTION_EVIDENCE_DIR=/private/path`.

### Step 3: Document the operator procedure

Add exact steps and expected state transitions to `docs/test-scenarios.md`. Include the strict rule that `dispatched` means handed to the source app, not proven applied. Document when the operator must unlock a phone, toggle airplane mode, or force-stop a process; automation must pause before those physical interventions.

### Step 4: Verify and commit

Run shell tests and `make host-verify`. Expected: PASS.

Commit:

```bash
git add scripts/verify-notification-action-evidence.sh scripts/verify-notification-action-evidence_test.sh docs/release-evidence/notification-actions-schema.json docs/test-scenarios.md Makefile
git commit -m "test(release): gate notification action evidence"
```

---

## Task 4: Run the complete automated code gate

**Files:** No source changes unless a test exposes a defect.

### Step 1: Run protocol and relay gates

```bash
make sync-proto
make proto-test
make relay-test
```

Expected: PASS; Go tests include `-race`.

### Step 2: Run mobile static, JVM, build, and migration gates

```bash
cd mobile
npm run typecheck
npm run lint
npx expo-doctor
cd android
./gradlew :twinotify-core:testDebugUnitTest :twinotify-core:lintDebug :twinotify-core:assembleDebug
./gradlew :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ReliableDeliveryMigrationTest,co.twinotify.core.storage.NotificationActionDaoTest,co.twinotify.core.storage.ActionClaimTransactionTest,co.twinotify.core.e2e.NotificationActionFixtureTest,co.twinotify.core.e2e.E2eControlSecurityTest
```

Expected: PASS on an API-compatible connected target. If connected tests are unavailable, record them as pending and do not continue to release completion.

### Step 3: Run host workflow gates

```bash
cd ../..
make host-verify
git diff --check
```

Expected: PASS.

Any defect found here gets its own failing regression test and conventional fix commit before proceeding.

---

## Task 5: Run the two-emulator correctness matrix

**Files:** Evidence only in the user-specified private directory. Do not commit private artifacts.

### Step 1: Confirm exact targets

Use `adb devices` and require two distinct emulator serials for this task. Install the same freshly built debug APK and record APK hash and git commit.

### Step 2: Run the composite scenario

```bash
E2E_DEVICE_A=emulator-5554 \
E2E_DEVICE_B=emulator-5556 \
E2E_NOTIFICATION_ACTION_EVIDENCE_DIR=/private/path/emulator-actions \
make e2e-notification-actions
```

Expected: every Task 2 child passes. For app-installed/fallback routing, use the dedicated debug fixture package so no user app is modified.

### Step 3: Audit evidence

Confirm single execution counters for duplicates, no execution after 120 seconds, `outcome_unknown` after mid-claim kill, stable mirror tag/id through state overlays, full cached detail after auto-cancel, and no notification/reply content in artifacts.

If any row fails, stop the matrix, add the smallest regression test, fix, rerun automated gates, and restart the affected scenario. Do not relabel a failed row as flaky.

---

## Task 6: Run the authorized MI 11X and POCO F1 physical matrix

**Files:** Private evidence only; update documentation only for verified OEM-specific findings.

### Step 1: Resolve device identities read-only

From `adb devices`, map the connected serials to models using `adb -s <serial> shell getprop ro.product.model` and record OS/OEM builds. Require exactly the MI 11X and POCO F1 selected for the run. Do not assume based on serial name.

### Step 2: Install the bound build and establish clean paired state

Install the exact APK whose hash is in the evidence manifest. Confirm both devices show Twinned and the same pair generation. Do not clear app data or re-pair unless the user explicitly authorizes that disruptive recovery.

### Step 3: Execute required scenarios

Run every physical row from Task 3 in both directions where applicable. Required hands-on checkpoints:

- inline reply while awake over LAN and relay;
- mark-read/archive-style broadcast;
- action tapped while mirror locked, proving no transmission before unlock;
- origin under Doze or OEM battery restriction inside and beyond the 2-minute window;
- airplane mode for 5 minutes, then reconnect, proving no late execution;
- process kill after durable claim, proving `outcome_unknown` and no restart execution;
- origin restart/rebind, proving new action IDs and stale action rejection;
- installed and missing-after-post tap routing;
- MIUI/HyperOS-shaped action set comparison;
- 50-action-day loop, duplicate, and battery sanity.

Use timestamps and safe counters. Capture screens for Android notification presentation and the approved detail UI, but redact unrelated user notification content.

### Step 4: Validate physical evidence

```bash
make verify-notification-action-evidence ACTION_EVIDENCE_DIR=/private/path/physical-actions
```

Expected: PASS. If a device OS cannot execute a row, record the limitation and leave the release gate incomplete rather than substituting emulator evidence.

---

## Task 7: Final product and design audit

**Files:**

- Modify: `docs/test-scenarios.md` only if observed behavior needs clarification
- Modify: `docs/design/SCREEN_INVENTORY.md`
- Modify: `docs/superpowers/specs/2026-08-29-mirrored-notification-actions-design.md` only to append verified implementation/evidence references, not to rewrite history

### Step 1: Reconcile implementation against every spec section

Trace each release-gate statement to a test or physical artifact. Search for forbidden leaks:

```bash
rg -n 'reply_text|remote_input_key|PendingIntent|notification content' e2e docs/release-evidence mobile/modules/twinotify-core/android/src/main
```

Inspect every match. Production code names are expected; logs, metrics, activity rows, and evidence values are not.

### Step 2: Perform the full anti-slop re-check

Re-read the complete anti-slop design law and check the rendered notification detail screen, notification icons/actions, light/dark/Monet modes, 200 percent font scaling, and all pending/terminal states point by point. Verify no clipped content, false centering, low contrast, generic icon tiles, unnecessary pills, glow/shadow halos, hidden entrance content, dead controls, or inconsistent iconography. Fix and re-verify every defect found.

### Step 3: Update inventories and run the final gate

Update the screen inventory with the new route and link the private evidence manifest by safe identifier only. Run:

```bash
make verify
make host-verify
make verify-notification-action-evidence ACTION_EVIDENCE_DIR=/private/path/physical-actions
git diff --check
git status --short
```

Expected: all gates pass and the working tree is clean.

Commit documentation updates:

```bash
git add docs/test-scenarios.md docs/design/SCREEN_INVENTORY.md docs/superpowers/specs/2026-08-29-mirrored-notification-actions-design.md
git commit -m "docs: record mirrored action verification"
```

---

## Completion Standard

Do not call the feature complete until automated gates, two-emulator scenarios, the MI 11X/POCO F1 evidence verifier, and the complete anti-slop audit all pass. The final handoff must list exact commands, commit IDs, device models/builds, evidence location, any remaining limitations, and the honest meaning of `dispatched`.
