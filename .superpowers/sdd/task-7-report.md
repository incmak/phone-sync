# Task 7 report: offline pairing and LAN upgrade UI

## Result

Status at correction handoff: **DONE_WITH_CONCERNS**.

The Task 7 UI, tests, package foundation, relay fallback, and nearby-upgrade entry point are implemented. Automated verification and the Android debug build pass. One physical M2012K11AI (API 36) was available and accepted the APK. Physical screen inspection could not proceed because the phone remained behind a secure keyguard; no unlock bypass was attempted. A second physical phone was not present.

Baseline: `cf5f527` on branch `codex/offline-lan-sync`.
Feature commit: `5659a70` (`feat(mobile): pair nearby without internet`).
Native TLS mapping correction: `ff2aaa6` (`fix(mobile): distinguish nearby pin failures`).
UI hardening correction base: `ff2aaa6`; intended subject `fix(mobile): harden nearby pairing flow`.

The original feature commit did not change native files. The follow-up `ff2aaa6` intentionally changed the bounded native TLS mapping and its tests, as documented below. No sensitive pairing material was written to screenshots, logs, snapshots, test names, or this report.

## Strict TDD evidence

The test foundation and all behavior tests were created before the production UI changes.

- Initial RED command: `cd mobile && npm test -- --runInBand app/pair/__tests__/offlinePairingFlow.test.tsx`
- Initial RED observable: exit 1, one suite failed, 15/15 tests failed because the nearby choice, screens, bridge behavior, repair states, completion gate, upgrade action, and accessibility behavior did not exist.
- Initial RED artifact: `.omo/evidence/task-7/red-ui-tests.log`
- Refactor RED command: `cd mobile && npm test -- --runInBand -t 'keeps a native-complete pairing finished'`
- Refactor RED observable: exit 1; the screen fell back to `Finishing pairing…` when relay sync restart rejected after native completion.
- Refactor correction: relay restart is now best-effort after native completion is committed; pairing completion remains visible and actionable.
- Final GREEN command: `cd mobile && npm test -- --runInBand`
- Final GREEN observable: exit 0, 1/1 suite, 17/17 tests, 0 snapshots.
- Final GREEN artifact: `.omo/evidence/task-7/jest-full.log`

The Jest setup explicitly mocks Expo Router, camera, storage, QR rendering, and the native Expo module. Tests exercise native bridge calls and status events, including exact session cancellation, raw scanner-text forwarding, explicit confirmation, and COMPLETE-driven onboarding state.

## Implementation summary

- Added an explicit onboarding choice between nearby-without-internet and relay pairing.
- Added initiator and joiner nearby flows backed only by the approved native bridge.
- The initiator renders the opaque native pairing image in memory and does not parse, log, snapshot, or persist its contents.
- The scanner forwards only the scanned string plus local display name to native validation in nearby mode. Existing relay parsing and handshake remain available in relay mode.
- Both roles render the same native six-digit SAS and must explicitly confirm it.
- Nearby and verification back/cancel paths cancel the exact native session; gestures are disabled on those screens so navigation cannot bypass cleanup.
- Added bounded, distinct recovery copy for timeout, isolated/unavailable Wi-Fi, permission denial, TLS pin failure, malformed secure data, identity mismatch, peer rejection, invalid scan, and unavailable runtime.
- Success remains pending until native reports COMPLETE; an already committed pair stays complete if relay service restart fails.
- Paired-device settings can enable nearby sync without unpairing or overwriting the relay identity. Identity mismatch copy explicitly says the existing relay pair is unchanged.
- Existing relay flow remains routed and functional.
- Fixed the pre-existing apostrophe lint errors in touched `pair/fail.tsx` and `pair/fingerprint.tsx`.

## Success criteria and evidence

