# Twinotify Offline LAN Sync Design

**Date:** 2026-08-18
**Status:** Approved architecture, written specification awaiting review
**Scope:** Android 14+ phone-to-phone pairing and reliable notification/call-state synchronization on a shared local network without internet access

## 1. Objective

Twinotify must pair and synchronize two Android phones when both devices share a local network even if that network has no internet access. The existing relay remains an optional fallback, not a prerequisite.

The delivered behavior is:

- fresh installs can establish their first pair without contacting a relay;
- paired devices discover and authenticate each other on a shared Wi-Fi network;
- notifications, call state, snapshots, cancellations, and peer receipts use the existing encrypted protocol and durable delivery state;
- direct LAN is preferred when healthy;
- relay is used when configured and direct LAN is unavailable;
- when neither route is usable, events remain durably queued and later converge;
- status and foreground-service copy report the route that is actually carrying traffic;
- screen-off reliability on an offline LAN is supported through the existing user-enabled Always Connected foreground service.

## 2. Existing Foundation

This design extends the current implementation rather than creating a second synchronization system.

Reusable foundations include:

- `DeviceIdentity`, `CryptoStore`, and the existing signing/encryption keys;
- `PeerStore` and the one-peer ownership model;
- protocol-v2 encrypted envelopes and strict schema validation;
- Room-backed outbound custody, inbound journal, desired state, materialization retry, receipts, snapshots, and deduplication;
- `InboundDispatcher`, notification/call reducers, and materializers;
- `SyncService`, `ServiceConfigStore`, and Always Connected lifecycle behavior;
- relay transport and its authenticated fallback path.

The current UI already models `Direct on LAN`, relay, and offline states, but the native service only supplies relay connectivity. This specification closes that gap.

## 3. Constraints and Non-goals

### 3.1 Required constraints

- Android minimum SDK remains 34.
- The local path must not require DNS, a public certificate authority, Google Play Services, a laptop, or internet access.
- All peer content remains encrypted end to end using the existing protocol-v2 envelope format.
- Local discovery is only a locator. It is never an identity or trust source.
- No notification title, text, package name, call metadata, raw device identifier, long-lived key, or pair token may appear in DNS-SD records or logs.
- All queues and frames are byte-bounded as well as count-bounded.
- LAN and relay must share one outbound custody model. They must not race two independent send loops against the same rows.
- Duplicate delivery caused by route switching must be safe and idempotent.

### 3.2 Initial non-goals

- Wi-Fi Direct, Bluetooth transport, or automatic hotspot creation;
- communication across guest networks that isolate clients;
- more than one paired peer;
- iOS or desktop LAN implementations;
- guaranteed background delivery when the user disables Always Connected;
- using a phone as an embedded relay for other devices;
- replacing the existing relay or making relay enrollment mandatory.

## 4. Architecture

The native service gains four components:

1. `LanIdentityStore`: owns a Keystore-backed TLS identity and certificate pin.
2. `OfflinePairingCoordinator`: establishes trust without a relay.
3. `LanTransport`: advertises, discovers, authenticates, and exchanges direct frames.
4. `TransportCoordinator`: selects exactly one active outbound route and exposes truthful health.

```text
Notification/call capture
          |
          v
Existing durable outbox and Room state
          |
          v
TransportCoordinator
    | preferred       | fallback
    v                 v
LanTransport       RelayTransport
    |                 |
    +------ encrypted protocol-v2 envelopes ------+
                                                   |
                                                   v
                                      Existing InboundDispatcher
                                                   |
                                                   v
                              durable commit, materialize, receipt
```

The transport coordinator owns scheduling. A transport is only a route implementation and cannot independently drain the durable outbox.

## 5. Cryptographic Identity

### 5.1 Separate TLS and envelope keys

The existing Curve25519/Ed25519 application keys remain the authority for protocol encryption and transcript signatures. They are not reused as TLS certificate keys.

Each installation generates a non-exportable P-256 signing key in Android Keystore and a self-signed X.509 certificate suitable for TLS. `LanIdentityStore` exposes only:

- the TLS certificate chain needed by the local server;
- the SHA-256 digest of the certificate SubjectPublicKeyInfo;
- signing operations performed by Keystore;
- controlled identity rotation during a full unpair/reset.

