# Task 7 Correction Evidence Report: Revocation Linearization

## Scope and history

This correction addresses every Critical and Important finding in
`.superpowers/sdd/task-7-review.md` without starting Task 8.

History anchors at correction start:

```text
Task 6 base:                f06488fc56754ee6f13c5e36816e001c6a188e49
Task 7 feature commit:      7c24d39ee12a31a1b5effdb0d3c48acd7b6cbf3c
Correction working base:    7c24d39ee12a31a1b5effdb0d3c48acd7b6cbf3c
Branch:                     main
```

The earlier report's claim that the feature commit was blocked is obsolete. The
preserved staged work was subsequently committed as `7c24d39` with subject
`feat(relay): revoke pair authorization and mailboxes`.

Correction commit status at evidence handoff:

```text
Intended subject: fix(relay): linearize revocation across pair generations
Commit result:    blocked by the managed approval reviewer
Staged base/head: 7c24d39ee12a31a1b5effdb0d3c48acd7b6cbf3c
```

The reviewer stated that it did not see trusted authorization for the exact
default-branch history mutation and explicitly prohibited workarounds. No retry
or indirect commit path was attempted. The complete correction and this report
remain staged for the primary agent. If that agent has sufficient authority to
create the commit, its resulting head hash belongs in the reviewer handoff; it
cannot be self-embedded in the content-derived commit that contains this report.

Expanded correction ownership was limited to the paths directly required to
establish the generation boundary and retain existing durable-delivery tests:

- `relay/internal/server/client_hub.go`
- `relay/internal/server/durable_handoff.go`
- `relay/internal/server/jwt_auth.go`
- `relay/internal/server/revoke.go`
- `relay/internal/server/revoke_test.go`
- `relay/internal/server/server.go`
- `relay/internal/server/ws.go`
- `relay/internal/server/ws_mailbox_test.go`
- `relay/internal/store/mailbox_store.go`
- `relay/internal/store/pair_store.go`
- `relay/internal/store/pair_store_test.go`
- `.superpowers/sdd/task-7-report.md`

`ws.go`, `jwt_auth.go`, `durable_handoff.go`, and `mailbox_store.go` are directly
required because the review requires the immutable pair identity to reach every
WebSocket store mutation and delivery transfer. `ws_mailbox_test.go` changes only
bind existing synthetic clients to their already-confirmed pair ID.

## Strict TDD and observed RED evidence

The correction tests were written before the corresponding production changes.

### Required revocation interleavings

The first correction run failed to compile because the tests named APIs and
test barriers that did not exist yet, including:

```text
undefined: bucketRetainedTokenByPair
ClientHub.ConnectionForPair undefined
Server.webSocketBeforeRegister undefined
Server.revokeAfterCommit undefined
Server.relayPutBeforeStore undefined
Server.relayAckBeforeStore undefined
Server.handleRelayPutForPair undefined
Server.handleRelayAckForPair undefined
MailboxStore.PutForPair undefined
```

After adding only test seams and compatibility scaffolding, the deterministic
behavior RED run proved all four reviewed races were real:

```text
--- FAIL: TestRevokeRejectsAuthenticatedSocketRegisteringAfterCommit
    socket only reached its read deadline instead of closing
--- FAIL: TestRevokeDisconnectDoesNotCloseReboundGeneration
    new generation registration was disconnected
--- FAIL: TestRevokeRejectsInFlightOldSessionPutAfterRebind
    old generation put was not rejected
--- FAIL: TestRevokeRejectsInFlightOldSessionAckAgainstReboundMailbox
    old generation ack error=<nil>, want ErrNotFound
```

These barriers place execution at:

- JWT authentication complete but WebSocket registration not started;
- revoke Bolt commit complete but old-generation disconnect not started;
- old-session peer/capability resolution complete but mailbox put not started;
- old-session ACK accepted by the frame handler but mailbox mutation not started.

### Pair-scoped retained-token index

