# Task 7 Evidence Report: Authenticated Pair Revocation

## Scope

Implemented only Reliable Relay Task 7:

- authenticated `POST /pair/revoke`;
- atomic `PairStore.Confirm` collision protection;
- atomic `PairStore.RevokeByDevice` authorization, negotiation, retained-token, mailbox, counter, and tombstone purge;
- `ClientHub.Disconnect` for both paired devices;
- focused store and server regression coverage.

No Task 8 work or unrelated files were changed.

## RED evidence

Tests were added before production code in:

- `relay/internal/store/pair_store_test.go`;
- `relay/internal/server/revoke_test.go`.

The mandated focused command was run from `relay/` with a sandbox-safe Go build cache:

```text
GOCACHE=/private/tmp/phone-sync-go-cache go test ./internal/server ./internal/store -run Revoke -count=1
```

Observed feature failures before implementation:

```text
internal/store/pair_store_test.go:648:21: ps.RevokeByDevice undefined
internal/store/pair_store_test.go:742:18: ps.RevokeByDevice undefined
internal/store/pair_store_test.go:778:18: ps.RevokeByDevice undefined
--- FAIL: TestRevokeAllowsEitherPairedDevice
    status=404 body=404 page not found
--- FAIL: TestRevokeRejectsWrongOrUnpairedJWTWithoutMutation
    status=404 body=404 page not found
```

The localhost WebSocket RED case additionally reached the managed sandbox's expected local-listen restriction. Subsequent server and race gates were run with approved localhost execution.

## Implementation summary

- Reworked direct `PairStore.Confirm` to write the confirmed record and both device indexes in one Bolt update transaction.
- Direct confirmation now rejects pair-ID content changes and any device already indexed to a different confirmed pair with `ErrPairConflict`.
- Added `PairStore.RevokeByDevice`, which resolves either authenticated device's confirmed pair and performs one Bolt update transaction that:
  - calls store-internal `purgePairTx` for both mailbox directions;
  - removes the confirmed record and both device indexes;
  - removes both devices' stored capabilities;
  - removes the pair's persisted protocol floor;
  - removes retained committed pairing-token records;
  - returns the revoked pair only after transaction commit.
- Added rollback coverage using deliberately corrupt mailbox state to prove pair authorization, indexes, token state, and capability floor survive a failed mailbox purge.
- Added `ClientHub.Disconnect`, which unregisters and cancels the current connection without closing the producer-visible outbound channel.
- Added the JWT-authenticated `POST /pair/revoke` route. It atomically revokes store state, disconnects both devices, and returns HTTP 204.

## Behavior proved by tests

- Either paired device may authenticate and revoke.
- A wrong signing key or unpaired JWT subject receives HTTP 401 and cannot mutate the pair.
- Both device indexes and the confirmed pair disappear.
- Retained committed pairing-token state disappears.
- Capabilities and the v2 protocol floor do not leak into a rebound pair.
- Both durable mailbox directions, counters, acceptance sequences, and delivery tombstones are purged.
- A mailbox purge error rolls the whole revocation transaction back.
- Both active WebSockets close.
- JWTs signed by revoked keys fail authentication.
- The same device IDs can be rebound with new encryption/signing keys.
- A direct confirm cannot steal a live device binding; it succeeds only after revocation.

## GREEN and verification evidence

Focused store GREEN:

```text
GOCACHE=/private/tmp/phone-sync-go-cache go test ./internal/store -run Revoke -count=1
ok github.com/twinotify/relay/internal/store
```

Focused server GREEN with localhost access:

```text
GOCACHE=/private/tmp/phone-sync-go-cache go test ./internal/server -run Revoke -count=1
ok github.com/twinotify/relay/internal/server
```

Focused race GREEN:

```text
GOCACHE=/private/tmp/phone-sync-go-cache go test ./internal/server ./internal/store -run Revoke -race -count=1
ok github.com/twinotify/relay/internal/server
ok github.com/twinotify/relay/internal/store
```

Required package-wide race GREEN:

```text
GOCACHE=/private/tmp/phone-sync-go-cache go test ./internal/store ./internal/server -race -count=1
ok github.com/twinotify/relay/internal/store
ok github.com/twinotify/relay/internal/server
```

The brief's literal command was run:

```text
cd relay && make relay-test
make: *** No rule to make target `relay-test'. Stop.
```

This is a repository command-location mismatch: `relay/` has no Makefile, while the root `Makefile` defines `relay-test`. The actual repository gate was then run from the repository root and passed:

```text
GOCACHE=/private/tmp/phone-sync-go-cache make relay-test
?  github.com/twinotify/relay/cmd/relay [no test files]
ok github.com/twinotify/relay/internal/server
ok github.com/twinotify/relay/internal/store
```

Vet and whitespace gates:

```text
cd relay && GOCACHE=/private/tmp/phone-sync-go-cache go vet ./...
exit 0

git diff --check
exit 0
```

Fresh staged verification is recorded below before commit.

## Diff and self-review

- Production changes are limited to the Task 7-owned pair store, route registration, revocation handler, and client hub.
- Tests are limited to the Task 7-owned revocation test and pair-store test file.
- The store transaction reuses `purgePairTx`; it does not duplicate or weaken Task 4 mailbox semantics.
- No mailbox retention, size, quota, or acceptance behavior changed.
- No envelope parsing or relay-visible notification plaintext was added.
- Capability floor behavior is unchanged except that revocation intentionally deletes the revoked pair's floor.
- Confirmed-pair collision checks preserve Task 6's authenticated resumable pairing contract.
- Socket cancellation retains the existing no-channel-close producer safety model.
- No UI is involved. The anti-slop interface checklist is therefore not applicable beyond the required final instruction re-check.

## Concerns

- The task brief's `cd relay && make relay-test` path is stale or incorrect. The target passes from repository root. No Makefile was added under `relay/` because that would be out of Task 7 scope.
- Localhost WebSocket tests require execution outside the managed network sandbox; approval was obtained and all such gates passed.

## Final pre-commit verification

After staging only the seven scoped Task 7 paths, the required package race gate was run again from the final code state:

```text
GOCACHE=/private/tmp/phone-sync-go-cache go test ./internal/store ./internal/server -race -count=1
ok github.com/twinotify/relay/internal/store 6.224s
ok github.com/twinotify/relay/internal/server 26.167s
```

The final staged diff contains only the six authorized relay files plus this evidence report. `go vet ./...` and `git diff --cached --check` exit successfully.

## Commit status

The exact required commit was attempted on `main`:

```text
git commit -m "feat(relay): revoke pair authorization and mailboxes"
```

The managed approval reviewer rejected the history mutation as an unacceptable direct-to-main risk and explicitly prohibited workarounds. No commit was created. The staged implementation and ignored-but-force-staged report remain preserved.

```text
branch: main
base:   f06488fc56754ee6f13c5e36816e001c6a188e49
head:   f06488fc56754ee6f13c5e36816e001c6a188e49
```
