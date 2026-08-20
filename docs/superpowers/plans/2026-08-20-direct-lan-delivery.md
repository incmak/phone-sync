# Direct LAN Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver every Twinotify envelope directly over an authenticated paired LAN connection, with automatic relay fallback and truthful route UX.

**Architecture:** One Room-backed outbox and one `TransportCoordinator` own all outbound selection. `LanTransport` and `RelayTransport` become authenticated route sessions; both deliver through the existing `InboundDispatcher`. The mobile UI consumes a native route status rather than inferring “offline” from relay state.

**Tech Stack:** Kotlin, Room v5, Android NSD/ConnectivityManager/JSSE, coroutines/Flow, TypeScript/Expo Router, JUnit4, Android instrumentation, two physical Android phones.

## Global Constraints

- Android min SDK remains 34; all queues and frame paths are byte- and count-bounded.
- LAN discovery is untrusted until rotating-advertisement match, TLS pin, and signed hello succeed.
- No content, raw IDs, tokens, keys, pins, endpoint addresses, or SSIDs enter logs, DNS-SD, UI diagnostics, or evidence.
- A route never mutates an envelope ID, digest, sequence, or receipt meaning.
- Exactly one owner may select and send durable outbound rows at a time.
- Preserve current one-peer semantics, protocol-v2 validation, and `InboundDispatcher` as the only inbound application path.
- Do not use a worktree or push. Preserve unrelated current `RelayTransport` edits until their own review/commit boundary.

---

### Task 1: Finish the existing relay-session hardening boundary

**Files:**
- Modify only the current reviewed pair: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`, `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt`

**Produces:** A relay route session that cannot overlap an old socket with a reconnecting socket, fences late callbacks, joins workers, and preserves durable ACK order.

- [ ] Write/retain deterministic RED coverage for delayed close acknowledgement, timeout cancel fallback, rejected socket callbacks, stale onText, and receipt-to-ACK order.
- [ ] Run `cd mobile/android && ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*RelayTransportTest' --tests '*OutboxRepositoryTest' --rerun-tasks`; require all focused tests green.
- [ ] Request independent review of only this diff; fix every Critical/Important finding before commit.
- [ ] Run `:twinotify-core:testDebugUnitTest :twinotify-core:lintDebug`, `git diff --check`, then commit exact files as `fix(android): harden relay route session`.

### Task 2: Migrate durable custody from relay-specific to route-neutral

**Files:**
- Modify: `ReliableDeliveryEntities.kt`, `ReliableDeliveryDao.kt`, `NotificationDb.kt`, `OutboxRepository.kt`, all `OutboundMessage` construction sites
- Create: schema `mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/5.json`
- Test: `OutboxRepositoryTest.kt`, `ReliableDeliveryMigrationTest.kt`, `ReliableDeliveryTransactionTest.kt`

**Interfaces:**
```kotlin
enum class CustodyRoute { LAN, RELAY }
suspend fun onCustodyAccepted(msgId: String, route: CustodyRoute): CustodyResult
```

- [ ] Add RED tests proving Room v4 `relayAcceptedAt` migrates losslessly to `custodyAcceptedAt` plus `custodyRoute=RELAY`, route acceptance is idempotent, receipts delete on either route, ordinary rows remain until peer receipt, and a failed transaction rolls back.
- [ ] Implement `MIGRATION_4_5` without destructive migration and route-neutral repository names.
- [ ] Run JVM focused tests and `:twinotify-core:compileDebugAndroidTestKotlin`; execute migration/transaction instrumentation on a connected device when available.
- [ ] Independently review/commit exact migration, repository, schema, and test paths as `refactor(android): make message custody route neutral`.

### Task 3: Build the bounded direct frame and paired-discovery foundations

**Files:**
- Create: `core/lan/LanFrame.kt`, `LanFrameCodec.kt`, `LanAdvertisement.kt`, `LanDiscovery.kt`, `AndroidLanDiscovery.kt`
- Test: `LanFrameCodecTest.kt`, `LanAdvertisementTest.kt`, `LanDiscoveryTest.kt`, `AndroidLanDiscoveryTest.kt`