The retained-token correction tests initially failed because the pair-scoped
bucket and lifecycle did not exist. They cover:

- direct cleanup with 256 unrelated pending tokens;
- a corrupt unrelated pending value that must not affect revoke;
- rollback of the retained-token index when mailbox purge fails;
- one-time migration of a legacy committed token;
- a second `NewPairStore` after migration with a corrupt pending value, proving
  startup does not rescan after the version marker is committed.

### Final exact-generation audit REDs

The final audit found two remaining boundary gaps and added tests first.

Old generation invoking the WebSocket-triggered expiry sweep after rebind:

```text
internal/store/pair_store_test.go:814:23: mailbox.ExpireForPair undefined
FAIL github.com/twinotify/relay/internal/store [build failed]
```

Pair-scoped hub operation accepting an unbound synthetic registration:

```text
--- FAIL: TestPairScopedHubOperationsRejectUnboundRegistration
    pair-scoped lookup accepted an unbound registration
FAIL github.com/twinotify/relay/internal/server
```

Both became green only after the expiry transaction validated the exact pair
generation and pair-scoped hub matching rejected empty pair IDs.

## Implementation summary

### Immutable session and hub generation

- JWT authentication now snapshots one `PairSession` containing pair ID,
  device ID, peer ID, and a copied signing public key in a single Bolt view.
- The authenticated request context carries both device ID and immutable pair
  ID.
- WebSocket registration stores that pair ID on `wsClient`, then immediately
  revalidates the exact device/pair binding. This is the second half of the
  registration protocol: revoke-before-registration fails revalidation, while
  registration-before-revoke is removed by generation-targeted disconnect.
- Pair-scoped hub lookup, send, stop, transfer, and disconnect require an exact
  non-empty pair match. Legacy unscoped helpers remain only as compatibility
  wrappers and cannot satisfy a pair-scoped operation.
- Revocation disconnects only clients tagged with the revoked pair ID, so a
  newly rebound generation registered in the post-commit gap remains live.
- Durable handoff notifications carry the immutable pair ID through Bolt-view
  transfer and hub enqueue. Mixed old/new notifications are dispatched in
  contiguous generation batches without changing acceptance ordering.

No Bolt or hub mutex is held across socket I/O. The only callback under a Bolt
view performs bounded serialization/copying and nonblocking hub-queue mutation,
preserving the Task 4 handoff contract.

### Store mutation linearization

- `RevokeBySession` verifies the expected pair ID in the same Bolt update that
  calls `purgePairTx` and removes authorization.
- `PutForPair` validates the exact pair and sender/recipient ownership in the
  same Bolt update that durably accepts the opaque envelope.
- `AckForPair` validates the exact pair and expected sender in the same Bolt
  update that removes mailbox state and writes the terminal tombstone.
- Capability writes and floor advancement validate the exact pair in their
  Bolt update.
- Expiry sweep and expiry-cursor mutations validate the exact pair in their
  Bolt update.
- Pending delivery reads and Bolt-view-to-hub transfers validate and filter by
  the exact pair. Empty pages still validate a pair-scoped caller.
- Existing unscoped store methods remain as compatibility wrappers for Task
  4/5/6 tests and non-session maintenance callers.

Therefore a mutation that commits before revoke is removed by `purgePairTx`; a
mutation whose transaction begins after revoke cannot authorize against a
rebound pair and returns `ErrNotFound`.

### Bounded retained-token cleanup and migration

- `ConfirmPending` atomically writes `pair_id -> retained pair_token` with the
  confirmed pair, both device indexes, and the committed pending record.
- `DeletePending` removes the direct index transactionally when the committed
  retained record expires.
- Revoke performs one direct index lookup and deletes only that retained token;
  it never scans attacker-growable `pair_pending` state.
- `NewPairStore` performs a versioned, one-time compatibility migration for
  pre-index databases. After the marker is committed, later construction is
  constant-time and does not decode pending records.
