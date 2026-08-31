# Automatic LAN Promotion and Delivery Truth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make relay-paired Twinotify phones automatically establish direct-LAN trust, promote from relay to authenticated Wi-Fi when available, fall back immediately when it is not, and report custody and peer presence without false claims.

**Architecture:** Extend the backward-compatible relay capability exchange before sending new encrypted controls; derive LAN trust only on the phones from the existing paired X25519 identities; reuse Room v9 and its durable outbox for bootstrap, probes, and peer receipts; keep `TransportCoordinator` as the single drainer owner while it authenticates an ungranted LAN candidate beside relay; publish one classified, privacy-safe native status consumed by the existing home connection surface.

**Tech Stack:** Go 1.23, bbolt, JSON Schema 2020-12, Kotlin, libsodium/JNA, Room v9, Android Keystore/JSSE/NSD, coroutines/Flow, TypeScript, React Native/Expo Router, JUnit4, Jest, two physical Android phones over ADB.

## Global Constraints

- Work in the primary checkout only. Do not create or use a Git worktree.
- Follow failing-test-first TDD for every behavior change and record the expected failure before implementation.
- Preserve Room version 9. Stop and amend the approved design before adding an entity, column, or migration.
- Never route reliable work through the legacy v1 queue or DataStore replay guard.
- Never edit or commit `relay/internal/server/schemas/`; edit `proto/` and run `make sync-proto`.
- Preserve pair-scoped relay capability reads/writes and update them in the same Bolt transaction as existing protocol-floor state.
- Legacy mobile clients must receive the legacy `relay.capabilities` JSON shape without new keys.
- The relay may persist feature names but never a LAN pin, LAN secret, X25519 shared key, private-network address, SSID, or decrypted inner event.
- `LanPairStore` remains the only component that may attach a LAN-binding marker to `PeerStore`; a conflicting valid binding is never replaced silently.
- `TransportCoordinator` remains the only outbox-drainer lease authority. An authenticated probe candidate is not granted until the relay self-drainer has closed and joined.
- Bootstrap, probe, peer-receipt, snapshot, and unpair controls never inflate the main user-delivery count or Recent activity.
- Only an authenticated LAN session may mean “Reachable now.” Relay liveness requires a matching current-generation probe receipt and is presented only as “Checked in recently.”
- Keep the current home hierarchy, trace, switch, peer row, and button primitives. Add no card, badge row, nested panel, icon-only action, or raw diagnostics.
- Preserve unrelated user changes and make small conventional commits at the task boundaries below.

---

### Task 1: Negotiate transport features without breaking old clients

**Files:**
- Modify: `proto/relay-control.schema.json`
- Modify: `relay/internal/store/pair_store.go`
- Modify: `relay/internal/store/pair_store_test.go`
- Modify: `relay/internal/server/relay_frame.go`
- Modify: `relay/internal/server/client_hub.go`
- Modify: `relay/internal/server/websocket_memory_test.go`
- Modify: `relay/internal/server/ws.go`
- Modify: `relay/internal/server/ws_mailbox_test.go`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayFrameCodec.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayFrameCodecTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt`

**Interfaces:**

```go
type DeviceCapabilities struct {
    Protocols  []int    `json:"protocols"`
    AppVersion string   `json:"app_version"`
    Features   []string `json:"features,omitempty"`
    UpdatedAt  int64    `json:"updated_at"`
}
```

```kotlin
object RelayFeatures {
    const val LAN_BOOTSTRAP_V1 = "lan-bootstrap-v1"
    const val PEER_PROBE_V1 = "peer-probe-v1"
    val CURRENT = setOf(LAN_BOOTSTRAP_V1, PEER_PROBE_V1)
}

data class RelayFrame.Hello(
    val protocols: List<Int>,
    val appVersion: String,
    val features: Set<String> = emptySet(),
)

data class RelayFrame.Capabilities(
    val self: List<Int>,
    val peer: List<Int>,
    val floor: Int,
    val selfFeatures: Set<String> = emptySet(),
    val peerFeatures: Set<String> = emptySet(),
)

