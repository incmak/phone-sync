# Reliable Delivery Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android client durably capture, send, receive, order, materialize, acknowledge, reconcile, and accurately report notification state across network and process failures.

**Architecture:** A lifecycle-independent capture coordinator writes versioned encrypted events to Room. A relay transport negotiates protocol v2, retains normal rows until authenticated peer receipts, and uses durable relay acceptance only as transport custody. Inbound events commit desired state and an idempotency journal before an Android materializer applies stable notification side effects; receipts are emitted only after successful materialization.

**Tech Stack:** Kotlin, Android NotificationListenerService, Room, DataStore, kotlinx.coroutines, OkHttp WebSocket, libsodium, Expo Modules, Gradle, AndroidX test

## Global Constraints

- Follow `docs/superpowers/specs/2026-08-09-reliable-delivery-foundation-design.md` exactly.
- Consume the committed v2 contracts and fixture bytes from `docs/superpowers/plans/2026-08-09-reliable-delivery-protocol-relay.md` without renaming fields.
- Target Android 14+ product behavior while keeping compile-time compatibility with the Expo-generated minimum SDK.
- Normal outbox rows remain until a valid encrypted peer receipt.
- Receipt rows may be removed after durable `relay.accepted` to avoid receipt recursion.
- No queue path silently drops an accepted or non-compactable row.
- Notification ordering is per canonical notification; unrelated notifications may progress concurrently.
- A Room commit and Android notification side effect are separate crash-recoverable stages.
- A peer receipt is forbidden while desired state remains unmaterialized.
- Every state transition must be testable without a real WebSocket or Android notification manager.
- Every source change follows red-green-refactor and ends with native compilation.

---

## File Structure

### Build and CI

- Modify `.github/workflows/mobile.yml`: generate Android, compile Kotlin, block lint, run JVM tests, and assemble APK.
- Modify `Makefile`: add `mobile-verify` and later compose it into root verification.
- Modify `mobile/modules/twinotify-core/android/build.gradle`: add Room migration testing and remove non-blocking lint.

### Persistent state

- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`.
- Modify `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`.
- Rename responsibility in `OutboundEvent.kt`: retain the old table as a one-release legacy migration source.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LegacyOutboxMigrator.kt`.

### Protocol and crypto boundary

- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolV2.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/EnvelopeAuthenticator.kt`.
- Retire `service/EncryptedEnvelope.kt` after compatibility reads move to the protocol package.
- Replace DataStore replay decisions in `storage/ReplayGuard.kt` with the transactional inbound journal.

### Capture and lifecycle

- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCoordinator.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCommand.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotificationListenerBridge.kt`.
- Modify `listener/TwinotifyNotificationListener.kt`.
- Replace `listener/OutboundSink.kt` logging fallback with a durable application-scoped sink.
- Modify `listener/NotifPostBuilder.kt` to use canonical schema names and typed nulls.

### Receiver and materialization

- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationStateReducer.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/AndroidNotificationPort.kt`.
- Rewrite `service/InboundDispatcher.kt` as a v1 compatibility adapter over the v2 processor.
- Modify `service/MirrorPoster.kt` to build/post using persisted stable IDs.
- Modify `service/MirrorDismisser.kt` to apply exact mirror or source operations.
- Persist peer-cancel tombstones instead of relying only on `PendingPeerCancel.kt`.

### Transport, snapshots, and health

- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayFrameCodec.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SnapshotCoordinator.kt`.
- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt`.
- Rewrite `service/SyncService.kt` as lifecycle orchestration over repositories and transport.
- Expand `service/SyncServiceStatus.kt` into a native health snapshot.
- Modify `service/BootReceiver.kt` and `TwinotifyCoreModule.kt` to honor native enabled state and authenticated revocation.

---

### Task 1: Make Android Compilation a Required Gate

**Files:**

- Modify: `.github/workflows/mobile.yml`
- Modify: `Makefile`
- Modify: `mobile/modules/twinotify-core/android/build.gradle`

**Interfaces:**

- Produces: `make mobile-verify`
- Produces: generated `mobile/android/gradlew` during verification, not as a tracked source directory
- Produces: CI debug APK artifact

- [ ] **Step 1: Demonstrate that the current CI gate omits native compilation**

Run:

```bash
rg -n 'gradlew|assembleDebug|testDebugUnitTest|lintDebug|expo prebuild' .github/workflows/mobile.yml Makefile
```

Expected before the change: no native build command is found.

- [ ] **Step 2: Make Android lint blocking and add migration-test support**

Change the module Android block and dependency:

```groovy
android {
  namespace "co.twinotify.core"
  defaultConfig {
    versionCode 1
    versionName "0.8.0"
    testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'
  }
  lint {
    abortOnError true
    warningsAsErrors true
  }
}

dependencies {
  androidTestImplementation 'androidx.room:room-testing:2.7.1'
}
```

Keep existing dependencies; only replace the old `lintOptions` block.

- [ ] **Step 3: Add the exact local verification target**

Extend `Makefile`:

```make
.PHONY: mobile-verify
mobile-verify:
	cd mobile && npm ci
	cd mobile && npm run typecheck
	cd mobile && npx expo-doctor
	cd mobile && npx expo prebuild --platform android --clean --no-install
	cd mobile/android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
```

Do not add `mobile/android` to Git; it remains a deterministic generated build product.

- [ ] **Step 4: Add native CI with JDK 17 and APK upload**

Add a `native-android` job after typecheck:

```yaml
  native-android:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: mobile
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: mobile/package-lock.json
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle
      - run: npm ci
      - run: npx expo prebuild --platform android --clean --no-install
      - run: ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
        working-directory: mobile/android
      - uses: actions/upload-artifact@v4
        with:
          name: twinotify-debug-apk
          path: mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 5: Run the new gate and record all pre-existing native failures**