- Index collisions and corrupt indexed records fail closed. All index and pair
  changes roll back with a failed mailbox purge.

### Socket-close assertion

The end-to-end close assertion now fails on `net.Error.Timeout`. It accepts only
EOF or terminal Gorilla close errors and independently verifies that both exact
old-generation registrations are absent.

## Behavior proved

- Either paired device can authenticate and revoke.
- Wrong-key and unpaired JWT subjects cannot revoke.
- Both device indexes, confirmed state, capabilities, floor, retained token,
  both mailbox directions, counters, sequences, statuses, and cursors are
  removed in one Bolt transaction.
- Both old active sockets receive terminal connection closure.
- A JWT authenticated before revoke cannot register a surviving old socket.
- A legitimate rebound socket in the disconnect gap is not closed.
- An old in-flight put cannot recreate purged mailbox state or target a rebound
  pair.
- An old in-flight ACK cannot delete a rebound pair's item, even with the same
  device IDs, message ID, and digest.
- An old generation cannot trigger the expiry sweep against rebound state.
- Revoked signing keys fail new JWT authentication.
- The devices can be rebound with new keys and clean capability/sequence state.
- Direct revoke cost is independent of unrelated pending-token count.

## GREEN and gate evidence

Focused retained-token and expiry store GREEN:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix-go-cache \
  go test ./internal/store \
  -run 'TestExpiredMailboxSweepRejectsRevokedSessionAfterRebind|Revoke|RetainedToken' \
  -count=1
ok github.com/twinotify/relay/internal/store 3.761s
```

Focused revocation/handoff server GREEN:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix-go-cache \
  go test ./internal/server \
  -run 'TestWebSocketHelloHandoffSurvivesFailedAcceptedWrite|TestWebSocketHelloHandoffPreservesSequenceAcrossReverseAcceptedWrites|Revoke|PairScopedHub' \
  -count=1
ok github.com/twinotify/relay/internal/server 1.351s
```

Full non-race compatibility GREEN:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix-go-cache \
  go test ./internal/store ./internal/server -count=1
ok github.com/twinotify/relay/internal/store 10.359s
ok github.com/twinotify/relay/internal/server 23.579s
```

Focused correction race stress GREEN:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix-go-cache \
  go test ./internal/store ./internal/server \
  -run 'Revoke|RetainedToken|ExpiredMailboxSweep|PairScopedHub' \
  -race -count=10
ok github.com/twinotify/relay/internal/store 40.138s
ok github.com/twinotify/relay/internal/server 15.238s
```

Required full package race GREEN, bounded to detect hangs:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix-go-cache \
  go test ./internal/store ./internal/server -race -count=1 -timeout=2m
ok github.com/twinotify/relay/internal/store 11.066s
ok github.com/twinotify/relay/internal/server 31.094s
```

Required root repository gate GREEN:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix-go-cache make relay-test
?  github.com/twinotify/relay/cmd/relay [no test files]
ok github.com/twinotify/relay/internal/server 33.075s
ok github.com/twinotify/relay/internal/store 12.553s
```

Vet GREEN:

```text
cd relay && GOCACHE=/private/tmp/phone-sync-task7-fix-go-cache go vet ./...
exit 0; no output
```

Final whitespace and staged-scope checks are rerun after this report is staged
and recorded in the handoff.

## Diff and self-review

- No Task 8, Android, UI, schema, or deployment work is present.
- Retention remains 24 hours from first durable acceptance.
- The maximum encrypted envelope remains 1 MiB.
- Per-recipient caps remain 2,000 items and 128 MiB.
- Accepted items are never evicted; live queue pressure still forces reconnect
  and durable drain.
- `relay.accepted` remains post-Bolt-commit durable acceptance, not peer
  delivery.
- The relay continues to store and route opaque ciphertext only. No canonical
  IDs, sequences, titles, text, actions, icons, or notification plaintext are
  inspected or logged.
