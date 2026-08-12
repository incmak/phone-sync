# Task 8 Report: Bound Public Inputs and Persist Production Data

Date: 2026-08-11

## Result

Task 8 adds strict pairing HTTP decoding, exact pairing public-key and display-name bounds, injected per-IP and per-token token buckets, an atomic global pending-pair cap with a bounded TTL index sweep, explicit HTTP server timeouts, one cancellable maintenance loop, persistent development storage, and a TLS-only production Caddy topology.

No Android or Task 9 files were changed. The existing 24-hour relay retention, 1 MiB envelope limit, per-recipient 2,000 item / 128 MiB quota, explicit mailbox backpressure, opaque relay payload model, v2 durable-acceptance semantics, protocol-floor behavior, generation authorization, and revoke semantics were not weakened.

## TDD RED Evidence

Tests and deployment assertions were added before production implementation.

Command:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/server ./internal/store -run 'Limit|Rate|PendingCap|PendingPairSweep|HTTPServerTimeout|PairJSON|PairKey' -count=1
```

Observed RED: exit 1. The server test package failed to compile because `DefaultConfig`, `Config`, `NewWithConfig`, `NewHTTPServer`, and the injected limiter did not exist. The store test package failed to compile because `PendingPairLimits`, `NewPairStoreWithLimits`, and `ErrPendingPairLimit` did not exist. These were the intended missing production contracts.

During pre-commit production-topology review, an additional proxy-specific RED test was added:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/server -run 'TrustedProxy' -count=1
```

Observed RED: exit 1 because `Config.TrustProxyHeaders` did not exist. This proved the initial direct-peer limiter could not distinguish public clients behind Caddy. After implementation, the trusted-proxy rate tests pass while direct traffic still cannot bypass the limiter with spoofed forwarding headers.

The final limiter boundary recheck changed the idle-cleanup expectation to eviction at exactly 10 minutes. The focused test then failed with two surviving entries, after which the comparison was corrected from `>` to `>=` and the test passed.

Command:

```text
./deploy/assert-compose.sh
```

Observed RED: exit 1 with:

```text
missing production Compose file: .../deploy/docker-compose.prod.yml
```

Pre-commit Caddy review added a further deployment RED assertion requiring explicit `handle` blocks around the allowlisted relay matcher and the fallback 404. The assertion initially exited 1 against a bare `respond 404`, which Caddy directive sorting could place ahead of the proxy. The final Caddyfile uses mutually exclusive handle blocks and the assertion passes.

An earlier first Go invocation without the task-local `GOCACHE` failed before compilation because the managed sandbox denied writes to the macOS Go build cache. It is recorded as environment evidence, not as the behavioral RED run.

## GREEN Evidence

Focused behavior gate:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/server ./internal/store -run 'Limit|Rate|PendingCap|PendingPairSweep|HTTPServerTimeout|PairJSON|PairKey|JTICleanup|MaintenanceLoop' -count=1
```

Result: exit 0; both packages passed.

Required focused full race gate:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/store ./internal/server -race -count=1
```

Result: exit 0 after granting loopback-bind permission required by `httptest` WebSocket servers.

```text
ok github.com/twinotify/relay/internal/store 15.526s
ok github.com/twinotify/relay/internal/server 45.446s
```

Root relay gate:

```text
GOCACHE=/tmp/phone-sync-go-cache make relay-test
```

Result: exit 0.

```text
?  github.com/twinotify/relay/cmd/relay [no test files]
ok github.com/twinotify/relay/internal/server 45.706s
ok github.com/twinotify/relay/internal/store 14.273s
```

