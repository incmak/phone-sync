# Two-device E2E harness

The harness drives authenticated, debug-only product controls and observes only
content-free route, custody, receipt, queue, sequence, and materialization
state. It rejects phone, contact, notification content, network identifiers,
credentials, and raw protocol material.

## Complete direct-LAN product gate

`lan-product-correctness` runs eleven children in this exact order:
`lan-direct-delivery`, `lan-direct-reverse-delivery`, `lan-direct-dismiss`,
`lan-direct-update`, `lan-direct-peer-dismiss`, `lan-direct-call-state`,
`lan-direct-snapshot-receipt`, `lan-relay-fallback-return`,
`lan-restart-persistence`, `lan-direct-burst-backpressure`, and
`lan-direct-unpair-during-traffic`. It fails fast and retains four independent
artifacts for every completed child plus the failed child. The burst defaults
to 256 and is bounded to 2 through 1,000, below the production caps of 2,000
rows and 128 MiB.

Reverse delivery posts on B and requires exact materialization on A plus B-side
LAN custody and receipt evidence. Fallback uses the app-internal LAN
availability control on both phones, never an OS radio change, and separately
records the relay-carried and returned-LAN deliveries. Restart persistence uses
a bounded package force-stop followed by the typed launcher on A and B. It does
not clear package data.

From the repository root, use two explicit, distinct, already-paired hardware
serials and a private evidence directory:

```sh
E2E_DEVICE_A='<serial-a>' \
E2E_DEVICE_B='<serial-b>' \
E2E_LAN_PRODUCT_EVIDENCE_DIR='/private/path/lan-product' \
make e2e-lan-product
```

The Make target invokes `e2e/cmd/twinotify-e2e` and then
`scripts/verify-lan-product-evidence.sh`. It never clears package data, changes
Wi-Fi or mobile-data state, or pairs devices. Unpair runs last, so any later
re-pair is a manual operator action. Evidence lands at the exact directory in
`E2E_LAN_PRODUCT_EVIDENCE_DIR`, with four JSON artifacts at the root and under
each `children/NN-<scenario>/` directory.

The predecessor automation is implemented and host-tested through commit
`9c136cc`; this current change expands the host gate to eleven children. The
actual aggregate hardware execution is still pending physical two-phone run;
host fixtures are not physical acceptance.

## Real call release evidence

Call-sync acceptance uses the protected physical evidence pipeline, not the
obsolete synthetic `call-state` scenario. Follow `PHY-CALL-01` in
`docs/test-scenarios.md`, assemble the private release evidence directory, then
run the fail-closed release audit:

```sh
make release-audit RELEASE_EVIDENCE_DIR=/private/path/twinotify-release
```

The verifier requires an explicit `PHY-CALL-01: pass`. Host fixtures and the
debug call-state injection path are regression aids only and cannot satisfy it.
