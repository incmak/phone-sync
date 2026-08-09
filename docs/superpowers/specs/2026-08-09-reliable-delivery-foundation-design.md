# Reliable Delivery Foundation Design

**Date:** 2026-08-09

**Status:** Approved design, pending implementation plan

**Scope:** Android-to-Android notification delivery through the Go relay

**Depends on:** Existing Phase 1-4 pairing, E2EE, notification listener, Room queue, and relay WebSocket implementation

## 1. Goal

Make Twinotify's notification transport durable, ordered, idempotent, and observable before notification actions, replies, calls, FCM wake, or LAN acceleration are added.

The foundation must guarantee that:

- accepting a frame into a local WebSocket buffer is never reported as peer delivery;
- an offline, reconnecting, slow, or temporarily Dozed peer receives encrypted events after it reconnects, for up to 24 hours;
- the relay stores only opaque ciphertext and bounded routing metadata;
- a sender removes a normal outbox item only after an authenticated peer receipt;
- duplicate frames, lost receipts, reconnects, and process restarts do not duplicate a visible notification or repeat a side effect;
- updates and cancels cannot arrive out of order for the same canonical notification;
- current notification state converges after event expiry through an encrypted anti-entropy snapshot;
- quota exhaustion, expiry, quarantine, and transport failure are explicit states rather than silent loss;
- relay restart and container recreation preserve pairing and mailbox state;
- every broad claim has automated or recorded physical-device evidence.

The existing product targets Android 14 and newer. iOS, desktop, notification actions, replies, call controls, FCM wake, and LAN transport remain separate subprojects that build on this foundation.

## 2. Product Semantics

### 2.1 Delivery guarantee

Normal notification state events use at-least-once transport and exactly-once visible application semantics:

1. The origin commits an encrypted event to its local Room outbox.
2. The relay durably stores the opaque envelope in the recipient mailbox.
3. The receiver authenticates, decrypts, validates, and applies the event idempotently.
4. The receiver durably submits an encrypted peer receipt.
5. The receiver acknowledges the original mailbox item to the relay.
6. The sender decrypts the peer receipt and removes the original outbox item.

The network may deliver a message more than once. The receiver must never expose the same logical transition more than once.

### 2.2 Retention

- Relay mailbox retention is 24 hours from the relay's first durable acceptance time.
- Client wall clocks are advisory and do not control relay expiry.
- The encrypted inner event includes `created_at` and `expires_at`; a receiver rejects an event that is already expired beyond a five-minute clock-skew allowance.
- Expired mailbox items become terminal `expired` records in the sender activity journal. They do not disappear silently.
- After expiry or detected divergence, active notification state is repaired by the snapshot protocol in section 8.

### 2.3 Backpressure and storage limits

The first implementation uses these explicit bounds:

- maximum encrypted envelope: 1 MiB;
- maximum pending mailbox items per recipient: 2,000;
- maximum pending mailbox bytes per recipient: 128 MiB;
- maximum accepted HTTP pairing body: 64 KiB;
- maximum local active outbox bytes: 128 MiB, excluding terminal activity history;
- maximum local active outbox rows: 2,000.

When a relay mailbox reaches either limit, `relay.rejected` with reason `mailbox_full` is returned. The sender retains the event locally, backs off, and surfaces a degraded health state.

The local outbox never drops its oldest row. Before rejecting a new state event at the local limit, it may safely compact only unaccepted events for the same `canon_id`: a newer post/update replaces an older unaccepted post/update, and an unaccepted cancel replaces all older unaccepted state events for that canonical notification. Accepted events, action-like events, receipts, and revocation events are never compacted. If safe compaction cannot create capacity, capture reports `local_queue_full` and records the failure in bounded metadata-only activity history.

## 3. Alternatives Considered

### 3.1 Selected: encrypted relay mailbox plus sender outbox

The relay stores bounded ciphertext until the receiver commits it. This is the only option that provides offline delivery without requiring the origin phone to remain continuously reachable. Relay compromise exposes device identifiers, timing, sizes, and connection metadata, but not notification contents.

### 3.2 Rejected: sender-only retry

Keeping all events only on the sender avoids relay ciphertext storage, but delivery fails whenever both phones are not simultaneously reachable. It also prevents FCM-triggered pull from recovering notifications while the origin is offline.

### 3.3 Rejected: online-only relay

The existing ephemeral forwarding model is simple but silently loses notifications during ordinary mobile lifecycle and network conditions. It does not satisfy the product goal.

