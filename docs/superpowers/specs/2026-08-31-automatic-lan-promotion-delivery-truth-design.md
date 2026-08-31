# Automatic LAN Promotion and Delivery Truth Design

**Date:** 2026-08-31

**Status:** Approved, with the mixed-version capability-negotiation correction recorded below

**Scope:** Android relay-paired devices, automatic LAN trust bootstrap, live route promotion/fallback, peer reachability, and delivery-state presentation

**Depends on:** Reliable delivery v2, direct LAN Tasks 1-9, existing relay pairing, Room version 9, and the current single-drainer `TransportCoordinator`

## 1. Goal

Make Twinotify automatically use direct Wi-Fi whenever both paired phones can authenticate each other on the same LAN, with the relay used only for bootstrap or fallback, while reporting delivery and peer reachability truthfully.

The implementation must guarantee that:

- a pair created through the relay gains LAN trust without re-pairing or another user ceremony;
- an existing relay-only pair upgrades in the background after both phones install a compatible app version;
- the relay never learns the LAN secret or becomes an authority for LAN identity;
- a healthy relay route is promoted to authenticated LAN within a bounded retry interval when direct connectivity becomes available;
- a failed LAN route falls back to relay immediately, without waiting through the LAN retry delay;
- exactly one route owns outbound outbox draining at any instant;
- a local relay WebSocket is never presented as proof that the peer phone is online;
- rows still on the sender, rows in relay custody, and rows awaiting authenticated peer receipt are distinct product states;
- internal control traffic never inflates the user-visible notification count;
- old app versions remain safe and continue to use relay until both phones can complete the new bootstrap.

## 2. Scope and Non-goals

### 2.1 In scope

- encrypted LAN trust bootstrap for relay-paired devices;
- automatic migration of existing relay-only pairs;
- domain-separated LAN-secret derivation from existing paired X25519 identities;
- background relay-to-LAN promotion with bounded backoff;
- immediate LAN-to-relay fallback;
- authenticated peer-liveness probes for relay routes;
- native delivery-status fields and matching home-screen copy;
- privacy-safe diagnostic reason codes;
- automated protocol, storage, transport, and presentation tests;
- recorded two-phone ADB verification after APK installation.

### 2.2 Non-goals

- changing notification capture, grouping, history retention, or clear-history behavior;
- changing mirrored-notification replacement semantics;
- changing the persistent foreground-notification title, content intent, or app icon;
- adding the secondary “open in Twinotify” notification action;
- changing themed launcher icons;
- redesigning relay-server onboarding or shipping a default production relay URL;
- allowing silent replacement of an established LAN TLS identity;
- multi-peer support;
- using relay presence as trusted peer presence.

Those items remain separate follow-up work so this change can preserve the transport and cryptographic invariants already in production.

## 3. Observed Root Causes

The current product behavior follows directly from four implementation gaps:

1. Relay pairing stores the peer X25519 and Ed25519 public keys but does not create a `LanBinding`. Only nearby/offline pairing writes `LanPairStore`.
2. `LiveTransportRoutesFactory` omits LAN entirely when no validated binding exists.
3. `TransportCoordinator` chooses LAN only when opening a route. Once relay is authenticated, it carries relay until that session closes and never probes for promotion.
4. `ReliableDeliveryDao.activeOutboundCount()` includes all nonterminal rows. A row already accepted into durable relay custody but waiting for a peer receipt is therefore presented as “Queued for delivery,” and `home.tsx` treats any authenticated relay socket as “Reachable now.”

The screenshots and live ADB observations are consistent with these gaps: both services can have healthy relay connectivity on the same subnet while no direct route is eligible, and one phone can show relay connectivity while the other phone has not produced authenticated evidence of processing its mailbox.

## 4. Alternatives Considered

### 4.1 Selected: post-pair E2E LAN bootstrap

Each phone announces its TLS identity through the existing encrypted and authenticated v2 delivery channel. Both phones derive the same LAN secret from their already-paired X25519 identities. The relay carries only ciphertext and cannot mint, inspect, or replace LAN trust.

This is the only option that gives new and existing relay pairs the same no-re-pair migration path without adding a second user flow.

### 4.2 Rejected: extend only the relay pairing transcript

A versioned relay-pairing transcript could bind TLS pins for newly paired devices. It would still leave every existing pair without a migration path, requiring the selected E2E bootstrap as a second mechanism. It also adds relay rollout ordering and duplicate protocol state without improving the final trust model.

