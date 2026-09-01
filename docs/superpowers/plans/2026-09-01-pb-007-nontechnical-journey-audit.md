# PB-007 - Non-technical journey audit implementation plan

1. Capture and inspect the emulator-reachable first-run, permission, nearby-pairing, unpaired Home, Settings, History, privacy-control, light/dark, and 160% text states.
2. Add failing tests for nearby-mode permission gating, relay-mode exclusion, denial recovery, accessible grant controls, and corrected first-run/reliability copy.
3. Add typed native `NEARBY_WIFI_DEVICES` check/request functions using the module's existing Expo permission pattern.
4. Extend the existing permission screen only when nearby mode is selected, retaining current visual primitives and refresh behavior.
5. Correct the audited selection/privacy/battery copy without changing layout or navigation.
6. Run focused then full JavaScript, TypeScript, lint, Kotlin, Android lint/assembly, and emulator checks; recapture the corrected steps and complete the audit report.
7. Update `docs/product-backlog.md`, leaving every physical or owner-controlled acceptance row explicitly pending.

## Evidence

- Focused Jest journey suite: 6/6 passed after five assertions failed before implementation.
- Kotlin manifest contract regression: failed while `ACCESS_LOCAL_NETWORK` remained declared, then passed after the target-36 correction.
- `emulator-5558` showed the real Android **Nearby devices** prompt, enabled Continue after the grant, and reached `Nearby pairing QR code` plus `Waiting for the other phone to scan…` on a clean install.
- Focused Wi-Fi/NSD instrumentation test passed on `emulator-5558`: `OfflinePairingLoopbackTest#androidNsdAdapterRegistersAndUnregistersOnSuppliedWifiNetwork` (1/1).
- Light-mode and dark/160%-text screenshots are recorded in [`docs/audits/pb-007/`](../../audits/pb-007/README.md).
- Full JavaScript gate: 35 Jest suites / 233 tests passed; TypeScript and Expo lint passed.
- Full Android gate passed: `:twinotify-core:testDebugUnitTest`, `:twinotify-core:lintDebug`, `:twinotify-core:assembleDebugAndroidTest`, and `:app:assembleRelease` (816 tasks).
