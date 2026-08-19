# Task 8 report: prove offline pairing on two physical phones

Date: 2026-08-19

## Result

Status: **DONE_WITH_CONCERNS - PHYSICAL ACCEPTANCE PENDING**.

Task 8 implementation and all available automated gates pass. The debug-only Android control surface can start, join, confirm, cancel, and query production offline pairing through an install-authenticated, closed-world interface. A deterministic Go host scenario targets exactly two explicit hardware serials, disables mobile data only, proves a common Wi-Fi hash, carries ceremony secrets only through a bounded app-private one-time channel, confirms matching six-digit SAS values in memory, validates reciprocal application identities/TLS pins/sealed LAN bindings, and revalidates after process restart. Evidence is private, bounded, hash/state-only, and independently verified.

Physical OFFLINE-PAIR-01 was not run. Read-only inventory found one physical M2012K11AI only. No app clear, radio change, relay/topology manipulation, or two-device scenario was attempted. No internet-block, packet, DNS, or two-phone success evidence is claimed or fabricated.

Baseline: `2965f3a` on `codex/offline-lan-sync`.

## Strict TDD evidence

Production changes followed failing Android, Go, CLI, shell-verifier, and boundary tests. Representative RED artifacts:

- `.omo/evidence/task-8/red-go.log`: offline scenario contracts absent.
- `.omo/evidence/task-8/red-android.log`: offline control/private-result contracts absent.
- `.omo/evidence/task-8/red-secret-channel.log`: private stdin/file handoff absent.
- `.omo/evidence/task-8/red-private-auth.log`: install credential appeared in captured broadcast argv; the captured value and argv were subsequently redacted from the artifact.
- `.omo/evidence/task-8/red-bound-handle.log` and `red-android-bound-handle.log`: request handles were not token/command/expiry bound.
- `.omo/evidence/task-8/red-android-private-boundary.log`: one-time replay/symlink/oversize boundary seam absent.
- `.omo/evidence/task-8/red-private-atomic-owner.log`: atomic exclusive publication and ownership/mode checks absent.
- `.omo/evidence/task-8/red-closed-world-host-json.log`: trailing host JSON accepted.
- `.omo/evidence/task-8/red-session-cancel-cleanup.log`: failed SAS path left active sessions uncancelled.
- `.omo/evidence/task-8/red-verifier.log`: evidence verifier absent.

The first physical security instrumentation run also exposed two real filesystem differences: Android denied hard-link publication and missing replay files surfaced `ENOENT`. The corrected design exclusively creates the final 0600 file, writes/fsyncs it before publishing the ordinary result, removes it on failed writes, and normalizes filesystem failures to bounded errors. The same 15-test class then passed.

## Implementation and security audit

- Debug receiver commands are exactly allowlisted. Offline parameters are command-specific and reject extra fields/oversize values. Normal JSON is bounded; host decoders reject unknown keys, invalid states/hashes, trailing JSON, and oversize input.
- Install-token checks remain constant-time. The CLI no longer accepts token flags. It discovers the install token through `run-as`, creates a cryptographically random HMAC-SHA256 request handle bound to token, command, and a short expiry, and transfers authentication bytes through stdin into `files/e2e-auth`.
- Request handles have fixed grammar, cannot traverse directories, expire, are command/token bound, and are consumed once. App-private directories/files require app UID ownership and exact 0700/0600 modes. Stale targets, symlinks, oversize data, replay, partial writes, and cancellation cleanup are tested.
- QR payload, session handle, SAS, transcript, install token, raw SSID, and raw IP have no evidence fields. QR/session/SAS cross ADB only through `e2e-inputs`/`e2e-secrets`, never argv or ordinary result JSON. Arrays are cleared after use where mutable; errors contain bounded codes/stages only.
- The host rejects equal/implicit/emulator serials and stale pre-existing success. It checks hardware, disables `svc data` only, hashes SSID plus local subnet in memory, requires matching hashes, and does not disable Wi-Fi or airplane mode.
- Pairing success requires equal six-digit SAS, matching SAS hashes, reciprocal application identity hashes, both sealed LAN bindings, reciprocal TLS-pin hashes, and persistence after both app processes restart. Failed active ceremonies attempt exact-session cancellation.
- Evidence uses `O_EXCL`, mode 0600, a non-symlink 0700 directory, a 16 KiB bound, exact closed-world state/hash fields, and packet/DNS evidence hooks. Hash flags cannot create physical proof; docs explicitly require operator captures and mark acceptance pending otherwise.
- Merged debug manifest contains one control receiver and one state provider. The merged release manifest contains neither.
- No product LAN transport, product UI, schema, database migration, relay behavior, or radio-control expansion was added.

## Verification matrix