### 4.3 Rejected: unpinned TLS followed by application authentication

The phones could connect to any presented TLS certificate and authenticate only in the signed LAN handshake. That reduces stored pin state but weakens the existing “verify TLS identity before application traffic” boundary and complicates reasoning about active interception. This design preserves TLS pinning.

## 5. Security Design

### 5.1 Existing trust anchor

Relay pairing already establishes and persists:

- local X25519 encryption secret and public key;
- peer X25519 public key;
- local Ed25519 signing secret and public key;
- peer Ed25519 public key;
- both stable device IDs.

An authenticated v2 inner event proves knowledge of the paired X25519 secret because `crypto_box_open_easy` must authenticate before `InboundDispatcher` sees the plaintext. `EnvelopeAuthenticator` also cross-checks the encrypted `origin_device` with the relay-visible envelope and stored peer identity.

### 5.2 LAN-secret derivation

Both phones compute the same precomputed X25519 box key:

```text
shared_key = crypto_box_beforenm(peer_enc_public, local_enc_secret)
```

The derivation context is a length-delimited canonical encoding:

```text
context =
  "twinotify-lan-binding-context-v1\n" ||
  tuple_1 || tuple_2

tuple =
  len(device_id_utf8) || device_id_utf8 ||
  len(enc_public) || enc_public ||
  len(sign_public) || sign_public
```

The two tuples are sorted by unsigned UTF-8 byte order of `device_id`. Equal device IDs are invalid. Lengths are unsigned 32-bit big-endian values. Public keys must each be exactly 32 bytes.

The final secret uses an HKDF-SHA-256 equivalent with explicit domain separation:

```text
salt       = SHA-256(context)
prk        = HMAC-SHA-256(key=salt, data=shared_key)
lan_secret = HMAC-SHA-256(
  key=prk,
  data="twinotify-lan-secret-v1\n" || 0x01
)
```

`shared_key` and `prk` are zeroed after derivation. The 32-byte result is stored through the existing Keystore-sealed `LanPairStore` and is used by the existing advertisement-ID and LAN-authentication code.

The relay-visible pair token is not an input. The relay cannot derive the result from public keys and traffic metadata.

### 5.3 TLS pin announcement

The v2 inner-event type enum gains `lan.bootstrap` with a closed-world payload:

```json
{
  "protocol_version": 1,
  "tls_spki_sha256": "64 lowercase hex characters",
  "binding_context_sha256": "64 lowercase hex characters"
}
```

The event omits `canon_id` and `sequence`. It has a normal UUID `msg_id`, a ten-minute expiry, and requires an authenticated peer receipt. The TLS pin and binding-context digest exist only inside ciphertext.

The sender obtains its pin from the same `LanIdentityStore` identity used by the live LAN TLS server. A mismatch between announced and runtime local identity is a local bootstrap failure, not a reason to publish a second identity.

### 5.4 Bootstrap receive rules

After envelope authentication and strict payload validation:

1. Recompute the binding context from local and stored peer identities.
2. Reject `binding_context_sha256` mismatch as `lan_bootstrap_context_mismatch`.
3. Derive the LAN secret locally.
4. Before committing new trust, durably ensure one local `lan.bootstrap` announcement exists in the outbox. This closes the cross-store crash gap: the local response survives even if the process dies immediately after binding commit.
5. If no valid binding exists, prepare and commit `LanBinding(peer_pin, derived_secret, protocol=1, now)`.
6. If an existing binding has the same peer pin, secret, and protocol, treat the event as an idempotent duplicate. `pairedAtMillis` does not participate in trust-material equality.
7. If an existing valid binding differs, preserve it and reject the event as `lan_binding_conflict`.
8. Never clear a relay pair because bootstrap failed.

`LanPairStore` remains the only component allowed to attach its public commit marker to `PeerStore`.

### 5.5 Send and recovery rules

After relay capabilities establish protocol floor 2 **and advertise both peer features `lan-bootstrap-v1` and `peer-probe-v1`**, every service transport generation enqueues at most one active `lan.bootstrap` announcement when either no LAN binding exists or the existing binding secret equals the locally derived bootstrap secret. Receiving an announcement also durably ensures one local announcement exists before committing new trust. The existing outbox and peer-receipt mechanism provides retry and restart durability. Protocol-floor-1 sessions and peers without both feature advertisements never receive these v2 controls.

