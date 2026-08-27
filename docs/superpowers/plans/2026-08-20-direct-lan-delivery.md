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

- [x] Deterministic relay-session RED coverage and ACK-order regressions landed (commit `d2057c9`).
- [x] Focused relay and outbox tests passed for the implementation boundary (commit `d2057c9`).
- [x] Independent task review completed with findings resolved (commit `d2057c9`).
- [x] JVM, lint, and diff gates completed for the relay-session boundary (commit `d2057c9`).

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

- [x] Room v4-to-v5 migration, idempotent custody, receipt, retention, and rollback RED coverage landed (commit `263d1e1`).
- [x] `MIGRATION_4_5` and route-neutral repository naming landed without destructive migration (commit `263d1e1`).
- [x] Focused JVM and Android-test source compilation gates passed (commit `263d1e1`).
- [ ] Connected-device migration and transaction acceptance - pending physical two-phone run.
- [x] Independent migration/repository/schema review completed (commit `263d1e1`).

### Task 3: Build the bounded direct frame and paired-discovery foundations

**Files:**
- Create: `core/lan/LanFrame.kt`, `LanFrameCodec.kt`, `LanAdvertisement.kt`, `LanDiscovery.kt`, `AndroidLanDiscovery.kt`
- Test: `LanFrameCodecTest.kt`, `LanAdvertisementTest.kt`, `LanDiscoveryTest.kt`, `AndroidLanDiscoveryTest.kt`

**Interfaces:**
```kotlin
sealed interface LanFrame { val version: Int }
interface LanDiscovery { fun candidates(): Flow<LanCandidate>; suspend fun close() }
```

- [x] Closed-world bounded frame parser RED coverage landed (commit `192570d`).
- [x] Private rotating-advertisement and discovery lifecycle RED coverage landed (commit `192570d`).
- [x] Four-byte framing, typed frames, bounded buffers, private advertisements, and network-aware NSD landed (commit `192570d`).
- [x] Focused JVM, Android-test compilation, and independent review gates completed (commit `192570d`).

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

- [x] Pin, signature, replay, role, downgrade, arbitration, timeout, and cleanup RED coverage landed (commit `5355494`).
- [x] Pinned TLS and signed-hello authentication landed (commit `5355494`).
- [x] Loopback pinned-TLS instrumentation passed with sanitized evidence (commit `5355494`).
- [ ] Two-phone authenticated handshake acceptance - pending physical two-phone run.
- [x] Independent authentication review completed (commit `5355494`).

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

- [x] Direct post/update/cancel, call, snapshot, receipt, custody, conflict, resend, burst, and materialization RED coverage landed (commit `fc891e3`).
- [x] Typed post-transaction dispatcher results and LAN custody/receipt frames landed (commit `fc891e3`).
- [x] Single ordered reader/writer with bounded queues and coordinator-owned draining landed (commit `fc891e3`).
- [x] Focused JVM/Room, Android-test, and independent review gates completed (commit `fc891e3`).

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

- [x] Deterministic route ownership, fallback, resend, health, and backoff RED coverage landed (commit `65fc5f8`).
- [x] Coordinator-granted LAN-first selection, atomic handoff, bounded reconnect, and shared inbound ownership landed (commit `65fc5f8`).
- [x] Coordinator stress and independent review gates completed (commit `65fc5f8`).

### Task 7: Make service lifecycle and public status route-aware

**Files:**
- Modify: `SyncService.kt`, `ServiceConfigStore.kt`, `ServiceStartPolicy.kt`, `SyncServiceStatus.kt`, `BootReceiver.kt`, `TwinotifyCoreModule.kt`, `TwinotifyCore.types.ts`, `TwinotifyCoreModule.ts`
- Test: `ServiceLifecycleTest.kt`, TypeScript module tests

**Interfaces:**
```kotlin
data class ServiceConfig(val enabled: Boolean, val preferLan: Boolean, val relayUrl: String?)
data class SyncRouteStatus(val route: RouteKind, val phase: RoutePhase, val queuedCount: Int)
```

- [x] LAN-only, relay-only, Wi-Fi-loss, lifecycle cleanup, and truthful-health RED coverage landed (commit `098d74f`).
- [x] Privacy-safe route preference, retry, and status bridge APIs landed (commit `098d74f`).
- [x] Lifecycle, unit, Android-test compilation, and independent review gates completed (commit `098d74f`).

### Task 8: Ship truthful, accessible route UX

**Files:**
- Modify: `mobile/app/home.tsx`, `mobile/app/settings/index.tsx`, `mobile/app/pair/success.tsx`, `mobile/hooks/useSyncStatus.ts`, `mobile/components/primitives/TwStatusDot.tsx`
- Test: `mobile/app/**/__tests__/*`, `mobile/components/primitives/__tests__/TwStatusDot.test.tsx`

- [x] Public-route behavior tests for direct, relay, reconnecting, queued, and unpaired states landed (commit `ea4a63c`).
- [x] Relay-inferred offline copy was replaced by the truthful route-state model (commit `ea4a63c`).
- [x] Screen-reader, theme, 2x-font, and anti-slop behavior checks landed (commit `ea4a63c`).
- [x] Jest, typecheck, scoped lint, and independent UI/design-law review completed (commit `ea4a63c`).
- [ ] Physical light/dark and 2x-font screen checks on both handsets - pending physical two-phone run.

### Task 9: Prove end-to-end direct delivery and fallback

**Files:**
- Modify: E2E debug control/state provider, `e2e/internal/control`, `e2e/internal/scenario`, `Makefile`, `docs/test-scenarios.md`
- Create: LAN scenario/evidence verifier and tests

- [x] Sanitized, fail-closed route evidence fields and privacy rejection landed (commit `bc049e5`).
- [x] Host scenarios for direct post/cancel/update, peer dismiss, call state, snapshot/receipt, bounded burst, and unpair during traffic landed (commit `9c136cc`).
- [x] Repository host gates plus Go race/vet and verifier self-tests passed for the evidence tooling (commit `9c136cc`).
- [x] Independent review of the direct-LAN evidence tooling completed (commit `9c136cc`).
- [ ] Process-restart direct delivery on both handsets - pending physical two-phone run.
- [ ] LAN-loss relay fallback and return-to-LAN acceptance - pending physical two-phone run.
- [ ] Controlled no-uplink packet/DNS acceptance - pending physical two-phone run.
- [ ] Direct notification and call delivery in both directions on hardware - pending physical two-phone run.

## Completion Evidence

Implementation and host automation are complete through commit `9c136cc`. Physical release acceptance is still pending: every unchecked item above is a pending physical two-phone run. Shared-Wi-Fi pairing or host fixtures prove neither direct hardware delivery nor relay fallback by themselves.
