# Plan 022: Bound capture coordination across distinct canonical IDs

> **Executor instructions**: Work in the primary checkout only. Read `AGENTS.md`
> fully and use strict TDD. This is a high-risk listener reliability change: do
> not improvise a drop policy. Retain every meaningful RED, require independent
> lifecycle/privacy review, and update `advisor-plans/README.md` only after all
> gates pass. No worktree, push, device use, data clear, or private content logs.
>
> **Drift check (run first)**:
> `git diff --stat f119224..HEAD -- mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCoordinator.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotificationListenerBridge.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureReconciliation.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/OutboundSink.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener`

## Status

- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: none
- **Category**: tech-debt
- **Planned at**: commit `f119224`, 2026-08-27

## Why this matters

Plan 016 bounded each canonical lane to an ordered head plus one latest state,
but the process can still create one map entry, coroutine, and retained command
for every distinct canonical ID. During an unpaired interval or a long transient
Room/crypto outage, an adversarial or noisy notification source can grow these
process-wide structures without bound. Simply rejecting admission is unsafe:
listener callbacks currently `check(submit(...))`, and silent loss can leave the
peer with stale state.

## Current state

- `CaptureCoordinator` owns unbounded `lanes` and `deferredUntilPaired`
  `ConcurrentHashMap`s.
- `submit` creates and launches a lane for every new `canonId`; only the contents
  of an individual lane are conflated.
- transient persistence retries its head forever with bounded delay; a no-peer
  result retains one command per canon until `resumeDeferred()`.
- `TwinotifyNotificationListener.capturePosted`,
  `submitRemovalWithObservation`, and `DurableOutboundSink` turn `false` into an
  exception using `check`.
- The bound listener can reconstruct final desired state from
  `NotificationListenerBridge.activeSourceSnapshots()` plus durable active
  origin rows. `CaptureReconciliation.missingActiveStates` already identifies
  removals missing from the platform snapshot.
- Intermediate notification updates may be conflated; final desired state,
  removal, per-canon order, cancellation identity, and privacy must be preserved.

## Frozen design

Implement a process-wide capacity with two admission modes:

- listener callbacks use nonblocking typed admission;
- suspend-capable non-listener callers wait for capacity or use an explicitly
  durable path rather than crashing;
- overflow sets one process-wide reconciliation latch, never one object per
  rejected canon;
- when capacity becomes available, one reconciliation pass captures the current
  platform snapshot and durable missing-active set, then feeds work in bounded
  batches;
- if the listener is detached, retain only the single reconciliation-needed
  latch and run it at the next attach/rebind;
- logs and metrics use fixed codes/counters only, never canon, source key,
  package, title, text, exception message, or stack.

Use an injected small limit in tests and a documented production limit. The
limit applies to the union of active, queued, and deferred canonical IDs. Do not
replace unbounded per-canon maps with another unbounded overflow collection.

## Scope

**In scope**:

- capture coordinator, listener, listener bridge, reconciliation helper, durable
  outbound adapter, and focused JVM tests under
  `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener`

**Out of scope**: notification payload/protocol changes, Room migrations,
transport queues, service startup, UI, package denylist semantics, and changing
the per-canon head/latest ordering proven by Plan 016.

## Git workflow

- Primary checkout only; preserve unrelated changes.
- Commit after review as `fix(mobile): bound distinct capture lanes`.
- Do not push.

## Steps

### 1. Capture unbounded admission and crash behavior

Add RED tests with `maxCanonicalLanes=2` that require:

1. two blocked distinct canons occupy capacity and a third does not increase any
   retained map/coroutine count;
2. listener post/removal callbacks do not throw on capacity pressure;
3. only one reconciliation request is retained for a burst of 1,000 distinct
   overflow canons;
4. a suspend-capable `DurableOutboundSink` call is not silently accepted or lost;
5. final active snapshots and missing-active removals are recovered exactly once
   after a slot opens;
6. cancellation keeps its exact instance and frees capacity;
7. no-peer resume preserves the existing generation and latest-state race tests.

**Verify**:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*CaptureCoordinatorTest' --tests '*CaptureReconciliationTest' \
  --tests '*TwinotifyNotificationListenerTest'
```

Expected before production edits: the new capacity and non-throwing callback
tests fail.

### 2. Add closed-world admission and one global capacity

Replace the Boolean result with a sealed result such as `Accepted`,
`ReconcileRequired`, and `Closed`. Keep the scope-active guard. Under `laneLock`,
reserve capacity before installing or launching a new lane and release it on
every retirement/deferral/cancellation path. Expose only test counters, not
production IDs.

Do not hold `laneLock` across suspension, Room, crypto, platform snapshot reads,
or coroutine launch callbacks.

### 3. Make overflow healable and listener-safe

- Remove callback `check` calls. Handle `ReconcileRequired` by setting one
  process latch and scheduling the bounded recovery loop.
- Build recovery from immutable active snapshots and
  `CaptureReconciliation.missingActiveStates`; it must reconstruct final posts
  and terminal removals without replaying intermediate content.
- If capacity remains full, leave the same latch set and retry only when a lane
  retires or pairing/listener state changes. Do not timer-poll.
- Ensure `onListenerConnected` consumes any pending latch after its normal
  snapshot.
- Give suspend-capable outbound callers a bounded wait/admission API; preserve
  cancellation identity and never claim durability before admission.

### 4. Run mutation-sensitive stress tests

Temporarily remove the global capacity check and confirm the distinct-canon
stress test fails. Restore it. Temporarily remove the reconciliation latch and
confirm the final-state test fails. Restore it.

**Verify**: focused tests pass with deterministic schedulers and no sleeps.

### 5. Run full gates and review

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:lintDebug \
  :twinotify-core:testDebugUnitTest \
  :twinotify-core:compileDebugAndroidTestKotlin :app:assembleDebug
git diff --check
```

Require independent review of every lane-retirement, cancellation,
pair-deferral, listener-detach, and reconciliation race.

## Test plan

- Extend `CaptureCoordinatorTest.kt` with small-cap deterministic saturation,
  retirement, pairing, cancellation, and 1,000-distinct-canon cases.
- Extend `CaptureReconciliationTest.kt` for active final state and missing-source
  removal recovery.
- Add/extend listener tests proving callbacks never throw under pressure and no
  private fields enter diagnostics.
- Preserve every existing Plan 016 race test unchanged.

## Done criteria

- [ ] Active plus deferred distinct canonical IDs have a hard process limit.
- [ ] Overflow retains at most one reconciliation latch, not per-canon objects.
- [ ] Listener callbacks do not throw or silently claim durable admission.
- [ ] Final posts and removals heal after pressure clears or listener rebinds.
- [ ] Existing ordering, pairing, cancellation, and privacy tests remain green.
- [ ] Full native gate, diff check, and independent lifecycle/privacy review pass.

## STOP conditions

- Correct recovery requires storing notification content in a new schema.
- Any overflow policy can silently lose a terminal removal or report durability
  before persistence.
- A lock must be held across platform/Room work.
- Existing per-canon order, exact cancellation, or no-peer race tests regress.
- The only proposed fix is a larger but still unbounded map.

## Maintenance notes

Capacity is a correctness boundary, not merely tuning. Any future admission path
must specify whether it is nonblocking/reconcilable or suspend-capable/durable.
