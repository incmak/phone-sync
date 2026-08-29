# Mirrored Notification Actions: Mirror and UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task in the primary checkout. Stop at Task 7 until the user approves the visual brief.

**Goal:** Reconstruct secure standalone actions on each mirror notification, route taps at tap time, preserve source auto-cancel behavior, and expose a durable local detail surface.

**Architecture:** The inbound canonical-state transaction first maintains an opaque detail cache. `MirrorPoster` creates direct activity content intents and explicit receiver action intents. The receiver trusts identity only from immutable URI components, commits invocation state beside its outbox row, and lets the existing coordinator own delivery. Native APIs expose local cached detail to an Expo Router screen after a separately approved visual design.

**Tech Stack:** Kotlin, Android Notification and RemoteInput APIs, Room, Expo Modules, TypeScript, Expo Router, React Native, existing Twinotify Material 3 tokens and primitives.

## Global Constraints

- Execute only after the protocol and origin plans pass and are committed.
- Work in the primary checkout. Do not create a worktree.
- Add failing tests before production behavior.
- `ActionInvokeReceiver` is non-exported and trusts no identity-bearing extras.
- Reply actions use `FLAG_MUTABLE`; non-reply actions and content intents use `FLAG_IMMUTABLE`.
- Every action requires authentication and the receiver also rejects a locked keyguard.
- Commit `ActionInvocation(PENDING)` and the invoke outbox row in one transaction.
- Do not add a new queue drainer; signal the existing coordinator after commit.
- Never navigate with `canon_id`, package, title, or reply text. Only opaque `detailId` is allowed.
- Keep notification tag/id stable for pending and terminal re-posts.
- Never re-post over a cancelled canonical notification.
- Preserve `PendingPeerCancel.add` before `NotificationManager.cancel` in `MirrorDismisser`.
- UI work stops at the visual approval gate. After approval, use the existing tokens and `Tw*` primitives and perform the complete anti-slop audit before completion.

---

## Task 1: Make notification detail durable before Android can post

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryEntities.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/ReliableDeliveryDaoMaterializationTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/NotificationDetailCacheTest.kt`

### Step 1: Write the auto-cancel race and retention tests

Add connected DAO tests that prove:

- the first inbound post creates a random `detailId` in the same transaction as canonical desired state;
- update reuses the same `detailId` and refreshes payload/timestamps;
- cancel nulls canonical `desiredPayloadJson` but retains cached content and stamps `cancelledAt`;
- active rows are never age-evicted;
- cancelled rows survive 10 minutes, then expire;
- at most the 500 newest cancelled rows remain;
- a transaction failure creates neither canonical state nor cache state.

Run the focused connected test. Expected: FAIL because inbound reduction does not touch the cache.

### Step 2: Add transactional cache maintenance

Extend the existing canonical state commit transaction. Allocate `UUID.randomUUID()` only for a missing `canonId`; never derive it from notification content. The transaction performs:

```text
notif.post/update: upsert active cache snapshot, then commit canonical desired state
notif.cancel: retain payload, set cancelledAt, then commit cancelled canonical state
```

Expose narrow DAO lookups by `detailId` and `canonId`. Add a bounded sweep callable at startup and after cancellation.

### Step 3: Verify and commit

Run the connected cache/materialization tests. Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage
git commit -m "feat(mobile/detail): persist mirrored notification snapshots"
```

---

## Task 2: Reconstruct secure actions and invoke them atomically

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionInvokeReceiver.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/MirrorActionInvoker.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionInvocationExpiry.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions/ActionInvocationExpiryReceiver.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/MirrorActionInvokerTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionInvokeIntentTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionInvocationExpiryTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage/NotificationActionDaoTest.kt`

### Step 1: Specify intent security and invocation atomicity

Add tests asserting:

- each action data URI is `twinotify://invoke/<mirrorTag>/<mirrorId>/<actionId>` and uniquely identifies the stable mirror plus action;
- reply action PendingIntent is mutable, non-reply is immutable;
- identity-bearing extras cannot override URI identity;
- every action sets authentication required and generated replies are disabled;
- locked keyguard produces no invocation row and no outbox row;
- missing/cancelled canonical state produces no transmission;
- reply text is extracted only through `RemoteInput.getResultsFromIntent` and capped at 4,096 UTF-8 bytes;
- one Room transaction inserts `PENDING` plus the encoded invoke row or inserts neither;
- invoke expiry is armed at `createdAt + 120_000` and startup rehydrates it.

Run focused tests. Expected: FAIL because no action receiver/invoker exists.

### Step 2: Add action reconstruction to `MirrorPoster`

For at most three parsed descriptors, create explicit intents to the non-exported receiver. Build `RemoteInput("twinotify_reply")` only when `reply=true`; that local result key is never transmitted. Use URI identity and stable mirror tag/id, not request-code arithmetic.

Set `setAuthenticationRequired(true)` and `setAllowGeneratedReplies(false)`. Preserve action order and semantic action.

### Step 3: Implement the shared mirror invoker