| Scenario | Invocation | Binary observable | Artifact |
|---|---|---|---|
| Android debug/private boundary compiles | `:twinotify-core:compileDebugKotlin :twinotify-core:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL` | `.omo/evidence/task-8/green-android-private-boundary-compile.log` |
| Android JVM unit suite | `:twinotify-core:testDebugUnitTest` | `BUILD SUCCESSFUL` | `.omo/evidence/task-8/green-android-unit.log` |
| Real-device debug security boundary | `:twinotify-core:connectedDebugAndroidTest` for `E2eControlSecurityTest` | 16 tests finished; `BUILD SUCCESSFUL` | `.omo/evidence/task-8/fix-green-android-security.log` |
| Go host/control/ADB/CLI | `cd e2e && go test ./... -race -count=1` | 5 packages passed | `.omo/evidence/task-8/final2-go-race.log` |
| Go static analysis | `cd e2e && go vet ./...` | exit 0 | `.omo/evidence/task-8/final-go-vet.log` |
| Failed-session cancellation | focused scenario test | exit 0 | `.omo/evidence/task-8/green-session-cancel-cleanup.log` |
| Evidence verifier negatives and positive | `scripts/verify-offline-pairing-evidence.sh --self-test` | missing, mismatch, secret-field cases rejected; valid fixture accepted | `.omo/evidence/task-8/final-verifier-self-test.log` |
| Protocol fixtures | `GOCACHE=... make proto-test` | server package passed | `.omo/evidence/task-8/gate-proto-test.log` |
| Relay workflow invariants | `make relay-ci-test` | exit 0 | `.omo/evidence/task-8/gate-relay-ci-test.log` |
| Full mobile gate | `ANDROID_HOME=... make mobile-verify` | typecheck, Expo Doctor 18/18, clean prebuild, lint, unit tests, debug APK; `BUILD SUCCESSFUL`, 698 tasks | `.omo/evidence/task-8/gate-mobile-verify.log` |
| Generated-source cleanliness | `./scripts/verify-generated-clean.sh` | exit 0 | `.omo/evidence/task-8/gate-generated-clean.log` |
| Diff hygiene | `git diff --check` | exit 0 | `.omo/evidence/task-8/gate-diff-check.log` |
| Final merged manifests | generate debug/release manifests, inspect exact component names | debug receiver/provider 1 each; release 0 | `.omo/evidence/task-8/final-manifest-isolation.log` |
| Make preflight | `make e2e-offline-pairing` without explicit inputs | exit nonzero before ADB/radio action | `.omo/evidence/task-8/make-preflight-negative.log` |
| Physical inventory boundary | `adb devices -l` | one physical M2012K11AI; identifiers redacted in artifact | `.omo/evidence/task-8/device-inventory.log` |

## Physical acceptance boundary

OFFLINE-PAIR-01 remains pending because two distinct phones and an operator-controlled Wi-Fi topology with retained local connectivity and blocked internet were unavailable. Consequently the following are not claimed: two fresh phones completing a real pairing, packet/DNS no-uplink proof, process death before confirmation on two phones, process restart recovery on two phones, relay-pair upgrade on hardware, or physical unpair/identity rotation. The executable contracts and evidence hooks exist, but fixture/unit output is not substituted for physical evidence.

## Anti-slop recheck

The full AGENTS.md law was reread at completion. Task 8 changes no UI, layout, type, color, motion, imagery, controls, copy surface, or visual assets. Therefore every visual anti-slop category is unaffected. Documentation is terse, uses no decorative marketing structure, and does not use em dashes. No hidden/dead UI control was introduced.

## Independent review

Initial verdict: **REQUEST_CHANGES**. The reviewer confirmed transcript/persistence binding, private ceremony handoff, release isolation, and automated evidence, then identified two implementation Important findings plus the unavoidable physical blocker.

- Existing relay peers were accepted as "fresh" when no LAN binding existed. RED `.omo/evidence/task-8/review-red-fresh-start-cleanup.log` proved the scenario proceeded; the fresh-state gate now requires the peer application identity hash to be absent.
- A device-side session created before a failed/malformed Start response could escape cleanup. Cleanup is now armed before Start, query/cancel runs before the bounded failure snapshot, and a side-effect-then-error regression proves exact cancellation.
- The misleading instrumentation test name that called expiration a replay-window test was corrected. One-time file consumption remains the replay defense.

Focused correction GREEN: `.omo/evidence/task-8/review-green-fresh-start-cleanup.log`. Full correction Go race/vet: `.omo/evidence/task-8/review-green-go-race.log` and `.omo/evidence/task-8/review-green-go-vet.log`.

## Fresh review correction at 905fff8

The second independent review found three Important and two Minor issues. All five code findings are corrected:

- Private input, authentication, and output cleanup are separate APIs and target `e2e-inputs`, `e2e-auth`, and `e2e-secrets` respectively. Every prepared private request runs each cleanup under a two-second `context.WithoutCancel` deadline on success, ordinary result error, timeout, caller cancellation, malformed ordinary response, and private-read failure. Cleanup is idempotent when a directory or file is already absent. The test device produces an output before each forced failure and proves no input or output residue remains.
- The evidence verifier now requires the exact top-level inventory and JSON types, complete durable final phases, separately recorded initiator/joiner ceremony roles, roleless post-restart snapshots, no final error, lowercase 64-character hashes, reciprocal application identities and TLS bindings, directory mode 0700, and file mode 0600. It rejects extra files, directories, symlinks, missing fields, unexpected fields, invalid values, wrong types, public modes, and reciprocal mismatches. The reviewer's previously accepted combined mutation is covered by the phase, role, error, hash, and mode negative cases.
- Fresh preflight is exact idle state with no role, error, session hash, SAS hash, peer application identity, completion, or LAN binding. Fixtures shaped like production cancelled and failed terminal-idle state are rejected before mobile data changes.
- The Android security suite now sends real explicit exported broadcasts through `onReceive`, waits for ordinary and private file publication, and covers wrong-command, expiry, replay, malformed Intent, bounded errors, and auth/input/output removal. `PendingResult.finish()` is in a `finally` block.
- Package and component grammar is enforced at CLI parsing and every package-bearing shell helper, including `ReadRunAs` and `ForceStop`. Metacharacter and whitespace cases prove zero ADB invocation.

Evidence is atomically generated by writing and syncing an exclusive owner-only temporary inode, linking the complete inode to the final name without replacement, removing the temporary name, and syncing the directory. Verification is only invoked after the writer returns and the closed inventory rejects partial or extra nodes. A signed immutable evidence index is intentionally deferred to the later release-evidence plan because Task 8 has no signing authority or release key. The current artifact is not represented as signed or tamper-proof.

Correction evidence:

| Scenario | Invocation | Binary observable | Artifact |
|---|---|---|---|
| TDD RED, Go | focused control, ADB, and scenario tests | cleanup, component grammar, and stale terminal fixtures fail | `.omo/evidence/task-8/fix-red-go.log` |
| TDD RED, verifier | `scripts/verify-offline-pairing-evidence.sh --self-test` | reviewer phase mutation accepted and self-test fails | `.omo/evidence/task-8/fix-red-verifier.log` |
| Cleanup, freshness, grammar GREEN | `cd e2e && go test ./... -race -count=1` | all 5 packages pass | `.omo/evidence/task-8/fix-final2-go-race.log` |
| Go static analysis | `cd e2e && go vet ./...` | exit 0 | `.omo/evidence/task-8/fix-final2-go-vet.log` |
| Closed evidence verifier | verifier self-test | all positive and negative fixtures pass | `.omo/evidence/task-8/fix-final2-verifier.log` |
| Exported receiver security | focused connected Android test | 16 tests finish; build succeeds | `.omo/evidence/task-8/fix-green-android-security.log` |
| Full mobile gate | `ANDROID_HOME=... make mobile-verify` | TypeScript, Expo Doctor, lint, JVM tests, and debug APK succeed | `.omo/evidence/task-8/fix-gate-mobile-verify.log` |
| Protocol and workflow gates | `make proto-test`, `make relay-ci-test`, generated clean | exit 0 | `.omo/evidence/task-8/fix-gate-proto-test.log`, `.omo/evidence/task-8/fix-gate-relay-ci.log`, `.omo/evidence/task-8/fix-gate-generated-clean.log` |

Physical acceptance remains pending. Only one M2012K11AI was visible, so no two-phone pairing, radio change, internet-isolation claim, packet/DNS claim, or destructive scenario was attempted.

Corrected re-review verdict at `70f74a8`: **BLOCK**. One Important integration mismatch remained because native process restart drops the transient role while the verifier required role inside durable snapshots. The evidence index was also stale. No approval is claimed before a fresh review of the correction below.

## Final restart evidence alignment

The host now records the pre-restart ceremony assertion in a bounded `ceremony_roles` object with exact `device_a=initiator` and `device_b=joiner` values. The final `device_a` and `device_b` objects remain the native post-restart durable snapshots and are required to omit transient role. This preserves the real process-lifetime boundary while retaining explicit evidence that both sides occupied the intended ceremony positions before restart.

The integration regression runs `RunOfflinePairing` with production-shaped snapshots, writes the real result through `WriteOfflinePairingEvidence`, then invokes `scripts/verify-offline-pairing-evidence.sh`. This makes the operator verifier the authoritative contract for the Go scenario result instead of maintaining a second unchecked fixture literal.

| Scenario | Invocation | Binary observable | Artifact |
|---|---|---|---|
| Production-shaped role contract RED | focused scenario integration test | real post-restart result rejected by verifier | `.omo/evidence/task-8/role-contract-red.log` |
| Production-shaped role contract GREEN | focused scenario integration test with race detector | real result accepted by authoritative verifier | `.omo/evidence/task-8/role-contract-green.log` |
| Verifier role contract | `scripts/verify-offline-pairing-evidence.sh --self-test` | exact ceremony roles accepted; missing/invalid ceremony roles and durable roles rejected | `.omo/evidence/task-8/role-verifier-green.log` |
| Unchanged Android seam compile | `:twinotify-core:compileDebugKotlin` | `BUILD SUCCESSFUL` | `.omo/evidence/task-8/fix-final-role-android-compile.log` |

Physical acceptance remains pending and no fresh reviewer approval is claimed here.
