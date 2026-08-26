# Handoff Trace Redesign

## TL;DR
> Summary:      Replace Twinotify's generic cream-and-card UI with a cohesive Android-native "Handoff Trace" system. The redesign uses two endpoint brackets, one route trace, and one notification ticket as the product signature while preserving every live route, pairing, mirroring, retry, filter, and settings behavior already shipped.
> Deliverables:
> - Fixed mineral/verdigris light and dark tokens with Android-native sans, condensed, and monospace typography
> - A tested Handoff Trace artifact with distinct direct, relay, reconnecting, queued, paused, and unpaired geometries
> - Redesigned shared primitives plus responsive Welcome, Home, and Settings compositions
> - Agent-captured light, dark, narrow, and 2x-font evidence, contrast results, and independent UI/code approvals
> Effort:       Large
> Risk:         Medium - the visual surface is broad, but behavior is already isolated and must remain unchanged

## Scope
### Must have
- Use one signature system everywhere in scope: two endpoint brackets, a continuous route trace where a route exists, and exactly one notification ticket.
- Give `direct`, `relay`, `reconnecting`, `queued`, `paused`, and `unpaired` visually distinct geometry while retaining the exact words and state priority from `mobile/state/routePresentation.ts:3-85`.
- Use the fixed mineral/verdigris palette in the Design Contract below. Support system light/dark mode with no user-selectable hue.
- Replace Inter and JetBrains Mono with Android-native `sans-serif`, `sans-serif-medium`, `sans-serif-condensed`, and `monospace`; remove both Google-font dependencies, `useAppFonts`, and the root font-loading render gate.
- Make the wordmark a single system-colored text treatment. Do not split the name into accent colors and do not place the mark in a box.
- Remove Unicode gear/chevrons from Welcome, Home, and Settings. Use visible text actions or a Handoff Trace disclosure mark whose shape belongs to the signature.
- Use purposeful non-scale motion only for the reconnecting trace. Content and controls remain visible without animation, and reduced motion freezes the ticket in a truthful resting position.
- Preserve Home's native-backed route truth, mirror start/stop behavior, LAN-only start, retry action, metrics, paired-device action, Settings action, and App filter action.
- Treat `ac7a5f3 feat(mobile): prove live direct LAN product routes` and every later functional commit present at execution start as immutable behavior baselines; the redesign may change their presentation only.
- Preserve Settings' pairing navigation, relay/direct-only truth, durable direct-route preference with rollback on failure, App filter navigation, system notification settings action, and version display.
- Preserve Welcome's exact approved product copy and routes to `/onboarding/how` and `/onboarding/role`.
- Support 44dp minimum interactive targets, meaningful font scaling, grouped live-region route announcements, light/dark contrast, narrow screens, and 2x system fonts without clipping.
- Follow strict TDD: observe the focused test fail before product edits, capture RED/GREEN logs, and make one conventional commit per task.
- Work only in the primary checkout. Record the starting SHA before Task 1 and use it for the final scope audit.

### Must NOT have (guardrails, anti-slop, scope boundaries)
- No changes to `mobile/modules/twinotify-core/**`, `mobile/hooks/useRouteStatus.ts`, `mobile/hooks/useSyncStatus.ts`, `mobile/hooks/useMetrics.ts`, `mobile/state/routePresentation.ts`, relay, protocol, LAN, pairing, unpair, custody, E2E, or dependency-security behavior.
- No worktree, push, app-data clear, device-data clear, physical-phone mutation, paid font, font download, network-fetched asset, image-generation dependency, or new runtime package.
- No cream/beige page base, cool UI-kit gray, blue-purple palette, gradients, glows, radial/concentric halo, grain over content, glass, shadows that bloom around a component, or hard-edged fake shadows.
- No centered default hero stack, eyebrow/kicker pill, filled-plus-outlined CTA pair, repeated faint rounded cards, status chips, icon tiles, decorative rules, default disclosure chevrons, generic gear glyph, or dead-looking empty composition.
- No button scale/translate "boop," card lift, entrance opacity gate, hidden-by-default content, pulsing dot, growing underline, or motion that ignores reduced-motion settings.
- No user-facing IP, port, SSID, relay secret, peer key, pin, digest, or other raw network/security evidence.
- No copy changes to route labels/explanations, approved Welcome claims, or Settings delivery semantics unless a test proves the existing copy is factually wrong. This plan does not authorize such a product decision.
- No snapshot-only approval. Structural tests, contrast calculation, real emulator screenshots, UI XML, and independent reviewers are all required.

## Design contract

### Fixed palette
Use literal hex values. Remove `culori` palette generation from the UI path; do not replace it with another runtime color library.

| Token | Light | Dark | Intended use |
|---|---:|---:|---|
| `bg` | `#E3E9E5` | `#111815` | mineral page surface / green-black night surface |
| `card` | `#EDF2EE` | `#17201C` | rare elevated content plane, not every group |
| `fill` | `#D4DED8` | `#1E2924` | quiet control/metric field |
| `hover` | `#C6D2CB` | `#27352F` | pressed/selected tonal state |
| `border` | `#6E8176` | `#60766B` | functional boundaries; >=3:1 against `bg`, `card`, and `fill` |
| `borderHi` | `#52645A` | `#789085` | focused/strong functional boundary; >=4.38:1 against its three possible surfaces |
| `ink` | `#17201C` | `#EEF3EF` | primary text |
| `ink2` | `#34423B` | `#C9D2CD` | secondary text |
| `ink3` | `#52625A` | `#98A69E` | small/supporting text; must remain >= 4.5:1 on `bg` |
| `ink4` | `#75837C` | `#6F7D75` | disabled/decorative only, never body copy |
| `accent` | `#1F685A` | `#7FAE9F` | verdigris route/ticket state, used tonally |
| `accentHi` | `#3B7C6E` | `#9BBEAE` | focused trace segment |
| `accentLo` | `#CADBD3` | `#263D34` | restrained route field |
| `accentText` | `#145446` | `#A9CCBE` | small accent text; calculated contrast >= 7:1 on `bg` |
| `switchOff` | `#52625A` | `#98A69E` | off track; graphical contrast >= 3:1 against the immediate control surface |

Semantic roles are display pairs, not one color reused on incompatible surfaces:

| Role | Light foreground | Light surface | Dark foreground | Dark surface |
|---|---:|---:|---:|---:|
| `ok` | `#235C3C` | `#D2E4D6` | `#79C894` | `#1B2A20` |
| `info` | `#205D78` | `#D0E2E9` | `#74BDE7` | `#172731` |
| `warn` | `#72520B` | `#E7DDC1` | `#F2C56C` | `#2A2418` |
| `danger` | `#963B38` | `#EBD6D3` | `#F07B76` | `#2E1D1C` |

The semantic contrast assertions use these calculated WCAG 2 ratios in this order: `foreground/surface`, `foreground/bg`, `foreground/card`, `foreground/fill`.

| Role | Light expected ratios | Dark expected ratios |
|---|---|---|
| `ok` | `5.93, 6.39, 6.95, 5.71` | `7.51, 9.01, 8.34, 7.51` |
| `info` | `5.43, 5.88, 6.40, 5.26` | `7.42, 8.73, 8.08, 7.28` |
| `warn` | `5.30, 5.83, 6.33, 5.21` | `9.52, 11.14, 10.30, 9.28` |
| `danger` | `5.07, 5.72, 6.22, 5.11` | `5.96, 6.69, 6.19, 5.58` |

Expose the selected mode through this exact semantic contract. This is the semantic portion of the existing `Theme`; retain its other fixed token/type/spacing fields rather than replacing the interface with only `sem`:

```ts
export interface SemanticDisplayPair {
  foreground: string;
  surface: string;
}

export type SemanticRole = 'ok' | 'info' | 'warn' | 'danger';
export type SemanticDisplay = Record<SemanticRole, SemanticDisplayPair>;

// `twTheme({ dark })` returns the matching mode here.
interface Theme {
  sem: SemanticDisplay;
}
```

For every role and mode, tests must prove `foreground` against `surface`, `bg`, `card`, and `fill` is >= 4.5:1 and match the rounded expected ratios above. Icons, borders, status dots, and destructive text use `foreground`; semantic panels/badges use `surface`; badge text uses `foreground`. Banner title/body may remain `ink`/`ink2` only because those two text colors are also tested against all four semantic surfaces in both modes. Functional non-text boundaries and route graphics use only pairs proven >=3:1 against their immediate surface. No semantic element may synthesize a low-opacity surface from a foreground.

### Typography
- `fonts.ui = 'sans-serif'`
- `fonts.uiMedium = 'sans-serif-medium'`
- `fonts.uiSemi = 'sans-serif-medium'`
- `fonts.uiBold = 'sans-serif'` with explicit `fontWeight: '700'`
- `fonts.display = 'sans-serif-condensed'`
- `fonts.mono = 'monospace'`
- `fonts.monoMedium = 'monospace'` with explicit `fontWeight: '500'`
- Use condensed type only for the Welcome statement, Home route state, and large data. Use monospace only for real metrics, version numbers, fingerprints, IDs, or codes.
- Keep meaningful text scalable. `TwWordmark` may keep `allowFontScaling={false}` because it is a compact brand mark, but every heading, explanation, row label, metric label, and action must scale.

