# Plan 009: Recover orphaned call sessions before capture starts

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` to implement this plan task-by-task.
> Work only in the primary checkout. Never create a worktree. Follow TDD, do
> not use devices or radios, do not push, and do not commit until independent
> review is clear.

**Goal:** On service restart, durably enqueue terminal idle for every locally
owned active call session before registering for new telephony callbacks.

**Architecture:** Reuse `canonical_notification_state` as the crash journal. A
local `call:*` row in `ACTIVE` means its terminal idle was not atomically
committed; `CANCELLED` means idle plus its outbound envelope reached Room
custody. A pure terminalizer scans only local active call rows, persists idle
through the existing call persister, and handles stale concurrent commits by
re-reading. A small startup gate retries recovery with bounded delay and starts
capture only after recovery succeeds.

**Tech stack:** Kotlin, Room v4, kotlinx.coroutines, existing v2 call persister.

## Global constraints

- No Room schema migration and no second journal/store.
- Recover every locally originated active call; never touch remote calls or
  notification canonicals.
- Recovery does not require `READ_PHONE_STATE` and runs even when capture is now
  disabled.
- No new call source may register until all orphaned active rows are durably
  terminal or already terminal.
- CancellationException always propagates. Ordinary failures expose only a
  bounded health code and leave rows ACTIVE for retry.
- No ADB, emulator, device, radio, network, direct-LAN, UI, EAS, or push.

---

## Status

- **Priority**: P0
- **Effort**: M
- **Risk**: HIGH
- **Depends on**: Plan 008
- **Category**: bug
- **Planned at**: commit `c40db359`, 2026-08-19

## Files

- Create:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/ActiveCallTerminalizer.kt`
- Create:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/ActiveCallTerminalizerTest.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/CallCaptureLifecycleTest.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`
- Modify: `advisor-plans/README.md`

## Interfaces

The new file owns these internal interfaces/types:

```kotlin
internal interface ActiveCallRecoveryStore {
    suspend fun activeLocalCalls(originDevice: String): List<CanonicalNotificationState>
    suspend fun canonical(canonId: String): CanonicalNotificationState?
    suspend fun nextSequence(canonId: String): Long
}

internal class DaoActiveCallRecoveryStore(
    private val dao: ReliableDeliveryDao,
) : ActiveCallRecoveryStore

internal data class ActiveCallRecoverySummary(val terminated: Int)

internal class ActiveCallRecoveryException(
    val code: String,
    cause: Exception? = null,
) : Exception(code, cause)

internal class ActiveCallTerminalizer(
    private val store: ActiveCallRecoveryStore,
    private val persister: CallStatePersister,
) {
    suspend fun recover(originDevice: String): ActiveCallRecoverySummary
}

internal suspend fun recoverCallsBeforeCapture(
    recover: suspend () -> Unit,
    startCapture: () -> Unit,
    reportFailure: (String) -> Unit,
    retryDelayMs: Long = 1_000L,
)
```

`SyncService` constructs the DAO adapter and `CallStatePersister(context)`.

### Task 1: Define deterministic recovery behavior with RED tests

- [ ] Add `ActiveCallTerminalizerTest` with fakes for the store and persister.
- [ ] Prove an ACTIVE local `call:<lowercase UUID>` at sequence 2 produces one
      idle with the same UUID/direction and `nextSequence()` value 3.
- [ ] Prove notification rows, remote-origin call rows, and CANCELLED calls are
      absent from `activeLocalCalls` input and never reach the sink.
- [ ] Prove multiple active calls are terminated in store order.
- [ ] Prove exact-lowercase UUID validation rejects malformed/uppercase call
      canon with `call_recovery_invalid_canonical` and no persist call.
- [ ] Prove malformed direction payload falls back to `UNKNOWN` without
      exposing payload/error text.
- [ ] Prove `CancellationException` from store/persister propagates unchanged.

Run before production implementation:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*ActiveCallTerminalizerTest'
```

Expected: compile/test RED because the interfaces and terminalizer are absent.

### Task 2: Implement idempotent terminalization

- [ ] Add the exact local query to `ReliableDeliveryDao`:

```kotlin
@Query(
    "SELECT * FROM canonical_notification_state " +
        "WHERE originDevice=:originDevice AND state='ACTIVE' " +
        "AND substr(canonId, 1, 5)='call:' ORDER BY updatedAt, canonId",
)
abstract suspend fun activeLocalCallStates(
    originDevice: String,
): List<CanonicalNotificationState>
```

- [ ] Implement `DaoActiveCallRecoveryStore` with that query, `canonical`, and
      `nextCaptureSequenceForEvent`.
