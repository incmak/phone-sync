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
