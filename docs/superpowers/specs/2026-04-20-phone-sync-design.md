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
| **Relay** | Go + WebSocket + BoltDB | Docker container |

### 2.1.1 Why Expo + Native Module (not pure-Kotlin)

NotificationListenerService is a system-bound service — it must be implemented in Kotlin. No RN or Expo module exists that exposes the full NLS API (read, cancel, snooze, reason codes). The service also must stay alive in the OS binding regardless of whether a JS engine is running. Therefore:

- **Core** (NLS, SyncService foreground service, LanTransport, RelayTransport, FcmReceiver, Crypto, ReplyBridge) — implemented as a **custom Expo Native Module** in Kotlin, packaged under `mobile/modules/phone-sync-core/`. Runs independently of the RN JS runtime; survives app-process death as long as the OS holds the NLS binding.
- **UI shell** (pairing QR scan, settings, app allowlist/denylist, reliability onboarding, connection state, history) — standard Expo Router + React Native. Talks to the native module via its exported interface.
- **iOS** — module stubbed (returns feature-unavailable). All RN UI works on iOS but the core-sync is no-op. Documented as Android-only for v1.

Tradeoff accepted: every NLS-side change requires rebuilding the custom native module (EAS Build / prebuild). Expo Go is not usable — the project uses EAS Build dev clients.

### 2.1.2 Why Tauri for Desktop (not Electron, not Expo Web)

- **Expo does not target desktop natively.** Expo Web produces a website, not a tray-aware native app with OS notification access. Rejected.
- **Electron** works but ships Chromium (~150MB), high idle RAM, battery-hostile on laptops. Rejected for a background-running client.
- **Tauri**: Rust core, system webview for UI, single ~5–10MB binary, tray-icon support, native OS notifications via `tauri-plugin-notification` and `notify-rust`. Rust side handles WebSocket, crypto (libsodium via `sodiumoxide` crate), and OS-notification bridging. React/TS UI shared in structure (but not code) with the mobile app.

### 2.1.3 Shared Protocol, Duplicated Implementations

The `/proto` directory holds the JSON Schema for all packet types. Each client parses it in its native language (Kotlin for Android, Rust for desktop, Go for relay). No shared source code — duplication is the cost of not forcing a single-language choice, accepted because the protocol surface is small and stable.

---

## 3. Packet Protocol

Inspired by KDE Connect. Packets are JSON, encrypted as opaque ciphertext over the wire. Within the E2EE envelope:

```json
{
  "v": 1,
  "type": "notif.post" | "notif.update" | "notif.cancel" | "notif.reply" | "ping" | "pong" | "ack",
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
  "channel_importance": 4,
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
  "priority": "default",
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

- `onNotificationPosted(sbn)` → convert to `notif.post`/`notif.update`, hand to `OutboundQueue`.
- `onNotificationRemoved(sbn, rankingMap, reason)` → combined condition filter:
  - `canon_id ∈ pendingPeerCancel` → **suppress** and clear the entry. This set contains `canon_id`s we just called `cancelNotification` on in response to a peer's `notif.cancel`. Only suppresses the exact cancel we triggered; unrelated reason-10 events from other apps are NOT suppressed.
  - Else, reason == 1 (REASON_CLICK) → emit `notif.cancel` with `reason=user_click`.
  - Else, reason ∈ {2, 3} (REASON_CANCEL, REASON_CANCEL_ALL) → emit `notif.cancel` with `reason=user_swipe`.
  - Else, reason ∈ {8, 9} (REASON_APP_CANCEL, REASON_APP_CANCEL_ALL) → emit `notif.cancel` with `reason=app_cancel`.
  - Else (4, 6, 12, 13, 14, and any unhandled reason-10 from other listeners) → do not emit.
- Maintains two maps:
  - `mirroredFromPeer: Map<canonId, originDeviceId>` — notifications this device posted *because of* a peer event. Dismissal of such a notification triggers an origin-side cancel back to the originating peer (not a new post-event).
  - `pendingPeerCancel: Set<canonId>` — canon_ids on which we just invoked `cancelNotification` after receiving a peer's cancel. Used to suppress the single resulting reason-10 callback. Entries kept as **tombstones with 30s TTL** (was 5s, racy under load) — costs ~nothing memory-wise and prevents spurious echo cancels if the system delays the listener callback.

### 4.2 `SyncService : Service` (foreground, type `remoteMessaging`)

- Owns transport lifecycle. Always running while the listener is enabled.
- State machine: `LAN_CONNECTED` → `RELAY_CONNECTED` → `OFFLINE_QUEUED`.
- Persistent notification: "Phone-Sync active — connected via LAN".

**AndroidManifest.xml entries (required, API 34+):**

```xml
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<service
    android:name=".SyncService"
    android:foregroundServiceType="remoteMessaging"
    android:exported="false" />
