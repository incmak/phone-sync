# Plan 010: Gracefully terminalize calls before capture disable or service stop

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` to implement this plan task-by-task.
> Work only in the primary checkout. Never create a worktree. Follow TDD, do
> not use devices or radios, do not push, and do not commit until independent
> review is clear. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unregister live call capture and durably terminalize every committed
local ACTIVE call before a user-requested capture disable, service stop, or
unpair is allowed to finish.

**Architecture:** Add a suspendable quiesce operation to
`CallStateCoordinator`: unregister the source first, serialize behind
`callbackMutex`, drain any already-queued event into durable custody, invoke the
existing Plan 009 `ActiveCallTerminalizer`, and clear in-memory session/retry
state only after terminalization succeeds. One companion/application-owned
shutdown gate is shared by active-service and no-service entry points. It
reserves shutdown admission before reading `activeInstance`, so a concurrently
created service cannot register capture. Terminal custody and disabled-config
persistence run as two sequential, non-nested phases inside that shared
operation; each phase has exactly three attempts with a fixed one-second delay
and bounded health codes. The existing lifecycle fence remains the sole
install/teardown owner and gains an explicit quiescing state so capture cannot
restart during graceful shutdown.

**Tech stack:** Kotlin, kotlinx.coroutines, Room v4, Expo native module promises,
existing `ActiveCallTerminalizer`, `CallCaptureLifecycleFence`, and v2 call
persister.

## Global constraints

- No Room schema, protocol, payload, TypeScript, UI, relay, or direct-LAN change.
- Source unregistration happens before waiting for callback serialization.
- Every callback already inside `callbackMutex` finishes before terminalization.
- Pending events reach durable custody before the terminalizer scans local
  ACTIVE rows; terminal idle reaches Room before any in-memory queue is cleared.
- Ordinary call or disabled-config persistence failure leaves shutdown
  unfinalized and all retryable in-memory/durable state intact. Terminal custody
  has exactly 3 attempts, then config persistence has its own exactly 3 attempts;
  neither phase is nested, and each waits exactly 1,000 ms between attempts.
- `CancellationException` propagates by identity. It is never converted to a
  health code or successful shutdown.
- User-requested disable/stop/unpair first obtains graceful terminal custody,
  then persists the disabled setting, then finalizes. A shutdown or config
  persistence failure leaves the operation unfinalized. Concurrent/repeated
  requests share one active shutdown.
- Unexpected Android `onDestroy()` is best-effort only: unregister/close the
  source, clear process memory, and deliberately leave any ACTIVE Room journal
  for Plan 009 startup recovery.
- Preserve Plan 009 atomic local-ownership fencing and the existing
  `ORDER BY createdAt, rowid` equal-time outbox contract; do not duplicate or
  weaken those tests.
- No ADB, emulator, physical device, radio, network, EAS, worktree, or push.

---

## Status

- **Priority**: P0
- **Effort**: M
- **Risk**: HIGH
- **Depends on**: Plan 009
- **Category**: bug
- **Planned at**: commit `7ae95a2`, 2026-08-19

## Drift check

Run before editing production code:

```bash
git diff --stat 7ae95a2..HEAD -- \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateCoordinator.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/ActiveCallTerminalizer.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairWorkflow.kt
```

If an in-scope file changed, compare live behavior against every interface and
ordering assertion below. Stop if another change already owns graceful call
shutdown or makes these boundaries contradictory.

## Current defect

- `CallStateCoordinator.stop()` unregisters the source and immediately resets
  `sessionId`, `pendingEmits`, and `retryJob`; it does not wait for a callback
  already holding `callbackMutex` and does not create terminal idle.
- `setCallCaptureEnabled(false)` persists the flag and invokes the synchronous
  stop, so its promise can resolve while a local ACTIVE canonical remains.
- `stopSyncService` calls `Context.stopService()` directly. `ACTION_STOP`,
  `shutdownForUnpair`, and `onDestroy` also close capture without awaiting the
  Plan 009 terminalizer.
- `UnpairWorkflow` correctly orders stop/await before revoke and wipe, but its
  current `stopAndAwait` implementation does not durably terminalize calls.

## Files

- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateCoordinator.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStateCoordinatorTest.kt`
- Create:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/GracefulCallCaptureShutdown.kt`
- Create:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/GracefulCallCaptureShutdownTest.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/CallCaptureLifecycleTest.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Create:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/TwinotifyCoreModuleWorkflowTest.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/UnpairWorkflowTest.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`
- Modify: `advisor-plans/README.md`

