# Complete Direct-LAN Gate Coverage

**Status:** Complete on 2026-08-27 for all host-automated criteria. Full host verification and independent review passed. Physical fallback/return, restart, reverse-direction, no-uplink, and UI acceptance remain pending a physical two-phone run. Evidence: `.omo/evidence/final-plan-compliance.md` and `.omo/evidence/final-code-review.md`.

## TL;DR
> Summary:      Make the existing direct-LAN product aggregate execute and verify relay fallback/return, process restart recovery on both phones, and a real B-to-A notification delivery path. Keep the harness fail-closed, bounded, content-free, and explicit that hardware acceptance is still pending.
> Deliverables:
> - Executable, bounded A/B package restart actions backed by the existing validated ADB launcher helper
> - Direction-neutral notification actions and observation predicates for A-to-B and B-to-A scenarios
> - Eleven-child `lan-product-correctness` aggregate with fallback/return, restart persistence, and reverse delivery
> - Closed-world per-child evidence validation, synthetic self-tests, Make/CI wiring, and truthful documentation
> Effort:       Medium
> Risk:         Medium - lifecycle actions and multi-route evidence can create false passes unless action ordering, route sampling, and child artifacts remain independently verified

## Scope
### Must have
- Make `A.restart` and `B.restart` executable only after a successful matching `force-stop` step in the same child plan; launch only the configured package through the typed ADB client and bound every bridge action by the scenario step timeout.
- Generalize the existing A-origin/B-recipient action and predicate grammar only as far as needed for an exact B-origin/A-recipient notification scenario and relay custody assertions.
- Add `lan-direct-reverse-delivery`, strengthen `lan-relay-fallback-return`, and strengthen `lan-restart-persistence` so each requires observed route, custody, receipt, exact materialization, and terminal convergence evidence.
- Add those three children to `lan-product-correctness` exactly once, use unique tags, preserve fail-fast execution, retain every completed child plus the failed child, and keep destructive unpair last.
- Extend the strict verifier, its fixture generator, documentation checker, Make self-test, host verification recipe, and host workflow contract so omission or semantic corruption of any required child fails.
- Keep the actual two-phone fallback, restart, reverse-direction, no-uplink, and UI evidence explicitly pending until a physical run is captured.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- Do not edit Android production/native behavior, Room schemas, relay code, protocol schemas, UI, pairing state, or the concurrent Plan018 files.
- Do not add reboot, relay-process control, package clear, uninstall, data clear, OS Wi-Fi/mobile-data/airplane-mode changes, arbitrary shell strings, sleeps, or automatic pairing/re-pairing.
- Do not weaken pre-ADB validation, route authentication checks, evidence privacy bounds, closed-world schemas, child inventory checks, or the existing fail-fast aggregate behavior.
- Do not infer direct delivery from mirror presence or terminal route alone. Sample the authenticated route immediately before each delivery action and require custody/receipt deltas on the named origin.
- Do not collapse the relay leg and returned-LAN leg into one ambiguous route field. Retain ordered content-free route events for both deliveries and use the final route block only for the last delivery.
- Do not claim host fixtures, an emulator, or successful compilation as physical two-phone acceptance.
- Do not use a worktree, contact devices, mutate radios/network state, commit evidence, push, or modify files outside the paths named by a task.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD with Go `testing`, shell contract tests, verifier self-tests, and workflow-structure tests
- QA policy: every task has agent-executed scenarios; physical hardware acceptance stays explicitly pending and cannot be replaced by fixtures
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` - under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`

## Execution strategy
### Parallel execution waves
> Target 5-8 tasks per wave. This plan is intentionally serialized where tasks share `scenario.go`, `executor.go`, or the verifier contract; documentation and host-gate wiring can run together only after the required child contract is stable.

Wave 1 (foundation, sequential ownership of shared executor files):
- Task 1: bounded package restart action
- Task 2: direction-neutral action and predicate grammar, depends [1]

Wave 2 (scenario and evidence contract):
- Task 3: complete scenario plans and aggregate, depends [1, 2]
- Task 4: require all child evidence, depends [3]

Wave 3 (mandatory host consumer of the stable contract):
- Task 5: mandatory Make/CI host gate, depends [4]

Wave 4 (documentation after all reachable implementation commits exist):
- Task 6: truthful operator documentation, depends [4, 5]