### Handoff Trace geometry
Create `mobile/components/HandoffTrace.tsx` with these public contracts:

```ts
export type HandoffTraceState =
  | 'direct'
  | 'relay'
  | 'reconnecting'
  | 'queued'
  | 'paused'
  | 'unpaired';

export interface HandoffTraceProps {
  state: HandoffTraceState;
  width: number;
  height?: number;
  compact?: boolean;
  testID?: string;
}

export interface TraceGeometry {
  state: HandoffTraceState;
  leftBracket: string;
  rightBracket: string;
  routePaths: readonly string[];
  ticket: { x: number; y: number; width: number; height: number; path: string };
  waypointPaths: readonly string[];
}

export function buildTraceGeometry(
  state: HandoffTraceState,
  width: number,
  height: number,
): TraceGeometry;

export function traceTicketOffset(
  state: HandoffTraceState,
  reduceMotion: boolean,
  progress: number,
): number;

export function HandoffDisclosureMark(props: {
  size?: number;
  color?: string;
}): React.ReactElement;
```

Geometry rules:
- `direct`: one uninterrupted horizontal route between both brackets; ticket centered on the route.
- `relay`: two angled route segments meet at one small central relay waypoint above the baseline; the ticket sits on the outbound segment. Do not use a cloud icon.
- `reconnecting`: two solid approach segments stop around a central gap; the ticket remains visible on the left approach and may travel 0-8dp along that segment. Reduced motion fixes offset at 0.
- `queued`: the left route terminates in a three-notch queue bay before the midpoint; one ticket is docked in the bay. The right endpoint remains visible but disconnected.
- `paused`: the route is continuous except for one measured two-stroke pause gate at center; the ticket is docked immediately before the gate.
- `unpaired`: both empty brackets and the ticket beside the left bracket remain visible; no route joins the endpoints.
- Every state renders exactly two endpoint brackets and exactly one ticket. No circles, concentric rings, gradients, glows, shadows, icon tiles, or accessibility noise. The visible route words elsewhere own semantics, so the artifact is excluded from the accessibility tree.

### Screen compositions
- Welcome: left-aligned wordmark, large trace stage occupying the upper field, a one/two-line condensed statement below the trace, concise body copy, one full-width primary action, and one bare text action. The trace is the hero; do not restore `HeroRings` or center the copy.
- Home: treated top bar with wordmark and visible `Settings` text action; route state and switch share one top axis; the trace spans the screen below; explanation, paired peer, metrics, recent state, and the single relevant route action sit on one continuous surface. Use space and tone, not nested cards.
- Settings: strong left-aligned title; one continuous settings ledger with Pairing, Sync, Privacy, and About groups separated by generous spacing. Interactive rows use the Handoff disclosure mark, not `›`. Do not add a decorative route state the screen cannot truthfully derive.
- At <= 360dp width, reduce horizontal gutter to 16dp and let content flow vertically without absolute text placement. At >= 361dp use a 20-24dp gutter. At 2x font scale, all three screens scroll; controls never overlay text.

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD with Jest + React Native Testing Library, pure geometry/contrast functions, Expo typecheck/lint, and Android release assembly
- QA policy: every task has agent-executed scenarios; the final wave captures emulator screenshots and UI XML without clearing app/device data
- Evidence: `<attemptDir>/task-<N>-<slug>.<ext>` — under ulw-loop, `<attemptDir>` is the `currentAttemptDir` from `omo ulw-loop status --json` (`.omo/evidence/ulw/<session>/<goalId>/a<attempt>`); outside ulw-loop use `.omo/evidence/`

Before Task 1, record the baseline without changing the checkout:

```bash
mkdir -p .omo/evidence
git rev-parse HEAD | tee .omo/evidence/handoff-trace-start-sha.txt
git status --short | tee .omo/evidence/handoff-trace-start-status.txt
```

If unrelated changes exist, preserve them and stage/commit only the exact files named by each task. Never reset, stash, discard, or overwrite another worker's files.

## Execution strategy
### Parallel execution waves
> Target 5-8 tasks per wave where file ownership permits. Semantic-pair migration and primitive retuning touch the same files, so Task 3 intentionally follows Task 1; the three screen tasks remain parallel with disjoint ownership.

Wave 1 (no dependencies):
- Task 1: fixed palette, native typography, and removal of font gates/dependencies
- Task 2: Handoff Trace geometry, artifact, and disclosure mark

Wave 2 (after Task 1):
- Task 3: shared primitive visual/accessibility contract, depends [1]

Wave 3 (after Wave 2):
- Task 4: Welcome composition, depends [1, 2, 3]
- Task 5: Home composition and six-state trace binding, depends [1, 2, 3]
- Task 6: Settings composition, depends [1, 2, 3]

Wave 4 (after Wave 3):
- Final F1-F4 verification only; no product edits unless a reviewer rejects a criterion

Critical path: Task 1 -> Task 3 -> Task 4 -> F3 (Task 2 also gates Task 4 and runs alongside Task 1)

### Dependency matrix
| Task | Depends on | Blocks | Can parallelize with |
|------|------------|--------|----------------------|
| 1 | none | 3, 4, 5, 6 | 2 |
| 2 | none | 4, 5, 6 | 1 |
| 3 | 1 | 4, 5, 6 | 2 |
| 4 | 1, 2, 3 | F1-F4 | 5, 6 |
| 5 | 1, 2, 3 | F1-F4 | 4, 6 |
| 6 | 1, 2, 3 | F1-F4 | 4, 5 |

## Todos
> Implementation + Test = ONE task. Never separate.
> Every task MUST have: References + Acceptance Criteria + QA Scenarios + Commit.

