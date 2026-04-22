# Phase 4 — Pair Automation + App Filter Enforcement + Housekeeping

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Close the remaining UX rough edges from Phase 3 so the app feels production-ready to a single user on two phones. Automate the manual sig copy-paste during pairing, make the app filter actually enforce per-app overrides at the sender, push peer-aware state (display name, unpair signal) end-to-end, and instrument the home-screen metrics so the "—" placeholders turn into real numbers. Scope-cut: **no LAN transport this phase** (moved to Phase 5 alongside FCM) — direct socket + NSD + SPKI pinning is a sizable unit on its own and benefits from landing together with FCM's wake path. Phase 4 stays relay-only but polishes everything else.

**Scope-shift from original MEMORY §6 plan:** LAN dropped to Phase 5; the rest stays. Rationale: the remaining items all share the same surfaces (relay WS, pairing handshake, NotifPostBuilder, home UI), whereas LAN lives in its own `LanTransport` file with its own threat model. Bundling LAN with FCM gives both pair-aware wake paths a consistent place to land.

**Architecture additions:** Relay gets a second `/pair/notify` subscription leg (Device B side) so confirmation_sig automates both ways. Android gets `AppFilterStore` (DataStore) consumed by `NotifPostBuilder`. Pair handshake extended with `display_name`. New packets: `unpair`, `peer.hello`. Home UI gains a real metrics store.

**Tech-stack additions:** nothing new. Reuse existing deps. OkHttp ws, DataStore preferences, Expo Router.

**Spec reference:** `docs/superpowers/specs/2026-04-20-phone-sync-design.md` — §4.7 pairing (peer hello extension), §4.7.3 privacy filter (user-override enforcement), §7 error handling (unpair flow).

**Out of scope (Phase 4):** LAN transport (Phase 5 with FCM), FCM itself (Phase 5), reply bridge (Phase 6), icon cache + hash-elide (Phase 7), desktop Tauri (Phase 8+), lazy FGS (Phase 9), MessagingStyle reconstruction (Phase 18). App allowlist is deferred — Phase 4 ships the denylist-override direction only (user can block apps the default list missed; "unblock what the default blocked" stays compiled-in for now).

---

## Task breakdown

### Task 1 — Relay: bidirectional `/pair/notify` (push B→relay→A AND A→relay→B)

**Why:** Phase 3's `/pair/notify` is unidirectional — relay pushes `pair.sig` to whoever subscribed under `pair_token`. But Phase 3 UX has Device A displaying the sig as base64 for Device B to type in. Automation requires B to receive the sig over relay too.