data class TransportEvent.Authenticated(
    val floor: Int,
    val peerFeatures: Set<String>,
)
```

- [x] Add RED Go storage tests proving feature lists are copied, persisted, pair-scoped, purged on revoke/rebind, and decoded as empty from legacy records. Run `cd relay && go test ./internal/store -run 'TestPairCapabilities|TestRevokeByDevice' -race -count=1` and confirm the new assertions fail because features are absent.
- [x] Add RED WebSocket tests proving a legacy hello receives exactly `v,type,self,peer,floor`, a feature hello receives `self_features` and `peer_features`, peer feature changes propagate live, stale/replaced sockets with the same protocol list but different feature lists cannot receive a mis-shaped update, duplicates/unknown/oversized features are rejected, and the protocol floor remains monotonic. Run `cd relay && go test ./internal/server -run 'TestWebSocket.*Capabilities|TestClientHub.*Capabilities|TestProtocolFixtures' -race -count=1` and confirm failure.
- [x] Add RED Kotlin codec/transport tests proving legacy capability frames decode with empty feature sets, feature-bearing frames round-trip strictly, old-shape hello remains decodable, and `TransportEvent.Authenticated` carries the peer feature snapshot. Run `cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.service.RelayFrameCodecTest' --tests 'co.twinotify.core.service.RelayTransportTest'` and confirm failure.
- [x] Extend `relay-control.schema.json`: hello accepts an optional unique two-item maximum `features` array whose items are the two approved names; capabilities accepts optional paired `self_features`/`peer_features` arrays. Keep all other frames closed-world.
- [x] Extend pair capability persistence and pair-scoped APIs to copy feature slices on input/output. Preserve decoding of old JSON records with a missing field.
- [x] Shape capability responses per recipient: include feature keys only when that recipient advertised a non-empty feature set. Include feature fields in capability equality and the hub's connection-generation fence so a live peer update reaches only the matching current socket.
- [x] Extend the Kotlin codec with the same bounded allowlist, advertise `RelayFeatures.CURRENT`, decode absent fields as empty, and carry peer features with authenticated transport events.
- [x] Run `make sync-proto`, the three focused RED-to-GREEN commands above, then `git diff --check`.
- [x] Commit with `feat(relay): negotiate transport features`.

### Task 2: Add strict encrypted bootstrap and probe event contracts

**Files:**
- Modify: `proto/inner-event-v2.schema.json`
- Modify: `proto/fixtures/manifest.json`
- Create: `proto/fixtures/v2-valid/lan-bootstrap-inner.json`
- Create: `proto/fixtures/v2-valid/peer-probe-inner.json`
- Create: `proto/fixtures/v2-invalid/lan-bootstrap-extra-field.json`
- Create: `proto/fixtures/v2-invalid/lan-bootstrap-bad-digest.json`
- Create: `proto/fixtures/v2-invalid/peer-probe-id-mismatch.json`
- Create: `proto/fixtures/v2-invalid/peer-probe-extra-field.json`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/protocol/ProtocolJson.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolFixtureTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/protocol/ProtocolValidationTest.kt`
- Modify: `relay/internal/server/fixture_test.go`

**Contracts:**

```json
{"protocol_version":1,"tls_spki_sha256":"<64 lower hex>","binding_context_sha256":"<64 lower hex>"}
```

```json
{"probe_id":"<same canonical UUID as msg_id>","sent_at":1788160800000,"request_direct":true}
```

- [x] Add valid/invalid fixtures and manifest entries first. Extend fixture tests to route `lan_bootstrap_inner` and `peer_probe_inner` through `ProtocolJson.decodeInner`. Run `cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.protocol.ProtocolFixtureTest'` and confirm the valid fixtures fail as unsupported.
- [x] Add direct RED validation tests for missing fields, extra fields, uppercase/non-hex digests, protocol versions other than 1, noncanonical probe UUIDs, `probe_id != msg_id`, negative `sent_at`, and non-Boolean `request_direct`. Run `cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.protocol.ProtocolValidationTest'` and confirm failure.
- [x] Add both names to the schema and Kotlin inner-type allowlists. Add closed-world schema branches and matching `validateLanBootstrapPayload` / `validatePeerProbePayload` functions; both types forbid `canon_id` and `sequence`.
- [x] Run `make sync-proto`, `cd relay && go test ./internal/server -run TestProtocolFixtures -race -count=1`, and both Kotlin protocol test classes.
- [x] Run `git diff --check` and verify `git status --short` does not list generated relay schemas.
- [x] Commit with `feat(proto): define LAN bootstrap controls`.