Critical path: Task 1 -> Task 2 -> Task 3 -> Task 4 -> Task 5 -> Task 6 -> final verification

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1    | none       | 2, 3   | none                 |
| 2    | 1          | 3      | none                 |
| 3    | 1, 2       | 4      | none                 |
| 4    | 3          | 5, 6   | none                 |
| 5    | 4          | 6      | none                 |
| 6    | 4, 5       | final  | none                 |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [ ] 1. Execute package restarts through a bounded, prevalidated bridge action

  What to do: Add RED tests first for `A.restart` and `B.restart`. Introduce a distinct restart action kind, require each restart token to follow a successful matching `A.force-stop` or `B.force-stop` earlier in the same leaf plan, and keep reboot/relay lifecycle/UI tokens unsupported. Extend `Bridge` with a typed restart operation and implement `ADBBridge.Restart` by selecting the named ADB client and calling the existing `StartPackage(ctx, Package)`. Apply `stepTimeout` to every bridge action in `runPlan`, not just predicates and cleanup, so a blocked launch or force-stop cannot hang the aggregate. Preserve the caller cancellation cause. Update every deterministic fake bridge to implement the new method. Add low-level tests that `StartPackage` emits only the validated fixed-argument launcher command and rejects unsafe package names before invoking ADB.

  Must NOT do: Do not implement restart as reboot, `pm clear`, reinstall, shell text, a fixed sleep, or force-stop plus launch hidden inside one action. Do not allow restart without an explicit preceding force-stop in the same child. Do not change the install-scoped E2E token or package data.

  Parallelization: Can parallel: NO | Wave 1 | Blocks: [2, 3] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `e2e/internal/scenario/executor.go:17-69` - typed action kinds and content-free event IDs
  - Pattern:  `e2e/internal/scenario/executor.go:71-142` - parser currently classifies `A.restart` and `B.restart` as unsupported
  - API/Type: `e2e/internal/scenario/executor.go:186-230` - pre-ADB plan validation and the `Bridge` contract
  - Pattern:  `e2e/internal/scenario/executor.go:277-371` - per-step execution, bounded cleanup, and result status derivation
  - API/Type: `e2e/internal/scenario/executor.go:924-984` - device selection and typed `ADBBridge` methods
  - API/Type: `e2e/internal/adb/adb.go:256-269` - validated `ForceStop` and already-existing `StartPackage` helpers
  - Test:     `e2e/internal/adb/adb_test.go:144-187` - invalid-component and typed ADB error tests
  - Test:     `e2e/internal/scenario/scenario_test.go:326-391` - executable-plan and no-bridge-on-preflight-failure patterns

  Acceptance criteria (agent-executable only):
  - [ ] Before implementation, new focused tests fail because restart remains unsupported, `Bridge` has no restart seam, or a blocking action exceeds the bound; RED output is saved to `<attemptDir>/task-1-restart-red.log`.
  - [ ] `ValidateExecutablePlan` accepts both `force-stop -> restart` device sequences and rejects restart-before-force-stop, duplicate restart without a new force-stop, reboot, relay lifecycle, and UI-only tokens before any bridge call.
  - [ ] `ADBBridge.Restart` calls `StartPackage` for exactly the named device and validated package; metacharacters or whitespace reach no ADB runner.
  - [ ] A bridge action that blocks until context cancellation returns within `stepTimeout` plus a 250 ms scheduling allowance and produces failed evidence with stable `execution_failed`, never a hang or pass.
  - [ ] `cd e2e && go test ./internal/adb ./internal/scenario ./cmd/twinotify-e2e -race -count=1 -run 'Test.*(Restart|ActionTimeout|StartPackage|ExecutablePlan)'` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: A and B package restart actions use the typed launcher and remain bounded
    Tool:     bash
    Steps:    mkdir -p .omo/evidence && bash -o pipefail -c 'cd e2e && go test ./internal/adb ./internal/scenario -race -count=1 -run "Test.*(Restart|ActionTimeout|StartPackage)" 2>&1 | tee ../.omo/evidence/task-1-restart-green.log'
    Expected: Go exits 0; tests observe one fixed-argument package launch on the selected device and bounded cancellation for a blocking restart.
    Evidence: <attemptDir>/task-1-restart-green.log

  Scenario: Unsafe or unordered lifecycle tokens fail before device contact
    Tool:     bash
    Steps:    bash -o pipefail -c 'cd e2e && go test ./internal/scenario ./cmd/twinotify-e2e -race -count=1 -run "Test.*(RestartBeforeForceStop|UnsupportedLifecycle|Preflight).*" 2>&1 | tee ../.omo/evidence/task-1-restart-error.log'
    Expected: Go exits 0; assertions prove zero bridge/ADB calls for restart-before-force-stop, reboot, relay restart, and unsafe package inputs.
    Evidence: <attemptDir>/task-1-restart-error.log
  ```

  Commit: YES | Message: `test(e2e): support bounded package restarts` | Files: [`e2e/internal/scenario/executor.go`, `e2e/internal/scenario/scenario_test.go`, `e2e/internal/adb/adb_test.go`]

- [ ] 2. Generalize notification actions and oracles across A and B without weakening validation

  What to do: Add RED table tests first. Parse only `A.shell.post`, `B.shell.post`, `A.shell.cancel`, and `B.shell.cancel` with the existing bounded tag/text grammar. Generalize tracked-sequence selection to `{A,B}.tracked.sequence:<positive-int>` so it reads only the named recipient and its baseline. Generalize custody to `{A,B}.custody.{lan,relay}:<allowlisted-event>:<positive-delta>` while retaining the existing event allowlist and positive bounded integer checks. Keep peer-receipt predicates device-neutral. Refactor helpers so named-device selection is centralized and cannot accidentally swap origin/recipient. Update semantic fake bridges so `Post(device, ...)` writes the opposite device, records custody/receipt on the origin, and continues to model exact materialized sequence.

  Must NOT do: Do not accept arbitrary device letters, route strings, event names, negative/zero deltas, free-form predicates, or B-origin control commands that no scenario needs. Do not make mirror presence alone sufficient for reverse delivery.

  Parallelization: Can parallel: NO | Wave 1 | Blocks: [3] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `e2e/internal/scenario/executor.go:71-154` - current A-only shell action grammar and manual split helper
  - API/Type: `e2e/internal/scenario/executor.go:156-184` - closed list of known predicates
  - Pattern:  `e2e/internal/scenario/executor.go:648-731` - B-only tracked sequence and LAN-only custody evaluation
  - Pattern:  `e2e/internal/scenario/executor.go:869-875` - baseline-aware new-active helper
  - Pattern:  `e2e/internal/scenario/executor.go:931-959` - real A/B client selection already supports posting on either device
  - Test:     `e2e/internal/scenario/route_predicate_test.go:13-69` - named-device route isolation tests
  - Test:     `e2e/internal/scenario/scenario_test.go:914-1110` - semantic fake currently hard-codes B as the post recipient

  Acceptance criteria (agent-executable only):
  - [ ] RED evidence shows B-origin post and A-recipient sequence tests fail before the grammar/evaluator change at `<attemptDir>/task-2-direction-red.log`.
  - [ ] Parser tables accept only the four A/B post/cancel forms and reject invalid devices, empty tags, excess components, and arbitrary operations without invoking a bridge.
  - [ ] `A.tracked.sequence:1` can only pass from a new exact active/materialized sequence on A; a matching change on B alone fails. The existing B predicate has the symmetric guarantee.
  - [ ] Custody assertions accept only `lan` or `relay`, read the named origin, require an allowlisted event and positive delta, and map missing LAN proof to `missing_lan_custody` and missing relay proof to `missing_relay_custody`.
  - [ ] `cd e2e && go test ./internal/scenario -race -count=1 -run 'Test.*(Directional|Reverse|Tracked|Custody|ActionParser)'` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Reverse post reaches only the named recipient oracle
    Tool:     bash
    Steps:    bash -o pipefail -c 'cd e2e && go test ./internal/scenario -race -count=1 -run "Test.*(Directional|Reverse|Tracked).*" 2>&1 | tee ../.omo/evidence/task-2-direction-green.log'
    Expected: Go exits 0; B-origin post produces a new exact sequence only on A and the symmetric wrong-device fixtures fail their predicates.
    Evidence: <attemptDir>/task-2-direction-green.log

  Scenario: Predicate and action grammars remain closed
    Tool:     bash
    Steps:    bash -o pipefail -c 'cd e2e && go test ./internal/scenario -race -count=1 -run "Test.*(ActionParser|Custody|UnknownPredicate).*" 2>&1 | tee ../.omo/evidence/task-2-direction-error.log'
    Expected: Go exits 0; invalid device, route, event, delta, and operation cases are rejected before any fake bridge call.
    Evidence: <attemptDir>/task-2-direction-error.log
  ```

  Commit: YES | Message: `test(e2e): generalize directional LAN oracles` | Files: [`e2e/internal/scenario/executor.go`, `e2e/internal/scenario/scenario_test.go`, `e2e/internal/scenario/route_predicate_test.go`]

