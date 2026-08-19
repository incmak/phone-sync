# Plan 006: Compile Android instrumentation sources in every native gate

> **Executor instructions**: Work in the primary checkout only. Do not create a
> worktree. Follow TDD: add structural mutations first and capture their failure
> against the current verifier, then make the smallest gate changes. Run every
> verification command and stop on a STOP condition. Do not push.
>
> **Drift check (run first)**:
> `git diff --stat 70cb092..HEAD -- Makefile .github/workflows/mobile.yml scripts/verify-host-workflows.sh scripts/verify-host-workflows_test.sh`
> If any listed file has semantically changed, compare live recipe/step ordering
> with this plan before proceeding and stop on mismatch.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: Plan 003
- **Category**: tests
- **Planned at**: commit `70cb092`, 2026-08-19

## Why this matters

Android Keystore/TLS, Room migrations, offline pairing, debug-control security,
and call-notification tests live under `src/androidTest`. The current local and PR
native gates build the debug APK and run JVM tests but never compile those
instrumentation sources, so API drift or broken test code can merge undetected.
Compilation is host-safe and does not require an emulator or physical phone.

## Current state

- `Makefile` `mobile-verify` ends with:
  `./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`.
- `.github/workflows/mobile.yml` `native-android` uses the same Gradle command.
- `scripts/verify-host-workflows.sh` structurally verifies the mobile typecheck
  job and the exact `mobile-verify` recipe, but does not inspect the native job.
- `scripts/verify-host-workflows_test.sh` has adversarial mutations for Jest and
  Make recipes; extend this pattern rather than adding a substring-only check.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Structural RED/GREEN | `./scripts/verify-host-workflows_test.sh` | RED before implementation; exit 0 after |
| Direct verifier | `./scripts/verify-host-workflows.sh` | exit 0 |
| Native compile | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:compileDebugAndroidTestKotlin` | BUILD SUCCESSFUL |
| Host gate | `make host-verify` | exit 0 |
| Diff | `git diff --check` | exit 0 |

## Scope

**In scope**:
- `Makefile`
- `.github/workflows/mobile.yml`
- `scripts/verify-host-workflows.sh`
- `scripts/verify-host-workflows_test.sh`
- `advisor-plans/README.md`

**Out of scope**:
- Running instrumentation tests
- ADB, emulators, physical devices, radios, EAS, or network changes
- Android production/test source changes
- Direct LAN implementation or UI

## Steps

### Step 1: Add fail-closed structural mutations and observe RED

Add mutations proving the verifier rejects:

1. `compileDebugAndroidTestKotlin` missing from `mobile-verify`;
2. the compile task moved after `assembleDebug` or onto a noncanonical extra line;
3. the task missing from the real `native-android` Gradle step;
4. a commented task or task placed in `typecheck`/another job;
5. duplicate/noncanonical Gradle native commands.

The verifier must parse actual recipe/job commands, ignore comments, and require
one exact canonical command in each location. A literal string anywhere in the
file is insufficient.

**Verify**: structural test -> non-zero for the new mutations against current code.

### Step 2: Add instrumentation-source compilation to local and CI gates

Change the exact final `mobile-verify` recipe and `native-android` CI step to:

```text
./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
```

Extend `verify-host-workflows.sh` so both exact locations are structurally
enforced once, in order, with no comment/wrong-job bypass. Preserve existing
read-only permissions, action pins, path coverage, and all host checks.

**Verify**: structural test and direct verifier -> exit 0.

### Step 3: Compile and run host gates

Run the focused Android-test compilation, then `make host-verify`. Request an
independent read-only review of the exact diff and mutation strength. Fix every
Critical/Important finding TDD-first.

**Verify**: native compile, host gate, and diff check -> exit 0.

## Done criteria

- [ ] Both local `mobile-verify` and mobile PR CI compile Android-test Kotlin.
- [ ] Neither gate requires a device merely to compile tests.
- [ ] Structural mutations reject missing, commented, moved, duplicated, and
      wrong-job forms.
- [ ] Existing host workflow security contracts remain green.
- [ ] Focused native compilation and `make host-verify` pass.
- [ ] Independent review has no Critical/Important findings.
- [ ] Plan 006 is marked DONE and only scoped files are committed.

## STOP conditions

- Compilation unexpectedly requires a connected device.
- The fix requires changing Android production or instrumentation source.
- The verifier cannot distinguish real job/recipe commands without a broad parser
  rewrite outside the listed files.
- Any existing workflow permission, action pin, or no-secret invariant regresses.

## Maintenance notes

Instrumentation execution remains a separate emulator/physical evidence gate.
This plan proves sources compile on every native build; it does not claim runtime
device behavior.
