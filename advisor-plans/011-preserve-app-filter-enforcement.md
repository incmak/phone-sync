# Plan 011: Preserve app-filter enforcement across every mutation

> **Executor instructions**: Work in the primary checkout only. Use strict
> test-driven development and capture a meaningful failing test before changing
> production code. Do not use a worktree, device, network, push, or package-data
> clear. Do not commit until an independent reviewer confirms the privacy
> boundary is fail-closed. Update this plan's row in `advisor-plans/README.md`
> when complete.

## Status

- **Priority**: P0
- **Effort**: S
- **Risk**: HIGH
- **Depends on**: none
- **Category**: security
- **Planned at**: commit `66dc533`, 2026-08-27

## Drift check

```bash
git diff --stat 66dc533..HEAD -- \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/filter/AppFilterStore.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/TwinotifyNotificationListener.kt \
  mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt \
  mobile/app/filter.tsx
```

Stop if another change already owns the filter cache or changes the listener's
non-suspending callback contract.

## Why this matters

The native listener enforces user-selected blocked apps from a synchronous
cache. Every add, remove, or clear currently commits DataStore and then assigns
`cached = null`; `cachedOrEmpty()` consequently returns an empty set. A user
changing one app can therefore temporarily allow content from every blocked app
to leave the phone. The UI also updates optimistically and discards native
errors, so it can claim a block that was never committed.

## Current state

- `AppFilterStore.kt:19-28` owns a nullable volatile set and deliberately returns
  `emptySet()` when it is absent.
- `AppFilterStore.kt:31-44` nulls that cache after all three mutations.
- `TwinotifyNotificationListener.kt:130-137` adds `cachedOrEmpty()` to the
  compiled denylist on every notification callback.
- `filter.tsx:56-65` changes visual state first and swallows persistence errors.

The callback must remain non-blocking. Never read DataStore from
`onNotificationPosted` and never weaken the compiled-in denylist.

## Scope

**In scope**:

- `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/filter/AppFilterStore.kt`
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/filter/AppFilterStoreTest.kt` (create)
- `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/TwinotifyNotificationListenerFilterTest.kt` (create if needed)
- `mobile/app/filter.tsx`
- `mobile/app/__tests__/filterEnforcement.test.tsx` (create)
- `advisor-plans/README.md`

**Out of scope**: default denylist asset/hash, notification payload schema,
Room schema, transport, pairing, and visual redesign.

## Implementation steps

### 1. Capture the privacy regression RED

Add deterministic tests proving:

1. starting from `{pkg.a, pkg.b}`, removing `pkg.a` leaves callback-visible
   cache exactly `{pkg.b}` with no empty intermediate result;
2. adding a package preserves all existing entries;
3. concurrent add/remove operations publish the final committed set, never an
   older completion that resumes late;
4. a persistence failure keeps the last committed cache;
5. `clear` publishes empty only after its commit succeeds.

Use a small injected persistence seam or cache owner so JVM tests do not fake
Android DataStore. The RED must fail because the live cache becomes null/empty.

### 2. Publish the committed immutable set atomically

Serialize load and mutations with one coroutine mutex. Each mutation must:

1. derive the new set inside the serialized DataStore edit;
2. finish the commit;
3. publish an immutable copy to the volatile callback snapshot before releasing
   the mutation owner.

Do not expose an uncommitted value and do not set the snapshot to null after
initial load. Preserve `cachedOrEmpty()` only for the pre-listener-initialization
case; listener `onCreate` already loads before callbacks.

### 3. Make the filter UI reflect durable truth

Await `addToDenylist`/`removeFromDenylist`. Roll the affected switch back on
rejection and prevent overlapping writes for the same package. Keep unrelated
rows interactive. Add an accessible, content-free error announcement; do not
display package data from native errors.

### 4. Run gates and independent review

```bash
cd mobile && npm test -- --runInBand app/__tests__/filterEnforcement.test.tsx
cd mobile && npm run typecheck
cd mobile/android && ANDROID_HOME=/Users/mak/Library/Android/sdk ./gradlew --no-daemon \
  :twinotify-core:testDebugUnitTest --tests '*AppFilterStoreTest' --tests '*TwinotifyNotificationListenerFilterTest' \
  :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug
git diff --check
```

Expected: all selected tests and gates exit 0. Independent review must explicitly
confirm there is no fail-open cache window and no stale concurrent publication.

## Done criteria

- [ ] Meaningful pre-fix RED is retained.
- [ ] Callback-visible cache always equals the last committed DataStore set.
- [ ] Persistence failure preserves enforcement and rolls back UI state.
- [ ] Listener callback remains non-blocking.
- [ ] Focused Jest, Kotlin, AndroidTest compilation, lint, typecheck, and diff check pass.
- [ ] Independent security review approves.

## STOP conditions

- The fix requires reading DataStore from the notification callback.
- A concurrent mutation test cannot deterministically prove publication order.
- Any proposal weakens or makes the compiled-in denylist user-editable.
- A required change falls outside the listed scope.