## Interfaces

`CallStateCoordinator` produces one new suspend boundary:

```kotlin
suspend fun quiesceAndTerminalize(
    terminalizeCommittedCalls: suspend () -> Unit,
)
```

`GracefulCallCaptureShutdown.kt` owns bounded orchestration:

```kotlin
internal sealed interface GracefulCallShutdownResult {
    data object Completed : GracefulCallShutdownResult
    data class Failed(val code: String) : GracefulCallShutdownResult
}

internal data class CallShutdownConfigIntent(
    val disableCallCapture: Boolean,
    val disableService: Boolean,
) {
    fun mergedWith(other: CallShutdownConfigIntent): CallShutdownConfigIntent =
        CallShutdownConfigIntent(
            disableCallCapture || other.disableCallCapture,
            disableService || other.disableService,
        )
}

internal suspend fun gracefullyShutdownCallCapture(
    quiesceAndTerminalize: suspend () -> Unit,
    reportFailure: (String) -> Unit,
    delayBeforeRetry: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): GracefulCallShutdownResult

internal suspend fun persistDisabledForCallShutdown(
    persistDisabled: suspend () -> Unit,
    reportFailure: (String) -> Unit,
    delayBeforeRetry: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): GracefulCallShutdownResult

internal class GracefulCallShutdownGate {
    fun start(
        scope: CoroutineScope,
        intent: CallShutdownConfigIntent,
        shutdown: suspend () -> GracefulCallShutdownResult,
    ): Deferred<GracefulCallShutdownResult>

    fun isReserved(): Boolean
    suspend fun awaitRelease()
}
```

`SyncService` companion owns exactly one `GracefulCallShutdownGate`, used
whether `activeInstance` is present or absent. The first caller supplies its
structured scope; later callers share that active deferred. Its identity-safe
completion callback releases the active deferred after every outcome, but
releases admission only after `Completed`, which includes disabled-config
persistence. Failure or cancellation keeps admission reserved until an explicit
retry completes.

Every production registration path (`onStartCommand`, normal
`configureCallCapture`, and debug synthetic startup) checks `isReserved()`
inside its existing lifecycle-fence registration boundary. A reserved start
does not register and reports only `call_shutdown_failed`. Normal service
startup waits on `awaitRelease()` in its structured scope, re-reads
`ServiceConfigStore`, and only then applies the latest capture setting; debug
startup returns false and requires an explicit later retry. The shutdown entry
point establishes reservation synchronously before reading `activeInstance`.

The gate merges concurrent config intent monotonically: capture disable sets
`disableCallCapture`, service stop/unpair sets `disableService`, and concurrent
requests persist the union. `ServiceConfigStore.applyCallShutdownIntent` writes
the requested false bits in one DataStore edit. After each successful config
write, the gate rechecks under its monitor that no stronger intent arrived; if
one did, it runs one new non-nested three-attempt config phase for the merged
intent before returning `Completed`. There are only two monotonic bits, so a
request cannot cause an unbounded upgrade loop.

`TwinotifyCoreModule.kt` adds a testable promise settlement boundary:

```kotlin
internal suspend fun <T> settleTwinotifyPromise(
    code: String,
    boundedMessage: String,
    operation: suspend () -> T,
    resolve: (T) -> Unit,
    reject: (String, String, Throwable?) -> Unit,
) {
    try {
        resolve(operation())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        reject(code, boundedMessage, null)
    }
}
```

The peer-unpair service-job boundary is also testable without Android:

```kotlin
internal suspend fun quiesceServiceJobsAfterCallShutdown(
    fromRelayJob: Boolean,
    activeRelay: Job?,
    stopOtherChildren: suspend () -> Unit,
    cancelAndJoinServiceScope: suspend () -> Unit,
) {
    if (!fromRelayJob) activeRelay?.cancelAndJoin()
    stopOtherChildren()
    if (!fromRelayJob) cancelAndJoinServiceScope()
}
```

The `fromRelayJob=true` caller deliberately leaves its own relay/scope alive
until wipe returns and `InboundDispatcher` requests Android service stop.

Use only these public bounded codes:

```kotlin
const val CALL_SHUTDOWN_FAILED = "call_shutdown_failed"
const val CALL_SHUTDOWN_STALE = "call_shutdown_stale"
```

