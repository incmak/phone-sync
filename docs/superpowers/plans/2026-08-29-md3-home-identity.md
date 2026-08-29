# Twinotify Material 3 Home and Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Twinotify's placeholder home and template branding with an MD3/Monet live control center, privacy-bounded recent activity, and a complete custom launcher and Android notification icon system.

**Architecture:** Keep React Native as the screen and component layer, extend the existing theme provider with all-or-nothing Material role schemes, and expose a narrow native recent-activity API. Store UI history in a dedicated Room table so presentation reads cannot affect protocol custody transitions. Keep the existing route presenter as the single user-facing route truth and keep Android small icons as native resources.

**Tech Stack:** Expo SDK 57, React Native 0.86, Expo Router, TypeScript, React Native SVG, React Native Reanimated, Kotlin, Expo Modules, Room 2.7.1, Android API 34+, Jest, JUnit, AndroidX migration tests.

## Global Constraints

- Work directly in the primary checkout; do not create or use a Git worktree.
- Follow TDD: observe each focused test fail before implementation.
- Preserve `presentRoute` as the single route-to-copy mapping and preserve all six route states.
- Recent activity is local-only and must never store or return notification title, body, expanded text, contact/conversation text, ciphertext, nonce, device ID, or canonical ID.
- The UI activity table retains at most 500 rows and 30 days and is cleared by unpair.
- Room advances from version 7 to 8 with `MIGRATION_7_8`; never use destructive migration.
- Monet resolution is all-or-nothing; never mix dynamic and fallback roles.
- The fixed fallback visual system is the approved mineral-green Seam palette.
- Every interactive target is at least 48 by 48 dp.
- Content remains visible when motion does not run, and route movement respects reduced motion.
- Use the custom Seam mark for identity and route artifacts; use Material Symbols only for conventional Android actions.
- Keep the current notification/call privacy behavior and do not add call actions.
- Do not expose raw notification/device data in tests, screenshots, logs, or final reporting.
- Every task ends in a conventional, scoped commit.

## File Structure

### New files

- `mobile/components/primitives/TwinotifyMark.tsx`: reusable Seam vector mark.
- `mobile/components/home/HomeTopAppBar.tsx`: app bar and settings action.
- `mobile/components/home/ConnectionSurface.tsx`: route, switch, trace, peer, and recovery action.
- `mobile/components/home/HomeMetrics.tsx`: responsive aligned metrics.
- `mobile/components/home/RecentActivitySection.tsx`: activity loading/error/empty/populated UI.
- `mobile/components/home/HomeFilterAction.tsx`: Material list navigation action.
- `mobile/components/home/recentActivityPresentation.ts`: privacy-safe row copy and relative time.
- `mobile/components/home/__tests__/*.test.tsx`: focused home component tests.
- `mobile/hooks/useRecentActivity.ts`: focus-aware bounded native refresh.
- `mobile/hooks/__tests__/useRecentActivity.test.ts`: hook behavior tests.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/UiActivityJournal.kt`: isolated UI history API and mappings.
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/storage/UiActivityJournalTest.kt`: cap, privacy, and idempotency tests.
- `mobile/modules/twinotify-core/android/src/main/res/drawable/ic_stat_twinotify.xml`: production small notification icon.
- `mobile/plugins/with-twinotify-brand-assets.js`: deterministic Expo asset configuration helper only if `app.json` cannot express a required role.
- `mobile/plugins/__tests__/with-twinotify-brand-assets.test.js`: config-plugin contract when the plugin is needed.
- `mobile/assets/brand/twinotify-mark.svg`: canonical source artwork.
- `mobile/assets/brand/icon.png`: legacy launcher icon.
- `mobile/assets/brand/adaptive-foreground.png`: adaptive foreground.
- `mobile/assets/brand/adaptive-monochrome.png`: themed-icon layer.
- `mobile/assets/brand/splash.png`: splash artwork.

### Modified files

