# Task 4 fix 3 report: linearize durable delivery transfer

## Status

Both Important findings from the final review are corrected. Idle per-recipient
handoff lanes are reclaimed without allowing concurrent holders to split onto
different lanes, and every live or handshake delivery now has one explicit
Bolt-view-to-bounded-queue linearization point.

## TDD evidence

### Reclaim idle durable handoff lanes

Initial deterministic RED:

```text
TestWebSocketHelloHandoffSurvivesFailedAcceptedWrite:
idle handoff lanes after failed accepted write = 1, want 0

TestWebSocketHelloHandoffPreservesSequenceAcrossReverseAcceptedWrites:
idle handoff lanes after overlapping puts = 1, want 0

TestWebSocketDurableHandoffReclaimsManySequentialRecipients:
idle handoff lanes after sequential recipients = 128, want 0

TestWebSocketHelloHandoffExpireRefillClosesAtConfiguredBound:
idle handoff lanes after overflow = 1, want 0
```

GREEN under race stress:

```text
go test ./internal/server \
  -run 'TestWebSocket(HelloHandoffSurvivesFailedAcceptedWrite|HelloHandoffPreservesSequenceAcrossReverseAcceptedWrites|DurableHandoffReclaimsManySequentialRecipients|HelloHandoffExpireRefillClosesAtConfiguredBound)' \
  -race -count=3
ok github.com/twinotify/relay/internal/server 14.865s
```

`durableHandoffs.acquire` creates or leases a lane atomically under the parent
map lock. The lease spans Put, metadata recording, the sender accepted write,
and ready-prefix dispatch. `release` takes the parent lock before lane state and
deletes only when refs are zero, pending is empty, and dispatch is inactive.
Overflow-cleared and failed-Put lifecycles release through the same path.

### Linearize validation through bounded queue insertion

Initial store RED:

```text
TestMailboxTransferLiveByIDsLinearizesAgainstTerminalMutations/{ack,expiry,purge}_wins:
MailboxStore lacks atomic live-transfer API

TestMailboxTransferLiveByIDsLinearizesAgainstTerminalMutations/transfer_wins_before_{ack,expiry,purge}:
MailboxStore lacks atomic live-transfer API
```

Initial hub RED:

```text
TestWebSocketDeliveryTransferLinearizesConnectionReplacement/{live,handshake}:
ClientHub lacks atomic bounded delivery-transfer API
```

GREEN evidence:

```text
go test ./internal/store \
  -run 'TestMailbox(TransferLiveByIDsLinearizesAgainstTerminalMutations|LiveByIDsCopiesAndOmitsExpiredRecords)' \
  -race -count=10
ok github.com/twinotify/relay/internal/store 6.494s

go test ./internal/server \
  -run 'TestWebSocket(LiveDeliveryMutationWinsBeforeTransfer|DeliveryTransferLinearizesConnectionReplacement|HelloHandoff|DurableHandoff)' \
  -race -count=3
ok github.com/twinotify/relay/internal/server 17.175s

go test ./internal/server \
  -run 'TestWebSocketHelloHandoffDoesNotEmitExpiredBufferedItem|TestWebSocketLiveDeliveryMutationWinsBeforeTransfer' \
  -race -count=5
ok github.com/twinotify/relay/internal/server 3.060s
```

`MailboxStore.TransferLiveByIDs` validates and copies opaque records inside one
Bolt view, then keeps that read transaction open while the server serializes
frames and performs an all-or-none, nonblocking hub queue insertion. ACK,
expiry, or purge that commits first is absent from the view. If the transfer
owns the view first, the writer transaction waits until the already-authorized
queue callback returns.

The initial hello drain, buffered handshake flush, and normal live dispatch all
use this API. Capabilities and expiry statuses are still written before the
initial bounded delivery queue is populated. The outbound queue is sized to the
frozen 64-item initial batch. Capacity failure stops the current connection so
reconnect drains Bolt in durable order; it never deletes mailbox state.

Deterministic barriers cover ACK, expiry, and purge immediately before live
transfer, plus expiry after handshake snapshot and before transfer. Connection
replacement after store validation but before hub insertion sends only to the
current live replacement; a stale handshaking connection accepts nothing.

### Preserve typed transport independently of envelope advertisement

The first resumed full gate exposed one regression:

```text
2026/08/11 09:13:54 relay hello for mailbox-device-b: delivery queue unavailable
--- FAIL: TestWebSocketV1EnvelopeCompatibility (2.75s)
    --- FAIL: .../typed_peer_advertises_its_actual_live_protocols_and_rejects_v2_before_put
        device mailbox-device-b did not select protocol 3
```

