# Plan 002: Make Android E2E scenarios executable and evidence truthful

> **Executor instructions**: Follow every step and gate. Work only in the
> primary checkout; never create a worktree. Do not mark unsupported scenarios
> as passing. Update `advisor-plans/README.md` when done.
>
> **Drift check (run first)**:
> `git diff --stat f667dd1..HEAD -- e2e/internal/scenario e2e/cmd/twinotify-e2e e2e/scripts/run-two-emulators.sh e2e/scripts/validate-workflow.sh .github/workflows/e2e-android.yml`

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `f667dd1`, 2026-08-19

## Why this matters

The Android E2E suite currently mixes real bridge operations, unsupported
operations, and string actions that silently return success. It also writes
static `status: pass` artifact files after the command returns. This can turn a
partial smoke check into a release-looking correctness claim. The repaired
runner must execute only capabilities it owns, preserve same-tag update
identity, fail closed on unsupported actions, and derive artifacts from observed
state rather than templates.

## Current state

- `e2e/internal/scenario/executor.go:66-106` parses action strings with one
  `SplitN`. `A.shell.post:n1:v1` therefore posts tag `n1:v1`, not tag `n1` with
  update content `v1`.
- `executor.go:93-100` returns `ErrUnsupportedEnvironment` for restart/UI/relay
  actions, but returns `nil` for broad strings containing `outbox`, `health`,
  `mirror`, `receipt`, and similar words.
- `scenario.go:39-42` expects three updates to one notification and sequence 3.
- `scenario.go:103-108` puts relay restart, process restart, reboot, and expiry
  into `all-correctness`, even though the executor cannot run them.
- `run-two-emulators.sh:169-175` copies health output, then fabricates three
  generic pass-shaped state/timeline/metrics files.
- `validate-workflow.sh:7` requires `all-correctness` as the manual default,
  while the scheduled workflow selects `post`.
- `scenario_test.go:95-104` checks only that plans end in a predicate. It does
  not prove that each action is executable or that evidence derives from
  observations.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused Go | `cd e2e && go test ./internal/scenario ./cmd/twinotify-e2e -race -count=1` | exit 0 |
| Full Go | `cd e2e && go test ./... -race -count=1 && go vet ./...` | exit 0 |
| Shell syntax | `bash -n e2e/scripts/run-two-emulators.sh e2e/scripts/validate-workflow.sh` | exit 0 |
| Workflow contract | `./e2e/scripts/validate-workflow.sh` | prints validation passed |
| Diff hygiene | `git diff --check` | exit 0, no output |

## Scope

**In scope**:

- `e2e/internal/scenario/scenario.go`
- `e2e/internal/scenario/executor.go`
- `e2e/internal/scenario/scenario_test.go`
- create `e2e/internal/scenario/evidence.go` and test if a separate artifact
  boundary keeps the implementation smaller
- `e2e/cmd/twinotify-e2e/main.go` and focused tests
- `e2e/scripts/run-two-emulators.sh`
- `e2e/scripts/validate-workflow.sh`
- `.github/workflows/e2e-android.yml`
- `advisor-plans/README.md` status only

**Out of scope**:

- inventing relay fault injection, UI automation, reboot, or restart support;
- weakening evidence verification to accept placeholder JSON;
- changing production notification protocol or Android app behavior;
- claiming physical scenarios were run;
- worktrees.

## Git workflow

Stay in the primary checkout. Use Conventional Commits, for example
`fix(e2e): make scenario evidence state-derived`. Do not push without explicit
operator instruction.

## Steps

### Step 1: Replace permissive action interpretation with a closed contract

Write RED tests proving:

- update posts use the same tag `n1` with payload variants `v1`, `v2`, `v3`;
- any unknown action fails and makes zero bridge calls;
- an assertion-like action such as `A.outbox.nonzero` cannot succeed as a no-op;
- `all-correctness` is rejected by an executable-plan validator while it
  contains unsupported actions;
- `post`, corrected `update`, `dismiss-origin`, `rapid-post-update-cancel`, and
  `offline` contain only supported bridge actions and observable predicates.

Prefer a typed `Action` representation (`Kind`, `Device`, `Tag`, `Text`) or an
equally closed parser. Do not add another substring allowlist. Predicates are
observations, not actions, and must never be dispatched through `action()`.

**Verify**: focused Go tests show the intended RED, then pass after production
implementation.

### Step 2: Make the default scenario executable

Add a pure capability validator used by both `Plan` and the CLI. Until the
missing relay/restart/UI hooks exist:

- retain unsupported scenario names only if invoking them fails with
  `ErrUnsupportedEnvironment` before mutating either device;
- define an executable aggregate, for example `core-correctness`, from only the
  supported scenarios;
- change the manual workflow default from `all-correctness` to the executable
  aggregate;
- keep the scheduled scenario as `post`;
- update `validate-workflow.sh` to assert those exact values.

Do not rename an incomplete subset to `all-correctness`. The release verifier
must continue to treat full all-correctness as pending until it is real.

### Step 3: Generate evidence from the executor result

Introduce a machine-readable result containing:

- scenario ID and terminal status;
- sanitized ordered action/event IDs actually executed;
- before/after observation summaries for A and B;
- convergence measurements already available from provider snapshots;
- bounded error code when unsupported or failed.

The CLI writes this result atomically into the run directory. Rework
`run-two-emulators.sh` so `state.json`, `timeline.json`, and `metrics.json` are
derived from that result. Delete the three static `printf ... "pass"` lines.
Keep health artifacts copied from real status output. On failure, preserve a
sanitized failed result and exit nonzero.

Write tests that mutate a fake observation and prove the artifact changes, and
that an unsupported action can never produce `status: pass`.

### Step 4: Exercise the local emulator path without overclaiming

Run the shell syntax and workflow validator. If local AVD resources are
available, run `E2E_SCENARIO=post make e2e-emulator` and verify the five
sanitized artifacts are nonempty and schema-valid. If AVD resources are not
available, record that as pending and rely only on the deterministic Go/shell
gates. Never edit an artifact to make it pass.

## Test plan

- Exact same-tag update parsing and bridge calls.
- Closed action inventory and fail-before-mutation behavior.
- Supported aggregate contains only executable actions.
- Unsupported scenario produces an error result and nonzero exit.
- Evidence values come from fake bridge observations, not constants.
- Failure evidence is sanitized and contains no notification contents or
  credentials.
- Existing offline-pairing secret-boundary tests remain green.

## Done criteria

- [ ] No substring branch returns success for assertion-like action names.
- [ ] Update posts reuse one tag and drive sequence 1, 2, 3.
- [ ] Workflow default names only an executable aggregate.
- [ ] Unsupported full scenarios fail before device mutation.
- [ ] No static pass artifact remains in `run-two-emulators.sh`.
- [ ] Focused/full Go, shell syntax, workflow validator, and diff check pass.
- [ ] Physical/AVD execution is claimed only if raw evidence exists.
- [ ] README plan status is updated.

## STOP conditions

- The only way to implement an action is to weaken Android debug-control
  authentication or expose notification content.
- Scenario execution cannot return real observations without modifying the
  production protocol.
- An existing release contract requires unsupported actions to be marked pass.
- A step requires radio changes on the operator's physical phones while they are
  unavailable.

## Maintenance notes

When relay fault injection and restart/UI bridges are later implemented, add
them as explicit capabilities and tests, then expand the executable aggregate.
Only after every child is executable should the release `all-correctness` claim
be restored.