- `mobile/components/tokens.ts`: MD3 semantic roles and fixed Seam schemes.
- `mobile/components/Theme.tsx`: atomic Monet/fallback scheme selection.
- `mobile/components/primitives/TwWordmark.tsx`: compose `TwinotifyMark` and wordmark.
- `mobile/components/primitives/TwSwitch.tsx`: MD3 geometry and role/state mapping.
- `mobile/components/primitives/TwButton.tsx`: Material role and state-layer mapping.
- `mobile/components/index.ts`: export new identity and home components.
- `mobile/components/HandoffTrace.tsx`: approved Seam geometry/color behavior.
- `mobile/app/home.tsx`: orchestration-only live control center.
- `mobile/app.json`: launcher, adaptive, monochrome, splash assets.
- `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`: recent-activity types and method.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`: `UiActivityEvent` entity.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`: bounded UI activity queries and updates.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`: migration, entity registration, version 8.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt`: atomic outgoing activity creation.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`: terminal activity update seam.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt`: inbound applied activity.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorDismisser.kt`: committed dismissal activity.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/UnpairOps.kt`: explicit UI history clearing assertion path.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`: bounded native read and artwork resolution.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt`: branded small icon and source artwork fallback.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`: branded foreground small icon.
- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateMaterializer.kt`: branded call small icon.
- `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt`: 7-to-8 migration proof.
- Existing Jest/JUnit builder and lifecycle tests adjacent to each modified production path.

---

### Task 1: Material 3 Role Scheme and Monet Resolution

**Files:**
- Modify: `mobile/components/tokens.ts`
- Modify: `mobile/components/Theme.tsx`
- Create: `mobile/components/__tests__/materialTheme.test.tsx`
- Modify: `mobile/components/primitives/TwSwitch.tsx`
- Modify: `mobile/components/primitives/TwButton.tsx`
- Modify: `mobile/components/primitives/__tests__/TwSwitch.test.tsx`
- Modify: `mobile/components/primitives/__tests__/TwButton.test.tsx`

**Interfaces:**
- Produces: `MaterialColorScheme`, `fixedSeamScheme(dark: boolean)`, `resolveMaterialScheme(input)`, and `Theme.colors` with MD3 role names.
- Consumes: React Native `PlatformColor`, `useColorScheme`, and the current `ThemeProvider` boundary.

- [ ] **Step 1: Write failing scheme tests**

```tsx
it('uses the full dynamic scheme only when every required role resolves', () => {
  const dynamic = completeDynamicScheme('#315f49');
  expect(resolveMaterialScheme({ dark: false, dynamic })).toEqual(dynamic);
  expect(resolveMaterialScheme({ dark: false, dynamic: { ...dynamic, outline: undefined } })).toEqual(
    fixedSeamScheme(false),
  );
});

it('maps the switch to MD3 52 by 32 geometry and a 48 dp target', () => {
  const screen = render(<ThemeProvider><TwSwitch checked onChange={jest.fn()} /></ThemeProvider>);
  expect(flatten(screen.getByRole('switch').props.style).minHeight).toBeGreaterThanOrEqual(48);
  expect(screen.getByTestId('tw-switch-track').props.style).toEqual(
    expect.arrayContaining([expect.objectContaining({ width: 52, height: 32 })]),
  );
});
```

- [ ] **Step 2: Run focused tests and observe failure**

Run:

```bash
cd mobile
npm test -- --runInBand components/__tests__/materialTheme.test.tsx components/primitives/__tests__/TwSwitch.test.tsx components/primitives/__tests__/TwButton.test.tsx
```

Expected: FAIL because `MaterialColorScheme`, atomic resolution, and the MD3 switch contract do not exist.

- [ ] **Step 3: Implement fixed and dynamic Material roles**

Define every required role as a `ColorValue`, retain semantic status pairs, and keep a compatibility projection for untouched secondary screens during this plan:

```ts
export interface MaterialColorScheme {
  primary: ColorValue;
  onPrimary: ColorValue;
  primaryContainer: ColorValue;
  onPrimaryContainer: ColorValue;
  secondary: ColorValue;
  onSecondary: ColorValue;
  secondaryContainer: ColorValue;
  onSecondaryContainer: ColorValue;
  tertiary: ColorValue;
  onTertiary: ColorValue;
  tertiaryContainer: ColorValue;
  onTertiaryContainer: ColorValue;
  surface: ColorValue;
  surfaceDim: ColorValue;
  surfaceBright: ColorValue;
  surfaceContainerLowest: ColorValue;
  surfaceContainerLow: ColorValue;
  surfaceContainer: ColorValue;
  surfaceContainerHigh: ColorValue;
  surfaceContainerHighest: ColorValue;
  onSurface: ColorValue;
  onSurfaceVariant: ColorValue;
  outline: ColorValue;
  outlineVariant: ColorValue;
  error: ColorValue;
  onError: ColorValue;
  errorContainer: ColorValue;
  onErrorContainer: ColorValue;
  inverseSurface: ColorValue;
  inverseOnSurface: ColorValue;
  inversePrimary: ColorValue;
  scrim: ColorValue;
}

export function resolveMaterialScheme({ dark, dynamic }: {
  dark: boolean;
  dynamic?: Partial<MaterialColorScheme>;
}): MaterialColorScheme {
  const required = MATERIAL_ROLE_KEYS.every((key) => dynamic?.[key] !== undefined);
  return required ? dynamic as MaterialColorScheme : fixedSeamScheme(dark);
}
```

