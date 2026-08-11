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

---

# Post-clean-review correction: bounded durable status processing

## Scope and outcome

The two Important findings in `task-4-clean-review.md` are corrected without
entering Task 5. Wrapped v1 puts now remain durably accepted while a typed
protocol-1 peer is paused in hello handshake. Terminal message lookup uses a
recipient/message index after a one-time transactional migration, and hello
expiry output drains a dedicated per-pair pending index in fixed pages of 64.

## Separate RED/GREEN evidence

### Finding 1: wrapped v1 during typed `[1]` handshake

The new test pauses the recipient inside the full WebSocket hello path after it
advertises `[1]` but before activation, then sends a wrapped-v1 `relay.put` from
its peer. The exact initial RED was:

```text
--- FAIL: TestWebSocketWrappedV1PersistsWhileProtocolOnePeerHandshakes
    ws_mailbox_test.go:760: wrapped v1 during typed [1] handshake = server.testFrame{V:2, Type:"relay.rejected", MsgID:"83677777-7777-4777-8777-777777777777", Envelope:nil}, want relay.accepted
```

The production fix treats `protocolV2Handshake` as typed transport for this
decision, matching the hub's existing typed protocol-1 handshake queue support.
The resulting focused GREEN, including the complete v1 compatibility matrix,
was:

```text
GOCACHE=/tmp/phone-sync-task4-status-focused-cache go test ./internal/server \
  -run 'TestWebSocket(HelloPagesExpiryStatusesUnderTerminalChurn|HelloSendsExpiryBeforeMailboxDelivery|WrappedV1PersistsWhileProtocolOnePeerHandshakes|V1EnvelopeCompatibility)' \
  -race -count=3 -v
PASS
ok github.com/twinotify/relay/internal/server 12.329s
```

### Finding 2: direct terminal lookup and bounded expiry output

The direct-lookup test first establishes valid indexed tombstones, then inserts
an unrelated malformed canonical status before exercising duplicate ACK and
terminal Put. The pre-index implementation scanned the unrelated record and
produced these exact REDs:

```text
TestMailboxTerminalLookupIgnoresUnrelatedCorruptStatuses/duplicate_ack:
indexed duplicate ack consulted unrelated status: unmarshal delivery status: unexpected end of JSON input

TestMailboxTerminalLookupIgnoresUnrelatedCorruptStatuses/terminal_put:
indexed terminal put consulted unrelated status: unmarshal delivery status: unexpected end of JSON input
```

The WebSocket churn test creates 70 acknowledged and 70 expired tombstones,
then reconnects until expiry output drains. Before paging, its exact RED was:

```text
--- FAIL: TestWebSocketHelloPagesExpiryStatusesUnderTerminalChurn
    ws_mailbox_test.go:682: first hello expiry page = 70, want 64
```

The store now maintains:

- `mailbox_status_by_recipient`: recipient/message to canonical status key,
  used by terminal Put and missing-item ACK without scanning status records.
- `mailbox_expiry_pending`: sender/recipient/message to canonical status key,
  containing expired statuses only and supporting deterministic pair-scoped
  pages.
- `mailbox_meta/status_indexes_v1`: a migration marker written only after both
  indexes are transactionally backfilled from legacy canonical tombstones.

Hello retains its capabilities, expiry-status, then durable-delivery ordering.
It reads at most 64 pending expiry statuses for the paired devices. Only after
every frame in that page writes successfully does it mark the page reported.
A failed partial page remains pending in full and may replay, giving at-least-
once reporting without silent loss. Reporting deletes only pending-index keys;
the original canonical tombstone, acceptance time, digest, mailbox expiry,
terminal occurrence, sender/recipient identity, and 24-hour expiry remain
unchanged. ACK tombstones are never visited by hello.

The store GREEN covers direct lookup, transactional legacy migration across a
database close/reopen, expiry drain marking without canonical deletion, and
secondary-index cleanup on purge and physical status expiry:

```text
GOCACHE=/tmp/phone-sync-task4-status-focused-cache go test ./internal/store \
  -run 'TestMailbox(TerminalLookupIgnoresUnrelatedCorruptStatuses|StatusIndexesMigrateLegacyTombstonesAcrossReopen|PurgePairDeletesBothDirections|ExpireStatusesRemovesOnlyExpiredTombstones)' \
  -race -count=5
ok github.com/twinotify/relay/internal/store 3.006s
```

The server churn GREEN above proves pages of exactly 64, then 6, then 0 across
reconnects. It also verifies all 140 canonical ACK/expiry tombstones survive
paging with identity, digest, scoping, acceptance timestamps, 24-hour expiry,
and ciphertext opacity intact.

## Transaction and boundary audit

- Put, ACK, expiry, purge, and physical tombstone expiry update canonical and
  secondary status state in the same Bolt write transaction.
