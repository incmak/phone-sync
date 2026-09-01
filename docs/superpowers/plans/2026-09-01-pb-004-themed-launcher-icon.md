# PB-004 — Themed launcher icon implementation plan

1. [x] Add failing source-check tests for the currently fully opaque configured monochrome PNG and for missing, fully transparent, and fully opaque fixtures.
2. [x] Implement a dependency-free PNG alpha checker and wire it into the mobile verification scripts.
3. [x] Convert only the canonical monochrome raster background to transparency while preserving geometry and pixel bounds.
4. [x] Run focused/full mobile tests, typecheck, lint, and the launcher-asset check.
5. [x] Run a clean Expo prebuild, inspect generated normal/round adaptive XML and density assets, then assemble a bundled APK.
6. [x] Install and inspect launcher presentation on `emulator-5558`; record source/emulator evidence and keep POCO F1/MI 11X captures pending.

## Evidence

- TDD red phase: the focused Jest suite first failed because the checker did not exist; after the checker was added, the production-asset case alone remained red with `monochromeImage is fully opaque` while all negative cases passed.
- `npm run check:launcher-assets`: passed with `size=1024x1024`, `visible=65954`, `transparent=982622`, and centered bounds `281,317,742,705`.
- Negative tests reject omitted, fully transparent, and fully opaque monochrome layers.
- `npx expo prebuild --clean --platform android`: passed. Both generated `ic_launcher.xml` and `ic_launcher_round.xml` contain `<monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>`.
- All five generated density layers contain transparent and visible pixels; mdpi through xxxhdpi preserve proportional centered bounds.
- Normal icon sources were unchanged: SHA-256 remained `e7113d…` for `icon.png`, `43f9a1…` for `adaptive-foreground.png`, and `56e830…` for `splash.png`.
- `npm test -- --runInBand`: 34 suites and 226 tests passed. `npm run typecheck` and `npm run lint` passed.
- `NODE_ENV=production ./gradlew :app:assembleRelease`: passed after a clean Android prebuild and produced a bundled APK.
- `emulator-5558`: the installed APK showed the unchanged normal launcher icon and a clearly visible, centered Seam silhouette in the launcher's Minimal/themed style in both light and dark modes. Temporary launcher shortcuts, theme mode, and text scale were restored afterward.

## Anti-slop visual review

- Identity: no geometry, color, background, shape, or optical-size redesign was introduced; only the monochrome layer's erroneous white background became transparent.
- Hierarchy and legibility: the Seam silhouette remains recognizable at launcher size and has contrast in both light and dark themed palettes.
- Alignment: source and generated density bounds are centered, remain inside the adaptive safe zone, and visually align with neighboring themed icons.
- States: normal, light-themed, and dark-themed launcher presentations were inspected on the emulator; no interaction surface changed.

## Deferred physical evidence

POCO F1 and MI 11X normal/dark/themed captures remain pending a physical two-phone run. Repository rules prohibit marking that observation complete from emulator evidence alone.
