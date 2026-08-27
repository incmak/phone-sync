# Permission-Aware Notification Materialization Implementation Plan

**Status:** Complete on 2026-08-27. Final native gate passed, focused connected Room instrumentation passed 10/10, and independent review approved the frozen diff. Evidence: `.omo/evidence/plan017-permission-aware-materialization.md` and `.omo/evidence/plan017-final-code-review.md`.

**Goal:** Leave notification state durable when posting is unavailable, avoid a permission-denial wake loop, and resume only through real startup/foreground lifecycle paths.

**Architecture:** `AndroidNotificationPort` exposes overridable outcome interface methods with Boolean compatibility adapters. `NotificationMaterializer` persists a sequence-bound, typed retry disposition. `RETRYABLE` rows have a bounded due time and participate in one global earliest-due scheduler; `PERMISSION_BLOCKED` rows have no due time, are retried only by a materialization invocation (startup, receiver, or active-service foreground hook), and never schedule an alarm. Every retry write is a Room transaction conditional on the canonical row still having the attempted sequence. The pending query admits a permission-held same sequence on explicit materialization and admits any newer canonical sequence over an older retry. `TwinotifyCoreModule.OnActivityEntersForeground` invokes the production `SyncService` companion hook, which refreshes permission health and only asks an already-active service to materialize. It never enables or starts the service.

## Constraints

- `NotificationPostOutcome` has only `Applied`, `PermissionBlocked`, and `RetryableFailure`; do not invent a permanent result without a separately proven permanent platform failure.
- `postMirrorOutcome` and `postCallMirrorOutcome` are interface methods, not extension functions. Their default Boolean adapters preserve existing fakes.
- Cancels never consult `POST_NOTIFICATIONS`.
- Retry data has exact `sequence`, typed `MaterializationRetryDisposition`, and nullable due time. Store only bounded fixed error codes, never identifiers, payloads, exception messages, or stacks.
- `CancellationException` is immediately rethrown as the exact same object before retry classification.
- Retry delay is `min(5s * 2^(attempt-1), 5min)` with overflow-safe arithmetic. Attempts continue only for the same sequence and disposition.
- Room moves from 6 to 7 through one explicit migration; earlier schema files remain untouched and there is no destructive fallback.
- Do not include `PENDING_PLATFORM` receipt supersession in this plan. It is a separately documented follow-up unless required to make the retry transaction correct.
- Keep the implementation, verification, and commit scoped to the files named in this plan. Do not push or mutate pairing/radio state as part of this work.

## Task 1 - Outcome classification and materializer behavior

**Files:** `AndroidNotificationPort.kt`, `NotificationMaterializer.kt`, `NotificationMaterializerTest.kt`.

1. RED: override the interface outcome method with `PermissionBlocked`; assert state stays pending, persisted disposition is `PERMISSION_BLOCKED`, no due time exists, and scheduler is untouched. Assert a receipt-factory `CancellationException` is rethrown by identity with no retry side effect. Capture the focused Gradle RED.
2. Implement `NotificationPostOutcome` and DefaultAndroidNotificationPort classification: disabled manager or denied post permission produces `PermissionBlocked`; construction/notify errors produce `RetryableFailure`; notify success produces `Applied`.
3. Change materializer platform application from Boolean to typed outcome. Permission blocking parks durable state without calling a scheduler. Retryable platform and receipt failures write a `RETRYABLE` record. After every materialization invocation, query and schedule the global earliest retryable due time.
4. GREEN: focused materializer tests prove permission parking, retry scheduling, cancel application without post permission, bounded retry delay, cancellation identity, and restart retry.

## Task 2 - Sequence-aware Room persistence and migration

**Files:** `ReliableDeliveryEntities.kt`, `ReliableDeliveryDao.kt`, `NotificationDb.kt`, generated Room schema `7.json`, DAO/migration Android tests, narrowly related fake stores.

1. RED: add migration test from v6 containing a retry row and canonical sequence; add DAO tests proving (a) a sequence-1 future retry cannot suppress canonical sequence 2 and (b) a same-sequence `PERMISSION_BLOCKED` row with null due is admitted on an explicit materialization invocation.
2. Define `MaterializationRetryDisposition { RETRYABLE, PERMISSION_BLOCKED }`, add Room conversion, and add `sequence` plus nullable `nextAttemptAt` to the entity. Migration 6->7 rebuilds only `materialization_retry`, backfills sequence from canonical state (or zero when orphaned), marks legacy rows `RETRYABLE`, preserves all retry fields, and recreates the due index.
3. Add DAO transactions that read attempts, calculate the next bounded due time, then execute an upsert whose `WHERE EXISTS` requires `canonical_notification_state.latestSequence == attemptedSequence`. Return a typed outcome so stale work cannot arm a timer. Preserve attempt count only for the exact sequence/disposition. Add `earliestRetryableMaterializationAt()`.
4. Update the pending query: absent retry, due retry, same-sequence permission-held retry, and a retry older than canonical state are eligible. A same-sequence future retryable row is not.
5. GREEN: compile/run focused tests and inspect generated `schemas/.../7.json`; prove v2-v6 schema bytes were unchanged and migration validation passes.

## Task 3 - Global retry wake and real lifecycle resume

**Files:** `NotificationMaterializer.kt`, `SyncService.kt`, `TwinotifyCoreModule.kt`, optionally `SyncServiceStatus.kt`, focused scheduler/lifecycle tests.

1. RED: scheduler tests prove an earlier global due replaces a later in-process fallback wake, while a later request does not move an earlier wake. Materializer tests prove startup/restart schedules the DAO’s earliest retryable durable due, not a hard-coded five seconds.
2. Implement the replacement-safe in-process wake coordinator. AlarmManager continues to update the single request to the global earliest due. Receiver invokes the same materializer path, which recomputes the durable earliest due after work.
3. Add `SyncService.onAppForeground(context)` companion hook. It refreshes `SyncServiceStatus.postPermission`; if and only if `activeInstance` exists, it launches that service’s materializer. It does not start/enable a disabled service. Add `OnActivityEntersForeground { SyncService.onAppForeground(requireContext()) }` to the real Expo module lifecycle.
4. GREEN: lifecycle behavior/source tests prove foreground calls the real companion hook, status refresh occurs, active service materializes parked work, and no inactive configuration starts a service. Existing onCreate remains the cold-start fallback.

## Verification and evidence

1. Focused JVM: `:twinotify-core:testDebugUnitTest --tests '*NotificationMaterializerTest' --tests '*...scheduler/lifecycle test'`.
2. Android test compile: `:twinotify-core:compileDebugAndroidTestKotlin`; capture migration and DAO test sources plus generated schema.
3. Sequential complete native gate: `lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug`.
4. `git diff --check`, scoped diff review, evidence report with scenario, invocation, terminal result, binary observable, and artifact path. Request independent review after gates.

## Plan review

The plan has one outcome contract, one typed retry model, one transactional sequence fence, and one lifecycle route. Permission-held work cannot make a timer loop, but is deliberately visible to startup and foreground materialization. Retryable work remains process-death safe because scheduling always derives from the durable global earliest due time. The plan deliberately excludes receipt supersession so this change stays bounded.
