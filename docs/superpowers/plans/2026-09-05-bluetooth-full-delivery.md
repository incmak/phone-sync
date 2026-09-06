# Bluetooth Full Delivery Implementation Plan

> **For agentic workers:** Execute this plan inline, task by task, using test-driven development and verification-before-completion. The user authorized exploration, implementation and testing while away; no worktree or UI decisions are needed.

**Goal:** Make the existing Bluetooth route a verifiable standalone carrier for notifications and incoming-call controls, including recovery and promotion without a configured relay.

**Architecture:** Keep the authenticated LE L2CAP wire and shared DirectDelivery engine. Fix the coordinator's relay-dependent promotion branch and the Android/host observation mismatch. Compose route-enforced Bluetooth calling scenarios from the existing capability-dispatch scenarios rather than add another call-control implementation.

**Tech Stack:** Kotlin/coroutines/Room, Android instrumentation, Go E2E harness.

## Global Constraints

- Work in the primary checkout; preserve unrelated changes.
- Exactly one coordinator-granted outbox drainer; close and join before promotion.
- No schema/database migration, dependencies, audio capture, hidden Android APIs, dialer replacement, or permission/UI changes.
- Bluetooth carries the same encrypted events and receipts as LAN; existing notification action and incoming answer/decline/hang-up capability semantics apply.
- Compatible recent emulators can exercise simulated BLE discovery and L2CAP. Physical radio behavior, OEM call PendingIntents, latency, power, and radio recovery still require physical two-phone evidence.
- Audio feasibility: Android documents voice-call capture as privileged-only (https://developer.android.com/media/platform/sharing-audio-input). HFP client APIs are system APIs (https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothHeadsetClient.java). This normal APK cannot promise smartwatch-style cellular audio transfer. A companion InCallService is a possible separate call-control integration, not an audio bridge (https://developer.android.com/reference/android/telecom/InCallService).

## Acceptance criteria

1. Android STATUS includes bounded LAN, Bluetooth and relay custody counts, including persisted Bluetooth rows; host parser accepts real emulator output.
2. With no relay configured, Bluetooth carries queued rows, continues while LAN probes fail, and promotes after LAN authenticates; no overlapping drainer and no empty-route busy loop.
3. A dedicated Bluetooth call-control gate faults LAN/relay, verifies Bluetooth throughout incoming call state and answer/decline/hang-up/replay scenarios, checks custody/receipts, and restores faults after success/failure.
4. Existing Kotlin and race-enabled Go tests pass; current APK and emulator notification/call-control regression scenarios are executed and evidence recorded honestly.

### Task 1: Repair real Android custody evidence

**Files:** `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`, `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/e2e/E2eControlSecurityTest.kt`.

**Interface:** STATUS.product_observations.custody_counts has exactly `lan`, `bluetooth`, `relay` as required by `e2e/internal/scenario/route_evidence.go`.

- [x] Change the instrumentation contract to `setOf("lan", "bluetooth", "relay")` and exercise a persisted Bluetooth custody row. Observe assertion failure on the unchanged producer.
- [x] Include `BLUETOOTH` in the SQL custody filter and `bluetooth` in JSON emission. Keep the bounded event vocabulary and content-free fields.
- [x] Re-run focused instrumentation, install rebuilt app on both emulators, and run `core-correctness` using the real host parser.

### Task 2: Maintain standalone direct-route promotion

**Files:** `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt`.

**Interface:** TransportCoordinator(lan, bluetooth, relay = null) uses the same granted-direct session and promotion logic as relay-enabled direct preference.

- [x] Add virtual-clock tests for Bluetooth-to-LAN promotion with relay null, close-before-grant, and recovery after all direct opens fail. Observe failure before the implementation.
- [x] Select `runDirectPreferred` whenever direct preference has direct routes; accept a nullable relay. With no relay and no due successful direct route, publish RECONNECTING and wait until the earliest direct cooldown, interruptible by user retry. Reuse the existing wait helper and retain relay-enabled behavior.
- [x] Run `./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.service.TransportCoordinatorTest'`, then the full Kotlin tests.

### Task 3: Verify full Bluetooth call-control delivery

**Files:** `e2e/internal/scenario/bluetooth_route.go`, `e2e/internal/scenario/bluetooth_route_test.go`, `e2e/internal/scenario/call_control.go`, `e2e/internal/scenario/executor.go`, and the custody event vocabulary in `route_evidence.go` / `SyncService.kt` as required for invoke/result evidence.

**Interface:** Add `bluetooth-call-control-correctness` composed of Bluetooth-specific answer, decline, duplicate children. Preserve the existing generic call gate and Bluetooth promotion gate.

- [x] Add executable-plan and fake-bridge tests requiring LAN/relay faults before calls, actual Bluetooth route at delivery, all three child scenarios, cleanup and rejection when Bluetooth custody/route/receipt evidence is absent. Observe unknown-plan failure first.
- [x] Compose each existing call-control child with Bluetooth isolation/route assertions and custody/receipt assertions. Set executor's direct route and call-control semantic flags for these children. Restrict mandatory LAN-promotion verification to the original `bluetooth-direct-route` gate; standalone calling must not require Wi-Fi to finish.
- [x] Run `cd e2e && go test ./... -race -count=1`. Run current generic call-control scenarios on two emulators; run Bluetooth-specific gate only with real associated peers and label physical evidence pending otherwise.

### Task 4: Record verification and support boundary

**Files:** `e2e/README.md`, `docs/evidence/bluetooth-route/README.md`, this plan.

- [x] Document the standalone Bluetooth call gate and incoming-call/audio limits.
- [x] Build debug app and instrumentation, run fresh unit tests and emulator delivery/call gates. Record skips, failures, temporary test signing workaround, and physical-only limitations.
- [x] Review diff for scope, correctness, cancellation/lease ordering, and honest route evidence. No commit or release is required by this request.

### Added after radio reproduction: bounded progressive frame I/O

The opt-in `BluetoothRadioLinkTest` uses the real Android BLE/L2CAP APIs and the production Ed25519 handshake on two API 37 emulators. A 1 KiB round trip passed; a 1 MiB transfer exposed the fixed 10-second whole-frame deadline. `BluetoothSocketWireTest.progressingFrameMayTakeLongerThanTheIdleReadDeadline` reproduced that timeout deterministically on loopback before implementation.

- Change `BluetoothSocketWire` to renew its 10-second idle deadline after each successful read/write chunk, retain the handshake limit, cap a complete frame at 120 seconds, and close/join blocked I/O on cancellation.
- Exercise steady progress, a stalled writer/reader, and a trickling peer hitting the absolute frame deadline.
- Run `BluetoothRadioLinkTest` concurrently with `-e bluetooth_role client` / `server`; optional `-e bluetooth_bytes 1024` or `65536` isolates size-dependent behavior. Default is 1,048,576 bytes in each direction. These are synthetic UTF-8 framing fixtures with fixed test-only signing identities, not product pairing or a cellular call.
- Do not translate a passing simulator test into physical-device support or a latency claim.

### Added after real Bluetooth calling: local ended-call convergence

The first app-level Bluetooth answer/hang-up scenario verified Bluetooth custody
for both control invocations/results and received call-state receipts, then
failed `direct.terminal`. Real STATUS showed old local ended-call canonical rows
still waiting for materialization. `NotificationMaterializer.applyPlatform`
tried to cancel a notification by source key for these locally observed calls,
but call rows deliberately have no source notification key.

A failing `NotificationMaterializerTest.locallyObservedEndedCallConvergesWithoutCancellingASourceNotification`
reproduced the pending retry. The fix treats a locally observed ended call as
already applied, while keeping real source notification cancellations and
remote call mirror cancellations on their existing platform paths. This also
allows startup materialization to clear old ended-call retries without a data
migration or resetting the pair.

The `bluetooth-standalone-delivery` gate reuses the small/maximum envelope checks
from the original Bluetooth gate and omits only its mandatory LAN promotion,
so it can run for Bluetooth-only pairs. Actual route, custody, receipts and
terminal convergence remain required.

The metadata-only follow-up showed that new locally captured call states had
no retry record at all: capture had recorded `materializedSequence=0` even
though the platform state was already observed. The strict app-level Bluetooth gate reproduced this on the actual durable
capture path. `DurableCapturePersister.persistCallState` now records the
observed sequence as materialized; inbound reduction still waits for its
real platform operation. The earlier materializer fix repairs preexisting rows
at startup. No diagnostic logging remains in the app.

Execution results and any acceptance limitations are recorded in
`docs/evidence/bluetooth-route/2026-09-05-emulator-verification.md`. Checked task
items indicate execution, not that every physical or emulator scenario passed.

### Added after standalone notification failure: duplex read progress

The standalone app gate authenticated Bluetooth but failed to observe the small
notification before the route dropped. Investigation found the direct processor
could wait for a pong write while also being the sole reader of a credit-limited
stream. `DirectDeliveryTest.blockedPongDoesNotPreventReadingTheNextFrameAndReleasingLinkCredits`
failed before the change. A rendezvous (`buffer(0)`) read-ahead separates socket
reading from ordered commit/ack processing, permitting at most one next bounded
frame to be read while a response write waits. No new drainer or unbounded queue
is introduced. This fixes the demonstrated stall; the emulator gate must still
prove whether it resolves the observed product failure.

The shell-post failure was not proof of payload loss: repeated `n1` tags become
updates, and startup reconciliation creates multiple sequence-delta candidates.
The original gate remains unchanged; standalone delivery now uses fresh capture
fixtures for both small and near-limit envelopes. Both must receive Bluetooth
custody and peer receipts, with empty terminal queues. Actual encoded fixture
sizes may be below the target because of the existing framing allowance.

The large fixture initially failed capture validation before transmission.
Android JSON escapes Base64 slashes; the original calculation only allowed
Base64 expansion and 4 KiB framing. A synthetic-byte instrumentation regression
failed first, then passed with an additional 2% escaping allowance. The real
stored byte count remains bounded and checked. Standalone authentication and
receipt waits now use host predicates, avoiding the device control command's
ten-second ceiling. These test fixes do not relax product custody or terminal
convergence requirements.

### Added after actual encrypted-envelope failure: nested JSON size

The correctly sized large envelope persisted, but the product gate observed no
Bluetooth custody. The Android synthetic-byte test reproduced
`bluetooth_frame_too_large`: Android JSON escaped Base64 slashes again when
wrapping the already encoded envelope as a frame string. Shared frame encoding
now omits optional slash escapes, consuming other escape pairs atomically so
literal backslashes and quotes retain their exact values. Both LAN and Bluetooth
use it; frame limits and buffered-byte limits are unchanged. The instrumentation
test checks large-envelope byte equality on both codecs and escaping edge cases.

Final outcome: the two-emulator standalone encrypted notification gate and all
three Bluetooth call-control children passed on the final build. All active
outboxes and pending materializations converged to zero. Kotlin (962), Android
instrumentation (41), Jest (262), typecheck, Android lint/build and Go E2E race
checks passed. Physical-radio/OEM/live-call evidence and call audio remain
outside these emulator results. See the evidence summary for exact scope.
