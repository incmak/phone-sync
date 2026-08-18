# Direct LAN Transport and Route Coordination Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` task by task. Use `superpowers:test-driven-development`, `superpowers:systematic-debugging`, `superpowers:verification-before-completion`, and `superpowers:requesting-code-review` at their required boundaries.

**Goal:** Carry the existing encrypted notification, call-state, snapshot, cancellation, and receipt envelopes directly between paired phones on a shared Wi-Fi network, while preserving durable custody and automatically falling back to the relay.

**Architecture:** Add privacy-preserving paired NSD discovery, pinned TLS with signed application hello, a bounded direct frame protocol, and a single `TransportCoordinator`. Refactor relay-specific custody names into route-neutral Room state so LAN and relay share one sender and cannot race. Keep `InboundDispatcher` as the only authenticated inbound application path.

**Tech stack:** Kotlin, Room v5 migration, coroutines/Flow, Android NSD and `Network`, JSSE, existing libsodium protocol, JUnit4, Android instrumentation.

**Prerequisite:** Plan 1 is committed, independently approved, and its physical offline-pairing gate is green.

**Design source:** `docs/superpowers/specs/2026-08-18-offline-lan-sync-design.md`

## Execution rules

- Preserve the one-peer model and all protocol-v2 validation.
- Do not build a second outbox, receipt table, retry loop, or materializer.
- At most one component owns outbound selection/send transitions at a time.
- Direct frames have count and byte budgets. Never size a queue only by frame count.
- NSD data is untrusted until advertisement derivation, TLS pin, and signed hello all pass.
- A direct acceptance is custody, not delivery completion, except for receipt rows.
- Route switching is allowed to resend exact envelopes; it is never allowed to mutate message ID/digest.
- Each task records RED/GREEN evidence under `.superpowers/sdd/reports/direct-lan-task-<n>.md`.
- Commit explicit paths and do not push before independent review.

## Task 1: Make durable custody route-neutral with a Room v5 migration

**Create:**

- `mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/5.json`

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`
- all `OutboundMessage` construction/copy sites under `mobile/modules/twinotify-core/android/src/main`
- all affected fake stores/tests under `mobile/modules/twinotify-core/android/src/test`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`

### Step 1: Write migration and repository REDs

Add tests proving:

- v4 `relayAcceptedAt` values migrate byte-for-byte to v5 `custodyAcceptedAt` with `custodyRoute='relay'`;
- null old values remain null with null route;
- all other outbound columns, rows, indices, receipts, and activity survive;
- `onCustodyAccepted(msgId, route)` accepts only `lan` or `relay`;
- exact duplicate acceptance is idempotent;
- receipt rows delete on either route acceptance;
- ordinary rows remain until peer receipt;
- route acceptance followed by peer digest conflict does not delete;
- send attempt scheduling is route-neutral;
- rollback leaves the old row unchanged on transaction failure.

Run:

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:compileDebugAndroidTestKotlin \
  :twinotify-core:testDebugUnitTest --tests '*OutboxRepositoryTest'
```

Expected RED: missing v5 fields and generic custody API.

### Step 2: Implement `MIGRATION_4_5`

Recreate `outbound_message` transactionally with:

```text
custodyAcceptedAt INTEGER NULL
custodyRoute TEXT NULL
```

Copy `relayAcceptedAt` into `custodyAcceptedAt`; set route to `relay` when non-null. Preserve all constraints and recreate all three indices. Do not use destructive migration.

Rename store/repository methods from `markRelaySent`/`acceptRelay` to route-neutral names. Keep relay frame methods named relay-specific only at the protocol adapter boundary.

### Step 3: Prove migration on a real Android database

```bash
cd mobile/android
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ReliableDeliveryMigrationTest,co.twinotify.core.storage.ReliableDeliveryTransactionTest
```

Then run all JVM tests and inspect generated schema diff.

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/5.json \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LegacyOutboxMigrator.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/OutboxRepositoryTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ReliablePipelineTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/NotificationMaterializerTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/LegacyConversionTransactionTest.kt
git commit -m "refactor(android): make message custody route neutral"
```

