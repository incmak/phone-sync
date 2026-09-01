# PB-004 — Themed launcher icon regression design

Status: approved by the owner's 2026-09-01 instruction to complete all locally unblocked backlog work.

## Goal

Restore a visible, optically centered Twinotify Seam mark when Android applies themed launcher icons, without redesigning or changing the normal launcher identity.

## Root cause

`adaptive-monochrome.png` has an alpha value of 255 for every pixel. Android treats the monochrome layer as an alpha mask, so a themed launcher colors the entire adaptive-icon canvas instead of the Seam silhouette. The canonical SVG already has no background element; its previous raster export flattened transparency to white.

## Scope

- Replace only the monochrome PNG with the same black Seam geometry on a genuinely transparent 1024×1024 canvas.
- Preserve the current non-transparent pixel bounds and adaptive safe-zone placement.
- Add a portable Node source check that decodes the configured PNG and fails if the layer is omitted, fully transparent, fully opaque, malformed, outside its safe zone, or wired to the wrong asset.
- Clean-prebuild Android and assert both generated launcher XML files retain a `<monochrome>` layer referencing generated monochrome resources.
- Install a bundled app on `emulator-5558` and inspect the launcher icon in supported emulator modes.

## Non-goals

- No new logo, palette, launcher background, normal adaptive foreground, legacy icon, round icon, splash asset, or notification icon.
- No claims about POCO F1 or MI 11X launcher rendering until the owner is available for the required physical captures.
- No dependence on an external image-generation service or a new image-processing dependency; this is a deterministic alpha correction to an existing canonical vector export.

## Acceptance criteria

1. The configured monochrome PNG contains both transparent and visible pixels, retains the existing Seam bounds, and stays inside the adaptive safe zone.
2. Automated negative tests reject missing, fully transparent, and fully opaque monochrome layers.
3. A clean Expo prebuild emits `<monochrome>` in normal and round adaptive-icon XML.
4. Normal icon source assets remain byte-for-byte unchanged.
5. The bundled app installs and its launcher presentation is inspected on `emulator-5558`; physical OEM themed-icon captures remain explicitly pending.