Use Android system accent/neutral tonal resources through `PlatformColor` and select the complete light or dark role map inside `ThemeProvider`. Preserve literal Seam colors for Jest and atomic fallback.

- [ ] **Step 4: Revise shared switch and button primitives**

Use exact Material roles and state layers. The switch is `52 x 32`, its thumb is `24` when checked and `16` when unchecked, and its outer press target is at least `48`. Buttons use primary, secondary-container, surface, and error roles without hover lift or broad shadows.

- [ ] **Step 5: Run focused tests and static gates**

Run:

```bash
cd mobile
npm test -- --runInBand components/__tests__/materialTheme.test.tsx components/primitives/__tests__/TwSwitch.test.tsx components/primitives/__tests__/TwButton.test.tsx
npm run typecheck
npm run lint
```

Expected: all focused tests PASS; typecheck and lint exit 0.

- [ ] **Step 6: Commit**

```bash
git add mobile/components/tokens.ts mobile/components/Theme.tsx mobile/components/__tests__/materialTheme.test.tsx mobile/components/primitives/TwSwitch.tsx mobile/components/primitives/TwButton.tsx mobile/components/primitives/__tests__/TwSwitch.test.tsx mobile/components/primitives/__tests__/TwButton.test.tsx
git commit -m "feat(mobile/ui): add Material 3 dynamic color roles"
```

### Task 2: Seam Identity and Complete Android Icon Pipeline

**Files:**
- Create: `mobile/components/primitives/TwinotifyMark.tsx`
- Modify: `mobile/components/primitives/TwWordmark.tsx`
- Modify: `mobile/components/primitives/__tests__/TwWordmark.test.tsx`
- Modify: `mobile/components/index.ts`
- Create: `mobile/assets/brand/twinotify-mark.svg`
- Create: `mobile/assets/brand/icon.png`
- Create: `mobile/assets/brand/adaptive-foreground.png`
- Create: `mobile/assets/brand/adaptive-monochrome.png`
- Create: `mobile/assets/brand/splash.png`
- Modify: `mobile/app.json`
- Create: `mobile/modules/twinotify-core/android/src/main/res/drawable/ic_stat_twinotify.xml`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateMaterializer.kt`
- Modify or create adjacent builder tests for all three notification paths.

**Interfaces:**
- Produces: `TwinotifyMark({size, color})`, final launcher/splash paths, and `R.drawable.ic_stat_twinotify`.
- Consumes: `Theme.colors.onSurface`, captured `small_icon_png_b64`, and captured `large_icon_png_b64`.

- [ ] **Step 1: Write failing brand and notification-resource tests**

```tsx
test('wordmark composes one bare Seam mark and the product name', () => {
  render(<ThemeProvider><TwWordmark /></ThemeProvider>);
  expect(screen.getByTestId('twinotify-mark')).toBeTruthy();
  expect(screen.getByText('twinotify')).toBeTruthy();
});
```

```kotlin
@Test fun mirrorUsesTwinotifySmallIconAndSourceArtworkFallback() {
    val notification = MirrorPoster.buildNotification(context, post(large = null, small = SMALL_PNG), 7)
    assertEquals(co.twinotify.core.R.drawable.ic_stat_twinotify, notification.smallIcon.resId)
    assertNotNull(notification.getLargeIcon())
}
```

Add source assertions that `SyncService` and `CallStateMaterializer` reference `R.drawable.ic_stat_twinotify`, and that no production file uses `android.R.drawable.ic_dialog_info` or `stat_sys_data_bluetooth`.

- [ ] **Step 2: Run focused tests and observe failure**

Run:

```bash
cd mobile
npm test -- --runInBand components/primitives/__tests__/TwWordmark.test.tsx
cd android
./gradlew :twinotify-core:testDebugUnitTest --tests '*MirrorPoster*' --tests '*CallStateMaterializer*'
```

Expected: FAIL because the Seam component/resource and new artwork fallback do not exist.

- [ ] **Step 3: Implement the canonical mark and in-app wordmark**

Use the approved joined-message path once in `TwinotifyMark` and once in the canonical SVG source. The component uses `currentColor`, exposes no accessibility node of its own, and never adds a tile:

```tsx
export function TwinotifyMark({ size = 24, color }: TwinotifyMarkProps) {
  const theme = useTheme();
  return (
    <Svg testID="twinotify-mark" width={size} height={size * 0.88} viewBox="0 0 48 43" accessible={false}>
      <Path d="M4 10H22L27 15H44V33H26L21 28H4Z" fill="none" stroke={color ?? theme.colors.onSurface} strokeWidth={4} strokeLinejoin="round" />
      <Path d="M15 5V38" fill="none" stroke={color ?? theme.colors.onSurface} strokeWidth={4} strokeLinecap="round" />
    </Svg>
  );
}
```

- [ ] **Step 4: Generate and wire deterministic brand assets**

Render the canonical SVG into the required PNG sizes using an existing project-capable rasterizer or a narrowly installed development tool only if none exists. Keep the mark inside Android adaptive safe zones, use the approved Seam fallback surface, and configure:

```json
{
  "icon": "./assets/brand/icon.png",
  "android": {
    "adaptiveIcon": {
      "foregroundImage": "./assets/brand/adaptive-foreground.png",
      "monochromeImage": "./assets/brand/adaptive-monochrome.png",
      "backgroundColor": "#DCEBE1"
    }
  }
}
```

Configure the splash plugin to use `./assets/brand/splash.png`. Remove old template assets only after `rg` proves they are unreferenced.

- [ ] **Step 5: Implement the Android notification resource and builders**

Create a white, opaque, transparent-background vector resource. Reference it through the module `R` class in mirrors, calls, and the foreground service. In `MirrorPoster`, use:

```kotlin
val sourceArtwork = largeIcon ?: smallIcon
return NotificationCompat.Builder(ctx, NotifChannelSetup.CHANNEL_MIRRORS)
    .setSmallIcon(R.drawable.ic_stat_twinotify)
    .apply { if (sourceArtwork != null) setLargeIcon(sourceArtwork) }