Static Go analysis:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go vet ./...
```

Result: exit 0 with no output.

Formatting and diff checks:

```text
gofmt -w <all changed Go files>
git diff --check
```

Result: exit 0 with no diff errors.

## Deployment Evidence

Development Compose:

```text
docker compose -f deploy/docker-compose.yml config
```

Result: exit 0. Resolved relay environment contains `BOLT_PATH: /data/twinotify-relay.db`, `/data` is a named volume, and development alone publishes `8080`.

Production Compose:

```text
TWINOTIFY_DOMAIN=relay.example.test docker compose -f deploy/docker-compose.prod.yml config
```

Result: exit 0. Resolved relay service has only `expose: 8080`, is attached only to the internal relay network, persists `/data`, and explicitly enables trusted proxy headers. Caddy alone publishes TCP 80/443 and joins the edge and relay-internal networks.

Required-domain negative assertion:

```text
env -u TWINOTIFY_DOMAIN docker compose -f deploy/docker-compose.prod.yml config
```

Result: expected non-zero interpolation failure stating `TWINOTIFY_DOMAIN is required`.

Combined assertion target:

```text
./deploy/assert-compose.sh
```

Result: exit 0. It verifies both persistent Bolt paths, trusted proxy enablement, no production relay host port, Caddy 80/443 publication, explicit allowlist/fallback handle blocks, and absence of `tls internal` from the production Caddyfile.

## Implemented Bounds

- Every JSON-body pairing endpoint wraps `r.Body` with `http.MaxBytesReader(w, r.Body, 64<<10)`.
- Pairing JSON uses `DisallowUnknownFields` and requires EOF after the first value.
- X25519 and Ed25519 public keys must each base64-decode to exactly 32 bytes.
- Display names must be valid UTF-8 and at most 64 encoded bytes.
- Unauthenticated pairing routes consume a token from a mutex-protected bucket keyed by normalized direct remote IP; decoded pair tokens consume a separate bucket shared across remote IPs. Production explicitly enables trusted Caddy forwarding headers, while direct development traffic ignores spoofable forwarding headers.
- Rate-limit configuration and time are injected through `server.Config`; responses are 429 with integer `Retry-After` seconds.
- Limiter entries are evicted at 10 minutes idle by default.
- The pending-pair count and expiry index are updated in the same Bolt transaction as inserts and deletes. New inserts at the cap abort before writing any record or index entry.
- TTL maintenance reads only the earliest expiry-index entries and deletes at most the configured batch; ordinary pairing requests do not scan the global pending bucket.
- HTTP server bounds are 5s header, 15s read, 15s write, 75s idle, and 16 KiB maximum headers.
- The single periodic maintenance worker runs mailbox expiry, delivery-status expiry, bounded pairing TTL sweep, JTI cleanup, and limiter cleanup. Main cancels and joins it before HTTP shutdown completes and before deferred Bolt close.

## Scope and Expansion

Brief-owned changes:

- Created `relay/internal/server/http_limits.go` and `http_limits_test.go`.
- Modified `relay/internal/server/pair.go`, `pair_handshake.go`, and `relay/cmd/relay/main.go`.
- Modified development Compose and Caddy configuration; created production Compose.

Explicitly permitted adjacent expansions:

- `relay/internal/server/server.go`: injected Task 8 configuration, limiter ownership, route middleware, and maintenance lifecycle.
- `relay/internal/server/pair_notify.go`: applied the required per-IP/token bound to the unauthenticated pairing WebSocket route and used the injected clock.
- `relay/internal/server/jwt_auth.go`: added the maintenance-loop JTI cleanup operation.
- `relay/internal/store/pair_store.go` and `pair_store_test.go`: implemented and tested the persistence-bound atomic cap, counter migration, expiry index, bounded sweep, and revoke/delete counter maintenance.
- `Makefile` and new `deploy/assert-compose.sh`: added the requested deployment assertion target.

No pre-existing user or other-agent changes were reverted. The working tree was clean at Task 8 start.

## Environment and Tools

- Go: `go1.26.5 darwin/arm64`.
- Docker: `29.4.0`.
- Docker Compose: `v5.1.2`.
- Docker was available and both Compose configurations were resolved, not merely inspected statically.
- The `caddy:2-alpine` image was not present locally, so an additional containerized `caddy validate` check was skipped without pulling an unrequested image. Compose interpolation/topology and Caddyfile static assertions ran successfully.
- `shellcheck` was not available.
- The managed sandbox denied local socket binds on the first full `httptest` race run. The exact same command passed after approved loopback-bind execution.
- A task-local Go build cache under `/tmp` was used because the managed sandbox cannot write the default macOS Go cache.

## Self-review

- Confirmed input-size errors distinguish 413 from ordinary malformed JSON 400 responses.
- Confirmed limiter IP normalization removes the source port and canonicalizes parsed IPv4/IPv6 values.
- Confirmed forwarded client IPs are used only when the injected trusted-proxy setting is enabled; the production internal-only relay enables it, while development leaves it disabled.
- Confirmed identical pairing-init retries remain idempotent and do not consume additional pending capacity.
- Confirmed cap enforcement is transaction-serialized under concurrent inserts and a rejected insert leaves both logical count and Bolt file size unchanged.
- Confirmed retained pairing tokens removed by expiry or revoke decrement the same durable counter and remove their expiry/index records atomically.
- Confirmed production relay has no `ports` mapping and Caddy contains no `tls internal` directive.
- Confirmed Caddy only proxies `/health`, `/pair/*`, and `/ws`; unmatched paths return 404.
- Confirmed no code path inspects relay plaintext, canonical IDs, sequences, titles, text, actions, or icons.
- Confirmed the anti-slop design law has no user-interface surface in this task. No UI, decorative motion, typography, layout, or interactive visual control was introduced.
- An independent read-only code review initially blocked the bare Caddy fallback ordering. The issue was fixed with mutually exclusive handle blocks; the reviewer re-read the current tree and returned `CLEAR / APPROVE` with no remaining blocker.

## Concerns

- The production pending-pair cap defaults to 10,000 because the approved design mandates a global cap but does not specify its numeric value. It remains explicitly injectable for deterministic tests and future operational tuning.
- The trusted-proxy switch is safe in the supplied production topology because relay port 8080 is internal and Caddy is its only peer. Any future topology that adds another relay-internal service must preserve that trust boundary or replace the boolean with an explicit trusted-proxy CIDR allowlist.

## Commit Status

The exact Task 8 scope was staged and `git diff --cached --check` passed. The mandated command:

```text
git commit -m "fix(relay): bound inputs and persist production state"
```

was rejected by the managed approval reviewer because it mutates the default `main` branch and this implementation subagent could not supply trusted root-user approval. No workaround was attempted. The reviewed Task 8 files remain staged for the root agent or user to commit with the exact subject. The review agent's separate `.omo/evidence/task-8-code-review.md` remains untracked and is intentionally excluded.

---

## Formal Review Corrections (2026-08-12)

The formal Task 8 review returned four Important findings. This correction addresses all four without changing Task 4-7 delivery, authorization, privacy, protocol-floor, retention, or backpressure semantics. No Task 9 or Android work was started.

### Correction RED evidence

I1 bounded limiter and JTI state:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/server -run 'GlobalBudget|CleanupIsBatched|JTICacheBudget|JTICacheConcurrent' -count=1
```

Observed RED: the tests failed to compile because limiter/JTI `MaxEntries`, `CleanupBatch`, configured constructors, cardinality accessors, and capacity errors did not exist. The tests were written before the bounded list/map implementations.

I2 bounded maintenance and shutdown lifecycle:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/store ./internal/server ./cmd/relay -run 'ExpiryMaintenanceUsesDedicated|MaintenanceStart|GracefulStop' -count=1
```

Observed RED: missing dedicated live/status expiry buckets and batch APIs, missing maintenance ownership/config seams, and missing `gracefulStop`. The lifecycle test proved a second start would create a separate worker and that the shutdown deadline had previously been created before the maintenance join.

I3 restart integrity:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/store -run 'PairStoreRestartIntegrity|PairStoreSweepFailsClosed|PairStoreIndexesRemainExact' -count=1
```

Observed RED: build failure for missing `OpenPairStoreWithLimits` and exact index validator. After those APIs existed, the malformed-expiry subtest remained RED because an incorrect cursor expression skipped entries; fixing the cursor made the whole corruption matrix GREEN.

I4 deployment assertions:

```text
cd relay
GOCACHE=/tmp/phone-sync-go-cache go test ./cmd/deployassert -count=1
```

Observed RED: build failure for missing `validateComposeJSON` and `validateCaddyfile`. The mutation tests preceded the structural validator implementation.

The first full race correction gate found an additional compatibility RED:

```text
--- FAIL: TestJTICacheEvictionAfter2TTL
expected accept after 2*ttl, got jti replay
```

Root cause: the bounded rewrite removed the previous O(1) lazy expiry check for the same JTI. `CheckAndSet` now removes only that exact expired entry before replay/capacity checks; global cleanup remains separately batched and bounded.

### I1 result

- Pairing rate state is an injected-capacity map plus age-ordered list. Unknown IP/token keys fail closed at capacity; no live exhausted key is evicted or refreshed into a new burst.
- Cleanup examines at most the configured batch and releases the mutex between units. Unique-key concurrent flood tests prove exact maximum cardinality and concurrent request progress.
- JTI replay state uses the same bounded map/list pattern with independent injected capacity and cleanup batch. Capacity failure is distinct from replay internally and both fail authentication closed.
- Focused race tests cover overflow, exhausted-key preservation, bounded inspection, idle eviction, concurrent `allow`, concurrent `CheckAndSet`, and exact cardinality.

### I2 result

- Live mailbox records and canonical delivery statuses have separate expiry-ordered Bolt indexes. Put, ACK, expiry, terminal-status cleanup, purge, migration/reopen, conflict rollback, and status-removal paths maintain reciprocal entries.
- `ExpireBatch` and `ExpireStatusesBatch` visit at most the injected batch. Pair expiry, JTI cleanup, and limiter cleanup are also bounded per tick, with cancellation checks between every maintenance unit.
- `StartMaintenance` is single-owner/idempotent and returns the same joinable completion channel on repeated starts.
- Shutdown cancels and joins maintenance first, then creates a fresh HTTP shutdown deadline. Deferred Bolt close occurs only after HTTP shutdown and handler completion.
- Exact-index tests enumerate records and both expiry buckets after migration, reopen, ACK, expiry, rollback, and purge.

### I3 result

- A one-time schema-v2 migration rebuilds derived pending expiry/count and retained-token indexes from authoritative pending records, then persists the schema and full active pending-limit configuration including TTL.
- Once the v2 marker exists, startup does not repair silently. It validates the counter, every pending/expiry reciprocal, every retained-token/pending reciprocal, record identities, markers, and persisted configuration and returns `ErrPairStoreCorrupt` on mismatch.
- Missing/orphan/malformed expiry entries, missing/extra retained mappings, valid-but-wrong counters, and changed TTL/config all fail closed in deterministic reopen tests.
- A sweep validates its head before testing time or counting removal, so an orphan head returns an error with zero successful removals.
- Production uses error-returning `OpenPairStoreWithLimits`, `OpenMailboxStore`, and `NewWithConfigChecked`; compatibility `New*` wrappers retain panic-on-fatal-init behavior for existing callers.

### I4 result

- `deploy/assert-compose.sh` resolves both Compose files with `config --format json`, requires the missing-domain command to fail, and passes the JSON to `relay/cmd/deployassert`.
- The structural Compose validator asserts exact service ownership of environment, production service sets, networks and `internal` flag, relay `/data` volume target, Caddy read-only Caddyfile bridge, no direct relay publication, and only Caddy TCP 80/443.
- The brace-aware Caddy parser asserts one domain site, exact `/health`, `/pair/*`, and `/ws` allowlist, `relay:8080` upstream, a 404 fallback, balanced/expected blocks, and no internal TLS.
- Mutation tests prove moved environment ownership, non-internal networking, an extra Caddy port, a direct relay port, bind-mounted relay data, a non-bind Caddy bridge, wrong upstream, missing/extra allowed path, missing fallback, and `tls internal` all fail.
- The official `caddy:2-alpine` adapter was also run against the production Caddyfile. Adapted JSON contained the exact three path matches, upstream `relay:8080`, and fallback status 404, with no warning after canonical formatting.

### Fresh correction GREEN evidence

Focused race suites:

```text
ok github.com/twinotify/relay/cmd/deployassert 1.762s
ok github.com/twinotify/relay/internal/server 1.715s
ok github.com/twinotify/relay/internal/store 2.102s
```

Required package race gates, run separately to retain exact results:

```text
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/store -race -count=1
ok github.com/twinotify/relay/internal/store 12.544s

GOCACHE=/tmp/phone-sync-go-cache go test ./internal/server -race -count=1
ok github.com/twinotify/relay/internal/server 24.065s
```

Root gate:

```text
GOCACHE=/tmp/phone-sync-go-cache make relay-test
```

Final result after the last structural deployment hardening: exit 0. It synchronized schemas and ran `go test ./... -race -count=1`:

```text
ok github.com/twinotify/relay/cmd/deployassert 1.301s
ok github.com/twinotify/relay/cmd/relay 1.571s
ok github.com/twinotify/relay/internal/server 27.743s
ok github.com/twinotify/relay/internal/store 12.154s
```

Static and deployment gates:

```text
cd relay && GOCACHE=/tmp/phone-sync-go-cache go vet ./...
cd relay && GOCACHE=/tmp/phone-sync-go-cache go test ./cmd/deployassert -race -count=1
GOCACHE=/tmp/phone-sync-go-cache make deployment-test
docker compose -f deploy/docker-compose.yml config --format json
TWINOTIFY_DOMAIN=relay.example.test docker compose -f deploy/docker-compose.prod.yml config --format json
env -u TWINOTIFY_DOMAIN docker compose -f deploy/docker-compose.prod.yml config --format json
docker run --rm ... caddy:2-alpine caddy adapt --config /etc/caddy/Caddyfile --adapter caddyfile
gofmt -w <all changed Go files>
git diff --check
```

Results: vet, mutation suite, deployment target, both positive Compose resolutions, Caddy adaptation, formatting, and diff checks exited 0. The missing-domain command failed as required with `TWINOTIFY_DOMAIN is required`.

### Correction scope and environment

Correction files are limited to relay/store/server lifecycle and tests, `relay/cmd/relay`, the new `relay/cmd/deployassert`, `deploy/assert-compose.sh`, the formatting-only Caddyfile update, and this report. The user-owned `.gitignore`, untracked `CLAUDE.md`, and untracked `.omo/` were neither modified nor staged by this correction.

Docker Engine access initially required managed escalation. After approval, Compose v5 resolution and the official Caddy 2 Alpine adapter both ran successfully. A task-local `/tmp` Go cache remained necessary because the managed sandbox cannot write the default macOS Go build cache.

### Correction self-review and deferred minors

- Rechecked that no maintenance request path performs a global attacker-controlled scan; full consistency scans occur only at startup.
- Rechecked transactional ordering: derived index changes share the same Bolt write transaction as authoritative record changes and rollback together.
- Rechecked that bounded cleanup never admits an unknown key by evicting live state, and per-unit mutex release permits writers to progress.
- Rechecked maintenance ownership, cancellation boundaries, shutdown ordering, and deferred Bolt close.
- Rechecked deployment service ownership and route semantics from resolved/adapted structures rather than source text.
- Rechecked all frozen relay constraints: 24-hour first-accept/status retention, 1 MiB opaque encrypted envelope maximum, 2,000/128 MiB recipient quotas, explicit backpressure, durable v2 acceptance, protocol-floor monotonicity, generation-scoped authorization, and revoke atomicity.
- Canonical base64 preflight length checks and explicit trusted-proxy CIDR/direct-peer validation remain deferred minor hardening. Exact decoded key sizes are already enforced, and the supplied production network keeps relay internal behind Caddy. These should be revisited in the final relay-wide review without destabilizing this correction.
- Re-read the anti-slop design law point by point. This correction has no UI, typography, layout, animation, or interactive visual surface, so none of its interface-specific prohibitions apply.

### Final fail-closed re-review corrections

The formal correction re-review found two remaining runtime/startup gaps. Deterministic tests were added first.

Observed RED:

```text
--- FAIL: TestPairStoreSweepFailsClosedWhenExpiryBucketDisappearsAfterOpen
sweep without expiry bucket = 0, <nil>; want 0, ErrPairStoreCorrupt

--- FAIL: TestMailboxStoreMarkerPresentExpiryIndexCorruptionFailsOpen
marker-present mailbox expiry corruption was silently trusted
```

The mailbox RED reproduced missing live/status index buckets, a missing reciprocal live entry, orphan and malformed status entries, and a mismatched live entry.

Final behavior:

- `PairStore.SweepExpired` returns wrapped `ErrPairStoreCorrupt` if its required expiry bucket disappears after construction; it no longer reports silent success.
- `OpenMailboxStore` performs one migration/rebuild only when the marker is absent. Every checked open then validates the marker, both index buckets, every authoritative record-to-index reciprocal, every index-to-record reciprocal, canonical identity/expiry time, and exact counts.
- Marker-present mailbox expiry corruption fails production initialization before handlers start. It is never silently repaired or left for the maintenance loop to log forever.
- ACK, terminal-status cleanup, status reporting cleanup, and purge require the exact reciprocal expiry entry before deleting authoritative state. Missing runtime derived state therefore aborts the Bolt transaction instead of being conditionally skipped.

Focused final race gate:

```text
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/store \
  -run 'SweepFailsClosedWhenExpiryBucket|MarkerPresentExpiryIndexCorruption|MaintenanceExpiryIndexes|ExpiryMaintenance|Purge|Ack' \
  -race -count=1
ok github.com/twinotify/relay/internal/store 2.662s
```

Fresh full final gates after these changes:

```text
GOCACHE=/tmp/phone-sync-go-cache go test ./internal/store ./internal/server -race -count=1
ok github.com/twinotify/relay/internal/store 11.849s

GOCACHE=/tmp/phone-sync-go-cache make relay-test
exit 0

cd relay && GOCACHE=/tmp/phone-sync-go-cache go vet ./...
exit 0

cd relay && GOCACHE=/tmp/phone-sync-go-cache go test ./cmd/deployassert -race -count=1
ok github.com/twinotify/relay/cmd/deployassert 1.281s

GOCACHE=/tmp/phone-sync-go-cache make deployment-test
exit 0
```

Both positive Compose JSON resolutions, the missing-domain negative, official Caddy adaptation, gofmt, and `git diff --check` were also rerun and passed after the final fail-closed changes.

Formal re-review verdict: `APPROVE / CLEAR`. The reviewer independently reran the complete race gate (`store 22.539s`, `server 53.906s`, `cmd/relay 2.400s`, `cmd/deployassert 1.732s`), vet, deployment assertion, and diff check, and recorded no remaining Critical or Important finding in `.superpowers/sdd/task-8-review.md`.
