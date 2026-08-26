# Live Direct LAN Service Integration Plan

> **Execution:** Use `superpowers:subagent-driven-development` task by task in the primary checkout. No worktrees and no push.

**Goal:** Make the authenticated direct-LAN route used by the running Android service, with LAN-first selection, relay fallback, truthful status, and physical two-phone proof.

**Why this correction exists:** The 2026-08-20 plan produced and tested `DirectLanConnector`, `LanRoute`, and `TransportCoordinator`, but production `SyncService` still constructs only `RelayTransport`. A loopback instrumentation test proves components, not app behavior.

## Global constraints

- Preserve one Room outbox owner and one ordered inbound dispatcher.
- Load LAN trust only through `PeerStore` plus `LanPairStore.loadValidated`; a missing or invalid sealed binding disables LAN without deleting a valid relay pair.
- Use the selected Wi-Fi `Network` for listener registration, discovery, and dialing; never expose addresses, SSIDs, pins, keys, secrets, or raw IDs in status/logs/evidence.
- Verify peer TLS pin before signed hello parsing. Bind signed hello to both device identities, both nonces, roles, version, and TLS session context.
- LAN attempt failures must be bounded and must allow relay fallback. A LAN-only pair remains running and queued while reconnecting.
- Service stop, unpair, network loss, and destruction must close discovery, listener, network lease, route session, workers, and coordinator exactly once.
- Existing UI consumes only public route status. UI changes require the full anti-slop point-by-point review.

### Task 1: Build the production live-route factory

**Files:**
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/LiveTransportRoutes.kt`
- Test: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveTransportRoutesTest.kt`
- Modify only if required for a narrow reusable seam: `lan/AndroidLanDiscovery.kt`, `lan/LanSession.kt`, focused tests

- [ ] Add RED tests for validated-binding load, corrupt/missing binding fallback, Wi-Fi lease loss, advertisement current/adjacent day derivation, listener port publication, initiator/acceptor role agreement, peer signing key use, and exactly-once cleanup.
- [ ] Construct a LAN route from the stored peer, sealed binding, local identity/signing key, selected Wi-Fi network, pinned mutual TLS contexts, private NSD discovery, `DirectLanConnector`, `LanRoute`, and existing dispatcher/outbox.
- [ ] Construct a relay route adapter that preserves current relay capabilities, inbound delivery ordering, snapshot hooks, and self-draining ownership.
- [ ] Run focused JVM tests, Android-test source compilation, lint, and independent review.

### Task 2: Replace the relay-only service loop with one coordinator

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`, `SyncServiceStatus.kt`
- Test: `ServiceLifecycleTest.kt`, `TransportCoordinatorTest.kt`, new focused integration tests as needed

- [ ] Add RED tests proving `SyncService` starts the coordinator for LAN-bound peers, uses relay fallback when configured, runs LAN-only without a relay URL, publishes authenticated LAN/relay/reconnecting/queued truth, and never starts parallel outbox drains.
- [ ] Route explicit retry and preference changes into the live coordinator. Preserve legacy migration, materialization, retention, call capture, snapshot, and unpair ordering.
- [ ] Join coordinator and all route resources on stop/unpair/destroy; do not leak NSD, multicast, network callbacks, sockets, or jobs.
- [ ] Run focused tests twice, full JVM + Android-test compile + lint, and independent review.

### Task 3: Prove product behavior and close UX/evidence gaps

**Files:**
- Modify only test/evidence/UI files required by findings from real execution.

- [ ] Build and install the current debug app on both connected phones without clearing app data unless the user explicitly authorizes it.
- [ ] Verify the running service authenticates LAN and delivers notification post/update/cancel, call ringing/active/idle, snapshots, and receipts in both directions.
- [ ] Verify LAN failure falls back to relay using a route-specific debug control; verify return to LAN, bounded burst, process restart, and unpair during traffic.
- [ ] Run host verification, full native gates, TypeScript/Jest/lint/Expo Doctor, Go race/vet, E2E verifiers, and generated-clean/diff checks.
- [ ] Perform the complete anti-slop UI review in light/dark and 2x font, fix every defect, then obtain a final independent whole-range review.
- [ ] Record honestly any no-uplink packet/DNS proof that still requires an external network topology.

## Completion evidence

Completion requires production-source proof that `SyncService` starts `TransportCoordinator` with a validated LAN route and optional relay route, plus physical evidence from two phones showing the installed app itself delivers directly and falls back correctly. Loopback-only instrumentation is insufficient.