- [ ] 1. Establish the fixed mineral/verdigris foundation and Android-native type

  Pre-edit RED (tests only; run before any production/package edit): create `mobile/components/__tests__/designFoundation.test.ts` and `mobile/components/__tests__/semanticContrast.test.ts`, and first update `routeContrast.test.ts` to expect the new literal contract. Then run:

  ```bash
  cd mobile
  bash -o pipefail -c 'npm test -- --runInBand components/__tests__/designFoundation.test.ts components/__tests__/semanticContrast.test.ts components/__tests__/routeContrast.test.ts 2>&1 | tee ../.omo/evidence/task-1-foundation-red.log; test ${PIPESTATUS[0]} -ne 0'
  ```

  Expected RED: Jest fails because native font names, fixed palette literals, and `SemanticDisplayPair` do not exist yet. The wrapper exits 0 only when the test command itself is nonzero. Evidence: `<attemptDir>/task-1-foundation-red.log`.

  What to do: Replace generated warm-neutral/hue tokens with the exact Design Contract literals; implement the exact light/dark `SemanticDisplayPair` table; add `display` to the font contract; simplify `ThemeProvider` to system light/dark only; remove its AsyncStorage readiness gate and unused hue controls; delete `useFonts.ts`; remove `useAppFonts` from the barrel/root layout; remove `@expo-google-fonts/inter`, `@expo-google-fonts/jetbrains-mono`, and now-unused `culori` from both package manifests. Update every current semantic consumer to use `theme.sem.<role>.foreground` for text/icons/borders/status dots and `.surface` for fills; badge text uses the role foreground, and every button variant uses a tested theme text/fill pair (`accent` uses `bg`, never hard-coded white). Replace the accent card's alpha fill with `accentLo`; remove `hexWithAlpha` once its last consumer is gone. Update the Jest theme mock to call `twTheme({ dark })` and expose only the real context surface. When `HANDOFF_CONTRAST_EVIDENCE` is set, `semanticContrast.test.ts` writes stable two-space JSON shaped as `{ schema: 'twinotify.handoff-contrast.v1', modes: [{ mode, tokens, checks: [{ foreground, background, foregroundHex, backgroundHex, kind, ratio, threshold, pass }] }] }`; modes are ordered light/dark, checks are sorted by foreground/background name, `kind` is `text` or `graphic`, ratios are rounded to four decimals, and the file contains no timestamp or absolute path.

  Must NOT do: Do not add a replacement font package, download an asset, remove AsyncStorage from the app, change navigation, change app startup routing, or modify any screen composition in this task.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [3, 4, 5, 6] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `mobile/components/tokens.ts:6-13` - current font-name contract to replace with Android system families
  - Pattern:  `mobile/components/tokens.ts:15-45` - generated hue system to remove, not recolor
  - Pattern:  `mobile/components/tokens.ts:47-71` - current cream/warm-neutral surface that the new literal palette replaces
  - API/Type: `mobile/components/tokens.ts:73-79,136-186` - replace flat semantic strings with the selected mode's explicit foreground/surface pairs
  - Pattern:  `mobile/components/Theme.tsx:13-79` - unused hue/dark preference and async render gate to simplify to `useColorScheme`
  - Pattern:  `mobile/components/useFonts.ts:1-22` - file to delete
  - Pattern:  `mobile/app/_layout.tsx:1-14` - remove only the font hook and `return null`; preserve `usePeerUnpairListener`, provider, and stack
  - Pattern:  `mobile/components/index.ts:1-18` - remove only the `useAppFonts` export
  - Pattern:  `mobile/package.json:17-53` - remove the two Google-font packages and now-unused `culori`; do not add a replacement
  - Pattern:  `mobile/components/primitives/TwBanner.tsx:19-46` - replace alpha-synthesized banner colors with the selected role's explicit surface/foreground pair
  - Pattern:  `mobile/components/primitives/TwCard.tsx:47-59` - semantic card tones use explicit surfaces/borders rather than low-opacity foregrounds
  - Pattern:  `mobile/components/primitives/TwButton.tsx:89-113` - destructive text/border uses `theme.sem.danger.foreground`
  - Pattern:  `mobile/components/primitives/TwStatusDot.tsx:21-31` - dots use the active theme's semantic foreground
  - Pattern:  `mobile/components/primitives/TwAppChip.tsx:61-64` - danger badge uses danger surface with danger foreground text
  - Pattern:  `mobile/app/onboarding/perms.tsx:52-99` - update the passed semantic type and granted border/badge foreground/surface uses
  - Pattern:  `mobile/app/onboarding/relay.tsx:111-147` - error/success border and text use semantic foregrounds
  - Pattern:  `mobile/app/pair/fail.tsx:18-20` and `mobile/app/pair/fingerprint.tsx:141` - danger icon/text use semantic foreground
  - Test:     `mobile/components/__tests__/routeContrast.test.ts:1-23` - extend the luminance/contrast pattern to every text/control pairing in the Design Contract
  - Test:     `mobile/jest.setup.js:16-28` - align the test theme mock with the simplified Theme API

  Acceptance criteria (agent-executable only):
  - [ ] New tests assert the exact light/dark base and semantic tables, `accentText/bg >= 4.5`, `ink3/bg >= 4.5`, every button variant's actual text/fill >=4.5, `ink`/`ink2` against every semantic surface >=4.5, switch track/immediate surface >=3.0, and both `border`/`borderHi` against every possible `bg|card|fill` surface >=3.0.
  - [ ] For every `ok|info|warn|danger` role in both modes, automated tests assert semantic foreground against semantic surface, `bg`, `card`, and `fill` is >=4.5:1 and equals the Design Contract's expected ratio after rounding to two decimals; generated evidence includes the four-decimal ratios.
  - [ ] Every actual semantic consumer uses the selected theme pair. No UI consumer imports a flat `TW_SEMANTIC.<role>` color or generates a semantic surface with alpha.
  - [ ] `HANDOFF_CONTRAST_EVIDENCE=<path>` causes the test to write deterministic valid JSON for both modes and all base/semantic pairs.
  - [ ] `TW_FONTS` contains only Android-native family names from the Typography contract; no Inter or JetBrains string remains under `mobile/app` or `mobile/components`.
  - [ ] `mobile/package.json` and `mobile/package-lock.json` contain none of `@expo-google-fonts/inter`, `@expo-google-fonts/jetbrains-mono`, or `culori`.
  - [ ] `mobile/components/useFonts.ts` is deleted; `RootLayout` renders `ThemeProvider` and `Stack` without waiting for font load.
  - [ ] `rg -n "Inter_|JetBrains|@expo-google-fonts|useAppFonts|twBuildPalette|TW_HUES|culori|hexWithAlpha" mobile/app mobile/components mobile/package.json mobile/package-lock.json` returns no matches.
  - [ ] `cd mobile && npm run typecheck && npm test -- --runInBand components/__tests__/designFoundation.test.ts components/__tests__/semanticContrast.test.ts components/__tests__/routeContrast.test.ts` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: The fixed foundation is exact and readable
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand components/__tests__/designFoundation.test.ts components/__tests__/semanticContrast.test.ts components/__tests__/routeContrast.test.ts 2>&1 | tee ../.omo/evidence/task-1-foundation-green.log
    Expected: All exact-token, semantic-pair, native-font, package-removal, and WCAG contrast assertions pass.
    Evidence: <attemptDir>/task-1-foundation-green.log

  Scenario: A forbidden font or runtime palette dependency cannot return unnoticed
    Tool:     bash
    Steps:    cd mobile && ! rg -n "Inter_|JetBrains|@expo-google-fonts|useAppFonts|twBuildPalette|TW_HUES|culori|hexWithAlpha" app components package.json package-lock.json
    Expected: The search produces no match and the inverted check exits 0.
    Evidence: <attemptDir>/task-1-foundation-error.log
  ```

  Commit: YES | Message: `refactor(mobile/ui): adopt native mineral foundation` | Files: [`mobile/components/tokens.ts`, `mobile/components/Theme.tsx`, `mobile/components/useFonts.ts`, `mobile/components/index.ts`, `mobile/components/primitives/TwAppChip.tsx`, `mobile/components/primitives/TwBanner.tsx`, `mobile/components/primitives/TwButton.tsx`, `mobile/components/primitives/TwCard.tsx`, `mobile/components/primitives/TwStatusDot.tsx`, `mobile/app/_layout.tsx`, `mobile/app/onboarding/perms.tsx`, `mobile/app/onboarding/relay.tsx`, `mobile/app/pair/fail.tsx`, `mobile/app/pair/fingerprint.tsx`, `mobile/jest.setup.js`, `mobile/components/__tests__/designFoundation.test.ts`, `mobile/components/__tests__/semanticContrast.test.ts`, `mobile/components/__tests__/routeContrast.test.ts`, `mobile/package.json`, `mobile/package-lock.json`]

- [ ] 2. Build the Handoff Trace signature artifact and state geometry

  Pre-edit RED (tests only; run before creating `HandoffTrace.tsx`): create `mobile/components/__tests__/HandoffTrace.test.tsx`, including the optional evidence writer described below. Then run:

  ```bash
  cd mobile
  bash -o pipefail -c 'npm test -- --runInBand components/__tests__/HandoffTrace.test.tsx 2>&1 | tee ../.omo/evidence/task-2-trace-geometry-red.log; test ${PIPESTATUS[0]} -ne 0'
  ```

  Expected RED: Jest fails with `Cannot find module '../HandoffTrace'`. Evidence: `<attemptDir>/task-2-trace-geometry-red.log`.

  What to do: Implement `HandoffTrace`, `buildTraceGeometry`, `traceTicketOffset`, and `HandoffDisclosureMark` exactly as defined in the Design Contract. Use `react-native-svg` already in the repository. Reconnecting may animate only the ticket's horizontal translation over a maximum 8dp; the line, brackets, ticket, and all screen content are visible at the resting frame. Stop/cancel the animation on unmount and use `useReducedMotion()` to freeze it. In the table test, when `HANDOFF_GEOMETRY_EVIDENCE` is set, write stable two-space JSON shaped as `{ schema: 'twinotify.handoff-geometry.v1', input: { width, height }, states: [{ state, geometry, signature }], assertions: { stateCount, uniqueSignatures, twoBracketsOneTicket } }`; states use the union order in the Design Contract, signatures are stable serialized route/waypoint geometry, and the file contains no timestamp, random value, or absolute path.

  Must NOT do: Do not import a general icon pack, draw a gear/cloud/phone icon, use circles/rings/gradients/glows/shadows, animate opacity/scale, expose decorative paths to accessibility, or change route copy/state types.

  Parallelization: Can parallel: YES | Wave 1 | Blocks: [4, 5, 6] | Blocked by: []

  References (executor has NO interview context - be exhaustive):
  - API/Type: `mobile/state/routePresentation.ts:3-16` - six `DeliveryState` values that the artifact must cover one-to-one
  - API/Type: `mobile/state/routePresentation.ts:25-85` - state priority and truth remain authoritative; geometry never reinterprets it
  - Pattern:  `mobile/components/primitives/TwLogo.tsx:14-46` - existing local SVG technique; do not copy the ring logo geometry
  - Pattern:  `mobile/components/primitives/TwIcon.tsx:19-32` - rounded path cap/join implementation conventions only
  - Test:     `mobile/components/primitives/__tests__/TwStatusDot.test.tsx:16-55` - rendered-tree structural assertions and accessibility exclusion
  - External: `https://docs.swmansion.com/react-native-reanimated/docs/device/useReducedMotion/` - existing reduced-motion API already used by the app

  Acceptance criteria (agent-executable only):
  - [ ] A table-driven test covers all six states and proves every `TraceGeometry` has two nonempty bracket paths, exactly one ticket path, bounded coordinates, and a unique serialized route/waypoint signature.
  - [ ] Direct has one uninterrupted route; relay has two angled segments plus one waypoint; reconnecting has a center gap; queued has a three-notch bay and no joined right route; paused has a two-stroke gate; unpaired has zero joining route paths.
  - [ ] `traceTicketOffset` returns 0 for every reduced-motion input and never returns outside 0-8 for reconnecting.
  - [ ] Rendered output contains no `LinearGradient`, `RadialGradient`, `Circle`, shadow, filter, scale transform, or second ticket.
  - [ ] The root artifact is `accessible={false}` and `importantForAccessibility="no-hide-descendants"`.
  - [ ] `HANDOFF_GEOMETRY_EVIDENCE=<path>` causes the test to write deterministic valid JSON containing all six states and no timestamps, random values, or absolute paths.
  - [ ] `cd mobile && npm test -- --runInBand components/__tests__/HandoffTrace.test.tsx && npm run typecheck` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Six states produce six truthful, bounded signatures
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand components/__tests__/HandoffTrace.test.tsx -t "six state geometries" 2>&1 | tee ../.omo/evidence/task-2-trace-geometry-green.log
    Expected: The table reports six passing cases and six distinct serialized geometries.
    Evidence: <attemptDir>/task-2-trace-geometry-green.log

  Scenario: Reduced motion and anti-glow structure are enforced
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand components/__tests__/HandoffTrace.test.tsx -t "reduced motion|forbidden rendering" 2>&1 | tee ../.omo/evidence/task-2-trace-geometry-error.log
    Expected: Offset is always zero under reduced motion and the rendered tree contains none of the forbidden primitives/effects.
    Evidence: <attemptDir>/task-2-trace-geometry-error.log
  ```

  Commit: YES | Message: `feat(mobile/ui): add handoff trace signature` | Files: [`mobile/components/HandoffTrace.tsx`, `mobile/components/__tests__/HandoffTrace.test.tsx`]

- [ ] 3. Retune shared primitives for the Handoff Trace visual language

  Pre-edit RED (tests only; run before primitive edits): update `TwButton.test.tsx` and `TwWordmark.test.tsx`, create `TwCard.test.tsx`, `TwSwitch.test.tsx`, and `mobile/scripts/__tests__/check-ui-xml.test.ts` with passing/undersized/out-of-bounds/sibling-overlap fixtures. The checker test invokes the `.mjs` CLI with `spawnSync(process.execPath, ...)` so Jest does not need to transform the production ESM script. Then run:

  ```bash
  cd mobile
  bash -o pipefail -c 'npm test -- --runInBand components/primitives/__tests__/TwButton.test.tsx components/primitives/__tests__/TwCard.test.tsx components/primitives/__tests__/TwSwitch.test.tsx components/primitives/__tests__/TwWordmark.test.tsx scripts/__tests__/check-ui-xml.test.ts 2>&1 | tee ../.omo/evidence/task-3-primitives-red.log; test ${PIPESTATUS[0]} -ne 0'
  ```

  Expected RED: current TwButton still scales, TwCard still has default border/shadow, TwWordmark still contains a logo/accent split, TwSwitch lacks a physical 44x44 frame, and the XML checker module is missing. Evidence: `<attemptDir>/task-3-primitives-red.log`.

  What to do: Remove TwButton scale animation in favor of a stationary tonal pressed state; ensure the physical Pressable target is >=44dp; make `TwCard` a rare tonal plane without default border/shadow while retaining Task 1's semantic pair use; delete the now-unused `TW_SHADOW` token and Theme shadow fields after the last card consumer is removed; make `TwWordmark` a single-color text mark without `TwLogo`; keep TwRow's full title/subtitle accessible name and scalable text; give TwSwitch a real 44x44 Pressable target with reduced-motion thumb translation. Implement dependency-free `mobile/scripts/check-ui-xml.mjs`: parse nested uiautomator `<node>` XML, determine ancestor relationships, verify all nonempty text/content-description bounds fit the root viewport, verify every clickable/checkable/focusable node is at least `44 * density / 160` pixels on each axis, and reject intersections between a leaf interactive node and a text node that is neither ancestor nor descendant. CLI: `node check-ui-xml.mjs --density <dpi> --expect-files <N> <xml...>`. Keep all public variants used by existing screens.

  Must NOT do: Do not add gradients, glows, pills, default shadows, icon tiles, a filled-plus-outlined action pair, or a new dependency. Do not remove variants used outside the three redesigned screens.

  Parallelization: Can parallel: NO | Wave 2 | Blocks: [4, 5, 6] | Blocked by: [1]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `mobile/components/primitives/TwButton.tsx:20-39` - preserve button variants/sizes and 48/56dp sizing
  - Pattern:  `mobile/components/primitives/TwButton.tsx:41-82` - scale helper/animation to delete
  - API/Type: `mobile/components/primitives/TwButton.tsx:116-164` - preserve accessible name, disabled/busy state, loading indicator, and scalable label
  - API/Type: `mobile/components/primitives/TwCard.tsx:6-24` - preserve tone/interactivity API while changing default material
  - Pattern:  `mobile/components/primitives/TwCard.tsx:33-69` - default 1px border/shadow behavior to remove
  - API/Type: `mobile/components/primitives/TwRow.tsx:5-54` - preserve row API and complete accessibility name
  - API/Type: `mobile/components/primitives/TwSwitch.tsx:12-91` - preserve switch role/state and reduced-motion timing; expand the physical Pressable frame
  - Pattern:  `mobile/components/primitives/TwWordmark.tsx:13-29` - remove logo + accent split, retain one-line brand scaling behavior
  - Pattern:  `mobile/components/tokens.ts:87-113,144-148,181-185` - remove the shadow constant and Theme fields only after TwCard stops consuming them
  - Test:     `mobile/components/primitives/__tests__/TwButton.test.tsx:12-46` - update the obsolete scale assertion to a no-transform assertion; retain all accessibility tests
  - Test:     `mobile/components/primitives/__tests__/TwRow.test.tsx:7-31` - unchanged accessibility regression
  - Test:     `mobile/components/primitives/__tests__/TwWordmark.test.tsx:7-12` - retain one-line brand contract and add one-color assertion
  - Test:     `mobile/scripts/__tests__/check-ui-xml.test.ts` - use `spawnSync` against the real CLI and deterministic XML fixtures to prove each failure class and exact PASS summary

  Acceptance criteria (agent-executable only):
  - [ ] TwButton has no scale/translate transform in source or rendered style, never moves on press, and uses only opacity or background tone for pressed feedback.
  - [ ] TwCard default/raised styles have no all-around shadow; default has no border; functional warn/danger boundaries remain legible.
  - [ ] `rg -n "TW_SHADOW|shadowSm|shadowMd|shadowLg" mobile/app mobile/components` returns no matches after the last consumer is removed.
  - [ ] TwWordmark renders one Text node, one color, no nested accent Text, and no icon.
  - [ ] TwRow and TwSwitch expose correct roles/states/names; every interactive primitive's physical frame is >=44x44.
  - [ ] Meaningful primitive labels do not set `allowFontScaling={false}`.
  - [ ] XML checker exits 0 only when all files meet viewport, min-target, and non-related-overlap rules; it prints exactly `UI_XML_CHECK PASS files=<N> out_of_bounds=0 undersized=0 sibling_overlaps=0`.
  - [ ] `cd mobile && npm test -- --runInBand components/primitives/__tests__ scripts/__tests__/check-ui-xml.test.ts && npm run typecheck` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Primitive behavior and accessibility survive the material change
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand components/primitives/__tests__ scripts/__tests__/check-ui-xml.test.ts 2>&1 | tee ../.omo/evidence/task-3-primitives-green.log
    Expected: Button, card, row, switch, wordmark, status, XML-bounds, loading, disabled, target-size, and font-scaling tests all pass.
    Evidence: <attemptDir>/task-3-primitives-green.log

  Scenario: Generic motion and material effects cannot regress
    Tool:     bash
    Steps:    cd mobile && ! rg -n "transform:.*scale|withSpring|shadowOpacity|shadowRadius" components/primitives/TwButton.tsx components/primitives/TwCard.tsx components/primitives/TwWordmark.tsx
    Expected: No scale animation or default shadow implementation is found in the three primitives.
    Evidence: <attemptDir>/task-3-primitives-error.log
  ```

  Commit: YES | Message: `refactor(mobile/ui): align primitives with handoff trace` | Files: [`mobile/components/tokens.ts`, `mobile/components/primitives/TwButton.tsx`, `mobile/components/primitives/TwCard.tsx`, `mobile/components/primitives/TwRow.tsx`, `mobile/components/primitives/TwSwitch.tsx`, `mobile/components/primitives/TwWordmark.tsx`, `mobile/components/primitives/__tests__/TwButton.test.tsx`, `mobile/components/primitives/__tests__/TwCard.test.tsx`, `mobile/components/primitives/__tests__/TwRow.test.tsx`, `mobile/components/primitives/__tests__/TwSwitch.test.tsx`, `mobile/components/primitives/__tests__/TwWordmark.test.tsx`, `mobile/scripts/check-ui-xml.mjs`, `mobile/scripts/__tests__/check-ui-xml.test.ts`, `mobile/scripts/__tests__/fixtures/ui-pass.xml`, `mobile/scripts/__tests__/fixtures/ui-undersized.xml`, `mobile/scripts/__tests__/fixtures/ui-out-of-bounds.xml`, `mobile/scripts/__tests__/fixtures/ui-overlap.xml`]

