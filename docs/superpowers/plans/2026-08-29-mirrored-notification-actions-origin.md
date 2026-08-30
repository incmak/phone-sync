# Mirrored Notification Actions: Origin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task in the primary checkout.

**Goal:** Capture real source-notification action capabilities, advertise opaque generation-bound descriptors, and execute encrypted mirror invocations at most once on the origin phone.

**Architecture:** Live Android `Notification.Action` handles stay in a process-memory registry. Room v9 adds a durable execution journal and the shared mirror-side tables needed by the next plan. A dedicated inbound claim transaction commits the inbound journal and `CLAIMED` state before any `PendingIntent.send`; completion uses compare-and-set and persistent 60-second recovery so a crash can never cause a replay.

**Tech Stack:** Kotlin, Android Notification APIs, Room 2.7.1, AlarmManager, coroutines, JUnit 4, AndroidX instrumented Room migration tests.

## Global Constraints

- Execute only after the protocol plan passes and is committed.
- Work in the primary checkout. Do not create a worktree.
- Add failing JVM or instrumented tests before production code.
- Never persist a `PendingIntent`, `Notification.Action`, `RemoteInput` key, component, or intent extra.
- Never call `PendingIntent.send` from a Room transaction.
- Never re-execute an existing `CLAIMED` or `COMPLETED` invocation.
- A strict origin-clock check uses `now <= invokedAt + 120_000`; there is no execution grace.
- Fresh action IDs are installed only for the sequence that actually wins `commitCapturedState`.
- Snapshot messages continue to reuse committed `desiredPayloadJson` verbatim.
- Preserve the coordinator's sole-drainer ownership. Do not add a direct outbox flush path.
- Run relevant Go tests with `-race`; keep commits small and conventional.

---

## Task 1: Add the one-way Room 8 to 9 foundation

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/NotificationActionDaoTest.kt`
- Create after schema export: `mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/9.json`

### Step 1: Specify migration and transaction behavior

Extend `ReliableDeliveryMigrationTest` to migrate a populated v8 database to v9 and assert all old rows survive. Add assertions for these exact tables and indexes:

```text
action_invocation(invocationId PK, canonId, actionId, notificationSequence,
  replyText nullable, state, createdAt, expiresAt, updatedAt)
  indexes: canonId, state, expiresAt

action_execution(invocationId PK, canonId, actionId, state,
  resultStatus nullable, claimedAt, completedAt nullable)
  indexes: state, claimedAt, completedAt

notification_detail_cache(detailId PK, canonId UNIQUE, payloadJson,
  originDevice, receivedAt, updatedAt, cancelledAt nullable)
  indexes: cancelledAt, unique canonId
```

Add DAO tests for insert/read, uniqueness, terminal transition clearing `replyText`, and compare-and-set completion. Run the connected migration test. Expected: FAIL because version 9 and its entities do not exist.

### Step 2: Add entities, DAO primitives, and migration

Define entities with String states to keep on-disk values explicit. Add `MIGRATION_8_9`, register it, move `@Database(version = 8)` to 9, and include all three entities. Add only narrow DAO methods required by the spec, including:

```kotlin
@Query("UPDATE action_execution SET state='COMPLETED', resultStatus=:status, completedAt=:now " +
       "WHERE invocationId=:id AND state='CLAIMED'")
suspend fun completeClaim(id: String, status: String, now: Long): Int

@Query("UPDATE action_invocation SET state=:state, replyText=NULL, updatedAt=:now " +
       "WHERE invocationId=:id AND state='PENDING'")
suspend fun terminalizeInvocation(id: String, state: String, now: Long): Int
```

Do not add destructive migration or a 9 to 8 downgrade.

### Step 3: Verify and export the schema

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ReliableDeliveryMigrationTest,co.twinotify.core.storage.NotificationActionDaoTest
./gradlew :twinotify-core:assembleDebug
```

Expected: PASS and schema `9.json` generated. Inspect the JSON for all indexes and nullability before committing.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/schemas
git commit -m "feat(mobile/storage): add notification action journals"
```

---

## Task 2: Capture immutable action handles and rotate IDs with committed sequences

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotifPostBuilder.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCommand.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCoordinator.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionRegistry.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionDescriptorFactory.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/CaptureCoordinatorTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionRegistryTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionDescriptorFactoryTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/CaptureReconciliationTest.kt`

### Step 1: Specify capture and registry invariants

Add tests showing:

- capture snapshots copy at most three actions with non-null `PendingIntent`s in source order;
- action title and reply label are capped at 64 characters;
- only a free-form text `RemoteInput` makes `reply=true`;
- `FLAG_AUTO_CANCEL` becomes `is_auto_cancel`;
- registry replacement is atomic and rejects stale sequence/action pairs;
- cancel, unpair, and mirroring disable purge handles;
- a simulated stale CAS attempt never installs its IDs;
- the retry creates new IDs and installs only the finally committed generation;
- a snapshot item reuses the stored payload and does not call the ID factory.

Use small interfaces around framework objects so JVM tests can use fakes. Run the focused tests. Expected: FAIL because snapshots have no action handles and no registry exists.

### Step 2: Introduce the memory-only registry

Implement:

```kotlin
data class ActionGeneration(
  val sequence: Long,
  val sourceKey: String,
  val packageName: String,
  val handlesByActionId: Map<String, CapturedActionHandle>,
)

class ActionRegistry {
  private val generations = ConcurrentHashMap<String, ActionGeneration>()
  fun install(canonId: String, generation: ActionGeneration)
  fun lookup(canonId: String, sequence: Long, actionId: String): ActionLookup
  fun purge(canonId: String)
  fun clear()
}
```

`CapturedActionHandle` wraps the original `PendingIntent` plus the origin-owned `RemoteInput` objects. Publish immutable maps and replace the complete generation in one `ConcurrentHashMap` operation.

### Step 3: Integrate descriptor preparation with the capture CAS

Extend `SourceNotificationSnapshot` with immutable action metadata/handles and auto-cancel. `ActionDescriptorFactory.prepare(snapshot, sequence)` mints UUIDs and returns both wire descriptors and an uninstalled `ActionGeneration`.

In `DurableCapturePersister.persist`:

1. obtain the candidate sequence;
2. prepare descriptors and build/encrypt the payload;
3. call `commitCapturedState`;
4. only on `Committed`, install that exact generation;
5. on `Stale`, discard it and retry from step 1;
6. on committed cancel, purge the registry entry.

Do not install before the Room commit. Do not reconstruct descriptors when building state snapshots.

### Step 4: Verify capture behavior and commit

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.actions.*' --tests 'co.twinotify.core.listener.CaptureCoordinatorTest' --tests 'co.twinotify.core.listener.CaptureReconciliationTest'
```

Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener
git commit -m "feat(mobile/capture): advertise live notification actions"
```

---

## Task 3: Build encrypted invoke-result control messages without a new drainer

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionControlEncoder.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionControlEncoderTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`

### Step 1: Specify exact control envelopes

Add failing tests for invoke and result encoding. Assert:

- invoke expiry is creation plus 120,000 ms;
- result expiry is creation plus 600,000 ms;
- both use `requiresPeerReceipt=false`;
- both have null outbox `canonId` and `sequence`, preserving the control lane;
- payload keys match the protocol plan exactly;
- no reply content appears in logs, activity rows, or error strings.

### Step 2: Implement the shared encoder

Factor existing v2 encryption/envelope construction without changing nonce ownership. Provide:

```kotlin
suspend fun encodeInvoke(input: ActionInvokeInput): OutboundMessage
suspend fun encodeResult(input: ActionResultInput): OutboundMessage
```

The encoder returns a row; callers commit it in their own Room transactions. It never inserts or flushes directly.

### Step 3: Verify atomic insertion support

Add DAO transaction methods used by mirror invoke and origin completion. The transaction receives a fully encoded row and inserts it beside the relevant state mutation. It may signal `TransportCoordinator` only after commit through existing service wiring.

Run focused JVM and connected DAO tests. Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage
git commit -m "feat(mobile/actions): encode reliable action controls"
```

---

## Task 4: Implement the durable at-most-once claim protocol

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionInvocationProcessor.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/PendingIntentActionExecutor.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionInvocationProcessorTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ActionClaimTransactionTest.kt`

### Step 1: Specify every claim interleaving

Write fakes for clock, registry, active-notification lookup, executor, result encoder, and wake scheduler. Tests must cover:

- absent invocation: inbound journal and `CLAIMED` commit together before executor call;
- `COMPLETED`: stored result is re-enqueued and executor is never called;
- recent `CLAIMED`: duplicate is ignored and executor is never called;
- stale `CLAIMED`: compare-and-set to `outcome_unknown`, enqueue once, never execute;
- concurrent duplicates execute zero or one time;
- strict freshness boundary at exactly 120,000 ms passes and 120,001 ms expires;
- stale sequence/action, missing source key, vanished notification, reply on non-reply, and oversized reply map to safe statuses;
- `CanceledException`, attach failure, and background activity denial map to `failed`;
- a completion CAS that loses to recovery emits no contradictory result.

Run the focused test. Expected: FAIL because the processor does not exist.

### Step 2: Add Transaction A, B, and C DAO methods

Implement a dedicated claim transaction rather than reusing `commitDirectControl`'s callback inside Room:

```kotlin
sealed interface ActionClaimDecision {
  data object Execute : ActionClaimDecision
  data object InFlight : ActionClaimDecision
  data class Replay(val status: String) : ActionClaimDecision
}
```

Transaction A inserts the inbound journal and reads/inserts the execution row. Transaction B/C compare-and-set `CLAIMED` to `COMPLETED` and insert the encoded result row atomically. Duplicate `msg_id` and duplicate `invocation_id` remain distinct checks.

### Step 3: Execute outside Room

Validate after claim, then call `PendingIntentActionExecutor` outside every database transaction. For replies, build a fill-in intent and use the origin-owned `RemoteInput` keys with `RemoteInput.addResultsToIntent`. For activity `PendingIntent`s, opt in to the Android 14 background-start mode but treat denial as `failed`.

Never read an executable identity from the wire.

### Step 4: Prove concurrency and crash boundaries

Run JVM processor tests and the connected claim transaction test. Include a latch-based concurrency test, not sleeps. Expected: PASS and executor call count never exceeds one.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage
git commit -m "feat(mobile/actions): claim invocations before dispatch"
```