**Current flow (Phase 3):** A subscribes `/pair/notify?token=X`. B POSTs `/pair/complete` with pubkeys + sig (that B got by typing from A's screen). Relay validates + pushes `pair.sig` to A (redundant — A already has its own sig).

**New flow (Phase 4):** Split the handshake into three relay endpoints.

1. A: `POST /pair/init` (unchanged) → displays QR.
2. A: opens `GET /pair/notify?token=X&role=A` → waits for `peer.hello` frame carrying B's pubkeys.
3. B: scans QR → `POST /pair/hello` with B's pubkeys + `pair_token` (new endpoint — just relays to A via pair hub).
4. Relay forwards B's `peer.hello {B_enc, B_sign, B_device_id, B_display_name}` to A's subscription.
5. A: receives B's pubkeys → computes sig via `deviceASignConfirmation` → `POST /pair/complete` (existing, unchanged) which now also validates and stores the pair.
6. B: opens `GET /pair/notify?token=X&role=B` → waits for `pair.sig` frame.
7. Relay pushes `pair.sig` to B.
8. B calls `deviceBCompletePairing(relayUrl, pairToken, sig)` locally — BUT since `/pair/complete` already ran on A's side, B's version becomes `deviceBFinalizePair` that just verifies the sig locally + calls `storePeerPubkeys`. OR: move `/pair/complete` to be B-initiated (as it is now), and A's side pushes the sig over the hub.

**Simpler model that matches existing code:** keep `/pair/complete` as the B-initiated confirm path. Replace the "B types sig from A's screen" step with:
- A computes sig locally → `POST /pair/send_sig {pair_token, sig}` (new endpoint, idempotent; only A can send since relay verifies A's sig against the pending record).
- B subscribes `/pair/notify?token=X&role=B`, waits for the sig, then POSTs `/pair/complete`.

**Final concrete design:**

- New endpoint `POST /pair/hello` — B sends its pubkeys + device_id + display_name. Relay stores against the pending record and pushes `peer.hello` frame to A's subscription.
- New endpoint `POST /pair/send_sig` — A sends the computed sig. Relay stores against the pending record and pushes `pair.sig` frame to B's subscription.
- `PairHub.Subscribe` keyed by `(pair_token, role)` instead of just `pair_token` — two concurrent subscribers per token (A and B). Identity check on Unsubscribe stays.

Relay changes:

- Modify `relay/internal/server/pair_hub.go`:
  - Change map key from `string` (token) to `struct { Token, Role string }`.
  - `Subscribe(token, role)` / `Unsubscribe(token, role, ch)` / `Push(token, role, frame)`.
- Modify `relay/internal/server/pair_notify.go`:
  - Parse `role` query param (must be `"A"` or `"B"`). 400 if missing or not in set.
  - Subscribe using the composite key.
- New handlers in a new file `relay/internal/server/pair_handshake.go`:
  - `handlePairHello` — decode `{pair_token, device_id, enc_pubkey, sign_pubkey, display_name}`, look up pending pair (404 if missing/expired), update `PendingPair` with B's fields (extend struct), push `peer.hello` frame to A's subscription.
  - `handlePairSendSig` — decode `{pair_token, confirmation_sig}`, look up pending pair (with B's pubkeys now populated), verify sig server-side (so B can trust it before completing), push `pair.sig` frame to B's subscription.
- Modify `relay/internal/store/pair_store.go`:
  - Extend `PendingPair` struct: `BEncPubkey`, `BSignPubkey`, `BDeviceID`, `BDisplayName`, `ADisplayName`.
  - Add helper to update pending in place (merge B's fields).
- Modify `relay/internal/server/pair.go`:
  - `handlePairInit` accepts optional `display_name` field (from A).
  - `handlePairComplete` unchanged in signature; uses stored B-side fields for the 5-field sig verify (already does this).
- Modify `relay/internal/server/server.go`:
  - Register `POST /pair/hello` + `POST /pair/send_sig` routes (NO auth middleware — pair_token gates them).
- Schema: new `proto/pair-hello.schema.json` for the frame format delivered over `/pair/notify`. Existing `pair-sig.schema.json` reused.

**TDD (Go):**

- Extend `pair_notify_test.go` with bidirectional scenarios:
  - A subscribes with role=A → B POSTs `/pair/hello` → A receives `peer.hello` frame within 100 ms.
  - A POSTs `/pair/send_sig` → B (subscribed with role=B) receives `pair.sig` frame within 100 ms.
  - Missing/invalid `role` query param → 400.
  - `/pair/send_sig` with a bad sig → 400 at the relay level (server verifies before pushing).
- Add test for the identity-guarded double-subscribe race per role.

**Commit:** `feat(relay): bidirectional /pair/notify with role-aware hub + /pair/hello + /pair/send_sig`

---

### Task 2 — Mobile: automate the pairing flow end-to-end

**Files:**

- Modify `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/PairNotifyClient.kt`:
  - Change `awaitSig(relayBaseUrl, pairToken)` to `awaitFrame(relayBaseUrl, pairToken, role, expectedType)` — generic frame waiter typed by inner `type`.
  - Two call-sites:
    - Device A waits for `peer.hello`.
    - Device B waits for `pair.sig`.
- Modify `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/PairProtocol.kt`:
  - Add `sendPeerHello(relayUrl, pairToken, deviceId, encPk, signPk, displayName)` — POST /pair/hello.
  - Add `sendConfirmationSig(relayUrl, pairToken, sig)` — POST /pair/send_sig.
- Modify `TwinotifyCoreModule.kt`:
  - Add AsyncFunctions:
    - `sendPeerHello(relayUrl, pairToken, displayName)` — B sends its pubkeys to A via relay.
    - `awaitPeerHello(relayUrl, pairToken)` — A waits for B's pubkeys; resolves to JSON string with B's pubkeys + deviceId + displayName.
    - `sendConfirmationSig(relayUrl, pairToken, sigB64)` — A pushes sig for B.
  - Keep `awaitPairSig` (rename internal impl to use the new generic awaitFrame with `expectedType = "pair.sig"` and `role = "B"`).
- Modify `mobile/app/pair/qr.tsx` (Device A):
  - After `startPairInitiator`, call `awaitPeerHello` instead of `awaitPairSig`.
  - On resolve, parse B's pubkeys + displayName.
  - Route to fingerprint screen passing B's data.
- Modify `mobile/app/pair/scan.tsx` (Device B):
  - After scanning QR, call `sendPeerHello(relayUrl, pairToken, displayName)` — `displayName` comes from Android `Settings.Global.DEVICE_NAME` via a new native helper (`getDeviceDisplayName`).
  - Route to fingerprint screen.
- Modify `mobile/app/pair/fingerprint.tsx`:
  - Role A path: tap "They match" → compute sig via existing `deviceASignConfirmation` → call new `sendConfirmationSig` → navigate to success. **No more copy-paste UI.**
  - Role B path: tap "They match" → call `awaitPairSig(relayUrl, pairToken)` (now `role="B"`) → on resolve, call `deviceBCompletePairing(relayUrl, pairToken, sig)` + `storePeerPubkeys` → navigate to success.
  - Remove the manual paste TextInput + the "Phase 3 interim" copy.
- Modify `mobile/app/pair/success.tsx`:
  - Unchanged in contract. Still marks onboarding complete + starts sync service.

**New native helper:**

- Modify `TwinotifyCoreModule.kt`:
  - `AsyncFunction("getDeviceDisplayName") { ... Settings.Global.getString(ctx.contentResolver, "device_name") ?: Build.MODEL ... }`.

**Pair payload change:** the QR JSON's schema stays the same (A's pubkeys + pair_token + relayUrl + A's displayName optional). B doesn't need A's pubkeys via `peer.hello` because they're in the QR — just uses QR data.

**TDD:** mobile side — instrumented tests deferred per MEMORY §8. JVM-side pure additions are minimal; no new unit tests needed for Task 2 (pure refactor + wiring).

**Commit:** `feat(mobile/pairing): automate bidirectional sig exchange via /pair/notify`

---

### Task 3 — App filter enforcement in NotifPostBuilder

**Files:**

- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/filter/AppFilterStore.kt`:
  ```kotlin
  package co.twinotify.core.filter

  import android.content.Context
  import androidx.datastore.preferences.core.stringSetPreferencesKey
  import androidx.datastore.preferences.core.edit

  /**
   * User-controlled per-package deny overrides. Augments the compiled-in default denylist.
   * Packages in this store are suppressed by NotifPostBuilder regardless of the default list.
   */
  object AppFilterStore {
      private val KEY_DENY = stringSetPreferencesKey("twinotify_user_denylist")

      @Volatile private var cached: Set<String>? = null

      suspend fun load(ctx: Context): Set<String> = cached ?: run {
          val ds = dataStore(ctx)
          val set = ds.data.first()[KEY_DENY] ?: emptySet()
          cached = set
          set
      }

      suspend fun add(ctx: Context, pkg: String) {
          dataStore(ctx).edit { it[KEY_DENY] = (it[KEY_DENY] ?: emptySet()) + pkg }
          cached = null
      }

      suspend fun remove(ctx: Context, pkg: String) {
          dataStore(ctx).edit { it[KEY_DENY] = (it[KEY_DENY] ?: emptySet()) - pkg }
          cached = null
      }

      // DataStore instance helper here — mirrors the pattern in CryptoStore.kt.
  }
  ```
- Modify `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/NotifPostBuilder.kt`:
  - `build(...)` signature takes an additional `userDenylist: Set<String>` param.
  - Hard-drop if `pkg in userDenylist` alongside existing filters.
- Modify `TwinotifyNotificationListener.kt`:
  - Load both default denylist AND user denylist in `onCreate`.
  - Pass both unioned to `NotifPostBuilder.build`.
  - Subscribe to AppFilterStore changes so toggles take effect without service restart — OR just invalidate cache on update (simpler; `cached = null` in the store triggers a fresh load on next notification).
- Modify `TwinotifyCoreModule.kt`:
  - New AsyncFunctions: `getUserDenylist()`, `addToDenylist(pkg)`, `removeFromDenylist(pkg)`.
- Modify `mobile/app/filter.tsx`:
  - Remove the "Phase 3 cosmetic only" banner.
  - Toggle calls `addToDenylist` / `removeFromDenylist` via the bridge.
  - Hardcoded `TW_APPS` list stays (Phase 4 does not add `getInstalledApps` — deferred).

**TDD:** JVM test for `NotifPostBuilder` requires MockK + StatusBarNotification stubs — instrumented. Add a simple unit test for the union logic inline:

```kotlin
// Test: build() receives a denylist { "com.evil" }, ignores it for unrelated package but drops for "com.evil"
```

Add to the existing `androidTest` dir if needed; otherwise document as manual smoke.

**Commit:** `feat(mobile/filter): enforce user-controlled per-app denylist in NotifPostBuilder`

---

### Task 4 — Unpair handshake (peer-aware)

**Why:** Phase 3's `unpair()` only rotates local keys. The peer device keeps trying to reconnect and shows "Offline" indefinitely. Phase 4 sends an `unpair` packet so the peer clears its state too.

**Proto:** new packet type `unpair` — inner ciphertext shape `{v, type:"unpair", canon_id:null, reason}`. Add to `packet.schema.json` enum. No separate schema file needed (opaque inside ciphertext envelope).

**Android changes:**

- Modify `TwinotifyCoreModule.kt` `unpair`:
  - Before clearing local state: build + encrypt `{v:1, type:"unpair"}` → enqueue via `QueuingOutboundSink.enqueueUnpair` OR write a one-off outbound entry. Fire-and-forget; don't block unpair on send success.
  - Add a small `wait-for-flush` (bounded to 3 s) so the queue drains before we wipe keys (otherwise the outbound event gets decrypted against the new keys). Use `SyncServiceStatus.queuedCount` flow, wait until it hits zero OR timeout.
  - Then proceed with the Phase 2 unpair path (clear PeerStore, rotate keys, NonceSource.regenerate, ReplayGuard.clear).
- Modify `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`:
  - Dispatch `"unpair"` inner type → call a new handler `handleUnpair()`:
    - Stop `SyncService`.
    - Wipe `PeerStore`, rotate keys via `CryptoStore.rotate`, `NonceSource.regenerate`, `ReplayGuard.clear`, clear `NotificationMapDao` + `OutboundQueue` + `AppFilterStore`.
    - Emit an event to JS so the UI can route back to onboarding.
- Modify `TwinotifyCoreModule.kt`:
  - Add `Events("onPeerUnpair")`.
  - `InboundDispatcher.handleUnpair` calls `SyncServiceStatus.notifyUnpair()` → module forwards to JS.

**JS side:**

- Add to `mobile/hooks/useTwinotifyCore.ts`:
  - Listen for `onPeerUnpair` event → navigate to `/onboarding/role` and clear `OnboardingState` persistence.
- Modify the app root (`mobile/app/_layout.tsx` or create `mobile/hooks/usePeerUnpairListener.ts`):
  - Global listener that runs whenever app is mounted; on `onPeerUnpair` event: `OnboardingState.reset()` + `router.replace('/onboarding/role')`.

**TDD:** Go tests unchanged. Kotlin-side instrumented-only.

**Commit:** `feat(mobile/pairing): unpair packet + peer-side handler + JS router`

---

### Task 5 — Peer display name end-to-end

**Why:** Phase 3 shows truncated UUID on settings + status cards. Phase 4 threads a human-readable name through the pair handshake.

**Spec:** new field `display_name` in the pair handshake (sent both by A during `/pair/init` and by B during `/pair/hello`). Stored in `PendingPair` and `ConfirmedPair`. Peer reads it on receive.

**Changes:**

- Modify `relay/internal/store/pair_store.go`:
  - Add `ADisplayName`, `BDisplayName` to `PendingPair` AND `ConfirmedPair`.
- Modify `handlePairInit`: accept optional `display_name`. Store.
- Modify `handlePairHello` (Task 1): accept `display_name`. Store.
- Modify the `peer.hello` + `pair.sig` frames to include display_name — actually `peer.hello` already carries B's fields; include display_name. `pair.sig` just needs the sig.
- Modify `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/PeerStore.kt`:
  - Extend `PeerRecord` with `displayName: String?`.
  - Persist to DataStore.
- Modify `PairProtocol.initiate` to accept `displayName` and pass to `/pair/init`.
- Modify `TwinotifyCoreModule.kt`:
  - `startPairInitiator(relayUrl)` → calls `getDeviceDisplayName()` internally; passes to PairProtocol.
  - `storePeerPubkeys(encB64, signB64, peerDeviceId, peerDisplayName?)` — extra optional param.
  - `getPairStatus()` returns the peer displayName too.
- Modify mobile UI:
  - `home.tsx` shows `peer.displayName ?? peer.deviceId.slice(0, 8)`.
  - `settings/pair.tsx` shows displayName prominently + UUID below as subtitle.
  - `fingerprint.tsx` labels "Compare with `Alice's Pixel 9`" etc.
- Modify `types/twinotify.d.ts`: extend `PairStatus` with `peerDisplayName?: string`.

**TDD:** relay side — extend existing pair tests to verify the round-trip of display_name.

**Commit:** `feat: peer display name threaded end-to-end (handshake + store + UI)`

---

### Task 6 — Home metrics instrumentation

**Why:** Phase 3's Home shows `—` for Today/Latency/Blocked. Wire real counters.

**Files:**

- Create `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/metrics/MetricsStore.kt`:
  - DataStore-backed counters: `mirroredToday`, `blockedToday`, `latencyRollingAvgMs` (over last 10 samples).
  - Daily reset: on first read each UTC day, zero the counters. Use `last_reset_epoch_day` int pref.
  - `incrementMirrored()`, `incrementBlocked()`, `recordLatency(ms)`.
- Wire increments:
  - `QueuingOutboundSink.enqueuePost` — after encrypting + enqueueing: `MetricsStore.incrementMirrored(ctx)`.
  - `NotifPostBuilder` — when a notification is dropped by any filter: call `MetricsStore.incrementBlocked(ctx)`. Add the call as the second argument or just invoke from the caller (the NLS) when build returns null.
  - Latency: stamp `ts` on sent event; peer ack returns latency; Phase 4 sends an `ack` packet with the `canon_id` + `delta_ms`. On receiver: compute `now - envelope.ts` as rough RTT (not true RTT but close enough). Actually simpler: measure latency as `inbound_received_ts - envelope.ts` on the receiver and stash in metrics local to that device (each phone tracks "how fresh are the mirrors I receive"). That's what the home card should show.
  - So: `InboundDispatcher.handlePost` — before calling MirrorPoster, compute `now - post.ts` and call `MetricsStore.recordLatency(ctx, delta)`.
- `TwinotifyCoreModule.kt`:
  - New AsyncFunction `getMetrics()` returning `{ mirroredToday, blockedToday, latencyMs }`.
- `mobile/hooks/useTwinotifyCore.ts` + types:
  - Expose `getMetrics` + a `useMetrics()` hook that polls every 5 s (simpler than event-based; metrics don't need instant updates).
- `mobile/app/home.tsx`:
  - Replace `—` with real values from `useMetrics()`.

**TDD:** JVM unit test for MetricsStore via a FakeDataStore fixture (match the NotificationMapDao pattern). Counter add/reset/rolling-avg correctness.

**Commit:** `feat(mobile/metrics): mirrored/blocked counters + latency rolling avg for home card`

---

### Task 7 — Housekeeping sweep from Phase 3 final review

Small cleanups flagged as post-merge improvements:

1. **`relay/internal/server/ws.go`** — update stale `TODO(phase-2)` comment on `CheckOrigin`. JWT now gates `/ws`; `/pair/notify` + `/pair/hello` + `/pair/send_sig` are intentionally unauthenticated + pair_token-gated. Replace comment with a clearer rationale block.
2. **`ReasonCodeFilterTest.kt`** — add explicit reason-code 5 (REASON_LISTENER_CANCEL) test case mapping to `NoEmit`. Currently falls through `else` branch implicitly.
3. **`SyncService.kt`** — clean up double-assignment of `currentWs` (lines 141-159 per final review): remove the post-`newWebSocket` assignment; rely on `onOpen` callback.
4. **`MEMORY.md`** — refresh "Known gaps" after these fixes so future sessions don't re-flag them.
5. **`.github/workflows/`** — add a CI step that recomputes `SHA-256(mobile/modules/twinotify-core/android/src/main/assets/default-denylist.json)` and diffs against `EXPECTED_SHA256_HEX` in `DenylistLoader.kt`. Fail the build on drift.
6. **`NonceSource.kt`** — add a KDoc at the top of `regenerate()` documenting that `PeerStore.clear()` + `NonceSource.regenerate()` + `CryptoStore.rotate()` must happen atomically in the unpair path (nearest thing to an atomic action is the sequence inside `TwinotifyCoreModule.unpair`).
7. **`validator.go`** — enable `jsonschema.WithFormatAssert` on UUID so malformed msg_id gets rejected hard (currently advisory). Update validator construction with the option.

**Commit:** `chore: Phase 3 post-merge cleanup + CI denylist integrity gate`

---

## Verification (Phase 4 merge gate)

- [ ] `cd relay && go test ./... -count=1 -race` green.
- [ ] `cd mobile && npx tsc --noEmit` green.
- [ ] `cd mobile && npx expo-doctor` green.
- [ ] JVM unit tests green in `mobile/modules/twinotify-core/android/src/test/`.
- [ ] Manual smoke on 2 phones (append scenarios to `docs/test-scenarios.md` Phase 4 section):
  - Fresh pair with automated sig exchange (no typing).
  - Peer display name appears correctly on both sides.
  - Blocking an app via filter → subsequent notifications from that app don't mirror.
  - Unpair on A → B's UI routes to onboarding within a second.
  - Home card shows non-zero mirrored count + blocked count + latency after a few notifications.
- [ ] CI denylist integrity gate catches a tampered commit.

---

## Critical files / invariants

Same as Phase 3 (MEMORY §11) — all load-bearing rules still apply. Specifically:

- **Room schema bump:** if you need a new entity for metrics counters, prefer DataStore preferences (simpler, no migration burden). The plan above uses DataStore.
- **PairHub composite key** change requires every existing test to move to the new API. Walk through `pair_notify_test.go` carefully — identity check must survive.
- **Schema `$id` byte-for-byte match** — if you add `pair-hello.schema.json`, register it in the validator and verify via the existing walk-and-register loop.

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| PairHub key change breaks existing `awaitPairSig` path | Med | High | Keep the old single-key API as a thin compat wrapper during Task 1; remove only after Task 2 migrates callers |
| Unpair packet arrives during active mirror flood → decrypt failure | Low | Med | Drain queue before wipe (3 s bounded wait) + InboundDispatcher ignores decrypt failures gracefully |
| Metrics counters drift if device clock rolls back | Low | Low | Daily-reset guard uses `epochDay`; clock rollback merely delays a reset |
| App filter cache invalidation races with NLS onNotificationPosted | Med | Low | NLS reads `AppFilterStore.load()` each notification (fast DataStore path); cache invalidation sets null — next load fetches fresh |
| CI denylist gate blocks intentional denylist edits | Low | Low | Document: changing the JSON REQUIRES updating the Kotlin const in the same commit |
| `jsonschema.WithFormatAssert` rejects previously accepted envelopes | Low | Med | Run validator tests with real captured packets before flipping |

---

## Commit sequence

```
Task 1: feat(relay): bidirectional /pair/notify with role-aware hub + /pair/hello + /pair/send_sig
Task 2: feat(mobile/pairing): automate bidirectional sig exchange via /pair/notify
Task 3: feat(mobile/filter): enforce user-controlled per-app denylist in NotifPostBuilder
Task 4: feat(mobile/pairing): unpair packet + peer-side handler + JS router
Task 5: feat: peer display name threaded end-to-end (handshake + store + UI)
Task 6: feat(mobile/metrics): mirrored/blocked counters + latency rolling avg for home card
Task 7: chore: Phase 3 post-merge cleanup + CI denylist integrity gate
```

Branch: `phase-4-pair-auto-and-enforcement`. Merge to main after all verifications + 2-device smoke.