- [ ] 4. Recompose Welcome around the Handoff Trace artifact

  Pre-edit RED (tests only; run before editing Welcome): create `mobile/app/__tests__/welcomeHandoffTrace.test.tsx`. Then run:

  ```bash
  cd mobile
  bash -o pipefail -c 'npm test -- --runInBand app/__tests__/welcomeHandoffTrace.test.tsx 2>&1 | tee ../.omo/evidence/task-4-welcome-red.log; test ${PIPESTATUS[0]} -ne 0'
  ```

  Expected RED: the current screen has `HeroRings`, no Handoff Trace, centered layout, and the obsolete action/material structure. Evidence: `<attemptDir>/task-4-welcome-red.log`.

  What to do: Delete `HeroRings` and implement the left-aligned composition in the Screen Contract. Import `HandoffTrace` directly from `../../components/HandoffTrace`; derive its width from `useWindowDimensions()` minus responsive gutters and clamp only the artifact width, never text scale. Keep the existing ScrollView so narrow/2x layouts remain reachable. Use one primary button and a stationary bare text action.

  Must NOT do: Do not change the approved copy, route destinations, wordmark behavior, onboarding state, add an eyebrow, center the copy, pair a filled button with an outlined button, hide content behind animation, or cap meaningful font scaling.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [F1, F2, F3, F4] | Blocked by: [1, 2, 3]

  References (executor has NO interview context - be exhaustive):
  - Pattern:  `mobile/app/onboarding/welcome.tsx:8-17` - `HeroRings` to delete completely
  - API/Type: `mobile/app/onboarding/welcome.tsx:19-98` - preserve `WelcomeScreen`, exact copy, routes, roles, and ScrollView
  - Pattern:  `mobile/app/onboarding/welcome.tsx:101-110` - replace centered/min-height hero stack with responsive left-aligned layout
  - Test:     `mobile/app/__tests__/routeProductTruth.test.tsx:81-108` - approved claims, alternate action, scalable text, and light/dark copy opacity
  - API/Type: `mobile/components/HandoffTrace.tsx:HandoffTrace` - signature artifact created in Task 2

  Acceptance criteria (agent-executable only):
  - [ ] `HeroRings`, `Circle`, and concentric/radial artwork are absent from Welcome.
  - [ ] `HandoffTrace` is present once, full-width within the responsive gutter, and marked decorative.
  - [ ] Copy remains exactly: `Mirror selected notifications.` and `Send selected alerts to your second phone. End-to-end encrypted, with no account required.`
  - [ ] `Get started` still pushes `/onboarding/how`; `I already have a code` still replaces with `/onboarding/role`.
  - [ ] The primary and alternate actions are each >=44dp, text scales, and the screen remains scrollable at 2x font scale.
  - [ ] `cd mobile && npm test -- --runInBand app/__tests__/welcomeHandoffTrace.test.tsx app/__tests__/routeProductTruth.test.tsx && npm run typecheck` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Welcome presents the signature and preserves both actions
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand app/__tests__/welcomeHandoffTrace.test.tsx app/__tests__/routeProductTruth.test.tsx -t "Welcome|describes encryption" 2>&1 | tee ../.omo/evidence/task-4-welcome-green.log
    Expected: One Handoff Trace renders; approved copy, routes, roles, and target sizes all pass.
    Evidence: <attemptDir>/task-4-welcome-green.log

  Scenario: Enlarged text remains scalable and scroll-reachable
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand app/__tests__/welcomeHandoffTrace.test.tsx -t "scalable scroll layout" 2>&1 | tee ../.omo/evidence/task-4-welcome-fontscale-green.log
    Expected: The root is a ScrollView, meaningful text allows scaling with line height greater than font size, no absolute copy/action placement exists, and both actions retain >=44dp targets.
    Evidence: <attemptDir>/task-4-welcome-fontscale-green.log
  ```

  Commit: YES | Message: `feat(mobile/ui): recompose welcome handoff` | Files: [`mobile/app/onboarding/welcome.tsx`, `mobile/app/__tests__/welcomeHandoffTrace.test.tsx`]

- [ ] 5. Recompose Home as the live route canvas

  Pre-edit RED (tests only; run before editing Home): create `mobile/app/__tests__/homeHandoffTrace.test.tsx`. Then run:

  ```bash
  cd mobile
  bash -o pipefail -c 'npm test -- --runInBand app/__tests__/homeHandoffTrace.test.tsx 2>&1 | tee ../.omo/evidence/task-5-home-red.log; test ${PIPESTATUS[0]} -ne 0'
  ```

  Expected RED: the current Home has no state-bound Handoff Trace, still uses hero/recent cards, and contains Unicode gear/chevron glyphs. Evidence: `<attemptDir>/task-5-home-red.log`.

  What to do: Replace the hero/recent card stack with one continuous route canvas. Bind `HandoffTrace.state` directly to `presentRoute(...).state`; keep the current grouped live-region label/explanation; retain switch, native methods, error rollback, paired peer, metrics, route action, Settings, paired-device, and App filter navigation. Replace the Unicode gear with the visible text action `Settings`; replace `›` with `HandoffDisclosureMark`. Use the route state as the largest condensed line, the trace as the focal artifact, aligned data columns, and a terse recent empty state without another bordered card.

  Must NOT do: Do not infer route from relay connectivity, modify `presentRoute`, add new route copy, change native calls, modify hooks, show raw network evidence, use multiple cards, or make an unreachable-looking action.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [F1, F2, F3, F4] | Blocked by: [1, 2, 3]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `mobile/app/home.tsx:30-32` - preserve `serviceIsRunning` truth
  - API/Type: `mobile/app/home.tsx:36-91` - preserve native seeding, start/stop, retry, `presentRoute`, peer label, and reachability
  - Pattern:  `mobile/app/home.tsx:99-185` - top bar and card hero to recompose; retain live-region semantics at lines 121-133
  - API/Type: `mobile/app/home.tsx:187-207` - preserve the single state-dependent recovery action
  - API/Type: `mobile/app/home.tsx:209-232` - preserve Recent and App filter destinations while removing repeated card material
  - API/Type: `mobile/state/routePresentation.ts:25-85` - only source for visual state and words
  - Test:     `mobile/state/__tests__/routePresentation.test.ts:11-98` - complete route truth matrix that must stay green unchanged
  - Test:     `mobile/app/__tests__/routeProductTruth.test.tsx:43-72` - paused copy, LAN-only start, settings/pair controls, metrics, and live region
  - API/Type: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts:80-103` - native calls whose invocation signatures must not change

  Acceptance criteria (agent-executable only):
  - [ ] Table-driven screen tests prove all six `DeliveryState` values select the matching `HandoffTrace` test ID/geometry; label/explanation remain the exact `presentRoute` output.
  - [ ] Healthy LAN shows direct geometry even with no relay URL; healthy relay shows relay geometry; queued outranks reconnecting; disabled mirroring shows paused; unpaired shows unpaired and one pair action.
  - [ ] Mirror on with relay URL calls `startSyncService(relayUrl)`; mirror on without it calls `startLanOnlySyncService()`; mirror off calls `stopSyncService()`; failure reverts the switch.
  - [ ] Retry calls `retryRoute`; Settings, paired-device, pair, and App filter destinations remain unchanged.
  - [ ] No Unicode `⚙`, `›`, `‹`, repeated TwCard hero/recent wrappers, pulsing state dot, or raw network detail appears.
  - [ ] Metrics keep `No data` instead of `0ms`, use monospace only for real numbers, and expose truthful accessibility labels.
  - [ ] `cd mobile && npm test -- --runInBand app/__tests__/homeHandoffTrace.test.tsx app/__tests__/routeProductTruth.test.tsx state/__tests__/routePresentation.test.ts && npm run typecheck` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Every product state drives the matching route geometry
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand app/__tests__/homeHandoffTrace.test.tsx -t "direct|relay|reconnecting|queued|paused|unpaired" 2>&1 | tee ../.omo/evidence/task-5-home-states-green.log
    Expected: Six screen-state cases pass with exact route words and matching geometry identifiers.
    Evidence: <attemptDir>/task-5-home-states-green.log

  Scenario: A route presentation cannot leak or invent transport truth
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand state/__tests__/routePresentation.test.ts app/__tests__/routeProductTruth.test.tsx 2>&1 | tee ../.omo/evidence/task-5-home-truth-error.log
    Expected: LAN-without-relay, relay, queued priority, pause, retry, and no-network-detail assertions all pass unchanged.
    Evidence: <attemptDir>/task-5-home-truth-error.log
  ```

  Commit: YES | Message: `feat(mobile/ui): make home a live handoff canvas` | Files: [`mobile/app/home.tsx`, `mobile/app/__tests__/homeHandoffTrace.test.tsx`]

- [ ] 6. Recompose Settings as a continuous handoff ledger

  Pre-edit RED (tests only; run before editing Settings): create `mobile/app/__tests__/settingsHandoffTrace.test.tsx`. Then run:

  ```bash
  cd mobile
  bash -o pipefail -c 'npm test -- --runInBand app/__tests__/settingsHandoffTrace.test.tsx 2>&1 | tee ../.omo/evidence/task-6-settings-red.log; test ${PIPESTATUS[0]} -ne 0'
  ```

  Expected RED: the current Settings screen still wraps every group in TwCard and uses Unicode chevrons instead of Handoff disclosures. Evidence: `<attemptDir>/task-6-settings-red.log`.

  What to do: Remove the repeated TwCard group wrappers. Keep four plainly named groups separated by space, align title/subtitle/trailing controls on a stable row grid, and use `HandoffDisclosureMark` on interactive rows. Preserve all current asynchronous loading, preference rollback, copy, actions, and the truthful difference between relay-configured and direct-only peers. Make the ledger scroll and allow row height to grow at 2x font scale.

  Must NOT do: Do not display an inferred live route, add theme/hue controls, reintroduce lock-screen preview, change direct-route preference semantics, collapse subtitle space, add chevrons, or wrap each section in a faint rounded card.

  Parallelization: Can parallel: YES | Wave 3 | Blocks: [F1, F2, F3, F4] | Blocked by: [1, 2, 3]

  References (executor has NO interview context - be exhaustive):
  - API/Type: `mobile/app/settings/index.tsx:24-31` - retain sync-state label mapping
  - API/Type: `mobile/app/settings/index.tsx:36-71` - preserve pair/relay/preference state and rollback behavior
  - Pattern:  `mobile/app/settings/index.tsx:73-77` - current section-header helper may remain as plain text but not as tracked caps/pill
  - Pattern:  `mobile/app/settings/index.tsx:79-171` - four group contents and destinations to preserve; remove only repeated card/Unicode material
  - Test:     `mobile/app/__tests__/routeProductTruth.test.tsx:17-41` - relay-first truth, direct-only no-switch rule, and removed dead privacy control
  - Test:     `mobile/app/__tests__/routeProductTruth.test.tsx:74-79` - truthful system notification settings label
  - Test:     `mobile/components/primitives/__tests__/TwRow.test.tsx:7-31` - complete title/subtitle accessible names

  Acceptance criteria (agent-executable only):
  - [ ] Pairing, Sync, Privacy, and About remain visible and ordered; no group is a default bordered/rounded card.
  - [ ] Relay-configured peers show Relay server plus Prefer direct Wi-Fi; direct-only peers show Delivery route / Direct Wi-Fi only and no impossible preference switch.
  - [ ] A rejected `setPreferLan` call restores the prior visible switch state.
  - [ ] Paired device, App filter, and Notification settings actions keep their destinations/native call and complete accessible names.
  - [ ] No lock-screen preview, Unicode chevron, hue control, or inferred live-route claim exists.
  - [ ] Rows expand under font scaling, maintain >=44dp targets, and do not let a trailing switch/disclosure overlap text.
  - [ ] `cd mobile && npm test -- --runInBand app/__tests__/settingsHandoffTrace.test.tsx app/__tests__/routeProductTruth.test.tsx components/primitives/__tests__/TwRow.test.tsx && npm run typecheck` exits 0.

  QA scenarios (MANDATORY - task incomplete without these):
  ```
  Scenario: Relay and direct-only ledgers remain truthful and actionable
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand app/__tests__/settingsHandoffTrace.test.tsx app/__tests__/routeProductTruth.test.tsx -t "relay|direct Wi-Fi only|notification settings|preference" 2>&1 | tee ../.omo/evidence/task-6-settings-green.log
    Expected: Both configuration branches, rollback, destinations, and accessible names pass.
    Evidence: <attemptDir>/task-6-settings-green.log

  Scenario: Preference failure and scalable row structure remain safe
    Tool:     bash
    Steps:    cd mobile && npm test -- --runInBand app/__tests__/settingsHandoffTrace.test.tsx -t "rolls back|scalable ledger" 2>&1 | tee ../.omo/evidence/task-6-settings-edge-green.log
    Expected: Rejected persistence restores the switch; rows contain scalable text, grow vertically, and keep a separate >=44dp trailing-control slot.
    Evidence: <attemptDir>/task-6-settings-edge-green.log
  ```

  Commit: YES | Message: `feat(mobile/ui): turn settings into handoff ledger` | Files: [`mobile/app/settings/index.tsx`, `mobile/app/__tests__/settingsHandoffTrace.test.tsx`]

## Final verification wave (MANDATORY - after all implementation tasks)
> Runs in PARALLEL. ALL must APPROVE. Surface results to the caller and wait for an explicit "okay" before declaring complete.
- [ ] F1. Plan compliance audit - a fresh read-only reviewer checks Tasks 1-6, every RED/GREEN log, exact file ownership, commit atomicity, and the forbidden-scope diff from the recorded starting SHA. Evidence: `<attemptDir>/final-plan-compliance.md`.
- [ ] F2. Code quality review - a fresh read-only reviewer inspects the entire diff for route-truth regressions, animation lifecycle leaks, brittle geometry, accessibility regressions, dead code, dependency residue, and React Native idioms. Run `cd mobile && npm run typecheck && npm test -- --runInBand && npm run lint` plus `git diff --check`. Evidence: `<attemptDir>/final-code-review.md` and `<attemptDir>/final-mobile-gates.log`.
- [ ] F3. Real manual QA - an independent QA executor assembles a bundled release, installs it on `emulator-5554` without clearing data, and captures the evidence matrix below. It inspects every PNG and corresponding UI XML, including scroll bottoms. Evidence: `<attemptDir>/final-qa-report.md` plus the named PNG/XML files.
- [ ] F4. Scope fidelity and anti-slop audit - an independent UI reviewer checks the complete Anti-Slop Checklist below point by point against the final light/dark/narrow/2x captures. Nothing extra may ship beyond Must Have and none of Must NOT Have may appear. Evidence: `<attemptDir>/final-ui-anti-slop-review.md`.

### Final automated commands

```bash
cd mobile
npm run typecheck
npm test -- --runInBand
npm run lint
npx expo-doctor
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug assembleRelease
cd ../..
git diff --check
HANDOFF_START_SHA=$(cat .omo/evidence/handoff-trace-start-sha.txt)
git diff --name-only "$HANDOFF_START_SHA"..HEAD | tee .omo/evidence/final-changed-files.txt
if rg -n '^(mobile/modules/twinotify-core|mobile/hooks/use(RouteStatus|SyncStatus|Metrics)\.ts|mobile/state/routePresentation\.ts|relay/|proto/|e2e/)' .omo/evidence/final-changed-files.txt; then exit 1; fi
```

### Emulator evidence matrix

Precondition: `mobile/android/app/build/outputs/apk/release/app-release.apk` exists from F2. Do not run `pm clear`, uninstall with data removal, or modify a physical phone.

| Surface | Mode/size | Font | Required evidence | Binary checks |
|---|---|---:|---|---|
| Welcome | light, 1080x2400 | 1.0 | `welcome-light.png/.xml` | trace is focal; copy left-aligned; both actions visible; no rings/card stack |
| Welcome | dark, 1080x2400 | 1.0 | `welcome-dark.png/.xml` | same hierarchy; no blue-charcoal; contrast passes |
| Welcome | light, 720x1600 | 1.0 | `welcome-narrow-top.png/.xml`, `welcome-narrow-bottom.png/.xml` | no horizontal clipping; actions reachable by scroll |
| Welcome | light, 1080x2400 | 2.0 | `welcome-font2-top.png/.xml`, `welcome-font2-bottom.png/.xml` | meaningful text scales; no glyph/control clipping |
| Home | light, 1080x2400 | 1.0 | `home-light.png/.xml` | visible exact route words match trace geometry; live region named |
| Home | dark, 1080x2400 | 1.0 | `home-dark.png/.xml` | same truth; controls and metrics legible |
| Home | light, 720x1600 | 1.0 | `home-narrow.png/.xml` | no nested-card squeeze; Settings/filter/pair actions >=44dp |
| Home | light, 1080x2400 | 2.0 | `home-font2-top.png/.xml`, `home-font2-bottom.png/.xml` | switch never overlaps state/explanation; scroll reaches Recent/filter |
| Settings | light, 1080x2400 | 1.0 | `settings-light.png/.xml` | continuous ledger; truthful relay/direct-only branch |
| Settings | dark, 1080x2400 | 1.0 | `settings-dark.png/.xml` | same structure; no cream/card-grid residue |
| Settings | light, 720x1600 | 1.0 | `settings-narrow.png/.xml` | row copy wraps without colliding with trailing controls |
| Settings | light, 1080x2400 | 2.0 | `settings-font2-top.png/.xml`, `settings-font2-bottom.png/.xml` | all groups reachable; no clipped labels/controls |
| Handoff artifact | Jest geometry matrix | N/A | `handoff-six-state-geometry.json` | six unique signatures; two brackets and one ticket each |
| Palette | Jest contrast matrix | N/A | `handoff-contrast.json` | normal text >=4.5:1; UI graphics >=3:1 |

F3 first generates the two deterministic non-visual artifacts. These commands are exact; the tests themselves own JSON serialization so no ad-hoc parser can diverge from the asserted values:

```bash
mkdir -p .omo/evidence/handoff-trace-final
cd mobile
HANDOFF_GEOMETRY_EVIDENCE=../.omo/evidence/handoff-trace-final/handoff-six-state-geometry.json \
  npm test -- --runInBand components/__tests__/HandoffTrace.test.tsx -t "six state geometries"