| Scenario | Invocation | Binary observable | Artifact |
|---|---|---|---|
| Explicit nearby and relay choice | Jest full suite | Named controls found and actionable | `.omo/evidence/task-7/jest-full.log` |
| Native initiator image remains ephemeral | Jest full suite | Native start called; no console/storage write; native event routes to verification | `.omo/evidence/task-7/jest-full.log` |
| Joiner forwards scanned text only | Jest full suite | `joinOfflinePairing(scannedText, displayName)` called; relay hello not called | `.omo/evidence/task-7/jest-full.log` |
| Identical SAS and explicit confirmation | Jest full suite, initiator + joiner cases | Formatted six digits shown; no implicit confirm; exact-session confirm after press | `.omo/evidence/task-7/jest-full.log` |
| Back/cancel cleanup | Jest full suite | Exact native session ID cancelled before router back | `.omo/evidence/task-7/jest-full.log` |
| Distinct repair branches | Jest table over seven native error states | Each state renders its own title and retry action | `.omo/evidence/task-7/jest-full.log` |
| COMPLETE gate | Jest incomplete then COMPLETE states | Storage remains incomplete before COMPLETE and is marked after COMPLETE | `.omo/evidence/task-7/jest-full.log` |
| Relay restart is non-fatal after commit | Jest with rejected `startSyncService` | Completed screen remains `Twinned.` | `.omo/evidence/task-7/jest-full.log` |
| Existing relay pair nearby upgrade | Jest paired settings + identity mismatch | Upgrade route exists; no unpair or peer-key overwrite calls | `.omo/evidence/task-7/jest-full.log` |
| Accessible controls/default visibility | Jest rendered styles/roles/labels | Named control has >=48dp target, opacity > 0, no hidden overflow | `.omo/evidence/task-7/jest-full.log` |
| Light/dark contrast | WCAG contrast calculation against repository tokens | All audited text/background pairs >= 5.06:1; camera error pair 13.48:1 | `.omo/evidence/task-7/contrast-audit.log` |
| Type safety | `cd mobile && npm run typecheck` | exit 0 | `.omo/evidence/task-7/typecheck.log` |
| Expo compatibility | `cd mobile && npx expo-doctor` | 18/18 checks passed | `.omo/evidence/task-7/expo-doctor.log` |
| Touched-file lint | scoped `npx eslint` command | exit 0, zero touched-file errors/warnings | `.omo/evidence/task-7/lint-scoped.log` |
| Full lint | `cd mobile && npm run lint` | exit 0; 0 errors, 5 warnings in untouched files | `.omo/evidence/task-7/lint-full.log` |
| Debug Android build | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon assembleDebug` | BUILD SUCCESSFUL, 483 tasks | `.omo/evidence/task-7/debug-apk-build.log` |
| Physical install | `adb -s <explicit-serial> install -r mobile/android/app/build/outputs/apk/debug/app-debug.apk` | Performing Streamed Install / Success | `.omo/evidence/task-7/adb-install.log` |

Debug APK: `mobile/android/app/build/outputs/apk/debug/app-debug.apk` (227 MB).

## Dependency foundation

Installed versions verified by `npm ls`:

- Expo 54.0.37
- jest-expo 54.0.18
- @testing-library/react-native 13.3.3
- react-test-renderer 19.1.0
- expo-asset 12.0.13
- expo-constants 18.0.14

Artifact: `.omo/evidence/task-7/dependency-versions.log`. Testing Library 13.3.3 was selected because the available 14.x prerelease used an async rendering contract incompatible with this Expo SDK test surface. Expo and its direct packages were aligned to the patch versions required by Expo Doctor.

## Physical device and screen matrix

ADB inventory found exactly one physical device:

- Serial: `adb-b13d3d82-Wu4k6u (2)._adb-tls-connect._tcp`
- Model/product: M2012K11AI / aliothin
- Android API: 36
- Install: passed
- Original font scale: 1.0
- Original night mode: custom schedule

Inventory artifact: `.omo/evidence/task-7/adb-inventory.log`.

The safe wake plus `wm dismiss-keyguard` attempt left `showing=true` and focus on `NotificationShade`. This indicates a secure keyguard. No input, PIN, biometric, or other unlock bypass was attempted. Therefore no physical screenshot was captured and no phone setting was changed.

| Touched screen | Automated/render audit | Physical portrait, large font, light/dark |
|---|---|---|
| onboarding role | Scroll-safe and accessible role controls audited | Not completed: secure keyguard |
| onboarding connect | Rendered behavior, target, visibility, contrast tested | Not completed: secure keyguard |
| onboarding relay | Type/lint and scroll/target audit | Not completed: secure keyguard |
| pair nearby | Rendered bridge/event/cancel/repair behavior tested | Not completed: secure keyguard |
| pair verify | Both roles rendered; SAS/confirm/cancel tested | Not completed: secure keyguard |
| pair scan | Camera mock rendered; nearby and relay dispatch tested | Not completed: secure keyguard |
| pair QR relay | Type/lint and relay-routing audit | Not completed: secure keyguard |
| pair fingerprint | Type/lint and touched-file cleanup audit | Not completed: secure keyguard |
| pair success | Incomplete/COMPLETE/restart-failure states rendered | Not completed: secure keyguard |
| pair fail | Type/lint, scrolling, copy, and icon audit | Not completed: secure keyguard |
| settings pair | Paired upgrade and identity-preservation behavior rendered | Not completed: secure keyguard |

Physical boundary artifact: `.omo/evidence/task-7/physical-visual-boundary.log`. A second phone was not present, so a two-phone end-to-end pairing run was not performed.

## Mandatory anti-slop audit

The full AGENTS.md anti-slop law was re-read before completion. Each relevant category was checked across every touched UI file:

- **Existing language and cohesion:** kept the repository Theme/tokens, type scale, spacing, corner language, QR component, camera, fingerprint, and button primitives. No generic visual system or new font/palette was introduced.
- **No generic effects:** no gradient, glow, glass, background halo, candy aurora, grain overlay, grid, fake shadow, hover lift/boop, underline animation, entrance animation, floating card, decorative accent bar, or fixed background was added.
- **No decorative component clichés:** removed the colored phone emoji tile and the alert icon tile. No eyebrow pill, status-chip field, gradient icon tile, logo box, decorative quote, pricing/testimonial/CTA template, fake app window, fake code window, or decorative rule was added.
- **Controls are real:** both connection choices route and persist their mode; nearby start/join/confirm/cancel call native; relay pairing remains live; retry, mismatch, back, Done, and settings-upgrade controls have real handlers. Tests assert bridge calls and navigation rather than copy alone.
- **CTA composition:** transactional screens use one clear action followed vertically by a quiet text action where needed. No stock side-by-side fill-plus-outline pair was added.
- **Visible by default:** no content begins at opacity 0 or translated away. Loading content is explicit. Source audit found no hidden entrance state.
- **Clipping and edges:** ScrollViews with `flexGrow` replace fixed-height content on touched text-heavy screens. No content container uses hidden overflow or clipping. Text retains 24dp or larger horizontal gutters.
- **Large text:** explicit line heights, scrollable content, flexible sections, and bottom padding are present. Fixed dimensions are limited to the pairing image, viewfinder, and centered back control; live text is not placed inside those bounds.
- **Targets and accessibility:** new Pressables expose button/radio/image/alert semantics and labels; interactive targets are at least 48dp. Hardware-back cancellation and gesture disabling protect native cleanup.
- **Centering:** viewfinder, SAS, pairing image, back glyph, and bare alert mark use explicit alignment. No clipped or off-center text is embedded in custom geometry.
- **Contrast:** initial audit found tertiary dark text on a filled surface at 4.34:1. New/touched filled-option copy was corrected to `ink2`; final audited pairs are >=5.06:1. Camera overlay copy is white on black or dark red.
- **Color discipline:** reused the product's warm neutrals and existing single accent. No new saturated competing accent or hard color seam was introduced.
- **Typography:** reused the established app fonts because the brief requires the current product visual language. No new Google/default display choice, mono house voice, gradient headline, cramped display copy, or repeated tracked-caps treatment was introduced.
- **Copy:** recovery text is terse, bounded, actionable, and distinct. Em dashes and decorative quotation styling were not added. Sensitive material never appears in logs, screenshots, snapshots, names, or this report.
- **Layout-template checks:** these are compact native transactional screens, not marketing pages. None uses split-hero, kicker/H2, pricing grid, testimonial, logo wall, pre-footer CTA, standard footer, numbered rail, email pill, or SaaS page skeleton.
- **Imagery/signature guidance:** no decorative hero or fabricated brand/customer imagery was appropriate for this native security flow. Functional camera, pairing image, fingerprints, and existing product marks remain the only visuals.
- **Motion:** existing router transition remains; no content-gating or decorative motion was added. Reduced-motion-specific work was unnecessary because Task 7 adds no authored animation.
- **Final corrections from audit:** removed obsolete phase comments, removed icon containers, made role/relay/success/fail content scroll-safe, raised back/input targets to 48dp, added labels/roles, corrected filled-surface contrast, preserved completion across relay restart failure, and fixed the two apostrophe lint findings.

## Known concerns and limitations

1. Resolved in the follow-up native correction below: transport `TLS_PIN_MISMATCH` now publishes the bounded public `tls_pin_mismatch` code, while application hello/key mismatches remain `identity_mismatch`.
2. Physical portrait, large-font, and light/dark screen inspection did not complete because the only connected phone was securely locked. No screenshots were captured.
3. Only one physical phone was available. Two-phone end-to-end nearby pairing was not run.
4. Full lint reports five warnings in untouched `home.tsx`, `onboarding/oem.tsx`, and `components/primitives/TwIcon.tsx`; all touched files have zero findings.

## Independent-review correction

The correction was developed RED-first against `.superpowers/sdd/task-7-review.md` using the receiving-code-review and TDD procedures.

### Correction RED and GREEN

- RED command: `cd mobile && npm test -- --runInBand app/pair/__tests__/offlinePairingFlow.test.tsx components/primitives/__tests__/TwButton.test.tsx`
- RED observable: exit 1, 10 failed and 20 passed. Failures covered shared-button semantics, visible/hardware/unmount scanner cleanup, stale-session handling, nearby completion with an existing relay pair, rejected confirmation repair, representative touched controls, and the relay/fingerprint anti-slop structures.
- RED artifact: `.omo/evidence/task-7/correction-red.log`
- Additional retry RED: the retry transition did not cancel exact session `11111111-1111-4111-8111-111111111111`; artifact `.omo/evidence/task-7/correction-red-retry.log`.
- Focused GREEN command: the same two-suite command after production changes.
- Focused GREEN observable before the final pre-join guard: 31/31 passed, 0 snapshots.
- Focused GREEN artifact: `.omo/evidence/task-7/correction-green-focused.log`.
- Full GREEN command: `cd mobile && npm test -- --runInBand`.
- Additional pre-join RED: leaving during deferred display-name lookup still called native join; artifact `.omo/evidence/task-7/correction-red-prejoin.log`. The corrected continuation checks the mounted/leaving boundary before invoking native.
- Additional cancellation-retry RED: a transient exact-cancel rejection prevented late continuation cleanup from retrying; artifact `.omo/evidence/task-7/correction-red-cancel-retry.log`. Failed session IDs are now released for one later exact retry.
- Full GREEN observable: 2/2 suites, 33/33 passed, 0 snapshots.
- Full GREEN artifact: `.omo/evidence/task-7/correction-jest-full.log`.

### Review finding closure matrix

| Review finding | Correction | Behavior evidence |
|---|---|---|
| Nearby COMPLETE bypass through relay `paired=true` | Success predicate now reads persisted mode. Nearby requires exact `phase=complete && completed=true`; relay mode uses relay pair status. | Incomplete and COMPLETE nearby states both run with an existing relay identity; relay mode has a separate passing case. |
| Scanner session leak and late navigation | Visible back, hardware back, and unmount set a leaving boundary, query current native status, cancel its exact session once, and recheck cleanup after a deferred join settles. Relay continuations are also suppressed after exit. | Three deferred-join tests assert exact cancellation and no late `/pair/nearby` replacement. |
| Shared button semantics | `TwButton` now defaults to role button, infers string/number names, accepts explicit labels/hints/test IDs, publishes disabled/busy state, and uses flexible minimum heights of 48dp or more. | Focused primitive tests cover small target, inferred name, explicit loading name, disabled and busy; scanner permission controls provide representative screen coverage. |
| Confirmation rejection | Rejections are caught, mapped to the bounded repair table, and rendered as an alert with a working return action. | Rejecting native mock produces `Pairing session changed` without an unhandled promise; exact mismatch cancellation is separately tested. |
| Behavior breadth | Both connection choices are pressed; retry cancels then starts; stale session events are ignored; subscriptions are removed; scanner back paths, pre-join exit and transient cleanup retry, relay/nearby completion, dark theme, 2x font-scale flexible layout, and anti-slop action order are exercised. | 33-test suite in `.omo/evidence/task-7/correction-jest-full.log`. |
| Relay QR/fingerprint anti-slop | Countdown is plain type with a descriptive accessibility label; pulsing status halo is removed from the QR screen; fingerprint confirmation is the primary full-width action followed by a quiet full-width mismatch action. | Rendering test asserts uncontained countdown styling and ranked accessible action order; source audit confirms `TwStatusDot` is absent from the screen. |

### Correction verification

| Gate | Observable | Artifact |
|---|---|---|
| TypeScript | exit 0 | `.omo/evidence/task-7/correction-typecheck.log` |
| Full Jest | 33/33 passed | `.omo/evidence/task-7/correction-jest-full.log` |
| Expo Doctor | 18/18 checks passed | `.omo/evidence/task-7/correction-expo-doctor.log` |
| Scoped correction lint | zero errors and zero warnings | `.omo/evidence/task-7/correction-lint-scoped.log` |
| Full lint | exit 0; five unchanged warnings outside touched files | `.omo/evidence/task-7/correction-lint-full.log` |
| Debug APK | BUILD SUCCESSFUL, 483 tasks | `.omo/evidence/task-7/correction-debug-apk-build.log` |
| Physical inventory | one M2012K11AI only | `.omo/evidence/task-7/correction-adb-inventory.log` |
| APK install | install command exit 0; package manager returned the installed `com.twinotify.app` base path | `.omo/evidence/task-7/correction-adb-install.log` |

### Correction physical boundary

Read-only ADB inventory still found one phone. Its state remained font scale 1.0, night mode custom schedule, `showing=true`, with focus on `NotificationShade`. No keyguard bypass was attempted and no system setting was changed. Therefore the correction does **not** claim two-phone pairing, sanitized screenshots, portrait inspection, 2x font rendering on hardware, light/dark device rendering, TalkBack semantics, or real camera-overlay behavior. Artifact: `.omo/evidence/task-7/correction-physical-boundary.log`.

### Correction anti-slop matrix

| Law category | Result and correction |
|---|---|
| Cohesion and existing design system | Pass. Existing warm-neutral theme, typography, spacing, QR, fingerprint, and primitive language remain; no new visual system or font was added. |
| Pills, badges, halos, glows, glass, gradients | Pass after correction. The relay countdown capsule and pairing pulse were removed. No new pill, glow, gradient, glass, bloom, halo, grain, or grid was added. |
| Button composition | Pass after correction. Fingerprint actions are vertically ranked primary then quiet action, not a side-by-side filled/destructive or filled/outline preset. |
| Real controls and semantics | Pass in source/runtime tests. Shared buttons now expose role, name, disabled/loading state and >=48dp flexible targets; every changed control retains a real handler. Physical TalkBack remains unverified. |
| Visible-by-default content | Pass. No opacity-zero, translated-away, timed entrance, hidden section, or animation-gated content exists in the correction. |
| Clipping, fixed heights, large text | Source/test pass. `TwButton` fixed heights were replaced by minimum height plus vertical padding; text screens remain scrollable; 2x font-scale render asserts flexible controls and no hidden overflow. Physical large-font inspection remains unverified. |
| Motion | Pass after correction. The repeating pairing halo is no longer used on relay QR; no entrance reveal, hover lift, underline fill, or decorative motion was added. Existing press feedback and router transitions remain functional native interaction. |
| Color and contrast | Pass from prior token audit. The correction introduces no color. Plain timer and fingerprint actions use existing readable token pairs. |
| Icons and decorative containers | Pass. No new icon tile, logo box, fake mark, oversized glyph container, or fabricated brand asset was introduced. |
| Layout and marketing templates | Pass. Transactional screens contain no split hero, kicker/H2, pricing/testimonial grid, pre-footer CTA, fake product window, numbered rail, or SaaS skeleton. |
| Copy and security | Pass. Confirmation failure has bounded actionable copy. Sensitive pairing material remains absent from logs, screenshots, snapshots, test names, and report content. |
| Edges, centering, and gutters | Source pass. Existing deliberate gutters and centered QR/SAS/camera geometry remain. Physical optical inspection remains unverified. |

## Native pin-error correction evidence

This follow-up keeps the Task 7 UI contract but fixes the native transport-to-status boundary. No wire schema, pairing frame, or UI file changed.

| Scenario | Invocation | Binary observable | Artifact |
|---|---|---|---|
| RED: TLS pin transport failure was incorrectly published as application identity mismatch | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest --tests co.twinotify.core.pairing.lan.OfflinePairingRuntimeAdapterTest.tlsPinTransportFailurePublishesBoundedPinErrorAndIdentityMismatchStaysApplicationScoped` before the correction | Test failed at the expected assertion (`identity_mismatch` != `tls_pin_mismatch`); the captured log includes `BUILD FAILED` | `.omo/evidence/task-7/red-native-tls-pin.log` |
| GREEN: TLS pin and application identity errors remain distinct at the native public event boundary | Same focused Gradle test after the correction | `BUILD SUCCESSFUL`; terminal status and closed-world event map carry exact `tls_pin_mismatch`, while `OfflinePairingError.IDENTITY_MISMATCH` remains `identity_mismatch` | `.omo/evidence/task-7/green-native-tls-pin.log` |
| Repeatability | Same focused Gradle test a second time | `BUILD SUCCESSFUL` | `.omo/evidence/task-7/green-native-tls-pin-repeat.log` |
| Native gates | `cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug` | All three tasks passed; `BUILD SUCCESSFUL` | `.omo/evidence/task-7/native-gap-full-gates.log` |
| TypeScript contract | `cd mobile && npm run typecheck` | exit 0 with the closed `OfflinePairingErrorCode` union including TLS pin, peer rejection, and Wi-Fi errors | `.omo/evidence/task-7/native-gap-typecheck.log` |
| Existing mobile behavior | `cd mobile && npm test -- --runInBand` | 1 suite, 17 tests passed; existing UI TLS-pin repair branch remains covered | `.omo/evidence/task-7/native-gap-jest.log` |
| Diff hygiene | `git diff --check` | passed | `.omo/evidence/task-7/native-gap-diff-check.log` |

The focused test uses a transport that throws `PairingTransportFailure.TLS_PIN_MISMATCH`, asserts the resulting public code and exact event-map key set, and separately asserts application identity mismatch remains unchanged. The native mappings are exhaustive `when` expressions, so future enum additions fail compilation until explicitly assigned a bounded public code.

## Final workspace checks

- `git diff --check`: passed; artifact `.omo/evidence/task-7/git-diff-check.log`.
- Commit `5659a70` contains only the exact Task 7 paths listed in the brief.
- No generated `mobile/android/` content is staged.
- No push is performed.