**Interfaces:**
```kotlin
sealed interface LanFrame { val version: Int }
interface LanDiscovery { fun candidates(): Flow<LanCandidate>; suspend fun close() }
```

- [ ] Add RED parser tests for closed-world frame fields, duplicate JSON keys, malformed lengths/UTF-8/base64, trailing bytes, oversized control/envelope data, and four-frame byte-budget backpressure.
- [ ] Add RED discovery tests for secret-derived rotating advertisements, current/adjacent-day matching only, privacy-safe TXT records, network-bound candidates, and balanced NSD/multicast cleanup.
- [ ] Implement four-byte framing, immutable typed frames, bounded buffers, secret-derived rotating advertisements, and network-aware Android NSD.
- [ ] Run focused JVM tests, compile Android test sources, independently review, and commit exact scope as `feat(android): define private LAN transport foundations`.

### Task 4: Authenticate one direct LAN connection

**Files:**
- Create: `core/lan/LanHandshake.kt`, `LanConnectionFactory.kt`
- Test: `LanHandshakeTest.kt`, `LanPinnedTlsTest.kt`

**Interfaces:**
```kotlin
interface AuthenticatedLanConnection : Closeable {
  val peerDeviceId: String
  val incoming: Flow<LanFrame>
  suspend fun send(frame: LanFrame)
}
```

- [ ] Add RED tests for wrong TLS pin, wrong signing key, nonce replay, reflected role, protocol downgrade, simultaneous connection arbitration, timeout, and cancellation cleanup.
- [ ] Implement TLS pin verification before parsing a signed hello; bind hello signatures to both identities, both nonces, connection role, protocol version, and TLS session context.
- [ ] Execute loopback instrumentation and a two-phone handshake. Record only state codes and hashes.
- [ ] Independently review and commit as `feat(android): authenticate direct LAN sockets`.

### Task 5: Deliver direct envelopes through existing durable inbound custody

**Files:**
- Create: `core/lan/LanTransport.kt`, `LanTransportTest.kt`, `LanCustodyTransactionTest.kt`
- Modify: `InboundDispatcher.kt`, `OutboxRepository.kt`, focused dispatcher/repository tests

**Interfaces:**
```kotlin
sealed interface InboundDispatchResult {
  data class Accepted(val msgId: String, val envelopeSha256: String) : InboundDispatchResult
  data class Duplicate(val msgId: String, val envelopeSha256: String) : InboundDispatchResult
  data class Rejected(val code: String) : InboundDispatchResult
}
```

- [ ] Add RED tests covering post/update/cancel, call state, snapshots, receipt frames, duplicate custody, digest conflict, crash-after-custody resend, bounded burst, and materialization failure after durable acceptance.
- [ ] Return the typed dispatcher result only after the Room transaction boundary; implement LAN accepted/receipt frames from that result.
- [ ] Keep one ordered reader, one writer, and bounded queues in `LanTransport`; it must not drain the outbox itself.
- [ ] Run focused JVM/Room tests, compile and execute relevant Android tests, review, and commit as `feat(android): retain LAN events through peer receipt`.

### Task 6: Coordinate LAN-first and relay fallback from one owner

**Files:**
- Create: `TransportCoordinator.kt`, `TransportCoordinatorTest.kt`
- Modify: `RelayTransport.kt`, `LanTransport.kt`, route/repository tests

**Interfaces:**
```kotlin
enum class RouteKind { LAN, RELAY, NONE }
data class RouteHealth(val active: RouteKind, val phase: RoutePhase, val queuedCount: Int)
interface TransportRoute { val kind: RouteKind; suspend fun open(): AuthenticatedRouteSession }
```