- Capability-floor advancement remains monotonic; v1 is not weakened after a
  persisted floor of 2.
- Task 6 collision-safe confirmation and same-token completion replay remain
  intact. The new retained-token index is written in that same transaction.
- Producer-visible outbound channels remain open; replacement and disconnect
  cancel the client-owned `done` channel only.
- Generation checks occur at the actual Bolt mutation/transfer boundary, not
  only during initial JWT parsing.

One diagnostic run after making pair matching strict was interrupted at 140
seconds. It identified two old tests that manually registered unbound synthetic
clients; their expected handoff deliveries could no longer match a production
pair. Those fixtures now use their existing `mailbox-test-pair` ID. The focused
tests, full non-race suite, focused race stress, full race suite, and root gate
all pass afterward. This was a fixture-compatibility diagnosis, not a retained
production hang.

## Concerns and operational notes

- The one-time legacy retained-token migration scans pre-index pending state at
  startup because no direct index exists in the old schema. It is versioned and
  never runs in authenticated revoke or after the marker is committed. Migration
  corruption fails startup closed instead of silently orphaning token state.
- Localhost WebSocket tests require managed localhost-listener permission. All
  server and race gates above ran with that permission.
- The brief's literal `cd relay && make relay-test` remains a command-location
  mismatch: the target exists in the root Makefile. The required root
  `make relay-test` passed and no out-of-scope relay Makefile was added.

## Instruction and design-law re-check

The approved Task 7 brief, reliable-delivery design, review, and full supplied
anti-slop law were re-read before handoff. This correction is Go persistence,
authentication, concurrency, and tests only. No interface layout, typography,
color, iconography, animation, clipping, hover, entrance visibility, or other UI
rule applies. The backend result preserves the frozen delivery, privacy,
retention, quota, backpressure, and compatibility constraints listed above.

---

## Second correction: rebound registration isolation

### History and scope

The correction re-review found one remaining availability race in the range
`7c24d39ee12a31a1b5effdb0d3c48acd7b6cbf3c..1a8bee75cd5ddc688f75ebee84fe3944b76dfbff`.
This final correction started from:

```text
base/head: 1a8bee75cd5ddc688f75ebee84fe3944b76dfbff
subject:   fix(relay): linearize revocation across pair generations
branch:    main
```

Only these directly required paths changed:

- `relay/internal/server/client_hub.go`
- `relay/internal/server/ws.go`
- `relay/internal/server/revoke_test.go`
- `.superpowers/sdd/task-7-report.md`

No Task 8 or unrelated work is included.

### Deterministic RED evidence

The combined real-WebSocket regression was added before production changes. It
pauses a P1 request after JWT authentication and upgrade but before hub
registration, fully revokes P1, confirms P2, registers and exercises a P2
socket, then resumes P1.

Observed RED:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix2-go-cache \
  go test ./internal/server \
  -run 'TestDelayedRevokedRegistrationCannotEvictReboundGeneration|TestClientHubRegisterPairReplacesOnlySameGeneration' \
  -count=1 -timeout=20s
--- FAIL: TestDelayedRevokedRegistrationCannotEvictReboundGeneration
    revoke_test.go:326: delayed old generation evicted the rebound registration
FAIL github.com/twinotify/relay/internal/server
```

A second hub-level test covers the inverse edge: an old different generation is
still registered when a later generation attempts registration. It also failed
against the pre-fix implementation:

```text
--- FAIL: TestClientHubRegisterPairRejectsDifferentGenerationWithoutEviction
    revoke_test.go:398: different-generation registration was not rejected