HANDOFF_CONTRAST_EVIDENCE=../.omo/evidence/handoff-trace-final/handoff-contrast.json \
  npm test -- --runInBand components/__tests__/semanticContrast.test.ts components/__tests__/routeContrast.test.ts
cd ..
node -e 'const fs=require("fs"); const [gp,cp]=process.argv.slice(1); const g=JSON.parse(fs.readFileSync(gp,"utf8")); const c=JSON.parse(fs.readFileSync(cp,"utf8")); const order=["direct","relay","reconnecting","queued","paused","unpaired"]; if(g.schema!=="twinotify.handoff-geometry.v1") throw Error("bad geometry schema"); if(JSON.stringify(g.states.map(x=>x.state))!==JSON.stringify(order)) throw Error("bad state order"); if(new Set(g.states.map(x=>x.signature)).size!==6) throw Error("non-unique geometry"); if(g.assertions?.stateCount!==6||g.assertions?.uniqueSignatures!==true||g.assertions?.twoBracketsOneTicket!==true) throw Error("failed geometry assertion"); if(c.schema!=="twinotify.handoff-contrast.v1") throw Error("bad contrast schema"); if(JSON.stringify(c.modes.map(x=>x.mode))!==JSON.stringify(["light","dark"])) throw Error("bad mode order"); const checks=c.modes.flatMap(x=>x.checks); if(!checks.length||checks.some(x=>x.pass!==true||x.ratio<x.threshold)) throw Error("failed contrast row"); const kinds=new Set(checks.map(x=>`${x.kind}:${x.threshold}`)); if(!kinds.has("text:4.5")||!kinds.has("graphic:3")) throw Error("missing contrast threshold"); console.log(`EVIDENCE_JSON_CHECK PASS geometry=${g.states.length} contrast=${checks.length}`);' \
  .omo/evidence/handoff-trace-final/handoff-six-state-geometry.json \
  .omo/evidence/handoff-trace-final/handoff-contrast.json