- [ ] Add deterministic RED tests proving authenticated LAN wins, relay continues during LAN failure, only one route calls `sendable`, ambiguous rows may resend exactly, health is truthful, and backoff resets only after sustained authenticated health.
- [ ] Refactor relay to a coordinator-granted session and implement LAN-first selection, atomic route handoff, bounded reconnect, and shared inbound worker.
- [ ] Run the coordinator stress suite repeatedly with coroutine debugging, then independent review/commit as `refactor(android): coordinate direct and relay routes`.

### Task 7: Make service lifecycle and public status route-aware

**Files:**
- Modify: `SyncService.kt`, `ServiceConfigStore.kt`, `ServiceStartPolicy.kt`, `SyncServiceStatus.kt`, `BootReceiver.kt`, `TwinotifyCoreModule.kt`, `TwinotifyCore.types.ts`, `TwinotifyCoreModule.ts`
- Test: `ServiceLifecycleTest.kt`, TypeScript module tests

**Interfaces:**
```kotlin
data class ServiceConfig(val enabled: Boolean, val preferLan: Boolean, val relayUrl: String?)
data class SyncRouteStatus(val route: RouteKind, val phase: RoutePhase, val queuedCount: Int)
```

- [ ] Add RED tests proving an enabled LAN-bound peer starts without a relay URL; relay-only peers stay relay-only; Wi-Fi loss queues without loss; boot/unpair/stop cleanly join every route resource; and health reports LAN/relay/reconnecting/queued accurately.
- [ ] Add route preference/retry APIs and a route status event to the native bridge without exposing private network data.
- [ ] Run lifecycle/unit/Android-test compilation gates, independently review, and commit as `feat(android): prefer direct LAN with relay fallback`.

### Task 8: Ship truthful, accessible route UX

**Files:**
- Modify: `mobile/app/home.tsx`, `mobile/app/settings/index.tsx`, `mobile/app/pair/success.tsx`, `mobile/hooks/useSyncStatus.ts`, `mobile/components/primitives/TwStatusDot.tsx`
- Test: `mobile/app/**/__tests__/*`, `mobile/components/primitives/__tests__/TwStatusDot.test.tsx`

- [ ] Add RED behavior tests for Direct on Wi-Fi, Via relay, Reconnecting, Queued for delivery, and Not paired; each maps only from the public native route status.
- [ ] Replace relay-inferred “Offline” copy with the approved route-state model. Show one status label, short concrete explanation, and only one recovery action where appropriate.
- [ ] Verify screen-reader labels/live updates, dark/light themes, and 2x font scale; do not use decorative status pills, fake controls, or animation-hidden content.
- [ ] Run Jest, typecheck, scoped lint, physical screen checks on both phones, independent UI/design-law review, and commit as `feat(mobile): show truthful delivery routes`.

### Task 9: Prove end-to-end direct delivery and fallback

**Files:**
- Modify: E2E debug control/state provider, `e2e/internal/control`, `e2e/internal/scenario`, `Makefile`, `docs/test-scenarios.md`
- Create: LAN scenario/evidence verifier and tests

- [ ] Add sanitized evidence fields for route kind, route generation, queue count/bytes, receipt time, and stable error code. Reject evidence containing secrets or network identifiers.
- [ ] Add two-phone scenarios for notification post/update/cancel, call ringing/active/idle, snapshot/receipt convergence, process restart, LAN loss with relay fallback, return to LAN, bounded burst, and unpair during traffic.
- [ ] Run repository gates, Go race/vet, Android unit/lint/Android-test compile, and connected instrumentation.
- [ ] Run controlled no-uplink acceptance with packet/DNS observations, then direct/fallback tests on the two connected phones. Verify direct notification and call delivery in both directions.
- [ ] Obtain a final independent review and commit evidence tooling as `feat(e2e): verify direct LAN synchronization`.

## Completion Evidence

Completion requires current-tree evidence of a lossless v4→v5 migration, authenticated LAN delivery for every envelope type, automatic relay fallback, route-correct UI, bounded resources, and physical two-phone direct notification/call verification. The current shared-Wi-Fi pairing success alone is not sufficient; it proves trust establishment, not direct delivery.
