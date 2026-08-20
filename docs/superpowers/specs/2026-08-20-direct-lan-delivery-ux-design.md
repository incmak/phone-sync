# Direct LAN Delivery and Route UX

**Date:** 2026-08-20
**Status:** Approved
**Extends:** `2026-08-18-offline-lan-sync-design.md`

## Outcome

Twinotify pairs two phones once, then delivers every existing encrypted envelope
over the local network whenever the paired peer is reachable. The same durable
outbox falls back to the relay when direct LAN is unavailable. Notifications,
call state, snapshots, cancellations, and receipts all use this one policy.

Pairing alone is not presented as a live route. The product only reports a route
after that route has authenticated and is actually usable.

## Route policy

`TransportCoordinator` is the sole selector and sender for a durable outbound
row. It prefers an authenticated, healthy LAN session. If that session is not
usable, it selects the configured authenticated relay. If neither route is
usable, it preserves the row for retry.

- Route changes may resend the exact same encrypted envelope.
- Route changes never alter message IDs, sequence values, digests, or receipt
  semantics.
- LAN acceptance and relay acceptance are the same durable custody transition,
  recorded with the route that accepted it.
- Receipt rows are terminal on acceptance over either route. Ordinary events
  remain until the existing peer receipt or snapshot convergence rules complete.
- Inbound frames from either route pass through the existing authenticated
  `InboundDispatcher`; there is no LAN-specific materializer or dedupe path.

## Direct LAN lifecycle

The active service advertises and discovers only a paired, rotating NSD
identifier. Discovery does not establish trust. A candidate must pass derived
advertisement matching, stored TLS SPKI pin verification, signed application
hello, freshness, and bounded frame parsing before it becomes a route.

The coordinator closes and releases LAN resources on Wi-Fi loss, pin/hello
failure, service stop, unpair, or bounded liveness failure. It attempts relay
fallback without losing queued work. It re-attempts LAN only with bounded,
decorrelated backoff and no parallel direct sessions.

## Product experience

The home screen and foreground notification expose one concise connection state:

| Actual condition | User-facing state | Meaningful action |
| --- | --- | --- |
| authenticated paired LAN session | **Direct on Wi-Fi** | none; this is the preferred route |
| authenticated relay selected | **Via relay** | none; delivery remains encrypted |
| route recovery in progress | **Reconnecting** | retry is automatic; user may open details |
| no usable route with durable work | **Queued for delivery** | show queued count and a single retry control |
| no paired peer | **Not paired** | start pairing |

“Offline” is not shown merely because a relay is disconnected when a paired LAN
route is healthy. “Twinned” means trust was committed, not that a message was
delivered. Pair-success copy states that nearby delivery becomes active when the
peer is reachable, and route status provides the current truth.

The screen uses plain hierarchy, not decorative badges: one route label,
one-sentence explanation, and one action only when recovery needs user input.
All state changes are announced accessibly, preserve keyboard/screen-reader
semantics, and remain readable at large font scales in light and dark themes.

## Failure behavior

Security failures remain fail-closed and distinguishable: invalid QR/frame,
TLS pin mismatch, application identity mismatch, peer rejection, local-network
permission denial, and Wi-Fi unavailability. No repair screen exposes secrets,
raw network identifiers, or transcript material.

An unreachable peer is a delivery condition, not a pairing failure. The app
keeps durable work, reports the queue truthfully, and retries automatically when
LAN or relay health returns.

## Verification

Completion requires:

1. JVM and Android transaction tests proving route-neutral custody, duplicate
route switching, receipt behavior, ordering, bounded queues, parser rejection,
TLS/application authentication, retry, and fallback.
2. Android-test source compilation and runtime execution for Room migration,
route transitions, and lifecycle cleanup.
3. Two physical phones on shared Wi-Fi: fresh pair, notification and call state
delivery over direct LAN, snapshot/cancellation/receipt convergence, process
restart persistence, and relay fallback recovery.
4. A controlled no-uplink run with packet/DNS evidence before claiming offline
acceptance.
5. Manual UI verification on both phones for direct, relay, reconnecting,
queued, large-font, light/dark, and TalkBack states.

## Scope boundary

This adds the delivery route promised by nearby pairing. It does not add
Bluetooth, Wi-Fi Direct, hotspot automation, multi-peer sync, or a second
outbox. Protected-release evidence and a real no-uplink physical run remain
separate release gates.