### Task 3: Derive and compare bootstrap LAN trust safely

**Files:**
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/lan/LanBootstrapCrypto.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/lan/LanBootstrapCryptoTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/LanPairStore.kt`
- Verify unchanged: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/LanPairStoreTest.kt`

**Interfaces:**

```kotlin
data class LanBootstrapIdentity(
    val deviceId: String,
    val encryptionPublicKey: ByteArray,
    val signingPublicKey: ByteArray,
)

class LanBootstrapMaterial(
    lanSecret: ByteArray,
    bindingContextSha256: ByteArray,
)

fun interface BoxPrecomputer {
    fun sharedKey(peerPublicKey: ByteArray, localSecretKey: ByteArray): ByteArray
}

object LanBootstrapCrypto {
    fun derive(
        local: LanBootstrapIdentity,
        peer: LanBootstrapIdentity,
        localEncryptionSecretKey: ByteArray,
    ): LanBootstrapMaterial
}

internal fun LanBinding.sameTrustMaterial(other: LanBinding): Boolean
```

- [x] Add RED tests for A/B symmetry, unsigned UTF-8 device ordering, length-delimited context bytes, identity collision, 31/33-byte key rejection, mutation resistance of returned arrays, and changed device/encryption/signing identity changing the context and secret. Use a deterministic `BoxPrecomputer` in JVM tests. Run the new test class and confirm it fails to compile before production types exist.
- [x] Add RED JVM coverage proving `sameTrustMaterial` ignores `pairedAtMillis` and requires the same protocol/pin/secret; retain the existing instrumented replacement-rejection coverage unchanged.
- [x] Implement canonical context encoding with unsigned 32-bit big-endian lengths. Use `SodiumAndroid.crypto_box_beforenm` for the 32-byte shared key and JCA `HmacSHA256` for the specified extract/expand steps.
- [x] Zero `shared_key`, `prk`, temporary context buffers containing derived material, and local copies in `finally`; return defensive copies only. Map native failures to `lan_bootstrap_crypto_unavailable` without logging key material.
- [x] Add the trust-material comparison helper without weakening `LanPairStore.commit` replacement checks.
- [x] Run `cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.lan.LanBootstrapCryptoTest'` and `git diff --check`; retain the existing instrumented store suite for the final device gate.
- [x] Commit with `feat(mobile/lan): derive bootstrap trust`.

### Task 4: Persist reliable controls and classify delivery state in Room v9

**Files:**
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/PeerControlOutbox.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/PeerControlOutboxTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`
- Verify unchanged: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/OutboxRepositoryTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionControlEncoder.kt`

**Interfaces:**

```kotlin
data class DeliveryQueueSnapshot(
    val pendingLocal: Int,
    val awaitingPeer: Int,
    val heldByRelay: Int,
    val internalActive: Int,
    val totalActive: Int,
    val totalActiveBytes: Long,
    val userContentKind: UserContentKind,
)

enum class UserContentKind { NOTIFICATIONS, SYNC_UPDATES }

interface PeerControlSealer {
    suspend fun seal(event: InnerEventV2, requiresPeerReceipt: Boolean): OutboundMessage
}

class PeerControlOutbox {
    suspend fun ensureBootstrap(generation: Int, payload: LanBootstrapPayload): OutboundMessage
    suspend fun ensureProbe(generation: Int, requestDirect: Boolean): OutboundMessage?
    fun acceptProbeReceipt(ackedMsgId: String, digest: String, generation: Int, now: Long): Boolean
    fun peerEvidence(generation: Int, now: Long): PeerEvidence
}
```