`CallCaptureLifecycleFence` adds an explicit lease, without holding a JVM
monitor across suspension:

```kotlin
internal data class CallCaptureQuiesceLease(
    val coordinator: CallStateCoordinator?,
    val terminal: Boolean,
)

fun beginQuiesce(terminal: Boolean): CallCaptureQuiesceLease
fun finishQuiesce(lease: CallCaptureQuiesceLease, completed: Boolean)
```

While a lease is active, `start` must reject registration. On failure the same
coordinator remains installed but quiesced so a later retry can finish. On
success it is detached and closed; a non-terminal fence may register again,
while a terminal fence never may.

### Task 1: Quiesce behind callbacks without losing retryable state

**Files:**

- Modify `CallStateCoordinator.kt`
- Modify `CallStateCoordinatorTest.kt`

**Consumes:** existing source registration, `callbackMutex`, `pendingEmits`,
`retryJob`, `drainPending`, and terminal-session sealing.

**Produces:** `quiesceAndTerminalize(terminalizeCommittedCalls)`.

- [ ] **Step 1: Add deterministic RED tests for the exact ordering**

Add tests with controlled deferreds/latches proving:

1. a callback enters `emit`, graceful quiesce unregisters the source, but the
   terminalizer does not begin until that callback leaves `callbackMutex`;
2. a source callback arriving after unregister is ignored;
3. successful pending delivery occurs before `terminalizeCommittedCalls`, and
   terminalization occurs before queue/session cleanup;
4. a failed pending delivery or failed terminalizer leaves the coordinator
   disabled, retains pending/session state, and a repeated call can succeed;
5. cancellation from pending delivery or terminalization is the exact same
   `CancellationException` instance and cleanup does not run;
6. repeated successful quiesce is idempotent: a second empty-journal scan emits
   no additional terminal event.

Use a test-only inspection seam no broader than:

```kotlin
internal data class CallCoordinatorDebugState(
    val registered: Boolean,
    val sessionId: String?,
    val pendingCount: Int,
)
```

Run the new focused tests before production edits:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*CallStateCoordinatorTest*quiesce*'
```

Expected: compile/test RED because the suspend boundary is absent.

- [ ] **Step 2: Implement the smallest safe coordinator boundary**

Implement in this order:

```kotlin
suspend fun quiesceAndTerminalize(terminalizeCommittedCalls: suspend () -> Unit) {
    val handle = synchronized(lock) {
        _status.set(CallCaptureStatus(false, CallCaptureDisabledReason.DISABLED))
        if (registrationQuiesced) null else registration
    }
    handle?.close()
    synchronized(lock) {
        if (registration === handle || handle == null) registrationQuiesced = true
    }

    val activeRetry = synchronized(lock) {
        retryJob.also { retryJob = null }
    }
    activeRetry?.cancelAndJoin()

    callbackMutex.withLock {
        if (!drainPending(scheduleRetryOnFailure = false)) {
            throw ActiveCallRecoveryException("call_shutdown_failed")
        }
        terminalizeCommittedCalls()
        synchronized(lock) {
            registration = null
            registrationQuiesced = false
            sessionId = null
            lastFrameworkState = null
            direction = CallDirection.UNKNOWN
            sequence = 0L
            pendingEmits.clear()
        }
    }
}
```

Refactor `drainPending` only enough to suppress its autonomous retry scheduler
during the owned shutdown attempt. Do not clear state in a `finally` block.
Retain the registration handle until success so a throwing `close()` is a
failed, retryable attempt rather than a false unregistration claim.
Add `registrationQuiesced` under the existing lock: successful unregister marks
it once, retry skips a second `close()`, `start()` resets it when installing a
new source, and best-effort `stop()` closes only a handle not already quiesced.

Change `deliver` and both callers so `CancellationException` is rethrown by
identity. If cancellation occurs after an event was removed from or created for
the pending queue, restore/enqueue that exact event before rethrowing; ordinary
emitter failures still return `false`. Catch neither cancellation nor
terminalizer exceptions in `quiesceAndTerminalize`. Keep `stop()`/`close()` as
the explicit best-effort process teardown path.

- [ ] **Step 3: Run the whole coordinator class twice**

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*CallStateCoordinatorTest'
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*CallStateCoordinatorTest' --rerun-tasks
```

