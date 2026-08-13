# Android Task 7 evidence: encrypted snapshot convergence

Commit under review: `74fe0a5` (`feat(android): reconcile active notification state`).

## Implementation evidence

- `SnapshotCoordinator.kt` stages authenticated begin/item/end events, computes the deterministic
  SHA-256 digest over sorted `canon_id + NUL + sequence + NUL + state` lines, rejects malformed
  counts/digests/origins, bounds snapshots to 4,096 items and 512 KiB payloads, and rate-limits
  repair generation to five minutes.
- `ReliableDeliveryDao.kt` performs digest-gated atomic reconciliation, preserves live states with
  newer sequences, cancels absent mirrors only after a complete valid end, rejects origin changes,
  and expires a staged session after ten minutes without mutating canonical state.
- `DurableCapturePersister.persistSnapshotEvent` encrypts state digest/begin/item/end events and
  writes each to the durable v2 outbox. `SyncService` wires the coordinator to the local device,
  listener source, and authenticated transport lifecycle. `InboundDispatcher` routes authenticated
  snapshot controls into the coordinator and materializes committed rows.
- End commits reject sessions older than ten minutes; bounds and listener availability are checked
  before an outbound begin is emitted.
- `NotificationListenerBridge` exposes a lifecycle-safe active notification snapshot and explicit
  listener availability; no listener means snapshot generation returns `SourceUnavailable`.

## Verification evidence

Commands run from `mobile/android` (raw stdout logs and explicit exit markers):

```text
./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin --offline
BUILD SUCCESSFUL (73 actionable tasks)
./gradlew --no-daemon :twinotify-core:lintDebug :twinotify-core:assembleDebug --offline
BUILD SUCCESSFUL (111 actionable tasks)
```

Captured gate artifacts:

- `/private/tmp/phone-sync-task7-jvm.log` - `:twinotify-core:testDebugUnitTest`, `exit_code=0`.
- `/private/tmp/phone-sync-task7-androidtest-compile.log` -
  `:twinotify-core:compileDebugAndroidTestKotlin`, `exit_code=0`.
- `/private/tmp/phone-sync-task7-lint-assemble.log` - `:twinotify-core:lintDebug
  :twinotify-core:assembleDebug`, `exit_code=0`.
- `mobile/modules/twinotify-core/android/build/test-results/testDebugUnitTest/*.xml` -
  64 tests, 0 failures, 0 errors (including 5 `SnapshotCoordinatorTest` cases).
- `mobile/modules/twinotify-core/android/build/outputs/aar/twinotify-core-debug.aar` -
  assembled debug AAR.

The module JVM XML reports contain 64 tests, 0 failures, and 0 errors. The focused
`SnapshotCoordinatorTest` suite contains 5 passing tests. Android instrumentation sources,
including direct Room transaction assertions for digest mismatch, live-newer ordering, staging
expiry, and stable mirror identity, compile successfully. Connected-device execution was not
claimed because no online ADB device was available during this run.

## Review boundary

This report records implementation and local build evidence only. Independent code review remains
required before pushing the commit. Full two-device relay snapshot convergence remains part of the
verification plan and is not claimed by this task report.