The secret comparison distinguishes relay-bootstrap bindings from established nearby-pairing bindings, whose LAN secrets were created by the nearby session and must not be replaced. Bootstrap-derived bindings continue announcing once per service generation, which repairs expiry or a prior asymmetric crash without adding another durable state machine. Identical announcements are idempotent and are excluded from user-facing queue counts.

An older peer does not advertise the new features, so a current sender never places an unknown bootstrap or probe event in that peer's mailbox. The sender records a bounded internal incompatibility code, continues over relay, and re-evaluates the peer's capabilities on later relay updates. It never downgrades TLS authentication.

### 5.6 Mixed-version capability negotiation

The existing relay hello/capability exchange gains an optional, closed-world `features` string array. Current clients advertise exactly `lan-bootstrap-v1` and `peer-probe-v1`. The relay persists the list beside protocol versions and app version and returns `self_features` and `peer_features` only to a client that itself sent a non-empty feature list. This response shaping is required because deployed clients strictly reject unknown capability-frame fields; clients that send the legacy hello continue receiving the legacy capability shape byte-for-shape.

The updated mobile codec accepts both the legacy capability shape and the feature-bearing shape, treating absent feature lists as empty. The relay validates bounded, unique feature strings and never interprets encrypted bootstrap payloads. Feature negotiation is an availability/compatibility gate, not a cryptographic authority: LAN trust still requires the E2E-authenticated announcement, locally derived secret, pinned TLS identity, and signed LAN handshake.

This correction is required by the existing implementation: an old v2 client hard-rejects an unknown authenticated inner type and deliberately leaves the mailbox item unacknowledged. Blindly sending `lan.bootstrap` would therefore strand that item at the head of a mixed-version peer's mailbox. Gating the send is the smallest change that preserves the approved mixed-version safety requirement.

## 6. Authenticated Peer Liveness

### 6.1 Why relay connection is insufficient

An authenticated relay route proves only that this phone can reach the relay. The other phone may be offline, background-restricted, disconnected, or connected under a stale generation. Therefore relay route state cannot by itself set `peerReachable=true`.

### 6.2 Probe event

The v2 inner-event enum also gains `peer.probe` with this closed-world payload:

```json
{
  "probe_id": "same canonical UUID as msg_id",
  "sent_at": 1788160800000,
  "request_direct": true
}
```

The event requires a peer receipt and has a two-minute expiry. It is never materialized and never appears in recent notification activity.

While relay is the authenticated active route and the peer advertises `peer-probe-v1`:

- enqueue a probe immediately if no active probe exists;
- keep at most one active probe in the outbox;
- after its peer receipt, wait 60 seconds before the next probe;
- set `request_direct=true` only when the local LAN-attempt cooldown is due and direct delivery is preferred;
- accept peer evidence only from a receipt that matches a locally originated, unexpired probe in the current route generation;
- mark relay peer evidence stale 150 seconds after the latest matching probe receipt.

An inbound event or receipt drained from an old relay mailbox is not current liveness proof merely because it arrived now. Its peer may have produced it before going offline. Normal-message receipts may update delivery state, but only the fresh probe correlation updates relay reachability.

An authenticated LAN session is immediate peer evidence for the lifetime of that session. When LAN closes, the next route must establish fresh evidence; the former LAN state is not carried forward as “Reachable now.”

Local wall time is used only for freshness presentation. It does not authorize transport or mutate cryptographic state.

## 7. Route Selection and Handoff

### 7.1 Policy

With the existing `preferLan=true` default:

1. If LAN trust exists, try LAN first.
2. If LAN cannot authenticate, open relay immediately.
3. While relay is active, continue bounded LAN probes.
4. Promote as soon as a LAN probe authenticates.
5. Stay on LAN while it remains authenticated.
6. On LAN loss, open relay immediately and cool down only the next LAN probe.

An explicit `preferLan=false` remains an advanced opt-out and retains relay-first behavior. It does not run promotion probes.

LAN attempts need overlap on both phones: discovery cannot succeed if only one phone is listening. When a due relay-side probe uses `request_direct=true`, its sender begins a bounded local LAN attempt and its receiver triggers the same attempt after authenticating the event. The receiver ignores the request when direct delivery is disabled, a LAN attempt is already active, or the minimum 15-second anti-storm floor has not elapsed. The E2E event coordinates timing but does not authorize LAN trust; the stored binding, TLS pin, and signed LAN handshake remain authoritative.

