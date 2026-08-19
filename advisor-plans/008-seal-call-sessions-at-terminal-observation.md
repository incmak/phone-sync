# Plan 008: Seal call sessions when terminal idle is observed

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` to implement this plan task-by-task.
> Work only in the primary checkout. Never create a worktree. Follow TDD, do
> not use devices or radios, do not push, and do not commit until independent
> review is clear.

**Goal:** Prevent a failed terminal persistence attempt from reusing and
resurrecting a completed call session when a later call begins.

**Architecture:** Treat framework `IDLE` observation as the logical end of the
in-memory session before attempting persistence. The immutable idle event stays
in the existing ordered retry queue, while a subsequent ringing/off-hook event
allocates a new UUID and starts again at sequence 1. Delivery completion must no
longer mutate global session state, because an older queued idle may complete
after the next session has already begun.

**Tech stack:** Kotlin, kotlinx.coroutines test scheduler, existing
`CallStateCoordinator` ordered retry queue.

## Global constraints

- Preserve the privacy-bounded `call.state` payload and wire schema unchanged.
- Preserve ordered durable custody: an older pending idle must be delivered
  before any event from the next session.
- Do not add Room/DataStore persistence in this plan. Process-restart and
  graceful-stop recovery are a separate follow-up.
- Do not use ADB, emulators, physical devices, radios, network, EAS, or UI.

---

## Status

- **Priority**: P0
- **Effort**: S
- **Risk**: MED
- **Depends on**: Plan 007
- **Category**: bug
- **Planned at**: commit `70cb092`, 2026-08-19

## Current defect

`CallStateCoordinator.onFrameworkState()` creates an idle event, but
`sessionId` is cleared only by `finalizeEvent()` after `emit` succeeds. If idle
persistence fails and a new framework call arrives, the new ringing/active
events reuse the completed session UUID with higher sequences. The remote
reducer accepts those higher sequences and can resurrect a terminal call.

## Files

- Modify:
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateCoordinator.kt`
- Modify:
  `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/call/CallStateCoordinatorTest.kt`
- Modify: `advisor-plans/README.md`

## Interfaces

- Consumes: existing `CallStateEvent`, `CallFrameworkState`, ordered
  `pendingEmits`, and injected `sessionIdFactory`.
- Produces: unchanged public `CallStateCoordinator` API; changed internal
  invariant that `sessionId == null` immediately after an idle event is created.

### Task 1: Reproduce cross-call resurrection

- [ ] **Step 1: Add a failing deterministic test**

Add a test whose session factory returns two distinct lower-case UUIDs. Allow
the first ringing event to persist, fail every attempt to persist its idle, then
emit ringing for a second call. Assert that the newly returned/queued ringing
event uses the second UUID and sequence 1, not the first UUID and sequence 3.

```kotlin
@Test
fun failedIdleSealsSessionAndNextCallUsesFreshIdentity() = runTest {
    val source = FakeSource()
    val attempted = mutableListOf<CallStateEvent>()
    val ids = ArrayDeque(listOf(SESSION_ID, SECOND_SESSION_ID))
    var allowIdle = false
    val coordinator = CallStateCoordinator(
        source = source,
        emit = { event ->
            attempted += event
            if (event.state == "idle" && !allowIdle) error("terminal sink unavailable")
        },
        sessionIdFactory = { ids.removeFirst() },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    coordinator.start()
    source.emit(CallFrameworkState.RINGING)
    source.emit(CallFrameworkState.IDLE)
    testScheduler.runCurrent()

    source.emit(CallFrameworkState.RINGING)
    testScheduler.runCurrent()

    val secondRinging = attempted.last { it.state == "ringing" }
    assertEquals(SECOND_SESSION_ID, secondRinging.callSessionId)
    assertEquals(1L, secondRinging.sequence)
    coordinator.close()
}
```

- [ ] **Step 2: Run the focused test and capture RED**

Run:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*CallStateCoordinatorTest.failedIdleSealsSessionAndNextCallUsesFreshIdentity'
```

Expected: FAIL because the second ringing reuses `SESSION_ID` and continues the
old sequence.

### Task 2: Seal terminal state before persistence

- [ ] **Step 1: Move logical terminal cleanup into event creation**

Inside the existing synchronized event-construction block, build the idle event
from the current UUID/direction/next sequence, then immediately clear only the
live-session fields before returning that event:

```kotlin
val event = CallStateEvent(
    callSessionId = requireNotNull(sessionId),
    state = mappedState,
    direction = direction,
    sequence = sequence,
)
if (frameworkState == CallFrameworkState.IDLE) {
    sessionId = null
    lastFrameworkState = CallFrameworkState.IDLE
    direction = CallDirection.UNKNOWN
    sequence = 0L
}
event
```

Remove `finalizeEvent()` and its calls. A successfully delivered old idle must
never clear a newer session.

- [ ] **Step 2: Run the focused class**

Run:

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*CallStateCoordinatorTest'
```

Expected: PASS.

### Task 3: Prove ordered recovery across two calls

- [ ] **Step 1: Add the full retry-order regression**

Extend the test so the first idle fails, the second call emits ringing,
off-hook, and idle while the old terminal remains pending, then enable the sink
and advance past `RETRY_DELAY_MS`. Assert successful durable order exactly:

```kotlin
assertEquals(
    listOf(
        SESSION_ID to "ringing",
        SESSION_ID to "idle",
        SECOND_SESSION_ID to "ringing",
        SECOND_SESSION_ID to "active",
        SECOND_SESSION_ID to "idle",
    ),
    delivered.map { it.callSessionId to it.state },
)
assertEquals(
    listOf(1L, 2L, 1L, 2L, 3L),
    delivered.map { it.sequence },
)
```

Also assert duplicate idle after logical close is ignored.

- [ ] **Step 2: Run focused tests twice**

Run the focused class normally, then again with `--rerun-tasks`.

Expected: both executions PASS and actually run the test tasks.

### Task 4: Full verification and review

- [ ] **Step 1: Run native gates**

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk \
  ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest \
  :twinotify-core:compileDebugAndroidTestKotlin \
  :twinotify-core:lintDebug
git diff --check
```

Expected: `BUILD SUCCESSFUL`; diff check exit 0.

- [ ] **Step 2: Request independent review**

The reviewer must focus on session identity, sequence reset, queued idle/new-call
ordering, duplicate callbacks, retry reentrancy, and the risk that an older idle
completion clears a newer session.

- [ ] **Step 3: Mark DONE and commit only after CLEAR**

Commit subject:

```text
fix(android): seal terminal call sessions
```

## Done criteria

- [ ] An observed idle logically closes the current in-memory session even when
      persistence fails.
- [ ] A later call always receives a fresh UUID and starts at sequence 1.
- [ ] Pending terminal idle remains ahead of the next session in durable order.
- [ ] Delivery completion of an older idle cannot clear a newer live session.
- [ ] Duplicate idle remains ignored.
- [ ] Focused tests pass twice, full native gates pass, and independent review
      has no Critical/Important findings.

## STOP conditions

- Correctness requires changing the `call.state` wire schema.
- Fixing the in-memory invariant requires a Room migration or new durable store.
- Existing ordered retry behavior cannot preserve old-idle-before-new-call.
- Any device, radio, network, direct-LAN, UI, or E2E work becomes necessary.

## Self-review

- Spec coverage: closes only the proven cross-call UUID resurrection defect;
  restart/stop recovery remains explicitly separate.
- Placeholder scan: no TODO/TBD or unspecified implementation step remains.
- Type consistency: all names match current `CallStateCoordinator` and
  `CallStateEvent` APIs.
