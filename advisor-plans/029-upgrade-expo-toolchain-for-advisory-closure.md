# Plan 029: Upgrade the Expo toolchain to close upstream advisories

> **Executor instructions**: Work in the primary checkout only. Read
> `AGENTS.md` fully. Use `superpowers:executing-plans` task by task, TDD for
> every compatibility change, and independent review before committing. Obtain
> explicit operator approval before registry queries, `npm audit`, dependency
> installation, EAS use, or physical-device work. This plan authorizes no work
> until the user separately approves its execution.
>
> **Drift check (run first)**:
> `git diff --stat HEAD -- mobile/package.json mobile/package-lock.json mobile/app.json mobile/app.config.* mobile/eas.json mobile/modules/twinotify-core .github/workflows Makefile scripts/verify-mobile-dependencies.sh scripts/verify-mobile-dependencies_test.sh scripts/verify-android-release-workflow.sh scripts/verify-android-release_test.sh scripts/verify-host-workflows.sh scripts/verify-host-workflows_test.sh`

## Status

- **Status**: DONE: source/toolchain migration complete; physical two-phone acceptance remains pending
- **Priority**: P1
- **Effort**: L
- **Risk**: HIGH
- **Depends on**: Plan 028
- **Category**: dependencies, migration, Android
- **Planned at**: Plan 028 implementation state, 2026-08-28

## Goal

Move Twinotify from Expo SDK 54 to a supported Expo toolchain whose declared
Metro dependency ranges resolve outside every current high/critical advisory,
without weakening the Plan 028 audit gate or regressing the custom Android
module, notification/call sync, routing UI, release workflow, or physical
two-phone behavior.

## Why this is separate

Plan 028 removed every range-compatible high advisory and made the protected
release fail closed. Nine high package entries remain in the Expo/Metro build
chain:

- Expo 54.0.37 installs `@expo/metro@54.2.0` and
  `@expo/metro-config@54.0.17`.
- `@expo/metro@54.2.0` pins the Metro family to `0.83.3`; Metro declares
  `image-size@^1.0.2`, while the registry's latest `image-size` observed by
  Plan 028 is `2.0.2` and remains within the live advisory's vulnerable range.
- `@expo/metro-config@54.0.17` declares `postcss@~8.4.32`; the live advisory
  requires `postcss>=8.5.23`.
- The authorized audit's only offered remediation is Expo 57.0.17, a framework
  major. Forcing Metro, PostCSS, or image-size across those parent ranges would
  create an unverified toolchain and is prohibited.

This migration can change React Native, Gradle, Android SDK requirements,
Expo modules, Router, Jest, Reanimated, and generated native projects. It must
therefore be reviewed and verified as a product migration, not a lockfile fix.

## Global constraints

- Keep `scripts/verify-mobile-dependencies.sh` fail closed on any high or
  critical result. No waiver, allowlist, `--offline`, `--omit`, `--production`,
  `|| true`, static report, or severity reduction may make protected release
  pass.
- Use one ordinary full-tree `npm ci` in each protected workflow job. Do not
  add a second install or network audit.
- Do not add broad dependency overrides for Metro, PostCSS, image-size, React,
  React Native, Kotlin, Gradle, or AndroidX.
- Preserve the existing `shell-quote` override unless a fresh advisory and
  dependency-tree review proves it unnecessary.
- Never edit generated `mobile/android/` or `mobile/ios/` as source. Change
  Expo config or `mobile/modules/twinotify-core/`, then regenerate with clean
  prebuild.
- Preserve the package name, deep links, permissions, foreground-service type,
  notification listener/service declarations, Room schemas and migrations,
  EAS project identity, release evidence contract, and protected-secret order.
- Do not claim physical notification/call success without private two-phone
  evidence. Do not push or trigger EAS without explicit approval.
- Pause for user direction before any visual or UX change. A compatibility-only
  migration should not redesign the interface.

## Files expected to change

- Modify: `mobile/package.json` - Expo 57-compatible direct dependencies and
  dev dependencies selected by Expo's compatibility resolver.
- Modify: `mobile/package-lock.json` - npm-resolved lockfile only.
- Modify only if compatibility requires it: `mobile/app.json`,
  `mobile/eas.json`, `mobile/tsconfig.json`, `mobile/eslint.config.js`, and
  `mobile/modules/twinotify-core/**`.
