# PB-003 — Private notification history implementation plan

1. [x] Add failing unit and instrumented tests for bounded protected content, Room v10 migration, transactional clear/disable, and bridge redaction.
2. [x] Add the v10 entities, migration, DAO transactions, retention policy, and Keystore-backed content repository.
3. [x] Record best-effort content at outbound capture and inbound materialization success boundaries.
4. [x] Extend the native/TypeScript bridge and add the History screen with time/app grouping and clear/retention controls.
5. [x] Run native and TypeScript tests, lint/typecheck/build gates, then exercise the screen and persistence controls on `emulator-5558`.
6. [x] Complete the anti-slop visual/interaction checklist and record exact evidence here before marking PB-003 done.

## Evidence

- TDD red phase observed before implementation: the focused JVM test failed on the missing `HistoryContentCodec`, and the focused Jest test failed on the missing history presentation module.
- `npm test -- --runInBand`: 33 suites and 222 tests passed.
- `npm run typecheck` and `npm run lint`: passed.
- `./gradlew :twinotify-core:testDebugUnitTest`: passed.
- `./gradlew :twinotify-core:assembleDebugAndroidTest`: passed.
- `emulator-5558`: 12 focused instrumented tests passed, covering Room migrations through v10, age/row/byte retention, transactional clear/disable isolation, and real Android Keystore ciphertext-at-rest round-trip.
- A locally bundled release APK was built and installed without a Metro server. The History route rendered successfully and its content-deletion confirmation was exercised without accepting the destructive action.

## Anti-slop visual and interaction review

- Hierarchy and restraint: one page, one segmented grouping control, no stacked cards, no gradients, no decorative badges, and no duplicate headings.
- Layout: the empty state and privacy controls have stable alignment and deliberate spacing; no text or controls clipped at the default scale or at 130% text size.
- Theme: inspected in both light and dark modes; semantic foreground, muted text, destructive action, selected segment, and switch states remained legible.
- Interaction: back, clear-all, grouping, content retention, and 7/30-day controls expose descriptive accessibility labels and at least 48dp touch targets.
- Safety: destructive content disable presents a specific confirmation; per-app/all-history clearing is scoped transactionally to history tables and does not touch delivery state or receipts.
- States: automated component coverage exercises loading, error, empty, time-grouped, and app-grouped presentations; the bundled empty state was inspected on the emulator.
- Privacy: rendered rows receive only bounded decrypted title/preview data; native tests confirm the database does not contain those strings in plaintext.
