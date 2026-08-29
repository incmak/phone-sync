# Production Relay Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a fail-closed, restart-safe, observable, backed-up single-node Go relay suitable for a controlled Play Store launch.

**Architecture:** Keep Caddy in front of one Go relay and one Bolt database. Add mutual pairing approval across Go and Android, persist JWT replay state in the existing Bolt file, centralize storage admission/readiness, close sockets during shutdown, and operate immutable constrained containers with consistent snapshots and rollback.

**Tech Stack:** Go 1.23, chi, gorilla/websocket, bbolt, JSON Schema 2020-12, Kotlin/Android/libsodium, Docker Compose, Caddy, GitHub Actions

## Global Constraints

- Work directly in the primary checkout; do not create or use a worktree.
- Preserve v1 and v2 relay frames, pair IDs, and every existing accepted-mailbox invariant.
- Never edit generated `relay/internal/server/schemas/`; edit `proto/` and run `make sync-proto`.
- New reliable-delivery store paths remain pair-scoped and authorize inside the same Bolt transaction.
- Production must not publish relay port 8080.
- Existing confirmed pairs remain valid; only new production pair completion requires both signatures.
- Logs and metrics must not contain raw device IDs, pair tokens, JWTs, message IDs, keys, nonces, ciphertext, or request bodies.
- Every behavior change observes a failing test before production code.
- Run Go tests with `-race` before each implementation commit.
- Do not modify or commit the user's unrelated notification-detail working-tree changes.

---

### Task 1: Mutual cryptographic pair confirmation

**Files:**
- Modify: `proto/pair-complete.schema.json`
- Create: `relay/internal/server/pair_confirmation.go`
- Modify: `relay/internal/server/pair.go`
- Modify: `relay/internal/server/server.go`
- Modify: `relay/internal/store/pair_store.go`
- Test: `relay/internal/server/pair_test.go`
- Test: `relay/internal/server/pair_notify_test.go`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/PairConfirmation.kt`
- Test: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/pairing/PairConfirmationTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/PairProtocol.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/android/src/debug/java/co/twinotify/core/e2e/E2eControlReceiver.kt`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Modify: `mobile/app/pair/fingerprint.tsx`

**Interfaces:**
- Consumes: existing A transcript `pair_token || A_enc || A_sign || B_enc || B_sign` and existing `confirmation_sig`.
- Produces: `responder_confirmation_sig` and `responderConfirmationMessage(token, aEnc, aSign, bEnc, bSign, aSig): ByteArray` with the domain prefix from the design.

- [x] **Step 1: Write failing Go request tests**

Add cases that enable `Config.RequireMutualPairSignatures`, omit the responder signature, sign with the wrong B key, alter one transcript field, and submit two identical valid retries. Assert 400 for missing/invalid proof and one stable `pair_id` for valid retries.

```go
config := DefaultConfig()
config.RequireMutualPairSignatures = true
srv := newTestServerWithConfig(t, config)

complete["responder_confirmation_sig"] = base64.StdEncoding.EncodeToString(
    ed25519.Sign(bPrivate, responderConfirmationMessage(pending, aSignature)),
)
```

- [x] **Step 2: Run the focused Go test and observe RED**

Run: `make sync-proto && cd relay && go test ./internal/server -run 'TestPairComplete.*Responder|TestPairCompletion.*Mutual' -race -count=1`

Expected: compilation or assertion failure because the config field, transcript helper, and request field do not exist.

- [x] **Step 3: Write the pure Kotlin transcript test and observe RED**

Use fixed byte arrays and assert the exact bytes equal the UTF-8 domain prefix followed by token, A keys, B keys, and A signature. Run:

`cd mobile && npx expo prebuild --platform android --clean --no-install && cd android && ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests '*PairConfirmationTest'`

Expected: compilation failure because `PairConfirmation` does not exist.

- [x] **Step 4: Implement and verify mutual confirmation**

Add this Go boundary and its Kotlin byte-equivalent:

```go
const responderConfirmationDomain = "twinotify-pair-confirm-b-v1\n"

func responderConfirmationMessage(p *store.PendingPair, aSignature []byte) []byte {
    message := append([]byte(responderConfirmationDomain), []byte(p.PairToken)...)
    message = append(message, p.AEncPubkey...)
    message = append(message, p.ASignPubkey...)
    message = append(message, p.BEncPubkey...)
    message = append(message, p.BSignPubkey...)
    return append(message, aSignature...)
}
```