- [x] Add RED encoder/controller tests proving a bootstrap has a ten-minute TTL, a probe has a two-minute TTL, `probe_id == msg_id`, both require peer receipts, encryption uses the stored peer, one bootstrap is reused within a generation, one unexpired probe is active, and current-generation digest-matching receipts alone create 150-second evidence.
- [x] Add RED Room transaction tests for the classification truth table: `NEW` without custody is pending local; `ACCEPTED` is awaiting peer; relay-custody `ACCEPTED` is held by relay; receipts/snapshots/bootstrap/probe/unpair are internal; notification-only work uses `NOTIFICATIONS`; call/action/mixed work uses `SYNC_UPDATES`; totals and bytes still include every active row.
- [x] Run the focused JVM controller test and compile the instrumented Room test; confirm failure before implementation.
- [x] Implement a reusable control sealer by extracting the existing envelope construction pattern without changing notification/action wire bytes. Use `NonceSource`, `ProtocolJson`, X25519 encryption, lowercase SHA-256, defensive JSON, and `requiresPeerReceipt=true` for the two new controls.
- [x] Add only DAO queries/projections and `@Transaction` composition methods; do not change entities or database version. Keep `activeOutboundCount/Bytes` for engineering/retention callers and add explicit `deliveryQueueSnapshot()` for product status.
- [x] Make probe tracking process-local and generation-scoped. Register only after durable insert succeeds; an old accepted probe after restart cannot assert liveness and blocks no fresh probe once its inner expiry passes.
- [x] Run the focused controller/action JVM tests, compile the Android suite, run all 50 `ReliableDeliveryTransactionTest` cases on the connected M2012K11AI, and run `git diff --check`.
- [x] Commit with `feat(mobile/delivery): persist peer controls`.

### Task 5: Apply bootstrap/probes and receipt them atomically

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LanBootstrapProcessor.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LanBootstrapProcessorTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryTransactionTest.kt`

**Interfaces:**

```kotlin
sealed interface ReceiptBackedControlResult {
    data object Applied : ReceiptBackedControlResult
    data class Rejected(val code: String) : ReceiptBackedControlResult
}

@Transaction
suspend fun commitReceiptBackedControl(
    inbound: InboundMessage,
    receipt: OutboundMessage,
    process: suspend () -> ReceiptBackedControlResult,
): DirectControlCommitResult

interface LanBootstrapProcessor {
    suspend fun process(payload: LanBootstrapPayload): LanBootstrapProcessResult
}
```

- [x] Add RED DAO tests proving process success inserts inbound journal plus one receipt in one Room transaction, duplicate digest is idempotent, message-ID conflict is rejected, process rejection inserts neither row, and receipt conflict rolls back.
- [x] Add RED bootstrap processor tests for context mismatch, absent-binding commit, same-trust idempotence, binding conflict preservation, nearby-random binding preservation, own announcement durability before commit, store failure mapping, and “binding changed” signaling only after a successful commit.
- [x] Add RED dispatcher tests proving both new controls require authenticated v2 opening, produce durable peer receipts, never materialize, never enter Recent activity, duplicate safely, probe `request_direct` uses the bounded signal seam, and only a matching probe receipt updates evidence. Prove ordinary notification receipts do not set relay peer evidence.
- [x] Run the two JVM classes and compile the instrumented Room class; confirm the new tests fail.
- [x] Expose `DurableReceiptFactory.createApplied(ackedMsgId, envelopeSha256)` and add `commitReceiptBackedControl` without changing existing notification materialization ordering.
- [x] Implement `DefaultLanBootstrapProcessor`: load paired identities, recompute context/secret, validate conflict, ensure the local announcement, commit through `LanPairStore`, and return a post-commit route-reload flag. Never clear relay pairing on failure.
- [x] Route `lan.bootstrap` and `peer.probe` before the existing direct-control allowlist. Create the receipt before entering the Room transaction; run the processor inside it; emit route-reload/direct-attempt signals only after the transaction returns committed.
- [x] Replace unconditional `setLastReceiptAt` with generation/digest/expiry-correlated probe evidence. Continue updating delivery classification for every valid peer receipt.
- [x] Run the two focused JVM classes, compile the Android suite, run all 52 `ReliableDeliveryTransactionTest` cases on the connected M2012K11AI, and run `git diff --check`.
- [x] Commit with `feat(mobile/lan): bootstrap relay pairs`.

### Task 6: Promote relay to LAN without overlapping drainers

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/TransportCoordinator.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/TransportCoordinatorTest.kt`