The TLS SPKI digest is bound to the existing application identity inside the mutually signed pairing transcript. A peer therefore trusts the TLS certificate because its pin was explicitly paired and signed, not because the certificate is publicly trusted.

### 5.2 Persisted LAN binding

`PeerStore` currently uses plaintext DataStore and is suitable only for public peer identity. The LAN pair secret must not be added to it.

A new `LanPairStore` persists one versioned binding blob sealed with the existing Android-Keystore master-key mechanism. The sealed content includes:

- random `bindingId`;
- digest of the complete public `PeerRecord` identity;
- peer TLS SPKI SHA-256;
- pair-specific LAN secret;
- negotiated LAN protocol version;
- pairing timestamp.

`PeerRecord` gains only the public `lanBindingId` commit marker. Persistence uses a recoverable two-phase order:

1. write and verify the sealed LAN binding under a fresh `bindingId`;
2. commit the matching public peer record and `lanBindingId` in one `PeerStore` edit;
3. treat LAN trust as usable only when both records exist and the binding's peer-identity digest matches the current public peer record;
4. remove unreferenced sealed bindings during startup recovery.

A crash before step 2 leaves an unusable orphan that recovery deletes. After step 2, both required records already exist. This makes a half-pair non-usable even though Android DataStore does not provide a transaction across two files.

Migration from an existing relay pair leaves `lanBindingId` absent. Such a pair continues using relay until the users perform an authenticated LAN-upgrade ceremony on both phones. No TLS pin is inferred from discovery traffic.

## 6. Offline Pairing

### 6.1 Pairing roles

The initiating phone creates a time-limited local pairing session. The joining phone scans its QR code. Either phone can initiate.

The initiating phone:

1. creates a random 256-bit session token and a bounded monotonic lifetime;
2. starts a temporary TLS listener;
3. advertises `_twinotify-pair._tcp` with an opaque random session identifier;
4. displays a QR payload.

The QR payload contains only data intentionally shown to the other user:

- format and protocol version;
- session identifier, creation hint, and maximum lifetime;
- initiator device ID and display name;
- application encryption and signing public keys;
- initiator TLS SPKI pin;
- session token.

It does not contain notification data, private keys, an IP address, or a relay requirement. The initiator's monotonic deadline is authoritative. The joining phone caps the advertised lifetime to the protocol maximum, but pairing never assumes that the two wall clocks agree.

### 6.2 Mutual transcript

After resolving the matching temporary service, the joining phone connects with the QR-provided TLS pin. Both sides exchange:

- their application public identities;
- their TLS SPKI pins;
- independent nonces;
- the session identifier and bounded lifetime fields;
- the negotiated protocol version.

All fields are encoded using one canonical, length-delimited transcript format. Both phones derive:

- a short authentication string for human comparison;
- a pair-specific LAN secret using HKDF over the session secret, both nonces, and transcript digest.

Each screen shows the same short code and peer name. Both users must explicitly confirm. Each phone then signs the full transcript with its existing application signing key. Trust is persisted through the sealed-binding two-phase commit only after both signatures and both confirmations are verified.

Cancellation, timeout, service loss, pin mismatch, signature failure, or user rejection clears the temporary listener and all provisional state. A half-pair is never written to `PeerStore`.

### 6.3 Existing relay pair upgrade

For devices already paired through the relay, LAN identity can be added without replacing the peer:

- both devices enter “Enable nearby sync”;
- QR exchange is still required;
- the presented application keys must exactly match the existing `PeerRecord`;
- only LAN pin/secret fields are added atomically;
- any identity mismatch aborts and leaves the existing relay pair unchanged.

### 6.4 Optional relay enrollment after offline pairing

An offline-created pair is not automatically known to any relay. Entering a relay URL must therefore start an explicit enrollment ceremony rather than pretending fallback is ready.

Enrollment reuses the relay's existing confirmed-pair protocol with the identities already stored locally:

1. one phone creates a relay pair session using its existing device ID and public keys;
2. it sends the session token and a hash of the normalized relay origin to its peer over the already authenticated LAN channel, or displays a QR if LAN is unavailable;
3. the second phone verifies that both advertised application keys match its existing peer record and asks the user to confirm the relay origin;
4. both phones complete the relay handshake with their existing signing keys;
5. each phone independently verifies an authenticated relay connection before persisting relay-enabled status;
6. a partial or failed enrollment leaves the offline LAN pair untouched and reports relay fallback as unavailable.