Store the verified responder signature in `PendingPair.ResponderConfirmationSig` for deterministic retry comparison. New Android completes pairing with A's scanned public keys and B's signing secret. Update the schema to require `responder_confirmation_sig` and disallow additional properties.

Run the focused Go and Kotlin commands again. Expected: PASS.

- [x] **Step 5: Run regression gates and commit**

Run: `make proto-test && make relay-test`

Run: `cd mobile && npm run typecheck`

Commit only Task 1 files with: `feat(relay): require mutual pairing confirmation`

---

### Task 2: Restart-persistent JWT replay protection

**Files:**
- Create: `relay/internal/server/persistent_jti.go`
- Test: `relay/internal/server/persistent_jti_test.go`
- Modify: `relay/internal/server/jwt_auth.go`
- Modify: `relay/internal/server/jwt_auth_test.go`
- Modify: `relay/internal/server/server.go`
- Modify: `relay/internal/server/http_limits.go`

**Interfaces:**
- Consumes: `*store.Bolt`, `JTICacheConfig`, and the server clock.
- Produces: `OpenPersistentJTICache(*store.Bolt, JTICacheConfig) (*PersistentJTICache, error)` implementing `CheckAndSet(string, time.Time) error`, `Cleanup(time.Time) (int, error)`, and `EntryCount() (int, error)`.

- [x] **Step 1: Write failing persistence and claim-bound tests**

Create a Bolt file, admit one UUID JTI, close and reopen Bolt, then assert the same JTI returns `ErrJTIReplay`. Add middleware tests for missing `iat`, missing `exp`, non-UUID JTI, an `exp-iat` window above 60 seconds, and `iat` more than 30 seconds in the future.

- [x] **Step 2: Observe RED**

Run: `make sync-proto && cd relay && go test ./internal/server -run 'TestPersistentJTI|TestJWTClaims' -race -count=1`

Expected: missing type or requests incorrectly authorized.

- [x] **Step 3: Implement the bounded Bolt replay store**

Use three buckets: `auth_jti_v1`, `auth_jti_expiry_v1`, and `auth_jti_meta_v1`. Store `sha256(jti)` as the primary key. Use an eight-byte big-endian expiry prefix plus the digest for the expiry key. Initialize by validating reciprocal entries and rebuilding the exact count.

The middleware parses with an injected clock, requires EdDSA, reads verified claims, enforces the 60-second lifetime and 30-second future-skew limits, then atomically consumes the JTI. Any storage or capacity error returns 401 without calling the handler.

- [x] **Step 4: Verify and commit**

Run the focused test, then `make relay-test`. Expected: PASS with the race detector.

Commit: `feat(relay): persist authentication replay state`

---

### Task 3: Production config, capacity admission, and health