Expected: all tests pass; the second invocation executes rather than reporting
the test task up-to-date.

### Task 2: Bound retries and make the lifecycle fence shutdown-aware

**Files:**

- Create `GracefulCallCaptureShutdown.kt`
- Create `GracefulCallCaptureShutdownTest.kt`
- Modify `SyncService.kt` only for `CallCaptureLifecycleFence`
- Modify `CallCaptureLifecycleTest.kt`

**Consumes:** Task 1 quiesce API, Plan 009 `ActiveCallRecoveryException`, and
the existing active-only startup gate.

**Produces:** bounded graceful-shutdown helper/gate and quiesce lease state.

- [ ] **Step 1: Add RED retry and fence tests**

Prove exactly:

- success performs one attempt and returns `Completed`;
- terminal failures attempt exactly 3 times at 0 ms, 1,000 ms, and 2,000 ms,
  report only the allowlisted shutdown code, then return `Failed`;
- after terminal custody succeeds, config persistence has a separate exactly-3
  budget at 0 ms, 1,000 ms, and 2,000 ms; fail-once then success returns
  `Completed`, while 3 failures return `Failed` without rerunning the terminal
  phase or finalization;
- an arbitrary/oversized exception code maps to `call_shutdown_failed`;
- reporter failure is advisory and does not stop retry;
- cancellation from quiesce, terminalizer, reporter, or retry delay propagates
  unchanged;
- two concurrent gate calls return the same active `Deferred`, including when
  one observes an active service and the other reaches the no-service fallback;
  after a failed result a later call gets a new deferred; after success,
  repeated work is idempotent through the empty journal/coordinator;
- concurrent capture-disable and service-stop intents merge both false bits;
  a stronger intent arriving during the first config write triggers exactly one
  new three-attempt config phase and both callers observe the final result;
- `beginQuiesce` rejects registration immediately, failure retains the same
  coordinator for retry, success detaches/closes it, and terminal success keeps
  the fence permanently closed;
- unexpected `stop(terminal=true)` does not invoke terminalization and leaves
  the injected ACTIVE journal fake unchanged.
- admission is reserved before work/`activeInstance` lookup, remains reserved
  after terminal/config failure or cancellation, and is released only when both
  sequential phases return `Completed`.

Run before implementation:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*GracefulCallCaptureShutdownTest' \
  --tests '*CallCaptureLifecycleTest*quiesce*'