- [ ] 3. Add reverse, fallback-return, and restart-persistence children to the aggregate

  What to do: Add RED scenario-plan and executor tests before changing the plans. Add `lan-direct-reverse-delivery` with authenticated LAN on both devices, one B-origin post, exact sequence/materialization on A, B-side LAN custody and peer-receipt deltas, and `direct.terminal`. Strengthen `lan-relay-fallback-return` to: observe LAN on both; inject app-internal `SET_LAN_AVAILABLE=false` on both; observe authenticated relay on both; deliver one uniquely tagged A-origin notification with relay custody/receipt; restore both LAN preferences; observe authenticated LAN on both; deliver a second unique notification with LAN custody and the second receipt; and reach direct terminal zero. Ordered content-free route events must identify the relay-carried tag before the LAN-carried tag. Strengthen `lan-restart-persistence` to: observe LAN; post a unique item and observe nonzero durable outbox before stopping A; force-stop/restart A; re-observe authenticated LAN and exact receiver materialization/custody/receipt; then force-stop/restart B; re-observe authenticated LAN; deliver a second unique item; and require total LAN custody/receipt deltas plus direct terminal zero. Add all three children once to `lan-product-correctness`; keep unique tag rewriting deterministic and `lan-direct-unpair-during-traffic` last.

  Must NOT do: Do not use OS network actions for fallback, auto-repair pairing, continue after a failed child, reuse tags across children, or let terminal LAN status retroactively prove the relay leg. Do not call restart without the paired force-stop.

  Parallelization: Can parallel: NO | Wave 2 | Blocks: [4] | Blocked by: [1, 2]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `e2e/internal/scenario/scenario.go:15-43` - step/child plan model and flattened action list
  - Pattern:  `e2e/internal/scenario/scenario.go:103-176` - current direct, fallback, and restart scenario definitions
  - Pattern:  `e2e/internal/scenario/scenario.go:231-246` - eight-child aggregate and unique-tag rewriting
  - Pattern:  `e2e/internal/scenario/executor.go:277-371` - route event capture occurs at each action/predicate boundary
  - Pattern:  `e2e/internal/scenario/executor.go:374-400` - final route block derives from the last sampled delivery while preserving ordered events
  - Pattern:  `e2e/internal/scenario/executor.go:435-455` - aggregate fail-fast behavior and completed-child retention
  - Pattern:  `e2e/internal/scenario/executor.go:482-490` - app-internal LAN availability fault and cleanup bookkeeping
  - Test:     `e2e/internal/scenario/scenario_test.go:404-457` - bounded counts, aggregate order, unique names/tags, and unpair-last checks
  - Test:     `e2e/internal/scenario/scenario_test.go:605-680` - failed-child retention and fallback route evidence patterns
  - Test:     `e2e/cmd/twinotify-e2e/main_test.go:102-123` - executable preflight before ADB

  Acceptance criteria (agent-executable only):
  - [ ] RED output at `<attemptDir>/task-3-scenarios-red.log` proves the aggregate is missing exactly the three required children and restart preflight is unsupported before implementation.
  - [ ] `PlanWithBurstCount("lan-product-correctness", 8)` contains exactly eleven children in deterministic order: delivery, reverse delivery, dismiss, update, peer dismiss, call state, snapshot/receipt, fallback-return, restart-persistence, burst/backpressure, unpair-during-traffic.
  - [ ] Every child name and every notification tag is unique across the aggregate; unpair is the last child; the aggregate stops at its first failed child and retains all earlier results plus that failed result.
  - [ ] Reverse delivery fails if A lacks exact materialization, B lacks LAN custody, B lacks a peer receipt, either route is not authenticated LAN, or terminal state is nonzero.
  - [ ] Fallback-return fails if either authenticated relay transition, relay-carried route event, relay custody, restored authenticated LAN transition, LAN-carried route event, LAN custody, receipt delta, cleanup restoration, or terminal zero is missing.
  - [ ] Restart-persistence fails unless nonzero durable work is observed before A stops, both A and B execute ordered force-stop/restart pairs, LAN is reauthenticated after each restart, both deliveries have LAN custody/receipts, and terminal state is clean.
  - [ ] `cd e2e && go test ./internal/scenario ./cmd/twinotify-e2e -race -count=1 -run 'Test.*(LanProduct|ReverseDelivery|Fallback|RestartPersistence|Preflight)'` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: All eleven product children execute against deterministic production-shaped observations
    Tool:     bash
    Steps:    bash -o pipefail -c 'cd e2e && go test ./internal/scenario ./cmd/twinotify-e2e -race -count=1 -run "Test.*(LanProductCorrectness|ReverseDelivery|FallbackEvidence|RestartPersistence|Semantic).*" 2>&1 | tee ../.omo/evidence/task-3-scenarios-green.log'
    Expected: Go exits 0; aggregate order is exact, tags are unique, both route legs and both restart pairs are present, and unpair remains last.
    Evidence: <attemptDir>/task-3-scenarios-green.log

  Scenario: Missing route, custody, receipt, persistence, or direction evidence fails closed
    Tool:     bash
    Steps:    bash -o pipefail -c 'cd e2e && go test ./internal/scenario -race -count=1 -run "Test.*(RejectsMissing|StopsAtFailure|InterruptedFallback|Restart.*Failure).*" 2>&1 | tee ../.omo/evidence/task-3-scenarios-error.log'
    Expected: Go exits 0 because every deliberately incomplete fixture returns a stable failed result, cleanup attempts both LAN restorations, and no later child runs.
    Evidence: <attemptDir>/task-3-scenarios-error.log
  ```

  Commit: YES | Message: `test(e2e): complete LAN correctness scenarios` | Files: [`e2e/internal/scenario/scenario.go`, `e2e/internal/scenario/executor.go`, `e2e/internal/scenario/scenario_test.go`, `e2e/internal/scenario/route_predicate_test.go`, `e2e/cmd/twinotify-e2e/main_test.go`]

- [ ] 4. Make per-child evidence mandatory and semantically closed-world

  What to do: Add RED shell and Go tests first. Expand `required_children` to the same exact eleven-child order. Add verifier branches and synthetic fixtures for reverse delivery, fallback-return, and restart-persistence. Reverse evidence must prove B-origin post, A exact sequence/materialization predicate, B LAN custody/receipt, authenticated LAN route, and terminal zero. Fallback evidence must prove ordered relay and LAN route events tied to distinct tags, relay then LAN custody deltas, two receipts, final authenticated LAN on both, and terminal zero. Restart evidence must prove nonzero outbox before A force-stop, ordered A and B force-stop/restart events, reauthenticated LAN predicates after each restart, two LAN custody/receipt deltas, and terminal zero. Extend self-tests to delete each new child and corrupt one required semantic field/event in each branch. Extend Go evidence tests so a failed aggregate write retains earlier passed children and the failed child as independent `status=failed` artifacts, while a root `passed` claim with missing/failed children is rejected by the shell verifier.

  Must NOT do: Do not put endpoint, serial, package data, token, SSID, IP, notification content, or raw protocol material into events or artifacts. Do not accept extra child directories, renamed children, child order changes, a passed root with incomplete children, or route proof derived only from final state.

  Parallelization: Can parallel: NO | Wave 2 | Blocks: [5, 6] | Blocked by: [3]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `e2e/internal/scenario/evidence.go:15-29` - result children are stored separately from the closed root JSON schema
  - Pattern:  `e2e/internal/scenario/evidence.go:47-104` - full-tree validation happens before child-directory replacement
  - Pattern:  `e2e/internal/scenario/evidence.go:105-139` - four derived artifacts per root/child
  - Test:     `e2e/internal/scenario/route_evidence_test.go:208-256` - independent child artifacts and no partial replacement on validation failure
  - API/Type: `scripts/verify-lan-product-evidence.sh:6-15` - current eight-child required inventory
  - Pattern:  `scripts/verify-lan-product-evidence.sh:21-103` - artifact safety, derived-file consistency, observation bounds, and route closed world
  - Pattern:  `scripts/verify-lan-product-evidence.sh:106-204` - child-specific semantic assertions
  - Pattern:  `scripts/verify-lan-product-evidence.sh:206-230` - exact child count/order and fail-closed iteration
  - Test:     `scripts/verify-lan-product-evidence.sh:307-405` - synthetic fixture generator and mutation-based self-test

  Acceptance criteria (agent-executable only):
  - [ ] RED evidence at `<attemptDir>/task-4-evidence-red.log` shows the old verifier accepts an eight-child fixture and lacks semantic branches for the three new scenarios.
  - [ ] The verifier accepts exactly one full eleven-child fixture and rejects every missing, extra, misordered, failed, unsafe-permission, secret-bearing, or inconsistent root/child artifact set.
  - [ ] Each new child has at least one self-test mutation that preserves valid JSON but removes its decisive semantic proof; all such fixtures exit nonzero.
  - [ ] A failed execution writes independent artifacts for every completed child and the failed child, with no fabricated directory for an unrun child; those artifacts remain available even though the CLI/Make command exits nonzero.
  - [ ] `./scripts/verify-lan-product-evidence.sh --self-test` and `cd e2e && go test ./internal/scenario -race -count=1 -run 'Test.*AggregateEvidence'` both exit 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Complete eleven-child synthetic evidence passes
    Tool:     bash
    Steps:    bash -o pipefail -c './scripts/verify-lan-product-evidence.sh --self-test 2>&1 | tee .omo/evidence/task-4-evidence-green.log'
    Expected: Script exits 0 and prints `direct LAN product evidence self-test passed` only after the full eleven-child fixture is accepted.
    Evidence: <attemptDir>/task-4-evidence-green.log

  Scenario: Partial aggregate and semantically hollow new children fail
    Tool:     bash
    Steps:    bash -o pipefail -c 'cd e2e && go test ./internal/scenario -race -count=1 -run "Test.*AggregateEvidence" 2>&1 | tee ../.omo/evidence/task-4-evidence-error.log && cd .. && ./scripts/verify-lan-product-evidence.sh --self-test >/dev/null'
    Expected: Command exits 0; Go assertions preserve failed-child artifacts and the verifier self-test confirms missing/corrupted reverse, fallback, and restart evidence is rejected.
    Evidence: <attemptDir>/task-4-evidence-error.log
  ```

  Commit: YES | Message: `test(e2e): require complete LAN evidence` | Files: [`e2e/internal/scenario/evidence.go`, `e2e/internal/scenario/route_evidence_test.go`, `scripts/verify-lan-product-evidence.sh`]