**Files:**
- Create: `relay/cmd/relay/config.go`
- Test: `relay/cmd/relay/config_test.go`
- Create: `relay/internal/server/capacity.go`
- Test: `relay/internal/server/capacity_test.go`
- Modify: `relay/internal/server/health.go`
- Modify: `relay/internal/server/server.go`
- Modify: `relay/internal/server/server_test.go`
- Modify: `relay/internal/server/pair.go`
- Modify: `relay/internal/server/pair_handshake.go`
- Modify: `relay/internal/server/ws.go`
- Modify: `proto/relay-control.schema.json`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayFrameCodec.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Test: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayFrameCodecTest.kt`

**Interfaces:**
- Produces: `loadRuntimeConfig(getenv func(string) string) (runtimeConfig, error)`.
- Produces: `CapacityCheck func() error`, `Server.BeginShutdown()`, and readiness routes.
- Adds stable rejection reason `server_capacity`.

- [x] **Step 1: Write failing config, readiness, and backpressure tests**

Table-test every required production variable and unsafe path. Inject `CapacityCheck` returning `ErrServerCapacity`; assert `/health/live` is 200, `/health/ready` and `/health` are 503, pairing mutation is 503 with `Retry-After`, and a valid v2 put returns `server_capacity` without increasing mailbox count. Assert `BeginShutdown` changes readiness to 503.

- [x] **Step 2: Observe RED**

Run: `make sync-proto && cd relay && go test ./cmd/relay ./internal/server -run 'TestProductionConfig|TestHealth|TestServerCapacity' -race -count=1`

Expected: missing APIs and wrong health/backpressure results.

- [x] **Step 3: Implement fail-closed config and admission**

Production values are exactly those in the approved design. Implement the disk check with `unix.Statfs`, comparing `Bavail * Bsize` to `MIN_FREE_DISK_BYTES`. Keep the check injectable in tests. Apply it only to new pair state and new mailbox puts, never ACK, revoke, or expiry cleanup.

Add `server_capacity` to Go schema fixtures and Android's closed rejection set.

- [x] **Step 4: Verify and commit**

Run focused Go tests, `make proto-test`, `make relay-test`, the focused Kotlin codec test, and `cd mobile && npm run typecheck`.

Commit: `feat(relay): fail closed on production capacity`

---

### Task 4: Graceful connections, structured logs, and private metrics

**Files:**
- Create: `relay/cmd/relay/limit_listener.go`
- Test: `relay/cmd/relay/limit_listener_test.go`
- Create: `relay/cmd/relay/logging.go`
- Create: `relay/internal/server/metrics.go`
- Test: `relay/internal/server/metrics_test.go`
- Modify: `relay/internal/server/client_hub.go`
- Modify: `relay/internal/server/ws.go`
- Modify: `relay/internal/server/http_limits.go`
- Modify: `relay/internal/server/pair_handshake.go`
- Modify: `relay/cmd/relay/main.go`
- Modify: `relay/cmd/relay/main_test.go`

**Interfaces:**
- Produces: a listener capped by `MAX_OPEN_CONNECTIONS`.
- Produces: `GET /metrics` on the relay router only.
- Produces: `ClientHub.Drain(code int, reason string)` closing active sockets with code 1012.

- [x] **Step 1: Write failing listener, metrics, privacy, and shutdown tests**

Assert the limited listener never has more accepted live connections than its budget. Assert metric labels come only from closed enums, active connections return to zero, and rendered metrics contain no supplied device or message IDs. Open a WebSocket, call `BeginShutdown`, and assert close code 1012.

- [x] **Step 2: Observe RED**

Run: `make sync-proto && cd relay && go test ./cmd/relay ./internal/server -run 'TestLimitListener|TestMetrics|TestBeginShutdown' -race -count=1`

- [x] **Step 3: Implement bounded observability and drain**

Use atomics for counters and gauges. Render a fixed Prometheus text body with no dynamic labels. Replace relay logging with `slog` calls that contain only constant event names and bounded reasons. Configure JSON logging only when `TWINOTIFY_ENV=production`.

Track each live WebSocket in the hub. Drain by signaling the handler, which serializes a WebSocket close control frame with code 1012 before closing the connection.

- [x] **Step 4: Verify and commit**

Run focused tests and `make relay-test`. Scan with:

`rg -n 'log\.(Printf|Println|Fatalf|Fatal|Print)|slog\..*(deviceID|pairToken|msgID|recipient|ciphertext|jwt)' relay`

Expected: no privacy-unsafe relay log calls.

Commit: `feat(relay): add graceful drain and private metrics`

---

### Task 5: Consistent backup and guarded restore

**Files:**
- Modify: `relay/internal/store/bolt.go`
- Test: `relay/internal/store/bolt_test.go`
- Create: `relay/cmd/relay/backup.go`
- Test: `relay/cmd/relay/backup_test.go`
- Modify: `relay/cmd/relay/main.go`
- Modify: `relay/cmd/relay/main_test.go`
- Modify: `relay/internal/server/metrics.go`

**Interfaces:**
- Produces: `(*Bolt).Snapshot(path string) error` and `ValidateBolt(path string) error`.
- Produces: periodic `backupManager.Run(ctx)`, offline CLI `relay backup --from PATH --to-dir DIR --retention N`, and CLI `relay restore --from PATH --to PATH --backup-dir DIR --data-dir DIR`.

- [x] **Step 1: Write failing backup, retention, corruption, and restore tests**

Mutate Bolt while taking a snapshot and assert the snapshot opens and passes consistency checks. Create more snapshots than retention and assert only the newest configured count remain. Assert a corrupt source, path outside allowed roots, and restore while the destination lock is held all fail without changing the destination. Assert a valid restore keeps a timestamped recovery copy.

- [x] **Step 2: Observe RED**

Run: `make sync-proto && cd relay && go test ./cmd/relay ./internal/store -run 'TestSnapshot|TestBackup|TestRestore' -race -count=1`

- [x] **Step 3: Implement snapshot scheduling and restore**

Create snapshots through `Tx.CopyFile` into a same-directory temporary path, validate, then rename. Use mode `0600`, UTC names, sanitized build version, and lexicographic time ordering. Restore copies into the data directory, validates, renames the current DB to `.recovery-<UTC>`, then atomically installs the new DB. Refuse symlinked or out-of-root paths.

- [x] **Step 4: Verify and commit**

Run focused tests and `make relay-test`.

Commit: `feat(relay): automate consistent backup and restore`

---

### Task 6: Harden the image, Compose topology, and Caddy allowlist

**Files:**
- Modify: `relay/Dockerfile`
- Modify: `relay/cmd/relay/main.go`
- Modify: `relay/cmd/relay/config.go`
- Create: `relay/cmd/relay/healthcheck.go`
- Modify: `relay/cmd/relay/main_test.go`
- Modify: `deploy/docker-compose.prod.yml`
- Modify: `deploy/caddy/Caddyfile`
- Modify: `relay/cmd/deployassert/main.go`
- Modify: `relay/cmd/deployassert/main_test.go`
- Modify: `deploy/assert-compose.sh`
- Create: `deploy/.env.production.example`

**Interfaces:**
- Consumes: full digest-pinned `TWINOTIFY_RELAY_IMAGE`.
- Produces: constrained relay and Caddy services plus relay binary healthcheck.

- [x] **Step 1: Extend deployment mutation tests and observe RED**

Require the production environment, backup volume, read-only root, non-root user, dropped capabilities, no-new-privileges, PID/memory/CPU/nofile bounds, log rotation, healthcheck, digest image, and Caddy `/health/*` route while rejecting `/metrics` and any extra public path.

Run: `make deployment-test`

Expected: deployment assertion failure against the current Compose file.

- [x] **Step 2: Implement the hardened topology**

The Dockerfile creates owned `/data` and `/backups`, embeds OCI labels and version variables, and keeps the distroless non-root final image. Compose uses `image: ${TWINOTIFY_RELAY_IMAGE:?...}`, a read-only root, named writable volumes, closed resource bounds, bounded logging, and exec-form `/relay healthcheck`.

- [x] **Step 3: Verify and commit**

Run: `make deployment-test`

Run: `docker build -t twinotify-relay:hardening -f relay/Dockerfile .`

Run a temporary Compose project with a local digest-equivalent image override, wait for relay health, inspect UID 65532 volume writes through health and backup creation, and confirm host port 8080 is absent.

Commit: `chore(deploy): harden production relay container`

---

### Task 7: Release workflow, smoke automation, and runbook

**Files:**
- Create: `.dockerignore`
- Create: `.github/workflows/relay-image.yml`
- Modify: `.github/workflows/relay.yml`
- Modify: `.gitignore`
- Modify: `Makefile`
- Modify: `relay/cmd/relay/backup.go`
- Modify: `relay/cmd/relay/backup_test.go`
- Create: `deploy/smoke-relay.sh`
- Create: `deploy/smoke-relay_test.sh`
- Create: `deploy/deploy-relay.sh`
- Create: `deploy/deploy-relay_test.sh`
- Create: `docs/relay-production-runbook.md`
- Modify: `relay/README.md`

**Interfaces:**
- Produces: manual or `relay-v*` tag GHCR publication with SBOM and provenance.
- Produces: digest-only deploy and automatic previous-digest rollback on failed readiness/smoke.

- [x] **Step 1: Write workflow and shell contract tests and observe RED**

Extend `relay-ci-test` to require SHA-pinned actions, least-privilege permissions, provenance, SBOM, digest output, deployment smoke, and shell syntax checks. Add fixture-driven shell tests for digest rejection and rollback command selection.

Run: `make relay-ci-test`

Expected: failure because the image workflow and scripts are absent.

- [x] **Step 2: Implement publication, deployment, and operator docs**

Publish only on `workflow_dispatch` or tags matching `relay-v*`. Require a digest reference matching `@sha256:[0-9a-f]{64}` for production. The deploy script records the current image, stops the single relay writer, runs the image's offline `backup` command against the mounted data and backup volumes, starts the new digest, waits for readiness, runs the smoke script, and restores the prior digest on failure without restoring the database.

Document DNS, firewall, backup export, restore drill, uptime monitoring, upgrade, rollback, and the beta-to-paid launch move.

- [x] **Step 3: Verify and commit**

Run: `make relay-ci-test && bash -n deploy/smoke-relay.sh deploy/deploy-relay.sh`

Run: `make relay-verify && make deployment-test`

Also verified the workflow with actionlint, all four shell files with ShellCheck 0.11.0, and a local `linux/amd64,linux/arm64` OCI export with BuildKit SBOM and maximum provenance enabled.

Commit: `ci(relay): publish and deploy verified images`

---

### Task 8: Final verification, evidence ledger, and independent audit

**Files:**
- Modify: `docs/superpowers/plans/2026-08-29-production-relay-hardening.md`
- Modify: `advisor-plans/README.md` only if its current product-truth ledger needs the new host-verification result

**Interfaces:**
- Consumes: all prior task commits.
- Produces: fresh automated evidence and a Sol/High `ship`, `fix-first`, or `rethink` verdict.

- [x] **Step 1: Run the complete host gates**

Run:

```sh
make relay-verify
make deployment-test
cd mobile && npm run typecheck
git diff --check
```

Record exact exit codes and test counts. Do not claim Android instrumentation, physical devices, public TLS, or off-host backup evidence unless actually run.

Fresh committed-head evidence at `d1b98c6` on 2026-08-29:

- `make relay-verify`: exit 0; workflow/shell contracts, `go vet`, four Go packages under `-race -count=1`, and the Docker build passed.
- A separate JSON race run recorded 203 top-level tests and 408 total test/subtest pass events across four packages, with zero skips and zero failures.
- `make deployment-test`: exit 0.
- `cd mobile && npm run typecheck`: exit 0.
- `git diff --check`: exit 0.
- Actionlint 1.7.7 and ShellCheck 0.11.0: exit 0.
- A final AMD64/ARM64 OCI export with BuildKit maximum provenance and SBOM completed at manifest-list digest `sha256:ea7c909bf1d5f846c7d120339c7e2a3ec61b7789e2cce09b2ce509612e8b7659`; the local OCI archive SHA-256 is `1bf0a065c65aa66d4ebd6670858a34488a940ec7b262308ade2f170e047f8e95`.

- [x] **Step 2: Perform manual artifact inspection**

Inspect the full diff, generated-schema cleanliness, Compose-resolved configuration, image user, read-only mounts, exposed ports, health state, backup file permissions, restore recovery copy, workflow action pins, and privacy scan.

The isolated production-Compose audit reported relay user `65532:65532`, read-only root, `cap_drop: [ALL]`, `no-new-privileges`, no host port binding, and only `/data` plus `/backups` writable. Readiness was healthy. The startup snapshot was owned by `65532:65532` with mode `0600`; an offline restore created a separate mode-`0600` recovery copy and returned healthy. Only Caddy resolves public ports 80/443. The exact pinned Caddy image validated the production Caddyfile. Generated schemas/fixtures remain ignored and untracked. All workflow actions use full commit SHAs, and the relay log scan found only static event names and bounded static labels.

Evidence limits remain explicit: no GHCR publication, public DNS/TLS request, real off-host backup transfer, production monitoring alert, Android instrumentation, protected EAS build, or physical two-phone scenario was run in this host audit.

- [ ] **Step 3: Obtain the fresh read-only Sol/High audit**

Provide the exact base/head commits, allowed files, design/spec, verification outputs, and residual evidence limits. Accept only `ship`; on `fix-first`, implement in the primary session, rerun every affected gate, and obtain a new fresh reviewer.

- [ ] **Step 4: Mark plan complete and hand off deployment choice**

Check completed steps, commit the evidence-ledger update if needed, and report the recommended free beta host plus the paid launch move with current official-source links.
