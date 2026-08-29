# Production Relay Hardening Design

**Date:** 2026-08-29

**Status:** Approved for implementation

**Scope:** The existing Go relay, Android relay-pairing client, production container, and single-node deployment operations

**Depends on:** `2026-08-09-reliable-delivery-foundation-design.md` and the implemented v1/v2 relay protocol

## 1. Goal

Prepare the existing relay for a Play Store launch without replacing its proven delivery model. The first public deployment remains a single Go process behind Caddy with one durable Bolt database. This release does not claim high availability or horizontal scaling.

The hardened relay must:

- fail closed when production-only configuration is absent or unsafe;
- require cryptographic approval from both phones for every new production pair;
- reject replayed authentication tokens after a process or container restart;
- preserve every accepted encrypted mailbox item until acknowledgement or expiry;
- reject new work before storage exhaustion instead of corrupting or evicting state;
- expose distinct liveness and readiness signals without exposing user data;
- stop accepting work and close active WebSockets during graceful shutdown;
- emit structured, privacy-safe logs and private aggregate metrics;
- run as a constrained, non-root, read-only container with writable data and backup volumes only;
- create consistent, retained Bolt snapshots and provide a verified restore path;
- deploy and roll back immutable images by digest;
- preserve v1/v2 protocol compatibility and stable pair IDs for a later Cloudflare Durable Objects implementation.

## 2. Selected Architecture

```text
Android clients
    |
    | HTTPS / WSS
    v
Caddy public TLS ingress
    |
    | private Docker network
    v
Go relay
    |
    +-- /data/twinotify-relay.db       authoritative Bolt database
    +-- /backups/*.db                  consistent retained snapshots
```

Only Caddy publishes ports 80 and 443. The relay listens on port 8080 inside an internal Docker network. Caddy proxies `/health`, `/health/*`, `/pair/*`, and `/ws`; it never proxies `/metrics`.

The initial launch is deliberately single-node. Bolt is the only writer, and one relay instance owns the volume. Running multiple replicas against the same database or advertising high availability is unsupported.

## 3. Security Design

### 3.1 Mutual pairing confirmation

The existing initiator signature remains byte-for-byte compatible:

```text
sig_A(pair_token || A_enc || A_sign || B_enc || B_sign)
```

Device B adds a responder signature after its user approves the displayed fingerprint:

```text
sig_B("twinotify-pair-confirm-b-v1\n" ||
      pair_token || A_enc || A_sign || B_enc || B_sign || sig_A)
```

`POST /pair/complete` carries the existing `confirmation_sig` plus `responder_confirmation_sig`. The relay verifies both signatures before committing a new pair. The domain prefix separates B's approval from all other signatures, and including `sig_A` binds B's approval to A's exact approval.

Production sets `REQUIRE_MUTUAL_PAIR_SIGNATURES=true` and refuses a missing responder signature. The published `pair-complete` schema and every new Android build always require and send it. Development may leave the switch false only for direct-handler migration fixtures that intentionally bypass schema validation; it is not a supported client compatibility mode. Any supplied responder signature is always verified. Existing confirmed pairs are not invalidated or rewritten.

### 3.2 Durable JWT replay protection

The relay stores only `SHA-256(jti)` and its expiry in Bolt. Admission is one Bolt transaction: reject an unexpired digest, reject when the configured replay budget is full, or insert the digest and its expiry index. Restarting the process or recreating the container therefore cannot reopen a token replay window.

Authentication additionally requires:

- EdDSA and the stored device signing key;
- non-empty `sub` matching a confirmed device;
- UUID `jti`;
- required numeric `iat` and `exp`;
- no more than 60 seconds between `iat` and `exp`;
- at most 30 seconds of future clock skew for `iat`;
- the existing pair generation still matching the authenticated session.

Replay records remain for twice the 60-second token lifetime. Cleanup is expiry-indexed and bounded. When the replay budget is full, admission transactionally removes the oldest expired indexed record before rejecting for capacity, so routine maintenance lag cannot strand the relay in a global authentication outage. Non-upgrade `/ws` requests are rejected before authentication can consume a JTI. Valid authentication is also admitted atomically against bounded per-device and normalized source-IP token buckets before the JTI write, limiting how much of the global replay budget one pair or network source can consume. Capacity failure rejects authentication; it never admits a token without recording it.