```

Expected: compile/test RED for absent types and lease methods.

- [ ] **Step 2: Implement bounded orchestration**

Implement each helper with its own exactly three attempts; call the config
helper only after the terminal helper returns `Completed`, never inside the
terminal retry loop. Rethrow cancellation before all other exception handling.
Map `ActiveCallRecoveryException("call_recovery_stale")` to
`call_shutdown_stale`; all other ordinary call/config failures become
`call_shutdown_failed`. A failed result must not call
`finishQuiesce(completed=true)`.

The gate must use lazy async plus identity-safe completion clearing, matching
the reviewed Plan 009 startup gate:

```kotlin
val next = scope.async(start = CoroutineStart.LAZY) { shutdown() }
active = next
next.invokeOnCompletion {
    synchronized(this) { if (active === next) active = null }
}
next.start()
```

Do not retain a completed deferred forever.

- [ ] **Step 3: Run focused tests**

Run the command from Step 1. Expected: PASS.

### Task 3: Route capture-disable and service-stop requests through one workflow

**Files:**

- Modify `SyncService.kt`
- Modify `ServiceLifecycleTest.kt`
- Modify `CallCaptureLifecycleTest.kt`
- Modify `TwinotifyCoreModule.kt`
- Modify `ServiceConfigStore.kt`
- Create `TwinotifyCoreModuleWorkflowTest.kt`

**Consumes:** Tasks 1-2 APIs, `DaoActiveCallRecoveryStore`,
`ActiveCallTerminalizer`, `CallStatePersister`, and `ServiceConfigStore`.

**Produces:** suspend service entry points used by both Expo functions and
Android service actions.

- [ ] **Step 1: Add a pure RED command-order seam**

Add an internal workflow function in `SyncService.kt`:

```kotlin
internal suspend fun executeCallCaptureStopRequest(
    sharedShutdown: suspend () -> GracefulCallShutdownResult,
    finalizeStop: suspend () -> Unit,
) {
    when (val result = sharedShutdown()) {
        GracefulCallShutdownResult.Completed -> finalizeStop()
        is GracefulCallShutdownResult.Failed -> throw ActiveCallRecoveryException(result.code)
    }
}
```

RED tests must prove `reserve admission -> unregister/quiesce -> terminal idle
persisted -> persist disabled -> release admission -> finalize`. Disabled config
must not run after terminal-phase failure/cancellation. Finalization must not run
after config-phase failure/cancellation. Config fail-once then success must use
only its 0/1,000 ms attempts and must not rerun terminal custody.

- [ ] **Step 2: Add service-owned graceful entry points**

Add:

```kotlin
suspend fun disableCallCaptureAndAwait(context: Context)
suspend fun shutdownActive(context: Context, fromRelayJob: Boolean = false)
```

Reserve the single companion-owned shutdown gate synchronously before reading
`activeInstance`; use it for both branches. The shared deferred first runs the
terminal helper, then the config helper. For an active service it must:

1. obtain a fence quiesce lease;
2. call the coordinator's Task 1 method, if present;
3. inside that callback construct the existing `ActiveCallTerminalizer` with
   `DaoActiveCallRecoveryStore(reliableDao)` and
   `CallStatePersister(applicationContext)`, then recover the local device;
4. if no coordinator exists, still run the terminalizer so an orphaned ACTIVE
   journal is closed;
5. after terminal success, run the separate three-attempt disabled-config phase;
6. finish the fence and release admission only after both phases complete;
7. publish `call_shutdown_failed`/`call_shutdown_stale` without raw text.

Add this atomic DataStore entry point:

```kotlin
suspend fun applyCallShutdownIntent(
    ctx: Context,
    intent: CallShutdownConfigIntent,
    now: Long = System.currentTimeMillis(),
): ServiceConfig
```

One `edit` sets `CALL_CAPTURE_ENABLED=false` only when requested, sets
`ENABLED=false` plus `LAST_USER_CHANGE_AT=now` only when requested, preserves
all other keys, and returns the reread config. Tests use a fake persistence
lambda to prove the exact merged bits and retry timing; do not claim JVM tests
execute Android DataStore.

For no active service, use that same gate to run the terminalizer directly
against `NotificationDb`, so a JS promise cannot claim completion merely
because no coordinator object exists. Add a race test in which two no-service
callers enter concurrently and prove one terminalizer execution, one shared
deferred, and identical result; add an active/no-service observation race and
prove the companion gate still serializes the durable work. Add both
null-to-active race orders: (a) reservation wins before service publication;
(b) service publishes before reservation but has not registered. In both,
normal and debug registration must recheck reservation inside the lifecycle
fence and remain blocked until both shutdown phases complete; after release,
normal startup must re-read config and the newly persisted false value must keep
capture disabled.

- [ ] **Step 3: Route the Expo functions**

Change `setCallCaptureEnabled(false)` to await the shared two-phase operation
and only then resolve the promise. Keep the enable path unchanged except it may
not bypass an active/failed admission reservation. Submit intent
`CallShutdownConfigIntent(disableCallCapture=true, disableService=false)`.

Change `stopSyncService` to await the shared active/no-active terminal plus
config phases, call `Context.stopService`, and only then resolve. On
bounded failure reject with the existing public promise codes (`CALL_CAPTURE`
or `STOP_SVC`) and no raw database/payload text. Submit intent
`CallShutdownConfigIntent(disableCallCapture=false, disableService=true)`.

Route both functions through `settleTwinotifyPromise`. Put
`catch (cancellation: CancellationException) { throw cancellation }` before
the existing rejection behavior. JVM tests must inject the same cancellation
instance and assert it escapes unchanged while neither resolve nor reject is
called; ordinary failure must reject once with the static public code/message
and never resolve.

- [ ] **Step 4: Route `ACTION_STOP` without blocking the main thread**

Launch the companion-gated two-phase operation without first persisting
disabled. Only `Completed` may cancel relay jobs, remove foreground state, call
`stopSelf`, and complete `shutdownCompleted`. After three config failures, do
not perform those final actions and keep admission reserved for an explicit
retry. On `Failed`, leave the foreground service alive, unregistered, and
retryable; publish the bounded call-shutdown health code. Repeated `ACTION_STOP`
calls share the active job.
`ACTION_STOP` and unpair submit `disableService=true`; a concurrent capture-only
request is merged so both false bits persist before the shared result completes.

Do not call `runBlocking` around the terminalizer or callback mutex.

- [ ] **Step 5: Verify focused service tests**

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*CallCaptureLifecycleTest' \
  --tests '*ServiceLifecycleTest' \
  --tests '*TwinotifyCoreModuleWorkflowTest' \
  --tests '*GracefulCallCaptureShutdownTest'
```

