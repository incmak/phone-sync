# Plan 021: Release inbound state serialization before platform materialization

> **Executor instructions**: Work in the primary checkout only. Read `AGENTS.md`
> fully, use strict TDD, and retain the meaningful RED. Do not use a worktree,
> push, clear app data, or use a device. Update `advisor-plans/README.md` after an
> independent correctness review.
>
> **Drift check (run first)**:
> `git diff --stat f119224..HEAD -- mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: HIGH
- **Depends on**: Plan 020
- **Category**: perf
- **Planned at**: commit `f119224`, 2026-08-27

## Why this matters

`InboundDispatcher` currently holds one global `stateMutex` while it runs
`NotificationMaterializer`. Platform notification IPC, image decode, receipt
crypto, and Room work can therefore stop every unrelated notification and call
delivery behind one slow or blocked effect. Durable custody must remain serialized
where required, but platform materialization should be requested after the lock
is released through the process-wide coalescing gate already owned by
`SyncService`.

## Current state

- Notification dispatch at `InboundDispatcher.kt:412-543` performs duplicate,
  authorization, reduction, supersession preparation, and
  `commitInboundDesired` under `stateMutex`.
- Its committed/duplicate branch directly constructs a materializer and calls
  `materializePending()` before leaving the lock.
- Call dispatch at `InboundDispatcher.kt:551-676` repeats the same pattern.
- `SyncService.requestPendingMaterialization()` already uses
  `MaterializationRequestGate` to guarantee one active pass plus one coalesced
  follow-up.
- Production constructs the dispatcher in `SyncService.onCreate`; the internal
  constructor is also used by focused control tests.
- Acceptance may follow durable Room custody. It must not depend on the platform
  effect completing, and no ACK may precede the durable commit.

## Frozen design

Refactor each desired-state path into:

1. a locked phase that returns a small closed-world result containing the exact
   `InboundDispatchResult` and whether materialization is required;
2. an unlocked phase that calls an injected nonblocking materialization requester;
3. return of the already-durable dispatch result.

The production requester must be `SyncService`'s existing coalesced request
path. Do not create a second scope, mutex, materializer job, or fire-and-forget
global. Rejection, duplicate, stale-receipt, supersession, peer-cancel, and
cancellation semantics stay byte-for-byte compatible.

## Scope

**In scope**:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- focused dispatcher/service JVM tests

**Out of scope**: DAO/schema changes, control/snapshot/unpair custody semantics,
transport protocol changes, receipt format, notification visuals, and new
background scopes.

## Git workflow

- Primary checkout only; preserve unrelated work.
- Commit after review as `perf(mobile): release inbound lock before materialization`.
- Do not push.

## Steps

### 1. Prove platform work blocks unrelated durable ingress

Add a deterministic RED test with an injected materialization requester that
blocks. Dispatch one committed canonical, then dispatch an unrelated canonical
while the first request remains blocked. Require the second durable commit/result
to complete before releasing the blocker. Cover one notification and one call so
both production branches are mutation-sensitive.

Also assert:

- no request occurs for rejection or receipt conflict;
- committed and qualifying duplicate paths request work;
- cancellation from the locked transaction propagates by exact instance;
- acceptance is impossible before the durable journal callback returns.

**Verify**:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*InboundDispatcherControlTest' --tests '*ServiceLifecycleTest'
```

Expected before the fix: the unrelated-dispatch concurrency test fails or times
out under the deterministic test scheduler.

### 2. Add the injected requester and locked result

Add a small `fun interface` or lambda parameter to the internal dispatcher
constructor. The production constructor must require or receive the service
requester; tests may inject a counter/blocker. Avoid a default that constructs a
materializer inside the dispatcher.

For notification and call branches, return a private data/sealed result from
`stateMutex.withLock`, then request materialization outside it. Keep all Room
identity, authorization, exact-set supersession, reduction, and receipt-journal
work inside the lock.

### 3. Wire the shared service gate

Construct `InboundDispatcher` with `requestPendingMaterialization` from
`SyncService.onCreate`. If Plan 020 added a trigger, pass the routine trigger.
The requester must return quickly and coalesce through the existing
`MaterializationRequestGate`.

**Verify**: the focused tests pass with no sleeps.

### 4. Run full gates and independent review

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:lintDebug \
  :twinotify-core:testDebugUnitTest \
  :twinotify-core:compileDebugAndroidTestKotlin :app:assembleDebug
git diff --check
```

The reviewer must trace both LAN and relay callers and confirm that durable
custody/receipt-before-ACK ordering is unchanged.

## Test plan

- Add deterministic lock-boundary tests under
  `InboundDispatcherControlTest.kt` or a new focused
  `InboundDispatcherConcurrencyTest.kt`.
- Extend `ServiceLifecycleTest.kt` to prove production wiring uses the one shared
  request gate.
- Exercise commit, duplicate, rejection, receipt conflict, call, notification,
  cancellation, and coalesced follow-up.

## Done criteria

- [ ] No platform materializer or requester runs while `stateMutex` is held.
- [ ] Unrelated durable ingress progresses while the first platform pass blocks.
- [ ] Production uses exactly one process coalescing gate.
- [ ] Durable custody and receipt-before-ACK behavior is unchanged.
- [ ] Full native gate, diff check, and independent transport/Room review pass.

## STOP conditions

- Moving work outside the lock makes a receipt, supersession bundle, peer cancel,
  or canonical commit non-atomic.
- A new coroutine scope or second materialization gate appears necessary.
- A route acknowledges before the durable transaction returns.
- The fix requires protocol or Room schema changes.

## Maintenance notes

The lock protects durable state transitions, not Android IPC. New desired-state
handlers should return durable intent from the lock and request platform work
through the same service gate.