- Direct terminal lookup is one secondary-key read plus one canonical-key read.
  The only whole-status migration is the versioned, one-time upgrade backfill.
- Hello's Bolt views close before any WebSocket write. Page marking is a later
  short Bolt update. No socket or network I/O occurs under a Bolt transaction,
  hub lock, or durable-handoff lane lock.
- Canonical tombstones still expire logically after 24 hours. Continuous
  physical sweeping is deliberately left to Task 8 as required by the review.
- Existing MaxItems/MaxBytes accounting, accepted-write/durable sequence
  ordering, 64-item mailbox delivery bound, overflow cancellation, envelope
  byte opacity, status-before-delivery ordering, and raw/typed v1 compatibility
  are unchanged.

## Fresh full verification

```text
GOCACHE=/tmp/phone-sync-task4-status-store-race-cache go test ./internal/store -race -count=1
ok github.com/twinotify/relay/internal/store 2.963s

GOCACHE=/tmp/phone-sync-task4-status-server-race-cache go test ./internal/server -race -count=1
ok github.com/twinotify/relay/internal/server 21.395s

GOCACHE=/tmp/phone-sync-task4-status-full-cache make relay-test
[exit 0; sync-proto completed and `cd relay && go test ./... -race -count=1` completed]

cd relay && GOCACHE=/tmp/phone-sync-task4-status-full-cache go vet ./...
[exit 0, no output]

git diff --check
[exit 0, no output]
```

## Final self-review

The authoritative clean review was re-read in full. The implementation was
checked against both findings, legacy migration/reopen, per-pair scoping,
reconnect continuation, purge/expiry cleanup, original tombstone identity,
digest idempotence/conflict, acceptance timestamps, ciphertext opacity, Task 5
boundaries, and Task 8's physical-sweep boundary. The complete supplied
anti-slop law was also re-checked point by point. This correction changes only
Go persistence/WebSocket logic, tests, and this evidence report; its visual UI
rules are inapplicable.

## Files changed by this correction

- `relay/internal/server/ws.go`
- `relay/internal/server/ws_mailbox_test.go`
- `relay/internal/store/mailbox_store.go`
- `relay/internal/store/mailbox_store_test.go`
- `.superpowers/sdd/task-4-fix3-report.md`

Requested commit message:

```text
fix(relay): bound durable status processing
```

---

# Re-review correction: retry bounded expiry statuses

## Outcome

The Important reliability finding appended after commit `da4e6e1` is fixed.
Expiry delivery is no longer retired because a server-side socket write
returned success. Canonical expired tombstones remain in the retry index for
their 24-hour lifetime, and a single persistent cursor per sender/recipient
pair rotates fixed-size pages fairly across reconnects.

This section supersedes the earlier report language that described successful
hello writes as permanently draining pending expiry-index keys.

## RED evidence

The legacy-index/reopen regression proved the old retirement was persistent:

```text
--- FAIL: TestMailboxStatusIndexesMigrateLegacyTombstonesAcrossReopen
    mailbox_store_test.go:529: rotated expiry after reopen = []store.DeliveryStatus{}, <nil>; want retry
```

The full WebSocket rotation and loss-after-success tests produced:

```text
--- FAIL: TestWebSocketHelloPagesExpiryStatusesUnderTerminalChurn
    ws_mailbox_test.go:701: hello expiry rotation page 2 from 64 = [six tail IDs], want [six tail IDs followed by the wrapped first 58 IDs]

--- FAIL: TestWebSocketHelloRetriesExpiryAfterSuccessfulWriteAndClientDeath
    ws_mailbox_test.go:751: expiry lost after successful write and client death: ... i/o timeout
```

The upgrade regression recreated the exact `da4e6e1` shape: a canonical
expired tombstone, the v1 migration marker, and an already-retired pending
entry. Its RED was:

```text
--- FAIL: TestMailboxStatusIndexUpgradeRestoresRetiredExpiryRetry
    mailbox_store_test.go:586: v1-retired expiry after index upgrade = []store.DeliveryStatus{}, <nil>; want retry
```

Self-review then exposed an ACK-churn starvation edge in the first cursor
implementation. Its separate RED was:

```text
--- FAIL: TestMailboxExpiryStatusCursorIsPairScoped
    mailbox_store_test.go:632: ACK churn reset rotated AB page = [first status], <nil>
```

Finally, the bounded stale-index test showed that limiting emitted frames alone
was insufficient because logically expired index entries could still cause an
unbounded scan:

```text
--- FAIL: TestMailboxExpiryStatusRotationBoundsStaleIndexProcessing
    mailbox_store_test.go:686: first bounded scan crossed stale page: [live status], <nil>
```