## Task 2: Define and bound the direct frame protocol

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanFrame.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanFrameCodec.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanFrameCodecTest.kt`

### Step 1: Write parser REDs

Cover exact closed-world frames:

- `lan.hello`;
- `lan.hello_ack`;
- `lan.put`;
- `lan.accepted`;
- `lan.ping`;
- `lan.pong`;
- `lan.close`.

Reject unknown versions/types/fields, duplicate JSON keys, invalid UUID/digest/base64, zero-length frame, declared length above the cap, malformed UTF-8, truncated body, trailing bytes, and envelope bytes beyond the protocol limit.

Test a byte-budget queue with four maximum legal frames and prove the fifth suspends or rejects without allocation growth. Define exact constants in one place:

```kotlin
const val MAX_ENVELOPE_BYTES = 1_048_576
const val MAX_CONTROL_BYTES = 16_384
const val MAX_BUFFERED_FRAMES = 4
const val MAX_BUFFERED_BYTES = 4_260_000
```

The byte ceiling must include framing overhead and must be justified by tests, not copied blindly.

### Step 2: Implement codec and bounded buffer

Use four-byte big-endian lengths. Decode to typed immutable values. Copy byte arrays at boundaries. Keep parser errors as bounded stable codes.

### Step 3: Run focused/full tests and commit

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*LanFrameCodecTest'
./gradlew --no-daemon :twinotify-core:testDebugUnitTest
```

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan
git commit -m "feat(android): define bounded LAN frames"
```

## Task 3: Derive private advertisements and wrap paired NSD

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanAdvertisement.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanDiscovery.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/AndroidLanDiscovery.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanAdvertisementTest.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanDiscoveryTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/lan/AndroidLanDiscoveryTest.kt`

### Step 1: Write derivation/discovery REDs

Prove:

- advertisement HMAC domain includes protocol label, advertiser device ID, and signed UTC epoch day;
- two paired devices derive distinct IDs;
- an observer without secret cannot derive either ID from public IDs alone;
- expected-peer matching checks local day minus one, current day, and plus one;
- larger clock mismatch yields `lan_clock_skew`, not broad matching;
- TXT inventory contains only version, ad ID, and reviewed capability bits;
- service name contains no device ID/name;
- resolver returns addresses together with the originating `Network`;
- no request requires `INTERNET` or `VALIDATED` capability;
- network loss invalidates addresses and emits one loss event;
- registration/resolve/discovery callbacks are stopped exactly once;
- multicast lock acquisition/release is balanced through cancellation and failure.

### Step 2: Implement pure derivation then Android adapter

Use the sealed `LanPairStore` secret. Perform HMAC comparison in constant time. The adapter exposes flows of typed candidates and never authenticates them.

Use executor-based `NsdManager` APIs when available. Keep a compatibility implementation for API 34-36. Socket consumers must receive the resolved `Network`; they must not retain bare IP addresses beyond candidate lifetime.

### Step 3: Run tests

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*LanAdvertisementTest' --tests '*LanDiscoveryTest'
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.lan.AndroidLanDiscoveryTest
```

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanAdvertisement.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanDiscovery.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/AndroidLanDiscovery.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/lan/AndroidLanDiscoveryTest.kt
git commit -m "feat(android): discover paired phones privately"
```

## Task 4: Authenticate direct connections with pin plus signed hello

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanHandshake.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanConnectionFactory.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanHandshakeTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/lan/LanPinnedTlsTest.kt`

### Step 1: Write authentication REDs

Test:

- TLS pin is checked before a hello can be parsed;
- hello signs protocol version, both device IDs, both nonces, TLS session binding/exported digest, and connection role;
- wrong application signing key, wrong pin, stale nonce, replayed hello, changed protocol floor, reflected signature, or swapped roles fail;
- nonce cache is bounded and expires by monotonic time;
- lexicographically smaller device ID is normal initiator;
- simultaneous connections lead both peers to retain the same deterministic socket;
- authentication timeout and half-close clean every resource;
- no peer identity is accepted from NSD/TLS subject/display name alone.

### Step 2: Implement handshake and connection factory

Use Plan 1 `LanTlsContextFactory` and `LanIdentityStore`. Connect through `candidate.network.socketFactory` or explicit network binding. The server uses its Keystore certificate. Both sides still sign fresh application hello data with the existing Ed25519 identity.

Return an authenticated connection object only after TLS pin and both hello signatures pass. It owns one reader, one writer, and one close path.

### Step 3: Run real loopback and two-phone handshake

```bash
cd mobile/android
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.lan.LanPinnedTlsTest
```

Run an explicit two-phone handshake with packet capture/log evidence limited to bounded state codes and hashed IDs. Verify pin mutation on one test install causes hard failure.

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanHandshake.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanConnectionFactory.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanHandshakeTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/lan/LanPinnedTlsTest.kt
git commit -m "feat(android): authenticate direct LAN sockets"
```