- Modify tests beside any compatibility change. Do not create a generic
  migration test file when an existing focused suite owns the behavior.
- Modify workflow/verifier files only if Expo 57 changes an exact command or
  generated contract; preserve the Plan 028 security semantics.
- Update: `advisor-plans/README.md` only after final independent review.

## Task 1: Freeze the current migration and advisory baselines

- [ ] Record `node --version`, `npm --version`, Java version, Android SDK path,
      Gradle version, current Expo/RN package versions, and `git status` under
      ignored `.omo/evidence/plan-029/`.
- [ ] With approval, run `cd mobile && npm audit --audit-level=high --json` and
      verify the Plan 028 verifier still exits nonzero with the expected Expo /
      Metro chain. Stop if a new SDK 54-compatible fix has appeared; return to
      the smaller compatible-patch path instead of performing a major upgrade.
- [ ] Query the configured registry for Expo 57.0.17 and each Expo-selected
      direct dependency version before editing. Record only package names,
      versions, dist tags, engine requirements, and peer ranges. Do not record
      credentials or tokens.
- [ ] Capture `npx expo-doctor`, clean Android prebuild, the canonical Gradle
      gate, `make host-verify`, and the focused route/call/notification tests as
      the pre-migration green baseline. If a baseline is red for a source
      reason, stop and repair it outside this migration first.

## Task 2: Let Expo select one coherent SDK 57 dependency set

- [ ] Create `.omo/evidence/plan-029/mobile-proposal/`, copy only
      `mobile/package.json` and `mobile/package-lock.json` into it, enter that
      disposable directory, run the SDK 57 compatibility resolver, and review
      its proposed manifest before changing the source tree. The intended
      commands from that directory are:

      ```bash
      cd mobile
      npm install --package-lock-only --ignore-scripts --save-exact expo@57.0.17
      npx expo install --fix --npm
      ```

      If Expo 57.0.17 is no longer the current compatible fixing patch, use the
      smallest registry-proven SDK 57 patch whose Metro ranges close both live
      highs, and record why.
- [ ] Reject a proposed set that mixes Expo SDK majors, violates React or React
      Native peers, introduces a prerelease, removes the local `twinotify-core`
      package, or requires a broad override.
- [ ] Apply the reviewed manifest and lockfile update with npm's resolver. Do
      not hand-edit lockfile dependency nodes.
- [ ] Run `npm ls --all` and fail on invalid, extraneous, or peer-conflicting
      packages. Record exact installed versions for Expo, React Native, Router,
      Reanimated, Worklets, Jest Expo, Metro, PostCSS, and image-size.
- [ ] Run the Plan 028 verifier self-test before the live audit. A dependency
      change must not alter or bypass verifier behavior.

## Task 3: Repair compatibility breaks with focused RED-GREEN cycles

For each failure below, add or strengthen the nearest owning test first, observe
the failure, make the smallest compatibility change, and rerun the focused
test. Do not combine unrelated fixes in one commit.

- [ ] TypeScript/Router: run `npm run typecheck` and the route-product, Home,
      Handoff Trace, call-sync, offline-pairing, and filter suites. Preserve all
      existing route names, parameters, back behavior, reduced-motion behavior,
      and warning-free assertions.
- [ ] Expo config/plugins: run `npx expo-doctor` and clean Android prebuild.
      Compare generated manifest permissions, package/application IDs, intent
      filters, notification listener/service declarations, foreground-service
      type, and module autolinking against the SDK 54 baseline.
- [ ] Native module: run the canonical Gradle command from `mobile/android`:

      ```bash
      ./gradlew --no-daemon lintDebug testDebugUnitTest \
        compileDebugAndroidTestKotlin assembleDebug
      ```

      Preserve Room migrations and exported schemas, libsodium/JNA signatures,
      monotonic nonce handling, mirror-dismiss ordering, bounded outbound
      admission, `flushMutex`, direct-LAN/relay routing, and call capture.
- [ ] Release/build configuration: run the Android release, host workflow, and
      generated-clean self-tests. Any command change must receive a mutation
      test that proves missing, reordered, duplicated, suppressed, or
      secret-after-gate variants still fail.
- [ ] If the migration requires a product-visible or visual change, stop and
      request user direction before editing UI files.

## Task 4: Prove advisory closure without weakening the boundary

- [ ] With approval, run a fresh full-tree
      `npm audit --audit-level=high --json` through
      `scripts/verify-mobile-dependencies.sh`.