```bash
make mobile-verify
```

Expected at task completion: exit 0. Resolve build configuration and compatibility failures in this task. Do not change notification behavior merely to silence an existing behavioral test; assign that failure to its owning later task and complete Task 1 only when the baseline gate is green.

- [ ] **Step 6: Commit the native gate**

```bash
git add .github/workflows/mobile.yml Makefile mobile/modules/twinotify-core/android/build.gradle
git commit -m "ci(android): compile and test the native sync engine"
```

---

### Task 2: Add Durable Delivery Tables and Migrate the Legacy Queue

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/OutboundEvent.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LegacyOutboxMigrator.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt`

**Interfaces:**

- Produces: `ReliableDeliveryDao`
- Produces: Room database version 3 and `MIGRATION_2_3`
- Produces: `LegacyOutboxMigrator.migrate(originDevice: String)`
- Preserves: every existing v1 ciphertext and nonce until converted to an exact v1 envelope

- [ ] **Step 1: Write a failing 2-to-3 migration test**

Create an Android migration test that builds schema version 2, inserts one row into `outbound_queue`, runs `MIGRATION_2_3`, and asserts the old row remains plus all new tables exist. Core assertions:

```kotlin
@Test
fun migrate2To3_preservesLegacyCiphertextAndCreatesReliableTables() {
    helper.createDatabase(TEST_DB, 2).apply {
        execSQL("INSERT INTO outbound_queue(ciphertextB64,nonceB64,msgId,createdTs) VALUES('ct','nonce','11111111-1111-4111-8111-111111111111',1000)")
        close()
    }
    val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)
    db.query("SELECT ciphertextB64, nonceB64, msgId FROM outbound_queue").use {
        assertTrue(it.moveToFirst())
        assertEquals("ct", it.getString(0))
        assertEquals("nonce", it.getString(1))
        assertEquals("11111111-1111-4111-8111-111111111111", it.getString(2))
    }
    for (table in listOf("outbound_message", "inbound_message", "canonical_notification_state", "origin_sequence", "activity_event", "snapshot_stage")) {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { assertTrue(it.moveToFirst(), table) }
    }
}
```

- [ ] **Step 2: Run the migration test and observe the missing migration**

```bash
cd mobile
npx expo prebuild --platform android --clean --no-install
cd android && ./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ReliableDeliveryMigrationTest
```

Expected: compile failure because `MIGRATION_2_3` does not exist.

- [ ] **Step 3: Define exact entities with string-backed states**

Create entities with no enum converter dependency:

```kotlin
@Entity(tableName = "outbound_message", indices = [Index("state"), Index("nextAttemptAt"), Index("canonId")])
data class OutboundMessage(
    @PrimaryKey val msgId: String,
    val canonId: String?,
    val sequence: Long?,
    val eventType: String,
    val protocolVersion: Int,
    val envelopeJson: String,
    val envelopeSha256: String,
    val byteSize: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val relayAcceptedAt: Long?,
    val attempts: Int,
    val nextAttemptAt: Long,
    val state: String,
    val lastError: String?,
    val requiresPeerReceipt: Boolean,
)

@Entity(tableName = "inbound_message", indices = [Index("outcome"), Index("canonId")])
data class InboundMessage(
    @PrimaryKey val msgId: String,
    val originDevice: String,
    val envelopeSha256: String,
    val eventType: String,
    val canonId: String?,
    val sequence: Long?,
    val outcome: String,
    val committedAt: Long,
    val appliedAt: Long?,
    val receiptMsgId: String?,
    val relayAckState: String,
)

@Entity(tableName = "canonical_notification_state", indices = [Index(value = ["mirrorLocalTag", "mirrorLocalId"], unique = true)])
data class CanonicalNotificationState(
    @PrimaryKey val canonId: String,
    val originDevice: String,
    val latestSequence: Long,
    val state: String,
    val desiredPayloadJson: String?,
    val materializedSequence: Long,
    val sourceNotificationKey: String?,
    val mirrorLocalId: Int?,
    val mirrorLocalTag: String?,
    val peerCancelPending: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "origin_sequence")
data class OriginSequence(@PrimaryKey val canonId: String, val nextSequence: Long)

@Entity(tableName = "activity_event", indices = [Index("occurredAt")])
data class ActivityEvent(
    @PrimaryKey val eventId: String,
    val msgId: String?,
    val packageName: String?,
    val eventType: String,
    val status: String,
    val byteSize: Long,
    val occurredAt: Long,
    val detailCode: String?,
)