An API-level RED also established that expiry selection needed the hello's
explicit time to enforce the canonical 24-hour boundary deterministically:

```text
too many arguments in call to s.ExpiryStatuses
have (string, string, number, time.Time)
want (string, string, int)
```

## Design and safety

- `mailbox_expiry_pending` is now a durable retry index. Successful hello
  writes never delete its live entries.
- `mailbox_expiry_cursor` stores at most one message ID for each
  sender/recipient pair. Selection starts strictly after that ID, wraps once,
  and never repeats an entry within one page.
- A page inspects at most 64 index entries and emits at most 64 live statuses.
  Logically expired entries encountered in that bounded window are deleted from
  canonical and secondary state in the same short Bolt update. Continuous
  whole-database sweeping remains Task 8.
- The server advances the cursor to the page's last status only after every
  `relay.expired` write succeeds. A failed or partial page returns before the
  cursor update, so the complete page is eligible for retry and duplicates are
  safe.
- Apparent success followed by client death advances only fairness, not
  retryability. A one-item set wraps immediately; larger sets repeat after one
  bounded rotation cycle.
- ACK creation never resets an unrelated expiry cursor. Direct tombstone
  removal clears a cursor only when it points to that tombstone. Pair purge
  deletes both directional cursors; physical/logical status expiry removes
  matching cursor and index state transactionally.
- The migration marker is bumped to `status_indexes_v2`. The one-time
  transactional backfill restores canonical expired statuses whose v1 pending
  entries were retired by `da4e6e1`, then persists normal cursor state across
  close/reopen.
- Bolt transactions close before WebSocket writes. Cursor advancement is a
  later short update. No socket I/O occurs under Bolt, hub, or handoff locks.

The deterministic tests cover exact 64-entry pages across three wraps, no
first-page starvation, retry after server-success/client-death, partial-write
replay, ACK churn, pair scoping, bounded cursor count, v1 upgrade, reopen,
logical and physical 24-hour expiry, pair purge, cursor/index cleanup, original
status identity, digest and acceptance metadata, and ciphertext opacity.

## Final GREEN and gates

```text
GOCACHE=/tmp/phone-sync-task4-retry-report-store-cache go test ./internal/store \
  -run 'TestMailbox(StatusIndexesMigrateLegacyTombstonesAcrossReopen|StatusIndexUpgradeRestoresRetiredExpiryRetry|ExpiryStatusCursorIsPairScoped|ExpiryStatusRotationOmitsTombstoneAtLogicalExpiry|ExpiryStatusRotationBoundsStaleIndexProcessing|PurgePairDeletesBothDirections|ExpireStatusesRemovesOnlyExpiredTombstones)' \
  -race -count=5
ok github.com/twinotify/relay/internal/store 6.883s

GOCACHE=/tmp/phone-sync-task4-retry-report-server-cache go test ./internal/server \
  -run 'TestWebSocket(HelloPagesExpiryStatusesUnderTerminalChurn|HelloRetriesExpiryAfterSuccessfulWriteAndClientDeath|HelloPartialExpiryWriteDoesNotAdvanceCursor|HelloSendsExpiryBeforeMailboxDelivery)' \
  -race -count=3 -v
PASS
ok github.com/twinotify/relay/internal/server 9.208s

GOCACHE=/tmp/phone-sync-task4-retry-full-store-cache go test ./internal/store -race -count=1
ok github.com/twinotify/relay/internal/store 3.761s

GOCACHE=/tmp/phone-sync-task4-retry-full-server-cache go test ./internal/server -race -count=1
ok github.com/twinotify/relay/internal/server 16.614s

GOCACHE=/tmp/phone-sync-task4-retry-make-cache make relay-test
[exit 0; sync-proto and `go test ./... -race -count=1` completed]

cd relay && GOCACHE=/tmp/phone-sync-task4-retry-make-cache go vet ./...
[exit 0, no output]

git diff --check
[exit 0, no output]
```

## Final self-review

The appended authoritative re-review and the complete anti-slop law were read
and checked point by point. The correction preserves capabilities-before-
status-before-delivery ordering, durable acceptance order, MaxItems/MaxBytes,
64-item delivery bounds, overflow cancellation, exact opaque envelope bytes,
terminal digest/idempotence semantics, v1 compatibility, and Task 5's boundary.
This is persistence and WebSocket logic only, so visual-interface rules are
inapplicable. No client acknowledgement schema or Task 5 capability state was
added.

## Files changed by this correction

- `relay/internal/server/ws.go`
- `relay/internal/server/ws_mailbox_test.go`
- `relay/internal/store/mailbox_store.go`
- `relay/internal/store/mailbox_store_test.go`
- `.superpowers/sdd/task-4-fix3-report.md`

Requested commit message:

```text
fix(relay): retry bounded expiry statuses
```