- [ ] 5. Put the complete LAN evidence contract in mandatory host verification

  What to do: Start with RED shell-structure tests. Keep `e2e-lan-product` as a fail-fast two-line runtime gate: validate distinct explicit serials/evidence directory, run the aggregate CLI, then run the strict verifier only if the CLI succeeds. Extend `e2e/scripts/lan_product_target_test.sh` for the eleven documented children, dry-run safety, CLI-before-verifier ordering, failed CLI/no-verifier behavior where testable without ADB, and the full verifier self-test. Add this shell test exactly once to `host-verify` after E2E Go race/vet and before later workflow-contract checks. Add the same command exactly once to `.github/workflows/e2e-host.yml`, add the LAN verifier path to its push triggers, and update `scripts/verify-host-workflows.sh` plus its mutation tests so deleting, duplicating, reordering, commenting, or conditionally running the LAN gate fails.

  Must NOT do: Do not make host CI require ADB, devices, credentials, Docker, or physical evidence. Do not call the physical `e2e-lan-product` target from CI. Do not allow `continue-on-error`, conditional execution, duplicate invocations, or an unpinned action.

  Parallelization: Can parallel: NO | Wave 3 | Blocks: [6] | Blocked by: [4]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `Makefile:42-55` - exact ordered `host-verify` recipe guarded by structural tests
  - API/Type: `Makefile:65-69` - physical aggregate target, preconditions, CLI, and verifier ordering
  - Test:     `e2e/scripts/lan_product_target_test.sh:8-31` - Make precondition, dry-run safety, and verifier self-test
  - Test:     `e2e/scripts/lan_product_target_test.sh:33-109` - documentation checker fixtures tied to the required child list
  - Pattern:  `.github/workflows/e2e-host.yml:4-23` - push path allowlist
  - Pattern:  `.github/workflows/e2e-host.yml:28-53` - host-only command job
  - API/Type: `scripts/verify-host-workflows.sh:395-448` - approved command closed world
  - API/Type: `scripts/verify-host-workflows.sh:465-513` - exact `host-verify` recipe contract
  - Test:     `scripts/verify-host-workflows_test.sh:75-103` - mutation rejection patterns for required host commands
  - Test:     `scripts/verify-host-workflows_test.sh:172-197` - Make recipe omission, comment, order, and duplication rejection

  Acceptance criteria (agent-executable only):
  - [ ] RED evidence at `<attemptDir>/task-5-host-gate-red.log` proves `host-verify` and `e2e-host.yml` currently omit `./e2e/scripts/lan_product_target_test.sh`.
  - [ ] `host-verify` and `e2e-host.yml` each run `./e2e/scripts/lan_product_target_test.sh` exactly once in fail-fast order; workflow push paths include `scripts/verify-lan-product-evidence.sh`.
  - [ ] Structural self-tests reject removal, duplication, reordering, comment-only presence, conditional/continue-on-error use, and an extra unapproved host command.
  - [ ] The host lane invokes only fixture/self-test mode and never ADB, a physical target, radio mutation, credentials, or physical evidence.
  - [ ] `./e2e/scripts/lan_product_target_test.sh`, `./scripts/verify-host-workflows.sh`, and `./scripts/verify-host-workflows_test.sh` all exit 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Mandatory host LAN gate passes without devices
    Tool:     bash
    Steps:    bash -o pipefail -c './e2e/scripts/lan_product_target_test.sh 2>&1 | tee .omo/evidence/task-5-host-gate-green.log && ./scripts/verify-host-workflows.sh && ./scripts/verify-host-workflows_test.sh'
    Expected: All scripts exit 0; the LAN target self-test validates Make preconditions, eleven-child evidence, and documentation fixtures without invoking ADB.
    Evidence: <attemptDir>/task-5-host-gate-green.log

  Scenario: Workflow and Make omissions fail closed
    Tool:     bash
    Steps:    bash -o pipefail -c './scripts/verify-host-workflows_test.sh 2>&1 | tee .omo/evidence/task-5-host-gate-error.log'
    Expected: Script exits 0 only after its internal mutations prove that removing or weakening the LAN target test from Make/workflow is rejected.
    Evidence: <attemptDir>/task-5-host-gate-error.log
  ```

  Commit: YES | Message: `ci(e2e): enforce the complete LAN host gate` | Files: [`Makefile`, `e2e/scripts/lan_product_target_test.sh`, `.github/workflows/e2e-host.yml`, `scripts/verify-host-workflows.sh`, `scripts/verify-host-workflows_test.sh`]

- [ ] 6. Document the complete automated gate without overstating physical acceptance

  What to do: Update the active direct-LAN plans, E2E README, and manual scenario table to list the exact eleven-child aggregate, describe reverse B-to-A delivery, app-internal LAN fault injection and relay return, bounded force-stop/launcher restart on both phones, per-child artifacts, and the exact Make invocation. Cite the reachable implementation/evidence/CI commits from Tasks 1-5. Keep every hardware, no-uplink, visual, and protected-release item unchecked with the exact phrase `pending physical two-phone run`. Extend the documentation checker and its fixtures to require all eleven names, reject the old eight-child count/claims, reject unknown `lan-*` scenarios, and verify every documented path, link, target, and checked commit.

  Must NOT do: Do not mark Task 9 physical items complete, invent device serials/results, claim bidirectional hardware proof, or present host fixtures as physical evidence. Do not rewrite superseded historical plans except the two active status plans explicitly named below.

  Parallelization: Can parallel: NO | Wave 4 | Blocks: [final] | Blocked by: [4, 5]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `e2e/README.md:8-35` - current eight-child gate, safe invocation, and pending-physical disclaimer
  - Pattern:  `docs/test-scenarios.md:397-440` - current scenario matrix, eight-child aggregate claim, safety rules, and route semantics
  - Pattern:  `docs/superpowers/plans/2026-08-20-direct-lan-delivery.md:157-174` - Task 9 automation claims and pending physical requirements
  - Pattern:  `docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md:42-58` - product gate status and pending hardware matrix
  - API/Type: `scripts/verify-lan-product-evidence.sh:234-305` - fail-closed documentation status checker
  - Test:     `e2e/scripts/lan_product_target_test.sh:33-109` - accepted/rejected documentation fixtures

  Acceptance criteria (agent-executable only):
  - [ ] All four live documents say eleven children, name each child exactly once where the aggregate is enumerated, and distinguish A-to-B from B-to-A notification delivery.
  - [ ] Documents state that fallback uses app-internal route availability rather than OS radio mutation, restart uses force-stop plus typed launcher without clearing data, and per-child artifacts remain available on failure.
  - [ ] Every checked implementation line cites a reachable ancestor commit; all physical/no-uplink/UI acceptance remains unchecked and includes `pending physical two-phone run`.
  - [ ] The checker rejects stale `runs eight`, omitted new scenario names, unknown `lan-*` names, checked physical claims, unreachable commits, and nonexistent paths/links/Make targets.
  - [ ] `./scripts/verify-lan-product-evidence.sh --check-doc-status docs/test-scenarios.md docs/superpowers/plans/2026-08-20-direct-lan-delivery.md docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md e2e/README.md` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Live documentation matches the eleven-child gate and remains truthful
    Tool:     bash
    Steps:    bash -o pipefail -c './scripts/verify-lan-product-evidence.sh --check-doc-status docs/test-scenarios.md docs/superpowers/plans/2026-08-20-direct-lan-delivery.md docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md e2e/README.md 2>&1 | tee .omo/evidence/task-6-docs-green.log'
    Expected: Checker exits 0; every required scenario, command, path, commit citation, and pending physical statement resolves.
    Evidence: <attemptDir>/task-6-docs-green.log

  Scenario: Stale or overstated LAN documentation is rejected
    Tool:     bash
    Steps:    bash -o pipefail -c './e2e/scripts/lan_product_target_test.sh 2>&1 | tee .omo/evidence/task-6-docs-error.log'
    Expected: Script exits 0 only after mutation fixtures prove stale eight-child copy, missing reverse/fallback/restart names, checked physical claims, unknown scenarios, bad commits, and broken paths fail.
    Evidence: <attemptDir>/task-6-docs-error.log
  ```

  Commit: YES | Message: `docs: record complete LAN gate coverage` | Files: [`docs/test-scenarios.md`, `docs/superpowers/plans/2026-08-20-direct-lan-delivery.md`, `docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md`, `e2e/README.md`, `scripts/verify-lan-product-evidence.sh`, `e2e/scripts/lan_product_target_test.sh`]