@Entity(tableName = "snapshot_stage", primaryKeys = ["snapshotId", "canonId"])
data class SnapshotStage(val snapshotId: String, val canonId: String, val sequence: Long, val payloadJson: String, val receivedAt: Long)
```

- [ ] **Step 4: Add DAO primitives and transactional boundaries**

`ReliableDeliveryDao` must expose:

```kotlin
@Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOutbound(row: OutboundMessage)
@Query("SELECT * FROM outbound_message WHERE state IN ('NEW','ACCEPTED') AND nextAttemptAt <= :now ORDER BY createdAt LIMIT :limit") suspend fun sendable(now: Long, limit: Int): List<OutboundMessage>
@Query("UPDATE outbound_message SET state='ACCEPTED', relayAcceptedAt=:acceptedAt, nextAttemptAt=:retryAt WHERE msgId=:msgId") suspend fun markRelayAccepted(msgId: String, acceptedAt: Long, retryAt: Long): Int
@Query("DELETE FROM outbound_message WHERE msgId=:msgId") suspend fun deleteOutbound(msgId: String): Int
@Query("SELECT * FROM inbound_message WHERE msgId=:msgId") suspend fun inbound(msgId: String): InboundMessage?
@Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertInbound(row: InboundMessage)
@Query("SELECT * FROM canonical_notification_state WHERE canonId=:canonId") suspend fun canonical(canonId: String): CanonicalNotificationState?
@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCanonical(row: CanonicalNotificationState)
@Query("SELECT * FROM canonical_notification_state WHERE latestSequence > materializedSequence ORDER BY updatedAt") suspend fun pendingMaterialization(): List<CanonicalNotificationState>
```

Add transaction methods for sequence reservation, inbound desired-state commit, materialization completion, receipt transition, safe compaction, snapshot commit, and terminal activity movement. Each transaction returns a sealed result rather than encoding control flow in row counts.

- [ ] **Step 5: Implement `MIGRATION_2_3` and retain the v1 table**

Keep `outbound_queue` registered as `LegacyOutboundEvent` in database version 3. Add all new entities and `ReliableDeliveryDao` to `NotificationDbImpl`. Implement the migration with these exact columns and then create the indexes declared by the entities:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS outbound_message (
            msgId TEXT NOT NULL PRIMARY KEY, canonId TEXT, sequence INTEGER, eventType TEXT NOT NULL,
            protocolVersion INTEGER NOT NULL, envelopeJson TEXT NOT NULL, envelopeSha256 TEXT NOT NULL,
            byteSize INTEGER NOT NULL, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL,
            relayAcceptedAt INTEGER, attempts INTEGER NOT NULL, nextAttemptAt INTEGER NOT NULL,
            state TEXT NOT NULL, lastError TEXT, requiresPeerReceipt INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_message_state ON outbound_message(state)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_message_nextAttemptAt ON outbound_message(nextAttemptAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_message_canonId ON outbound_message(canonId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS inbound_message (
            msgId TEXT NOT NULL PRIMARY KEY, originDevice TEXT NOT NULL, envelopeSha256 TEXT NOT NULL,
            eventType TEXT NOT NULL, canonId TEXT, sequence INTEGER, outcome TEXT NOT NULL,
            committedAt INTEGER NOT NULL, appliedAt INTEGER, receiptMsgId TEXT, relayAckState TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbound_message_outcome ON inbound_message(outcome)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbound_message_canonId ON inbound_message(canonId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS canonical_notification_state (
            canonId TEXT NOT NULL PRIMARY KEY, originDevice TEXT NOT NULL, latestSequence INTEGER NOT NULL,
            state TEXT NOT NULL, desiredPayloadJson TEXT, materializedSequence INTEGER NOT NULL,
            sourceNotificationKey TEXT, mirrorLocalId INTEGER, mirrorLocalTag TEXT,
            peerCancelPending INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_canonical_notification_state_mirrorLocalTag_mirrorLocalId ON canonical_notification_state(mirrorLocalTag, mirrorLocalId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS origin_sequence (canonId TEXT NOT NULL PRIMARY KEY, nextSequence INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS activity_event (
            eventId TEXT NOT NULL PRIMARY KEY, msgId TEXT, packageName TEXT, eventType TEXT NOT NULL,
            status TEXT NOT NULL, byteSize INTEGER NOT NULL, occurredAt INTEGER NOT NULL, detailCode TEXT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_event_occurredAt ON activity_event(occurredAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS snapshot_stage (
            snapshotId TEXT NOT NULL, canonId TEXT NOT NULL, sequence INTEGER NOT NULL,
            payloadJson TEXT NOT NULL, receivedAt INTEGER NOT NULL,
            PRIMARY KEY(snapshotId, canonId))""")
    }
}
```

- [ ] **Step 6: Write the failing legacy conversion test**

Use a fake DAO and assert `LegacyOutboxMigrator.migrate("dev-a")` creates this exact envelope without changing ciphertext or nonce:

```json
{"v":1,"type":"enc","msg_id":"11111111-1111-4111-8111-111111111111","origin_device":"dev-a","ts":1000,"nonce":"nonce","ciphertext":"ct"}
```

It must calculate SHA-256 over the UTF-8 envelope, insert a `NEW` reliable row, then delete the legacy row in one Room transaction. Re-running is idempotent by `msgId`.

- [ ] **Step 7: Run migration/JVM tests and commit**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ReliableDeliveryMigrationTest
git add mobile/modules/twinotify-core/android/src
git commit -m "feat(android): add durable reliable delivery state"
```

---

### Task 3: Implement the Authenticated v2 Codec and Atomic Replay Boundary

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolV2.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/EnvelopeAuthenticator.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolV2Test.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/EnvelopeAuthenticatorTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReplayGuard.kt`

**Interfaces:**

- Produces: `ProtocolJson.encodeInner(event: InnerEventV2): String`
- Produces: `ProtocolJson.decodeInner(raw: String): InnerEventV2`
- Produces: `ProtocolJson.encodeEnvelope(envelope: EncryptedEnvelope): String`
- Produces: `EnvelopeAuthenticator.open(rawEnvelope: String): AuthenticatedEnvelope`
- Consumes: `Encrypter.decrypt` through an injected `PayloadDecryptor`

- [ ] **Step 1: Write failing null, round-trip, and mismatch tests**