## Task 5: Integrate direct durable custody through `InboundDispatcher`

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanTransport.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanTransportTest.kt`
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/lan/LanCustodyTransactionTest.kt`

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`
- focused dispatcher/repository tests

### Step 1: Write end-to-end transport REDs with real production seams

Test through `LanTransport`, `OutboxRepository`, the real frame codec, and a Room-backed dispatcher seam:

- sender selects one due row and sends its exact stored envelope/digest;
- receiver authenticates/parses then durably commits before emitting `lan.accepted`;
- exact durable duplicate emits the same acceptance without rematerializing;
- message ID with different digest emits no acceptance and closes with a stable conflict code;
- sender ordinary row becomes `ACCEPTED` with route `lan` but remains until authenticated peer receipt;
- sender receipt row deletes after direct acceptance;
- platform materialization failure still has durable custody and schedules its existing retry;
- crash after inbound commit but before acceptance leads to safe resend/duplicate acceptance;
- burst above byte/count budget backpressures the socket and loses no delivery;
- reader, writer, and dispatcher remain ordered and bounded;
- ping timeout/reconnect does not create a second sender.

### Step 2: Give `InboundDispatcher` a typed result

Return a sealed result containing only what transport needs:

```kotlin
sealed interface InboundDispatchResult {
    data class Accepted(val msgId: String, val envelopeSha256: String) : InboundDispatchResult
    data class Duplicate(val msgId: String, val envelopeSha256: String) : InboundDispatchResult
    data class Rejected(val code: String) : InboundDispatchResult
}
```

Relay callers may ignore the result only after existing relay ACK semantics remain proven. Never acknowledge before the Room transaction boundary.

### Step 3: Implement `LanTransport`

`LanTransport` owns no autonomous outbox loop. It exposes authenticated inbound events and bounded send/accept operations to the coordinator. One ordered coroutine processes inbound puts. One writer serializes frames. Both use byte-budgeted queues and suspend instead of dropping.

### Step 4: Run JVM and connected Room tests

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*LanTransportTest' --tests '*ReliablePipelineTest'
ANDROID_SERIAL=<serial> ./gradlew --no-daemon \
  :twinotify-core:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.lan.LanCustodyTransactionTest
```

### Step 5: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanTransport.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanTransportTest.kt \
  mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/lan/LanCustodyTransactionTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/OutboxRepositoryTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ReliablePipelineTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt
git commit -m "feat(android): retain direct events through peer receipt"
```

## Task 6: Add one-owner route coordination

**Create:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt`

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt`

### Step 1: Write route-state REDs

Use fake monotonic clock, fake LAN/relay sessions, and real `OutboxRepository`. Prove:

- LAN wins only after full authentication;
- relay carries traffic when LAN is absent/unhealthy;
- only one route may call `sendable`/`markSent` at a time;
- route switch completes or cancels one bounded send boundary before next owner starts;
- exact in-flight row may resend after ambiguous failure without mutation;
- LAN authentication failure does not disable a valid relay;
- relay authentication failure does not poison LAN;
- queue wake occurs immediately on capture, route availability, peer receipt, and retry deadline;
- backoff is bounded/jittered and resets only after sustained authenticated health;
- no connection storm on network callbacks;
- service cancellation joins route jobs;
- route health is truthful during connecting/fallback/offline states.

### Step 2: Refactor relay into a route session

Split relay socket/session mechanics from outbox ownership. `RelayTransport` must no longer autonomously drain when not granted the coordinator lease. Preserve legacy floor-1 forwarding, authenticated receipt handling, JWT refresh, bounded inbound flow, and all current tests.

Expose a narrow coordinator contract such as:

```kotlin
interface TransportRoute {
    val kind: RouteKind
    suspend fun open(): AuthenticatedRouteSession
}

interface AuthenticatedRouteSession {
    suspend fun send(row: OutboundMessage): SendOutcome
    val inbound: Flow<RouteInbound>
    suspend fun close()
}
```

### Step 3: Run deterministic stress tests

Run the focused suite with `--tests '*TransportCoordinatorTest' -Dkotlinx.coroutines.debug` and repeat at least 50 times. Add a deterministic burst/reconnect/JWT-expiry case that closes the earlier transport-review WATCH item.

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt
git commit -m "refactor(android): coordinate LAN and relay routes"
```

## Task 7: Own LAN lifecycle in `SyncService`

**Modify:**

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceStartPolicy.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/BootReceiver.kt`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairWorkflow.kt`
- corresponding JVM tests

### Step 1: Write lifecycle REDs

Prove:

- paired + enabled + Always Connected starts NSD/server/coordinator without a relay URL;
- relay-only pair still starts relay and does not advertise without LAN binding;
- LAN preference can be disabled durably;
- boot restart works for offline pair with null relay URL;
- Wi-Fi loss tears down resolved sockets but retains durable queue;
- Wi-Fi return restarts discovery once;
- service stop/unpair cancels and joins discovery, server, routes, inbound worker, materializer, retention, call capture, then wipes;
- peer unpair from LAN avoids self-join deadlock using the established workflow pattern;
- foreground text and health route are `lan`, `relay`, `connecting`, or `offline`, never generic `online`;
- notification/call snapshot reconciliation runs after authenticated LAN connection;
- disabled Always Connected reports best-effort mode and makes no screen-off promise.

