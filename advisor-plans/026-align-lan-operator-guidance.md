# Plan 026: Align LAN operator guidance with the executable harness

**Status:** DONE

## Problem

`docs/test-scenarios.md` correctly lists the automated
`lan-relay-fallback-return` and `lan-restart-persistence` children, but its later
operator section still claims a direct-route-only control is missing and treats
restart as unimplemented host work. The source harness already implements both.
Physical two-phone execution is still pending and must remain pending.

## Tasks

1. Add a fail-closed docs-verifier mutation proving the stale capability claim
   is currently accepted.
2. Make `docs/test-scenarios.md` distinguish implemented automation from pending
   physical acceptance, with no invented pass.
3. Extend the executable docs contract and self-test so the contradiction cannot
   return.
4. Run docs/self-test, host verification, diff check, and independent review.

## Acceptance

- The document names fallback/return and two-sided restart as implemented
  aggregate children.
- It still marks the actual two-phone run and no-uplink observation pending.
- The docs verifier rejects the obsolete “control is needed” claim.
- Host verification remains green.