```kotlin
@Test
fun optionalJsonNull_decodesAsNullNotLiteralNull() {
    val event = ProtocolJson.decodeInner(validPostJson.replace("\"text\":\"hello\"", "\"text\":null"))
    assertNull(event.payloadObject().optNullableString("text"))
}

@Test
fun outerAndAuthenticatedInnerMessageIdsMustMatch() {
    val decryptor = PayloadDecryptor { validInnerJson.replace(INNER_ID, DIFFERENT_ID).encodeToByteArray() }
    val auth = EnvelopeAuthenticator(decryptor, peerDeviceId = "dev-a")
    assertFailsWith<EnvelopeMismatchException> { auth.open(validOuterJson) }
}

@Test
fun malformedBase64IsRejectedBeforeDecryptorInvocation() {
    var calls = 0
    val decryptor = PayloadDecryptor { calls++; byteArrayOf() }
    assertFailsWith<ProtocolException> { EnvelopeAuthenticator(decryptor, "dev-a").open(validOuterJson.replace(VALID_NONCE, "%%%")) }
    assertEquals(0, calls)
}
```

- [ ] **Step 2: Run and observe missing protocol classes**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests 'co.twinotify.core.protocol.*'
```

Expected: compile failure for missing protocol types.

- [ ] **Step 3: Define immutable protocol types**

```kotlin
data class InnerEventV2(
    val msgId: String,
    val originDevice: String,
    val type: String,
    val canonId: String?,
    val sequence: Long?,
    val createdAt: Long,
    val expiresAt: Long,
    val payloadJson: String,
) {
    fun payloadObject(): JSONObject = JSONObject(payloadJson)
}

data class EncryptedEnvelope(
    val version: Int,
    val msgId: String,
    val originDevice: String,
    val createdAt: Long,
    val nonceB64: String,
    val ciphertextB64: String,
)

data class AuthenticatedEnvelope(
    val outer: EncryptedEnvelope,
    val inner: InnerEventV2,
    val envelopeSha256: String,
)

fun interface PayloadDecryptor { fun decrypt(envelope: EncryptedEnvelope): ByteArray }
```

Validate UUIDs with `UUID.fromString`, exact version/type, origin equality to the paired peer, inner/outer ID equality, inner/outer origin equality, inner/outer timestamp equality, expiry ordering, 24-byte decoded nonce, nonempty ciphertext, and a maximum 1 MiB raw UTF-8 envelope.

- [ ] **Step 4: Centralize typed JSON null handling**

Use:

```kotlin
fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

fun JSONObject.putNullable(key: String, value: String?) {
    put(key, value ?: JSONObject.NULL)
}
```

Do not use `optString(key).takeIf { it.isNotEmpty() }` for nullable fields.

- [ ] **Step 5: Move replay decisions into the inbound Room transaction**

Retain `ReplayGuard` only as a deprecated v1 compatibility adapter. V2 processing first decrypts and validates, then calls the DAO transaction that inserts `InboundMessage`. A duplicate primary key with identical digest returns prior state; a different digest returns `ID_CONFLICT`. No DataStore read-then-edit path participates in v2.

- [ ] **Step 6: Validate committed protocol fixtures**

Configure the module test source set to read the root committed fixtures directly:

```groovy
android {
  sourceSets {
    test.resources.srcDir("${rootProject.projectDir}/../../proto/fixtures")
  }
}
```

Tests load `manifest.json` from the test classloader and resolve its relative fixture paths. They must encode the Kotlin model and compare parsed JSON structures and ciphertext bytes, not incidental object-property order.

- [ ] **Step 7: Run protocol and all JVM tests; commit**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest
git add mobile/modules/twinotify-core/android/src mobile/modules/twinotify-core/android/build.gradle Makefile
git commit -m "feat(android): authenticate reliable delivery metadata"
```

---

### Task 4: Replace the Ephemeral Listener Sink with Ordered Durable Capture

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCommand.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/CaptureCoordinator.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotificationListenerBridge.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/OutboundSink.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotifPostBuilder.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/CaptureCoordinatorTest.kt`

**Interfaces:**

- Produces: `CaptureCoordinator.get(context).submit(command: CaptureCommand)`
- Produces: `NotificationListenerBridge.attach/detach/cancelSource/activeSources`
- Consumes: `ReliableDeliveryDao`, protocol codec, crypto keys, filter store, and device identity

- [ ] **Step 1: Write failing per-canonical ordering tests**

Use a fake persister that blocks preparation of notification A while allowing B to finish. Assert A's post/update/cancel sequences commit as 1/2/3, B commits independently, and no command falls back to logging when no SyncService exists.

```kotlin
@Test
fun commandsForSameCanonStayOrderedWhileDifferentCanonsRunConcurrently() = runTest {
    val persister = RecordingCapturePersister()
    val coordinator = CaptureCoordinator(testScope, persister, laneIdleMs = 100)
    coordinator.submit(PostCommand("a", sourceKey = "ka", snapshot = post("a")))
    coordinator.submit(PostCommand("b", sourceKey = "kb", snapshot = post("b")))
    coordinator.submit(RemoveCommand("a", sourceKey = "ka", reason = "app_cancel"))
    persister.awaitCount(3)
    assertEquals(listOf(1L, 2L), persister.sequencesFor("a"))
    assertEquals(listOf(1L), persister.sequencesFor("b"))
}
```

- [ ] **Step 2: Run and observe missing coordinator types**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests '*CaptureCoordinatorTest'
```

- [ ] **Step 3: Define commands as immutable captured inputs**

