# Plan 020: Stop routine passes from rescanning permission-held mirrors

> **Executor instructions**: Work in the primary checkout only. Read `AGENTS.md`
> fully before editing. Follow strict TDD and retain the meaningful RED. Do not
> create a worktree, push, clear app data, or use a device. Update
> `advisor-plans/README.md` when complete.
>
> **Drift check (run first)**:
> `git diff --stat f119224..HEAD -- mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/NotificationMaterializerTest.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryDaoMaterializationTest.kt`
> If these paths drift, reconcile every fact below before proceeding.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: perf
- **Planned at**: commit `f119224`, 2026-08-27
- **Result**: DONE on 2026-08-27; deterministic REDs, native 562/562,
  Android-test compilation, lint, debug assembly, and independent re-review
  passed

## Why this matters

A permission-blocked canonical remains pending by design, but the current Room
query admits every such row into every materialization pass. Each new inbound
event starts a pass, so N permission-held mirrors followed by N ordinary events
can cause O(N²) platform attempts and retry-row rewrites while Android still
forbids posting. Permission restoration, process restart, and newer-sequence
healing must remain reliable without polling all held work on unrelated traffic.

## Current state

- `ReliableDeliveryDao.pendingMaterialization` admits a retry when
  `retry.disposition='PERMISSION_BLOCKED'`, regardless of the trigger.
- `NotificationMaterializer.materializePending()` has no trigger parameter and
  therefore cannot distinguish ordinary inbound work from a verified permission
  restoration or startup recovery.
- `InboundDispatcher` invokes the materializer after each committed notification
  and call.
- `SyncService.onAppForeground` computes effective availability from both
  `POST_NOTIFICATIONS` and `areNotificationsEnabled()`, but
  `resumePermissionBlockedMaterializationOnForeground` requests a pass even when
  that result is false.
- `TwinotifyNotificationListener.onCreate` runs a startup pass. This must resume
  held work only when posting is actually available.
- A retry row whose sequence is older than `canonical.latestSequence` must never
  block the newer state; preserve this Plan 017 invariant.

## Frozen behavior

Use an explicit closed-world trigger, not a magic Boolean at scattered call
sites. Recommended values are `ROUTINE` and `POST_PERMISSION_AVAILABLE`:

- routine inbound, snapshot, retry-alarm, and unrelated foreground work exclude
  same-sequence `PERMISSION_BLOCKED` rows;
- a newer canonical sequence bypasses an older hold in every trigger;
- service/listener startup includes held rows only when effective post
  availability is true;
- app foreground includes held rows only when effective availability is true;
- permission-held work schedules no alarm;
- cancellation propagates by exact instance and no content-derived value is
  logged.

## Scope

**In scope**:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt`
- focused JVM and Room tests for those symbols

**Out of scope**: Room schema changes, retry timing changes, notification UI,
receipt ordering, transport ACK behavior, service auto-start, and physical-device
evidence.

## Git workflow

- Primary checkout only; preserve unrelated changes.
- Commit only after independent review with `fix(mobile): park permission-held materialization`.
- Do not push.

## Steps

### 1. Capture the repeated-scan failure

Add RED tests requiring:

1. a same-sequence permission hold is absent from a routine DAO query;
2. a permission-restoration query admits it;
3. a newer sequence remains admitted during a routine query;
4. a routine materializer pass with many held rows performs zero platform posts
   and zero retry writes;
5. foreground while effective permission is false updates health but requests no
   pass;
6. foreground/startup with effective permission true requests exactly one
   coalesced restoration pass.

Do not weaken the existing no-alarm, saturation, sequence-fence, or cancellation
tests.

**Verify**:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*NotificationMaterializerTest' --tests '*ServiceLifecycleTest'
```

Expected before production edits: at least one new behavioral failure.

### 2. Add one trigger-aware persistence boundary

Add the trigger to `MaterializationStore.pendingMaterialization`,
`DaoMaterializationStore`, and the DAO query. Bind the SQL clause so a current
permission hold is admitted only for `POST_PERMISSION_AVAILABLE`; keep
`retry.sequence < state.latestSequence` unconditional.

Thread the same enum through `NotificationMaterializer.materializePending`.
Default to `ROUTINE` so alarms and new call sites fail safe. Do not duplicate the
pending query or add an in-memory shadow list.

**Verify**: focused JVM tests above pass.

### 3. Route only real restoration triggers

- Change the foreground helper to invoke the active service only when effective
  post availability is true.
- At service and listener startup, compute the same runtime-plus-global
  availability predicate and choose the restoration trigger accordingly.
- Keep ordinary dispatcher/snapshot/alarm paths routine.
- Preserve the existing `MaterializationRequestGate`; restoration requests must
  coalesce rather than overlap.

**Verify**:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*NotificationMaterializerTest' --tests '*ServiceLifecycleTest' \
  --tests '*MaterializationRequestGate*' \
  :twinotify-core:compileDebugAndroidTestKotlin
```

Expected: all selected tests and Android-test compilation pass.

### 4. Run the native gate and review

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:lintDebug \
  :twinotify-core:testDebugUnitTest \
  :twinotify-core:compileDebugAndroidTestKotlin :app:assembleDebug
git diff --check
```

Require an independent reviewer to trace routine, startup, foreground,
newer-sequence, alarm, exception, and cancellation paths before commit.

## Test plan

- Extend `NotificationMaterializerTest.kt` for trigger selection and zero work on
  routine scans.
- Extend `ReliableDeliveryDaoMaterializationTest.kt` for SQL admission of current
  holds versus newer sequences.
- Extend `ServiceLifecycleTest.kt` for effective foreground/startup routing and
  exact request counts.
- Use deterministic counters and injected availability; no sleeps or device.

## Done criteria

- [x] Routine passes never select a same-sequence permission hold.
- [x] Verified permission restoration/startup selects every current hold once.
- [x] Newer canonical state bypasses an older hold.
- [x] Permission-held work schedules no alarm and no busy retry.
- [x] Full native gate, Android-test compilation, diff check, and independent
      review pass.
- [x] Only in-scope paths and `advisor-plans/README.md` change.

## STOP conditions

- The fix needs a Room migration or destructive fallback.
- A held row can be stranded after process restart with permission already on.
- A newer sequence is excluded by the permission hold.
- Foregrounding would start a stopped service automatically.
- Any test requires content, device identifiers, sleeps, or a real phone.

## Maintenance notes

Any new materialization entry point must declare its trigger explicitly. Review
future SQL changes against both dimensions: retry disposition and sequence
freshness.