```

Expected JSON result: the validation command prints `EVIDENCE_JSON_CHECK PASS geometry=6 contrast=<positive-count>`; geometry schema/order/assertions are exact, and every text/graphic contrast row passes its 4.5/3.0 threshold.

F3 then creates `.omo/evidence/run-handoff-ui-capture.sh` with the exact content below, marks it executable, and runs it from the repository root. This evidence-only script is not committed. It installs with `-r`, never clears/uninstalls data, captures all 16 named PNG/XML pairs, performs the exact top-to-bottom swipes, and registers its reset trap before the first device mutation.

```bash
#!/usr/bin/env bash
set -Eeuo pipefail

HANDOFF_ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
HANDOFF_SERIAL=emulator-5554
HANDOFF_OUT_DIR=${HANDOFF_EVIDENCE_DIR:-$HANDOFF_ROOT_DIR/.omo/evidence/handoff-trace-final}
HANDOFF_APK=$HANDOFF_ROOT_DIR/mobile/android/app/build/outputs/apk/release/app-release.apk

mkdir -p "$HANDOFF_OUT_DIR"

handoff_reset_device() {
  local handoff_status=$?
  trap - EXIT INT TERM
  adb -s "$HANDOFF_SERIAL" shell wm size reset >/dev/null 2>&1 || true
  adb -s "$HANDOFF_SERIAL" shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  adb -s "$HANDOFF_SERIAL" shell cmd uimode night no >/dev/null 2>&1 || true
  adb -s "$HANDOFF_SERIAL" shell am force-stop com.twinotify.app >/dev/null 2>&1 || true
  exit "$handoff_status"
}
trap handoff_reset_device EXIT INT TERM