```kotlin
sealed interface CaptureCommand { val canonId: String; val sourceKey: String }
data class PostCommand(override val canonId: String, override val sourceKey: String, val snapshot: SourceNotificationSnapshot) : CaptureCommand
data class RemoveCommand(override val canonId: String, override val sourceKey: String, val reason: String, val removedAt: Long) : CaptureCommand
```

`SourceNotificationSnapshot` copies package, id, tag, post time, flags, category, visibility, text fields, and icon handles needed by `NotifPostBuilder`. It does not retain a mutable `StatusBarNotification` beyond the callback.

- [ ] **Step 4: Implement one actor lane per canonical notification**

Use a `ConcurrentHashMap<String, SendChannel<CaptureCommand>>`. `submit` uses `getOrPut` to create a channel consumed by one coroutine. Each lane reserves a sequence and durably persists commands in receive order, then removes itself after an injected idle timeout only if still mapped to the same channel. A failed lane logs metadata and keeps the coordinator alive through `SupervisorJob`.

No channel is bounded without a durable overflow path. Use `Channel.UNLIMITED` for the short pre-Room command window, export its current depth to health state, and run active-notification reconciliation on listener reconnect to repair a process death during that window.

- [ ] **Step 5: Make the listener attach to a durable application singleton**

In `onCreate`, call `CaptureCoordinator.get(applicationContext)` and `NotificationListenerBridge.attach(this)`. In `onDestroy`, detach only if the stored instance is this listener. Remove `installedSink`, `currentSink`, `LoggingOutboundSink`, and SyncService sink installation.

Both posted and removed callbacks compute the canonical ID synchronously and submit an immutable command. Filter self/group/secret/Android Auto/denylist before icon compression. Save the exact source `sbn.key` with every origin command.

- [ ] **Step 6: Detect post versus update from durable origin state**

Inside the lane transaction, the first active event for a canonical ID is `notif.post`; a later active callback is `notif.update`. Removal is `notif.cancel`. Persist source key and sequence with the origin canonical state. Do not rely on the old default `isUpdate=false` parameter.

- [ ] **Step 7: Run listener/coordinator tests and native compilation; commit**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
git add mobile/modules/twinotify-core/android/src
git commit -m "fix(android): durably order notification capture"
```

---

### Task 5: Add an Idempotent Desired-State Reducer and Platform Materializer

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationStateReducer.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/AndroidNotificationPort.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorDismisser.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/NotificationStateReducerTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/NotificationMaterializerTest.kt`

**Interfaces:**

- Produces: `NotificationStateReducer.reduce(current, event): Reduction`
- Produces: `NotificationMaterializer.materializePending(): MaterializationSummary`
- Produces: `AndroidNotificationPort.postMirror`, `cancelMirror`, and `cancelSource`
- Consumes: authenticated event and transactional DAO from prior tasks

- [ ] **Step 1: Write failing reducer tests for update, cancel, and stale resurrection**

```kotlin
@Test
fun cancelAtSequence3RejectsLateUpdateAtSequence2() {
    val cancelled = state(sequence = 3, status = "CANCELLED", materialized = 3)
    val result = NotificationStateReducer.reduce(cancelled, event(type = "notif.update", sequence = 2))
    assertIs<Reduction.Stale>(result)
    assertEquals(cancelled, result.state)
}

@Test
fun updateReusesPersistedMirrorIdentity() {
    val active = state(sequence = 1, status = "ACTIVE", localId = 42, localTag = "mirror-x")
    val result = assertIs<Reduction.Apply>(NotificationStateReducer.reduce(active, event(type = "notif.update", sequence = 2)))
    assertEquals(42, result.state.mirrorLocalId)
    assertEquals("mirror-x", result.state.mirrorLocalTag)
}
```

- [ ] **Step 2: Write failing crash-window materializer tests**

Use a fake `AndroidNotificationPort` that fails once. Assert desired state commits as `PENDING_PLATFORM`, no receipt becomes eligible, restart retries the same stable ID, second materialization succeeds, then exactly one receipt becomes eligible.

