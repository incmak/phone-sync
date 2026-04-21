# Phone-Sync — Design Spec

**Date:** 2026-04-20
**Status:** Draft for review
**Scope:** Personal-use MVP, architected to allow later publication.

---

## 1. Goal

Mirror Android notifications between two personal phones (Android 14+, API 34–35) such that:

- A notification posted on Phone A appears on Phone B within ~1 second on LAN, ~1–2 seconds off-LAN.
- Updates (same package + id + tag) replace the mirrored copy in place.
- User dismissal on either phone dismisses on the other.
- Inline reply typed on Phone B is sent by Phone A's originating app (RemoteInput passthrough).
- Notification content is end-to-end encrypted; the relay server sees only ciphertext.

Non-goals (v1): SMS bridging, clipboard sync, file transfer, media-session control, desktop client, third-party pairing (only the user's own two devices).

**Phase 1 MVP vs Phase 2 polish:**

- **Phase 1 MVP display**: title + text + small icon + large icon. Mirror renders as basic `NotificationCompat.Builder` notification. `big_text`, `sub_text`, `template`, `messages[]` are captured in the packet schema but NOT reconstructed on the receiver.
- **Phase 2**: MessagingStyle reconstruction (chat threading), BigTextStyle, BigPictureStyle rendering on receiver.

---

## 2. High-Level Architecture

```text
 Phone A                              Phone B
 ┌────────────────────────┐          ┌────────────────────────┐
 │ NotificationListener   │          │ NotificationListener   │
 │   Service (origin)     │          │   Service (origin)     │
 │                        │          │                        │
 │ SyncService (FGS)      │◀───LAN──▶│ SyncService (FGS)      │
 │  ├─ LanTransport       │  mDNS +  │  ├─ LanTransport       │
 │  ├─ RelayTransport     │  TLS WS  │  ├─ RelayTransport     │
 │  ├─ FcmReceiver        │          │  ├─ FcmReceiver        │
 │  └─ Crypto (libsodium) │          │  └─ Crypto (libsodium) │
 └────────┬───────────────┘          └───────────────▲────────┘
          │                                          │
          │   FCM high-priority ping (wake peer)     │
          └───────────────┐             ┌────────────┘
                          ▼             ▼
                    ┌──────────────────────┐
                    │     Relay (Go)       │
                    │  WebSocket + BoltDB  │
                    │  + FCM Admin SDK     │
                    │  (Docker, laptop→VPS)│
                    └──────────────────────┘
```

**Two transports, one protocol:**

- **LAN transport** — Android NSD discovery, TLS socket, direct peer connection. Used when both devices are on same network and discovery succeeds.
- **Relay transport** — persistent TLS WebSocket to home/cloud relay. Always available as fallback. Relay triggers FCM high-priority data message to wake a Dozed recipient.

The app selects LAN when available, falls back to relay. Both sides publish liveness over relay; LAN is opportunistic acceleration.

---

## 2.1 Stack & Client Decisions

**Three clients, one protocol:**

| Target | Stack | Role |
|--------|-------|------|
| **Mobile (Android 14+)** | Expo RN app + custom Expo Native Module (Kotlin) | Both origin AND receiver of notifications |
| **Mobile iOS** | Expo stub, no-op v1 | iOS cannot read arbitrary notifications without entitlement — out of scope |
| **Desktop** (macOS/Win/Linux) | Tauri (Rust core + React UI) | **Receiver only** in v1 (mirrors phone notifications to desktop). Origin role deferred to v2 (would require desktop's own notification capture, OS-specific). |
| **Relay** | Go 1.22 + gorilla/websocket + bbolt + chi + santhosh-tekuri/jsonschema **v6** | Docker container |

### 2.1.1 Why Expo + Native Module (not pure-Kotlin)

NotificationListenerService is a system-bound service — it must be implemented in Kotlin. No RN or Expo module exists that exposes the full NLS API (read, cancel, snooze, reason codes). The service also must stay alive in the OS binding regardless of whether a JS engine is running. Therefore:

- **Core** (NLS, SyncService foreground service, LanTransport, RelayTransport, FcmReceiver, Crypto, ReplyBridge) — implemented as a **custom Expo Native Module** in Kotlin, packaged under `mobile/modules/phone-sync-core/`.
- **Process-lifecycle reality check:** the Kotlin module lives in the **same process** as the RN JS engine. When the OEM battery manager kills the app, both die together. What survives is the *system's intent to re-bind the NLS* — when a new notification arrives, Android restarts the app process and re-binds the listener. Crucially, all in-memory state (`mirroredFromPeer`, `pendingPeerCancel`, outbound queue tail) is lost on process death.
- **Persistence requirement:** `mirroredFromPeer`, `localIdToCanonId`, and outbound-queue entries are persisted to Room on write. `pendingPeerCancel` is in-memory only. Eviction of `mirroredFromPeer`/`localIdToCanonId` on `notif.cancel` / local dismiss / 7-day TTL sweep (see §4.1). Reconnection on restart rehydrates transport state from Room.
- **UI shell** (pairing QR scan, settings, app allowlist/denylist, reliability onboarding, connection state, history) — standard Expo Router + React Native. Talks to the native module via its exported interface.
- **iOS** — module stubbed (returns feature-unavailable). All RN UI works on iOS but the core-sync is no-op. Documented as Android-only for v1.

Tradeoff accepted: every NLS-side change requires rebuilding the custom native module (EAS Build / prebuild). Expo Go is not usable — the project uses EAS Build dev clients.

### 2.1.2 Why Tauri for Desktop (not Electron, not Expo Web)

- **Expo does not target desktop natively.** Expo Web produces a website, not a tray-aware native app with OS notification access. Rejected.
- **Electron** works but ships Chromium (~150MB), high idle RAM, battery-hostile on laptops. Rejected for a background-running client.
- **Tauri**: Rust core, system webview for UI, single ~5–10MB binary, tray-icon support, native OS notifications via `tauri-plugin-notification` and `notify-rust`. Rust side handles WebSocket, crypto (libsodium via `dryoc` crate), and OS-notification bridging. React/TS UI shared in structure (but not code) with the mobile app.

### 2.1.3 Shared Protocol, Duplicated Implementations

The `/proto` directory holds the JSON Schema for all packet types. Each client parses it in its native language (Kotlin for Android, Rust for desktop, Go for relay). No shared source code — duplication is the cost of not forcing a single-language choice, accepted because the protocol surface is small and stable.

---

## 3. Packet Protocol

Inspired by KDE Connect. Packets are JSON, encrypted as opaque ciphertext over the wire. Within the E2EE envelope:

```json
{
  "v": 1,
  "type": "notif.post" | "notif.update" | "notif.cancel" | "notif.reply" | "reply.failed" | "icon.request" | "icon.reply" | "ping" | "pong" | "ack",
  "msg_id": "uuid",
  "origin_device": "device-id",
  "ts": 1713600000000,
  "payload": { ... }
}
```

### 3.0 Icon Transfer Optimization

Icons are hashed (SHA-256 of PNG bytes) and cached by hash on both ends.

- **Receiver cache**: keyed by hash, LRU-evicted at 50MB, persisted across restarts.
- **Sender cache (REQUIRED)**: keyed by hash, same LRU policy. **The sender must retain the PNG bytes by hash** so it can serve `icon.request(hash)` NACKs from the receiver. Without this, a post-eviction NACK gets an empty response and the mirror renders without an icon. Sender cache size: 50MB.
- On `notif.update` or `notif.post` for an app whose icon hash already exists on the peer, sender omits `*_icon_png` and sends only `*_icon_hash`. Receiver NACKs via `icon.request(hash)` on cache miss; sender re-transmits. Small icons capped at 96×96, large icons at 256×256 before PNG encode.

### 3.1 `notif.post` / `notif.update`

```json
{
  "canon_id": "devA:com.whatsapp:42:tag",
  "app_name": "WhatsApp",
  "channel_id": "messages_chat",
  "channel_name": "Messages",
  "channel_importance": 3,
  "visibility": "public",
  "is_group_summary": false,
  "group_key": null,
  "package": "com.whatsapp",
  "id": 42,
  "tag": "conversation-xyz",
  "title": "Alice",
  "text": "See you at 7",
  "big_text": "See you at 7 — the new place on Main St",
  "sub_text": null,
  "template": "MessagingStyle",
  "messages": [
    { "sender": "Alice", "text": "See you at 7", "ts": 1713599990000 }
  ],
  "is_clearable": true,
  "is_ongoing": false,
  "small_icon_png": "<base64 rendered bitmap, omitted on update if hash unchanged>",
  "small_icon_hash": "<sha256 hex, always present>",
  "large_icon_png": "<base64 bitmap or null, omitted on update if hash unchanged>",
  "large_icon_hash": "<sha256 hex, null if no icon>",
  "actions": [
    {
      "title": "Reply",
      "action_id": "opaque-action-handle",
      "remote_input_key": "android.intent.extra.REPLY",
      "is_reply": true
    }
  ]
}
```

`canon_id = origin_device + ":" + package + ":" + id + ":" + (tag or "")` — stable cross-device identity, origin-scoped. Including `origin_device` in v1 means the schema supports multi-pair in v2 without a migration; in v1 it's always the paired peer's device_id.

**Known limitation — id=0 collisions.** Some apps reuse `id=0` with differing tags, and some use neither distinguishing field (e.g., ongoing foreground-service notifications). For identical `(origin_device, package, id, tag)` from the same origin, last-write-wins — a new `notif.post` supersedes any prior mirror. Distinct apps cannot collide because `package` is included. Logged as a known-limitation in README; reconsidered in v2 if real-world collisions appear.

### 3.2 `notif.cancel`

```json
{ "canon_id": "devA:com.whatsapp:42:tag", "reason": "user_swipe" }
```

### 3.2.1 `icon.request`

Receiver → sender NACK when a `notif.post`/`notif.update` arrived with an `*_icon_hash` but no `*_icon_png` body and the receiver's local cache does not have the bytes for that hash.

```json
{ "hash": "<sha256 hex>" }
```

Sender responds with a dedicated `icon.reply` packet carrying `{ hash, png_b64 }` (not a full `notif.post` — that would re-trigger the listener's post-event on the receiver). Receiver caches the bytes by hash and re-renders the pending notification whose `*_icon_hash` matches. `icon.reply` is added to the envelope `type` enum alongside `icon.request`.

### 3.2.2 `reply.failed`

Origin → receiver when a `notif.reply` arrived but the `(canon_id, action_id)` map entry is missing (origin's map was cleared by process death, or action_id is stale after a notification update).

```json
{
  "canon_id": "devA:com.whatsapp:42:tag",
  "action_id": "uuid",
  "reason": "stale_action_id" | "notification_gone" | "map_lost"
}
```

Receiver surfaces a toast: "Reply couldn't be sent — open the conversation on the origin device."

### 3.3 `notif.reply`

```json
{
  "canon_id": "devA:com.whatsapp:42:tag",
  "action_id": "opaque-action-handle",
  "remote_input_key": "android.intent.extra.REPLY",
  "text": "On my way"
}
```

---

## 4. Components (Android)

### 4.1 `PhoneSyncNotificationListener : NotificationListenerService`

**Critical self-loop prevention:** Phone B's NLS observes EVERY notification on the device, including mirrors Phone B itself posted via `NotificationManager.notify()`. Without filters, the NLS would fire `onNotificationPosted` for the mirror and emit `notif.post` back to Phone A — infinite loop. Two mechanisms prevent this:

1. **Self-package filter** — `onNotificationPosted` and `onNotificationRemoved` both early-return when `sbn.packageName == context.packageName` (i.e., the mirror was posted by Phone-Sync itself).
2. **Local↔origin ID mapping** — when Phone B posts a mirror, the mirror gets a Phone-Sync-chosen local `(id, tag)` under `com.phonesync`. Phone B maintains `localIdToCanonId: Map<(localId, localTag), canonId>` keyed by Phone-Sync's local identifiers, populated at post time. This map enables round-trip lookups despite the self-package filter (below).

**Callbacks:**

- `onNotificationPosted(sbn)`:
  - If `sbn.packageName == ownPackage` → return (do not emit; it's our own mirror).
  - Else → convert to `notif.post`/`notif.update`, hand to `OutboundQueue`.
- `onNotificationRemoved(sbn, rankingMap, reason)`:
  - If `sbn.packageName == ownPackage`: the removal is on a mirror we posted. **First check `pendingPeerCancel`** — if the mirror's `canon_id` (looked up via `localIdToCanonId`) is in the set, this is the echo of a cancel WE invoked in response to the origin's cancel — suppress + consume tombstone + delete map entries. If NOT in `pendingPeerCancel`, the user dismissed locally: apply reason-code filter, emit `notif.cancel` to origin (tracked in `mirroredFromPeer[canon_id]`), delete map entries. If `localIdToCanonId` lookup misses (process death lost the map before persistence flushed), skip — origin's state drifts one notification, recovered at next update.
  - Else (`sbn.packageName != ownPackage`): a real notification on this device. Apply the combined reason-code filter below.
  - `canon_id ∈ pendingPeerCancel` → **suppress** and clear the entry. This set contains `canon_id`s we just called `cancelNotification` on in response to a peer's `notif.cancel`. Only suppresses the exact cancel we triggered; unrelated reason-10 events from other apps are NOT suppressed.
  - Else, reason == 1 (REASON_CLICK) → emit `notif.cancel` with `reason=user_click`.
  - Else, reason ∈ {2, 3} (REASON_CANCEL, REASON_CANCEL_ALL) → emit `notif.cancel` with `reason=user_swipe`.
  - Else, reason ∈ {8, 9} (REASON_APP_CANCEL, REASON_APP_CANCEL_ALL) → emit `notif.cancel` with `reason=app_cancel`.
  - Else (4, 6, 12, 13, 14, and any unhandled reason-10 from other listeners) → do not emit.
- Maintains three maps:
  - `mirroredFromPeer: Map<canonId, originDeviceId>` (persisted to Room) — notifications this device posted *because of* a peer event. Needed to route cancel-on-mirror-dismiss back to the originating peer.
  - `localIdToCanonId: Map<(localId, localTag), canonId>` (persisted to Room) — reverse lookup from Phone-Sync's own chosen local id/tag (under `com.phonesync`) back to the origin's `canon_id`. Populated when posting a mirror. Required because `onNotificationRemoved` for a self-mirror yields only the local id/tag, not the origin canon_id. Without this map, the lookup into `mirroredFromPeer` would fail.
  - `pendingPeerCancel: Set<canonId>` (in-memory, tombstones with 30s TTL) — canon_ids on which we just invoked `cancelNotification` after receiving a peer's cancel. Suppresses the single resulting reason-10 echo. Losing this on process death is tolerable: at worst, one duplicate cancel echoes back, which the peer's own `pendingPeerCancel` or `mirroredFromPeer`-absence check absorbs.
- **Eviction rules:**
  - `mirroredFromPeer[canon_id]` and `localIdToCanonId[(localId, localTag)]` are removed when: (a) peer sends `notif.cancel` for that `canon_id`, (b) the local user dismisses the mirror, (c) 7-day TTL sweep (bounds unbounded growth if origin never sends cancel and user never dismisses).

### 4.2 `SyncService : Service` (foreground, type `remoteMessaging`)

- Owns transport lifecycle. Runs in one of two modes (user-selectable, see §6.1):
  - **Lazy FGS (default)** — FGS promoted while actively transmitting; demoted to a regular bound service + socket released after 5 min idle. Woken by FCM high-priority pings.
  - **Always Connected** — FGS always promoted, WebSocket held open. Opt-in for users who prioritize latency over battery.
- State machine: `LAN_CONNECTED` → `RELAY_CONNECTED` → `OFFLINE_QUEUED` → `IDLE_DEMOTED` (lazy mode only).
- Persistent notification (while FGS promoted): "Phone-Sync active — connected via LAN/Relay".

**AndroidManifest.xml entries (required, API 34+):**

```xml
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<!-- LAN discovery (NsdManager) -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
<!-- Android 16+ will require this for NsdManager + local sockets -->
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />

<service
    android:name=".SyncService"
    android:foregroundServiceType="remoteMessaging"
    android:exported="false" />
```

`POST_NOTIFICATIONS` is runtime-requested on first launch (API 33+). Without grant the receiver cannot post mirrors. FGS without matching type declaration throws `SecurityException` on API 34+.

### 4.2.1 Notification Channels (receiver)

Android 8+ silently drops notifications posted without a registered channel.

- **Phase 1:** single channel `mirrored_notifications` with importance **`IMPORTANCE_DEFAULT`** (NOT `HIGH`). Rationale: `IMPORTANCE_HIGH` produces heads-up popups and bypasses DND on many OEMs — surprising behavior that overrides the user's quiet hours. `DEFAULT` still shows in tray with sound (if user has enabled sound for the channel). Users who want heads-up can elevate the channel in system settings. All mirrors go here.
- **Phase 2:** mirror the origin's channel — the packet carries `channel_id`, `channel_name`, `channel_importance` (see §3.1). Receiver constructs the local channel ID as **`"mirror__" + origin_device + "__" + channel_id`** (double-underscore delimiter prevents collision with origin channel IDs containing `:`). Channel name displayed as `"[origin_device] channel_name"` in system settings. Creates with mirrored importance. Preserves per-channel user muting.

### 4.3 `LanTransport`

- `NsdManager.registerService(_phonesync._tcp)` advertises self. TXT record includes a **daily-rotated advertising ID** (HKDF(pair_secret, "ad-id", utc_epoch_day)) rather than the raw `device_id` — prevents passive LAN observers from correlating the same device across days.
- **Clock-skew tolerance:** each peer advertises THREE ad-ids simultaneously — yesterday's, today's, and tomorrow's (derived from `utc_epoch_day - 1`, `utc_epoch_day`, `utc_epoch_day + 1`). Peer selection accepts any of the three. Prevents silent DoS when device clocks straddle UTC midnight or drift by hours.
- **Peer selection:** on discovery, only services whose advertising ID matches one of the expected ±1-day derivations are accepted. All others ignored. Multi-device expansion (v2) will iterate the paired set and try all derivations.
- Open TLS socket → frame length-prefixed JSON. **TLS peer cert must be pinned to the pairing pubkeys:** each device's TLS self-signed cert embeds its `enc_pubkey` in the SubjectPublicKeyInfo, and the peer verifies the presented cert's SPKI matches the stored peer `enc_pubkey` from the pair record. Without pinning, a LAN attacker with a self-signed cert could complete TLS, forward E2EE ciphertext transparently, and gain traffic-analysis capability. With pinning, the TLS identity is tied to the same trust anchor as the E2EE layer.
- Drops to idle if peer unreachable for 30s. Does NOT drive retries — relay is the source of truth for reachability.

### 4.4 `RelayTransport`

- OkHttp WebSocket, URL from paired config.
- Auth: JWT signed with device's Ed25519 signing key (separate keypair from encryption key), presented on connect. **Relay verifies the JWT signature against the `sign_pubkey` stored in the pair record at pairing time — not against any arbitrary Ed25519 key.** The pair record is the trust anchor.
- **JWT replay protection:** each JWT carries a fresh `jti` (UUID v4) and short `exp` (60s). Relay maintains a `seenJti` cache (in-memory, bounded to `exp` window, evicted on expiry). Duplicate `jti` within window → 401. Prevents captured-JWT replay (e.g., from relay logs after a breach or hostile network snapshot).
- Exponential backoff reconnect (1s → 60s cap), keepalive ping every 30s.
- Outbound queue with disk persistence (Room) for offline bursts.

### 4.5 `FcmReceiver : FirebaseMessagingService`

- Received payload: `{"event_id":"..."}`. No notification content.
- On receipt: **check `RemoteMessage.getPriority()` — if `PRIORITY_NORMAL` (FCM silently downgraded the message due to abuse heuristics), skip FGS promotion and fall back to lazy reconnect**. Only `PRIORITY_HIGH` grants the `startForeground()` background-start exemption. The "~10s" figure commonly cited refers to the separate FGS-notification-hidden rule; the exemption itself has no formally documented duration but is reliably sufficient for a single reconnect.
- If priority is HIGH: wake `SyncService`, promote FGS, force reconnect, fetch event by ID.
- If priority is NORMAL: queue the event_id locally; next time the app is foregrounded or next FGS-exempt event arrives, fetch.

### 4.6 `Crypto`

Android Keystore cannot directly hold X25519 keys usable by libsodium (Keystore exposes asymmetric keys only through `Cipher`/`KeyAgreement` APIs and does not support XSalsa20-Poly1305). Pattern used: **Keystore-wrapped libsodium keys.**

- **Master key** — AES-256-GCM key, generated in Android Keystore with `setUserAuthenticationRequired(false)`, hardware-backed (StrongBox when available). This key never leaves Keystore.
- **libsodium X25519 keypair** — generated with `crypto_box_keypair()`. The raw secret key bytes are encrypted with the master key (AES-GCM, Keystore-sealed) and persisted via **Jetpack DataStore with androidx.datastore:datastore-tink** (see "At-rest storage" note below). Public key stored in plain DataStore (unencrypted).
- **Ed25519 signing keypair** (for JWT relay auth) — generated with `crypto_sign_keypair()`; same wrapping pattern.
- **Runtime access** — when encrypting/decrypting a packet, app unseals the wrapped secret key via Keystore → passes raw bytes to libsodium `crypto_box_easy` → zeros the raw bytes in memory immediately after. Keys live as raw bytes only for the duration of a single operation.
- Encryption: `crypto_box_easy` (X25519 + XSalsa20-Poly1305, authenticated). Sender encrypts with own secret + peer's public; recipient decrypts and verifies sender authenticity. `crypto_box_seal` is rejected because it is anonymous and provides no sender authentication.
- **Nonce: hybrid monotonic counter + random prefix.** 24-byte nonce = 16 random bytes (generated once per session on app start) || 8-byte big-endian counter. Counter is persisted atomically to Room (`nonce_counter` table, single-row) and incremented BEFORE every encrypt; write is fsync'd before ciphertext leaves the device. Rationale: pure-random 24-byte nonces have negligible collision probability in a single CSPRNG lifetime, but **a device restored from backup can replay CSPRNG state** and produce colliding nonces — catastrophic for XSalsa20-Poly1305 (leaks plaintext XOR, breaks MAC). The counter guarantees uniqueness even after state restoration; the random prefix prevents cross-session counter collision if Room is wiped. On Room corruption / first-launch detection: regenerate the random prefix AND reset counter to 0.
- **Keystore wraps, it does not hold the libsodium key itself. "Stored in Keystore" elsewhere in this doc means "wrapped by a Keystore-protected master key."**
- **At-rest storage:** wrapped secret keys persisted via **Jetpack DataStore with androidx.datastore:datastore-tink** (AEAD), NOT `EncryptedSharedPreferences` (the entire `androidx.security:security-crypto` library is deprecated as of v1.1.0 with no further releases planned).
- **Forward secrecy posture (v1):** long-term static X25519 keypairs. A passive adversary who later compromises either device's wrapped secret key can decrypt historical captured ciphertext. Documented limitation. Signal-style Double Ratchet deferred to v2. The nonce hybrid above protects against backup-restore nonce reuse but does NOT provide forward secrecy.

### 4.6.1 Replay Protection

- Every inbound packet's `msg_id` (UUID v4) is checked against a local `seenMessageIds` table before decryption-ack is granted.
- Duplicate `msg_id` → silently drop.
- TTL on the dedup table: 48h (2× relay offline queue TTL). Evicted on schedule.
- **Staleness check uses `relay_ts` (relay-stamped) not `ts` (origin clock).** Origin clocks may be wrong; packets older than 48h by relay stamp are rejected. LAN direct packets carry no relay stamp → only `msg_id` dedup applies, which is sufficient for replay defense on LAN.
- **Trust model for `relay_ts`:** the stamp is applied by the relay **outside** the E2EE envelope and is NOT authenticated. A malicious or compromised relay can forge it to "un-stale" old packets. This is acceptable because **`msg_id` dedup is the primary replay defense** (applied regardless of timestamp); `relay_ts` is a secondary advisory check. A relay forging `relay_ts` gains nothing an attacker who can already replay packets wouldn't gain — and replays are caught by dedup.

### 4.7 `Pairing` (two-sided confirmation)

- Device A: generate keypairs → register `{pair_token, A_enc_pubkey, A_sign_pubkey}` with relay via `POST /pair/init` → display QR containing `{relay_url, A_device_id, A_enc_pubkey, A_sign_pubkey, pair_token}`.
- Device B: scan QR → verify QR fingerprint SHA-256(`A_enc_pubkey || A_sign_pubkey`) matches what Device B will display → `POST /pair/complete` with `{pair_token, B_device_id, B_enc_pubkey, B_sign_pubkey}`.
- **Relay holds the pair in `pending` state; does NOT finalize yet.** Relay pushes B's pubkeys to Device A (via the open WebSocket Device A established during `/pair/init`).
- **Device A displays SHA-256(`B_enc_pubkey || B_sign_pubkey`).** User compares with what Device B is showing for A's keys. Both users tap confirm on both devices.
- Both confirmations (signed with respective signing keys) sent back to relay → relay marks pair `trusted` → `pair_token` invalidated.
- **Why two-sided:** prevents MITM at `/pair/complete` where an attacker with the QR (shoulder-surfed, screen-recorded) could race Device B to submit attacker's pubkeys. With one-sided confirmation, user on Device A would see legit fingerprint (from legit Device B) while the *relay* is bound to attacker's keys if the attacker races first. Two-sided: both devices must cryptographically confirm they agree on each other's pubkeys before binding.
- `pair_token` is cryptographically bound to the pubkeys it accompanies via Device A's signature (`sig_A(pair_token || B_enc_pubkey || B_sign_pubkey)`) at confirmation time, so the relay rejects any pubkey substitution even if the token leaks.

### 4.7.1 Mirror Tap Behavior (Content Intent)

When user taps a mirrored notification on Phone B:

- **Phase 1:** tap dismisses locally + sends `notif.cancel` to origin (which dismisses on Phone A). No app launch on either side. The mirror's `contentIntent` points to a local `MirrorTapReceiver` BroadcastReceiver that triggers this. `FLAG_AUTO_CANCEL` is set so the tap auto-dismisses before our receiver fires.
- **Phase 2 (optional):** "Open on origin" bridge — receiver sends a `notif.open` event, origin fires the captured `contentIntent.send()`. Not in v1.

Reply actions and non-reply action buttons on the mirror are handled separately (see §4.8); they do NOT dismiss the mirror.

### 4.7.2 Group Summary Handling

Android's `Notification.Builder.setGroup(key)` + `setGroupSummary(true)` creates parent/child bundles. Naive mirroring produces ugly output.

- **Phase 1 policy:** **drop `isGroupSummary=true` packets on the receiver.** Only render the children. Summaries are typically "3 new messages" aggregates the system generates; dropping them avoids clickable-summary-to-nowhere. Children carry enough context.
- **Phase 2:** reconstruct `groupKey` so multiple mirrored children of the same origin group bundle together on the receiver. Requires remapping origin `groupKey` into a receiver-local group key namespaced by origin device.

### 4.7.3 Privacy Filtering

Sensitive notifications (banking OTPs, password resets, 2FA codes) should not auto-mirror.

- **Phase 1:** user-configurable **app allowlist/denylist** UI. Default denylist is **hard-coded in the APK** (`mobile/assets/default-denylist.json`). Contents: common OTP-sensitive apps — bank apps, `com.google.android.apps.authenticator2`, `com.authy.authy`, `com.microsoft.azure.authenticator`, `com.cisco.duo`, `org.telegram.messenger`, password managers. Shown to user on first run.
- **Denylist integrity check:** at app startup, compute SHA-256 of the loaded denylist JSON and compare against a hard-coded hash constant compiled into the Kotlin source. On mismatch (repackaged APK removed the denylist), abort app start with a visible error — prevents a tampered build silently mirroring OTP apps.
- **Phase 1 exclusions — never mirrored:**
  - `Notification.VISIBILITY_SECRET` notifications.
  - Notifications with `category == CATEGORY_CAR_EMERGENCY | CATEGORY_CAR_INFORMATION | CATEGORY_CAR_WARNING` (Android Auto side-channel).
  - Notifications whose package is the current Android Auto projection app (`com.google.android.projection.gearhead`).
  - `FLAG_NO_CLEAR` notifications are mirrored but marked non-dismissable on Phone B.
- **Receiver-side visibility default:** mirrored notifications posted with `VISIBILITY_PRIVATE` (hides sensitive text on Phone B's lock screen by default). User can elevate per-channel in Phase 2.
- **Phase 2:** per-channel opt-out (e.g., mirror WhatsApp messages but not WhatsApp calls).

### 4.8 `ReplyBridge`

- **`action_id` generation (origin side):** when building the outbound `notif.post`, iterate `notification.actions[]` and assign each a UUID v4 as `action_id`. Store `(canon_id, action_id) → (PendingIntent, RemoteInput)` in an in-memory map keyed off the service lifecycle.
- **Invalidation:** on every `notif.post` (including updates to the same `canon_id`), old `action_id`s for that `canon_id` are discarded and new ones minted. On `notif.cancel` for that `canon_id`, all its `action_id` entries are dropped. A stale `action_id` from the peer → reply drops with a `reply.failed` event sent back (Phone B shows a toast).
- **Reply execution (origin side):** look up `(canon_id, action_id)` → build `Intent`, fill via `RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)`, fire `PendingIntent.send(context, 0, intent)`.
- **Peer side:** when mirroring, the Phone B notification is posted with reconstructed `Action` objects whose `PendingIntent` targets a local `ReplyReceiver` BroadcastReceiver. The receiver captures the typed text and emits `notif.reply` back to origin with the stored `action_id`.
- Map entry lifetime: tied to source notification; TTL safety net of 1h for orphans.
- **Persistence gap:** the `(canon_id, action_id) → (PendingIntent, RemoteInput)` map is in-memory only (PendingIntents are process-local handles and cannot be persisted). On OEM kill + NLS re-bind, the map is lost. A reply attempt for a notification that existed before the kill emits `reply.failed` (see §3.2.2). Documented limitation; recovery = user taps the notification on origin to restore its PendingIntent in a fresh app process.

---

### 4.9 Desktop Client (Tauri)

Runs as a tray/menubar app on macOS, Windows, Linux. Receiver-only in v1.

**Components (Rust side, `src-tauri/`):**

- `crypto::{box_easy_seal, box_easy_open}` — `dryoc` wrapper, wrapped keys stored in OS keychain (macOS Keychain, Windows Credential Manager, libsecret on Linux) via `keyring` crate. Same Keystore-wrap pattern as Android.
- `relay_ws` — `tokio-tungstenite` WebSocket client. Auto-reconnect with backoff. JWT auth via Ed25519.
- `lan_discovery` — `mdns-sd` crate, daily-rotated ad-id, same derivation as Android.
- `notifier` — native OS notifications per platform:
  - **macOS** — `UNUserNotificationCenter` via `tauri-plugin-notification`. Click + dismiss callbacks reliable.
  - **Linux** — `org.freedesktop.Notifications` via `tauri-plugin-notification` or `notify-rust`. `ActionInvoked` and `NotificationClosed` signals reliable; `reason` field distinguishes user-close from app-close.
  - **Windows** — `tauri-plugin-notification` wraps UWP toasts but **does not reliably surface per-notification dismiss events back to Rust**. For v1 we bypass the plugin on Windows and call `winrt::Windows::UI::Notifications::ToastNotification` directly, subscribing to the `Dismissed` event handler per notification. This adds a thin Rust shim (`src-tauri/src/windows_toast.rs`). Click callbacks work via the plugin; dismiss callbacks route through the shim. Without this, Windows dismiss-sync silently fails.
  - All platforms: on user action (click/dismiss), invoke local handler that applies the `pendingPeerCancel` tag check, then emits `notif.cancel` back to origin if user-initiated.
- `reply_capture` — platform status:
  - **macOS** — `UNNotificationAction` with `UNTextInputNotificationAction` supports inline reply cleanly via `UNUserNotificationCenterDelegate.didReceive`. Working.
  - **Windows** — Toast inline reply via `ToastContentBuilder` + `InputType.Text` is available in the UWP API, **but `tauri-plugin-notification` does not currently expose reply text back to Rust** (as of April 2026). Options: (a) bypass the Tauri plugin and call `winrt::Windows::UI::Notifications` directly from a small Rust/C++ shim, or (b) treat Windows like Linux and show a fallback Tauri mini-window with a text box. **v1 ships with the fallback window on Windows.** Native inline reply on Windows is Phase 2.
  - **Linux** — `libnotify` has no inline reply; use a Tauri fallback window.
- `icon_cache` — LRU on-disk cache, same hash protocol.
- `pair_client` — QR code **scanner** (camera via `nokhwa` crate) OR manual entry (paste QR payload as text for headless/servers).

**UI (React + TS, `src/`):**

- Pairing screen (scan QR from phone or paste payload).
- Settings: relay URL, auto-launch on boot, show-in-dock toggle, notification style preferences.
- History: last N mirrored notifications with filter/search.
- Connection status (LAN / Relay / Offline).

**Dismissal sync on desktop:**

- User clicks the OS notification → OS dismisses it → our `NotificationHandler` callback fires → emit `notif.cancel(reason=user_click)` to origin.
- User clicks "Clear all" in OS notification center → each cleared notification fires its dismiss callback individually (all platforms). Emit one cancel per.
- Origin sends `notif.cancel` → Rust calls platform-specific close (macOS `UNUserNotificationCenter.removeDeliveredNotifications`, Windows `ToastNotifier.Hide`, Linux `org.freedesktop.Notifications.CloseNotification`).

**Desktop loop avoidance — no OS reason codes, so we use explicit tagging:**

Unlike Android's `REASON_LISTENER_CANCEL`, desktop OSes fire the dismiss callback identically for user- and programmatic-closes (e.g., macOS `userNotificationCenter(_:didDismissNotification:)` has no reason parameter). We use the same `pendingPeerCancel` tombstone approach but distinguish by **caller-side flagging** rather than OS-provided reason codes:

- Maintain `pendingPeerCancel: Set<canonId>` with 30s TTL.
- On receiving peer `notif.cancel`: insert `canon_id` into the set → invoke platform close API → when the dismiss callback fires, check membership → if present, consume the entry and suppress the echo; if absent, it's user-initiated, emit cancel to peer.
- Race window: a real user dismissal between "insert-into-set" and "OS close callback" would be mis-suppressed. Mitigation: insert-into-set happens immediately before the OS call (microseconds); tombstone self-clears on first hit; 30s TTL bounds stalled-callback cases.

**LAN discovery on desktop:** desktop advertises as well (same `_phonesync._tcp`). Phone ↔ desktop direct WebSocket over TLS when on same LAN.

**What's NOT in v1 desktop:**

- Originating desktop notifications (mirror desktop → phone). Deferred — requires per-OS notification capture (macOS Notification Center API is private/gated, Windows has UserNotificationListener API with UAP limitations, Linux varies by DE). Potentially v2.
- iOS desktop (i.e., Catalyst) support.

---

## 5. Components (Relay, Go)

### 5.1 Responsibilities

- Authenticate WebSocket connections via Ed25519-signed JWT. **JWT signature verified against the `sign_pubkey` stored in the pair record** (not just any valid Ed25519 signature — the relay looks up the sender's `device_id`, fetches their stored `sign_pubkey`, verifies the JWT signs to it). Prevents a relay operator or attacker holding a valid Ed25519 key from impersonating a paired device.
- Route messages between paired device pairs only. No cross-pair leakage.
- Persist undelivered messages to BoltDB (24h TTL). On peer reconnect, flush queue.
- On receive while peer offline: trigger FCM high-priority `{event_id}` ping via Firebase Admin SDK (coalesced to ≤1 ping per 10s per receiver to respect FCM budget).
- Host pairing handshake endpoint (`/pair` — stores pubkeys + pair_token, expires in 5 min).
- Stamp inbound messages with `relay_ts` (server wall-clock) for staleness checks on receiver.

### 5.1.1 Rate Limiting

- Per-device WebSocket: 60 messages/sec sustained, 200 burst. Excess → WebSocket close code **1008 (Policy Violation)** with reason `rate-limit-exceeded`. (HTTP 429 is not valid after WebSocket upgrade.)
- Per-IP pairing: 5 `/pair/init` per hour. Prevents pair-token enumeration.
- Per-device pair lifetime cap: 10 active pairs. Unpair required before adding more.

### 5.1.2 Log Policy (E2EE alignment)

- **Logged:** connection metadata only — device_id, connect/disconnect timestamp, bytes transferred, error class.
- **Never logged:** packet contents, ciphertext, msg_id, FCM payload.
- Retention: 7 days, auto-purged. BoltDB queue contents encrypted at rest (OS-level FDE on VM; not per-record encrypted since payload is already E2EE ciphertext).
- Stated in relay `/about` endpoint and README for user verification.

### 5.2 Surface

- `GET /ws` — upgraded WebSocket, JWT in header.
- `POST /pair/init` — device A registers pair_token + pubkeys.
- `POST /pair/complete` — device B presents pair_token + own pubkeys.
- `GET /health` — liveness.

### 5.3 Deployment

- Single static Go binary.
- Dockerfile (multi-stage, distroless base).
- `docker-compose.yml` with Caddy reverse proxy for automatic TLS (via Let's Encrypt when public-facing, or internal self-signed when LAN-only on laptop).
- BoltDB file mounted via volume.
- Env vars: `FCM_SERVICE_ACCOUNT_JSON`, `LISTEN_ADDR`, `JWT_AUDIENCE`.

---

## 6. Dismissal Sync — Full Flow (Loop Avoidance)

Scenario: WhatsApp notification posted on Phone A.

1. Phone A listener fires `onNotificationPosted(sbn)`. `sbn.packageName != ownPackage` (it's WhatsApp, not Phone-Sync), so emit `notif.post(canon_id=X="devA:com.whatsapp:42:tag", origin=A)`.
2. Phone B receives `notif.post`. Posts local mirror via `NotificationManager.notify(localId=7001, localTag="mirror")`. Records `mirroredFromPeer[X] = A` and `localIdToCanonId[(7001, "mirror")] = X`.
2a. **Phone B's own NLS fires `onNotificationPosted(sbn)` for the mirror** — `sbn.packageName == ownPackage` → **early return**. No outbound emission. Loop prevented.
3. User swipes the mirror on Phone B → `onNotificationRemoved(sbn, reason=2, REASON_CANCEL)`. `sbn.packageName == ownPackage` branch fires: look up `canon_id = localIdToCanonId[(7001, "mirror")] = X`. Emit `notif.cancel(canon_id=X, origin=B, reason=user_swipe)`. Delete entries from both maps. (If user *tapped* instead: `reason=1, REASON_CLICK` → `reason=user_click`.)
4. Phone A receives `notif.cancel` → adds X to `pendingPeerCancel` (30s tombstone) → calls `cancelNotification(keyForX)`.
5. Phone A listener fires `onNotificationRemoved(reason=10, REASON_LISTENER_CANCEL)`. `sbn.packageName != ownPackage` branch: `X ∈ pendingPeerCancel` → **suppressed** + consume tombstone. No rebroadcast.
6. Phone B: its own local swipe already removed the notification in step 3. Done.

Reverse (swipe on A first):

1. User swipes WhatsApp on A → `reason=2` → emit `notif.cancel(reason=user_swipe)`.
2. Phone B receives → adds X to `pendingPeerCancel` → calls `cancelNotification(keyForLocalMirror)`.
3. Phone B listener fires `onNotificationRemoved(reason=10)`. `sbn.packageName == ownPackage` branch: look up canon_id via `localIdToCanonId`; `X ∈ pendingPeerCancel` → **suppressed** + consume tombstone + delete map entries. Done.

Mirror-of-mirror is prevented by the self-package filter in step 2a, not by `mirroredFromPeer` alone.

---

## 6.1 Battery Efficiency (Android)

**Target: <1% battery/day on a Pixel 8 with ~100 notifications/day.** Achieved by minimizing wake events, network activity, and wake locks.

**Transport selection order (cheapest first):**

1. **Relay WebSocket (default)** — piggybacks on Google Play Services' persistent FCM connection for wake-up. Our own WebSocket stays closed when idle; relay triggers FCM ping on peer-offline paths. When active, WebSocket uses ~20–40kbps baseline.
2. **LAN direct (when on home WiFi + peer detected)** — opportunistic acceleration only. Opens socket when NSD resolves peer; closes on 60s idle. No persistent LAN socket.
3. **FCM push** — only for peer-offline wake.

**Wake-lock rules:**

- NotificationListenerService callback: no explicit wake lock needed (system-held binding).
- SyncService: `PARTIAL_WAKE_LOCK` acquired per outbound batch, released immediately (target <200ms per wake).
- WebSocket keepalive: piggyback on OS TCP keepalive (60s) via `socket.setKeepAlive(true)`. No app-layer heartbeat unless idle >5min, then one app-ping to verify.
- FCM receive: `goAsync()` for the 10s OS-allotted window, no extended wake lock.

**Doze mode handling:**

- Foreground service (`remoteMessaging` type) is Doze-exempt but costs ~1% battery/day just by existing.
- Mitigation: **lazy FGS** — start FGS only when transport is actively connecting/transmitting. Stop FGS + release socket after 5 min idle. On next FCM wake, re-start FGS for the duration of the burst. This trades ~500ms of reconnect latency on a cold wake for significant battery savings.
- Alternative if user wants lowest latency over battery: "Always Connected" mode (FGS always running). Surfaced as a user toggle in settings.

**CPU/memory:**

- libsodium operations run on `Dispatchers.Default` within SyncService's coroutine scope (never on `Dispatchers.Main`). Average encrypt/decrypt: <1ms. No dedicated thread pool.
- Icon cache disk I/O on `Dispatchers.IO`. Evictions batched (every 100 writes).
- Room writes debounced (50ms) for burst coalescing.

**Network efficiency:**

- Permessage-deflate on WebSocket (OkHttp default). ~60% compression on JSON notification payloads.
- Icon hash-elide removes the majority of repeated bytes (same-app updates).
- No periodic polling anywhere. All events are push-driven.

**Idle cost per hour (estimated):**

- FGS overhead: ~0.04% battery
- Idle WebSocket (if kept open): ~0.02%
- FCM wake (per notification): ~0.001%
- Target total: <1% per 24h with 100 notifications.

**Measurable criteria in §12:** battery usage over 24h on both devices measured via `dumpsys batterystats`; CI gate on release: <1.5% per 24h at 100 notifs/day (50% safety margin).

**Desktop battery (laptops):**

- Tauri app is tray-only, no UI process unless window open. ~20MB RAM idle, ~0% CPU idle.
- Rust WebSocket uses OS select/epoll; no polling.
- Pause reconnect attempts when laptop is on battery AND screen locked >5min (user toggle).

---

## 6.2 Threat Model — Documented Scope & Limitations

**Out of scope (documented in README):**

- **Rooted / OS-compromised devices.** An attacker with root on either phone can extract the Keystore master key (call Keystore API as app UID), read DataStore contents, dump process memory, or intercept NotificationListenerService callbacks. App-layer cryptography cannot defend against OS compromise. Users mirroring banking OTPs on rooted devices accept this risk.
- **Forward secrecy across key compromise.** Long-term X25519 keypairs mean a later compromise of either device's wrapped secret key decrypts all historical captured ciphertext. Deferred to v2 (Signal Double Ratchet). Mitigation: unpair rotates keys; limited window of exposure going forward.
- **Malicious apps on paired phones with NLS grant.** Any app granted notification-access already sees all notifications — phone-sync doesn't change that attack surface.
- **Android Keystore attestation.** We don't verify StrongBox attestation certificates; assume device's Keystore is trustworthy at install time.

**Known metadata leaks (not content, but patterns):**

- **Relay operator** sees: `device_id`, connect timestamps, byte counts, message rate. Cannot see content (E2EE ciphertext only). Enough to infer activity patterns, online hours, notification density.
- **FCM (Google)** sees: push frequency from relay to each device. Ping payload is opaque event_id (not notification content). Google learns "Device A messaged Device B at time T" metadata.
- Users who treat notification metadata as sensitive should self-host the relay AND accept FCM leakage as an inherent cost of Doze-reliable wake.

---

## 7. Error Handling & Edge Cases

- **OEM battery kill** — In-app "Reliability Setup" screen detects manufacturer (`Build.MANUFACTURER`) and shows per-OEM instructions (disable battery optimization, enable autostart, lock in recent apps). Links to `dontkillmyapp.com`.
- **Listener permission revoked** — `NotificationManager.isNotificationListenerAccessGranted()` polled on SyncService start; if false, show persistent "Permission needed" notification.
- **POST_NOTIFICATIONS denied on receiver** — the mirror-post path is a no-op; UI surfaces a blocker card: "mirrors won't show until notifications are enabled."
- **Relay unreachable** — Outbound queue persists to Room up to 1000 events, drops oldest thereafter. Reconnect on network restore (`ConnectivityManager.NetworkCallback`).
- **Clock skew** — `ts` is origin's clock; not used for ordering or staleness. Ordering is by per-device monotonic sequence number on the relay WebSocket channel. Staleness uses relay-stamped `relay_ts` (see §4.6.1).
- **Icon size** — Bitmaps capped at 96×96 PNG before embedding. Large icon omitted if >8KB after encode.
- **Reply PendingIntent expired** — If origin no longer has the source notification (app cleared it), reply attempt logs warning and sends `reply.failed` back to peer; Phone B shows toast.
- **Unpair flow** — Either device can unpair → sends `unpair` packet, clears peer pubkeys, tells relay to delete pair, **and rotates its own X25519 encryption keypair + Ed25519 signing keypair** before any re-pairing. Rotation prevents identity linkage across consecutive pairings and gives a coarse form of forward secrecy (a compromise of current keys cannot decrypt post-unpair captures).
- **FCM high-priority quota** — Android enforces a per-app high-priority message budget in aggressive Doze / App Standby Bucket **rare**. Bursts of 50+ notifications while peer is Dozed may see delayed wake for later messages. Mitigation: relay coalesces pings (send at most 1 ping per 10s; receiver pulls queued events on wake). Documented limit, not fully avoidable on unrooted devices.
- **LAN-only + Dozed receiver (no FCM path)** — Lazy FGS re-promotion on API 31+ requires a high-priority FCM data message to grant the `startForeground()` background-start exemption; without it, `startForeground()` throws `ForegroundServiceStartNotAllowedException`. A LAN-direct TCP wake carries no such exemption. If the receiver is Dozed AND internet egress is unavailable (home WiFi with no internet, GMS absent / de-Googled ROMs, or firewalled networks), the receiver cannot re-promote FGS and cannot reliably process the inbound mirror. **Mitigations:** (a) document as a known limitation; (b) surface "Always Connected" mode in settings with copy explaining it as the only reliable option on GMS-less / offline-LAN networks; (c) long-term (v2): evaluate a non-GMS push path (UnifiedPush / ntfy) as an alternative wake mechanism.

---

## 8. Testing Strategy

- **Unit** (Kotlin, JUnit5 + MockK): packet serialization, loop-avoidance logic, `crypto_box_easy` roundtrip, reason-code filter truth table.
- **Unit** (Go): relay routing, JWT auth, FCM ping trigger, BoltDB queue flush.
- **Integration**: Android instrumentation test with two emulated `NotificationListenerService` instances talking via an in-process relay.
- **E2E manual**: two physical devices, scripted scenarios in `docs/test-scenarios.md` (WhatsApp post, dismiss, reply, update, ongoing, group cancel, offline→online flush, Doze wake via FCM).
- **Coverage target**: 80% on shared protocol + crypto + loop-avoidance modules.

---

## 9. Build Sequence (Phase Order)

1. **Relay (Go)** — WebSocket + BoltDB + pair endpoints + rate limiting + log policy. Run on laptop via docker-compose. No FCM yet.
2. **Proto schema** — lock `/proto/*.schema.json` for packet types v1. Generate Kotlin/Rust/Go validators.
3. **Mobile scaffold** — `npx create-expo-app`, EAS Build dev client, Expo Native Module skeleton (`phone-sync-core` with Kotlin side). Prove RN UI ↔ native-module bridge with a ping method.
4. **Android core: NLS + SyncService + RelayTransport** — post/update/cancel over relay only. Hardcoded pair config. Verify round-trip. Phase 1 display (title/text/icons, single channel).
5. **Manifest + permissions** — POST_NOTIFICATIONS runtime prompt, FGS type `remoteMessaging`, listener grant UX.
6. **Pairing flow + crypto** — QR-code pair, Keystore-wrapped libsodium `crypto_box_easy`, Ed25519 JWT, msg_id replay protection, fingerprint-both-pubkeys confirmation.
7. **Loop avoidance + dismissal sync** — reason-code filtering, `mirroredFromPeer` + `pendingPeerCancel` maps (30s tombstone), tap behavior (Phase 1: dismiss-only).
8. **App filter UX (allowlist/denylist)** — Expo UI to toggle per-app mirror. Default denylist for bank/OTP apps.
9. **LAN transport** — NSD with daily-rotated ad-id, TLS socket, transport selection.
10. **FCM integration** — Firebase project, relay-side FCM Admin SDK with coalescing, Android `FirebaseMessagingService`.
11. **Reply bridge** — RemoteInput passthrough with action_id lifecycle.
12. **Icon cache + hash-elide optimization** — sender + receiver caches, `icon.request` NACK.
13. **Battery efficiency tuning** — lazy FGS, keepalive piggyback, Room write debouncing, battery measurement via batterystats.
14. **Reliability UX** — OEM onboarding (dontkillmyapp links), permission nags, connection-state UI in Expo shell.
15. **Desktop (Tauri) scaffold** — project setup, shared proto parsing (Rust), tray icon, settings UI.
16. **Desktop receiver** — relay WebSocket client, OS notifications (macOS/Windows/Linux), keychain key storage, dismissal sync.
17. **Desktop LAN discovery + reply capture** — mDNS peer discovery, platform-specific reply UI.
18. **Phase 2 styles** — MessagingStyle, BigTextStyle, BigPictureStyle reconstruction; origin channel mirroring; group-summary reconstruction.
19. **Cloud deploy migration path** — Fly.io target, env-driven config swap, relay auto-update strategy.

Each phase is a standalone working increment, reviewable, mergeable.

---

## 10. Repo Layout

```text
phone-sync/
├── mobile/                 # Expo app (RN) — UI shell, settings, pairing QR, filters
│   ├── app/                # Expo Router screens
│   ├── modules/
│   │   └── phone-sync-core/   # Expo Native Module (Kotlin) — NLS + SyncService + transports
│   │       ├── android/
│   │       └── ios/        # stub (no-op on iOS v1)
│   ├── app.json
│   └── package.json
├── desktop/                # Tauri app (Rust core + React/TS UI)
│   ├── src/                # React UI
│   ├── src-tauri/          # Rust core (libsodium, tokio-tungstenite WS, notify-rust)
│   ├── tauri.conf.json
│   └── package.json
├── relay/                  # Go module
│   ├── cmd/relay/
│   ├── internal/
│   ├── Dockerfile
│   └── go.mod
├── proto/                  # Shared packet schema (JSON Schema) — single source of truth
├── deploy/
│   ├── docker-compose.yml
│   └── caddy/Caddyfile
├── docs/
│   ├── superpowers/specs/
│   ├── test-scenarios.md
│   └── oem-reliability.md
├── .github/workflows/      # CI: Android APK build, Tauri bundle, Go relay test + docker build
└── README.md
```

**CI:** GitHub Actions workflows per target — Android (Gradle build + unit tests + instrumentation), Desktop (Tauri bundle for macOS/Windows/Linux), Relay (Go test + docker build + push to ghcr.io). Matrix build on push to main + PRs.

---

## 11. Open Questions (Deferred)

- Signal-protocol ratchet for forward secrecy — YAGNI for v1; add later if threat model warrants.
- Desktop origin (desktop → phone mirror) — out of scope v1; v2 feature.
- iOS mobile client — blocked by Apple's notification access restrictions; requires entitlement. Out of scope.
- Watch (Wear OS) companion — out of scope v1.
- Multi-pair (3+ devices) — schema supports it via `origin_device` in canon_id; runtime not tested; flag for v2.
- Per-channel allowlist/denylist granularity (beyond per-app) — Phase 2.
- MessagingStyle / BigTextStyle / BigPictureStyle reconstruction on receiver — Phase 2.
- Group summary reconstruction — Phase 2.
- Origin channel mirroring (vs single receiver channel) — Phase 2.
- "Open on origin" bridge for mirror taps — Phase 2.
- Play Store publishing polish (privacy policy, crash reporting, PII audit) — Phase 3.

---

## 12. Success Criteria

- **Latency**: Notification from WhatsApp on Phone A visible on Phone B in <2s median over mobile data, <500ms on LAN, <1s on desktop via relay.
- **Dismissal**: Swipe on any of {Phone A, Phone B, Desktop} dismisses on the others within 2s.
- **Reply**: Type reply on Phone B or Desktop → WhatsApp on Phone A actually sends the message.
- **Doze**: App survives 24h Doze without missing notifications (FCM wake verified via batterystats + adb simulation).
- **Battery**: <1.5% per 24h with ~100 notifications/day on Android (measured). CI gate on release.
- **Relay**: Binary <20MB, runs on Raspberry Pi 4 + old laptop, <100MB RAM steady state.
- **Desktop binary**: <15MB bundle per platform (Tauri), <30MB RAM idle.
- **Loop-free**: no notification re-broadcast detected in 1000-notification stress test.
- **E2EE verified**: relay-side packet dump shows only ciphertext; msg_id dedup + relay_ts staleness rejects replayed packets.