adb -s "$HANDOFF_SERIAL" get-state | grep -qx device
adb -s "$HANDOFF_SERIAL" emu avd name >/dev/null
test -f "$HANDOFF_APK"
adb -s "$HANDOFF_SERIAL" install -r "$HANDOFF_APK"

HANDOFF_DENSITY=$(adb -s "$HANDOFF_SERIAL" shell wm density | tr -d '\r' | awk '/Override density:/{v=$3} /Physical density:/{if(v=="")v=$3} END{print v}')
test -n "$HANDOFF_DENSITY"

handoff_surface() {
  local night=$1 size=$2 font=$3
  adb -s "$HANDOFF_SERIAL" shell cmd uimode night "$night"
  adb -s "$HANDOFF_SERIAL" shell wm size "$size"
  adb -s "$HANDOFF_SERIAL" shell settings put system font_scale "$font"
}

handoff_launch() {
  local uri=$1
  adb -s "$HANDOFF_SERIAL" shell am force-stop com.twinotify.app
  adb -s "$HANDOFF_SERIAL" shell am start -W -a android.intent.action.VIEW -d "$uri" com.twinotify.app
  sleep 2
}

handoff_shot() {
  local name=$1 expected=$2 remote=/sdcard/handoff-ui.xml
  adb -s "$HANDOFF_SERIAL" exec-out screencap -p > "$HANDOFF_OUT_DIR/$name.png"
  adb -s "$HANDOFF_SERIAL" shell uiautomator dump "$remote"
  adb -s "$HANDOFF_SERIAL" pull "$remote" "$HANDOFF_OUT_DIR/$name.xml" >/dev/null
  adb -s "$HANDOFF_SERIAL" shell rm -f "$remote"
  grep -Fq "$expected" "$HANDOFF_OUT_DIR/$name.xml"
}

handoff_scroll_bottom() {
  local x1=$1 y1=$2 x2=$3 y2=$4 duration=$5 count=$6
  local i
  for ((i=0; i<count; i++)); do
    adb -s "$HANDOFF_SERIAL" shell input swipe "$x1" "$y1" "$x2" "$y2" "$duration"
    sleep 0.3
  done
  sleep 1
}

# Standard light: three complete top frames.
handoff_surface no 1080x2400 1.0
handoff_launch twinotify:///onboarding/welcome; handoff_shot welcome-light "Mirror selected notifications."
handoff_launch twinotify:///home; handoff_shot home-light "Mirror notifications"
handoff_launch twinotify:///settings; handoff_shot settings-light "Settings"

# Standard dark: three complete top frames.
handoff_surface yes 1080x2400 1.0
handoff_launch twinotify:///onboarding/welcome; handoff_shot welcome-dark "Mirror selected notifications."
handoff_launch twinotify:///home; handoff_shot home-dark "Mirror notifications"
handoff_launch twinotify:///settings; handoff_shot settings-dark "Settings"

# Narrow: Welcome top and bottom, then Home and Settings top frames.
handoff_surface no 720x1600 1.0
handoff_launch twinotify:///onboarding/welcome; handoff_shot welcome-narrow-top "Mirror selected notifications."
handoff_scroll_bottom 360 1400 360 260 500 4; handoff_shot welcome-narrow-bottom "Get started"
handoff_launch twinotify:///home; handoff_shot home-narrow "Mirror notifications"
handoff_launch twinotify:///settings; handoff_shot settings-narrow "Settings"

# 2x fonts: top and bottom for all three surfaces.
handoff_surface no 1080x2400 2.0
handoff_launch twinotify:///onboarding/welcome; handoff_shot welcome-font2-top "Mirror selected notifications."
handoff_scroll_bottom 540 2050 540 300 600 5; handoff_shot welcome-font2-bottom "Get started"
handoff_launch twinotify:///home; handoff_shot home-font2-top "Mirror notifications"
handoff_scroll_bottom 540 2050 540 300 600 5; handoff_shot home-font2-bottom "Recent"
handoff_launch twinotify:///settings; handoff_shot settings-font2-top "Settings"
handoff_scroll_bottom 540 2050 540 300 600 5; handoff_shot settings-font2-bottom "About"

