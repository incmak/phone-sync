# Complete App Filter Catalog Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to implement this plan task-by-task.

**Goal:** Replace the 15-entry demo app filter with the device's complete user-facing app catalog, permanently omit Twinotify, and default-block Android-classified music/audio apps while allowing users to opt them back in.

**Architecture:** Add an Android catalog boundary that queries `MAIN`/`LAUNCHER` activities already covered by the manifest package-visibility declaration, de-duplicates packages, excludes Twinotify's own package, classifies `ApplicationInfo.CATEGORY_AUDIO`, and sorts by display label. Extend the existing DataStore filter from one explicit deny set to explicit deny plus explicit allow overrides, then publish one effective immutable deny snapshot to the notification-listener callback. The React Native screen consumes real package names and app artwork, virtualizes the catalog, and keeps the existing design-system controls.

**Tech Stack:** Kotlin, Android PackageManager, Preferences DataStore, Expo Modules, TypeScript, React Native, Jest/React Native Testing Library, JUnit.

---

### Task 1: Specify catalog and default-filter policy

**Files:**
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/filter/InstalledAppCatalogTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/filter/AppFilterStoreTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/filter/InstalledAppCatalog.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/filter/AppFilterStore.kt`

**Step 1: Write the failing tests**

Cover these policy cases:

```kotlin
assertEquals(listOf("com.example.chat", "com.example.music"), catalog.map { it.packageName })
assertFalse(catalog.any { it.packageName == "co.twinotify.app" })
assertTrue(catalog.single { it.packageName == "com.example.music" }.defaultFiltered)
assertEquals(setOf("music"), FilterPreferences().effective(setOf("music")))
assertEquals(emptySet(), FilterPreferences(explicitlyAllowed = setOf("music")).effective(setOf("music")))
```

Also cover deterministic de-duplication/sorting, explicit user blocks, allow overrides, and switching a default-filtered app back to blocked.

**Step 2: Run the focused JVM tests and observe failure**

Run after Android prebuild is present:

```bash
cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.filter.*'
```

Expected: FAIL because the catalog and tri-state preference policy do not exist.

**Step 3: Implement the smallest policy boundary**

Create `InstalledAppCatalog` with a pure normalization helper and a production query:

```kotlin
val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
val candidates = packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
```

Map packages to display labels and category, exclude `context.packageName`, de-duplicate by package name, classify only `ApplicationInfo.CATEGORY_AUDIO` as default filtered, and sort label-first with a package-name tie-breaker.

Extend DataStore with an explicit allow-override set. Resolve the listener's effective snapshot as:

```kotlin
explicitlyDenied + (defaultFiltered - explicitlyAllowed)
```

All mutations must commit both preference sets before publishing the replacement immutable snapshot.

**Step 4: Run the focused JVM tests**

Expected: PASS.

### Task 2: Expose the real catalog through the native bridge

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Modify: `mobile/modules/twinotify-core/src/index.ts`
- Modify: `mobile/jest.setup.js`

**Step 1: Add a failing source/API contract test**

Extend the focused Kotlin or TypeScript contract coverage to require `getFilterableApps` and fields `packageName`, `displayName`, `artworkDataUri`, and `defaultFiltered`.

**Step 2: Observe failure**

Run the focused test and confirm the bridge method is absent.

**Step 3: Implement the bridge**

Add `FilterableApp` to the TypeScript surface and an Android async function that returns the catalog using real package names. Reuse `sourceAppArtworkDataUri` for installed app artwork. Load catalog first and then load the effective denylist on the screen so music defaults are reflected before rendering.

**Step 4: Run typecheck and the focused bridge test**

```bash
cd mobile && npm run typecheck
```

Expected: PASS.

### Task 3: Replace the demo screen with the real catalog

**Files:**
- Modify: `mobile/app/__tests__/filterEnforcement.test.tsx`
- Modify: `mobile/app/filter.tsx`
- Modify: `mobile/components/primitives/TwAppChip.tsx`

**Step 1: Write failing screen tests**

Mock more than 15 real catalog entries and assert:

- the sixteenth and later apps render;
- toggles call native persistence with full Android package names;
- an audio app starts blocked and can be enabled;
- search matches display name and package name;
- no hard-coded 15-app catalog or misleading fixed banking count appears;
- failure rollback and per-package write serialization still work.

**Step 2: Run the focused Jest test and observe failure**

```bash
cd mobile && npx jest app/__tests__/filterEnforcement.test.tsx --runInBand
```

Expected: FAIL because the screen still renders `TW_APPS`.

**Step 3: Implement the screen**

Load catalog followed by effective denylist, derive counts from package names, and virtualize rows with `FlatList`. Search both app label and package name. Render actual app artwork when available with a quiet design-system fallback. Replace the misleading fixed-count callout with concise copy explaining that protected apps cannot be enabled and music/audio apps start blocked but can be enabled below.

Preserve the current header, tabs, switch interaction, optimistic updates, content-free error, 48dp targets, theme tokens, typography, and dark-theme behavior.

**Step 4: Run the focused Jest test**

Expected: PASS.

### Task 4: Preserve listener fail-closed behavior

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/TwinotifyNotificationListenerFilterTest.kt`

**Step 1: Strengthen the regression test**

Assert that Twinotify-authored notifications are rejected before the effective user/default filter snapshot is consulted and that callbacks continue to avoid DataStore reads.

**Step 2: Run the listener and filter JVM tests**

```bash
cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest --tests 'co.twinotify.core.listener.TwinotifyNotificationListenerFilterTest' --tests 'co.twinotify.core.filter.*'
```

Expected: PASS.

### Task 5: Verify the complete mobile change

**Files:**
- Review: all files changed above

**Step 1: Run static and automated checks**

```bash
cd mobile && npm run typecheck
cd mobile && npm run lint
cd mobile && npx jest app/__tests__/filterEnforcement.test.tsx --runInBand
cd mobile/android && ./gradlew :twinotify-core:testDebugUnitTest :app:assembleDebug
```

Expected: all PASS.

**Step 2: Run emulator acceptance**

Install the debug APK on one configured emulator, open App filter, and verify the catalog exceeds 15 where the emulator has enough launcher apps, Twinotify is absent, music/audio rows start off, search/tabs work, toggles persist after reopening, and light/dark layouts remain usable at compact width and large font scale.

**Step 3: Re-read and apply the full anti-slop review**

Re-read the complete anti-slop law, then review the actual screen point-by-point: no gradients, excessive cards/pills, emoji UI icons, fake counts, raw colors, undersized touch targets, typography regressions, clipped text, inaccessible switch names, or light/dark contrast failures.

**Step 4: Review the diff and report limitations honestly**

Confirm no broad package visibility permission or compiled security denylist change was introduced. Report emulator evidence separately from physical-device evidence.