### 7.2 Dynamic route availability

The initial route snapshot can lack LAN because bootstrap has not yet committed a binding. The first successful binding commit emits an internal route-configuration change only after inbound control processing and its durable response work have completed. The live transport loop joins the current coordinator and reloads `LiveTransportRoutesFactory`; it does not leave the old coordinator running while constructing a second owner.

The reload path is serialized through the existing transport-restarter ownership boundary. Bursts are conflated, and the signal producer never synchronously joins the route job from which it was called.

### 7.3 Relay-to-LAN promotion

Opening and authenticating a LAN candidate does not grant it the outbox-drainer lease. During probing:

- relay remains the only granted/self-draining route;
- the LAN candidate may complete TLS and the signed socket handshake;
- the candidate does not call `OutboxRepository.sendable`;
- a failed candidate is fully closed before scheduling the next attempt.

After LAN authentication:

1. mark the candidate ready but ungranted;
2. close relay with the bounded internal code `route_promoted_to_lan`;
3. wait for the relay session and self-drainer job to join;
4. grant the existing authenticated LAN candidate;
5. publish `LAN/AUTHENTICATED`;
6. start the coordinator pump for LAN.

There is no instant at which both routes select outbound rows.

### 7.4 LAN failure and cooldown

A LAN session failure causes immediate relay opening before any LAN retry delay. LAN probe delay progresses through 15 seconds, 30 seconds, 60 seconds, 120 seconds, and a five-minute cap. A LAN session that stays authenticated for the existing 30-second stability window resets the LAN failure count.

The user’s “Try again now” action interrupts the current route and probe cooldown but does not erase the failure count. This prevents a retry button from disabling backoff.

### 7.5 Race rules

- If relay closes while a LAN probe is opening, the authenticated LAN candidate wins; otherwise the normal route-open loop resumes.
- If preference changes to relay-first during a probe, close the candidate and retain relay.
- If unpair or service stop begins, close candidate, granted session, listeners, discovery, and Wi-Fi lease before completing shutdown.
- If two direct connections authenticate simultaneously, the existing deterministic LAN arbitration remains authoritative.
- Route generation guards prevent stale health callbacks from a joined coordinator from overwriting current status.

## 8. Delivery State Model

### 8.1 Native counts

Replace the single product meaning of `activeOutboundCount()` with a classified snapshot. Existing storage and retention code may continue to use total active rows where appropriate.

```text
pending_local
  active user-delivery rows with no accepted custody

awaiting_peer
  active user-delivery rows with accepted custody but no authenticated peer receipt

held_by_relay
  awaiting_peer rows whose relayCustodyState is ACCEPTED

internal_active
  active receipt, snapshot, bootstrap, probe, and other non-user control rows
```

User-delivery rows are notification state, notification action, and call-state events. UI copy uses “sync update” when the classified set is not exclusively notification events. Internal rows are visible only in diagnostics and never in the main count.

`total_active` and byte totals remain available for storage pressure and engineering health. Product status must not substitute `total_active` for `pending_local`.

### 8.2 Public route status

The privacy-safe native-to-JS route status gains:

```text
pending_local_count
awaiting_peer_count
held_by_relay_count
peer_evidence: direct | recent | stale | unknown
delivery_reason:
  none |
  no_route |
  waiting_for_peer |
  relay_holding |
  lan_bootstrap_waiting |
  lan_binding_conflict |
  peer_version_incompatible
```

It still exposes no relay URL, IP address, SSID, port, token, certificate, or peer key.

Reason priority is deterministic: binding conflict, incompatible peer, no route with local work, relay custody waiting, generic waiting for peer, bootstrap waiting, none. A later healthy state clears stale transient reasons but does not erase an unresolved binding conflict in the current service generation.

For backward compatibility, existing `queued_count` and `SyncHealth.queuedCount` become aliases of `pending_local_count`. Engineering totals and bytes use explicit total-active fields and are never inferred from that legacy alias.

### 8.3 Presentation rules

The existing connection surface and visual hierarchy remain. No new card, badge row, icon-only control, or nested panel is introduced.