### Step 2: Integrate coordinator and health

Replace `startRelay()` ownership with `startTransportCoordinator(config)`. The service constructs LAN and relay routes as available. `ServiceStartPolicy` permits a LAN-bound peer with no relay URL. Store `preferLan` and optional relay enrollment state in native config.

Use a single ordered inbound worker shared by both route types. Do not launch unbounded `scope.launch` per frame.

### Step 3: Run module lifecycle gates

```bash
cd mobile/android
./gradlew --no-daemon :twinotify-core:testDebugUnitTest \
  --tests '*ServiceLifecycleTest' --tests '*TransportCoordinatorTest' \
  --tests '*UnpairWorkflowTest'
./gradlew --no-daemon :twinotify-core:compileDebugAndroidTestKotlin \
  :twinotify-core:lintDebug :twinotify-core:assembleDebug
```

### Step 4: Commit

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceConfigStore.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/ServiceStartPolicy.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/BootReceiver.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/UnpairWorkflow.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt \
  mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/UnpairWorkflowTest.kt
git commit -m "feat(android): prefer direct LAN with relay fallback"
```

## Task 8: Prove direct notification and call-state convergence

**Modify:**

- `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt`
- `e2e/internal/control/control.go`
- `e2e/internal/scenario/executor.go`
- `e2e/internal/scenario/scenario.go`
- `e2e/internal/scenario/scenario_test.go`
- `Makefile`
- `docs/test-scenarios.md`

**Create:**

- `e2e/internal/scenario/lan_direct.go`
- `e2e/internal/scenario/lan_direct_test.go`
- `scripts/verify-lan-evidence.sh`

### Step 1: Extend sanitized route evidence

Expose authenticated route kind, session generation, queue count/bytes, last receipt time, and bounded error code. Never expose endpoint IP, service name, advertisement ID, pin, secret, or content.

### Step 2: Add physical scenarios

Automate with two explicit distinct serials:

- notification post/update/cancel both directions;
- synthetic call ringing/active/idle both directions;
- duplicate send and digest conflict rejection;
- process death after receiver custody but before sender acceptance;
- Wi-Fi disconnect queue then reconnect convergence;
- screen off with Always Connected;
- relay fallback after LAN loss;
- safe return to LAN with an in-flight row;
- four maximum-size frames plus burst workload without loss or memory runaway;
- unpair during active LAN traffic.

All predicates correlate new canonical hashes against a pre-scenario baseline. No success may be inferred from an unrelated existing notification.

### Step 3: Run full gates

```bash
make proto-test
make relay-verify
ANDROID_HOME="$ANDROID_HOME" make mobile-verify
cd e2e && go test ./... -race -count=1 && go vet ./...
./scripts/verify-generated-clean.sh
git diff --check
```

### Step 4: Run two-phone direct/fallback suite

```bash
make e2e-lan \
  E2E_DEVICE_A=<serial-a> \
  E2E_DEVICE_B=<serial-b> \
  E2E_RELAY_URL=<optional-test-relay>
```

Capture packet-level evidence that ordinary direct scenarios contact no relay/public endpoint. Separately prove fallback by making LAN unavailable while retaining relay reachability.

### Step 5: Review and commit

Request an independent review of the exact Plan 2 range. Review must inspect Room migration, acknowledgement boundary, route ownership, TLS/hello authentication, NSD privacy, socket byte budgets, unpair ordering, and physical artifacts. Fix every Critical/Important finding and rerun complete gates.

```bash
git add mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt \
  mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eStateProvider.kt \
  e2e/internal/control/control.go e2e/internal/scenario/executor.go \
  e2e/internal/scenario/scenario.go e2e/internal/scenario/scenario_test.go \
  e2e/internal/scenario/lan_direct.go e2e/internal/scenario/lan_direct_test.go \
  Makefile docs/test-scenarios.md scripts/verify-lan-evidence.sh
git commit -m "feat(android): sync directly over local Wi-Fi"
```

Do not push until review is CLEAR/APPROVE and all evidence refers to the final commit tree.

## Plan 2 completion evidence

Plan 2 is complete only when:

- an offline-paired physical pair discovers and mutually authenticates on shared Wi-Fi;
- notification and call state converge both directions with internet and relay unavailable;
- direct acceptance occurs only after durable custody;
- ordinary events survive until authenticated peer receipt;
- relay fallback and return to LAN preserve exact IDs/digests and lose no row;
- only one outbound owner is active at a time;
- socket/flow/dispatcher memory is bounded by bytes and count;
- Room v4 data migrates losslessly to v5;
- screen-off Always Connected behavior is demonstrated on both physical phones;
- full repository gates and independent review are clear.