```

Keep full-notification colorization off and retain all existing content visibility, category, ongoing, and clearability behavior.

- [ ] **Step 6: Verify assets and focused tests**

Run:

```bash
cd mobile
npm test -- --runInBand components/primitives/__tests__/TwWordmark.test.tsx
npx expo config --type public
npm run typecheck
cd android
./gradlew :twinotify-core:testDebugUnitTest :app:assembleDebug
```

Expected: tests PASS; Expo config prints only new brand paths; Android assembly exits 0; source scan finds no generic production notification icons.

- [ ] **Step 7: Commit**

```bash
git add mobile/components mobile/assets/brand mobile/app.json mobile/modules/twinotify-core/android/src/main/res/drawable/ic_stat_twinotify.xml mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/call/CallStateMaterializer.kt mobile/modules/twinotify-core/android/src/test
git commit -m "feat(mobile/brand): add Seam identity and notification icons"
```

### Task 3: Privacy-Bounded UI Activity Journal and Room Migration

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/NotificationDb.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/UiActivityJournal.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/storage/UiActivityJournalTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt`
- Create after Room export: `mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/8.json`

**Interfaces:**
- Produces: `UiActivityEvent`, `UiActivityKind`, `UiActivityDirection`, `UiActivityStatus`, `UiActivityJournal.recordQueued`, `recordApplied`, `markTerminal`, and `recent(limit)`.
- Consumes: message ID, package/app name, route, and timestamp only; accepts no payload JSON.

- [ ] **Step 1: Write failing pure journal tests**

```kotlin
@Test fun eventSurfaceCannotCarryNotificationContent() {
    val fields = UiActivityEvent::class.java.declaredFields.map { it.name }.toSet()
    assertTrue(fields.containsAll(setOf("eventId", "msgId", "direction", "kind", "status", "occurredAt")))
    assertTrue(fields.intersect(setOf("title", "text", "bigText", "canonId", "deviceId", "ciphertext", "nonce")).isEmpty())
}

@Test fun duplicateMessageUpdatesOneRowAndCapStaysAtFiveHundred() = runTest {
    repeat(510) { journal.recordApplied(inbound(it), now = it.toLong()) }
    journal.markTerminal("m-509", UiActivityStatus.DELIVERED, "LAN", 999)
    assertEquals(500, store.rows.size)
    assertEquals(1, store.rows.count { it.msgId == "m-509" })
}
```

