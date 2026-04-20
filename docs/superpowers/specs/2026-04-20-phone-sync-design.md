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

---

## 2. High-Level Architecture

```
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
  "small_icon_png": "<base64 rendered bitmap>",
  "large_icon_png": "<base64 bitmap or null>",
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
- `onNotificationRemoved(sbn, rankingMap, reason)` → filter:
  - reason ∈ {1, 2, 3, 8, 9} → emit `notif.cancel` with `reason=user_swipe` / `app_cancel`.
  - reason ∈ {10, 11} → **suppress** (we just cancelled it locally in response to a peer).
- Maintains `mirroredKeys: Set<String>` — notifications posted locally *because of* a peer event. Dismissing one of these does NOT re-emit as a new post-event; it triggers an origin-side cancel back to the peer that sourced it.

### 4.2 `SyncService : Service` (foreground, type `remoteMessaging`)

- Owns transport lifecycle. Always running while the listener is enabled.
- State machine: `LAN_CONNECTED` → `RELAY_CONNECTED` → `OFFLINE_QUEUED`.
- Persistent notification: "Phone-Sync active — connected via LAN".

### 4.3 `LanTransport`

- `NsdManager.registerService(_phonesync._tcp)` advertises self.
- Discovery resolves peer → open TLS socket (client cert from pair step) → frame JSON lines.
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
- Encryption: X25519 keypair, `crypto_box_seal` — anonymous authenticated encryption to peer's pubkey. Private key generated in Android Keystore with StrongBox attestation when available.
- Signing: Ed25519 keypair for JWT relay auth.
- Keys never leave device.

### 4.7 `Pairing`

- Device A: generate keypairs → create QR code containing `{relay_url, device_id, enc_pubkey, sign_pubkey, pair_token}` where `pair_token` is a short-lived one-time secret registered with the relay.
- Device B: scan QR → send own pubkeys + `pair_token` to relay → relay binds the pair.
- Both devices show SHA-256 fingerprint of peer's enc_pubkey — user confirms they match. After confirmation, pair is marked trusted; `pair_token` invalidated.

### 4.8 `ReplyBridge`

- When peer sends `notif.reply`, look up stored `actionId → PendingIntent` map for that `canon_id`.
- Build `Intent`, fill `RemoteInput` result via `RemoteInput.addResultsToIntent`, fire PendingIntent.
- Map entry TTL = lifetime of the source notification; cleared on cancel.

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

- **Unit** (Kotlin, JUnit5 + MockK): packet serialization, loop-avoidance logic, `crypto_box_seal` roundtrip, reason-code filter truth table.
- **Unit** (Go): relay routing, JWT auth, FCM ping trigger, BoltDB queue flush.
- **Integration**: Android instrumentation test with two emulated `NotificationListenerService` instances talking via an in-process relay.
- **E2E manual**: two physical devices, scripted scenarios in `docs/test-scenarios.md` (WhatsApp post, dismiss, reply, update, ongoing, group cancel, offline→online flush, Doze wake via FCM).
- **Coverage target**: 80% on shared protocol + crypto + loop-avoidance modules.

---

## 9. Build Sequence (Phase Order)

1. **Relay (Go)** — WebSocket + BoltDB + pair endpoints. Run on laptop via docker-compose. No FCM yet.
2. **Android: listener + packet + relay transport** — post/update/cancel over relay only. Hardcoded pair config. Verify round-trip.
3. **Pairing flow + crypto** — QR-code pair, libsodium E2EE, JWT auth.
4. **Loop avoidance + dismissal sync** — reason-code filtering, mirroredFromPeer map.
5. **LAN transport** — NSD discovery, TLS socket, transport selection.
6. **FCM integration** — Firebase project, relay-side FCM Admin SDK, Android `FirebaseMessagingService`.
7. **Reply bridge** — RemoteInput passthrough.
8. **Reliability UX** — OEM onboarding, permission nags, connection-state UI.
9. **Cloud deploy migration path** — Fly.io target, env-var-driven config swap.

Each phase is a standalone working increment, reviewable, mergeable.

---

## 10. Repo Layout

```
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