## Final verification wave (MANDATORY - after all implementation tasks)
> Runs in PARALLEL. ALL must APPROVE. Surface results to the caller and wait for an explicit "okay" before declaring complete.
- [ ] F1. Plan compliance audit - every task done, every automated acceptance criterion met, exact eleven-child order confirmed, and concurrent Plan018 files absent from this plan's diff. Evidence: `<attemptDir>/final-plan-compliance.md`.
- [ ] F2. Code quality review - `cd e2e && gofmt -l .` is empty; `cd e2e && go vet ./...` and `cd e2e && go test ./... -race -count=1` pass; reviewer confirms bounded contexts, named-device isolation, fail-fast cleanup, no sensitive evidence, and no dead grammar. Evidence: `<attemptDir>/final-code-review.md`.
- [ ] F3. Real manual QA - run all host-side shell/verifier scenarios and inspect the root plus every child artifact for the full synthetic fixture. If two already-paired physical phones and operator authorization are unavailable, record `PENDING: physical two-phone run` for fallback, both restarts, reverse delivery, no-uplink, and UI acceptance. Never substitute host fixtures or an emulator. Evidence: `<attemptDir>/final-manual-qa.md` plus `<attemptDir>/lan-product-synthetic/`.
- [ ] F4. Scope fidelity - diff contains only E2E harness/evidence, Make/workflow contract, and active documentation paths listed above; no Android production, relay, proto, Room, UI, package data, radio/network, secret, worktree, or Plan018 changes are included. Evidence: `<attemptDir>/final-scope-fidelity.md`.