FAIL github.com/twinotify/relay/internal/server
```

The same-generation replacement characterization passed during RED, proving the
new failure was limited to cross-generation eviction rather than ordinary
reconnect behavior.

### Minimal implementation

- Hub registration now compares the current and incoming pair IDs while holding
  the existing hub mutex.
- If both IDs are non-empty and different, the incoming client is stopped and
  returned as rejected without stopping, replacing, or unregistering the
  current generation.
- The WebSocket handler observes the registration result and returns before
  session validation, writer goroutines, or frame processing when rejected.
- Same-generation reconnect still replaces and stops the prior client. The old
  client's deferred unregister cannot remove its replacement.
- Unbound registrations remain available for isolated legacy hub tests, but an
  unbound client still cannot satisfy pair-scoped send or lookup.
- When a valid new generation arrives while a different old generation remains
  current, it is rejected and closed without evicting the old registration. It
  retries after the old generation's exact disconnect/unregister completes.
  This resolves the edge without store access under the hub mutex or any
  cross-generation eviction.

The hub mutex is never held across Bolt access or network I/O. Rejected and
replaced clients are cancelled only through their owned `done` channel; no
producer-visible outbound channel is closed.

### Behavior proved

- Delayed revoked P1 cannot evict a validated, usable P2 socket.
- P1 closes terminally when released after rebind.
- P2 remains the exact current registration and receives P2-targeted frames
  before and after P1 resumes.
- P1-targeted frames cannot cross into P2, and P2 frames cannot reach P1.
- A rejected different-generation client is stopped, never installed, and its
  unregister cannot orphan the current client.
- Same-generation reconnect replaces the prior client, routes to the
  replacement, and leaves no registration after final unregister.
- Existing revoke-before-register and revoke-disconnect-gap ordering tests
  remain green.

### GREEN and gate evidence

Focused GREEN:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix2-go-cache \
  go test ./internal/server \
  -run 'TestDelayedRevokedRegistrationCannotEvictReboundGeneration|TestClientHubRegisterPair(ReplacesOnlySameGeneration|RejectsDifferentGenerationWithoutEviction)|TestRevokeRejectsAuthenticatedSocketRegisteringAfterCommit|TestRevokeDisconnectDoesNotCloseReboundGeneration' \
  -count=1 -timeout=30s
ok github.com/twinotify/relay/internal/server 0.823s
```

Required focused race stress, twenty repetitions:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix2-go-cache \
  go test ./internal/server \
  -run 'Revoke|DelayedRevokedRegistration|ClientHubRegisterPair' \
  -race -count=20 -timeout=3m
ok github.com/twinotify/relay/internal/server 20.874s
```

Required full package race:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix2-go-cache \
  go test ./internal/store ./internal/server -race -count=1 -timeout=2m
ok github.com/twinotify/relay/internal/store 10.580s
ok github.com/twinotify/relay/internal/server 29.008s
```

Required root repository gate:

```text
GOCACHE=/private/tmp/phone-sync-task7-fix2-go-cache make relay-test
?  github.com/twinotify/relay/cmd/relay [no test files]
ok github.com/twinotify/relay/internal/server 32.565s
ok github.com/twinotify/relay/internal/store 9.990s
```

Vet:

```text
cd relay && GOCACHE=/private/tmp/phone-sync-task7-fix2-go-cache go vet ./...
exit 0; no output
```

Final gofmt, whitespace, scope, and committed-range checks are recorded in the
handoff after staging.

### Final constraint re-check

This registration-only correction does not change retention, envelope size,
mailbox quotas, durable acceptance, backpressure, ciphertext privacy,
capability-floor behavior, pairing persistence, or mailbox ordering. The full
anti-slop law was rechecked; no UI implementation is present, so its interface
rules are not applicable.

### Commit handoff status

```text
intended subject: fix(relay): isolate rebound client generations
commit result:    blocked by the managed approval reviewer
staged base/head: 1a8bee75cd5ddc688f75ebee84fe3944b76dfbff
```

The reviewer stated that it did not see trusted authorization for the exact
default-branch mutation and prohibited workarounds. No retry or indirect commit
path was attempted. The exact four-path correction remains staged for the
primary agent, with no unstaged changes.