---

## Task 5: Recover orphaned claims persistently

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionClaimRecovery.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionClaimRecoveryReceiver.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionClaimRecoveryTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionClaimRecoveryWakeTest.kt`

### Step 1: Write failing recovery tests

Test that a fresh claim schedules `claimedAt + 60_000`, startup inside the grace period re-arms the remainder, startup after the deadline completes `outcome_unknown`, multiple due claims each get one result, and a racing real completion wins or loses by CAS without contradictory results.

### Step 2: Implement persistent wake and startup rehydration

Follow the existing materialization retry AlarmManager pattern. The receiver is non-exported. Maintain one in-process earliest wake and one persisted alarm. On wake:

1. query due `CLAIMED` rows;
2. encode each `outcome_unknown` result;
3. atomically CAS-complete and enqueue;
4. signal the coordinator after commit;
5. schedule the next outstanding claim.

Wire startup rehydration into the service lifecycle without starting a competing drainer.

### Step 3: Verify and commit

Run focused recovery tests and the module JVM suite. Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions
git commit -m "feat(mobile/actions): recover orphaned action claims"
```

---

## Task 6: Route origin action controls through the inbound dispatcher

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`

### Step 1: Add failing dispatcher and lifecycle tests

Verify `notif.action.invoke` enters the dedicated processor, does not enter canonical reduction/materialization, journals malformed authenticated input as rejected, and does not acknowledge before Transaction A commits. Verify unpair and mirroring-disable clear the registry and terminal cleanup sweeps execution rows after 24 hours.

### Step 2: Integrate the control branch

Add both action types to the direct-control classification but route invoke through `ActionInvocationProcessor` rather than the generic `commitDirectControl` callback. Keep receipt/custody behavior unchanged. Do not change `LanTransport`, `RelayTransport`, or `TransportCoordinator` ownership.

Wire registry clear into the existing unpair and disable operations. Add a bounded sweep to startup/maintenance.

### Step 3: Run origin regression gates

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.actions.*' --tests 'co.twinotify.core.listener.*' --tests 'co.twinotify.core.service.InboundDispatcherControlTest'
./gradlew :twinotify-core:lintDebug :twinotify-core:assembleDebug
```

Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core
git commit -m "feat(mobile/actions): dispatch origin action invocations"
```

---

## Plan Completion Gate

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest
./gradlew :twinotify-core:lintDebug :twinotify-core:assembleDebug
./gradlew :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ReliableDeliveryMigrationTest,co.twinotify.core.storage.NotificationActionDaoTest,co.twinotify.core.storage.ActionClaimTransactionTest
git diff --check
git status --short
```

Expected: all origin tests pass, schema 9 is committed, no executable Android capability is stored on disk, and the working tree is clean. Record unavailable connected-device coverage honestly and defer it to the verification plan rather than claiming success.

---

## Physical follow-up: bounded future clock skew

The MI 11X and POCO F1 physical run found an 11-second wall-clock difference. The origin treated every positive difference as an expired invoke, so a fresh direct reply failed immediately.

- Goal: accept a fresh invoke whose mirror clock is at most 30 seconds ahead while preserving the existing two-minute age limit.
- Non-goals: no protocol, schema, storage, transport, UI, pairing, or action-lifecycle changes.
- Acceptance: the focused processor test proves the 30-second boundary dispatches and the next millisecond fails closed; the existing action suite stays green; the same two-phone reply succeeds after installing the corrected build.
- Scope not to change: at-most-once claiming, generation binding, reply bounds, and terminal-result handling.