Root cause: the new queue-transfer methods incorrectly required advertised
encrypted-envelope protocol 2 even though the connection had already selected
the typed v2 control transport. A `[1]` typed peer is valid and must receive
relay control frames carrying compatible v1 envelopes.

Deterministic RED:

```text
TestWebSocketDeliveryTransferUsesTypedTransportForProtocolOneEnvelope/live:
typed protocol-one connection rejected relay control delivery

TestWebSocketDeliveryTransferUsesTypedTransportForProtocolOneEnvelope/handshake:
typed protocol-one connection rejected relay control delivery
```

Minimal GREEN removed only those two incorrect envelope-protocol checks:

```text
go test ./internal/server \
  -run TestWebSocketDeliveryTransferUsesTypedTransportForProtocolOneEnvelope \
  -race -count=10
ok github.com/twinotify/relay/internal/server 1.669s

go test ./internal/server -run TestWebSocketV1EnvelopeCompatibility -race -count=3
ok github.com/twinotify/relay/internal/server 3.548s
```

## Lock-order and safety audit

- Lane lifecycle: parent map mutex -> lane state mutex. No code takes these in
  reverse order. Commit and dispatch mutexes are per-recipient.
- Durable delivery: Bolt read view -> hub mutex. `FlushOrActivateV2` releases the
  hub mutex before invoking its store-backed resolver, so no reverse edge exists.
- Put: per-recipient commit mutex -> Bolt update; later ready-prefix dispatch uses
  dispatch mutex -> Bolt view -> hub mutex. ACK, expiry, and purge take only Bolt
  write transactions. Register, unregister, replacement, and stop take only the
  hub mutex.
- Serialization, frame copies, and channel insertion are bounded by selected
  mailbox IDs, MaxItems/MaxBytes metadata limits, and channel capacity. Channel
  insertion never waits. No socket or network call occurs under Bolt, hub, or
  lane locks.
- Ciphertext is never retained in lane or handshake metadata. Exact envelope
  bytes remain opaque and are copied only while serializing an authorized frame.

## Compatibility and boundary audit

- Durable acceptance sequence and accepted-write ordering remain exact.
- Mailbox MaxItems/MaxBytes, handshake metadata limits, overflow cancellation,
  terminal tombstones, expiry statuses, and the 64-item drain cap remain intact.
- Raw v1 forwarding stays byte-exact and online-only. Typed v1 compatibility and
  live advertised protocol behavior are unchanged.
- No persistent capabilities, negotiated floor, or downgrade state was added;
  Task 5 remains untouched.

## Fresh full verification

```text
GOCACHE=/tmp/phone-sync-task4-fix3-resume-focused-cache go test ./internal/store \
  -run 'TestMailbox(TransferLiveByIDsLinearizesAgainstTerminalMutations|LiveByIDsCopiesAndOmitsExpiredRecords)' \
  -race -count=10
ok github.com/twinotify/relay/internal/store 4.927s

GOCACHE=/tmp/phone-sync-task4-fix3-resume-focused-json-cache go test ./internal/server \
  -run 'TestWebSocket(HelloHandoff|HelloDrainHandoff|LiveDeliveryMutationWinsBeforeTransfer|DeliveryTransferLinearizesConnectionReplacement|DurableHandoff)' \
  -race -count=1
ok github.com/twinotify/relay/internal/server 6.686s

GOCACHE=/tmp/phone-sync-task4-fix3-resume-full2-cache make relay-test
? github.com/twinotify/relay/cmd/relay [no test files]
ok github.com/twinotify/relay/internal/server 12.821s
ok github.com/twinotify/relay/internal/store 2.878s

cd relay && GOCACHE=/tmp/phone-sync-task4-fix3-resume-full2-cache go vet ./...
[exit 0, no output]

git diff --check
[exit 0, no output]
```

## Anti-slop/design-law re-check

The complete supplied design law was re-read point by point before handoff.
This change contains Go persistence, concurrency, WebSocket queueing, tests, and
this report only. UI layout, type, color, iconography, animation, clipping, and
visual interaction rules are inapplicable. The report uses plain language and
concrete evidence without decorative interface conventions.

## Changed files

- `relay/internal/server/client_hub.go`
- `relay/internal/server/durable_handoff.go`
- `relay/internal/server/server.go`
- `relay/internal/server/ws.go`
- `relay/internal/server/ws_mailbox_test.go`
- `relay/internal/store/mailbox_store.go`
- `relay/internal/store/mailbox_store_test.go`
- `.superpowers/sdd/task-4-fix3-report.md`

## Commit

Requested commit message:

```text
fix(relay): linearize durable delivery transfer
```