Expected: PASS.

### Task 4: Preserve unpair ordering and unexpected-destroy recovery

**Files:**

- Modify `SyncService.kt`
- Modify `InboundDispatcher.kt`
- Modify `UnpairWorkflowTest.kt`
- Modify `CallCaptureLifecycleTest.kt`

**Consumes:** Task 3 `shutdownActive`, existing `UnpairWorkflow.execute`, and
Plan 009 startup recovery.

**Produces:** terminal call custody before revoke/wipe, without falsely claiming
graceful work during Android process destruction.

- [ ] **Step 1: Add RED unpair and destruction-order tests**

Using real workflow lambdas/fakes, assert:

```text
unregister call source
finish callback already in progress
persist terminal idle for every local ACTIVE call
persist service disabled
stop relay/service jobs
revoke peer when applicable
wipe local state
```

If terminal persistence exhausts its three attempts, assert disabled config,
service-job stop, revoke, and wipe are never invoked and the ACTIVE row remains.
If disabled-config persistence exhausts its separate three attempts after
terminalization, assert service-job stop, revoke, and wipe are not invoked and
admission remains reserved. Prove cancellation identity from
graceful shutdown through `UnpairWorkflow.execute`.

Add a separate `onDestroy` seam test proving it does only terminal fence close,
does not call `ActiveCallTerminalizer`, and leaves the ACTIVE fake journal for a
later Plan 009 recovery.

Add an exact `fromRelayJob=true` service-level scenario. The peer-unpair handler
runs inside the relay collection job, so it must:

1. await graceful call terminalization before any relay cancellation;
2. stop/join other service children;
3. neither cancel nor join the current relay job or its parent service scope;
4. return to `UnpairWorkflow`, complete the `NonCancellable` wipe, and only then
   request `Context.stopService` from `InboundDispatcher`;
5. let `onDestroy` perform the final best-effort relay/scope cancellation after
   wipe has completed.

The test must use the current coroutine as the injected relay job and prove the
workflow reaches `wipe` and `request-service-stop` without cancellation or
deadlock. The local `fromRelayJob=false` branch must still cancel and join the
relay/scope jobs after graceful terminalization.

- [ ] **Step 2: Use graceful shutdown in unpair**

Keep `UnpairWorkflow.execute` itself unchanged. Its existing `stopAndAwait`
lambda must call the graceful `SyncService.shutdownActive` before relay revoke
or local wipe. Do not wrap that failure and continue. Keep the final wipe in
the existing `NonCancellable` block only after graceful stop and revocation
succeed.

`onDestroy()` must call only the immediate terminal fence close plus scope
cancellation. Add a comment that durable ACTIVE state is intentional crash
journal custody, not a successful graceful stop. `SyncService.shutdownActive`
must not call `Context.stopService` in the `fromRelayJob=true` branch;
`InboundDispatcher.handleUnpair` requests it only after `UnpairWorkflow.execute`
has completed its wipe.

- [ ] **Step 3: Run unpair and lifecycle tests**

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*UnpairWorkflowTest' \
  --tests '*CallCaptureLifecycleTest' \
  --tests '*ServiceLifecycleTest' \
  --tests '*TwinotifyCoreModuleWorkflowTest'