- [ ] **Step 2: Run the focused JVM test and observe failure**

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests '*UiActivityJournalTest'
```

Expected: FAIL because the UI activity entity and journal do not exist.

- [ ] **Step 3: Implement the isolated entity and journal**

```kotlin
@Entity(
    tableName = "ui_activity_event",
    indices = [Index("occurredAt"), Index(value = ["msgId"], unique = true)],
)
data class UiActivityEvent(
    @PrimaryKey val eventId: String,
    val msgId: String?,
    val packageName: String?,
    val appName: String?,
    val direction: String,
    val kind: String,
    val status: String,
    val route: String?,
    val occurredAt: Long,
)
```

DAO transactions upsert by message ID, delete rows older than 30 days, and delete everything outside the newest 500 rows. `recent(limit)` clamps to `1..20` before reaching SQL.

- [ ] **Step 4: Add migration 7 to 8 and clearing**

Create `ui_activity_event`, both indices, register `MIGRATION_7_8`, add the entity, bump version to 8, and include `clearUiActivityEvents()` in `clearReliableState()`.

- [ ] **Step 5: Add migration test and export schema 8**

The instrumented test creates version 7 with existing paired/protocol rows, runs `MIGRATION_7_8`, validates version 8, proves old rows remain, and proves the new table accepts a privacy-bounded row.

- [ ] **Step 6: Run focused tests and schema generation**

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests '*UiActivityJournalTest' :twinotify-core:kspDebugKotlin
```

Expected: JVM tests PASS and `schemas/.../8.json` exists. Run the migration test on the connected emulator in Task 7.

- [ ] **Step 7: Commit**

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryMigrationTest.kt mobile/modules/twinotify-core/android/schemas/co.twinotify.core.storage.NotificationDbImpl/8.json
git commit -m "feat(mobile/storage): add privacy-bounded activity journal"
```

### Task 4: Wire Activity to Reliable Delivery and Expose the Native API

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/DurableCapturePersister.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/OutboxRepository.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/NotificationMaterializer.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorDismisser.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Create or modify focused native lifecycle tests adjacent to each path.

**Interfaces:**
- Produces: `RecentActivityItem` and `getRecentActivity(limit: number): Promise<RecentActivityItem[]>`.
- Consumes: Task 3 `UiActivityJournal`; uses only already-authenticated and committed lifecycle points.

- [ ] **Step 1: Write failing lifecycle tests**

```kotlin
@Test fun outboundCaptureAndReceiptProduceOneDeliveredUiEvent() = runTest {
    persister.persist(post(appName = "Messages", packageName = "example.messages"))
    repository.onPeerReceipt(messageId, digest, "applied", null, 2000)
    assertEquals(listOf("DELIVERED"), uiStore.rows.map { it.status })
}

@Test fun duplicateInboundMaterializationProducesOneAppliedUiEvent() = runTest {
    materializer.materializePending(nowMs = 2000)
    materializer.materializePending(nowMs = 3000)
    assertEquals(1, uiStore.rows.count { it.msgId == inboundMsgId })
}
```

Add an unpair test proving zero UI rows afterward and a module test proving requests over 20 are clamped.

- [ ] **Step 2: Run focused lifecycle tests and observe failure**

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests '*Activity*' --tests '*Unpair*'
```

Expected: FAIL because delivery paths do not call the journal and the module API does not exist.

- [ ] **Step 3: Wire journal calls only after durable state transitions**

Outbound creation and its queued UI row share the same Room transaction. Receipt/expiry/rejection updates happen inside or immediately after the existing terminal transaction using the retained message ID. Inbound mirror and dismissal rows are inserted only after platform application succeeds. Duplicate and replay branches do not insert a second event.

Do not pass `NotifPostJson` or raw payload JSON into `UiActivityJournal`; extract only `app_name` and `package_name` at the authenticated boundary.

- [ ] **Step 4: Add native and TypeScript contracts**

```ts
export type RecentActivityItem = {
  id: string;
  direction: 'sent' | 'received';
  kind: 'mirror' | 'dismissal';
  status: 'queued' | 'delivered' | 'applied' | 'expired' | 'rejected';
  route?: 'direct' | 'relay';
  appName?: string;
  packageName?: string;
  occurredAt: number;
  sourceIconDataUri?: string;
};
```

