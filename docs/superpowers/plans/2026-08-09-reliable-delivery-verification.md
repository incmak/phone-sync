# Reliable Delivery End-to-End Verification Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the reliable-delivery foundation through one-command CI, real relay plus two-emulator scenarios, stress and fault injection, and recorded physical-device release evidence.

**Architecture:** A host-side Go controller manages two Android emulators, the production relay binary, network faults, app lifecycle, and assertions. Android exposes a debug-only authenticated control receiver for pairing and state inspection; notification stimuli come from Android's privileged shell notification command so the source package differs from Twinotify. Automated evidence is supplemented by a versioned Pixel/Samsung physical matrix for Doze, OEM lifecycle, latency, and battery.

**Tech Stack:** Go 1.23, adb/emulator, Android API 35 Google APIs image, Gradle/Expo prebuild, shell notification service, Docker, GitHub Actions, batterystats

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-09-reliable-delivery-foundation-design.md` exactly.
- Run only after both protocol/relay and Android plans pass their completion audits.
- E2E control components exist only in debug builds and reject commands without a per-install random session token.
- E2E assertions inspect durable app state and Android notification state, not log text alone.
- No test disables encryption, authentication, ordering, persistence, or receipt behavior.
- Fault injection may delay or duplicate transport but may not replace production repositories with fakes.
- API 35 emulator coverage does not replace Android 14+ physical Pixel and Samsung evidence.
- Release remains blocked when required physical evidence is absent or stale for the candidate build.

---

## File Structure

### Debug-only Android control

- Create `mobile/modules/twinotify-core/android/src/debug/AndroidManifest.xml`.
- Create `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`.
- Create `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`.
- Create `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`.

### Host controller and scenarios

- Create `e2e/go.mod`.
- Create `e2e/cmd/twinotify-e2e/main.go`.
- Create `e2e/internal/adb/adb.go` and `adb_test.go`.
- Create `e2e/internal/control/control.go` and `control_test.go`.
- Create `e2e/internal/scenario/scenario.go`.
- Create `e2e/internal/scenario/reliable_delivery_test.go`.
- Create `e2e/internal/scenario/stress_test.go`.
- Create `e2e/scripts/prepare-avds.sh`.
- Create `e2e/scripts/run-two-emulators.sh`.
- Create `relay/cmd/relay-e2e/main.go`: run-scoped relay with injected short retention and mailbox limits.

### Gates and evidence

- Modify `Makefile`: add `verify`, `e2e-emulator`, and `release-audit`.
- Create `.github/workflows/e2e-android.yml`: scheduled/manual two-emulator job.
- Modify `.github/workflows/mobile.yml` and `.github/workflows/relay.yml` path filters.
- Rewrite `docs/test-scenarios.md` into executable scenario IDs and evidence fields.
- Create `docs/release-evidence/README.md`.
- Create `scripts/verify-release-evidence.sh`.

---

### Task 1: Consolidate a Trustworthy Root Verification Gate

**Files:**

- Modify: `Makefile`
- Modify: `.github/workflows/mobile.yml`
- Modify: `.github/workflows/relay.yml`
- Create: `scripts/verify-generated-clean.sh`

**Interfaces:**

- Produces: `make verify`
- Produces: independent `make proto-test`, `make relay-verify`, and `make mobile-verify`

- [ ] **Step 1: Add a failing shell contract test for the root gate**

Create `scripts/verify-generated-clean.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"
git diff --check
source_dir=${PROTO_SOURCE_DIR:-proto}
schema_dir=${RELAY_SCHEMA_DIR:-relay/internal/server/schemas}
fixture_dir=${RELAY_FIXTURE_DIR:-relay/internal/server/fixtures}
for source in "$source_dir"/*.schema.json; do
  cmp "$source" "$schema_dir/$(basename "$source")"
done
diff -ru "$source_dir/fixtures" "$fixture_dir"
```

Add `--self-test`: create source/schema/fixture trees under `mktemp -d`, verify equal copies pass, change one temporary schema byte, and verify the comparison fails. The self-test trap deletes only that validated temporary directory. Run `./scripts/verify-generated-clean.sh --self-test` and observe exit 0 only when both the positive and negative checks behave correctly.

- [ ] **Step 2: Compose exact verification order**

```make
.PHONY: verify
verify: proto-test relay-verify mobile-verify
	./scripts/verify-generated-clean.sh
```

Protocol runs first because both language suites consume its fixtures. Relay runs before mobile so transport-contract failures stop early. Mobile still runs independently in its CI workflow.

- [ ] **Step 3: Expand workflow path filters**

Both mobile and relay workflows trigger for changes to `proto/**`, `Makefile`, their workflow file, and the reliability specs/plans. CI uploads Go race output, Android lint/JUnit results, and APK artifacts on every failure using `if: always()`.

- [ ] **Step 4: Run the clean root gate**

```bash
make verify
```

Expected: protocol fixtures, relay formatting/vet/race/tests/container, Expo typecheck/Doctor, Kotlin lint/JVM tests, and APK assembly all pass.

- [ ] **Step 5: Commit the consolidated gate**

```bash
git add Makefile .github/workflows scripts/verify-generated-clean.sh
git commit -m "ci: add complete reliable delivery verification gate"
```

---

### Task 2: Add a Secure Debug-only E2E Control Surface

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/debug/AndroidManifest.xml`
- Create: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- Create: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`

**Interfaces:**

- Produces: debug broadcast action `co.twinotify.e2e.CONTROL`
- Produces: debug content URI `content://co.twinotify.app.e2e/state`
- Consumes: production PairProtocol, repositories, ServiceConfigStore, and Room database

- [ ] **Step 1: Write failing manifest/security tests**

Tests must prove the receiver/provider do not exist in release manifest merging, a missing or wrong token is rejected, a correct token can invoke only allowlisted commands, and notification content is never returned by default state queries.

```kotlin
@Test
fun wrongSessionTokenCannotExecuteCommand() {
    val receiver = E2eControlReceiver()
    val result = receiver.executeForTest(context, command("STATUS", token = "wrong"))
    assertEquals("unauthorized", result.code)
}
```

- [ ] **Step 2: Run and observe missing debug control types**

```bash
cd mobile/android && ./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.e2e.E2eControlSecurityTest
```

- [ ] **Step 3: Create an install-scoped session token**

On first debug-provider access, generate 32 random bytes with `SecureRandom`, store them in debug-only private SharedPreferences, and return the base64url token only through `adb shell run-as co.twinotify.app cat files/e2e-token`. Release source sets contain no token file, receiver, provider, or manifest entry.

- [ ] **Step 4: Implement an allowlisted asynchronous command receiver**

Supported commands are:

```text
PAIR_INIT(relay_url, display_name)
PAIR_JOIN(pair_payload, display_name)
PAIR_CONFIRM(pair_token)
PAIR_COMPLETE(pair_token)
START_SYNC(relay_url)
STOP_SYNC
SET_NETWORK_EXPECTED(online|offline)
RECONCILE
CLEAR_ACTIVITY
STATUS
```

Use `goAsync()` and a dedicated coroutine scope. Every command has `request_id`; write its bounded result JSON to `files/e2e-results/<request_id>.json` through an atomic temp-file rename. Pair commands call the production pairing API and cryptography, not a test pair shortcut.

- [ ] **Step 5: Implement content-free state inspection**

`E2eStateProvider` returns only:

```json
{
  "device_id": "...",
  "paired_peer": "...",
  "health": {},
  "active_outbox": 0,
  "active_inbound": 0,
  "pending_materialization": 0,
  "canonical": [{"canon_id_hash":"hex","sequence":3,"state":"CANCELLED","materialized_sequence":3}],
  "activity": [{"event_type":"notif.cancel","status":"applied","detail_code":null}]
}
```

Hashes use SHA-256; titles, text, icon bytes, ciphertext, nonces, keys, JWTs, and raw canonical IDs are excluded.

- [ ] **Step 6: Run debug/release manifest and security tests; commit**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest connectedDebugAndroidTest processReleaseMainManifest
git add mobile/modules/twinotify-core/android/src/debug mobile/modules/twinotify-core/android/src/androidTest
git commit -m "test(android): add secure debug E2E control surface"
```

---

### Task 3: Build a Host Controller for Two Real Emulators

**Files:**

- Create: `e2e/go.mod`
- Create: `e2e/cmd/twinotify-e2e/main.go`
- Create: `e2e/internal/adb/adb.go`
- Create: `e2e/internal/adb/adb_test.go`
- Create: `e2e/internal/control/control.go`
- Create: `e2e/internal/control/control_test.go`

**Interfaces:**

- Produces: `go run ./e2e/cmd/twinotify-e2e -scenario <id>`
- Produces: typed ADB device and Twinotify control clients

- [ ] **Step 1: Write failing parser and timeout tests**

Use a fake command runner; never require ADB for unit tests.

```go
func TestControlWaitsForMatchingRequestAndTimesOut(t *testing.T) {
	r := &fakeRunner{files: map[string][]byte{"r1.json": []byte(`{"request_id":"r1","code":"ok"}`)}}
	c := control.New(r, "emulator-5554", "token", 100*time.Millisecond)
	got, err := c.Execute(context.Background(), control.Command{RequestID: "r1", Name: "STATUS"})
	if err != nil { t.Fatal(err) }
	if got.Code != "ok" { t.Fatalf("result=%#v", got) }
}
```

Also test shell escaping, nonzero exit, device-offline detection, stale result rejection, and cancellation.

- [ ] **Step 2: Run and observe missing packages**

```bash
cd e2e && go test ./... -count=1
```

- [ ] **Step 3: Implement argument-safe ADB execution**

Use `exec.CommandContext(ctx, adbPath, "-s", serial, ...)` with separate arguments. Never construct a shell command string from payload values. Provide typed methods for install, grant, broadcast, run-as file read, force-stop, reboot, wait-for-device, airplane-mode/network shaping, notification post/cancel, and `dumpsys notification --noredact` capture.

- [ ] **Step 4: Implement production pairing orchestration**

Controller sequence:

1. read both session tokens;
2. A `PAIR_INIT`, receiving real pair payload;
3. B `PAIR_JOIN` with payload;
4. A `PAIR_CONFIRM` after persisted peer hello;
5. B `PAIR_COMPLETE` after persisted signature;
6. poll both `STATUS` results for reciprocal peer IDs;
7. start sync on both;
8. wait for protocol floor 2 and connected health.

The controller gives both devices `http://10.0.2.2:<run-relay-port>` in debug builds so each emulator reaches the host loopback relay. Every wait has a bounded timeout and includes both health snapshots in its failure report.

- [ ] **Step 5: Run host unit tests and commit**

```bash
cd e2e && gofmt -w . && go test ./... -race -count=1
git add e2e/go.mod e2e/cmd e2e/internal/adb e2e/internal/control
git commit -m "test(e2e): add two-device Android controller"
```

---

### Task 4: Automate Two Emulator Provisioning

**Files:**

- Create: `e2e/scripts/prepare-avds.sh`
- Create: `e2e/scripts/run-two-emulators.sh`
- Modify: `Makefile`

**Interfaces:**

- Produces: AVDs `twinotify-api35-a` and `twinotify-api35-b`
- Produces: serials `emulator-5554` and `emulator-5556`
- Produces: `make e2e-emulator`

- [ ] **Step 1: Add a failing controller preflight test**

Preflight checks required SDK tools, API 35 Google APIs x86_64 image, two free emulator ports, relay binary, APK, `cmd notification` support, and at least 8 GiB free RAM. It exits with a distinct code and actionable message for each missing prerequisite.

- [ ] **Step 2: Implement deterministic AVD preparation**

`prepare-avds.sh` uses:

```bash
sdkmanager "platform-tools" "emulator" "platforms;android-35" "system-images;android-35;google_apis;x86_64"
printf 'no\n' | avdmanager create avd --force --name twinotify-api35-a --package "system-images;android-35;google_apis;x86_64" --device pixel_8
printf 'no\n' | avdmanager create avd --force --name twinotify-api35-b --package "system-images;android-35;google_apis;x86_64" --device pixel_8
```

The script verifies resulting AVD names and never deletes unrelated AVDs.

- [ ] **Step 3: Implement cleanup-safe two-emulator startup**

`run-two-emulators.sh` creates one `mktemp -d` run directory, starts emulators on ports 5554 and 5556 with `-no-window -no-audio -no-boot-anim -no-snapshot -wipe-data`, records PIDs, and installs a trap that terminates only those validated PIDs plus the run-scoped relay. It waits for `sys.boot_completed=1`, disables animations, installs the debug APK, grants POST_NOTIFICATIONS, and allows the exact notification listener component.

Start relay with `BOLT_PATH=$run_dir/relay.db` and a loopback port selected before child startup. Pass that port to the controller; never use the developer's normal relay DB.

- [ ] **Step 4: Verify shell notification stimulus capability**

Before scenarios, run `adb shell cmd notification help` on both devices and assert support for `post` and `cancel`. Post a tagged notification from the shell package and confirm it appears in `dumpsys notification`; cancel it and confirm removal. This is the source stimulus because `com.android.shell` differs from Twinotify's self-filtered package.

- [ ] **Step 5: Add the Make target**

```make
.PHONY: e2e-emulator
e2e-emulator: relay-build mobile-verify
	./e2e/scripts/run-two-emulators.sh
```

- [ ] **Step 6: Run provisioning twice and commit**

```bash
make e2e-emulator E2E_SCENARIO=smoke
make e2e-emulator E2E_SCENARIO=smoke
git add e2e/scripts Makefile
git commit -m "test(e2e): provision isolated Android device pair"
```

Expected: both clean runs pair, connect at floor 2, post one shell notification, mirror it, and clean up only run-scoped processes/files.

---

### Task 5: Implement Correctness and Fault Scenarios

**Files:**

- Create: `e2e/internal/scenario/scenario.go`
- Create: `e2e/internal/scenario/reliable_delivery_test.go`
- Modify: `e2e/cmd/twinotify-e2e/main.go`

**Interfaces:**

- Produces: scenario IDs `post`, `update`, `dismiss-origin`, `dismiss-peer`, `offline`, `ack-loss`, `relay-restart`, `sender-kill`, `receiver-kill`, `reboot`, `expiry-snapshot`, and `all-correctness`

- [ ] **Step 1: Write failing scenario-state-machine tests with fake devices**

Each scenario is a deterministic sequence of commands and predicates. Unit tests assert the exact fault is active before stimulus and removed before final convergence. Example offline test ordering:

```go
want := []string{
	"B.network.off", "B.health.offline", "A.shell.post:n1", "A.outbox.nonzero",
	"B.network.on", "B.health.connected", "B.mirror.active:n1", "A.outbox.zero",
}
```

- [ ] **Step 2: Implement reusable eventual assertions**

Poll durable state at 200 ms with a per-step deadline. On failure, write an artifact bundle containing relay logs, both health states, sanitized E2E provider state, `dumpsys notification`, process state, and scenario timeline. Never dump keys, JWTs, plaintext notification content, ciphertext, or nonce values.

- [ ] **Step 3: Implement core notification scenarios**

- `post`: one mirror within awake target and receipt clears sender outbox.
- `update`: three same-tag shell posts leave one mirror at sequence 3.
- `dismiss-origin`: source cancellation removes exact peer mirror and produces no loop.
- `dismiss-peer`: expand the notification shade, locate the synthetic mirror with UIAutomator XML, and perform a horizontal user swipe; origin's source is removed by exact stored key. A programmatic shell cancel is not accepted as evidence for this scenario because it produces a different Android removal reason.
- rapid post/update/cancel: final state is cancelled with no resurrection.

- [ ] **Step 4: Implement network/process scenarios**

- receiver offline during post, then reconnect;
- sender offline after relay acceptance;
- WebSocket disconnect after materialization but before receipt acceptance;
- relay SIGTERM/restart with the same Bolt file;
- sender force-stop/restart;
- receiver force-stop/restart;
- receiver reboot and listener rebind;
- Wi-Fi-equivalent network disable/enable through emulator controls.

Every scenario asserts no false `CONNECTED`, no premature outbox deletion, no duplicate visible mirror, and terminal convergence.

- [ ] **Step 5: Implement expiry plus snapshot repair with injected relay clock**

Do not wait 24 hours in CI. Add `relay/cmd/relay-e2e/main.go`, which constructs the production server with injected mailbox limits and retention read from `TWINOTIFY_E2E_RETENTION`; this test binary is built only by the E2E Make target and rejects empty or nonpositive retention. Run it with two-second retention, wait for ordinary maintenance expiry, assert sender receives explicit expiry, then reconnect/digest/snapshot and assert the still-active source notification reappears exactly once. Production `relay/cmd/relay` has no retention override or clock-control endpoint.

- [ ] **Step 6: Run all correctness scenarios and commit**

```bash
make e2e-emulator E2E_SCENARIO=all-correctness
git add e2e/internal/scenario e2e/cmd/twinotify-e2e
git commit -m "test(e2e): cover reliable delivery failures"
```

---

### Task 6: Add Stress, Latency, and Resource Evidence

**Files:**

- Create: `e2e/internal/scenario/stress_test.go`
- Create: `e2e/internal/metrics/metrics.go`
- Create: `e2e/internal/metrics/metrics_test.go`
- Modify: `e2e/cmd/twinotify-e2e/main.go`

**Interfaces:**

- Produces: scenario IDs `burst-1000`, `offline-capacity`, and `latency-awake`
- Produces: machine-readable `e2e-result.json`

- [ ] **Step 1: Write failing percentile and final-state tests**

Use deterministic samples to prove nearest-rank p50/p95/p99, no negative clock-skew latency, and correct counts. Stress result fails if any event lacks terminal status or if visible final state differs from the scenario oracle.

- [ ] **Step 2: Implement a 1,000-event mixed-state oracle**

Generate deterministic seeded operations across 100 canonical tags: post, 7 updates, cancel, and a stale duplicate per tag. Inject duplicate relay deliveries and receipt loss for a fixed subset. Expected final state is 100 cancelled sources, zero mirrors, zero active normal outbox rows, no feedback-loop activity, and bounded terminal activity records.

- [ ] **Step 3: Measure awake latency without phone-clock comparison**

Use the host monotonic clock around source post and observed mirror appearance. Run at least 100 events after warmup and report p50/p95/p99. The acceptance gate is each normal awake event within approximately two seconds off-LAN, with any outlier accompanied by transport and lifecycle state.

- [ ] **Step 4: Exercise backpressure without silent eviction**

Launch relay with test limits of 10 items/64 KiB, keep receiver offline, submit 11 non-compactable canonical events, and assert the eleventh remains local with `mailbox_full`, the first ten remain in relay, and no accepted item disappears. Reconnect and verify all 11 eventually apply and receipt.

- [ ] **Step 5: Run stress twice and commit**

```bash
make e2e-emulator E2E_SCENARIO=burst-1000
make e2e-emulator E2E_SCENARIO=offline-capacity
git add e2e/internal e2e/cmd/twinotify-e2e
git commit -m "test(e2e): stress notification ordering and backpressure"
```

---

### Task 7: Add Scheduled E2E CI and Evidence Artifacts

**Files:**

- Create: `.github/workflows/e2e-android.yml`
- Modify: `Makefile`

**Interfaces:**

- Produces: nightly and manual two-emulator workflow
- Produces: downloadable APK, relay, timeline, JUnit, sanitized state, and metrics artifacts

- [ ] **Step 1: Add workflow syntax validation**

Use a local YAML parser available in the repository toolchain or GitHub's action validation in CI. The workflow must expose `scenario` input defaulting to `all-correctness`.

- [ ] **Step 2: Define the workflow**

Use Ubuntu with KVM enabled, Node 20, JDK 17, Go from `relay/go.mod`, Android API 35 Google APIs x86_64, and Gradle cache. Run `make verify`, prepare AVDs, then run the requested E2E scenario. A concurrency group cancels older branch runs but never cancels a release-tag run.

Set a 60-minute job timeout. Upload the sanitized run directory with `if: always()`. Secrets are unnecessary because relay and devices are local.

- [ ] **Step 3: Run the workflow-equivalent commands locally**

```bash
make verify
./e2e/scripts/prepare-avds.sh
make e2e-emulator E2E_SCENARIO=all-correctness
```

- [ ] **Step 4: Commit E2E CI**

```bash
git add .github/workflows/e2e-android.yml Makefile
git commit -m "ci(e2e): run nightly two-device notification suite"
```

---

### Task 8: Make Physical-device Release Evidence Enforceable

**Files:**

- Rewrite: `docs/test-scenarios.md`
- Create: `docs/release-evidence/README.md`
- Create: `scripts/verify-release-evidence.sh`
- Modify: `Makefile`

**Interfaces:**

- Produces: scenario IDs `PHY-PAIR-01`, `PHY-DOZE-01`, `PHY-OEM-01`, `PHY-NET-01`, `PHY-BATTERY-01`, and `PHY-RELIABILITY-01`
- Produces: `make release-audit RELEASE_EVIDENCE_DIR=<path>`

- [ ] **Step 1: Write a failing evidence verifier test fixture**

Create a temporary sample evidence directory missing Samsung battery output and assert verifier failure names `PHY-BATTERY-01 samsung missing`. Add the file with wrong app SHA and assert `build mismatch`. Add complete matching evidence and assert success.

- [ ] **Step 2: Define the evidence manifest**

Each candidate uses an untracked/private directory because logs may contain device metadata:

```json
{
  "app_sha256": "64-hex",
  "relay_git_commit": "40-hex",
  "e2e_result_sha256": "64-hex",
  "e2e_git_commit": "40-hex",
  "tested_at": "2026-08-09T12:00:00Z",
  "devices": [
    {"role":"pixel","model":"...","android":14,"build":"..."},
    {"role":"samsung","model":"...","android":14,"build":"..."}
  ],
  "scenarios": {
    "PHY-DOZE-01":"pass",
    "PHY-OEM-01":"pass",
    "PHY-NET-01":"pass",
    "PHY-BATTERY-01":"pass",
    "PHY-RELIABILITY-01":"pass"
  }
}
```

The verifier requires both device roles, Android 14+, matching APK SHA, current relay commit, an E2E result from the same commit that passed `all-correctness`, `burst-1000`, and `offline-capacity`, timestamps no older than the release candidate, physical scenario pass values, batterystats files, sanitized timelines, and operator notes.

- [ ] **Step 3: Script the physical scenarios**

`docs/test-scenarios.md` gives exact setup, stimulus, expected durable state, Android-visible state, evidence command, and cleanup for:

- real QR/fingerprint pairing and restart recovery;
- locked-screen post/update/dismiss in both directions;
- 24-hour Doze with notifications introduced while idle;
- Samsung background restriction and listener rebind;
- Wi-Fi/mobile handoff and relay restart;
- 100 notifications/day battery run using reset and final `dumpsys batterystats`;
- permission revoke/restore, reboot, app update, and explicit user stop.

No scenario stores notification text, encryption keys, JWTs, phone numbers, contacts, or unrelated device logs.

- [ ] **Step 4: Add the release audit target**

```make
.PHONY: release-audit
release-audit: verify
	@test -n "$(RELEASE_EVIDENCE_DIR)"
	./scripts/verify-release-evidence.sh "$(RELEASE_EVIDENCE_DIR)"
```

- [ ] **Step 5: Run verifier tests and commit**

```bash
./scripts/verify-release-evidence.sh --self-test
git add docs/test-scenarios.md docs/release-evidence/README.md scripts/verify-release-evidence.sh Makefile
git commit -m "test(release): require physical reliability evidence"
```

---

## Plan Completion Audit

Before declaring the reliable-delivery subproject complete, inspect current evidence for every item:

- [ ] `make verify` passes from a clean generated Android project.
- [ ] `make e2e-emulator E2E_SCENARIO=all-correctness` passes twice consecutively.
- [ ] The 1,000-event mixed-state scenario ends with the oracle state and no loop.
- [ ] Offline mailbox, backpressure, relay restart, process kill, reboot, ACK loss, duplicate, and expiry/snapshot scenarios pass.
- [ ] Awake latency evidence contains at least 100 post observations and meets the approved target.
- [ ] E2E artifacts contain no secrets or notification content.
- [ ] Release manifest matches the exact APK SHA and relay commit.
- [ ] Pixel physical evidence passes Android 14+ lock screen, Doze, network, reboot, permissions, and battery scenarios.
- [ ] Samsung physical evidence passes Android 14+ OEM background, listener rebind, Doze, network, and battery scenarios.
- [ ] Battery is below 1.5% per 24 hours at approximately 100 notifications/day on both devices.
- [ ] No critical/high issue, race report, schema drift, silent loss, duplicate visible notification, stale resurrection, or wrong dismissal remains open.

Passing this audit proves only the reliable notification-delivery foundation. Secure actions, inline replies, call controls, FCM/lazy-FGS/LAN performance, richer fidelity, filter/privacy/health UX, and final product release each retain their own design, implementation, and verification gates under the active goal.
