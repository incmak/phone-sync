# Plan 012: Recover from established-route failures without stopping delivery

> **Executor instructions**: Work only in the primary checkout. Use TDD and
> retain the behavioral RED. No worktree, device, network, push, or commit before
> independent lifecycle review. Preserve one and only one active outbox owner.

## Status

- **Priority**: P0
- **Effort**: M
- **Risk**: HIGH
- **Depends on**: Plan 011 only for execution order; no code dependency
- **Category**: bug
- **Planned at**: commit `66dc533`, 2026-08-27

## Drift check

```bash
git diff --stat 66dc533..HEAD -- \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveServiceTransportTest.kt
```

## Why this matters

Route-open failures are retried, but failures after authentication are not. An
exception from a LAN `send`, the outbox pump, or `awaitClosed` escapes
`TransportCoordinator.run()`. `SyncService` owns one transport job and does not
restart a failed child, so routine Wi-Fi loss can stall both LAN and relay until
the service itself is restarted.

## Current state

- `TransportCoordinator.kt:89-112` catches nothing around `carry(session)`.
- `TransportCoordinator.kt:136-160` runs the close waiter and pump in one child
  scope; a pump exception cancels the scope and escapes.
- `SyncService.kt:927-1051` launches one `LiveServiceTransportLoop.run` job.
- Current tests finish sessions normally; none makes `send` throw after LAN
  authentication and proves relay opens next.

Cancellation must still escape by identity. Session close must finish before a
new route is granted, and the backoff stability window must retain its current
anti-spin semantics.

## Scope

**In scope**:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveServiceTransportTest.kt`
- `advisor-plans/README.md`

**Out of scope**: route preference persistence, TLS/NSD, RelayTransport frame
semantics, Room schema, retry constants unless a test proves the existing
policy impossible.

## Implementation steps

### 1. Capture established-session failure REDs

Add deterministic tests for:

- LAN authenticates, first `send` throws an ordinary I/O exception, LAN closes
  exactly once, relay authenticates, and the same durable row is retried;
- `awaitClosed` throws after authentication and the loop reconnects;
- outbox selection/marking throws and the coordinator backs off rather than
  terminating or spinning;
- a `CancellationException` from send/close propagates unchanged and opens no
  replacement route;
- replacement never opens until the prior session's blocked close finalizer is
  released;
- repeated short failures advance backoff; a sustained session resets it.

The pre-fix RED must show `run()` completed exceptionally or relay never opened.

### 2. Convert ordinary carry failure into route loss

Around the established-session boundary only:

1. catch `CancellationException` and rethrow it;
2. retain a bounded internal failure code for health/logging, never raw endpoint
   or message content;
3. close and fully join the granted session;
4. publish `RECONNECTING`;
5. apply existing stability/backoff accounting;
6. continue the coordinator loop so route order can fall back.

Do not add an outer service restart loop. The coordinator remains the sole
route and outbox owner.

### 3. Run focused and full native gates

```bash
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest --tests '*TransportCoordinatorTest' --tests '*LiveServiceTransportTest' --rerun-tasks
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug
git diff --check
```

Expected: focused failure/fallback matrix and full native gates pass. Review must
trace cancellation, close/join ordering, single ownership, and backoff.

## Done criteria

- [ ] Established send, close-waiter, and outbox failures have RED-first tests.
- [ ] Ordinary failure keeps `run()` alive and permits relay fallback.
- [ ] Old and new sessions cannot overlap.
- [ ] Cancellation propagates by identity and opens no replacement.
- [ ] Full native gates and independent review pass.

## STOP conditions

- Fix requires parallel route grants or a second outbox pump.
- Session close cannot be bounded/joined without weakening cancellation.
- Test needs sleeps instead of deterministic seams.
- Live code drift already adds a competing restart owner.