- [ ] **Step 3: Run and observe missing reducer/materializer classes**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests '*NotificationStateReducerTest' --tests '*NotificationMaterializerTest'
```

- [ ] **Step 4: Implement a pure reducer**

```kotlin
sealed interface Reduction {
    val state: CanonicalNotificationState
    data class Apply(override val state: CanonicalNotificationState) : Reduction
    data class Stale(override val state: CanonicalNotificationState) : Reduction
}
```

For a new mirror, allocate `mirrorLocalId` once through an injected `LocalIdAllocator` and persist a deterministic tag `mirror-` plus the first 24 lowercase hex characters of SHA-256(canon ID). A unique database index detects the extremely unlikely local ID collision; retry allocation before committing.

Post/update set desired `ACTIVE` and payload; cancel sets `CANCELLED` and clears desired payload. Any `sequence <= latestSequence` is stale. Reduction does not invoke Android APIs.

- [ ] **Step 5: Commit desired state and inbound idempotency together**

The DAO transaction receives `AuthenticatedEnvelope`, checks the existing inbound digest, checks sequence, inserts `InboundMessage(outcome='PENDING_PLATFORM')`, and writes desired canonical state. It returns:

```kotlin
sealed interface InboundCommitResult {
    data class Pending(val canonId: String) : InboundCommitResult
    data class DuplicatePending(val canonId: String) : InboundCommitResult
    data class Terminal(val outcome: String, val receiptMsgId: String?) : InboundCommitResult
    data object IdConflict : InboundCommitResult
}
```

- [ ] **Step 6: Implement the Android port with stable operations**

```kotlin
interface AndroidNotificationPort {
    fun postMirror(state: CanonicalNotificationState): Boolean
    fun cancelMirror(localTag: String, localId: Int): Boolean
    fun cancelSource(notificationKey: String): Boolean
}
```

`postMirror` builds from `desiredPayloadJson` and calls `NotificationManagerCompat.notify(stableTag, stableId, notification)`. `cancelMirror` adds the persisted peer-cancel flag before cancellation. `cancelSource` calls `NotificationListenerBridge.cancelSource(exactKey)` and returns false while the listener is unbound.

Materialization chooses the exact target from canonical ownership: a canonical ID whose origin component equals the local device ID is a source notification and must use its stored `sourceNotificationKey`; a peer-origin canonical ID is a Twinotify mirror and must use its stored local tag/ID. Posting is valid only for peer-origin state. Before posting, the port verifies POST_NOTIFICATIONS and `NotificationManagerCompat.areNotificationsEnabled()`; missing permission returns false and leaves the row pending instead of falsely acknowledging a no-op.

- [ ] **Step 7: Materialize then authorize receipts**

For each row where `latestSequence > materializedSequence`, invoke the exact platform operation. On success, one DAO transaction updates `materializedSequence`, clears `peerCancelPending` only after the listener echo is consumed, marks inbound `APPLIED`, and inserts one receipt row with a newly generated UUID stored in `InboundMessage.receiptMsgId`. Duplicate delivery reuses that stored receipt ID instead of generating another. On failure, leave `PENDING_PLATFORM` and schedule retry; do not emit a receipt or relay ACK.

- [ ] **Step 8: Replace old mirror mapping behavior**

Remove random ID allocation and `OnConflictStrategy.ABORT` post-after-notify ordering. `MirrorPoster` becomes a builder used only by the port. The canonical state row is the authoritative mapping. Keep old mapping tables readable during version 3 migration, but new v2 posts do not write them.

- [ ] **Step 9: Run reducer/materializer tests and commit**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
git add mobile/modules/twinotify-core/android/src
git commit -m "fix(android): materialize notification state idempotently"
```

---

### Task 6: Implement Relay v2 Transport, Receipts, and Correct Retry

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayFrameCodec.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayUrlPolicy.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`
- Rewrite: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Replace: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/QueuingOutboundSink.kt`
- Replace: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboundQueue.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayFrameCodecTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/OutboxRepositoryTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt`

**Interfaces:**

- Produces: `RelayTransport.run(url: HttpUrl): Flow<TransportEvent>`
- Produces: `RelayUrlPolicy.parse(input: String, debug: Boolean): RelayEndpoints`
- Produces: `OutboxRepository.onRelayAccepted`, `onPeerReceipt`, `onRelayRejected`, and `onRelayExpired`
- Consumes: exact Task 1 relay frames and the materializer from Task 5

- [ ] **Step 1: Write failing outbox custody tests**

```kotlin
@Test
fun normalMessageSurvivesRelayAcceptedUntilPeerReceipt() = runTest {
    val repo = OutboxRepository(fakeDao, clock)
    fakeDao.insertOutbound(normalMessage("m1"))
    repo.onRelayAccepted("m1", acceptedAt = 1000)
    assertNotNull(fakeDao.outbound("m1"))
    repo.onPeerReceipt(receipt(ackedMsgId = "m1", status = "applied", digest = normalDigest))
    assertNull(fakeDao.outbound("m1"))
}

@Test
fun receiptMessageIsDeletedAfterRelayAccepted() = runTest {
    fakeDao.insertOutbound(receiptMessage("r1"))
    OutboxRepository(fakeDao, clock).onRelayAccepted("r1", 1000)
    assertNull(fakeDao.outbound("r1"))
}

@Test
fun releaseRelayPolicyRejectsPlaintextSchemes() {
    assertFailsWith<IllegalArgumentException> { RelayUrlPolicy.parse("http://relay.example", debug = false) }
    assertFailsWith<IllegalArgumentException> { RelayUrlPolicy.parse("ws://relay.example/ws", debug = false) }
    assertEquals("wss", RelayUrlPolicy.parse("https://relay.example", debug = false).webSocket.scheme)
}
```

- [ ] **Step 2: Write failing ACK-order test**

Use a fake socket and inbound processor. Deliver an original event and assert no `relay.ack` is sent until the generated peer receipt receives `relay.accepted`; then assert exactly one ACK with the original ID/digest.

- [ ] **Step 3: Run and observe missing transport/repository classes**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests '*OutboxRepositoryTest' --tests '*RelayTransportTest'
```

- [ ] **Step 4: Implement strict relay frame codec**

Model every frame as a sealed interface. Reject unknown fields, wrong types, invalid UUID/digest, missing envelope, unsupported protocol, and frames larger than 1 MiB. `relay.hello` writes `[2,1]` and the actual application version. `relay.capabilities` controls v2/legacy mode; it never changes a negotiated floor 2 back to 1.

`RelayUrlPolicy` accepts `https`/`wss` in release and rejects cleartext. Debug builds additionally accept `http`/`ws` for loopback and local development. It returns separate HTTP pairing/revocation and WebSocket endpoints without string replacement. PairProtocol, SyncService, and unpair all consume this one policy.

- [ ] **Step 5: Implement outbox transitions**

`sendable` selects `NEW` plus `ACCEPTED` rows whose receipt retry deadline elapsed. Sending does not mutate state. `relay.accepted` moves a normal row to `ACCEPTED` and sets a retry deadline with bounded exponential backoff; it deletes a receipt row. A valid peer receipt compares both message ID and digest, moves the original to metadata-only activity, and deletes ciphertext. `relay.expired`, terminal rejection, and quarantine do the same with their terminal status. `mailbox_full` retains the row and schedules backoff.

