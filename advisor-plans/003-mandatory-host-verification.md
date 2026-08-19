# Plan 003: Enforce complete host verification in Make and PR CI

> **Executor instructions**: Execute this only after Plan 002 is DONE. Work in
> the primary checkout only and do not create a worktree. Every new CI command
> must first pass locally. Update `advisor-plans/README.md` when done.
>
> **Drift check (run first)**:
> `git diff --stat f667dd1..HEAD -- Makefile .github/workflows mobile/package.json e2e/scripts scripts/verify-offline-pairing-evidence.sh scripts/verify-release-evidence.sh`

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: `advisor-plans/002-truthful-e2e-scenarios.md`
- **Category**: dx
- **Planned at**: commit `f667dd1`, 2026-08-19

## Why this matters

The repository has strong individual tests but no required host command or PR
workflow that runs all of them. Mobile PRs skip Jest, E2E Go tests are only
exercised manually/scheduled, and evidence-verifier self-tests are omitted from
`make verify`. A small, deterministic host gate prevents regressions in pairing
UI, security boundaries, scenario truth, and release evidence before expensive
emulator or physical testing begins.

## Current state

- `Makefile:34-39` runs mobile typecheck, Expo Doctor, native lint/unit/APK but
  no Jest.
- `Makefile:51-52` defines `verify` as protocol, relay, mobile, and generated
  clean only.
- `.github/workflows/mobile.yml:12-26` installs, typechecks, and runs Expo Doctor
  but skips `npm test`.
- `.github/workflows/e2e-android.yml:3-12` is manual/scheduled, so it is not a PR
  host gate.
- `mobile/package.json:15` already exposes `npm test`.
- Existing safe host checks include E2E Go race/vet, workflow validation,
  preflight self-test, generated-clean, release-evidence self-test, and
  offline-pairing-evidence self-test.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Mobile host | `cd mobile && npm ci && npm run typecheck && npm test -- --runInBand` | exit 0, all Jest suites pass |
| E2E host | `cd e2e && go test ./... -race -count=1 && go vet ./...` | exit 0 |
| Evidence | `./scripts/verify-release-evidence.sh --self-test && ./scripts/verify-offline-pairing-evidence.sh --self-test` | both print passed |
| Workflow scripts | `./e2e/scripts/preflight_test.sh && ./e2e/scripts/validate-workflow.sh` | exit 0 |
| Root gate | `make host-verify` | exit 0 |

## Scope

**In scope**:

- `Makefile`
- `.github/workflows/mobile.yml`
- create `.github/workflows/e2e-host.yml`
- create `scripts/verify-host-workflows.sh` and a self-test only if structural
  workflow assertions would otherwise be duplicated in Make
- `advisor-plans/README.md` status only

**Out of scope**:

- physical devices, AVD boot, Docker relay build, or production release build in
  the new host job;
- weakening race tests for speed;
- pinning actions to floating tags;
- dependency upgrades;
- worktrees.

## Git workflow

Stay in the primary checkout. Match pinned full-SHA GitHub Action usage in
existing workflows. Suggested commit: `ci: require complete host verification`.
Do not push without explicit instruction.

## Steps

### Step 1: Add one deterministic `host-verify` target

Add a phony target that runs, in fail-fast order:

1. protocol fixture tests;
2. mobile typecheck and all Jest tests after one `npm ci`;
3. E2E Go race tests and vet;
4. E2E workflow validator and preflight self-test;
5. offline and release evidence verifier self-tests;
6. generated-clean verification.

Avoid calling `mobile-verify` from this target because it prebuilds and runs the
native toolchain; `host-verify` is the fast deterministic layer. Add Jest to
`mobile-verify` as well so the full local gate cannot omit it.

**Verify**: `make host-verify` exits 0 twice.

### Step 2: Require Jest in mobile PR CI

In the existing `typecheck` job, add `npm test -- --runInBand` after typecheck.
Keep Expo Doctor. Upload Jest output only if the repository already has a
machine-readable reporter; do not add a dependency solely for an artifact.

Add `e2e/**`, evidence verifier scripts, and the new host workflow paths to
appropriate path filters so changes cannot evade the owning gate.

### Step 3: Add a PR-safe E2E host workflow

Create `e2e-host.yml` triggered on push/pull_request for `e2e/**`, relevant
scripts, Makefile, protocol, and the workflow itself. It must:

- use read-only contents permission;
- use full commit SHAs for actions;
- set up Go/Node only if required by invoked targets;
- run E2E Go race/vet and the shell/evidence self-tests;
- never require ADB, an emulator, Docker, credentials, or physical evidence.

Add a structural shell assertion, or extend an existing validator, proving the
workflow has push/PR triggers, pinned actions, read-only permissions, and all
required commands.

### Step 4: Run the full verification boundary

Run `make host-verify`, then the existing native/relay gates appropriate to the
changed files. At minimum run `make relay-ci-test` and mobile Jest/typecheck. If
`make verify` is run, record that it requires Docker and Android SDK and do not
claim it if unavailable.

## Test plan

- Structural test fails when Jest is removed from mobile workflow.
- Structural test fails when E2E race/vet or evidence self-test is removed.
- Structural test rejects unpinned GitHub actions and write permissions.
- `host-verify` succeeds from a clean checkout with no devices.
- Existing workflow validators remain green after Plan 002 default changes.

## Done criteria

- [ ] `make host-verify` exists and passes twice.
- [ ] All mobile Jest suites run on every relevant PR.
- [ ] E2E Go race/vet and evidence self-tests run on every relevant PR.
- [ ] Host CI requires no ADB, radios, credentials, or physical evidence.
- [ ] All action references are pinned and permissions read-only.
- [ ] Existing full gates are unchanged except for adding missing tests.
- [ ] README status is updated.

## STOP conditions

- Plan 002 is not DONE or the E2E scenario tests still encode false success.
- A deterministic host test requires an external secret, internet service, or
  physical device.
- GitHub Actions cannot run the repository's pinned Go/Node versions without a
  broader toolchain migration.

## Maintenance notes

Keep host verification separate from nightly emulator and private physical
release gates. A fast required gate should prove source contracts; it should not
pretend to replace device evidence.