- [ ] Require zero high and zero critical metadata counts and package entries.
      Also require a positive dependency total and an npm exit status of zero.
- [ ] Use `npm ls metro postcss image-size @expo/metro @expo/metro-config` to
      prove the installed parent ranges, not an override, admit the fixed
      versions.
- [ ] Re-run every dependency-verifier and protected-workflow mutation. Confirm
      that high, critical, malformed, empty, contradictory, command-failure,
      omitted-dev, offline, waiver, duplicate-audit, suppression, and
      after-secret cases all fail.
- [ ] If any high or critical remains, leave protected release blocked and mark
      this plan BLOCKED with its exact parent path. Do not continue to release
      evidence or risk acceptance.

## Task 5: Run the complete product gates

- [ ] Run `make host-verify` and retain the exit status and bounded summary.
- [ ] Run `ANDROID_HOME=<validated-sdk-path> make mobile-verify` and retain the
      final Gradle summary. Environment-only SDK discovery failures must be
      reported separately from source failures.
- [ ] Run `npm run lint`; require zero new warnings relative to the baseline.
- [ ] Run `git diff --check`, `scripts/verify-project-docs.sh`, and
      `scripts/verify-generated-clean.sh`.
- [ ] Confirm the diff contains no generated Android/iOS source, secrets,
      audit JSON, build outputs, npm caches, broad overrides, or unrelated UI.

## Task 6: Independent review and physical migration smoke

- [ ] Obtain an independent dependency/native/release review of the complete
      diff. The reviewer must verify peer compatibility, parent-declared fixed
      ranges, no verifier bypass, protected-secret order, generated manifest
      parity, and no silent product-scope expansion.
- [ ] Fix every Critical or Important finding with a new RED-GREEN cycle and
      request fresh review.
- [ ] With separate device authorization, install the debug APK on two
      API-compatible physical Android phones and run the existing private smoke
      scenarios for pairing, bidirectional notification post/dismiss, filter
      enforcement, direct LAN, relay fallback, restart/reconnect, and opt-in
      call state. Record only redacted evidence under the established private
      evidence path.
- [ ] If device authorization or two suitable phones are unavailable, report
      source completion separately and leave physical acceptance pending. Do
      not turn the pending scenarios green.
- [ ] After review, update Plan 029's README row and commit small, bisectable
      changes with conventional scoped messages. Do not push or trigger EAS.

## Verification matrix

| Boundary | Required evidence |
|---|---|
| Dependency integrity | `npm ls --all` valid; no broad/cross-major overrides |
| Advisory gate | fresh full-tree audit and verifier: high=0, critical=0 |
| JS product | typecheck, 21+ Jest suites, no lost route/call/filter coverage |
| Expo compatibility | Expo Doctor green; clean prebuild green |
| Android native | lint, JVM tests, instrumentation compilation, APK assembly green |
| Host/release | exact host gate and all release/workflow mutations green |
| UI | no visual change without approval; existing UI tests/warnings unchanged |
| Physical | two-phone smoke redacted and private, or explicitly pending |

## Done criteria

- [ ] The installed Expo 57-compatible graph has zero high and zero critical
      advisories in a fresh full-tree online audit.
- [ ] Metro's own declared ranges resolve fixed PostCSS and image-size versions;
      no override or audit exception creates that result.
- [ ] TypeScript, Jest, lint, Expo Doctor, clean prebuild, native Gradle, exact
      host, release-workflow, documentation, and generated-clean gates pass.
- [ ] Independent review has no unresolved Critical or Important finding.
- [ ] Product-visible behavior is either proven on two physical phones or
      explicitly remains pending without a release-complete claim.
- [ ] Only reviewed migration files are committed; no generated files, evidence,
      credentials, build output, or unrelated changes are tracked.

## STOP conditions

- No explicit authorization for registry, EAS, or physical-device operations.
- A compatible SDK 54 patch now exists, making a framework major unnecessary.
- Expo 57 requires unsupported Android tooling, a prerelease, a cross-major
  override, destructive Room migration, changed application identity, or loss
  of the custom native module.
- A high/critical advisory remains or the audit/verifier cannot obtain a fresh,
  internally consistent full-tree result.
- Native, route, call, notification, filtering, direct-LAN, relay-fallback, or
  release-contract tests cannot be restored without product/UX redesign.
- A protected secret would become available before the dependency gate.