```

Expected: PASS.

### Task 5: Prove Room custody, rollback, and idempotence

**Files:**

- Modify `ReliableDeliveryTransactionTest.kt`
- Modify `GracefulCallCaptureShutdownTest.kt`

**Consumes:** real Plan 009 terminalizer/persister/DAO ownership fence and Tasks
1-4 shutdown orchestration.

**Produces:** compiled transaction evidence for durable graceful shutdown.

- [ ] **Step 1: Add Android transaction sources before implementation is final**

Add real Room scenarios proving:

- local ACTIVE ringing/active is CANCELLED at the next sequence and exactly one
  NEW idle outbox row exists before graceful completion;
- two local ACTIVE calls are both terminalized in the existing
  `updatedAt, canonId` scan order before coordinator memory is cleared;
- remote ACTIVE calls and notification canonicals remain unchanged;
- a forced outbox constraint failure rolls back canonical/origin sequence,
  leaves the call ACTIVE, and returns failed/unfinalized shutdown;
- repeating shutdown after success creates no second idle;
- ownership changing to remote during shutdown returns idempotent ownership
  loss and never mutates the remote row/outbox.

Do not add another equal-createdAt ordering test. Retain and cite the Plan 009
hostile-index/`rowid` regression as the single contract for idle-before-later-
ringing send order.

- [ ] **Step 2: Compile Android instrumentation sources**

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL. Do not claim instrumentation execution without an
authorized API-compatible device/emulator.

- [ ] **Step 3: Run idempotence/cancellation JVM tests**

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*GracefulCallCaptureShutdownTest' \
  --tests '*ActiveCallTerminalizerTest' \
  --rerun-tasks
```

Expected: PASS with zero skipped/failing selected tests.

### Task 6: Full verification and independent review

**Files:**

- Modify `advisor-plans/README.md` status only after review CLEAR.

- [ ] **Step 1: Run focused suites twice**

Run coordinator, shutdown, lifecycle, service, terminalizer, and unpair focused
classes once normally and once with `--rerun-tasks`. Record XML counts and
confirm zero failures/errors/skips.

- [ ] **Step 2: Run full native gates**

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest \
  :twinotify-core:compileDebugAndroidTestKotlin \
  :twinotify-core:lintDebug
git diff --check
```

Expected: BUILD SUCCESSFUL, lint zero issues, diff check no output.

- [ ] **Step 3: Request independent review**

The reviewer must inspect the full live diff and evidence, focusing on:

- unregister-before-mutex and callback-before-terminalizer ordering;
- pending custody and clear-only-after-terminal-success;
- bounded retry/exhaustion and cancellation identity;
- lifecycle fence races with registration, re-enable, ACTION_STOP, unpair, and
  unexpected `onDestroy`;
- promise/service/unpair finalization only after durable terminal custody;
- local ownership/rollback/idempotence and no equal-order regression.

Fix every Critical/Important finding TDD-first and rerun all gates. Do not mark
DONE or commit until review returns CLEAR.

- [ ] **Step 4: Mark DONE and commit exact scoped paths**

After CLEAR only, update Plan 010's README status to DONE and commit the exact
implementation paths with:

```text
fix(android): terminalize calls before shutdown
```

Do not push.

## Done criteria

- [ ] A user-requested disable unregisters capture, waits for an in-flight
      callback, durably terminalizes all local ACTIVE calls, persists disabled,
      then resolves.
- [ ] Service stop and unpair do not finalize, revoke, or wipe before terminal
      call custody succeeds.
- [ ] Persistence failure makes exactly three bounded attempts, exposes no raw
      details, retains retryable state, and leaves the operation unfinalized.
- [ ] Cancellation propagates by identity through coordinator, helper, service,
      promise workflow, and unpair workflow.
- [ ] Concurrent/repeated disable/stop requests share work and successful
      repeats emit no duplicate idle.
- [ ] Unexpected `onDestroy` makes no graceful-success claim and leaves ACTIVE
      Room state recoverable by Plan 009.
- [ ] Remote calls/notification rows remain untouched; ownership loss is safe.
- [ ] Existing `createdAt, rowid` equal-time ordering remains green without a
      competing duplicate contract.
- [ ] Focused tests pass twice, Android transaction sources compile, full
      native/lint gates pass, diff check is clean, and independent review is
      CLEAR.

## STOP conditions

- Correctness requires a Room migration, second journal, protocol/schema, or
  payload change.
- A source cannot be unregistered before callback serialization without
  changing the Android telephony contract.
- Graceful shutdown would require clearing queues before terminal persistence.
- Unpair would need to wipe after terminal persistence failure.
- Any implementation needs JS/UI, relay/direct-LAN, devices, radios, network,
  EAS, worktree, or push.

## Maintenance notes

Android can kill the process without awaiting suspend work. This plan guarantees
graceful semantics only for explicit capture disable, service stop, ACTION_STOP,
and unpair. Unexpected process death intentionally relies on the Plan 009 ACTIVE
journal and the next service start; force-stop with no future process start
cannot guarantee peer cleanup and must not be claimed.
