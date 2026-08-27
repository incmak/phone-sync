# Plan 027: Keep Handoff Trace screen tests warning-free

**Status:** DONE

## Problem

The Home screen route-copy test renders the reconnecting Handoff Trace while it
awaits unrelated asynchronous status data. The real requestAnimationFrame loop
updates React state outside `act`, so every host gate prints a React lifecycle
warning even though the test passes. Geometry, motion bounds, and reduced-motion
behavior already have dedicated component tests.

## Tasks

1. Add a warning-sensitive Home regression and observe it fail with the current
   reconnecting render.
2. Run the Home screen suite under an explicit reduced-motion preference so its
   route, action, layout, and accessibility assertions do not start animation.
3. Preserve the dedicated non-reduced motion math tests and production behavior.
4. Run focused/full Jest, typecheck, lint, host verification, and review.

## Acceptance

- The focused reconnecting Home test emits no React `act` warning.
- All Handoff Trace geometry and motion tests remain green.
- Production UI and runtime motion code are unchanged.
- The exact host gate is green without the prior console warning.

## Result

The Home screen suite now runs with the platform's reduced-motion preference
enabled through a file-scoped Reanimated mock. A warning-sensitive regression
proved the original reconnecting animation emitted React lifecycle warnings and
now stays clean. Production Home and Handoff Trace code are unchanged.

Verification on 2026-08-27:

- Focused Home + Handoff Trace Jest: 24/24.
- Full mobile Jest: 21 suites, 157/157.
- TypeScript: clean.
- Lint: 0 errors; 4 pre-existing warnings outside this plan.
- `make host-verify`: exit 0 through generated-clean, without the former
  Handoff Trace `act` warning.
- Independent review: CLEAR / APPROVE with no blockers.

Evidence: `.omo/evidence/plan-027/report.md` and
`.omo/evidence/plan-027/independent-review.md`.
