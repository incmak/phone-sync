# Bound Capture Lanes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a malformed notification capture from retrying forever or allowing its canonical lane to grow without bound, while preserving per-notification ordering and retrying transient storage or keystore failures.

**Architecture:** Each canonical lane remains a single coroutine with a bounded two-slot state buffer: one ordered head and one replaceable latest later state. A conflated wake signal never carries data. While the head persists, later commands replace only that second slot; the first command therefore cannot be overtaken before the actor starts. The persister explicitly marks validation/encoding failures as permanent; the coordinator drops only that failed command and immediately continues with the retained state. Pairing deferral is coordinated by a monotonic resume generation: a no-peer result retries when pairing resumed before it could publish a deferral, and an already-published head is atomically completed without re-enqueueing it over retained later state. All other failures retain retry-at-the-head behavior.

**Tech Stack:** Kotlin, kotlinx.coroutines channels/test scheduler, Android Room/DataStore/Android Keystore, Gradle JVM tests.

## Global Constraints

- Work in the primary checkout; do not create a worktree, commit, push, or stage unrelated work.
- Keep one actor per `canonId`; commands for different IDs may run concurrently.
- The listener callback remains non-blocking.
- Retain the exact `CancellationException` instance; never wrap or convert cancellation.
- `CaptureNotPairedException` remains a latest-state deferred path until `resumeDeferred()`.
- Room and keystore failures stay retryable without a delay sleep in tests.
- Only state commands for one canonical notification are conflated; no cross-canonical or control-lane compaction is introduced.
- Capture no notification content in evidence.

---

### Task 1: Define and verify capture-failure and lane-buffer semantics

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCommand.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCoordinator.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/CaptureCoordinatorTest.kt`

**Interfaces:**
- Consumes: `CapturePersister.persist(command: CaptureCommand): CapturePersistResult`.
- Produces: `CapturePermanentException(message: String, cause: Throwable? = null)` for deterministic non-retryable persistence classification; all other failures remain retryable by contract.
- Produces: bounded per-canonical state through an ordered `head`, replaceable `latest`, and `Channel<Unit>(Channel.CONFLATED)` wake signal; a successful `submit` means the command is the active/next head or the newest retained later state.

- [x] **Step 1: Write failing coordinator tests**

Add these tests to `CaptureCoordinatorTest` using `runTest`, `CompletableDeferred`, and virtual time only:

```kotlin
@Test
fun permanentFailureDropsHeadAndAllowsLatestLaterStateToPersist() = runTest {
    var badAttempts = 0
    val persisted = mutableListOf<String>()
    val coordinator = CaptureCoordinator(this, CapturePersister { command ->
        if (command.sourceKey == "bad") {
            badAttempts += 1
            throw CapturePermanentException("invalid payload")
        }
        persisted += command.sourceKey
        CapturePersistResult(1)
    }, laneIdleMs = 50)

    coordinator.submit(PostCommand("same", "bad", post("same")))
    runCurrent()
    coordinator.submit(RemoveCommand("same", "good", "app_cancel", 2_000))
    advanceUntilIdle()

    assertEquals(1, badAttempts)
    assertEquals(listOf("good"), persisted)
}

@Test
fun laterStateIsConflatedWhileTransientHeadFailureRetries() = runTest {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val persisted = mutableListOf<String>()
    val coordinator = CaptureCoordinator(this, CapturePersister { command ->
        if (command.sourceKey == "head") {
            started.complete(Unit)
            release.await()
        }
        persisted += command.sourceKey
        CapturePersistResult(persisted.size.toLong())
    }, laneIdleMs = 50)

    coordinator.submit(PostCommand("same", "head", post("same")))
    started.await()
    coordinator.submit(PostCommand("same", "stale", post("same")))
    coordinator.submit(RemoveCommand("same", "latest", "app_cancel", 2_000))
    release.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf("head", "latest"), persisted)
}

@Test
fun pendingBurstKeepsOrderedHeadAndOnlyLatestLaterState() = runTest {
    val persisted = mutableListOf<String>()
    val coordinator = CaptureCoordinator(this, CapturePersister { command ->
        persisted += command.sourceKey
        CapturePersistResult(persisted.size.toLong())
    }, laneIdleMs = 50)

    coordinator.submit(PostCommand("same", "first", post("same")))
    coordinator.submit(PostCommand("same", "stale", post("same")))
    coordinator.submit(RemoveCommand("same", "latest", "app_cancel", 2_000))
    advanceUntilIdle()

    assertEquals(listOf("first", "latest"), persisted)
}
```

Add an `internal` `onLaneCompletionForTest: ((Throwable?) -> Unit)? = null` constructor seam. Register it with the launched lane job's `invokeOnCompletion`, then assert a persister-thrown `CancellationException` is observed by identity (`assertSame(expected, observed)`), not merely by class or message. Do not use `CoroutineExceptionHandler`: a normally cancelled coroutine is not reportable through that handler.

- [x] **Step 2: Run the focused test to verify RED**

Run:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*CaptureCoordinatorTest'
```

Expected: the permanent-head test fails because `CapturePermanentException` does not exist and/or current `persistSafely` retries it; the burst and transient-head tests fail because the unbounded queue persists `stale` before `latest`.

- [x] **Step 3: Implement the smallest coordinator contract**

In `CaptureCommand.kt`, add explicit classifications:

```kotlin
class CapturePermanentException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
```

In `CaptureCoordinator.kt`:

```kotlin
private class Lane(
    val signal: Channel<Unit> = Channel(Channel.CONFLATED),
    var active: Boolean = true,
    var waitingForPeer: Boolean = false,
    var head: CaptureCommand? = null,
    var latest: CaptureCommand? = null,
)
```

Under `laneLock`, `submit` assigns an empty `head`; otherwise it replaces `latest`, then signals `Unit`. The worker reads `head`, persists it, and only then advances `head = latest; latest = null`. A no-peer catch compares its persisted-attempt generation with the monotonic `resumeDeferred()` generation before publishing `waitingForPeer`; if pairing already resumed it retries the existing head instead. If resume arrives after publication but before completion, it marks the lane for completion rather than enqueueing the old head, and completion promotes retained `latest` (or the newer deferred replacement). This gives constant per-canonical memory while preserving the first command's order. In `persistSafely`, keep the existing cancellation branch first, retain `CaptureNotPairedException` behavior, then add this branch before generic retry:

```kotlin
} catch (error: CapturePermanentException) {
    logPermanentFailure(command, error)
    return
} catch (error: Throwable) {
    logPersistFailure(command, error)
    delay(delayMs)
    delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
}
```

All failures other than `CancellationException`, `CaptureNotPairedException`, and `CapturePermanentException` deliberately reach the generic retry branch. Do not close producer-visible channels, do not add a worker pool, and do not use error-message matching.

Attach the test seam without changing production failure behavior:

```kotlin
}.also { job ->
    onLaneCompletionForTest?.let(job::invokeOnCompletion)
}
```

- [x] **Step 4: Run focused tests to verify GREEN**

Run:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*CaptureCoordinatorTest'
```

Expected: all coordinator tests pass; the permanent command is attempted once, the retained latest state persists once, and no stale buffered command persists.

### Task 2: Classify production encoding/validation failures at the durable boundary

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/CaptureCoordinatorTest.kt`

**Interfaces:**
- Consumes: generated payload, `ProtocolJson.encodeInner`, and `ProtocolJson.encodeEnvelope` validation failures.
- Produces: `CapturePermanentException` only for deterministic notification payload/protocol/envelope validation errors. Room, DataStore, peer lookup, crypto-store, nonce, and encryption runtime failures remain unclassified/retryable.

- [x] **Step 1: Write the failing durable-classification test**

Add a JVM test for the internal, production-used `captureValidated` helper that causes an explicit `IllegalArgumentException` at the validation boundary and asserts it is exposed as `CapturePermanentException` with the original exception as `cause`.

```kotlin
val cause = IllegalArgumentException("envelope too large")
val error = assertFailsWith<CapturePermanentException> {
    captureValidated { throw cause }
}
assertSame(cause, error.cause)
```

The helper must be `internal` and production-used, not a test-only alternative path.

- [x] **Step 2: Run the focused test to verify RED**

Run:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*CaptureCoordinatorTest'
```

Expected: FAIL because the durable boundary leaves the validation exception untyped.

- [x] **Step 3: Wrap only deterministic validation boundaries**

Implement one internal production-used helper:

```kotlin
internal inline fun <T> captureValidated(block: () -> T): T = try {
    block()
} catch (error: IllegalArgumentException) {
    throw CapturePermanentException("capture payload rejected", error)
}
```

Use it around notification `NotifPostBuilder.build`/payload serialization, `ProtocolJson.encodeInner`, and `ProtocolJson.encodeEnvelope`. Keep peer lookup, Room reads/transactions, identity, `CryptoStore`, `NonceSource`, and `Encrypter.encrypt` outside it so intermittent storage/keystore failures continue through the coordinator's retry branch. Re-throw `CaptureNotPairedException` before the helper can wrap it.

- [x] **Step 4: Run focused tests to verify GREEN**

Run:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*CaptureCoordinatorTest'
```

Expected: all focused tests pass; the cause identity survives classification.

### Task 3: Verify the complete native surface and record evidence

**Files:**
- Create: `.omo/evidence/plan-016-capture-lane-recovery.md`
- Modify: `docs/superpowers/plans/2026-08-27-bound-capture-lanes.md`

- [x] **Step 1: Inspect the final scoped diff**

Run:

```bash
git diff --check -- mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCommand.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCoordinator.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/CaptureCoordinatorTest.kt docs/superpowers/plans/2026-08-27-bound-capture-lanes.md
```

Expected: no whitespace errors.

- [x] **Step 2: Run the full native gate**

Run:

```bash
cd mobile/android && ./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 3: Record exact evidence and plan self-review**

Write `.omo/evidence/plan-016-capture-lane-recovery.md` with each scenario, command, binary observable, and generated artifact path:

- permanent validation failure: one attempted head, later state persisted;
- bounded state buffer: head plus latest only, no stale intermediate state;
- transient failure: retry then success with head preserved;
- unpaired state: latest deferred then resumed;
- pairing-resume races: resume before no-peer publication retries the head, while resume after publication promotes retained latest without duplicating the old head;
- cancellation: exact exception object rethrown;
- cancellation retirement: a cancelled worker atomically retires only its lane, never requeues its aborted head, and a later same-canon state starts a fresh worker;
- diagnostics: permanent and retryable capture failures emit fixed, content-free codes with no notification identifiers, exception message, or throwable stack;
- full native gate and `git diff --check`.

Before handing off, re-read this plan and verify: all requirements map to tests; failure classes and helper names match; no incomplete markers remain; and no test uses wall-clock sleep or notification content.