### 3.4 Deferred: relay-side state compaction

The relay cannot safely compact notification state without seeing authenticated canonical IDs and sequence numbers. Exposing those fields weakens metadata privacy. Compaction stays client-side.

## 4. Protocol Version 2

### 4.1 Authenticated inner packet

Every v2 plaintext has this shape before encryption:

```json
{
  "v": 2,
  "msg_id": "uuid",
  "origin_device": "device-id",
  "type": "notif.post",
  "canon_id": "device:package:id:tag",
  "sequence": 17,
  "created_at": 1786267348000,
  "expires_at": 1786353748000,
  "payload": {}
}
```

`msg_id`, `origin_device`, `type`, `canon_id`, `sequence`, and timestamps are encrypted and authenticated. Event-specific data lives under `payload`. Events without a canonical notification, such as receipts and snapshot control packets, omit `canon_id` and `sequence` according to their event schema.

### 4.2 Outer encrypted envelope

The relay-visible v2 envelope is:

```json
{
  "v": 2,
  "type": "enc",
  "msg_id": "uuid",
  "origin_device": "device-id",
  "created_at": 1786267348000,
  "nonce": "base64",
  "ciphertext": "base64"
}
```

The receiver must compare the outer `msg_id`, `origin_device`, and `created_at` with the authenticated inner values. A mismatch is quarantined and never applied. Replay protection is applied atomically to the authenticated inner ID after successful decryption, not to unauthenticated outer metadata.

### 4.3 Relay control frames

Relay control frames are protected by TLS and the existing paired-device JWT authentication. They are not E2EE content packets.

Client to relay:

```json
{ "v": 2, "type": "relay.put", "envelope": {} }
{ "v": 2, "type": "relay.ack", "msg_id": "uuid", "envelope_sha256": "hex" }
{ "v": 2, "type": "relay.hello", "protocols": [2, 1], "app_version": "0.8.0" }
```

Relay to client:

```json
{ "v": 2, "type": "relay.accepted", "msg_id": "uuid", "accepted_at": 1786267348000 }
{ "v": 2, "type": "relay.deliver", "accepted_at": 1786267348000, "envelope": {} }
{ "v": 2, "type": "relay.rejected", "msg_id": "uuid", "reason": "mailbox_full" }
{ "v": 2, "type": "relay.expired", "msg_id": "uuid", "expired_at": 1786353748000 }
{ "v": 2, "type": "relay.capabilities", "self": [2, 1], "peer": [2, 1] }
```

The relay accepts a duplicate `(recipient_device, msg_id)` only when its envelope digest matches the stored digest. It returns the original `accepted_at`. A duplicate ID with different bytes is rejected as `id_conflict` and logged as a security event.

When a mailbox item expires, the relay retains a metadata-only delivery-status tombstone for another 24 hours and reports `relay.expired` to the authenticated sender on its next connection. The tombstone contains no ciphertext or notification content. This gives the sender an authoritative terminal transport outcome even when it was offline at expiry.

### 4.4 Peer receipts

After applying a normal event, the receiver sends an encrypted v2 packet:

```json
{
  "v": 2,
  "msg_id": "receipt-uuid",
  "origin_device": "receiver-device-id",
  "type": "peer.receipt",
  "created_at": 1786267349000,
  "expires_at": 1786353749000,
  "payload": {
    "acked_msg_id": "original-uuid",
    "envelope_sha256": "hex",
    "status": "applied"
  }
}
```

Allowed terminal statuses are `applied`, `expired`, `rejected`, and `decrypt_failed`. `rejected` and `decrypt_failed` include a bounded machine-readable reason.

Receipt packets do not require another peer receipt. Their sender removes them from its local outbox after `relay.accepted`, because the relay then durably owns their delivery. The final recipient still sends `relay.ack` so the relay can remove the receipt mailbox item. This prevents an infinite receipt-of-receipt chain.

The receiver must obtain `relay.accepted` for the peer receipt before sending `relay.ack` for the original event. If it disconnects between applying the event and accepting the receipt, the original remains in the relay mailbox; redelivery causes the idempotency journal to resend the receipt without repeating the side effect.

## 5. Persistent Data Models

### 5.1 Android outbox

Replace the auto-increment deletion queue with message-addressed state:

```text
OutboundMessage
  msgId: String primary key
  canonId: String?
  sequence: Long?
  eventType: String
  envelopeJson: String
  envelopeSha256: String
  byteSize: Long
  createdAt: Long
  expiresAt: Long
  relayAcceptedAt: Long?
  attempts: Int
  nextAttemptAt: Long
  state: NEW | ACCEPTED | RECEIPTED | EXPIRED | QUARANTINED
  lastError: String?
  requiresPeerReceipt: Boolean
```

`NEW` and `ACCEPTED` rows are active. `RECEIPTED`, `EXPIRED`, and `QUARANTINED` outcomes move to a bounded metadata-only activity journal. Ciphertext is deleted after the terminal transition.

### 5.2 Android inbound journal

```text
InboundMessage
  msgId: String primary key
  originDevice: String
  envelopeSha256: String
  eventType: String
  canonId: String?
  sequence: Long?
  outcome: PENDING_PLATFORM | APPLIED | STALE | EXPIRED | REJECTED | QUARANTINED
  committedAt: Long
  appliedAt: Long?
  receiptMsgId: String?
```

The inbound journal and desired notification-state mutation are committed in one Room transaction. A platform materializer then executes the desired post or cancellation using stable identifiers and marks the inbound row `APPLIED` only after the platform call succeeds. A duplicate with the same digest resumes a `PENDING_PLATFORM` operation or returns the prior terminal outcome and regenerates or requeues the same logical receipt. A duplicate ID with a different digest is quarantined.

### 5.3 Canonical notification state

```text
CanonicalNotificationState
  canonId: String primary key
  originDevice: String
  latestSequence: Long
  state: ACTIVE | CANCELLED
  desiredPayloadJson: String?
  materializedSequence: Long
  sourceNotificationKey: String?
  mirrorLocalId: Int?
  mirrorLocalTag: String?
  updatedAt: Long
```

An event with `sequence <= latestSequence` is stale and cannot mutate desired state. A cancel writes `CANCELLED` before invoking platform cancellation. A late post or update therefore cannot resurrect it. Stable mirror IDs are reused for every update of the same canonical notification.

`latestSequence` may temporarily exceed `materializedSequence` after process death or a platform failure. Startup and listener rebind run the materializer until they match. `NotificationManager.notify(stableTag, stableId, notification)` and cancellation by an exact stored key are idempotent, so retry is safe. No peer receipt is emitted while an inbound row remains `PENDING_PLATFORM`.

`sourceNotificationKey` stores the exact `StatusBarNotification.key` for origin notifications. Peer-initiated cancellation is executed through the bound `NotificationListenerService.cancelNotification(key)`, never by fuzzy package/id matching.

### 5.4 Relay mailbox

BoltDB stores mailbox records keyed by recipient device ID and message ID:

```text
MailboxRecord
  recipientDevice: String
  senderDevice: String
  msgId: String
  envelopeSha256: [32]byte
  envelope: []byte
  byteSize: uint64
  acceptedAt: int64
  expiresAt: int64
```

Separate recipient indexes preserve acceptance order and track total pending bytes and count. Pair ownership is checked on every put, deliver, and acknowledgement. The production container sets `BOLT_PATH=/data/twinotify-relay.db`; the mounted `/data` volume is the authoritative durable store.

## 6. Ordered Processing

### 6.1 Origin pipeline

Notification listener callbacks submit immutable capture commands to one process-wide ordered coordinator. The coordinator:

1. filters self, denied, group-summary, and unsupported notifications before icon work;
2. allocates the next per-canonical sequence in Room;
3. captures the exact source notification key;
4. builds and encrypts the event;
5. commits the canonical state and outbox row atomically;
6. signals the transport without depending on the React Native process state.

Post, update, and removal callbacks for one canonical notification cannot overtake each other. Expensive bitmap preparation may run concurrently, but its result carries the reserved sequence and is discarded if a newer sequence has already committed.

### 6.2 Receiver pipeline

WebSocket callbacks write raw relay deliveries to one bounded inbound channel. A dispatcher pool may decrypt unrelated messages concurrently, but canonical desired-state commits are serialized by `canon_id` and guarded by the persisted sequence. A lifecycle-independent materializer applies persisted desired state to Android, resumes incomplete work on process start and listener rebind, and authorizes receipts only after success. Receipt and snapshot control packets use a separate ordered control lane.

### 6.3 Connection lifecycle

- The notification listener always targets the durable coordinator; it never falls back to a logging-only sink.
- Mirroring has one persisted enabled flag. Stop, boot, sticky restart, and listener rebind all honor it.
- Relay URL and paired-device configuration are read from durable native storage on every service start, including a null-intent sticky restart.
- Reconnect backoff resets to one second after a connection remains authenticated and healthy for 30 seconds.
- Reconnect drains the relay mailbox in bounded batches before sending a convergence digest.
- The foreground notification reflects connecting, connected, queued, degraded, and stopped native states accurately.