The ceremony never rotates or replaces the paired application identities. A relay reporting a conflicting existing binding is treated as an enrollment failure requiring user action, not as permission to rebind either device.

## 7. Discovery and Privacy

### 7.1 Paired service advertisement

While sync is enabled and Always Connected is active, each phone advertises `_twinotify._tcp` through Android `NsdManager`.

The service name and TXT data contain only:

- protocol major version;
- a daily rotating advertisement ID;
- capability bits that reveal no user content;
- the listening port supplied through `NsdServiceInfo`, not as a TXT secret.

The advertisement ID is derived from the pair-specific LAN secret, the UTC day, the advertiser's stable device ID, and a domain-separated label. Including the advertiser identity inside the keyed derivation gives the two phones distinct values while revealing neither identity to an observer. Each phone can calculate the expected value for its stored peer. Discovery checks the local UTC day and its immediate previous and next days, allowing symmetric one-day clock or midnight skew without extending the passive correlation window beyond three candidates. A larger mismatch is reported as a clock problem rather than silently weakening authentication. Raw device IDs and display names are never advertised.

Discovery results remain untrusted until all of the following succeed:

1. the derived advertisement ID matches the paired secret;
2. the TLS SPKI matches the stored peer pin;
3. a fresh application hello is signed by the stored peer signing key;
4. the nonce and session identifier are not replayed.

### 7.2 Android platform behavior

The implementation uses the executor-based `NsdManager` APIs and network-aware resolution. It reacts to Wi-Fi changes through `ConnectivityManager.NetworkCallback` rather than retaining stale IP addresses. Discovery requests a local Wi-Fi transport and must not require `NET_CAPABILITY_INTERNET` or `NET_CAPABILITY_VALIDATED`, because the feature must work on a router with no uplink. Client sockets use the `Network` returned with the resolved NSD service, through that network's socket factory or an explicit `bindSocket`, so an enabled cellular network cannot silently capture or misroute the direct connection.

The app currently targets SDK 36, where `INTERNET` still permits local sockets. The manifest already declares `ACCESS_LOCAL_NETWORK`. Before targeting SDK 37, the app must add the Android 17 runtime permission flow or adopt the system NSD picker for service-specific access. The abstraction must represent permission denied separately from peer unavailable so the future transition does not require a transport rewrite.

Background mDNS reception may require a carefully scoped `WifiManager.MulticastLock` on affected platform versions. It is acquired only while discovery is active and Always Connected is enabled, and is always released on stop or network loss.

Official references:

- [Network service discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [NsdManager API](https://developer.android.com/reference/android/net/nsd/NsdManager)
- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

## 8. Direct Protocol

### 8.1 Connection ownership

Both phones listen, but only one normally initiates the paired connection. The lexicographically smaller stable device ID initiates. The other accepts. If simultaneous connections occur after a race or network transition, both sides deterministically retain the same connection and close the duplicate.

Every connection performs:

1. pinned TLS handshake;
2. signed `lan.hello` challenge-response;
3. capability and protocol-floor negotiation;
4. bounded clock-independent liveness setup;
5. durable synchronization.

### 8.2 Framing and bounds

Frames use a four-byte network-order length prefix followed by UTF-8 JSON. The frame envelope is closed-world and versioned.

Allowed direct control types are:

- `lan.hello` and `lan.hello_ack`;
- `lan.put` carrying one existing encrypted envelope;
- `lan.accepted` carrying message ID and digest;
- `lan.ping`, `lan.pong`, and `lan.close`.

The existing protocol envelope limit remains authoritative. Frame parsing rejects zero length, oversize length, malformed UTF-8, unknown fields/types, invalid IDs/digests, and trailing bytes before allocation or dispatch. Queue limits use both a small frame count and a total byte budget so several legal one-megabyte frames cannot create an unsafe memory spike.

### 8.3 Durable custody semantics

Direct LAN does not bypass receipts.

- `lan.put` is passed to the same strict parser/authenticator and `InboundDispatcher` used by relay delivery.
- `lan.accepted` is emitted only after the inbound message has reached durable local custody or has been recognized as an exact durable duplicate.
- For ordinary events, sender rows remain until the authenticated peer receipt arrives.
- For peer-receipt rows, direct acceptance is terminal because the receipt itself is the final acknowledgement.
- A message ID with a different digest is rejected as a conflict and never acknowledged.

Route switching may resend an envelope. Existing message ID/digest checks make exact duplicates safe. Relay acceptance and LAN acceptance feed a common custody repository so only valid state transitions are applied.

## 9. Route Selection

`TransportCoordinator` exposes these route states:

- `LAN_CONNECTING`;
- `LAN_CONNECTED`;
- `RELAY_CONNECTING`;
- `RELAY_CONNECTED`;
- `OFFLINE_QUEUED`;
- `DISABLED`.

Selection rules:

1. a healthy authenticated LAN route is preferred;
2. when LAN is unavailable or loses liveness, a configured relay is used;
3. when neither route is usable, the outbox remains queued;
4. when LAN returns, the coordinator finishes or cancels the current bounded relay send boundary, then changes route without starting a second drain loop;
5. transport changes wake the scheduler immediately;
6. errors use bounded exponential backoff with jitter, reset only after a sustained authenticated interval.

The relay may retain its control connection in standby if measurements show that this materially improves failover without unacceptable battery cost. It must not drain outbound rows while LAN owns the route. The first implementation should close or suspend relay activity under LAN and measure reconnection behavior before adding standby complexity.

## 10. Lifecycle and Background Reliability

The `SyncService` owns the NSD registration, local TLS server, discovery, transport coordinator, and route health. They share the service lifecycle and are cancelled and joined before unpair wipes keys or Room state. Unpair clears the LAN binding, deletes the LAN TLS Keystore alias, clears public peer state, and rotates the existing application keys in the established safe order. Startup recovery deletes any orphaned binding left before a public commit.

Always Connected is the reliable offline mode:

- the foreground service remains active while paired and enabled;
- its notification says `Direct on LAN`, `Over relay`, or `Offline, events queued`;
- boot restart occurs only when paired, sync-enabled, and Always Connected are all true;
- Wi-Fi network changes restart discovery and invalidate resolved addresses;
- listener reconnect triggers state reconciliation through the existing snapshot path;
- retention and retry jobs continue using their existing durable schedules.

If Always Connected is disabled, direct LAN is best effort while the app process is alive. The UI must state that Android can stop background discovery and sockets. No feature copy may promise dependable screen-off, offline delivery in this mode.

## 11. User Experience

### 11.1 Onboarding

The initial connection choice becomes:

- `Pair nearby without internet`;
- `Use a relay`.

Nearby pairing guides the user through:

1. local-network permission rationale when required;
2. create or scan QR;
3. finding the nearby phone;
4. comparing a short verification code;
5. confirming on both phones;
6. enabling notification access and Always Connected;
7. verified connection result.

Failure copy distinguishes permission denied, isolated Wi-Fi, peer not found, expired QR, identity mismatch, and service stopped. It never collapses these into “Network request failed.”

### 11.2 Home and settings

Home status is derived from native route health:

- `Direct on LAN`;
- `Over relay`;
- `Offline, N events queued`;
- pairing or permission-specific degraded state.

Settings include:

- `Prefer nearby connection`, enabled by default;
- optional relay URL and test result;
- Always Connected with accurate battery/reliability explanation;
- LAN permission state and repair action;
- paired TLS fingerprint details suitable for human inspection;
- an authenticated LAN-upgrade action for existing relay pairs.

The current visual language is preserved. Any modified screen must be checked against the repository anti-slop design law, accessibility, text clipping, contrast, touch targets, and real device rendering.

## 12. Failure Handling

The implementation explicitly handles:

- mDNS name collision and service rename;
- stale service resolution or changing IP address;
- guest Wi-Fi client isolation;
- local-network permission revocation;
- TLS pin mismatch;
- invalid or replayed signed hello;
- simultaneous connection races;
- process death during pairing;
- route loss during a frame;
- duplicate message after route switching;
- peer storage full or Room transaction failure;
- repeated malformed or oversized frames;
- unpair during active direct traffic;
- Wi-Fi sleep, Doze, reboot, and network handoff.

Authentication failures do not fall back to trusting another discovered endpoint. Repeated invalid peers are rate-limited by address and session. Logs contain bounded error codes and hashed identifiers only.

## 13. Verification Strategy

### 13.1 Automated tests

Pure JVM tests cover:

- canonical pairing transcripts and signature verification;
- QR parsing, expiry, and closed-world validation;
- pair-secret and rotating-advertisement derivation;
- TLS pin comparison;
- deterministic connection ownership;
- frame parser size and malformed-input rejection;
- route-selection state transitions;
- LAN/relay custody equivalence;
- duplicate, conflict, timeout, and reconnect behavior;
- unpair cancellation order;
- UI status mapping.

Android instrumentation tests cover:

- Keystore TLS identity generation and reuse;
- loopback pinned TLS handshake and pin rejection;
- Room transitions for direct acceptance and receipts;
- service lifecycle and permission-degraded states;
- process restart during provisional pairing and queued delivery;
- NSD behavior through an injectable adapter plus bounded real-device smoke coverage.

Existing protocol, relay, mobile, generated-file, lint, race, and release-build gates remain mandatory.

### 13.2 Two-device physical acceptance

Final acceptance uses two distinct Android 14+ physical devices and a Wi-Fi network whose internet uplink is disabled while local client communication remains enabled.

Required scenarios:

1. uninstall or clear both apps, then pair using only QR and local Wi-Fi;
2. verify no relay or public endpoint is contacted during pairing;
3. post, update, and cancel notifications in both directions;
4. mirror supported call-state transitions in both directions;
5. verify duplicate suppression and stable mirror identity;
6. force-stop/restart each app and prove durable convergence;
7. turn each screen off and verify Always Connected delivery;
8. disconnect/reconnect Wi-Fi and prove queued convergence;
9. move one phone to a different network and prove no false LAN connection;
10. configure a relay, remove LAN availability, and prove relay fallback;
11. restore LAN and prove safe return to direct routing;
12. revoke local-network access and verify truthful degraded health;
13. unpair during active traffic and verify no state/key recreation;
14. run bounded burst/backpressure and long-idle reconnect tests.

Evidence includes sanitized route timelines, queue counts, device/API identities, APK SHA-256, test XML, and packet-level proof that offline scenarios do not depend on the relay. No notification content is captured.

Performance targets after warmup:

- awake LAN delivery median below 500 ms;
- awake LAN p95 below 1 second under ordinary load;
- route failover begins within one liveness interval;
- no loss, reordering, or unbounded memory growth under the planned burst suite.

Screen-off latency is measured and reported rather than inferred. It is only accepted as reliable with Always Connected enabled.

## 14. Delivery Decomposition

This feature is too security-sensitive for one monolithic change. It will be implemented through three separately reviewed plans:

1. **Offline pairing and LAN identity**
   - Keystore TLS identity;
   - QR schema and mutual transcript;
   - atomic peer persistence and existing-pair upgrade;
   - pairing UX and security tests.

2. **Direct LAN transport and route coordination**
   - NSD adapter, advertisement privacy, and listener;
   - pinned TLS and signed hello;
   - bounded framing and custody integration;
   - one-owner LAN/relay route scheduler;
   - lifecycle and health reporting.

3. **Product integration and physical reliability proof**
   - onboarding/home/settings completion;
   - permission and troubleshooting flows;
   - two-device offline, screen-off, fallback, stress, and release evidence;
   - full anti-slop and accessibility review for touched UI.

Each plan is RED-first, independently reviewed before push, and ends at a testable boundary. Plan 2 cannot claim completion from fake NSD or loopback TLS alone. Plan 3 cannot claim completion without the two-device offline evidence.

## 15. Acceptance Criteria

The offline LAN feature is complete only when all statements are proven:

- Two fresh phones can pair with the internet unavailable and no laptop relay.
- Both phones mutually authenticate application identity and pinned TLS identity.
- A passive or active LAN peer cannot impersonate the paired phone.
- Notification and call-state flows work in both directions over direct LAN.
- Existing durable ordering, idempotency, materialization, receipt, and snapshot guarantees remain true.
- Relay fallback and return-to-LAN do not lose or corrupt events.
- Offline events survive process death and converge later.
- Screen-off behavior is verified on two physical phones with Always Connected.
- Status never reports LAN or relay connectivity without an authenticated usable route.
- Memory and queue use remain bounded under maximum legal frame sizes and burst tests.
- Existing relay-only users continue working and can opt into LAN securely.
- Full repository verification, independent code review, and physical evidence are green with no Critical or Important findings.