**Coordinator additions:**

```kotlin
fun interface RelayProbeScheduler {
    suspend fun ensureProbe(requestDirect: Boolean)
}

data class LanRetryPolicy(
    val delaysMs: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000),
)
```

- [x] Add RED deterministic coroutine tests for: relay promotion to an already authenticated LAN candidate; relay `close`/join completing before the first LAN `sendable()` call; failed candidates leaving relay active; failed probes using 15/30/60/120/300-second cooldown; a 30-second LAN session resetting only LAN failures; LAN loss opening relay before cooldown; retry interrupting cooldown without resetting its count; inbound direct requests respecting a 15-second floor; `preferLan=false` never probing; stop/cancel closing relay, candidate, and LAN exactly once; and `maxConcurrentDrains == 1` through stress handoffs.
- [x] Run `cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.service.TransportCoordinatorTest'` and confirm the promotion tests fail against the static coordinator.
- [x] Refactor `carry` into explicit granted-session ownership. A relay session may coexist only with an ungranted LAN `open()` candidate; no candidate pump starts during authentication.
- [x] On candidate success, close relay with `route_promoted_to_lan`, await its fully joined session, publish LAN authenticated, and only then start the coordinator LAN pump.
- [x] Track LAN failure/cooldown separately from general relay reconnect state. On LAN loss, set the next LAN due time and open relay immediately. Keep existing route stability semantics for nonpromotion failures.
- [x] Race cooldown with explicit retry, inbound direct-attempt requests, relay closure, and cancellation using structured concurrency. Preserve cancellation identity and close all owned sessions in `NonCancellable` cleanup where required.
- [x] Call the relay probe scheduler immediately and at its due interval while relay is active; set `request_direct=true` only when the LAN cooldown is due.
- [x] Run the focused coordinator/route tests repeatedly (`--rerun-tasks` once), then `git diff --check`.
- [x] Commit with `feat(mobile/transport): promote relay sessions to LAN`.

### Task 7: Reload routes and publish one truthful native status

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncServiceStatus.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LiveTransportRoutes.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveServiceTransportTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveTransportRoutesTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCore.types.ts`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Modify: `mobile/hooks/useRouteStatus.ts`
- Modify: `mobile/hooks/__tests__/useRouteStatus.test.ts`

**Public status:**

```kotlin
enum class PeerEvidence { DIRECT, RECENT, STALE, UNKNOWN }

enum class DeliveryReason {
    NONE,
    NO_ROUTE,
    WAITING_FOR_PEER,
    RELAY_HOLDING,
    LAN_BOOTSTRAP_WAITING,
    LAN_BINDING_CONFLICT,
    PEER_VERSION_INCOMPATIBLE,
}