### 3.3 Production configuration

`TWINOTIFY_ENV=production` activates fail-closed validation. Production requires:

- an absolute `BOLT_PATH` outside `/tmp`;
- `TRUST_PROXY_HEADERS=true` because only Caddy may reach the relay;
- `REQUIRE_MUTUAL_PAIR_SIGNATURES=true`;
- a positive `MIN_FREE_DISK_BYTES`;
- a positive `MAX_OPEN_CONNECTIONS`;
- an absolute `BACKUP_DIR` distinct from the database directory;
- a positive backup interval and retention count.

Unknown boolean values, invalid integers, unsafe paths, and missing production values stop startup with a non-zero exit.

### 3.4 Privacy discipline

Application logs use JSON in production. They may include event name, status code, protocol version, bounded reason, duration, and aggregate counts. They must not include notification plaintext, ciphertext, nonces, JWTs, pair tokens, public keys, raw device IDs, message IDs, request bodies, or full network addresses.

Metrics are aggregate counters and gauges only. `/metrics` is reachable on the private relay network and is intentionally absent from Caddy's public route allowlist.

## 4. Capacity and Availability

### 4.1 Admission control

Existing per-recipient limits remain 2,000 items and 128 MiB. Before `relay.put` or a pairing state mutation, the server runs a storage admission check. When available bytes are below `MIN_FREE_DISK_BYTES`, it rejects:

- relay puts with `relay.rejected.reason = server_capacity`;
- pairing mutations with HTTP 503 and `Retry-After`;
- readiness with HTTP 503.

Accepted mailbox records are never evicted to recover capacity. A low-disk condition does not block acknowledgements, revocation, expiry cleanup, or backup cleanup because those operations release or preserve existing state.

The HTTP listener caps simultaneously accepted TCP connections at `MAX_OPEN_CONNECTIONS`. The OS backlog absorbs short bursts; excess connections wait or time out instead of allocating unbounded Go state.

### 4.2 Health endpoints

- `GET /health/live` returns 200 while the process can serve HTTP.
- `GET /health/ready` returns 200 only when shutdown has not begun, Bolt passes a read check, and the storage admission check passes.
- `GET /health` remains a backward-compatible alias for readiness.

Health responses include only status and the immutable build version. They contain no database path or capacity numbers.

### 4.3 Shutdown

On SIGINT or SIGTERM the process:

1. marks readiness false;
2. stops periodic maintenance and backup scheduling;
3. immediately starts HTTP shutdown, so new connections stop before any active backup worker is joined;
4. closes active WebSockets with service-restart code 1012;
5. waits up to 10 seconds for HTTP shutdown;
6. serializes shutdown with the final Bolt write admission point, so a pairing mutation or relay put still decoding when shutdown wins the gate is rejected instead of committing; Bolt closes only after all owned background workers have joined.

Clients retain their local outboxes and reconnect. Durable mailbox items remain in Bolt, so shutdown cannot create a false delivery acknowledgement.

## 5. Observability

The private Prometheus text endpoint exports bounded process-owned relay metrics:

- current active WebSocket connections;
- accepted and rejected relay puts by bounded reason;
- accepted and rejected pairing mutations by bounded stage and result;
- authentication rejection counts by bounded reason;
- maintenance and backup success/failure counters;
- readiness state.

Labels are closed enums defined in code. No user-controlled label value is allowed.

Production logs use `log/slog` JSON output. Development keeps readable text output. Security-relevant conflicts and operational failures are logged without user identifiers.

## 6. Backup, Restore, and Rollback

### 6.1 Automated snapshots

The running process creates a consistent Bolt snapshot with a read transaction and `Tx.CopyFile`. It writes a temporary file in `BACKUP_DIR`, fsyncs through Bolt's copy operation, atomically renames the file, validates it by opening read-only and running Bolt consistency checks, then removes snapshots beyond `BACKUP_RETENTION_COUNT`.

Snapshot names contain UTC time and the immutable build version. Permissions are `0600`. A failed backup leaves the authoritative database untouched and records a metric plus structured error.

Local snapshots protect against operator error and a bad application rollout. The deployment runbook also requires provider disk snapshots or an encrypted off-host copy before public launch because a backup volume on the same VM does not protect against VM loss.

