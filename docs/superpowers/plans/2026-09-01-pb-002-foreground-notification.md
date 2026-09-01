# PB-002 Foreground Notification Implementation Plan

**Design:** `docs/superpowers/specs/2026-09-01-pb-002-foreground-notification-design.md`

**Status:** Source and emulator verification complete; physical OEM observations deferred

## Task 1: Lock the truth table and intent boundary

- [x] Add failing native presenter tests for every route, custody, paused, stopped, and unpaired state.
- [x] Add failing tests for explicit/sanitized launcher intent construction and immutable pending-intent flags.
- [x] Add failing tests for the centralized self-package predicate across live and reconciliation capture.
- [x] Run the focused tests and record the expected failures.

## Task 2: Share native presentation with Home

- [x] Implement the native delivery presenter without endpoint, peer, or payload fields.
- [x] Add the presentation to the public `SyncRouteStatus` map.
- [x] Make the TypeScript presenter prefer native presentation for enabled paired sessions while retaining safe lifecycle overrides and compatibility fallback.
- [x] Run Kotlin and TypeScript truth-table tests.

## Task 3: Render and navigate safely

- [x] Render foreground title and secondary custody explanation from the shared presentation.
- [x] Add an explicit, sanitized, immutable launcher `PendingIntent` with existing-task reuse flags.
- [x] Preserve ongoing, private, foreground-service behavior and avoid alert churn on refresh.
- [x] Add emulator notification and tap/task instrumentation.

## Task 4: Preserve self-filtering and verify

- [x] Route live and reconciliation capture through one fail-closed own-package predicate.
- [x] Verify own-package removal remains on the existing exactly-once peer-dismiss path.
- [x] Run focused JVM/TypeScript tests, Android test assembly, and emulator instrumentation.
- [x] Record physical two-phone/OEM tap, lock-screen, restart, and self-mirroring evidence as pending.
- [x] Review the final diff against the anti-slop notification checklist, privacy boundary, and unrelated-change constraint.

## Evidence

- RED: Kotlin compilation failed on the missing presenter/self-filter; the Home test failed because it still recomputed relay copy instead of accepting native presentation.
- Native: all 753 `:twinotify-core:testDebugUnitTest` tests pass and `:twinotify-core:lintDebug` passes.
- TypeScript: `npm run typecheck` passes; 44 focused route-presentation, Home, and handoff-trace tests pass.
- Android build: `:twinotify-core:assembleDebugAndroidTest` passes.
- Emulator API 37: the combined PB-001/PB-002 notification suite passes (8 tests). The foreground fixture posts a private, ongoing, only-alert-once notification, verifies the explicit immutable content intent has no carried data/extras, taps it twice, and observes one single-task activity instance.
- Source audit: generated `MainActivity` remains `singleTask`; the content intent adds `CLEAR_TOP | SINGLE_TOP` and reconstructs only `ACTION_MAIN`, the explicit own-package component, and `CATEGORY_LAUNCHER`.
- Self-filter audit: live capture and both reconciliation paths use `shouldCaptureOutbound`; the unchanged own-package removal path remains covered by the existing exactly-once dismissal tests.
- Pending physical evidence: lock-screen rendering, process/package restart tap behavior, self-notification non-mirroring between paired phones, and tap/task behavior on both OEM phones.
