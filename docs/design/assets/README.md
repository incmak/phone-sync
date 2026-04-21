# Twinotify Design Assets

Design bundle delivered by the design AI for Tier-1 Android flow (onboarding, pairing, home, settings, filter).

## Structure

- `tier-1-design-bundle/README.md` — original handoff note from the design AI.
- `tier-1-design-bundle/chats/chat1.md` — conversation transcript (scope + direction decisions).
- `tier-1-design-bundle/project/tokens.jsx` — design tokens: colors (oklch), fonts (Inter + JetBrains Mono), spacing, radii, shadows, typography scale.
- `tier-1-design-bundle/project/primitives.jsx` — UI atoms: `TwLogo`, `TwWordmark`, `TwStatusDot`, `TwDeviceChip`, `TwAppChip`, `TwFingerprint`, `TwQR`, `TwButton`, `TwCard`, `TwSwitch`, `TwRow`, `TwBanner`, `TwEmpty`, `TwIcon`, `TwSpinner`, `TwStatusBar`, `TwNavBar`.
- `tier-1-design-bundle/project/screens.jsx` — 18 screens: ScreenWelcome, ScreenHow, ScreenRole, ScreenRelay, ScreenPerms, ScreenOEM, ScreenReady, ScreenPairQR, ScreenPairScan, ScreenPairFP, ScreenPairSuccess, ScreenPairFail, ScreenHome, ScreenSettings, ScreenSetPair, ScreenFilter (plus helpers `Screen`, `ScreenHeader`).
- `tier-1-design-bundle/project/design-canvas.jsx` — the interactive prototype canvas (tweaks panel, theme toggle, hue picker, role + connection state).
- `tier-1-design-bundle/project/Twinotify.html` — standalone renderable prototype.

## Direction locked

- **Aesthetic:** calm + technical. Warm near-white / deep ink neutrals, single mint-teal accent (oklch 0.72 0.12 180). Inter for UI, JetBrains Mono for fingerprints/codes.
- **Light + dark parity.**
- **Accent hue tweakable** (mint / indigo / amber / rose).
- **Wordmark:** mirror-motif monogram — doubled dots, two interlocked rings, or mirrored T (3 variants; primary is the interlocked rings).
- **No device frame**, screens only.

## Implementation reference

Phase 3 plan at `docs/superpowers/plans/2026-04-21-phase-3-listener-first-mirror.md` ports these to React Native:

- `tokens.jsx` → `mobile/components/tokens.ts`
- `primitives.jsx` → `mobile/components/primitives/*.tsx`
- `screens.jsx` → `mobile/app/onboarding/*.tsx`, `mobile/app/pair/*.tsx`, `mobile/app/home.tsx`, `mobile/app/settings/*.tsx`, `mobile/app/filter.tsx`

Key RN adaptation notes are in the Phase 3 plan "Design-to-RN adaptation notes" section.
