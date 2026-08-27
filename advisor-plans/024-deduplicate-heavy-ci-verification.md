# Plan 024: Run heavyweight mobile verification once per CI job

> **Executor instructions**: Work in the primary checkout only. Read `AGENTS.md`
> fully. Use test-first workflow-contract mutations and keep local developer
> targets safe. No worktree, push, device, EAS invocation, or secret access.
> Update `advisor-plans/README.md` after review.
>
> **Drift check (run first)**:
> `git diff --stat f119224..HEAD -- Makefile .github/workflows/e2e-android.yml .github/workflows/android-release.yml scripts/verify-host-workflows.sh scripts/verify-host-workflows_test.sh scripts/verify-android-release-workflow.sh scripts/verify-android-release_test.sh e2e/scripts/run-two-emulators.sh`

## Status

- **Status**: DONE
- **Priority**: P2
- **Effort**: S
- **Risk**: MED
- **Depends on**: Plan 023
- **Category**: dx
- **Planned at**: commit `f119224`, 2026-08-27

## Why this matters

The emulator workflow runs `make verify`, then explicitly runs
`make mobile-verify`, then invokes `make e2e-emulator`, whose phony prerequisite
runs `mobile-verify` again. The protected release workflow also runs `npm ci`
before `make host-verify`, which runs the same locked install again. These repeats
add minutes, network exposure, cache churn, and timeout risk without adding
coverage.

## Current state

- `Makefile` declares `mobile-verify` and `e2e-emulator` phony;
  `e2e-emulator: relay-build mobile-verify`.
- `.github/workflows/e2e-android.yml` runs `make verify`, then
  `make relay-build`, `make mobile-verify`, and finally `make e2e-emulator` in
  separate steps/processes.
- `run-two-emulators.sh` consumes the already-built debug APK at
  `mobile/android/app/build/outputs/apk/debug/app-debug.apk` and relay binary at
  `bin/relay`; it does not need to rebuild them.
- `.github/workflows/android-release.yml` has a standalone mobile `npm ci` step
  immediately before `make host-verify`, whose recipe starts with mobile
  `npm ci`.
- Existing shell verifiers protect complete host/native gates but do not reject
  these duplicate invocations.

## Frozen design

- Preserve `make e2e-emulator` as the safe local one-command target that builds
  prerequisites.
- Add an explicit run-only target (for example `e2e-emulator-run`) that requires
  the APK and relay binary to exist and then invokes the scenario script.
- CI runs `make verify` exactly once, `make relay-build` exactly once, prepares
  AVDs, and calls the run-only target. It must not call `mobile-verify` again.
- Protected release CI lets `make host-verify` own the one locked install; remove
  the standalone install rather than weakening host verification.
- Workflow verifiers must reject missing gates and duplicate heavyweight gates.

## Scope

**In scope**:

- `Makefile`
- `.github/workflows/e2e-android.yml`
- `.github/workflows/android-release.yml`
- `scripts/verify-host-workflows.sh` and self-test
- `scripts/verify-android-release-workflow.sh` and self-test
- `e2e/scripts/run-two-emulators.sh` only if a non-mutating artifact preflight is
  needed

**Out of scope**: removing any test, changing scenario semantics, changing
release signing/provenance, running EAS, changing app/relay source, or weakening
local one-command safety.

## Git workflow

- Primary checkout only.
- Commit after review as `ci: avoid duplicate mobile verification`.
- Do not push.

## Steps

### 1. Capture duplicate-workflow failures

Extend shell self-tests first. Require rejection when:

- E2E Android invokes `mobile-verify` more than once or combines `make verify`
  with another direct `mobile-verify`;
- the scenario step calls the prerequisite-heavy target after the full verify;
- the run-only target omits explicit APK/relay artifact preflight;
- protected release has any standalone `npm ci` in addition to `host-verify`;
- either workflow removes the canonical host/native gate instead of deduplicating.

**Verify**:

```bash
./scripts/verify-host-workflows_test.sh
./scripts/verify-android-release_test.sh
```

Expected before workflow edits: at least one new mutation/contract test fails.

### 2. Split safe local preparation from run-only execution

Add a Make target that checks the exact APK and relay binary paths, then runs
`run-two-emulators.sh` without depending on `mobile-verify`. Keep
`e2e-emulator` depending on `relay-build mobile-verify` and delegating to the
run-only target. Preserve real tab characters.

Add a shell preflight fixture proving the run-only target refuses missing
artifacts rather than rebuilding or silently continuing.

### 3. Simplify E2E Android CI

Keep one `make verify`. Build only the relay binary afterward, prepare AVDs, and
invoke the run-only target. Do not call `npm ci`, `mobile-verify`, Expo prebuild,
or Gradle a second time. Preserve sanitized artifact upload and scenario inputs.

### 4. Remove the protected-release duplicate install

Delete the standalone “Install locked dependencies” step. Keep
`make host-verify` mandatory and before any protected secret-consuming build.
Update security mutation fixtures so secret-leak checks anchor to a stable
pre-secret step rather than the deleted step.

### 5. Run contract and full host gates

```bash
./scripts/verify-host-workflows.sh
./scripts/verify-host-workflows_test.sh
./scripts/verify-android-release-workflow.sh
./scripts/verify-android-release_test.sh
./e2e/scripts/preflight_test.sh
make host-verify
git diff --check
```

No emulator or EAS build is required. Require independent review of job command
counts and artifact flow.

## Test plan

- Mutation-test duplicate `mobile-verify`, use of the heavy target in CI,
  missing run-only artifact checks, duplicate `npm ci`, and removed canonical
  gates.
- Verify local `e2e-emulator` still has build prerequisites.
- Verify the CI run-only path consumes the exact APK and relay binary produced
  earlier in the same job.

## Done criteria

- [x] E2E Android performs one mobile install/prebuild/native verification cycle.
- [x] Protected release performs one locked mobile install through host verify.
- [x] Local `make e2e-emulator` remains safe and self-preparing.
- [x] CI run-only target fails closed on missing APK or relay binary.
- [x] Workflow verifiers, their self-tests, `make host-verify`, and diff check
      pass.

## STOP conditions

- Deduplication would skip a test or consume an artifact from another commit/job.
- The run-only target can proceed without checking exact APK/relay artifacts.
- A protected secret becomes available to an earlier step.
- The fix needs an emulator, EAS token, or release credential to validate.

## Maintenance notes

Workflow contracts should count heavyweight producers, not just search for their
task names. Future jobs may reuse artifacts only within the same trusted commit
or through provenance-verified uploads.