`ActionInvokeReceiver` parses the URI and delegates to `MirrorActionInvoker`. The invoker:

1. verifies keyguard unlocked;
2. resolves `canonId` from mirror tag/id;
3. loads the current active canonical row and matching action descriptor;
4. validates reply shape and byte length;
5. encodes the invoke;
6. transactionally inserts `ActionInvocation(PENDING)` and outbox row;
7. signals existing coordinator ownership after commit;
8. schedules expiry and requests a same-tag/id re-post.

Do not start a service drainer directly from the receiver.

### Step 4: Implement local expiry

Reuse the persistent AlarmManager pattern. On wake, terminalize due `PENDING` rows as `EXPIRED`, clear reply text in the same transaction, re-post only if the canonical row is still active at the same sequence, and schedule the next row.

### Step 5: Verify and commit

Run JVM action tests, connected atomicity tests, lint, and assemble. Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions mobile/modules/twinotify-core/android/src/androidTest/java/co/twinotify/core/storage
git commit -m "feat(mobile/actions): invoke mirrored notification actions"
```

---

## Task 3: Apply action results and re-render honest lifecycle states

**Files:**

- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/InboundDispatcher.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage/ReliableDeliveryDao.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/actions/ActionResultProcessorTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/InboundDispatcherControlTest.kt`

### Step 1: Specify result idempotency and presentation

Add tests showing:

- result processing journals the inbound message and changes state only from `PENDING`;
- every terminal transition clears `replyText` atomically;
- duplicate result is idempotent;
- a result after local `EXPIRED` is journaled but does not resurrect state;
- pending reply uses remote-input history and a `Sending...` status;
- dispatched renders `Sent`; unknown renders `Unconfirmed`; other safe states remain distinguishable;
- re-post uses current desired payload and stable tag/id;
- cancel or sequence mismatch suppresses the re-post.

Run focused tests. Expected: FAIL because results are not handled.

### Step 2: Add the mirror result control branch

Route `notif.action.result` through a dedicated transaction that inserts the inbound journal and conditionally terminalizes the matching invocation. Return a post-commit re-render request only when the current canonical row is active and sequence-compatible.

Keep user-facing strings in Android resources. Do not expose source exception details.

### Step 3: Verify and commit

Run the result, dispatcher, and mirror-poster test groups. Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/storage mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core
git commit -m "feat(mobile/actions): present mirrored action outcomes"
```

---

## Task 4: Replace unconditional tap dismissal with tap-time routing

**Files:**

- Delete: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorTapReceiver.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/detail/NotificationRouterActivity.kt`
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/detail/SourceAppLauncher.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/MirrorPoster.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/listener/ReasonCodeFilter.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/detail/NotificationRouterActivityTest.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/detail/SourceAppLauncherTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/listener/ReasonCodeFilterTest.kt`

### Step 1: Specify tap-time decisions and removal reasons

Add tests for app installed, app missing, no launcher, uninstall after notification post, launch failure, and invalid/missing detail ID. The router must decide at tap time and fall back to Twinotify route `notification/<detailId>` without leaving an empty activity.

Add reason tests: own-package removal reason 1 maps to `user_click`; swipe maps to `user_swipe`; peer-cancel tombstones and existing non-own-package behavior stay unchanged.

Expected initial result: FAIL because taps still use the unconditional broadcast receiver.

### Step 2: Add the direct router activity

Create an immutable direct-activity content PendingIntent with data `twinotify://notification/<detailId>`. `NotificationRouterActivity` loads package identity from the detail cache, queries the launcher at tap time, and starts it. If resolution or launch fails, it launches the Twinotify deep link and finishes only after the fallback is handed off.

Add a package visibility `<queries>` declaration for `MAIN` plus `LAUNCHER`. Do not put `canonId` or package data in the URI.

### Step 3: Preserve source auto-cancel semantics

Use `post.isAutoCancel`, defaulting true for legacy payloads. Remove `MirrorTapReceiver` registration and code. Update own-package reason labeling without touching the `PendingPeerCancel` ordering in `MirrorDismisser`.

### Step 4: Verify and commit

Run router, launcher, reason, and mirror tests plus lint/assemble. Expected: PASS.

Commit:

```bash
git add -A mobile/modules/twinotify-core/android/src/main
git add mobile/modules/twinotify-core/android/src/test
git commit -m "feat(mobile/tap): route mirrored notification taps"
```

---

## Task 5: Expose detail and invocation functions through the Expo module

**Files:**

- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/detail/NotificationDetailRepository.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/TwinotifyCoreModule.kt`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCore.types.ts`
- Modify: `mobile/modules/twinotify-core/src/TwinotifyCoreModule.ts`
- Modify: `mobile/hooks/useTwinotifyCore.ts`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/detail/NotificationDetailRepositoryTest.kt`
- Create: `mobile/modules/twinotify-core/src/__tests__/notificationDetail.test.ts`

### Step 1: Define the typed surface with failing tests

Define `NotificationDetail` and `NotificationDetailAction` with source app/package, origin label, content fields, icons, received/updated timestamps, active/cancelled/gone state, action descriptors, and invocation states. Add native functions:

```text
getNotificationDetail(detailId)
invokeMirrorAction(detailId, actionId, replyText?)
canLaunchSourceApp(packageName)
```

Tests must prove unknown/expired IDs return null, cancelled cache content remains readable within retention, the native invoke path shares `MirrorActionInvoker`, and launchability is a live query.

### Step 2: Implement local-only detail projection

Overlay cached payload with current canonical and invocation state. Never create UI activity rows containing notification content. Keep package name in the returned local object for the UI affordance only; navigation remains opaque.

### Step 3: Verify and commit

Run native detail tests and `npm run typecheck`. Expected: PASS.

Commit:

```bash
git add mobile/modules/twinotify-core mobile/hooks/useTwinotifyCore.ts
git commit -m "feat(mobile/detail): expose mirrored notification detail"
```

---

## Task 6: Propose the detail-screen visual brief

**Files:**

- Create: `docs/design/NOTIFICATION_DETAIL_VISUAL_BRIEF.md`

### Step 1: Inspect the current Material 3 app in context

Review `mobile/components/tokens.ts`, `Theme.tsx`, every `Tw*` primitive used by the home/activity surfaces, the current logo treatment, light/dark/Monet behavior, and screenshots from the latest UI verification artifacts. Re-read the full anti-slop design law before proposing anything.

### Step 2: Write one concrete visual proposal

The proposal must specify hierarchy, layout, typography roles, color tokens, action treatment, inline-reply interaction, pending/terminal feedback, cancelled/gone state, loading/error states, accessibility, large-font behavior, and motion. It must remain recognizably Google Material 3 and preserve the current `A` mark while avoiding generic cards, icon tiles, pill clutter, glows, gradients, hover lifts, hidden entrance content, and decorative line-icon overload.

Include a compact phone wireframe and exact UX copy. Use regular hyphens, not em dashes.

### Step 3: Present it to the user

Commit only the proposal:

```bash
git add docs/design/NOTIFICATION_DETAIL_VISUAL_BRIEF.md
git commit -m "docs: propose notification detail visual design"
```

Then stop and request explicit visual approval. Do not start Task 7 before approval.

---

## Task 7: Visual Approval Gate

**Blocking condition:** The user must explicitly approve the visual brief or request revisions.

- If revisions are requested, update only the brief, re-review it against the anti-slop law, commit the revision, and ask again.
- If approved, record the approval in the brief and proceed.
- This gate does not block Tasks 1 through 6; it blocks only the React Native detail-screen implementation.

---

## Task 8: Build the approved notification detail screen

**Files:**

- Create: `mobile/app/notification/[detailId].tsx`
- Create: `mobile/components/notification-detail/NotificationDetailScreen.tsx`
- Create only the approved supporting components under: `mobile/components/notification-detail/`
- Modify if required by approved navigation: `mobile/app/_layout.tsx`
- Create: `mobile/__tests__/notification-detail-screen.test.tsx`

### Step 1: Write screen behavior and accessibility tests

Before styling, add tests for loading, missing/expired detail, full active content, cancelled content, text and non-text actions, reply entry, pending/terminal state, launch-source-app affordance, hardware back, accessible names, disabled states, and 200 percent font scaling without clipping.

Expected: FAIL because the route does not exist.

### Step 2: Implement the approved design using existing primitives

Build from `Tw*` primitives and theme tokens. Keep content visible by default. Use no entrance state that begins at opacity zero. Every interactive control must call a real native function and expose pressed/disabled/accessibility state. Respect reduced motion and dynamic color.

Do not improvise a new visual direction beyond the approved brief.

### Step 3: Verify every control and visual state

Run:

```bash
cd mobile
npm run typecheck
npm run lint
npx expo-doctor
```

Build and open the route on the emulator. Exercise every control with real taps, including reply submit, open in app, back, and missing detail. Capture light, dark, Monet, cancelled, pending, terminal, and 200 percent font screenshots.

### Step 4: Perform the promised anti-slop audit

Re-read the entire anti-slop design law point by point. Check every applicable item against the rendered screen at normal and zoomed scale. Fix every issue found, especially clipping, centering, contrast, generic cards/pills, content at edges, shadow halos, dead controls, and hidden content.

Commit only after the audit:

```bash
git add mobile/app/notification mobile/components/notification-detail mobile/app/_layout.tsx mobile/__tests__
git commit -m "feat(mobile/detail): add notification detail screen"
```

---

## Plan Completion Gate

Run:

```bash
cd mobile
npm run typecheck
npm run lint
npx expo-doctor
cd android
./gradlew :twinotify-core:testDebugUnitTest :twinotify-core:lintDebug :twinotify-core:assembleDebug
git diff --check
git status --short
```

Expected: all automated gates pass; native tap and action paths are complete; UI screenshots and anti-slop findings are recorded; working tree is clean. Physical phone behavior remains part of the verification plan.
