# Twinotify Marketing Website Plan

**Date:** 2026-08-30
**Status:** Complete

## Goal

Create a responsive, deployment-ready static marketing website for Twinotify that explains the Android-to-Android notification mirroring product, demonstrates it through faithful rendered product screens, teaches setup, and routes visitors to truthful download and source destinations.

## Scope

- Add a self-contained site under `website/`.
- Reuse Twinotify's approved Seam identity and fixed mineral-green palette.
- Render representative Home, pairing, app-filter, and mirrored-notification screens as HTML/CSS product artifacts.
- Explain direct Wi-Fi delivery, relay fallback, end-to-end encryption, synchronized dismissal, app filtering, and notification actions.
- Include a three-step setup sequence and GitHub releases/source links.
- Add a lightweight static verification script and local-preview instructions.

## Non-goals

- Do not change mobile, relay, protocol, deployment, or release behavior.
- Do not claim a Play Store listing or a public release artifact that the repository does not currently prove exists.
- Do not add analytics, forms, third-party scripts, dependencies, or a framework.
- Do not publish or deploy the site.

## Visual system

- **Palette:** Night seam `#0C1713`, raised seam `#14241D`, mineral mist `#E3E9E5`, paper white `#F7FAF8`, route sage `#9BBEAE`, quiet ink `#34423B`.
- **Typography:** a condensed system display stack for decisive short headlines, paired with the native UI sans stack used by the Android product.
- **Layout:** the first viewport is a single composed scene. A live handoff trace crosses two detailed phone renders beneath a wide headline. Later content follows that same route, alternating product artifacts and terse explanations rather than stacking generic feature cards.
- **Signature:** an animated-but-never-hidden Seam route carrying a notification ticket between two fully populated phone screens.

## Numbered implementation tasks

1. Add a failing static contract for required content, landmarks, assets, links, and accessibility hooks.
2. Build the semantic HTML and product screen renders.
3. Build responsive styling, the Seam atmosphere, and reduced-motion-safe interaction.
4. Add local preview documentation.
5. Run the static contract, inspect desktop and mobile browser captures, click every real control, and complete the full anti-slop review.

## Acceptance criteria

- `node website/verify-site.mjs` passes.
- `python3 -m http.server 4173 --bind 127.0.0.1` from `website/` serves the site without exposing the repository tree or missing local assets.
- Desktop and mobile layouts contain no horizontal page overflow, clipped text, or inaccessible controls.
- Navigation, download, source, and instruction links work by keyboard and pointer.
- Motion never hides content and respects `prefers-reduced-motion`.
- Product and security copy remains consistent with repository evidence.
- Public-release availability is described truthfully.