data class SyncRouteStatus(
    val route: RouteKind,
    val phase: RoutePhase,
    val queuedCount: Int, // backward-compatible alias of pendingLocalCount
    val pendingLocalCount: Int,
    val awaitingPeerCount: Int,
    val heldByRelayCount: Int,
    val peerEvidence: PeerEvidence,
    val deliveryReason: DeliveryReason,
    val userContentKind: UserContentKind,
    val routeGeneration: Int,
)
```

- [x] Add RED status tests for reason priority, `queued_count == pending_local_count`, internal exclusion, total-active engineering fields, direct/recent/stale/unknown evidence, 150-second expiry, generation fencing, and no private transport fields in `toPublicMap()`.
- [x] Add RED lifecycle tests proving floor-2 plus both peer features triggers bootstrap/probe, a missing peer feature yields `peer_version_incompatible` without enqueueing an unknown control, a new binding invokes the conflated restarter after dispatch returns, and a route reload cancels/joins the old coordinator before constructing the next.
- [x] Add RED hook/native-bridge tests for defaults and every added snake_case field. Run the focused Kotlin tests and `cd mobile && npm test -- --runInBand hooks/__tests__/useRouteStatus.test.ts` and confirm failure.
- [x] Replace product callers of `activeOutboundCount()` with `deliveryQueueSnapshot().pendingLocal`; retain explicit totals/bytes for retention, storage pressure, and diagnostics. Add `setQueueSnapshot` rather than overloading `setQueueStats` with conflicting meanings.
- [x] Wire one service-owned `PeerControlOutbox` into relay feature handling, `InboundDispatcher`, and `TransportCoordinator`. Capture the route generation once and reject stale callbacks.
- [x] Use `SerializedTransportRestarter.forceRestart()` for LAN-binding changes. The signal producer must return without joining its own transport job; the restarter remains the only join/reload owner.
- [x] Publish evidence/reason/classified counts through native maps and TypeScript types. `SyncHealth.queuedCount` stays the pending-local alias; add explicit total-active count/bytes fields for engineering health.
- [x] Run `cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.service.ServiceLifecycleTest' --tests 'co.twinotify.core.service.LiveServiceTransportTest' --tests 'co.twinotify.core.service.LiveTransportRoutesTest'`, the focused Jest hook test, `cd mobile && npm run typecheck`, and `git diff --check`.
- [x] Commit with `feat(mobile/status): report delivery custody truth`.

### Task 8: Render truthful route, custody, and peer copy

**Files:**
- Modify: `mobile/state/routePresentation.ts`
- Modify: `mobile/state/__tests__/routePresentation.test.ts`
- Modify: `mobile/app/home.tsx`
- Modify: `mobile/app/__tests__/homeHandoffTrace.test.tsx`
- Modify: `mobile/components/home/ConnectionSurface.tsx`
- Modify: `mobile/components/home/__tests__/ConnectionSurface.test.tsx`

**Presentation rule:** `DeliveryPresentation` gains a `peerLine: 'Reachable now' | 'Checked in recently' | 'Not confirmed online' | null`; `ConnectionSurface` renders that value and includes it in its accessibility label. `home.tsx` no longer infers reachability from route kind.

- [ ] Add RED presentation tests covering every table row in section 8.3 of the approved design: direct, fresh relay, stale relay empty, relay-held, bootstrap waiting, incompatible peer, binding conflict, no-route pending local, no-route relay-held, reconnecting empty, and paused.
- [ ] Add RED grammar tests: one/many notifications only when `user_content_kind=notifications`; otherwise one/many sync updates. Prove relay-held work remains “Via relay,” and only no-route pending-local work becomes “Queued on this phone.”
- [ ] Add RED screen tests proving relay never renders “Reachable now,” direct does, fresh relay uses “Checked in recently,” stale/unknown uses “Not confirmed online,” the accessibility live label includes all three copy lines, retry appears only where the approved table permits, and existing switch/button targets remain at least 48 dp.
- [ ] Run `cd mobile && npm test -- --runInBand state/__tests__/routePresentation.test.ts app/__tests__/homeHandoffTrace.test.tsx components/home/__tests__/ConnectionSurface.test.tsx` and confirm failure.
- [ ] Implement the mapping in `routePresentation.ts` as the sole UI wording authority. Remove `peerReachable` from `home.tsx` and `ConnectionSurface`; pass/render the approved `peerLine` instead.
- [ ] Keep the current spacing, trace geometry, typography tokens, theme colors, and primitive controls. At narrow width or large font scale, retain the existing stacked header and untruncated multiline text.
- [ ] Run the focused Jest command, `cd mobile && npm run typecheck`, `cd mobile && npm run lint`, and `git diff --check`.
- [ ] Complete the anti-slop point-by-point review for hierarchy, restraint, copy truth, spacing, typography, color, light/dark behavior, 2x font, accessibility labels, and pressed/disabled/retry states. Record any physical-only item as pending rather than inferring success.
- [ ] Commit with `fix(mobile/home): show delivery truth`.