Final automated gate commands:

```bash
mkdir -p .omo/evidence
git diff --check
cd e2e && gofmt -l . && go vet ./... && go test ./... -race -count=1
cd ..
./e2e/scripts/lan_product_target_test.sh
./scripts/verify-lan-product-evidence.sh --self-test
./scripts/verify-host-workflows.sh
./scripts/verify-host-workflows_test.sh
./scripts/verify-lan-product-evidence.sh --check-doc-status \
  docs/test-scenarios.md \
  docs/superpowers/plans/2026-08-20-direct-lan-delivery.md \
  docs/superpowers/plans/2026-08-26-live-direct-lan-service-integration.md \
  e2e/README.md
make host-verify
git status --short
```

`make host-verify` is host-only and must not contact ADB. The physical command below is a separate pending release gate and must not run without two explicit already-paired hardware serials, an external private evidence directory, and operator authorization:

```bash
E2E_DEVICE_A='<serial-a>' \
E2E_DEVICE_B='<serial-b>' \
E2E_LAN_PRODUCT_EVIDENCE_DIR='/private/path/lan-product' \
make e2e-lan-product
```

## Commit strategy
- One logical change per commit. Conventional Commits (`<type>(<scope>): <subject>` body + footer).
- Atomic: every commit builds and passes tests on its own.
- No "WIP" / "fix typo squash later" commits on the final branch - clean up before merge.
- Reference the plan file path in the final commit footer: `Plan: .omo/plans/complete-lan-gate-coverage.md`.
- Work in the primary checkout only. Stage only the files owned by the current task, never the concurrent Plan018 files or `.omo/evidence/**`; do not push.

## Success criteria
- All Must-Have automation shipped; all host QA scenarios pass with captured evidence; F1, F2, and F4 approve; F3 approves the host artifact inspection and remains explicitly `PENDING: physical two-phone run` for hardware-only acceptance until real evidence exists; commit history is clean.
- The caller sees F1-F4, including the physical pending status, and explicitly says `okay` before the implementation is declared complete.