```kotlin
AsyncFunction("getRecentActivity") { limit: Int, promise: Promise ->
    moduleScope.launch {
        val rows = UiActivityJournal(NotificationDb.get(requireContext()).reliableDeliveryDao())
            .recent(limit.coerceIn(1, 20))
        promise.resolve(rows.map(::toRecentActivityMap))
    }
}
```

Resolve the source app label/icon locally and bound raster artwork dimensions and encoded bytes. Return no raw `detailCode`.

- [ ] **Step 5: Run focused native tests and TypeScript check**

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest --tests '*Activity*' --tests '*Unpair*'
cd ..
npm run typecheck
```

Expected: tests PASS and TypeScript exits 0.

- [ ] **Step 6: Commit**

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core mobile/modules/twinotify-core/android/src/test mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts
git commit -m "feat(mobile/activity): connect recent activity to delivery"
```

### Task 5: Recent Activity Hook and Privacy-Safe Presentation

**Files:**
- Create: `mobile/hooks/useRecentActivity.ts`
- Create: `mobile/hooks/__tests__/useRecentActivity.test.ts`
- Create: `mobile/components/home/recentActivityPresentation.ts`
- Create: `mobile/components/home/RecentActivitySection.tsx`
- Create: `mobile/components/home/__tests__/RecentActivitySection.test.tsx`

**Interfaces:**
- Produces: `useRecentActivity(limit)`, `presentRecentActivity(item, peerName, now)`, and `RecentActivitySection`.
- Consumes: Task 4 `getRecentActivity`; accepts peer display name but never peer device ID.

- [ ] **Step 1: Write failing hook and component tests**

```tsx
it('distinguishes loading, empty, failure, and populated activity', async () => {
  const { rerender } = render(<RecentActivitySection state={{ kind: 'empty' }} peerName="POCO F1" />);
  expect(screen.getByText('No activity yet')).toBeTruthy();
  expect(screen.getByText(/first mirrored notification/i)).toBeTruthy();
  rerender(<RecentActivitySection state={{ kind: 'error', retry: jest.fn() }} peerName="POCO F1" />);
  expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
});

it('never renders content-like fields from an unexpected native object', () => {
  render(<RecentActivitySection state={populated([{ ...safeItem, title: 'secret', text: 'secret' } as never])} peerName="POCO F1" />);
  expect(screen.queryByText('secret')).toBeNull();
});
```

- [ ] **Step 2: Run tests and observe failure**

Run:

```bash
cd mobile
npm test -- --runInBand hooks/__tests__/useRecentActivity.test.ts components/home/__tests__/RecentActivitySection.test.tsx
```

Expected: FAIL because the hook and components do not exist.

- [ ] **Step 3: Implement the focus-aware hook**

Refresh on mount/focus and every five seconds while focused. Clamp to five for Home. Preserve the previous success during a transient failure; expose a retry only when no successful snapshot exists. Cancel timers and ignore late promises on blur/unmount.

- [ ] **Step 4: Implement presentation and four UI states**

Map only typed enum fields to approved copy. Use app name, falling back to package label then `Source app`. Dismissal rows use the bare Seam dismissal glyph. Source artwork is decorative. `See all` renders only when the native result indicates more than five; if no detail screen is in this plan, query exactly five and omit `See all` rather than ship a dead control.

- [ ] **Step 5: Run focused tests, typecheck, and lint**

Run:

```bash
cd mobile
npm test -- --runInBand hooks/__tests__/useRecentActivity.test.ts components/home/__tests__/RecentActivitySection.test.tsx
npm run typecheck
npm run lint
```

Expected: all PASS and static gates exit 0.

- [ ] **Step 6: Commit**

```bash
git add mobile/hooks/useRecentActivity.ts mobile/hooks/__tests__/useRecentActivity.test.ts mobile/components/home
git commit -m "feat(mobile/home): add real recent activity presentation"
```

### Task 6: Rebuild the Home Screen as the MD3 Seam Control Center

**Files:**
- Create: `mobile/components/home/HomeTopAppBar.tsx`
- Create: `mobile/components/home/ConnectionSurface.tsx`
- Create: `mobile/components/home/HomeMetrics.tsx`
- Create: `mobile/components/home/HomeFilterAction.tsx`
- Create: `mobile/components/home/__tests__/ConnectionSurface.test.tsx`
- Create: `mobile/components/home/__tests__/HomeMetrics.test.tsx`
- Modify: `mobile/components/HandoffTrace.tsx`
- Modify: `mobile/components/__tests__/HandoffTrace.test.tsx`
- Modify: `mobile/app/home.tsx`
- Replace assertions in: `mobile/app/__tests__/homeHandoffTrace.test.tsx`