- [ ] **Step 6: Implement receipt-before-mailbox-ACK ordering**

Inbound apply writes a receipt outbox row and links its ID from `InboundMessage`. When that receipt receives `relay.accepted`, one transaction marks the inbound row `relayAckState='READY'`. Transport sends `relay.ack` for READY rows. Socket write success changes it to `SENT`; redelivery or reconnect may send it again. Receiving a receipt event applies only the outbox deletion side effect, then immediately makes its own relay mailbox ACK eligible without generating a peer receipt.

- [ ] **Step 7: Implement connection lifecycle and backoff reset**

`RelayTransport` owns one socket at a time. It sends hello on open, waits for capabilities, drains sender statuses and mailbox deliveries, then sends outbox batches of 32. Use decorrelated jitter between one and 60 seconds. Reset the attempt counter after 30 continuous authenticated seconds. Network callbacks wake the loop but never create a second concurrent socket.

Do not launch an independent coroutine for each inbound frame. Feed them to a bounded raw-frame channel whose consumer validates and dispatches in control order; per-canonical concurrency occurs only inside the inbound processor.

- [ ] **Step 8: Keep legacy mode explicit**

When peer floor is 1, wrap a v1 envelope in `relay.put` and remove its legacy row only after `relay.legacy_forwarded`. If the old peer is offline, the relay returns `peer_legacy` and the sender retains the row. Set health `LEGACY_ONLINE_ONLY`; never display `reliable`. Once floor is 2, reject any raw v1 inbound frame and send only v2 envelopes through `relay.put`.

- [ ] **Step 9: Run transport tests, full native gate, and commit**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
git add mobile/modules/twinotify-core/android/src
git commit -m "feat(android): retain events until authenticated receipt"
```

---

### Task 7: Add Encrypted Snapshot Convergence

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SnapshotCoordinator.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotificationListenerBridge.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/SnapshotCoordinatorTest.kt`

**Interfaces:**

- Produces: `SnapshotCoordinator.localDigest(originDevice: String): StateDigest`
- Produces: `SnapshotCoordinator.onDigest`, `onBegin`, `onItem`, `onEnd`
- Consumes: `NotificationListenerService.activeNotifications` through the bridge

- [ ] **Step 1: Write failing staging and concurrency tests**

Tests must prove incomplete snapshots cause no visible mutation, wrong item count/digest is rejected, valid end atomically upserts present items and cancels absent mirrors, staged rows expire after ten minutes, and a live event with a newer sequence wins over an older staged item.

```kotlin
@Test
fun incompleteSnapshotNeverRemovesExistingMirror() = runTest {
    val coordinator = SnapshotCoordinator(fakeDao, fakeCapture, clock)
    coordinator.onBegin(begin(id = "s1", count = 1))
    coordinator.onItem(item(snapshotId = "s1", canonId = "new", sequence = 4))
    assertEquals(setOf("existing"), fakeDao.activeCanonIds())
}
```

- [ ] **Step 2: Run and observe the missing coordinator**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests '*SnapshotCoordinatorTest'
```

- [ ] **Step 3: Implement deterministic digest and bounded chunks**

Digest sorted UTF-8 lines of `canonId + NUL + sequence + NUL + state` using SHA-256. `state.digest` contains origin epoch, count, and digest. On mismatch, enumerate non-self, non-denied active source notifications through `activeNotifications`, capture current payloads, and emit begin/items/end. Each item is a normal v2 encrypted event and must remain below 1 MiB.

- [ ] **Step 4: Stage then atomically reconcile**

Stage by snapshot ID. `onEnd` verifies origin, count, and digest, then in one transaction applies only items whose sequence is newer, marks absent mirrors for desired cancellation, and deletes the staged rows. Materializer performs platform calls afterward and receipts follow normal rules. A failed/incomplete snapshot leaves current state unchanged.

- [ ] **Step 5: Trigger convergence at the correct times**

Exchange digest after mailbox drain, after any `relay.expired`, after listener reconnect, and after local process recovery finds a pending materialization. Rate-limit full snapshot generation to once per peer per five minutes unless the user explicitly retries health repair.

- [ ] **Step 6: Run snapshot/native tests and commit**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
git add mobile/modules/twinotify-core/android/src
git commit -m "feat(android): reconcile active notification state"
```

---

### Task 8: Make Enabled State, Restart, Health, and Unpair Durable

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/BootReceiver.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairOps.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt`

**Interfaces:**

- Produces: `ServiceConfigStore.read/setEnabled/setRelayUrl`
- Produces: `SyncHealth` with transport, queue, listener, permission, protocol, and error state
- Consumes: authenticated `POST /pair/revoke`

- [ ] **Step 1: Write failing lifecycle decision tests**

Extract a pure `ServiceStartPolicy.decide(intentAction, persistedConfig, paired)` and test:

- null sticky restart plus enabled/paired/URL starts;
- null sticky restart while disabled stops;
- boot while disabled does not start;
- user stop persists disabled before service shutdown;
- user start persists URL and enabled independently of socket success;
- pairing success remains complete even if service startup fails.

- [ ] **Step 2: Run and observe missing config/policy types**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests '*ServiceLifecycleTest'
```

- [ ] **Step 3: Implement native service configuration**

Use one DataStore containing `enabled`, canonical `relay_url`, `always_connected`, and `last_user_change_at`. `SyncService.onStartCommand` reads it on every start, including null intents. `ACTION_STOP` writes `enabled=false` before closing transport. Boot starts only when enabled, paired, and URL are all present.

