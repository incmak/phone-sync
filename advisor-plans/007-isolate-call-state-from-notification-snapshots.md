# Plan 007: Isolate call state from notification anti-entropy

> **Executor instructions**: Work only in the primary checkout; never create a
> worktree. Execute TDD-first and capture a focused RED before production edits.
> Run all gates and stop on any STOP condition. Do not push or use devices.
>
> **Drift check (run first)**:
> `git diff --stat 70cb092..HEAD -- mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SnapshotCoordinator.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/SnapshotCoordinatorTest.kt mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`
> Plan 005 and Plan 006 should not alter these paths. Stop if the live snapshot
> scope differs semantically from the excerpts below.

## Status

- **Priority**: P0
- **Effort**: S
- **Risk**: MED
- **Depends on**: Plan 006
- **Category**: bug
- **Planned at**: commit `70cb092`, 2026-08-19

## Why this matters

The notification snapshot protocol enumerates listener notifications only, but
its digest and Room reconciliation currently include every active canonical row.
Because call mirrors reuse the canonical table, an active `call:*` row can make
snapshot emission reject its own enumeration or be cancelled as “missing” by a
notification-only snapshot. That can break notification repair and strand or
incorrectly dismiss live call state.

## Current state

- `SnapshotCoordinator.localDigest` uses all `activeOriginStates` and compares
  their count/digest with notification-listener snapshots.
- `SnapshotCoordinator.validateItem` accepts only `notif.post`/`notif.update`, so
  call rows cannot legitimately appear as snapshot items.
- `ReliableDeliveryDao.beginSnapshot` baselines every non-cancelled canonical for
  the origin.
- `ReliableDeliveryDao.commitSnapshot` cancels every missing baseline canonical.
- The frozen call contract uses canonical IDs exactly
  `call:<lower-case UUID>`; notification canonical IDs are built through
  `CanonIdBuilder` and are not in that namespace.

## Commands

| Purpose | Command | Expected |
|---|---|---|
| Focused JVM | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*SnapshotCoordinatorTest'` | RED first, then pass |
| Instrumentation compile | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |
| Full native | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug` | BUILD SUCCESSFUL |
| Diff | `git diff --check` | exit 0 |

## Scope

**In scope**:
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SnapshotCoordinator.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/SnapshotCoordinatorTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`
- `advisor-plans/README.md`

**Out of scope**:
- Extending the snapshot wire protocol with call payloads
- Call terminal/restart persistence (separate Plan 008)
- Room schema changes
- Direct LAN, UI, E2E, devices, radios, or release work

## Steps

### Step 1: Add mixed notification/call REDs

Add JVM tests proving:

1. a local digest containing one active notification plus one active call counts
   and hashes only the notification;
2. repair enumeration with the same mixed store produces one notification item
   rather than rejecting count/digest stability;
3. an origin containing only an active call has the empty-notification digest.

Add Android transaction tests proving:

4. snapshot begin baseline count excludes an active `call:*` row;
5. committing an empty notification snapshot does not cancel or mutate an active
   call row;
6. committing a notification snapshot can still cancel a missing notification
   while preserving the call row from the same origin.

Run the JVM suite and capture failure against current all-canonical behavior.
Instrumentation execution remains pending without devices, but its source must
compile under Plan 006.

### Step 2: Define one notification snapshot scope and apply it consistently

Add one small, pure predicate for the existing protocol scope, for example
`isNotificationSnapshotCanonical(canonId) = !canonId.startsWith("call:")`.
Use the same predicate in:

- coordinator local digest and repair state lookup;
- DAO begin baseline construction;
- DAO missing-baseline cancellation loop.

Do not silently accept call snapshot items. Preserve the existing closed-world
notification payload validation. Do not filter call rows from general canonical
queries used by materialization, receipts, health, or retention.

### Step 3: Verify and review

Run focused JVM twice (second with `--rerun-tasks`), instrumentation compilation,
and full native gates. Request independent review focused on consistent scope,
snapshot ownership/races, and preservation of notification cancellation.

## Done criteria

- [ ] Call rows never contribute to notification digest/count.
- [ ] Call rows never enter notification snapshot baselines.
- [ ] Notification snapshot commit never cancels/mutates call rows.
- [ ] Missing notification rows are still cancelled correctly.
- [ ] Call snapshot items remain rejected.
- [ ] JVM regressions pass twice; Android transaction tests compile.
- [ ] Full native/lint pass and independent review is clear.
- [ ] Plan 007 marked DONE; only scoped paths committed.

## STOP conditions

- The call canonical namespace is not exactly `call:<UUID>` in live production.
- Correctness requires a snapshot wire/schema change.
- Filtering would affect non-snapshot canonical operations.
- A Room schema migration becomes necessary.

## Maintenance notes

The notification snapshot protocol stays notification-only. Call state requires
its own terminal/restart recovery rather than being smuggled into notification
anti-entropy.
