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

- [x] Validated-binding, fallback, Wi-Fi lease, advertisement, listener, role, signing-key, and cleanup RED coverage landed (commit `967d119`).
- [x] The production LAN route factory now uses stored peer trust, sealed bindings, the selected Wi-Fi network, pinned mutual TLS, private discovery, and the existing dispatcher/outbox (commit `967d119`).
- [x] The relay adapter preserves capabilities, inbound ordering, snapshot hooks, and route ownership (commit `967d119`).
- [x] Focused JVM, Android-test compilation, lint, and independent review gates completed (commit `967d119`).

### Task 2: Replace the relay-only service loop with one coordinator

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`, `SyncServiceStatus.kt`
- Test: `ServiceLifecycleTest.kt`, `TransportCoordinatorTest.kt`, new focused integration tests as needed

- [x] Live coordinator startup, relay fallback, LAN-only operation, truthful status, and single-drain RED coverage landed (commit `949b284`).
- [x] Retry and route preference now use the live coordinator while preserving migration, materialization, retention, call, snapshot, and unpair ordering (commit `949b284`).
- [x] Stop, unpair, and destroy join coordinator and route resources exactly once (commit `949b284`).
- [x] Focused repeated tests, full JVM, Android-test compilation, lint, and independent review completed (commit `949b284`).

### Task 3: Prove product behavior and close UX/evidence gaps

**Files:**
- Modify only test/evidence/UI files required by findings from real execution.

- [x] Production-backed, content-free direct update, peer-dismiss, call, snapshot, receipt, burst, and unpair controls and observations landed (commit `ac7a5f3`).
- [x] The aggregate `lan-product-correctness` host gate and fail-closed evidence verifier landed (commit `9c136cc`).
- [x] Host verification, Go race/vet, E2E verifier, and Make safety gates passed for the aggregate tooling (commit `9c136cc`).
- [ ] Install the current debug app on both connected handsets without clearing app data - pending physical two-phone run.
- [ ] Verify direct post/update/cancel, calls, snapshots, and receipts in both directions - pending physical two-phone run.
- [ ] Verify LAN loss, relay fallback, return to LAN, restart, burst, and unpair during traffic - pending physical two-phone run.
- [ ] Perform light/dark, 2x-font, and final whole-range UI inspection on both handsets - pending physical two-phone run.
- [ ] Capture controlled no-uplink packet and DNS observations - pending physical two-phone run.

## Completion evidence

Production integration and host automation are complete through commit `9c136cc`: `SyncService` starts `TransportCoordinator` with a validated LAN route and an optional relay route. Product acceptance remains pending physical two-phone run for every unchecked item above. Loopback instrumentation and host fixtures are not physical delivery or fallback evidence.