### 6.2 Restore

Restore runs only while the relay service is stopped. The restore command:

1. opens and checks the selected snapshot read-only;
2. refuses a source or destination outside the explicit backup and data directories;
3. copies to a temporary file in the data directory with mode `0600`;
4. validates the temporary database;
5. renames the current database to a timestamped recovery copy;
6. atomically renames the validated temporary database into place.

The runbook starts the pinned image, waits for readiness, and performs an authenticated smoke scenario before deleting any recovery copy.

### 6.3 Immutable deployment

Production Compose requires `TWINOTIFY_RELAY_IMAGE` to be a complete image reference pinned by digest. CI builds the relay image, verifies it, emits an SBOM and provenance attestation, and publishes only from an explicit version tag or manual release job.

Deployment records the prior image digest. If readiness or the smoke check fails, the script restores the prior digest without changing the database. A schema or data migration that prevents binary rollback must be additive and documented before release. This hardening release adds only Bolt buckets and fields that older binaries ignore, so binary rollback remains possible.

## 7. Container and Proxy Hardening

The relay image remains distroless and non-root. The image creates `/data` and `/backups` with UID/GID 65532 so fresh named volumes are writable by the runtime user.

Production Compose applies:

- `read_only: true`;
- explicit `user: 65532:65532` for the relay;
- all Linux capabilities dropped;
- `no-new-privileges`;
- PID, memory, CPU, and file-descriptor limits;
- writable named volumes only at `/data` and `/backups`;
- bounded Docker JSON log rotation;
- a binary health check against `/health/ready`;
- Caddy dependency on relay readiness;
- no relay host port.

Caddy keeps automatic public certificates, removes server-identifying headers, applies conservative request-header limits, and proxies WebSocket traffic without buffering application content.

## 8. Verification and Release Evidence

Every behavior change follows red-green TDD. The automated gate includes:

- mutual-signature positive, missing, wrong-key, wrong-transcript, and retry tests;
- Android transcript/signature fixture parity with Go;
- JTI replay rejection across Bolt close and reopen;
- malformed and overlong JWT claim tests;
- low-disk readiness, pairing, and relay-put backpressure tests;
- liveness/readiness shutdown transition tests;
- bounded metrics and privacy scans;
- consistent backup, retention, corruption rejection, and restore tests;
- graceful WebSocket shutdown and reconnect-safe mailbox tests;
- production config fail-closed tests;
- Compose security invariant tests;
- a real container startup and health smoke test;
- `gofmt`, `go vet`, race tests, schema fixtures, Android type checks, and relevant Kotlin JVM tests.

The release claim remains limited by evidence. Host automation cannot replace the required physical Pixel and Samsung scenarios, Doze testing, network handoff, battery measurement, EAS signing, Play Integrity inputs, or a live public TLS deployment.

## 9. Deployment Stages

### Beta

Use one persistent free-tier VM for a small invite-only beta. Install Docker Engine, point a real domain at the VM, pin the image digest, mount persistent data and backup volumes, enable provider firewall rules for 80/443 only, and configure an external uptime check against `/health/live`.

Free capacity is a beta convenience, not a public availability promise. The operator must monitor disk, backup success, connection count, and provider reclamation limits.

### Public Play Store launch

Move the identical immutable container and volumes to a paid single VM in the USD 5-10 per month range before broad availability. Restore the latest validated snapshot, verify readiness and the authenticated smoke scenario, then change DNS. Keep the previous VM and image digest available for rollback during the launch window.

### Later Cloudflare migration

A Durable Objects implementation must preserve the public HTTP paths, WebSocket frames, v1/v2 semantics, rejection reasons, pair IDs, device IDs, and encrypted envelope bytes. The Go relay becomes the executable reference. Cross-implementation contract tests run against both backends before traffic moves. Because identities and pair records are exported and imported without changing their values, users do not re-pair solely because the hosting backend changes.

## 10. Explicit Non-Goals

This release does not provide:

- multi-node active-active relay service;
- automatic failover or a zero-downtime SLA;
- multi-region replication;
- plaintext inspection or relay-side notification compaction;
- public metrics or an unauthenticated admin API;
- a claim that free hosting is suitable for unrestricted Play Store traffic;
- completion of owner-controlled Play signing or physical-device evidence.