```

`POST_NOTIFICATIONS` is runtime-requested on first launch (API 33+). Without grant the receiver cannot post mirrors. FGS without matching type declaration throws `SecurityException` on API 34+.

### 4.2.1 Notification Channels (receiver)

Android 8+ silently drops notifications posted without a registered channel.

- **Phase 1:** single channel `mirrored_notifications` with importance `IMPORTANCE_HIGH`. All mirrors go here. Simple, one-channel UI toggle for the user.
- **Phase 2:** mirror the origin's channel — the packet carries `channel_id`, `channel_name`, `channel_importance` (see §3.1). On receipt, receiver calls `getOrCreateChannel(origin_device + ":" + channel_id)` with mirrored importance. Preserves per-channel user muting.

### 4.3 `LanTransport`

- `NsdManager.registerService(_phonesync._tcp)` advertises self. TXT record includes a **daily-rotated advertising ID** (HKDF(pair_secret, "ad-id", date)) rather than the raw `device_id` — prevents passive LAN observers from correlating the same device across days. Both peers compute the expected ad-id for today from the shared pair secret.
- **Peer selection:** on discovery, only services whose advertising ID matches the expected derivation are accepted. All others ignored. Multi-device expansion (v2) will iterate the paired set and try all derivations.
- Open TLS socket (client cert from pair step) → frame length-prefixed JSON.
- Drops to idle if peer unreachable for 30s. Does NOT drive retries — relay is the source of truth for reachability.

### 4.4 `RelayTransport`

- OkHttp WebSocket, URL from paired config.
- Auth: JWT signed with device's Ed25519 signing key (separate keypair from encryption key), presented on connect.
- Exponential backoff reconnect (1s → 60s cap), keepalive ping every 30s.
- Outbound queue with disk persistence (Room) for offline bursts.

### 4.5 `FcmReceiver : FirebaseMessagingService`

- Received payload: `{"event_id":"..."}`. No notification content.
- On receipt: if WebSocket not connected, wake `SyncService`, force reconnect, fetch event by ID.

### 4.6 `Crypto`

Android Keystore cannot directly hold X25519 keys usable by libsodium (Keystore exposes asymmetric keys only through `Cipher`/`KeyAgreement` APIs and does not support XSalsa20-Poly1305). Pattern used: **Keystore-wrapped libsodium keys.**

- **Master key** — AES-256-GCM key, generated in Android Keystore with `setUserAuthenticationRequired(false)`, hardware-backed (StrongBox when available). This key never leaves Keystore.
- **libsodium X25519 keypair** — generated with `crypto_box_keypair()`. The raw secret key bytes are encrypted with the master key (AES-GCM, Keystore-sealed) and persisted to EncryptedSharedPreferences. Public key stored in plain SharedPreferences.
- **Ed25519 signing keypair** (for JWT relay auth) — generated with `crypto_sign_keypair()`; same wrapping pattern.
- **Runtime access** — when encrypting/decrypting a packet, app unseals the wrapped secret key via Keystore → passes raw bytes to libsodium `crypto_box_easy` → zeros the raw bytes in memory immediately after. Keys live as raw bytes only for the duration of a single operation.
- Encryption: `crypto_box_easy` (X25519 + XSalsa20-Poly1305, authenticated). Sender encrypts with own secret + peer's public; recipient decrypts and verifies sender authenticity. `crypto_box_seal` is rejected because it is anonymous and provides no sender authentication.
- Nonce: 24 random bytes per message, prepended to ciphertext.
- **Keystore wraps, it does not hold the libsodium key itself. "Stored in Keystore" elsewhere in this doc means "wrapped by a Keystore-protected master key."**

### 4.6.1 Replay Protection

- Every inbound packet's `msg_id` (UUID v4) is checked against a local `seenMessageIds` table before decryption-ack is granted.
- Duplicate `msg_id` → silently drop.
- TTL on the dedup table: 48h (2× relay offline queue TTL). Evicted on schedule.
- **Staleness check uses `relay_ts` (relay-stamped) not `ts` (origin clock).** Origin clocks may be wrong; packets older than 48h by relay stamp are rejected. LAN direct packets carry no relay stamp → only `msg_id` dedup applies, which is sufficient for replay defense on LAN.

### 4.7 `Pairing`

- Device A: generate keypairs → create QR code containing `{relay_url, device_id, enc_pubkey, sign_pubkey, pair_token}` where `pair_token` is a short-lived one-time secret registered with the relay.
- Device B: scan QR → send own pubkeys + `pair_token` to relay → relay binds the pair.
- Both devices show SHA-256 fingerprint of `enc_pubkey || sign_pubkey` (concatenated) — user confirms they match. Binding both pubkeys in the fingerprint prevents MITM swap of the signing key for relay impersonation. After confirmation, pair is marked trusted; `pair_token` invalidated.

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

- **Phase 1:** user-configurable **app allowlist/denylist** UI. Default behavior: denylist includes common OTP-y apps (banks, authenticators) — shown to user on first run for confirmation. Per-app toggle. Stored locally (not synced).
- **Phase 1 also:** respect `Notification.VISIBILITY_SECRET` — never mirror. Respect `FLAG_NO_CLEAR` — mirror but mark non-dismissable.
- **Phase 2:** per-channel opt-out (e.g., mirror WhatsApp messages but not WhatsApp calls).

### 4.8 `ReplyBridge`

- **`action_id` generation (origin side):** when building the outbound `notif.post`, iterate `notification.actions[]` and assign each a UUID v4 as `action_id`. Store `(canon_id, action_id) → (PendingIntent, RemoteInput)` in an in-memory map keyed off the service lifecycle.
- **Invalidation:** on every `notif.post` (including updates to the same `canon_id`), old `action_id`s for that `canon_id` are discarded and new ones minted. On `notif.cancel` for that `canon_id`, all its `action_id` entries are dropped. A stale `action_id` from the peer → reply drops with a `reply.failed` event sent back (Phone B shows a toast).
- **Reply execution (origin side):** look up `(canon_id, action_id)` → build `Intent`, fill via `RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)`, fire `PendingIntent.send(context, 0, intent)`.
- **Peer side:** when mirroring, the Phone B notification is posted with reconstructed `Action` objects whose `PendingIntent` targets a local `ReplyReceiver` BroadcastReceiver. The receiver captures the typed text and emits `notif.reply` back to origin with the stored `action_id`.
- Map entry lifetime: tied to source notification; TTL safety net of 1h for orphans.

---

### 4.9 Desktop Client (Tauri)

Runs as a tray/menubar app on macOS, Windows, Linux. Receiver-only in v1.

**Components (Rust side, `src-tauri/`):**

- `crypto::{box_easy_seal, box_easy_open}` — `sodiumoxide` wrapper, wrapped keys stored in OS keychain (macOS Keychain, Windows Credential Manager, libsecret on Linux) via `keyring` crate. Same Keystore-wrap pattern as Android.
- `relay_ws` — `tokio-tungstenite` WebSocket client. Auto-reconnect with backoff. JWT auth via Ed25519.
- `lan_discovery` — `mdns-sd` crate, daily-rotated ad-id, same derivation as Android.
- `notifier` — `tauri-plugin-notification` for native OS notifications; on user action (click/dismiss), invoke local handler that emits `notif.cancel` back to origin.
- `reply_capture` — on platforms supporting inline reply (macOS 10.9+ via NSUserNotification, Windows Action Center): capture typed text, emit `notif.reply`. Linux libnotify has no inline reply — show a small Tauri window with a text box as fallback.
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
- Origin sends `notif.cancel` → Rust calls platform-specific close (macOS `UNUserNotificationCenter.removeDeliveredNotifications`, Windows `ToastNotifier.Hide`, Linux `org.freedesktop.Notifications.CloseNotification`). Same tombstone + loop-avoidance logic as Android.

**LAN discovery on desktop:** desktop advertises as well (same `_phonesync._tcp`). Phone ↔ desktop direct WebSocket over TLS when on same LAN.

**What's NOT in v1 desktop:**

- Originating desktop notifications (mirror desktop → phone). Deferred — requires per-OS notification capture (macOS Notification Center API is private/gated, Windows has UserNotificationListener API with UAP limitations, Linux varies by DE). Potentially v2.
- iOS desktop (i.e., Catalyst) support.

---

## 5. Components (Relay, Go)

### 5.1 Responsibilities

- Authenticate WebSocket connections via Ed25519-signed JWT.
- Route messages between paired device pairs only. No cross-pair leakage.
- Persist undelivered messages to BoltDB (24h TTL). On peer reconnect, flush queue.
- On receive while peer offline: trigger FCM high-priority `{event_id}` ping via Firebase Admin SDK (coalesced to ≤1 ping per 10s per receiver to respect FCM budget).
- Host pairing handshake endpoint (`/pair` — stores pubkeys + pair_token, expires in 5 min).
- Stamp inbound messages with `relay_ts` (server wall-clock) for staleness checks on receiver.

### 5.1.1 Rate Limiting

- Per-device WebSocket: 60 messages/sec sustained, 200 burst. Excess → 429 + disconnect.
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

1. Phone A listener fires `onNotificationPosted` → emits `notif.post(canon_id=X, origin=A)`.
2. Phone B receives → posts local notification, records `mirroredFromPeer[X] = A`.
3. User swipes on Phone B → `onNotificationRemoved(reason=1)`. `mirroredFromPeer[X]` exists → emit `notif.cancel(canon_id=X, origin=B)`.
4. Phone A receives `notif.cancel` → calls `cancelNotification(keyForX)`.
5. Phone A listener fires `onNotificationRemoved(reason=10)` → **suppressed** (listener-cancel, not user-swipe). No rebroadcast.
6. Phone B: its own local swipe already removed the notification in step 3. Done.

Reverse (swipe on A first):

1. User swipes on A → `reason=1` → emit `notif.cancel`.
2. Phone B receives → looks up local `canon_id=X` → calls `cancelNotification`.
3. Phone B listener fires `reason=10` → suppressed. Done.

Mirror-of-mirror never happens because `mirroredFromPeer` tagging makes Phone B's locally-posted mirror not a new post-event.

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

- libsodium operations on main thread of SyncService's coroutine scope. Average encrypt/decrypt: <1ms. No thread pool.
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

## 7. Error Handling & Edge Cases

- **OEM battery kill** — In-app "Reliability Setup" screen detects manufacturer (`Build.MANUFACTURER`) and shows per-OEM instructions (disable battery optimization, enable autostart, lock in recent apps). Links to `dontkillmyapp.com`.
- **Listener permission revoked** — `NotificationManager.isNotificationListenerAccessGranted()` polled on SyncService start; if false, show persistent "Permission needed" notification.
- **POST_NOTIFICATIONS denied on receiver** — the mirror-post path is a no-op; UI surfaces a blocker card: "mirrors won't show until notifications are enabled."
- **Relay unreachable** — Outbound queue persists to Room up to 1000 events, drops oldest thereafter. Reconnect on network restore (`ConnectivityManager.NetworkCallback`).
- **Clock skew** — `ts` is origin's clock; not used for ordering or staleness. Ordering is by per-device monotonic sequence number on the relay WebSocket channel. Staleness uses relay-stamped `relay_ts` (see §4.6.1).
- **Icon size** — Bitmaps capped at 96×96 PNG before embedding. Large icon omitted if >8KB after encode.
- **Reply PendingIntent expired** — If origin no longer has the source notification (app cleared it), reply attempt logs warning and sends `reply.failed` back to peer; Phone B shows toast.
- **Unpair flow** — Either device can unpair → sends `unpair` packet, clears peer pubkeys, tells relay to delete pair.
- **FCM high-priority quota** — Android enforces a per-app high-priority message budget in aggressive Doze / App Standby Bucket **rare**. Bursts of 50+ notifications while peer is Dozed may see delayed wake for later messages. Mitigation: relay coalesces pings (send at most 1 ping per 10s; receiver pulls queued events on wake). Documented limit, not fully avoidable on unrooted devices.

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