**Interfaces:**
- Produces: the final home composition.
- Consumes: Tasks 1, 2, and 5 plus existing `presentRoute`, `useSyncStatus`, `useRouteStatus`, and `useMetrics`.

- [ ] **Step 1: Replace the placeholder assertions with failing live-control-center tests**

```tsx
it('renders a real activity section and no permanent placeholder', async () => {
  arrangeDirectWithActivity([safeActivity]);
  render(<HomeScreen />);
  expect(await screen.findByText('Mirrored to POCO F1')).toBeTruthy();
  expect(screen.queryByText(/No mirrors yet/)).toBeNull();
});

it.each(['direct', 'relay', 'reconnecting', 'queued', 'paused', 'unpaired'])
  ('keeps the same control-center structure in %s', async (state) => {
    arrangeRoute(state);
    render(<HomeScreen />);
    expect(screen.getByTestId('connection-surface')).toBeTruthy();
    expect(screen.getByTestId(`handoff-trace-${state}`)).toBeTruthy();
    expect(screen.getByRole('switch', { name: 'Mirror notifications' })).toBeTruthy();
  });
```

Add tests for Settings, retry, pair, filter navigation, toggle rollback, disabled unpaired switch, no device ID fragment, and 320 dp/200% font-scale layouts.

- [ ] **Step 2: Run focused home tests and observe failure**

Run:

```bash
cd mobile
npm test -- --runInBand app/__tests__/homeHandoffTrace.test.tsx components/home/__tests__
```

Expected: FAIL because the new components and real activity surface are absent.

- [ ] **Step 3: Implement focused home components**

`ConnectionSurface` owns the only large tonal surface and receives already-presented route data. `HomeMetrics` uses one responsive flex/grid structure with reserved label/value slots. `HomeTopAppBar` uses the bare Seam wordmark and a conventional Material Settings symbol in one state-layer icon button. `HomeFilterAction` is a working list action named `Choose mirrored apps`.

- [ ] **Step 4: Refine Handoff Trace without changing its truth contract**

Keep geometry pure and accessibility-hidden. Use semantic colors passed from the connection surface. Preserve exact six-state geometry tests. Never start content or route endpoints at opacity zero.

- [ ] **Step 5: Reduce `home.tsx` to orchestration**

The screen loads pair/relay state, owns toggle/retry callbacks, derives `presentRoute`, and composes the five focused units. It contains no brand path, activity-copy switch, or hardcoded Material colors. Use `Unknown device`, never a sliced device ID.

- [ ] **Step 6: Run the complete JS gate**

Run:

```bash
cd mobile
npm test -- --runInBand
npm run typecheck
npm run lint
npx expo-doctor
```

Expected: all Jest suites PASS; typecheck/lint/doctor exit 0.

- [ ] **Step 7: Commit**

```bash
git add mobile/app/home.tsx mobile/app/__tests__/homeHandoffTrace.test.tsx mobile/components/home mobile/components/HandoffTrace.tsx mobile/components/__tests__/HandoffTrace.test.tsx mobile/components/index.ts
git commit -m "feat(mobile/home): rebuild the live control center"
```

### Task 7: Full Native Verification, Migration Proof, and Release Build

**Files:**
- Modify only if a failing gate proves a scoped defect in Tasks 1 through 6.
- Record execution evidence in the active plan checkboxes and final handoff; do not add raw device dumps.

**Interfaces:**
- Consumes: all earlier tasks.
- Produces: verified debug and release APKs with preserved pairing state.

- [ ] **Step 1: Generate a clean native project**

Run:

```bash
cd mobile
npm run prebuild
```

Expected: clean Android project generation exits 0 and preserves source-controlled module resources/schema inputs.