- [ ] **Step 4: Publish truthful health**

```kotlin
data class SyncHealth(
    val service: String,
    val transport: String,
    val protocolFloor: Int,
    val queuedCount: Int,
    val queuedBytes: Long,
    val listenerConnected: Boolean,
    val listenerPermission: Boolean,
    val postPermission: Boolean,
    val lastReceiptAt: Long?,
    val lastErrorCode: String?,
)
```

Map it to the Expo event and `getSyncStatus`. The foreground notification says connecting, connected, queued, degraded, or stopped based on the same native snapshot. It never says connected while the socket is offline.

- [ ] **Step 5: Make unpair relay-aware and failure-safe**

Persist `revocation_requested_at` before calling authenticated `/pair/revoke`; do not wipe local signing keys until terminal server outcome. Treat 204 as success. If the relay committed revocation but the HTTP response was lost, a retry with the still-local old key returns 401; treat that 401 as terminal success only while the persisted revocation marker is present. Then stop service, clear reliable state, rotate crypto, clear peer/config, and emit the JS event in a `NonCancellable` block.

- [ ] **Step 6: Schedule retention work**

On service start, listener connect, and once per six hours while active, sweep terminal activity by configured retention, snapshot stages older than ten minutes, legacy mappings older than seven days, and persisted peer-cancel tombstones past their bound. Sweeps are idempotent and run outside notification callbacks.

- [ ] **Step 7: Run lifecycle/full native gate and commit**

```bash
make mobile-verify
git add mobile/modules/twinotify-core/android/src
git commit -m "fix(android): make sync lifecycle and health durable"
```

---

### Task 9: Prove the Android Foundation Against Protocol Fixtures

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolFixtureTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ReliablePipelineTest.kt`
- Modify: `Makefile`
- Modify: `.github/workflows/mobile.yml`

**Interfaces:**

- Produces: root fixture sync consumed by both Go and Kotlin
- Produces: deterministic in-process reliable pipeline test

- [ ] **Step 1: Add a failing full-pipeline test with controlled failures**

Use fake crypto, relay, clock, DAO, and Android port but real codecs/reducer/repositories. Exercise:

1. capture post sequence 1;
2. relay accept without peer receipt;
3. duplicate delivery;
4. platform failure then restart;
5. successful materialization;
6. receipt accepted before relay ACK;
7. sender receipt removes original;
8. update sequence 2;
9. cancel sequence 3;
10. late update sequence 2 does not resurrect.

Assert one visible post identity, one final cancellation, no silent deletion, and zero active outbox rows only after receipts.

- [ ] **Step 2: Run and fix only integration seams**

```bash
cd mobile/android && ./gradlew --no-daemon testDebugUnitTest --tests '*ReliablePipelineTest'
```

Expected before seam completion: focused integration failure. Do not weaken unit invariants to make it pass.

- [ ] **Step 3: Run committed fixtures through Kotlin**

Each valid outer/control/inner fixture must parse into the expected Kotlin type. Each invalid fixture must fail with the stable category in `manifest.json`. Re-encoding compares parsed JSON structure, decoded nonce/ciphertext bytes, UUIDs, and digest, not object-key order.

- [ ] **Step 4: Compose the Android verification target**

Add fixture sync before Gradle and ensure CI path filters include `proto/**`, `Makefile`, and the mobile module. Upload unit reports and lint results even on failure.

- [ ] **Step 5: Run all Android gates**

```bash
make sync-proto
make mobile-verify
git diff --check
```

Expected: TypeScript, Expo Doctor, Kotlin compile, lint, JVM tests, and APK assembly all exit 0.

- [ ] **Step 6: Commit Android foundation verification**

```bash
git add mobile Makefile .github/workflows/mobile.yml
git commit -m "test(android): prove reliable notification state pipeline"
```

---

## Plan Completion Audit

Before starting the two-device verification plan, prove each item from current artifacts:

- [ ] CI compiles the custom Kotlin module, blocks lint, runs JVM tests, and assembles an APK.
- [ ] A v1 Room database migrates without discarding ciphertext.
- [ ] No normal outbox row is deleted on `WebSocket.send` or `relay.accepted`.
- [ ] A normal row is removed only by a valid encrypted receipt matching ID and digest.
- [ ] A receipt row is removed only after durable relay acceptance.
- [ ] Authenticated inner/outer mismatch cannot consume replay state or execute a side effect.
- [ ] Concurrent duplicate IDs are atomic and digest conflicts are quarantined.
- [ ] Post/update/cancel ordering is correct per canonical notification.
- [ ] Unrelated canonical notifications can prepare and progress concurrently.
- [ ] Updates reuse one stable visible mirror.
- [ ] Cancel tombstones prevent late resurrection.
- [ ] Peer dismissal invokes the exact stored source notification key.
- [ ] Crash between Room desired-state commit and Android API call resumes safely before receipt.
- [ ] Listener capture remains durable when SyncService is absent.
- [ ] Null sticky restart, reboot, user stop, and listener rebind honor persisted enabled state.
- [ ] Reconnect backoff resets after a healthy session.
- [ ] Expiry triggers explicit activity plus snapshot convergence.
- [ ] Foreground and Expo status reflect native truth.
- [ ] Unpair revokes relay authority before local key destruction.
- [ ] JSON null never becomes the string `"null"`.
- [ ] `make mobile-verify` passes from a clean generated Android project.

The final reliability plan will add real relay plus two-emulator scenarios, physical-device evidence, stress, latency, Doze, battery, and the consolidated release audit.