- [ ] For each row, derive UUID from the canonical ID and require
      `UUID.fromString(value).toString() == value`.
- [ ] Parse only the bounded `direction` field from `desiredPayloadJson`;
      `incoming`, `outgoing`, and `unknown` map to the enum, everything else
      maps to `UNKNOWN`.
- [ ] Persist `CallStateEvent(uuid, "idle", direction, nextSequence)`.
- [ ] Treat `Persisted`/`Duplicate` as success. On `Stale`, re-read: missing or
      CANCELLED is idempotent success; still ACTIVE recomputes sequence and
      retries at most 3 times; exhaustion throws code `call_recovery_stale`.
- [ ] Wrap ordinary store/persist exceptions with static code
      `call_recovery_failed`; never include throwable messages. Rethrow
      `CancellationException`.

Run focused tests. Expected: PASS.

### Task 3: Prove Room atomicity and deterministic outbox order

- [ ] Add Android transaction tests that create a locally owned ACTIVE call,
      run the real terminalizer/persister, then assert atomically:
  - canonical is CANCELLED at the next sequence;
  - exactly one NEW `call.state` idle outbox row exists;
  - a repeated recovery emits no duplicate;
  - a remote-origin active call and notification row are unchanged.
- [ ] Add stale/concurrent coverage: a stale result followed by a CANCELLED
      re-read is success; ACTIVE re-read recomputes once.
- [ ] Change the sendable query to stable insertion order:

```sql
ORDER BY createdAt, rowid LIMIT :limit
```

- [ ] Add an Android transaction test inserting recovery idle then fresh
      ringing with identical `createdAt`; assert `sendable()` returns insertion
      order so terminal idle cannot be overtaken.

Compile Android tests after adding RED sources:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:compileDebugAndroidTestKotlin
```

Expected after implementation: BUILD SUCCESSFUL. Do not claim device execution.

### Task 4: Gate capture startup behind recovery

- [ ] Add RED lifecycle tests for `recoverCallsBeforeCapture`:
  - first recovery failure reports `call_recovery_failed`, does not start;
  - after advancing the 1-second test delay, recovery succeeds and starts once;
  - recovery runs and completes even when `startCapture` represents disabled
    configuration;
  - cancellation propagates and never starts capture.
- [ ] Implement the helper as a coroutine loop. Catch only ordinary Exception,
      report the exception's bounded `ActiveCallRecoveryException.code` or
      `call_recovery_failed`, delay, and retry while active.
- [ ] In `SyncService`, replace direct normal-start
      `configureCallCapture(config.callCaptureEnabled)` with one service-owned
      recovery job. It must:
  1. get the local device ID;
  2. recover all active local call rows;
  3. only then call `configureCallCapture` with the latest config value.
- [ ] Keep debug synthetic setup explicit and unchanged. Cancel the recovery
      job through the existing service scope on destruction.
- [ ] Publish `call_recovery_failed` while retrying, without raw error text.

### Task 5: Verify and review

- [ ] Run focused JVM twice, second with `--rerun-tasks`.
- [ ] Run:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest \
  :twinotify-core:compileDebugAndroidTestKotlin \
  :twinotify-core:lintDebug
git diff --check
```

- [ ] Request independent review focused on transaction idempotence, stale
      races, local/remote ownership, capture-before-recovery ordering,
      cancellation, equal-time outbox order, and bounded error surfaces.
- [ ] After CLEAR, mark Plan 009 DONE and commit exact scoped paths with:

```text
fix(android): recover orphaned call sessions
```

## Done criteria

- [ ] Every locally owned ACTIVE call is durably terminal before capture starts.
- [ ] Repeated or concurrent recovery creates no duplicate terminal transition.
- [ ] Remote calls and notification canonicals are untouched.
- [ ] Failure leaves ACTIVE state intact, capture unregistered, and retries with
      bounded health.
- [ ] Recovery runs even when call capture is currently disabled.
- [ ] Equal-millisecond outbox rows preserve insertion order.
- [ ] JVM tests pass twice, Android transaction tests compile, full native/lint
      pass, and independent review has no Critical/Important finding.

## STOP conditions

- Correctness needs a Room schema migration or second durable store.
- Recovery cannot use existing atomic call persistence.
- Starting capture before recovery becomes unavoidable.
- A fix requires JS/UI, direct LAN, devices, radios, or network.

## Maintenance notes

Plan 010 will route graceful capture disable and service stop through this same
terminalizer. Unexpected process death remains recoverable because ACTIVE is
left as the durable marker. Android force-stop with no future process start
cannot guarantee peer cleanup and must not be claimed otherwise.