- [ ] **Step 2: Run the Room 7-to-8 migration test on the emulator**

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=co.twinotify.core.storage.ReliableDeliveryMigrationTest
```

Expected: migration suite PASS with version-7 data preserved and `ui_activity_event` validated.

- [ ] **Step 3: Run the complete owned Android gate**

Run:

```bash
cd mobile/android
./gradlew :twinotify-core:testDebugUnitTest :app:assembleDebug
```

Expected: owned Kotlin lint, all native JVM tests, app lint, and debug assembly execute successfully through the repository's fail-closed Gradle wiring.

- [ ] **Step 4: Build the release APK and audit the artifact**

Run:

```bash
cd mobile/android
./gradlew :app:assembleRelease
```

Expected: release assembly exits 0. Verify target SDK 36, non-debuggable manifest, absence of debug probes/traces, and presence of the Seam drawable and new launcher resources.

- [ ] **Step 5: Route any gate defect back to its owning task**

If and only if a gate exposes a defect, stop Task 7, return to the task that owns the failing file, add the regression test named by that task, repeat that task's focused test command, and use that task's exact `git add` paths with a `fix(mobile/home): correct release verification defect` commit. Then restart Task 7 from Step 1. Do not bundle unrelated gate fixes.

### Task 8: Two-Phone Physical Acceptance and Final Anti-Slop Audit

**Files:**
- Modify only if physical evidence proves a defect; every fix requires a regression test and a separate conventional commit.

**Interfaces:**
- Consumes: Task 7 release APK and the already-paired Mi 11X and POCO F1.
- Produces: physical evidence for every non-owner-only acceptance criterion.

- [ ] **Step 1: Preserve settings and install release on both phones**

Record each device's current font scale and night-mode setting without exposing device identifiers in the final report. Install with `adb install -r` so paired data remains intact. Launch Twinotify and verify both devices recover their route without toggling mirroring.

- [ ] **Step 2: Verify launcher and notification identity**

On both phones inspect normal launcher icon, themed launcher icon, splash, foreground-service notification, mirrored notification, and call notification when the user performs the controlled real-call step. Confirm the Seam small icon is optically centered and Android renders it as an opaque monochrome silhouette.

- [ ] **Step 3: Verify Monet and fixed hierarchy**

Capture bounded screenshots in light and dark modes with the current wallpaper palette. Change the system palette or wallpaper, recreate the app, and verify every home semantic role changes coherently with no mixed fallback values. Confirm the Seam mark remains `onSurface` and contrast stays readable.

- [ ] **Step 4: Verify real activity in both directions**

Send one controlled notification Mi 11X to POCO F1 and one POCO F1 to Mi 11X. Use privacy-safe parsers only to confirm delivery. Verify exactly one matching activity row per direction and no title/body text on Home. Dismiss each and verify exactly one synchronized dismissal row.

- [ ] **Step 5: Verify restart and route-state stability**

Force-stop/relaunch each phone one at a time. Confirm both recover Direct on Wi-Fi and stay connected past the former 15-second failure window. Safely induce reconnecting/queued/paused states where possible and confirm the connection surface does not jump or expose dead actions.

- [ ] **Step 6: Verify physical controls and accessibility matrix**

Tap Settings, mirroring switch, retry when available, paired phone, and Choose mirrored apps. Test both phones at font scales 0.85, 1.0, and 2.0 in light and dark modes. Scroll every screen to the bottom and confirm no clipping, edge contact, inaccessible control, off-center mark, or misaligned metric row. Restore each device's exact original font/night setting.

- [ ] **Step 7: Re-read and audit the complete anti-slop law**

Inspect implementation and screenshots point by point. Specifically verify: no pill eyebrow, gradient text, blue-purple gradient, icon tiles used as generic decoration, all-around shadows, clipped content, fake app logos, dead controls, hard seams, default hero stack, ragged parallel metrics, invisible entrance content, off-center mark, generic notification icons, or unreadable dynamic color pairing. Fix every confirmed issue before completion.

- [ ] **Step 8: Run final clean-tree verification**

Run:

```bash
git status --short
git log -8 --oneline --decorate
```

Expected: no unexplained changes; all implementation commits present. Stop Metro, remove temporary device screenshot/UI-dump files, and leave both phones on the release build with original accessibility/theme settings restored.

## Plan Self-Review

- Spec coverage: Tasks 1-2 cover MD3/Monet and identity; Tasks 3-5 cover bounded private history and UI states; Task 6 covers all home states and working controls; Tasks 7-8 cover migration, build, two-phone evidence, and the full anti-slop audit.
- Placeholder scan: every code-producing task includes concrete interfaces, focused test examples, implementation constraints, exact commands, expected results, and commit boundaries.
- Type consistency: `UiActivityEvent` maps to `RecentActivityItem`; `getRecentActivity(limit)` is consumed by `useRecentActivity(limit)`; the home receives presentation state only; all route labels remain owned by `presentRoute`.