## 7. Relay Concurrency and Security

- Producers never send to channels that another goroutine may close. A connection registration owns its cancellation context and private writer queue; replacement cancels the old context without closing a producer-visible channel.
- Mailbox persistence occurs before `relay.accepted` is written.
- Relay delivery is a read from durable state, not a transfer of ownership to an in-memory channel.
- HTTP server header, read, write, and idle timeouts are explicit.
- Pairing request bodies and key fields have strict size limits.
- Pending pairing records have TTL sweeping and per-IP/token rate limits.
- Authenticated unpair/revoke atomically removes both device indexes, confirmed pair state, active sockets, capability records, and both mailbox directions.
- Production deployment exposes only trusted TLS through Caddy; relay port 8080 remains internal. Release clients reject `ws://` and `http://`, while debug builds may use them for local development.

## 8. Expiry and State Convergence

Event history may expire after 24 hours, but currently active notifications must still converge.

After mailbox drain, peers exchange an encrypted digest containing the origin epoch, active canonical count, and a SHA-256 digest of sorted `(canon_id, latest_sequence, state)` tuples. A mismatch starts a bounded snapshot:

```text
state.snapshot.begin(snapshot_id, origin_epoch, item_count)
state.snapshot.item(snapshot_id, canon_id, sequence, notification_payload)
state.snapshot.end(snapshot_id, digest)
```

Snapshot items use the same notification payload schema as `notif.post`. They are chunked so every encrypted envelope remains below 1 MiB. The receiver stages items by snapshot ID and applies reconciliation only after `state.snapshot.end` validates the count and digest. An incomplete snapshot expires locally after 10 minutes with no visible removals.

At commit, the receiver upserts all active snapshot notifications and cancels mirrors for that origin that are absent from the authoritative set. Snapshot sequence values pass through the same stale-event guard, so a concurrent newer live event wins.

## 9. Failure Handling

### 9.1 Lost delivery or receipt

Sender and relay retry with bounded exponential backoff plus jitter. Duplicate application is prevented by the inbound journal. An ACK or receipt can be repeated without error.

### 9.2 Decrypt and validation failure

A first or second identical failure remains unacknowledged and is retried. On the third identical failure, the receiver records a quarantined digest and emits an encrypted `decrypt_failed` or `rejected` receipt referencing the outer ID and digest. It never executes the payload. The sender records the terminal failure and directs the user to repair pairing.

### 9.3 Apply failure

No receipt or relay acknowledgement is sent until the Room transaction and platform side effect reach a recoverable committed state. Platform posting/cancellation is structured so retry can safely complete or repeat it using stable IDs and persisted desired state.

### 9.4 Mailbox or local capacity

Capacity failures are explicit health states. No accepted or non-compactable row is evicted. Activity history records only package, event type, timestamps, size, and status by default; notification content is excluded.

### 9.5 Clock skew

Relay acceptance controls mailbox lifetime. Inner expiry allows five minutes of clock skew. Latency metrics clamp negative clock-skew values and record them separately rather than reporting false performance.

## 10. Compatibility and Rollout

1. Relay deploys first with v1 passthrough plus v2 mailbox/control support.
2. Android adds dual-read support and advertises `[2, 1]` during authenticated connection setup.
3. Existing v1 queue rows remain byte-for-byte unchanged. A new sender may wrap them in `relay.put` when the peer is v2; a v2 receiver acknowledges their outer ID and records them as legacy. A v1 peer continues to receive raw v1 envelopes through the online-only compatibility path.
4. Android writes v2 only after both relay and peer report v2 support. A new sender paired to a v1 peer uses explicit legacy online-only forwarding and never labels relay forwarding as reliable peer delivery.
5. Once both paired devices advertise v2, the relay records the negotiated floor and rejects v1 frames for that pair, preventing downgrade.
6. The UI shows `Legacy peer: online-only delivery` until both phones support v2.
7. Room migrations add v2 tables before changing writers. They neither rewrite nor delete existing ciphertext.
8. Rollback remains possible while the pair floor is v1. After v2 negotiation, rolling back a phone requires an explicit compatibility reset and surfaces degraded reliability rather than silently accepting v1.

## 11. Verification Strategy

### 11.1 One-command gate

