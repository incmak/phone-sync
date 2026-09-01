# PB-009 - Truthful delivery metrics implementation plan

1. Add failing pure timestamp/day-window tests, Room receipt/deduplication/rollover tests, migration coverage, and nullable-latency UI tests.
2. Add a bounded metadata-only verified-delivery ledger in Room version 11 with an explicit 10-to-11 migration and unpair cleanup.
3. Extend the receipt transaction to write the ledger only for first-time authenticated `applied` notification receipts and classify unusable clock evidence.
4. Replace queue-time and inbound-payload metrics with Room-derived local-day count and last-ten valid latency, retaining the DataStore blocked counter on the same local-day semantics.
5. Carry nullable latency through the native bridge and Home metrics without changing the existing visual composition.
6. Run focused and full JVM/Room suites, migration validation, TypeScript/Jest/lint, Android lint/assembly, and emulator checks; record physical two-phone reconciliation as pending.

## Evidence

- Red first: the Home metric test rendered `No data` for a measured `0 ms`, and the new Kotlin metric tests did not compile before the local-day/evidence model and Room API existed.
- `npm test -- --runInBand`: 34 suites / 227 tests passed.
- `npm run typecheck`: passed.
- `npm run lint`: passed.
- `./gradlew :twinotify-core:testDebugUnitTest :twinotify-core:lintDebug :twinotify-core:assembleDebugAndroidTest :app:assembleRelease`: 816 tasks, `BUILD SUCCESSFUL`.
- `emulator-5558`: `ReliableDeliveryTransactionTest` plus `ReliableDeliveryMigrationTest`, 64 tests, `OK`; this includes first-receipt deduplication, exclusion rules, local-day bounds, last-ten average, and migration 10-to-11.
- The temporary `co.twinotify.core.test` package was removed and the rebuilt `com.twinotify.app` release APK restored on `emulator-5558`.
- Anti-slop interface review: no new container, typography, color, spacing, control, animation, or navigation pattern was introduced. The existing metric composition and responsive wrapping remain intact; only the truthful semantic distinction between absent latency (`No data`) and a measured zero (`0 ms`) changed, with explicit accessibility labels for both states.
- Pending physical evidence: reconcile count and latency against authenticated notification receipts across two real phones, including clock skew and local-day rollover behavior.