### Task 9: Add deterministic end-to-end evidence and operator guidance

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- Modify: `e2e/internal/control/control.go`
- Modify: `e2e/internal/control/control_test.go`
- Modify: `e2e/internal/scenario/route_evidence.go`
- Modify: `e2e/internal/scenario/route_evidence_test.go`
- Modify: `e2e/internal/scenario/scenario.go`
- Modify: `e2e/internal/scenario/scenario_test.go`
- Modify: `docs/test-scenarios.md`

- [ ] Add RED host verifier tests for a sanitized route transition sequence `RELAY -> LAN -> RELAY -> LAN`, strictly increasing route generations, no overlapping drainer evidence, classified counts, and relay evidence becoming stale when the peer stops.
- [ ] Add debug-only evidence fields for route kind/phase/generation, peer-evidence enum, classified counts, and stable bounded reason code. Reject URLs, IPs, SSIDs, ports, tokens, pins, certificates, raw keys, ciphertext, titles, and notification text.
- [ ] Extend the existing direct-LAN fallback scenario rather than creating a duplicate runner where possible. Add automatic relay-bootstrap and peer-stale assertions; keep artifacts from every completed child when a later child fails.
- [ ] Document rollout order (relay first), mixed-version behavior, no-touch automatic promotion, and the exact two-phone ADB steps from the approved design.
- [ ] Run focused Go tests under `e2e/`, `make host-verify`, and `git diff --check`.
- [ ] Commit with `test(e2e): verify automatic LAN promotion`.

### Task 10: Run release-proportionate verification and physical acceptance

**Automated gates:**

- [ ] Run `make sync-proto`.
- [ ] Run `cd relay && go test ./... -race -count=1`.
- [ ] Run `cd relay && go vet ./...`.
- [ ] Run `cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest :twinotify-core:lintDebug :twinotify-core:assembleDebug`.
- [ ] Run `cd mobile && npm test -- --runInBand`.
- [ ] Run `cd mobile && npm run typecheck`.
- [ ] Run `cd mobile && npm run lint`.
- [ ] Run `make host-verify`.
- [ ] Run `git diff --check` and inspect `git status --short` for generated or unrelated files.

**Physical two-phone gate:**

- [ ] Confirm both attached serials, app version, version code, and installed APK SHA-256; sanitize serials in committed evidence.
- [ ] Preserve pairing and app data. Clear only diagnostic logs/evidence.
- [ ] Deploy or point both phones at the capability-aware relay before installing the new APK.
- [ ] Start on different networks and record relay fallback plus non-reachable peer wording.
- [ ] Put both unlocked phones on the same Wi-Fi without touching Twinotify and record automatic binding creation and `RELAY -> LAN` within the configured bound.
- [ ] Send multiple notifications in both directions and confirm each logical message reaches a peer receipt without duplicate delivery or notification-replacement regression.
- [ ] Disable Wi-Fi on one phone and record immediate relay fallback before the next LAN cooldown.
- [ ] Restore Wi-Fi and record coordinated bounded promotion back to LAN.
- [ ] Stop the peer service and record `recent -> stale` after 150 seconds with no “Reachable now” claim.
- [ ] Restart both apps and record binding persistence plus idempotent bootstrap.
- [ ] Capture light/dark screenshots and large-font interaction checks for direct, relay-held, no-route queued, and stale-peer states.
- [ ] If ADB, relay deployment, or a named physical state is unavailable, leave that checkbox pending and state the exact unverified condition.

**Final review:**

- [ ] Compare every acceptance criterion in `docs/superpowers/specs/2026-08-31-automatic-lan-promotion-delivery-truth-design.md` against code and artifacts.
- [ ] Invoke `superpowers:verification-before-completion`, run its required checks, and do not claim completion from stale output.
- [ ] Review the complete diff for unrelated changes, secret/private-network leakage, Room version drift, generated schema files, and a second outbox-drain path.
- [ ] Commit only any final test/documentation correction with a scoped conventional commit; otherwise leave the verified task commits unchanged.