Root `make verify` must perform:

- JSON Schema compilation and positive/negative fixtures;
- cross-language protocol golden-fixture checks;
- Go formatting, vet, unit tests, race tests, and relay container build;
- Expo dependency installation in CI, TypeScript type-check, and Expo Doctor;
- clean Expo Android prebuild;
- blocking Android lint;
- Kotlin JVM tests;
- debug APK assembly;
- Android instrumentation tests when an emulator is available.

No workflow may mark lint non-blocking. CI publishes APKs, JUnit reports, schema fixture results, relay race output, and emulator logs.

### 11.2 Kotlin unit coverage

Required behaviors:

- normal outbox rows survive relay acceptance and are removed only by a valid peer receipt;
- receipt rows become relay-owned after durable relay acceptance;
- safe local compaction never discards an accepted event;
- authenticated inner/outer metadata mismatch is rejected;
- replay check-and-mark is atomic under concurrent duplicates;
- per-canonical sequence rejects stale post, update, cancel, and snapshot items;
- cancellation tombstones prevent resurrection;
- stable mirror IDs replace notifications in place;
- a crash after desired-state commit but before or during the platform call resumes materialization and delays the receipt;
- exact source keys implement peer-to-origin dismissal;
- expiry, quarantine, and activity transitions are durable across process recreation;
- snapshot staging is invisible until a valid end frame commits it;
- reconnect backoff resets after a healthy session;
- JSON null remains null through every payload round trip.

### 11.3 Go coverage

Required behaviors:

- mailbox put commits before acceptance;
- duplicate ID plus identical digest is idempotent;
- duplicate ID plus different digest is rejected;
- only the paired sender can put and only the recipient can ack;
- pending count and byte quotas return backpressure without eviction;
- expiry removes ciphertext and updates indexes atomically;
- Bolt state survives server and container recreation;
- revocation purges both mailbox directions and closes active sockets;
- connection replacement, send, ack, and unregister remain race-free under stress;
- HTTP sizes, timeouts, pending-pair caps, and rate limits are enforced.

### 11.4 Two-emulator end-to-end suite

The harness pairs two Android emulators through the real relay and uses a test notification publisher. It verifies:

- basic post within the awake latency target;
- repeated updates replace one visible mirror;
- origin and peer dismissals both remove the exact counterpart;
- post/update/cancel bursts converge to the newest sequence;
- duplicate delivery and receipt loss do not duplicate side effects;
- receiver offline, sender offline, relay restart, process death, and network reconnect retain delivery;
- a 24-hour-expired event reports expiry, then snapshot repair converges active state;
- service restart and reboot honor the persisted enabled flag;
- a 1,000-notification run creates no feedback loop and reaches the correct final state.

### 11.5 Physical release matrix

At minimum, release evidence covers one current Pixel and one current Samsung device on Android 14 or newer. Record device model, OS build, app build, relay build, network, and timestamps for:

- screen unlocked and locked;
- Wi-Fi to cellular and cellular to Wi-Fi handoff;
- 24-hour Doze;
- process kill, reboot, package update, listener revocation, and listener rebind;
- relay loss and recovery;
- 100 notifications/day battery run using `dumpsys batterystats`.

The binding product gates remain:

- approximately 1-2 seconds off-LAN while both phones are awake and connected;
- no missed final notification state after the 24-hour Doze scenario;
- less than 1.5% battery per 24 hours at approximately 100 notifications/day;
- no loop or incorrect final state in the 1,000-notification stress run.

## 12. Release Gate

The reliability foundation is not complete while any of these remain:

- a known critical or high-severity defect;
- a Kotlin path that CI does not compile;
- protocol/schema drift or an unverified migration;
- a relay race finding;
- silent loss, silent eviction, or false delivered status;
- duplicate visible notifications;
- stale notification resurrection;
- incorrect source or mirror dismissal;
- unverified relay persistence across container recreation;
- missing emulator evidence for offline/restart/idempotency scenarios;
- missing physical evidence for Doze, OEM lifecycle, latency, and battery gates.

## 13. Follow-on Subprojects

After this foundation passes its automated gates:

1. secure notification actions, inline replies, and phone-call controls;
2. FCM wake, lazy foreground-service operation, and optional LAN transport;
3. icon hash caching, richer notification fidelity, history, health UX, and release polish;
4. desktop receiver and multi-pair support.

Each follow-on uses the same authenticated v2 envelope, outbox, receipt, sequencing, convergence, and verification infrastructure.