| Condition | Main label | Explanation | Peer line | Action |
|---|---|---|---|---|
| LAN authenticated | Direct on Wi-Fi | Your phones are talking directly over Wi-Fi. | Reachable now | none |
| Relay authenticated, peer recent, no waiting rows | Via relay | Your other phone checked in recently. Delivery is encrypted end to end. | Checked in recently | none |
| Relay authenticated, relay holds rows, peer stale | Via relay | N sync update(s) are stored securely and waiting for your other phone. | Not confirmed online | none |
| Relay authenticated, peer stale, no waiting rows | Via relay | Connected to the relay. Waiting for your other phone to check in. | Not confirmed online | none |
| Relay authenticated, bootstrap incomplete | Via relay | Setting up direct Wi-Fi in the background. Delivery is encrypted end to end. | Checked in recently or Not confirmed online | none |
| Relay authenticated, peer version incompatible | Via relay | Update Twinotify on your other phone to enable direct Wi-Fi. | Checked in recently or Not confirmed online | none |
| Relay authenticated, LAN binding conflict | Via relay | Direct Wi-Fi needs attention. Relay delivery remains encrypted end to end. | Checked in recently or Not confirmed online | none |
| No route, local rows pending | Queued on this phone | N sync update(s) will send when a connection is available. | Not confirmed online | Try again now |
| No route, no local rows, relay already holds rows | Reconnecting | N sync update(s) are stored securely while this phone reconnects. | Not confirmed online | Try again now |
| No route, no local rows | Reconnecting | Looking for your other phone. This retries on its own. | Not confirmed online | none |
| Mirroring disabled | Paused | Turn on mirroring when you want delivery to resume. | no reachability claim | none |

The grammar helper selects “notification(s)” only when every counted row is a notification event; otherwise it uses “sync update(s).” Accessibility labels combine the main label, explanation, and peer line without exposing raw reason codes.

The relay-custody case does not use “Queued for delivery” because the item has already left the phone and is durable at the relay. The app must not promise the peer received it until the authenticated peer receipt arrives. “Reachable now” is reserved for a currently authenticated LAN session; relay evidence uses the deliberately weaker “Checked in recently.”

## 9. Error Handling and Observability

### 9.1 Stable internal codes

The new paths use bounded stable codes:

- `lan_bootstrap_crypto_unavailable`
- `lan_bootstrap_context_mismatch`
- `lan_bootstrap_payload_invalid`
- `lan_bootstrap_store_failed`
- `lan_binding_conflict`
- `lan_peer_version_incompatible`
- `lan_promotion_failed`
- `peer_probe_failed`
- `route_promoted_to_lan`

Only approved public `delivery_reason` values cross the JS boundary. Raw exception text, IPs, URLs, certificates, and socket details remain log-only and must not be written to activity history.

### 9.2 Activity and metrics

- Bootstrap and probe events do not appear in Recent activity.
- Successful route changes continue to annotate user event custody as `LAN` or `RELAY`.
- Metrics distinguish `pending_local`, `held_by_relay`, `awaiting_peer`, and `internal_active`.
- A bootstrap conflict is observable as a bounded health code and never clears existing trust.
- Debug logs may record route kind, phase, and stable code only.

## 10. Compatibility and Deployment

### 10.1 Protocol compatibility

The relay continues to treat the encrypted inner packet as opaque. `proto/inner-event-v2.schema.json` remains the source of truth and gains the two event types plus closed-world payload definitions. `proto/relay-control.schema.json`, relay capability persistence, and capability response shaping gain the compatibility feature list described in section 5.6. Relay protocol-fixture tests are updated after `make sync-proto`, but generated relay schemas are not committed.

When a new phone talks to an old phone through the updated relay:

- relay v2 delivery continues normally;
- the old phone advertises no transport features and receives no new capability-frame fields;
- the new phone does not enqueue either unknown control event;
- no LAN binding is created;
- notification delivery remains on relay;
- after the second phone updates, the relay propagates the new peer features and the current transport generation sends a fresh announcement and converges automatically.

### 10.2 Storage compatibility

The design reuses `LanPairStore` and existing outbound rows. It adds DAO queries and value-comparison helpers but does not require a new Room entity or database migration. If implementation reveals a need for durable state that cannot be reconstructed from the outbox and binding, the plan must stop and amend this design before changing Room version 9.

### 10.3 Rollout order

1. Land relay-control schema, capability persistence/response shaping, inner-event schema, and Android support together.
2. Run relay schema/fixture tests and Android unit/instrumented compilation gates.
3. Deploy the backward-compatible relay before installing a mobile build that advertises the new features.
4. Build and install the same APK on both phones.
5. Verify automatic bootstrap on the existing relay-only pair.
6. Verify relay-to-LAN promotion, Wi-Fi loss fallback, and recovery.

