# PB-008 Automatic Transport Recovery Implementation Plan

**Design:** `docs/superpowers/specs/2026-09-01-pb-008-automatic-transport-recovery-design.md`

**Status:** Source and emulator verification complete; physical OEM observations deferred

## Task 1: Lock recovery policy and lifecycle boundaries

- [x] Add failing JVM tests for persisted enabled intent, permission loss, active-service idempotence, start coalescing, timeout, and platform-denied starts.
- [x] Add failing presenter and bridge tests for enabled-but-blocked recovery status.
- [x] Add failing source/manifest tests for boot, package replacement, asynchronous receiver work, and shared authority call sites.
- [x] Run the focused tests and record the expected failures.

## Task 2: Implement one idempotent recovery authority

- [x] Implement the pure recovery decision and start-request gate.
- [x] Implement the Android authority over `ServiceConfigStore`, `PeerStore`, permission checks, active-service truth, and `startForegroundService`.
- [x] Route foreground, boot, package replacement, and retry through the authority.
- [x] Preserve disabled intent and classify platform rejection without mutating persisted state.

## Task 3: Expose actionable product truth

- [x] Publish durable enabled intent and bounded recovery issue through existing native status.
- [x] Make Home distinguish paused intent from dead-but-enabled recovery.
- [x] Add a permission recovery action that opens Twinotify's existing rationale screen.
- [x] Review copy, accessibility, large-font layout, dark/light tokens, and touch targets against the anti-slop checklist.

## Task 4: Verify lifecycle behavior

- [x] Run focused and full Kotlin JVM tests, lint, TypeScript tests, and typecheck.
- [x] Build and run recovery instrumentation on the disposable emulator.
- [x] Exercise force-stop → launcher wake and in-place reinstall/package-replaced paths on the emulator, preserving and re-reading durable state.
- [x] Verify no duplicate service instances/coordinators are observed.
- [x] Record physical signed-upgrade, reboot, force-stop, and OEM policy evidence as pending.

## Evidence

- RED: Kotlin compilation failed on the absent recovery policy/gate/issue types; Home rendered “Paused” and no permission action while native status said the durable intent remained enabled.
- Native: all 758 `:twinotify-core:testDebugUnitTest` tests pass; `:twinotify-core:lintDebug` and `:twinotify-core:assembleDebugAndroidTest` pass.
- TypeScript: all 218 Jest tests pass; `npm run typecheck` and `npm run lint` pass.
- Emulator API 37, signed in-place upgrade: a two-phase instrumentation fixture persisted the enabled flag, exact relay URL, exact `lastUserChangeAt`, peer ID/name, and both public keys across `adb install -r`, then re-read them successfully.
- The first upgrade run exposed and then locked down a real mutation bug: lifecycle recovery had carried `EXTRA_RELAY_URL`, causing `SyncService` to rewrite `lastUserChangeAt`. Recovery now carries no relay URL and the corrected upgrade preserves the timestamp exactly.
- Emulator API 37, idempotence: an enabled fixture with both permissions started the foreground service; a second authority call returned `AlreadyRunning`. `dumpsys activity services` showed exactly one `SyncService` record and one independent Android-bound notification-listener service.
- Emulator API 37, force-stop/open: `am start -S` force-stopped the test package and launched the test-only foreground fixture. The shared `APP_FOREGROUND` authority restored exactly one foreground `SyncService`; Android recorded the launch caller state as `TOP`.
- Emulator API 37, paused upgrade: an enabled peer/route with durable `enabled=false` survived `adb install -r`; package-replacement recovery returned `NoAction("disabled")` and no transport service was active.
- Sticky recreation uses `RecoveryPolicy.decideServiceStart`, so a process-created service shell cannot create a transport coordinator after notification-listener or post-notification access is lost.
- UI review: the recovery state replaces the existing primary status rather than adding another card; copy is short and infrastructure-free; the single CTA uses existing theme tokens and full-width `TwButton` behavior; status remains one polite live region; the switch reflects durable intent and remains available to pause; narrow/large-font layouts have no fixed content height; all controls retain 48dp targets; no new icon, gradient, shadow, color, radius, or typography treatment was introduced.
- Pending physical evidence: signed upgrade, reboot, force-stop/open, OEM auto-start/battery-policy behavior, and two-phone delivery recovery on both target phones.