HANDOFF_XML_FILES=("$HANDOFF_OUT_DIR"/*.xml)
test "${#HANDOFF_XML_FILES[@]}" -eq 16
test "$(find "$HANDOFF_OUT_DIR" -maxdepth 1 -type f -name '*.png' | wc -l | tr -d ' ')" -eq 16
node "$HANDOFF_ROOT_DIR/mobile/scripts/check-ui-xml.mjs" \
  --density "$HANDOFF_DENSITY" \
  --expect-files 16 \
  "${HANDOFF_XML_FILES[@]}" | tee "$HANDOFF_OUT_DIR/ui-xml-check.log"
```

Run it exactly:

```bash
chmod +x .omo/evidence/run-handoff-ui-capture.sh
bash .omo/evidence/run-handoff-ui-capture.sh
```

The XML checker must print exactly:

```text
UI_XML_CHECK PASS files=16 out_of_bounds=0 undersized=0 sibling_overlaps=0
```

Any nonzero build/install/capture/checker command triggers the `EXIT` trap. F3 is incomplete unless a final read-only state check also passes:

```bash
HANDOFF_SERIAL=emulator-5554
test "$(adb -s "$HANDOFF_SERIAL" shell settings get system font_scale | tr -d '\r')" = "1.0"
adb -s "$HANDOFF_SERIAL" shell wm size | tr -d '\r' | grep -q '^Physical size:'
adb -s "$HANDOFF_SERIAL" shell cmd uimode night | tr -d '\r' | grep -qi 'no\|not night\|day'
```

### Full anti-slop final checklist

F4 must mark every row PASS, N/A with rationale, or REJECT. A single REJECT blocks completion.

| Law items covered | Required final condition | Evidence |
|---|---|---|
| Lucide/default icon pack; missing or faked icons; no icons at all; redrawn generic line icons; stock CTA arrow | No icon pack, Unicode gear/chevrons, or generic arrow on the redesigned surfaces. Handoff brackets, ticket, and disclosure are authored for this product. No fake brand/logo is added. | source diff + all screenshots |
| Em dashes; excessive copy; fake specificity | No em dash in new UI copy. Existing approved copy remains terse and specific; no fake metrics/customers. | `rg` + screen tests |
| Pill/eyebrow badge; gradient pill; metadata chips everywhere; inner-glow badge | No decorative pills/chips/eyebrows/status capsules. Native switch shape is the only functional capsule. | screenshots + component tree |
| Fraunces/Work Sans; Space Grotesk; Cormorant; Sora/JetBrains; Syne; Archivo/Inter; Didone; default Google-font rotation; safe free-font swap | No web/downloaded font. Android-native sans/condensed/monospace only; no font gate. | package diff + foundation test |
| Monospace house voice; one label treatment everywhere; cramped display type | Mono only for data/IDs/codes; display spacing is measured; labels use role-specific weight/size. | screenshots + style inspection |
| Gradient-filled headline; two-tone/accent headline; signature serif default | No gradient/two-tone headline and no reflexive serif. The approved native condensed statement remains one ink color and earns character from scale/composition. | Welcome screenshot + style inspection |
| Purple/blue-purple gradients; cool blue-charcoal; pastel candy gradient; drifting blobs; saturated accent; colliding colors; hard color seams | Fixed mineral/verdigris literals only; no gradient; verdigris is tonal, not sprayed across every element. Each screen stays on one continuous mode surface without an accidental section seam. | contrast JSON + screenshots |
| Cream/beige editorial base; slop gray neutral | Neither cream nor UI-kit gray appears. Surfaces use the green-mineral token table. | exact token test + screenshots |
| Background glow; cut-off glow; radial halo; bloom copy; botched glass; liquid-glass misuse | No glow, halo, blur, refraction, dispersion, or glass. The old concentric rings are deleted. | rendered-tree test + Welcome screenshots |
| Default all-around shadow; hard-edged fake shadow; bloom-shaped shadow | Default components have no shadow. Any functional elevation must be tight, directional, and explicitly justified; current plan expects none. | primitive tests + screenshots |
| Hairline light borders on boxes; default faint rounded cards; accent-bar card; kitchen-sink card | Home/Settings are continuous surfaces. Cards are rare tonal planes, not repeated outlined containers; no accent stripe. | Home/Settings screenshots |
| Glowy pill buttons; default CTA pair; filled-next-to-outlined pair; hover boop; underline-fill animation | One primary + one bare action on Welcome; no outlined pair; controls stay stationary; pressed state is tonal only. | primitive tests + Welcome screenshot |
| Floating cards; card hover lift; fake app/code windows; floating image tags | None exist. Product truth is shown directly, not through mock windows. | screenshots |
| Pricing tiers; testimonial/decorative-quote cards; initials avatars; pre-footer CTA; countdown; logo walls | N/A to this Android utility; none may be introduced. | scope diff |
| Logo lockup with icon tile; gradient initials; letterspaced/oversized footer wordmark | Wordmark is one system-colored text mark with no icon box, split accent, footer treatment, or gradient. | TwWordmark test + screenshots |
| Split hero; hero stack with right panel; default centered hero stack; multi-line dangling accent word | Welcome is left-aligned around the trace artifact, no right card, no accent word, and no centered stack. | Welcome matrix |
| Hero does not own first screen; stray next section | Welcome's first viewport is deliberately composed; on short/2x layouts scrolling is intentional and no unrelated half-section peeks in. | narrow/2x screenshots |
| Grid/graph-paper background; faint grid; fixed scrolling background; crude CSS/SVG illustration | No grid/fixed field. The SVG is a product-specific state diagram with exact semantic geometry, not filler. | source + screenshots |
| Grain on content; premium noise misuse; banded gradients | No grain or gradient. Text/icons stay crisp. | rendered-tree/source scan |
| Invisible-content entrance trap; dead-looking screen; motion without reduced-motion | Content is visible on first render. Only reconnecting ticket translates <=8dp; reduced motion freezes it. Stable states remain intentionally still. | HandoffTrace tests + reduced-motion test |
| Botched fill animation; scale motion | No fill/scale animation. No cap-changing progress effect. | source scan + primitive test |
| Content sliced by edge; clipped overlap; hard image seams | No images/overlap seams. All text/control/artifact bounds remain inside XML viewport at standard/narrow/2x. | PNG/XML matrix |
| Text jammed against edge; content flung to far edges | Responsive 16/20/24dp gutters and shared axes; no marooned edge clusters. | PNG/XML matrix |
| Nothing actually centered | Ticket, brackets, switch thumb, labels, and actions are mathematically/optically checked in geometry/unit tests and zoomed screenshots. | geometry test + reviewer zoom |
| Misaligned parallel columns; ragged comparison grid | Home metric labels/values share baselines and equal slots; Settings trailing controls align while text rows grow independently. | Home/Settings screenshots + XML bounds |
| Text unreadable/no contrast | WCAG matrix passes in light/dark: every semantic foreground against semantic surface/base surfaces and every normal text pair is >=4.5:1; route/control/boundary graphics are >=3:1. Disabled `ink4` is never used for body/interactive labels. | numeric contrast JSON + exact-token tests |
| Decorative rule/eyebrow tick; unrounded hairline rules; dot under active nav | No decorative lines or nav dots. Trace lines carry delivery meaning and use rounded caps. | source + screenshots |
| Off-center strike/cut line | N/A; no strike-through/redaction/cut line may be introduced. The paused gate is route geometry, not a line through text. | source + trace geometry |
| Default section heads: kicker-plus-H2; small-label-over-heading; big serif statement | No kicker/serif template. Settings group labels are functional information architecture, not decorative tracked caps. | screenshots |
| Numbered steps on vertical rail; inset form island; email-pill form; image-caption cards | N/A and absent. | scope review |
| Flat fill after hero; whole SaaS page template; stacked slop layouts; recycled house style | N/A as a three-screen app, but all screens share the product-specific trace language rather than generic marketing blocks. | all-screen montage |
| Standard footer; oversized footer wordmark placement | N/A; no footer introduced. | scope review |
| Sun/moon toggle | No theme toggle. System light/dark mode only. | Theme source + Settings screenshot |
| Dead controls/fake interactivity | Every visible action has an asserted route/native call; decorative trace is not focusable. | screen tests + UI XML |
| Product as empty generic artifact; crude illustration; avoiding the list without a signature | Handoff Trace is a carefully bounded custom SVG state diagram, populated with actual route state and one notification ticket rather than placeholder geometry. | six-state tests + Home screenshots |
| Atmosphere/layered depth/signature artifact/bespoke silhouette/treated nav/real specifics | Mineral surface + trace field + ticket create foreground/midground/background; ticket notch and endpoint brackets are bespoke; Home nav is a deliberate text action; metrics/peer/route are real. | montage + UI review |
| Real translucency/self-colored borders/considered light/premium glass recipes | N/A by deliberate restraint; no imitation glass/light is allowed. Depth comes from tone/space. | token/source audit |
| Authored micro-interactions/scroll-authored motion | Reconnecting motion is authored and truthful; no decorative entrance/scroll reveal. Scroll remains functional for accessibility. | motion tests + reviewer report |
| Full-bleed atmospheric hero; animated character field; gradient-filled icon jewel; premium grid/grain | N/A by product fit. The stateful Handoff Trace, not an imported atmospheric technique, is the signature. | scope/UI review |
| Inset island sections; blueprint/canvas background; oversized footer wordmark | N/A and absent; none is needed for this Android utility. | all-screen montage |
| Premium type/licensed type guidance | User constraint selects native Android type and prohibits paid/network fonts. Composition and the condensed system face carry identity without pretending a stock web font is licensed. | package/type audit |
| Component-library guidance/de-slopping prebuilt pieces | No new library is added. Existing accessible primitives are retained behaviorally and art-directed; no generic prebuilt marketing block is introduced. | dependency diff + primitive tests |
| Reference-language-not-content; creative-not-realistic; cohesion field notes | No reference content or fake realism. One mineral/verdigris world, one trace artifact, and one type system cover every screen. | final montage + UI review |
| Full-page composition/cohesive visual language/professional heartbeat | Welcome, Home, and Settings share palette, type, endpoint brackets, ticket, disclosure, spacing, and stationary control language. | final all-screen montage |
| Real logo walls/brand assets | N/A; no unsupported social proof or network asset. | scope audit |

## Commit strategy
- One logical change per commit. Conventional Commits (`<type>(<scope>): <subject>` body + footer).
- Atomic: every commit builds and passes tests on its own.
- No "WIP" / "fix typo squash later" commits on the final branch - clean up before merge.
- Reference the promoted plan file path in the final commit footer: `Plan: docs/superpowers/plans/2026-08-27-handoff-trace-redesign.md`.
- Do not push. Do not commit `.omo/evidence/**` artifacts.

## Success criteria
- All Must-Have shipped; all QA scenarios pass with captured evidence; F1-F4 approved; commit history clean.
- The final diff contains only the UI/package/test files authorized by Tasks 1-6 and no functional LAN/pairing/unpair/E2E/security edits.
- All six route states have unique tested geometry and retain exact product truth.
- Welcome, Home, and Settings pass light, dark, narrow, and 2x-font screenshot/XML inspection with no clipping, overlap, inaccessible control, dead action, or anti-slop rejection.
- The implementation uses no Google font, font-loading gate, cream/card-stack UI, Unicode navigation glyph, scale motion, gradient, glow, pill decoration, or paid/network asset.