The relay deployment is required for safe mixed-version feature negotiation, not because the relay needs to inspect or understand either encrypted inner event.

## 11. Testing Strategy

All implementation changes follow failing-test-first TDD.

### 11.1 Pure Kotlin tests

- KDF symmetry for A/B roles;
- context changes when either identity field changes;
- device-order independence and identity-collision rejection;
- exact key-size and canonical-encoding rejection;
- bootstrap payload strictness;
- idempotent same-binding handling;
- conflict preservation;
- one active bootstrap/probe limit;
- probe freshness boundaries;
- classified queue-count truth table;
- public reason priority and copy grammar.

### 11.2 Transport tests

- LAN wins at initial open;
- relay carries while LAN is unavailable;
- relay promotes to an authenticated LAN candidate;
- relay self-drainer is joined before LAN pump begins;
- LAN loss opens relay without waiting for LAN cooldown;
- failed probes back off and cap;
- stable LAN resets only the LAN failure count;
- explicit retry interrupts cooldown without erasing it;
- preference change, stop, unpair, and route-generation races close every owned session;
- maximum concurrent outbox drainers remains one.

### 11.3 Protocol and storage tests

- valid and invalid `lan.bootstrap` fixtures;
- valid and invalid `peer.probe` fixtures;
- legacy and feature-bearing relay hello/capability fixtures;
- old clients receive the legacy capability shape and never receive new control rows;
- new clients gate bootstrap/probe sends until the peer advertises both features;
- unknown extra fields rejected;
- old event fixtures remain valid;
- control events are excluded from user counts but included in engineering totals;
- relay custody and peer receipt transitions update the right class exactly once.

### 11.4 React Native tests

- relay route alone never renders “Reachable now”;
- fresh relay probe evidence renders “Checked in recently,” while only authenticated LAN renders “Reachable now”;
- relay-held work stays “Via relay” and uses stored/waiting copy;
- only no-route local work renders “Queued on this phone” and the retry action;
- mixed notification/call/action work uses “sync update” grammar;
- text remains readable at narrow widths and large font scale;
- switch and retry targets remain at least 48 dp and have truthful accessibility labels.

### 11.5 Physical two-phone verification

With both phones unlocked and attached to ADB:

1. Record app version and APK SHA-256 on both phones.
2. Clear only diagnostic logs, not app data or pairing.
3. Start both services on different networks and confirm relay fallback.
4. Put both phones on the same Wi-Fi and record automatic `RELAY -> LAN` promotion without touching the app.
5. Post multiple notifications and confirm no replacement regression and one authenticated peer receipt per logical message.
6. Disable Wi-Fi on one phone and record immediate relay fallback without duplicate delivery.
7. Restore Wi-Fi and record bounded LAN promotion.
8. Stop the peer service and confirm the remaining phone changes from recent to stale evidence and never claims “Reachable now.”
9. Restart both apps and confirm the existing LAN binding remains valid and bootstrap replay is idempotent.

ADB output, timestamps, route generations, and screenshots form the hardware evidence. Automated tests do not substitute for this gate.

## 12. Acceptance Criteria

The work is complete only when all of the following are true:

1. The user’s existing relay-only pair creates valid LAN bindings on both phones without re-pairing.
2. On the same Wi-Fi with `preferLan=true`, both phones converge to `LAN/AUTHENTICATED` within the configured probe bound.
3. The relay session is closed after LAN promotion and no two drainers overlap in tests or instrumentation.
4. LAN loss opens relay before the next LAN retry delay begins.
5. A relay route never renders “Reachable now”; fresh probe evidence renders only “Checked in recently.”
6. Relay-accepted rows are not labeled as still queued on the sender.
7. Internal bootstrap, probe, receipt, and snapshot rows do not affect the user-visible count.
8. Existing direct-nearby pairings continue to work and reject unauthorized binding replacement.
9. With the capability-aware relay deployed first, a mixed-version pair remains safely functional over relay and auto-upgrades after both apps are current.
10. Focused Kotlin/JS tests, protocol fixtures, TypeScript checks, lint where affected, and relevant relay tests pass.
11. The final point-by-point interface review confirms hierarchy, spacing, copy truth, accessibility, light/dark rendering, and interaction states.
12. The named two-phone ADB run is recorded honestly; any unavailable hardware scenario remains explicitly unverified rather than implied complete.
