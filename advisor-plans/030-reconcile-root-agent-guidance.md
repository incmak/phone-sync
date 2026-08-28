# Plan 030: Make root agent guidance match the live repository

> **Executor instructions**: Work in the primary checkout only. Read
> `AGENTS.md` fully before editing it. This is a documentation-contract change:
> preserve every truthful physical and protected-release pending marker. Use
> test-first shell fixtures. Do not use a worktree, device, network, EAS
> credentials, or push. Update `advisor-plans/README.md` only after all checks
> and independent truth review pass.
>
> **Drift check (run first)**:
> `git diff --stat 6e449b9..HEAD -- AGENTS.md mobile/package.json mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt docs/superpowers/plans/2026-08-20-direct-lan-delivery.md advisor-plans/README.md scripts/verify-project-docs.sh scripts/verify-project-docs_test.sh Makefile`
>
> The drift command intentionally includes both editable scope and read-only
> sources of truth. If any listed path changed since this plan was written,
> compare the current state below with the live files. Stop on a material
> mismatch rather than guessing a new status or editing a read-only source.

## Status

- **Status**: DONE
- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `6e449b9`, 2026-08-28

## Why this matters

`AGENTS.md` is the first operational contract read by coding agents, but four
of its load-bearing facts predate completed work. It identifies Expo SDK 54
instead of 57, Room version 5 instead of 7, direct-LAN Tasks 5-9 as open even
though their implementation and host automation landed, and an advisor ledger
ending at Plan 010 instead of 029. An executor following those statements can
create the wrong migration, redo completed transport work, or misstate what is
actually left. The fix must make those facts executable and regression-tested
without converting pending physical or protected-release evidence into a pass.

## Current state

- `AGENTS.md:19` calls the mobile app “Expo SDK 54 / RN”, while
  `mobile/package.json:20-36` pins Expo `57.0.17`, React Native `0.86.3`, and
  the matching Expo 57 packages.
- `AGENTS.md:76` and `AGENTS.md:116` say Room is version 5 and prescribe a
  5-to-6 migration. `NotificationDb.kt:195-208` declares database version 7;
  the file registers explicit migrations through `MIGRATION_6_7`, and committed
  schemas exist through `android/schemas/.../7.json`.
- `AGENTS.md:128` says direct-LAN Tasks 1-4 landed and 5-9 remain open.
  `docs/superpowers/plans/2026-08-20-direct-lan-delivery.md:92-184` records the
  implementation and host automation for Tasks 5-9 complete, with only named
  physical handset checks still pending.
- The same `AGENTS.md:128` sentence says the advisor ledger tracks Plans
  001-010. `advisor-plans/README.md:29-61` tracks Plans 001-029. Plans 004 and
  015 are the only blocked rows; Plan 004 awaits owner-controlled EAS inputs,
  and Plan 015 awaits physical call evidence.
- `scripts/verify-project-docs.sh` currently validates README, protocol,
  release-evidence, and scenario truth but never checks `AGENTS.md`.
  `scripts/verify-project-docs_test.sh` already supplies isolated fixtures and
  mutation-style rejection cases; extend that pattern rather than adding a new
  verifier.
- `Makefile:42-59` already runs both project-doc checks inside `host-verify`.
  Keep that recipe unchanged unless the verifier entry point itself changes.

Repository wording conventions: distinguish source/host completion from
release acceptance; use the exact phrase `pending physical two-phone run` for
hardware-only LAN evidence; call local APKs QA artifacts, not release
candidates; never name or expose protected secret values.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Focused docs self-test | `./scripts/verify-project-docs_test.sh` | exit 0; each stale `AGENTS.md` mutation is rejected |
| Live docs contract | `./scripts/verify-project-docs.sh` | exit 0 against the repository |
| Host workflow contract | `./scripts/verify-host-workflows.sh && ./scripts/verify-host-workflows_test.sh` | both exit 0 |
| Exact host gate | `make host-verify` | exit 0 |
| Hygiene | `git diff --check` | exit 0 |

## Scope

**In scope**:

- `AGENTS.md`
- `scripts/verify-project-docs.sh`
- `scripts/verify-project-docs_test.sh`
- `advisor-plans/README.md` status only after completion

**Read-only sources of truth**:

- `mobile/package.json`
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`
- `mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/7.json`
- `docs/superpowers/plans/2026-08-20-direct-lan-delivery.md`

**Out of scope**:

- Kotlin, TypeScript, Go, schema, dependency, Room migration, transport, UI, or
  release-workflow behavior;
- changing any physical scenario from pending to passed;
- EAS project linkage, credentials, protected builds, evidence creation,
  physical devices, or push;
- rewriting historical plan descriptions whose old SDK or version statements
  are intentionally frozen context.

## Git workflow

- Work in the primary checkout; do not create or use a worktree.
- Commit only after verification and review, using
  `docs: refresh root agent guidance`.
- Do not push.

## Steps

### Step 1: Add failing contract cases for stale root guidance

Extend `scripts/verify-project-docs_test.sh` so its valid fixture includes an
`AGENTS.md` with the current Expo major, Room version, completed direct-LAN task
range, final plans-through-030 statement, and explicit physical/protected-release
pending truth. The historical source state before Plan 030 tracked Plans 001-029.
Add one independent mutation for each of these regressions:

1. Expo 57 rewritten to Expo 54;
2. Room 7 / next migration 7-to-8 rewritten to Room 5 / 5-to-6;
3. direct-LAN Tasks 5-9 rewritten as open;
4. advisor ledger ending at 010 rather than the final 030;
5. physical two-phone or protected EAS pending state rewritten as complete.

Update `scripts/verify-project-docs.sh` to require the fixture's `AGENTS.md`,
check the current facts with bounded literal/pattern checks, and reject the
known stale forms. Do not parse arbitrary prose or make formatting brittle.

**Verify RED**: after adding the first stale-guidance mutation but before
changing `scripts/verify-project-docs.sh`, run
`./scripts/verify-project-docs_test.sh`. Expected: nonzero with
`self-test expected rejection: stale Expo SDK guidance`; it must not fail
because a fixture path is missing or malformed.

Then implement all bounded `AGENTS.md` checks in
`scripts/verify-project-docs.sh` and add the remaining independent mutations.
Run `./scripts/verify-project-docs_test.sh` again. Expected: exit 0 for the
isolated valid fixture and every mutation. Before changing the live
`AGENTS.md`, run `./scripts/verify-project-docs.sh`. Expected: nonzero with a
`project documentation check failed:` message naming the first stale live
root-agent fact. This second RED proves the production verifier is wired to the
real repository document.

### Step 2: Correct only current operational facts in AGENTS.md

Make these surgical updates:

- identify the mobile tree as Expo SDK 57 / React Native 0.86;
- describe current reliable state as Room version 7;
- replace the migration instruction with “new entity means version 8, explicit
  `Migration(7,8)`, registration in `NotificationDb.addMigrations(...)`, and a
  committed schema `8.json`”; retain the destructive-fallback prohibition;
- say direct-LAN Tasks 1-9 implementation/host automation are complete while
  the named two-phone hardware evidence remains pending;
- say `advisor-plans/README.md` tracks Plans 001-030 and names the two blocked
  boundaries without implying source work remains in Plan 015. The 001-029
  range is the historical source state immediately before Plan 030 landed.

Do not rewrite the long-form architecture beyond facts proven by the cited
sources. Preserve the instructions to trust git/code over stale `MEMORY.md` and
to pause for user-driven visual decisions.

**Verify**: `./scripts/verify-project-docs.sh` exits 0.

### Step 3: Prove the contract and reconcile the ledger

Run the full command table. Dispatch an independent read-only reviewer to
compare every new version/status sentence against the read-only sources of
truth and confirm that physical and protected-release claims remain pending.
Record its `APPROVE` verdict at
`.omo/evidence/plan-030/independent-truth-review.md`. Only then change Plan
030's row in `advisor-plans/README.md` to DONE with the commit/evidence summary.

**Verify**: `./scripts/verify-project-docs_test.sh && ./scripts/verify-project-docs.sh && ./scripts/verify-host-workflows.sh && ./scripts/verify-host-workflows_test.sh && make host-verify && git diff --check` exits 0.

## Test plan

- Extend the existing isolated fixture rather than reading the live checkout
  from self-tests.
- Prove each of the five stale/current-status dimensions fails independently.
- Keep the existing Expo Go, npm workflow, protocol, scenario, and release
  manifest mutations green.
- Run the exact host gate because the docs verifier is part of its required
  recipe.

## Done criteria

- [x] `AGENTS.md` names Expo 57 / React Native 0.86 and Room version 7.
- [x] Its next-migration instruction names version 8, `Migration(7,8)`, and
      schema `8.json`, while retaining the no-destructive-fallback invariant.
- [x] It states direct-LAN Tasks 1-9 implementation/host automation are done and
      keeps every hardware-only check pending.
- [x] It identifies `advisor-plans/README.md` as tracking Plans 001-030 and
      accurately distinguishes Plan 004's external EAS block from Plan 015's
      deferred physical evidence.
- [x] The docs verifier and self-test reject each stale form independently.
- [x] Host workflow checks, exact `make host-verify`, and `git diff --check`
      all pass.
- [x] The independent truth review ends `APPROVE` and is recorded at
      `.omo/evidence/plan-030/independent-truth-review.md`.
- [x] No file outside the in-scope list is modified.

## STOP conditions

Stop and report rather than improvise if:

- any cited source of truth materially differs from the Current state section;
- the database version is not 7 or an unregistered migration/schema is found;
- direct-LAN Tasks 5-9 contain an unchecked non-physical implementation item;
- completing the wording would require claiming physical or protected release
  evidence that does not exist;
- a verification command fails twice after a bounded documentation/test fix;
- the change appears to require source, build, dependency, or workflow behavior.

## Maintenance notes

The project-doc verifier should protect only load-bearing operational facts. On
the next Expo or Room upgrade, update `AGENTS.md` and the matching fixture in the
same commit. Historical plans may continue to mention old versions when they
describe the state at which those plans were authored; do not mass-rewrite them.
