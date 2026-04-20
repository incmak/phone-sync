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

Icons are hashed (SHA-256 of PNG bytes) and cached by hash on receivers. On `notif.update` or `notif.post` for an app whose icon bytes already exist on the peer, the sender omits `*_icon_png` and sends only `*_icon_hash`. Receiver looks up the hash in its icon cache; if missing (cache eviction), it NACKs via `icon.request(hash)` and sender re-transmits. Small icons capped at 96×96, large icons at 256×256 before PNG encode.

### 3.1 `notif.post` / `notif.update`

```json
{
  "canon_id": "com.whatsapp:42:tag",
  "app_name": "WhatsApp",
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

`canon_id = package + ":" + id + ":" + (tag or "")` — stable cross-device identity.

**Known limitation — id=0 collisions.** Some apps reuse `id=0` with differing tags, and some use neither distinguishing field (e.g., ongoing foreground-service notifications). For identical `(package, id, tag)` from the same origin, last-write-wins — a new `notif.post` supersedes any prior mirror. Distinct apps cannot collide because `package` is included. Logged as a known-limitation in README; reconsidered in v2 if real-world collisions appear.

### 3.2 `notif.cancel`

```json
{ "canon_id": "com.whatsapp:42:tag", "reason": "user_swipe" }
```

### 3.3 `notif.reply`

```json
{
  "canon_id": "com.whatsapp:42:tag",
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
  - Else, reason ∈ {1, 2, 3} (REASON_CLICK, REASON_CANCEL, REASON_CANCEL_ALL) → emit `notif.cancel` with `reason=user_swipe`.
  - Else, reason ∈ {8, 9} (REASON_APP_CANCEL, REASON_APP_CANCEL_ALL) → emit `notif.cancel` with `reason=app_cancel`.
  - Else (4, 6, 12, 13, 14, and any unhandled reason-10 from other listeners) → do not emit.
- Maintains two maps:
  - `mirroredFromPeer: Map<canonId, originDeviceId>` — notifications this device posted *because of* a peer event. Dismissal of such a notification triggers an origin-side cancel back to the originating peer (not a new post-event).
  - `pendingPeerCancel: Set<canonId>` — canon_ids on which we just invoked `cancelNotification` after receiving a peer's cancel. Used to suppress the single resulting reason-10 callback. Entries auto-expire after 5s to prevent leakage if the callback never fires.

### 4.2 `SyncService : Service` (foreground, type `remoteMessaging`)

- Owns transport lifecycle. Always running while the listener is enabled.
- State machine: `LAN_CONNECTED` → `RELAY_CONNECTED` → `OFFLINE_QUEUED`.
- Persistent notification: "Phone-Sync active — connected via LAN".

### 4.3 `LanTransport`

- `NsdManager.registerService(_phonesync._tcp)` advertises self; TXT record includes `device_id` and a short `pair_fingerprint` (first 8 bytes of SHA-256 of peer's encryption pubkey).
- **Peer selection:** on discovery, only services whose `device_id` matches the paired `peer.device_id` AND whose `pair_fingerprint` matches the stored peer pubkey are accepted. All others ignored. Multi-device expansion (v2) will iterate the paired set.
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

- libsodium via Lazysodium-Android.
- Encryption: **`crypto_box_easy`** (X25519 + XSalsa20-Poly1305, authenticated). Sender encrypts with own secret + peer's public; recipient decrypts and verifies sender authenticity cryptographically. `crypto_box_seal` is explicitly rejected because it is anonymous and provides no sender authentication.
- Nonce: 24 random bytes per message, prepended to ciphertext.
- Signing: Ed25519 keypair for JWT relay auth.
- Both keypairs stored in Android Keystore (StrongBox attestation when available). Keys never leave device.

### 4.6.1 Replay Protection

- Every inbound packet's `msg_id` (UUID v4) is checked against a local `seenMessageIds` Room table before decryption-ack is granted.
- Duplicate `msg_id` → silently drop.
- TTL on the dedup table: 48h (2× relay offline queue TTL). Evicted on schedule.
- Inbound packets carrying `ts` older than 48h are also rejected as stale.

### 4.7 `Pairing`

- Device A: generate keypairs → create QR code containing `{relay_url, device_id, enc_pubkey, sign_pubkey, pair_token}` where `pair_token` is a short-lived one-time secret registered with the relay.
- Device B: scan QR → send own pubkeys + `pair_token` to relay → relay binds the pair.
- Both devices show SHA-256 fingerprint of peer's enc_pubkey — user confirms they match. After confirmation, pair is marked trusted; `pair_token` invalidated.

### 4.8 `ReplyBridge`

- **`action_id` generation (origin side):** when building the outbound `notif.post`, iterate `notification.actions[]` and assign each a UUID v4 as `action_id`. Store `(canon_id, action_id) → (PendingIntent, RemoteInput)` in an in-memory map keyed off the service lifecycle.
- **Invalidation:** on every `notif.post` (including updates to the same `canon_id`), old `action_id`s for that `canon_id` are discarded and new ones minted. On `notif.cancel` for that `canon_id`, all its `action_id` entries are dropped. A stale `action_id` from the peer → reply drops with a `reply.failed` event sent back (Phone B shows a toast).
- **Reply execution (origin side):** look up `(canon_id, action_id)` → build `Intent`, fill via `RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)`, fire `PendingIntent.send(context, 0, intent)`.
- **Peer side:** when mirroring, the Phone B notification is posted with reconstructed `Action` objects whose `PendingIntent` targets a local `ReplyReceiver` BroadcastReceiver. The receiver captures the typed text and emits `notif.reply` back to origin with the stored `action_id`.
- Map entry lifetime: tied to source notification; TTL safety net of 1h for orphans.

---

## 5. Components (Relay, Go)

### 5.1 Responsibilities

- Authenticate WebSocket connections via Ed25519-signed JWT.
- Route messages between paired device pairs only. No cross-pair leakage.
- Persist undelivered messages to BoltDB (24h TTL). On peer reconnect, flush queue.
- On receive while peer offline: trigger FCM high-priority `{event_id}` ping via Firebase Admin SDK.
- Host pairing handshake endpoint (`/pair` — stores pubkeys + pair_token, expires in 5 min).

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

## 7. Error Handling & Edge Cases

- **OEM battery kill** — In-app "Reliability Setup" screen detects manufacturer (`Build.MANUFACTURER`) and shows per-OEM instructions (disable battery optimization, enable autostart, lock in recent apps). Links to `dontkillmyapp.com`.
- **Listener permission revoked** — `NotificationManager.isNotificationListenerAccessGranted()` polled on SyncService start; if false, show persistent "Permission needed" notification.
- **Relay unreachable** — Outbound queue persists to Room up to 1000 events, drops oldest thereafter. Reconnect on network restore (`ConnectivityManager.NetworkCallback`).
- **Clock skew** — `ts` is origin's clock; not used for ordering. Ordering is by per-device monotonic sequence number on the relay WebSocket channel.
- **Icon size** — Bitmaps capped at 96×96 PNG before embedding. Large icon omitted if >8KB after encode.
- **Reply PendingIntent expired** — If origin no longer has the source notification (app cleared it), reply attempt logs warning and notifies user on Phone B.
- **Unpair flow** — Either device can unpair → sends `unpair` packet, clears peer pubkeys, tells relay to delete pair.

---

## 8. Testing Strategy

- **Unit** (Kotlin, JUnit5 + MockK): packet serialization, loop-avoidance logic, `crypto_box_easy` roundtrip, reason-code filter truth table.
- **Unit** (Go): relay routing, JWT auth, FCM ping trigger, BoltDB queue flush.
- **Integration**: Android instrumentation test with two emulated `NotificationListenerService` instances talking via an in-process relay.
- **E2E manual**: two physical devices, scripted scenarios in `docs/test-scenarios.md` (WhatsApp post, dismiss, reply, update, ongoing, group cancel, offline→online flush, Doze wake via FCM).
- **Coverage target**: 80% on shared protocol + crypto + loop-avoidance modules.

---

## 9. Build Sequence (Phase Order)

1. **Relay (Go)** — WebSocket + BoltDB + pair endpoints. Run on laptop via docker-compose. No FCM yet.
2. **Android: listener + packet + relay transport** — post/update/cancel over relay only. Hardcoded pair config. Verify round-trip. MVP display (title/text/icons only).
3. **Pairing flow + crypto** — QR-code pair, libsodium `crypto_box_easy` E2EE, Ed25519 JWT auth, msg_id replay protection.
4. **Loop avoidance + dismissal sync** — reason-code filtering, `mirroredFromPeer` + `pendingPeerCancel` maps.
5. **LAN transport** — NSD discovery, TLS socket, transport selection (LAN preferred when peer fingerprint matches).
6. **FCM integration** — Firebase project, relay-side FCM Admin SDK, Android `FirebaseMessagingService`.
7. **Reply bridge** — RemoteInput passthrough with action_id lifecycle.
8. **Icon cache + hash-elide optimization** — receiver-side icon cache, `icon.request` NACK.
9. **Reliability UX** — OEM onboarding (dontkillmyapp links), permission nags, connection-state UI.
10. **Phase 2 styles** — MessagingStyle, BigTextStyle, BigPictureStyle reconstruction on receiver.
11. **Cloud deploy migration path** — Fly.io target, env-var-driven config swap.

Each phase is a standalone working increment, reviewable, mergeable.

---

## 10. Repo Layout

```text
phone-sync/
├── android/            # Android Studio project
│   ├── app/
│   └── build.gradle.kts
├── relay/              # Go module
│   ├── cmd/relay/
│   ├── internal/
│   ├── Dockerfile
│   └── go.mod
├── proto/              # Shared packet schema reference + JSON Schema files
├── deploy/
│   ├── docker-compose.yml
│   └── caddy/Caddyfile
├── docs/
│   ├── superpowers/specs/
│   ├── test-scenarios.md
│   └── oem-reliability.md
└── README.md
```

---

## 11. Open Questions (Deferred)

- Signal-protocol ratchet for forward secrecy — YAGNI for v1; add later if threat model warrants.
- Desktop client — out of scope v1.
- Watch (Wear OS) companion — out of scope v1.
- Multi-pair (3+ devices) — architected but not tested; flag for v2.

---

## 12. Success Criteria

- Notification from WhatsApp on Phone A visible on Phone B in <2s median over mobile data, <500ms on LAN.
- Swipe on either phone dismisses both within 2s.
- Type reply on Phone B → WhatsApp on Phone A actually sends the message.
- App survives 24h Doze without missing notifications (FCM wake verified).
- Relay binary <20MB, runs on Raspberry Pi 4, <100MB RAM steady state.
