# Phase 3 — Listener + First Mirror + Tier-1 UI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** First end-to-end user-visible slice. A notification posted on Phone A appears as a mirror on Phone B within ~3s over the relay, with dismiss propagation in both directions. Ship the full Tier-1 UI per the design bundle (onboarding, pairing, home, settings, app filter) so the user can drive the flow without touching a JS console. Close the Phase-2-documented confirmation_sig out-of-band gap via relay WebSocket push. Rebrand all internal identifiers from `phonesync`/`phone-sync` → `twinotify` in a single atomic Task 0.

**Architecture shift from Phase 2:** Phase 2 was headless crypto. Phase 3 adds (a) a `NotificationListenerService`, (b) a foreground `SyncService` holding the WebSocket, (c) Room persistence for NLS state maps, (d) Expo UI screens using the supplied design tokens + primitives, (e) a relay change that WebSocket-pushes `confirmation_sig` back to Device A during pairing, (f) startup denylist SHA-256 integrity check.

**Scope decisions locked by user (2026-04-21):**

- **Always-Connected FGS mode only.** Lazy-FGS + `IDLE_DEMOTED` state defers to Phase 9 Battery Tuning.
- **Relay sig-push bundled with Phase 3** (Task 7). Closes MEMORY §10 out-of-band gap.
- **Full Tier-1 UI ships in Phase 3.** Design bundle received: `docs/design/assets/tier-1-design-bundle/` — tokens (warm-ink + mint-teal accent, light+dark parity, oklch palette), primitives (Logo, Wordmark, StatusDot, DeviceChip, AppChip, Fingerprint, QR, Button, Card, Switch, Row, Banner, Empty), 18 screens (Welcome, How, Role, Relay, Perms, OEM, Ready, PairQR, PairScan, PairFP, PairSuccess, PairFail, Home, Settings, SetPair, Filter).
- **Rebrand force-invalidates paired test devices.** Acceptable; re-pair manually after Task 0.

**Tech-stack additions (Android):**

- `androidx.room:room-runtime:2.6.1` + `room-ktx:2.6.1` + KSP `room-compiler:2.6.1`.
- `androidx.lifecycle:lifecycle-service:2.8.4`.
- `com.google.devtools.ksp` Gradle plugin (prefer KSP over kapt).
- Existing deps reused: okhttp, lazysodium, jna, datastore-preferences, kotlinx-coroutines.

**Tech-stack additions (Mobile JS/TS):**

- `react-native-svg` — required by icon + logo primitives.
- `expo-camera` — QR scan screen.
- `expo-barcode-scanner` OR `expo-camera` w/ barcode API (SDK 52+ has built-in).
- `@react-navigation/native` + `@react-navigation/native-stack` — nav between onboarding/home/settings/filter. (Expo Router already in place; stack can be file-based under `mobile/app/`.)
- `@shopify/react-native-skia` — NOT needed; QR is a static svg for now, scanning uses camera.
- Fonts: `Inter` + `JetBrains Mono` via `expo-font` or Google Fonts package.
- No new state lib — React state + the existing `useTwinotifyCore` native bridge hook are enough.

**Tech-stack additions (Relay, Go):**

- Nothing new. Task 7 touches existing ws.go + pair.go surgically.

**Spec reference:** `docs/superpowers/specs/2026-04-20-phone-sync-design.md` — §3 packet protocol, §4.1 NLS, §4.2 SyncService + perms, §4.2.1 channels, §4.4 RelayTransport, §4.7.1 tap behavior, §4.7.3 privacy filters, §6 dismissal flow, §7 error handling.

**Design reference:** `docs/design/assets/tier-1-design-bundle/project/tokens.jsx`, `primitives.jsx`, `screens.jsx`, `Twinotify.html`.

**Out of scope (Phase 3):**

- LAN transport + NSD + TLS pinning (Phase 4)
- FCM + lazy FGS re-promotion (Phase 5)
- Reply bridge (`notif.reply`, `reply.failed`) (Phase 6)
- Icon cache + hash-elide + `icon.request`/`icon.reply` (Phase 7)
- MessagingStyle / BigTextStyle / BigPictureStyle reconstruction (Phase 18)
- Desktop Tauri (Phase 8+)
- Battery tuning, dumpsys batterystats, dontkillmyapp deep-links (Phase 9; OEM reliability copy exists in onboarding per design ScreenOEM but uses placeholder links)
- Two-sided cryptographic confirmation (B's signature enforcement) — remains documented known gap (Memory §10)
- iOS parity (design mentions iOS as future; Android-only ships)

---

## File-tree additions

```text
phone-sync/
├── mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/   # ← renamed in Task 0
│   ├── TwinotifyCoreModule.kt                                               # ← renamed + extended (Task 5)
│   ├── listener/
│   │   ├── TwinotifyNotificationListener.kt                                 # Task 3
│   │   ├── ReasonCodeFilter.kt                                              # Task 3 (pure fn)
│   │   ├── CanonIdBuilder.kt                                                # Task 3 (pure fn)
│   │   ├── NotifPostBuilder.kt                                              # Task 3
│   │   └── PendingPeerCancel.kt                                             # Task 3 (in-memory)
│   ├── service/
│   │   ├── SyncService.kt                                                   # Task 4
│   │   ├── OutboundQueue.kt                                                 # Task 4 (Room)
│   │   ├── InboundDispatcher.kt                                             # Task 4
│   │   ├── MirrorPoster.kt                                                  # Task 4
│   │   ├── MirrorDismisser.kt                                               # Task 4
│   │   ├── NotifChannelSetup.kt                                             # Task 4
│   │   ├── BootReceiver.kt                                                  # Task 4
│   │   └── SyncServiceStatus.kt                                             # Task 4 (StateFlow)
│   ├── storage/                                                             # existing — moved in Task 0
│   │   ├── NotificationDb.kt                                                # Task 2 (Room)
│   │   ├── MirroredFromPeer.kt                                              # Task 2
│   │   ├── LocalIdToCanonId.kt                                              # Task 2
│   │   ├── NotificationMapDao.kt                                            # Task 2
│   │   ├── OutboundEvent.kt                                                 # Task 4
│   │   └── OutboundEventDao.kt                                              # Task 4
│   └── filter/
│       └── DenylistLoader.kt                                                # Task 8
├── mobile/modules/twinotify-core/android/src/main/assets/
│   └── default-denylist.json                                                # Task 8
├── mobile/android/app/src/main/AndroidManifest.xml                          # Task 4 (add perms + service)
├── mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml       # Task 3 (add NLS service entry)
├── mobile/app/
│   ├── _layout.tsx                                                          # Task 9 (root stack nav)
│   ├── index.tsx                                                            # Task 9 (routes by state: onboarding | home)
│   ├── onboarding/
│   │   ├── welcome.tsx, how.tsx, role.tsx, relay.tsx, perms.tsx, oem.tsx, ready.tsx   # Task 10
│   ├── pair/
│   │   ├── qr.tsx, scan.tsx, fingerprint.tsx, success.tsx, fail.tsx                    # Task 11
│   ├── home.tsx                                                                       # Task 12
│   ├── settings/
│   │   ├── index.tsx, pair.tsx                                                        # Task 12
│   └── filter.tsx                                                                     # Task 12
├── mobile/components/
│   ├── tokens.ts                                                            # Task 9 (port of tokens.jsx)
│   ├── Theme.tsx                                                            # Task 9 (ThemeProvider)
│   ├── primitives/
│   │   ├── TwLogo.tsx, TwWordmark.tsx, TwStatusDot.tsx, TwDeviceChip.tsx,
│   │   ├── TwAppChip.tsx, TwFingerprint.tsx, TwQR.tsx, TwButton.tsx,
│   │   ├── TwCard.tsx, TwSwitch.tsx, TwRow.tsx, TwBanner.tsx,
│   │   ├── TwEmpty.tsx, TwIcon.tsx, TwSpinner.tsx
│   └── index.ts                                                             # Task 9
├── mobile/hooks/
│   ├── useTwinotifyCore.ts                                                  # Task 5
│   └── useSyncStatus.ts                                                     # Task 5
├── mobile/types/
│   └── twinotify.d.ts                                                       # Task 5
├── proto/
│   ├── envelope-encrypted.schema.json                                       # Task 1
│   ├── ack.schema.json                                                      # Task 1
│   └── pair-sig.schema.json                                                 # Task 7
└── relay/internal/server/
    ├── ws.go                                                                # Task 7 (surgical: expose clientFor)
    └── pair.go                                                              # Task 7 (push sig on complete)
```

---

## Task 0 — Internal rebrand `phonesync` → `twinotify` (single atomic commit)

**Files renamed/moved:**

- `mobile/modules/phone-sync-core/` → `mobile/modules/twinotify-core/` (directory rename)
- `mobile/modules/twinotify-core/android/src/main/java/expo/modules/phonesynccore/` → `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/` (directory rename)

**Files edited:**

- `mobile/modules/twinotify-core/package.json`: `"name": "phone-sync-core"` → `"twinotify-core"`.
- `mobile/modules/twinotify-core/expo-module.config.json`: update `platforms`, `android.modulesClassNames` to `co.twinotify.core.TwinotifyCoreModule`.
- `mobile/modules/twinotify-core/android/build.gradle`: `group`, `namespace` → `co.twinotify.core`.
- Every `.kt` file: `package expo.modules.phonesynccore.*` → `package co.twinotify.core.*`; update imports.
- `TwinotifyCoreModule.kt` (renamed from `PhoneSyncCoreModule.kt`): `Name("PhoneSyncCore")` → `Name("TwinotifyCore")`.
- Every `.ts/.tsx` under `mobile/app/`, `mobile/hooks/`: `requireNativeModule("PhoneSyncCore")` → `"TwinotifyCore"`.
- `mobile/app.json`: update `android.package` to `com.twinotify.app`, `scheme` to `twinotify`. `name`, `slug` remain (user-facing already rebranded).
- `mobile/package.json`: add/update workspace path `mobile/modules/twinotify-core`.
- DataStore keys in Kotlin: `phonesync_crypto`, `phonesync_peer`, `phonesync_identity`, `phonesync_nonce`, `phonesync_replay` → `twinotify_*`.
- Keystore alias constant in `KeystoreMaster.kt`: `phonesync.master` → `twinotify.master`.
- `mobile/android/app/src/main/AndroidManifest.xml`: `android:scheme="phonesync"` → `"twinotify"`.
- `relay/go.mod` module line: `module github.com/phonesync/relay` → `github.com/twinotify/relay`. Sweep every `github.com/phonesync/relay` import.
- `proto/*.schema.json`: `"$id": "https://phone-sync.local/schemas/..."` → `"https://twinotify.app/schemas/..."`.
- `relay/internal/server/validator.go`: `schemaBaseURL` constant → `"https://twinotify.app/schemas/"`. **Byte-for-byte match required (MEMORY §11).**
- String constants referencing `_phonesync._tcp` mDNS type → `_twinotify._tcp` (comments only in Phase 1–2 code; kept for Phase 4 parity).
- `Makefile`: any `phonesync` / `phone-sync-core` path refs.

**Verification for Task 0 (MUST pass before next task starts):**

```bash
# 1. Exhaustive grep — zero hits in source.
grep -rE 'phonesync|phone-sync|phone_sync' \
  mobile/app mobile/components mobile/hooks mobile/types mobile/modules/twinotify-core \
  mobile/android/app/src mobile/app.json mobile/package.json \
  relay/cmd relay/internal proto Makefile .github/workflows \
  --include='*.kt' --include='*.ts' --include='*.tsx' --include='*.js' --include='*.json' \
  --include='*.xml' --include='*.gradle' --include='*.go' --include='*.mod' --include='*.yml'
# Expected: no output. Docs/ excluded (historical docs kept).

# 2. Relay green.
cd relay && go build ./... && go test ./...

# 3. Mobile typecheck + doctor.
cd mobile && npx tsc --noEmit && npx expo-doctor

# 4. Dev-client build (manual, user-run).
```

**Critical risk guard:** if schema `$id` diverges from validator `schemaBaseURL` constant → validator silently rejects every packet. Verify envelope test passes after rename.

**Commit:** `refactor: rebrand internal identifiers phonesync → twinotify (Task 0)`

---

## Task 1 — Proto: encrypted envelope + ack schemas

**Why:** Phase 1 envelope only enumerated `ping`/`pong`/`pair.*` types. Phase 3 needs the encrypted envelope wrapping (`type: "enc"` with `nonce` + `ciphertext` base64) and an inner `ack` for delivery receipts.

**Files:**

- Create `proto/envelope-encrypted.schema.json`:
  ```json
  {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": "https://twinotify.app/schemas/envelope-encrypted.schema.json",
    "type": "object",
    "required": ["v", "type", "msg_id", "origin_device", "ts", "nonce", "ciphertext"],
    "properties": {
      "v": { "const": 1 },
      "type": { "const": "enc" },
      "msg_id": { "type": "string", "format": "uuid" },
      "origin_device": { "type": "string", "minLength": 1 },
      "ts": { "type": "integer", "minimum": 0 },
      "nonce": { "type": "string", "contentEncoding": "base64", "minLength": 32, "maxLength": 40 },
      "ciphertext": { "type": "string", "contentEncoding": "base64", "minLength": 1 }
    },
    "additionalProperties": false
  }
  ```
- Create `proto/ack.schema.json` — inner ciphertext shape after decryption, carrying `{canon_id, status: "delivered"|"decrypt_failed"}`.
- Modify `proto/packet.schema.json` — extend `type` enum to include `"enc"`, `"notif.post"`, `"notif.update"`, `"notif.cancel"`, `"ack"`.
- Modify `relay/internal/server/validator.go` — register new schemas via `go:embed`, remove stale `bytes` import anchor (MEMORY §10).

**TDD (Go):**

- `validator_test.go` — add cases: envelope with valid base64 nonce + ciphertext passes; missing `nonce` fails; wrong `type` value fails.

**Commit:** `feat(proto): encrypted envelope + ack schemas`

---

## Task 2 — Room persistence for NLS state maps

**Why Room over DataStore:** `mirroredFromPeer` + `localIdToCanonId` are indexed lookups (O(log n)); DataStore is O(n) scan. TTL sweep needs `WHERE created_ts < ?`.

**Files:**

- Create `co/twinotify/core/storage/NotificationDb.kt` — `@Database(entities=[MirroredFromPeer, LocalIdToCanonId, OutboundEvent], version=1)`. Singleton pattern.
- Create `storage/MirroredFromPeer.kt`:
  ```kotlin
  @Entity(tableName = "mirrored_from_peer")
  data class MirroredFromPeer(
      @PrimaryKey val canon_id: String,
      val origin_device_id: String,
      val created_ts: Long
  )
  ```
- Create `storage/LocalIdToCanonId.kt`:
  ```kotlin
  @Entity(tableName = "local_to_canon", indices = [Index(value=["local_id","local_tag"], unique=true)])
  data class LocalIdToCanonId(
      @PrimaryKey(autoGenerate=true) val id: Long,
      val local_id: Int,
      val local_tag: String?,
      val canon_id: String,
      val created_ts: Long
  )
  ```
- Create `storage/NotificationMapDao.kt`:
  - `putMirror(canonId, originDevice, localId, localTag)` — inserts both rows in transaction.
  - `lookupByLocal(localId, localTag): String?` — returns canon_id.
  - `lookupOrigin(canonId): String?` — returns origin device id.
  - `deleteByCanonId(canonId)` — removes both rows.
  - `sweepExpired(cutoffMs)` — deletes rows with `created_ts < cutoffMs`.
- Update `mobile/modules/twinotify-core/android/build.gradle` — add Room deps + KSP plugin.

**TDD (JVM-runnable):**

- `NotificationMapDaoTest.kt` via `Room.inMemoryDatabaseBuilder`:
  - Round-trip `putMirror` + `lookupByLocal` returns canon_id.
  - `deleteByCanonId` removes both rows.
  - `sweepExpired` with cutoff in the future deletes all; in the past deletes none.
  - Null `local_tag` handled.

**Commit:** `feat(mobile/storage): Room-backed notification state maps`

---

## Task 3 — `TwinotifyNotificationListener`

**Files:**

- Create `co/twinotify/core/listener/ReasonCodeFilter.kt` — pure function:
  ```kotlin
  sealed class FilterResult { object Suppress: FilterResult(); object NoEmit: FilterResult(); data class Emit(val reason: String): FilterResult() }
  fun filter(isOwnPackage: Boolean, canonInPending: Boolean, removalReason: Int): FilterResult { ... }
  ```
  Implements spec §4.1 truth table.
- Create `listener/CanonIdBuilder.kt`:
  ```kotlin
  fun build(originDevice: String, pkg: String, id: Int, tag: String?): String =
      "$originDevice:$pkg:$id:${tag ?: ""}"
  ```
- Create `listener/NotifPostBuilder.kt` — `StatusBarNotification → NotifPostJson?` — extracts title/text/sub_text/big_text/small_icon bitmap (96×96 PNG capped), applies privacy filter (spec §4.7.3: drop `VISIBILITY_SECRET`, Android Auto categories, gearhead package, denylisted packages). Returns null if filtered.
- Create `listener/PendingPeerCancel.kt` — `ConcurrentHashMap<String, Long>` with `add(canon, expiryMs)`, `consume(canon): Boolean`, `sweep()` called from SyncService's coroutine scope.
- Create `listener/TwinotifyNotificationListener.kt` — extends `NotificationListenerService`. Injects `NotificationMapDao`, `PendingPeerCancel`, `OutboundQueue`, `DenylistLoader`. Implements `onNotificationPosted` + `onNotificationRemoved` per spec truth table.
- Modify `mobile/modules/twinotify-core/android/src/main/AndroidManifest.xml`:
  ```xml
  <manifest>
    <application>
      <service
        android:name="co.twinotify.core.listener.TwinotifyNotificationListener"
        android:label="@string/app_name"
        android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        android:exported="true">
        <intent-filter>
          <action android:name="android.service.notification.NotificationListenerService"/>
        </intent-filter>
      </service>
    </application>
  </manifest>
  ```

**Spec §4.1 truth table (encoded in `ReasonCodeFilterTest`):**

| isOwnPkg | canonInPending | reason | expected |
|----------|----------------|--------|----------|
| true | true | any | Suppress |
| true | false | any | Emit(user_swipe) |
| false | true | any | Suppress |
| false | false | 1 | Emit(user_click) |
| false | false | 2, 3 | Emit(user_swipe) |
| false | false | 8, 9 | Emit(app_cancel) |
| false | false | 4, 6, 10, 12, 13, 14 | NoEmit |

**TDD:**

- `ReasonCodeFilterTest.kt` — parametric over all 14 reason codes × 4 input combinations.
- `CanonIdBuilderTest.kt` — null tag, empty tag, colon-bearing tag behaviors.
- `NotifPostBuilderTest.kt` — MockK on `StatusBarNotification`; verify title/text extracted, `VISIBILITY_SECRET` filtered, Android Auto category filtered, denylisted package filtered, `small_icon_png` base64 size.

Listener class itself requires instrumented test; no emulator → DONE_WITH_CONCERNS acceptable per user convention.

**Commit:** `feat(mobile/listener): NotificationListenerService with reason-code filter + privacy guard`

---

## Task 4 — `SyncService` (Always-Connected mode only)

**Scope cut from spec §4.2:** Always-Connected only. State machine this phase: `STARTING → CONNECTING → CONNECTED → OFFLINE_QUEUED → CONNECTED`. No `IDLE_DEMOTED` (defers to Phase 9).

**Files:**

- Create `service/SyncService.kt`:
  - Extends `Service` (not `LifecycleService`; stays minimal).
  - `onStartCommand`: builds channel, `startForeground(FGS_ID, notif, FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)`.
  - `SupervisorJob` + `CoroutineScope(Dispatchers.IO)` for WebSocket loop.
  - OkHttp WebSocket with exp-backoff reconnect 1s→60s cap, 30s keepalive (spec §4.4).
  - JWT minted via existing `JwtMinter.mint` before connect, presented in `Authorization: Bearer`.
  - Inbound `onMessage` → `InboundDispatcher.handle(text)`.
  - Outbound: consumes `OutboundQueue` — drains pending on connect, then streams new posts.
- Create `service/OutboundQueue.kt`:
  - Room-backed FIFO: `OutboundEvent(@PrimaryKey autoGen id, ciphertext_b64, nonce_b64, msg_id, created_ts)`.
  - Bounded to 1000 rows (spec §7); insert with `OnConflictStrategy.REPLACE` + pre-insert cap check that drops oldest.
  - `enqueue(env: EncryptedEnvelope)`, `drain(limit): List<OutboundEvent>`, `ack(id: Long)`.
- Create `service/InboundDispatcher.kt`:
  - Parse envelope JSON.
  - Check `ReplayGuard.checkAndAdd(msg_id)` — drop duplicate.
  - Decrypt via `Encrypter.decrypt(ct, nonce, peer.encPubkey, ownBoxSecret)`.
  - Parse inner JSON; dispatch by `type`: `notif.post` → `MirrorPoster.post(json)`, `notif.update` → same (idempotent on canon_id), `notif.cancel` → `MirrorDismisser.dismiss(canon_id)`, `ack` → noop.
  - Unknown inner type → log + drop.
- Create `service/MirrorPoster.kt`:
  - Builds `NotificationCompat.Builder` with channel `mirrored_notifications`, `VISIBILITY_PRIVATE`, `IMPORTANCE_DEFAULT`.
  - `contentIntent` = `PendingIntent.getBroadcast(ctx, reqCode, MirrorTapIntent, FLAG_IMMUTABLE)`.
  - `setAutoCancel(true)` (spec §4.7.1).
  - Generates `localId = Random.nextInt()`, `localTag = "mirror"` + canon_id-hash suffix.
  - Calls `NotificationManager.notify(localTag, localId, notif)`.
  - After post, writes `NotificationMapDao.putMirror(canonId, originDevice, localId, localTag)`.
  - Bitmap decoding from base64 for small/large icons; bitmap size guard (96/256 px).
- Create `service/MirrorDismisser.kt`:
  - Look up `canonId → (localId, localTag)` via `NotificationMapDao`. If missing (process-death gap), log and return.
  - `pendingPeerCancel.add(canonId, now + 30_000)`.
  - `NotificationManager.cancel(localTag, localId)`.
  - Listener's `onNotificationRemoved` self-package branch consumes tombstone.
- Create `service/NotifChannelSetup.kt`:
  - Channel `mirrored_notifications`, importance `DEFAULT` (spec §4.2.1).
  - Channel `twinotify_fgs_status`, importance `LOW` — ongoing FGS status notification.
  - Called from `SyncService.onCreate`.
- Create `service/BootReceiver.kt`:
  - `RECEIVE_BOOT_COMPLETED` → if pair exists + service mode = Always-Connected → `startForegroundService(Intent(ctx, SyncService::class.java))`.
- Create `service/SyncServiceStatus.kt`:
  - `object SyncServiceStatus { val state: MutableStateFlow<Status>; val queuedCount: MutableStateFlow<Int> }`. Module's `getSyncStatus` reads these; TS receives updates via event channel.
- Create `service/MirrorTapReceiver.kt`:
  - BroadcastReceiver fired by mirror tap → encrypts `notif.cancel(reason=user_click)` → enqueues.
- Modify `mobile/android/app/src/main/AndroidManifest.xml`:
  ```xml
  <uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
  <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" tools:ignore="CoarseFineLocation" />
  <uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
  ```
  Add `<service android:name="co.twinotify.core.service.SyncService" android:foregroundServiceType="remoteMessaging" android:exported="false" />` and `<receiver android:name="co.twinotify.core.service.BootReceiver" android:exported="true"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter></receiver>`.

**TDD:**

- `OutboundQueueTest.kt` — FIFO ordering, 1000-cap drops oldest, persistence across DB reopen.
- `MirrorPosterTest.kt` — MockK `NotificationManager`; verify channel id, visibility, title, icon base64 decoded; `putMirror` called.
- `MirrorDismisserTest.kt` — missing canon → no-op; tombstone inserted with 30s expiry.
- `ReplayGuardTest.kt` (existing) unchanged.
- Service lifecycle tests are instrumented-only; DONE_WITH_CONCERNS ok.

**Commit:** `feat(mobile/service): SyncService FGS + outbound queue + mirror poster/dismisser`

---

## Task 5 — Native module lifecycle + status exposure

**Files:**

- Modify `TwinotifyCoreModule.kt` — add AsyncFunctions:
  - `startSyncService(relayUrl: String)`
  - `stopSyncService()`
  - `isNotificationListenerGranted(): Boolean`
  - `openListenerSettings()`
  - `requestPostNotifPermission(): Boolean` (API 33+)
  - `getSyncStatus(): { state: string, queuedCount: number }`
  - `subscribeSyncStatus()` — starts forwarding `SyncServiceStatus` updates via `sendEvent("onSyncStatus", ...)`
- Create `mobile/hooks/useTwinotifyCore.ts` — TS wrapper with typed signatures.
- Create `mobile/hooks/useSyncStatus.ts` — subscribes to `onSyncStatus` event + returns `{ state, queuedCount }`.
- Create `mobile/types/twinotify.d.ts`:
  ```ts
  export type SyncState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'OFFLINE_QUEUED';
  export type PeerRecord = { deviceId: string; encPubkey: string; signPubkey: string };
  export type PairPayload = { relayUrl: string; deviceId: string; encPubkey: string; signPubkey: string; pairToken: string };
  ```

**TDD:**

- Pure Kotlin status-enum transition test.
- TS typecheck green (`npx tsc --noEmit`).

**Commit:** `feat(mobile): expose sync service lifecycle + status from native module`

---

## Task 6 — *merged into Task 5*

---

## Task 7 — Relay: push confirmation_sig to Device A via WebSocket

**Why:** MEMORY §10 gap — Device A's `confirmation_sig` currently transferred out-of-band (user copies between phones). This task closes the gap.

**Files:**

- Create `proto/pair-sig.schema.json`:
  ```json
  {
    "$id": "https://twinotify.app/schemas/pair-sig.schema.json",
    "type": "object",
    "required": ["v","type","pair_token","confirmation_sig"],
    "properties": {
      "v": {"const": 1},
      "type": {"const": "pair.sig"},
      "pair_token": {"type":"string"},
      "confirmation_sig": {"type":"string", "contentEncoding":"base64"}
    }
  }
  ```
- Modify `relay/internal/server/ws.go`:
  - Expose `Hub.clientFor(deviceId string) *client` (surgical — do NOT touch safety infrastructure: SetReadLimit, SetPongHandler, sync.Mutex writes, ping goroutine, per MEMORY §11).
- Modify `relay/internal/server/pair.go`:
  - On successful `/pair/complete`, look up Device A's live ws client via `hub.clientFor(aDeviceId)`. If present: send `{"v":1,"type":"pair.sig","pair_token":"...","confirmation_sig":"..."}`. If absent (Device A offline): persist into pair record for retrieval on next A reconnect.
- Modify `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/pairing/PairProtocol.kt`:
  - `initiate(...)` — now opens a WS to relay alongside the HTTP `/pair/init` call, listens for `pair.sig` push, resolves to the sig when received.
- Modify `TwinotifyCoreModule.kt`:
  - `startPairInitiator(relayUrl)` — becomes reactive. Returns a payload map AND emits an `onPairSig` event when `pair.sig` arrives.

**TDD (Go):**

- `pair_push_test.go` — boot test server with hub, open WS as Device A, HTTP `/pair/init`, separate HTTP `/pair/complete` from Device B, assert Device A's WS receives `pair.sig` frame within 100ms.

**Commit:** `feat(relay): push confirmation_sig to Device A on /pair/complete`

---

## Task 8 — Denylist startup integrity check

**Files:**

- Create `mobile/modules/twinotify-core/android/src/main/assets/default-denylist.json`:
  ```json
  {
    "version": 1,
    "packages": [
      "com.google.android.apps.authenticator2",
      "com.authy.authy",
      "com.microsoft.azure.authenticator",
      "com.cisco.duo",
      "com.chase.sig.android",
      "com.americanexpress.android.acctsvcs.us",
      "com.bankofamerica.cashpromobile",
      "com.wf.wellsfargomobile",
      "com.paypal.android.p2pmobile",
      "com.venmo",
      "com.discover.mobile.banking",
      "com.lastpass.lpandroid",
      "com.onepassword.android",
      "com.bitwarden.authenticator",
      "com.dashlane.dashlanephonefinal"
    ]
  }
  ```
- Create `co/twinotify/core/filter/DenylistLoader.kt`:
  ```kotlin
  object DenylistLoader {
    private const val EXPECTED_SHA256_HEX = "<compute-and-paste>"
    fun load(ctx: Context): Set<String> {
      val bytes = ctx.assets.open("default-denylist.json").use { it.readBytes() }
      val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
      if (sha != EXPECTED_SHA256_HEX) throw SecurityException("denylist integrity check failed")
      return JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("packages").let { arr ->
        buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
      }
    }
  }
  ```
- Hook: `NotifPostBuilder` consults `DenylistLoader.load(ctx)` (cached on first call). `TwinotifyCoreModule.onCreate` eagerly triggers load to fail-fast on tampered APKs.

**TDD:**

- `DenylistLoaderTest.kt` (JVM via robolectric stub for `AssetManager`):
  - Valid asset + matching hash → returns populated set.
  - Tampered asset → throws `SecurityException`.
  - Lookup `contains("com.authy.authy")` returns true.

**Commit:** `feat(mobile/filter): denylist integrity check with SHA-256 gate`

---

## Task 9 — Design tokens + primitives in React Native

**Port the design bundle to RN-compatible TS components.** Design is HTML/CSS; RN needs StyleSheet + `<View>`/`<Text>` + `react-native-svg`. Token shape stays identical.

**Files:**

- Create `mobile/components/tokens.ts`:
  - Port `tokens.jsx` to TS. `twTheme({hue, dark})` returns the same shape.
  - Export `TW_FONTS`, `TW_HUES`, `TW_NEUTRALS`, `TW_SEMANTIC`, `TW_SPACE`, `TW_RADIUS`, `TW_SHADOW`, `TW_TYPE`, `twBuildPalette`, `twTheme`.
  - **Caveat:** `oklch()` is not supported in RN StyleSheet (`backgroundColor`). Fall back: precompute oklch → hex via a tiny runtime conversion (use `culori` or hand-rolled oklch-to-srgb) at theme build time. Cache converted values so each `twTheme` call is cheap.
- Create `mobile/components/Theme.tsx`:
  - `ThemeContext = React.createContext<Theme>(twTheme({dark:false}))`.
  - `<ThemeProvider>` with `useColorScheme()` (Expo) + user-overridable hue stored in AsyncStorage.
  - `useTheme()` hook.
- Create `mobile/components/primitives/TwLogo.tsx` — SVG port of the 3 variants (pair/dots/monogram).
- Create `mobile/components/primitives/TwWordmark.tsx` — `<TwLogo/>` + `<Text>twin<Text accent>otify</Text></Text>`.
- Create `mobile/components/primitives/TwStatusDot.tsx` — animated pulse uses `react-native-reanimated` already in deps.
- Create `mobile/components/primitives/TwDeviceChip.tsx`, `TwAppChip.tsx`.
- Create `mobile/components/primitives/TwFingerprint.tsx` — monospace grid, 16 groups of 4.
- Create `mobile/components/primitives/TwQR.tsx` — accept a raw string (pair payload JSON), render a REAL QR code via `react-native-qrcode-svg` (NOT the design's mock svg — the design's QR is decorative, not scannable). Overlay the mirror glyph at center per design.
- Create `mobile/components/primitives/TwButton.tsx` — variants primary/accent/secondary/ghost/destructive, sizes sm/md/lg.
- Create `mobile/components/primitives/TwCard.tsx` — tones default/raised/fill/accent/warn/danger.
- Create `mobile/components/primitives/TwSwitch.tsx`.
- Create `mobile/components/primitives/TwRow.tsx`, `TwBanner.tsx`, `TwEmpty.tsx`, `TwSpinner.tsx`.
- Create `mobile/components/primitives/TwIcon.tsx` — port icon registry.
- Create `mobile/components/primitives/index.ts` — barrel export.

**New deps:**

```bash
npm i react-native-svg react-native-qrcode-svg culori
npm i expo-camera expo-font @expo-google-fonts/inter @expo-google-fonts/jetbrains-mono
# react-navigation if not already in expo-router base
```

**TDD:**

- `npx tsc --noEmit` green.
- `npx expo-doctor` green.
- Render smoke on device (user-run).

**Commit:** `feat(mobile/ui): design tokens + primitive components port`

---

## Task 10 — Onboarding screens

**Files (one per design screen, Expo Router file-based):**

- `mobile/app/_layout.tsx` — root Stack navigator; ThemeProvider wrapping.
- `mobile/app/index.tsx` — routes: if `hasCompletedOnboarding && isPaired` → redirect to `/home`; else `/onboarding/welcome`.
- `mobile/app/onboarding/welcome.tsx` — ScreenWelcome (design line 60–101). Hero mark + wordmark + tagline + "Get started" button.
- `mobile/app/onboarding/how.tsx` — ScreenHow (design 102–180). 3-step explainer with icons.
- `mobile/app/onboarding/role.tsx` — ScreenRole (181–233). Initiator vs Joiner selection.
- `mobile/app/onboarding/relay.tsx` — ScreenRelay (234–284). Relay URL input + default suggestion.
- `mobile/app/onboarding/perms.tsx` — ScreenPerms (285–328). NLS + POST_NOTIFICATIONS + FGS grants, each with "Grant" button wired to `useTwinotifyCore`.
- `mobile/app/onboarding/oem.tsx` — ScreenOEM (329–368). Device-manufacturer-specific reliability tips. Deep-links are placeholder (Phase 9 wires dontkillmyapp URLs).
- `mobile/app/onboarding/ready.tsx` — ScreenReady (369–393). Progression "Ready to pair" → routes to pair QR/scan based on role.
- Add onboarding completion flag to AsyncStorage so returns skip onboarding.

**TDD:**

- TS compile.
- Device smoke: all 7 screens reachable via "Next", "Back" works on each, permission grants actually call native functions.

**Commit:** `feat(mobile/ui): onboarding flow (welcome → ready)`

---

## Task 11 — Pairing screens

**Files:**

- `mobile/app/pair/qr.tsx` — ScreenPairQR (394–433). Displays QR (real, `react-native-qrcode-svg`) from `startPairInitiator`'s payload. Awaits `onPairSig` event (Task 7) — routes to fingerprint on arrival.
- `mobile/app/pair/scan.tsx` — ScreenPairScan (434–501). `expo-camera` barcode scanner. On scan, parses QR payload → `deviceBCompletePairing`.
- `mobile/app/pair/fingerprint.tsx` — ScreenPairFP (502–547). Displays local + peer fingerprint via `TwFingerprint`; user compares + confirms.
- `mobile/app/pair/success.tsx` — ScreenPairSuccess (548–573). Stores peer pubkeys + routes to Home.
- `mobile/app/pair/fail.tsx` — ScreenPairFail (574–596). Retry or unpair.

**Permissions:** camera permission requested inline via `Camera.useCameraPermissions` before Scan screen renders.

**TDD:** TS compile + device smoke end-to-end pair on 2 devices.

**Commit:** `feat(mobile/ui): pairing flow (QR → fingerprint → success)`

---

## Task 12 — Home + Settings + Filter

**Files:**

- `mobile/app/home.tsx` — ScreenHome (597–739). Connection status (`TwStatusDot` + `TwStatusLabel`), peer device chip, recent mirrors list (Phase 3 shows latest 20 from a small in-memory ring buffer emitted over `onMirrorEvent`), start/stop service buttons.
- `mobile/app/settings/index.tsx` — ScreenSettings (740–809). Rows: pair detail, app filter, notifications channel settings (deep-link to system), theme toggle, unpair.
- `mobile/app/settings/pair.tsx` — ScreenSetPair (810–860). Peer details + fingerprint display + unpair confirmation.
- `mobile/app/filter.tsx` — ScreenFilter (861–937). List of installed apps (via `PackageManager` query through a new native function `getInstalledApps`), toggle per-app allow/deny. Default denylist from Task 8 is pre-disabled + locked (user can override via long-press).

**Additional native functions needed (added to TwinotifyCoreModule):**

- `getInstalledApps(): List<{ pkg, name, iconB64 }>` — queries `PackageManager.getInstalledApplications(MATCH_ALL)`.
- `getPerAppOverride(pkg): "allow"|"deny"|null` + `setPerAppOverride(pkg, v)` — DataStore-backed.

**TDD:** TS compile + device smoke.

**Commit:** `feat(mobile/ui): home + settings + filter screens`

---

## Task 13 — End-to-end smoke + regression

**No new files; verification task.** Runs all layered smoke scenarios.

**Scenarios (manual, 2 physical devices):**

1. Fresh install both phones → onboarding → role split → pair via QR → fingerprint confirm → Home shows CONNECTED.
2. Signal message on A → mirror appears on B in <3s with correct title/text.
3. Swipe mirror on B → Signal notif dismisses on A.
4. Trigger new Signal msg on A → swipe on A → mirror dismisses on B.
5. Force-stop app on A → post 3 notifications → restart → queued events drain, mirrors appear on B.
6. Trigger Authy notification on A → NOT mirrored (denylist).
7. Repackage APK with altered denylist → app aborts on start.
8. Open Settings → Unpair → both devices show "Not paired" → re-pair works.
9. Dark mode toggle → all screens respect.
10. Hue tweak in settings → accent color changes across app.

**Commit:** `test(phase-3): end-to-end smoke scenarios documented in test-scenarios.md`

---

## Verification checklist (phase-3 merge gate)

- [ ] Task 0 grep audit: zero `phonesync`/`phone-sync` in source code.
- [ ] `cd relay && go test ./...` green (including `pair_push_test`).
- [ ] `cd mobile && npx tsc --noEmit` green.
- [ ] `cd mobile && npx expo-doctor` green.
- [ ] `cd mobile/modules/twinotify-core/android && ./gradlew test` green (JVM unit tests).
- [ ] Instrumented tests (`./gradlew connectedAndroidTest`) — green OR documented DONE_WITH_CONCERNS if no emulator.
- [ ] CI green on PR (`.github/workflows/relay.yml`, `mobile.yml`).
- [ ] Manual 2-device smoke (Task 13 scenarios 1–10) all pass.
- [ ] Spec §4.1 truth table unit tests 100% pass.
- [ ] Denylist integrity gate firing-on-tamper verified.
- [ ] Phase 3 branch rebased clean onto main; commits bisectable.

---

## Critical invariants Phase 3 MUST preserve (MEMORY §11)

- Schema `$id` === validator `schemaBaseURL` byte-for-byte (now `https://twinotify.app/schemas/`).
- libsodium JNA calls snake_case (`crypto_box_easy`, `crypto_sign_detached`).
- Ed25519 secret key is 64 bytes; `JwtMinter` guard intact.
- JTI cache retention 2×TTL on relay.
- Nonce counter monotonic; only reset on `unpair()` or `regenerate()`.
- WS safety infrastructure (SetReadLimit, SetPongHandler, sync.Mutex writes, ping goroutine) preserved through Task 7 surgical edits.
- Dockerfile build context = repo root.
- `ed25519.PublicKey(pairStore.SignPubkeyFor(sub))` cast required on relay verification.

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Task 0 misses a source site → build breaks | Med | High | Grep audit in verification checklist; CI gate |
| DataStore rename wipes pair state mid-test | Cert | Low | Expected; document in Task 0 commit; re-pair manually |
| Schema `$id` mismatch after domain change → validator drops all | Med | High | Envelope validation test runs after rename |
| NLS loop bug (missing self-package filter) → infinite mirror | Low | Critical | `ReasonCodeFilterTest` + 2-phone manual smoke before merge |
| FGS type declaration missing on API 34+ → SecurityException | Low | High | Manifest verified in Task 4; dev-client tested |
| Mirror tap `PendingIntent` leaks app context | Low | Med | Use `FLAG_IMMUTABLE` + `FLAG_UPDATE_CURRENT`; receiver registered in manifest |
| `oklch()` not supported in RN → palette renders wrong | Med | Med | Runtime oklch→srgb conversion cached at theme build; TS tests verify hex output |
| Full Tier-1 UI exceeds phase budget | High | Med | Scope-cut onboarding polish animations if time-constrained; ship functional-first UI |
| Rooming camera permission denied → pairing broken | Low | Med | `expo-camera` prompt integrated into ScreenPairScan with fallback "Paste QR payload" path |
| Room migration bugs | Low | Med | Phase 3 DB is version 1; document migration strategy for Phase 4 changes |

---

## Design-to-RN adaptation notes

The design bundle is HTML/CSS prototyping — faithful visual reproduction, not structural copy.

**Direct ports:**
- Token shape (`twTheme`) — 1:1 in `tokens.ts`.
- Primitives API — 1:1, just swap `<span>` → `<View>`, `<div>` → `<View>`, CSS style → RN StyleSheet.
- Color values — all as computed hex (convert oklch once).
- Spacing scale, radii, shadows — all direct.

**Adaptations:**
- `box-shadow` → RN `shadowColor`, `shadowOffset`, `shadowOpacity`, `elevation` (android).
- CSS `@keyframes` pulse → `react-native-reanimated` `withRepeat(withTiming(...))`.
- `color-mix(in oklch, ...)` → pre-compute at theme time with culori blend.
- Fonts: load via `expo-font` from `@expo-google-fonts/inter` + `@expo-google-fonts/jetbrains-mono`.
- QR code — design's decorative SVG is replaced with real `react-native-qrcode-svg` output; keep the center mirror glyph overlay.
- Camera view for Scan screen — `expo-camera` + barcode mode; overlay the design's viewfinder corners on top.
- Status bar + nav bar — RN handles these natively; design's `TwStatusBar`/`TwNavBar` mocks not needed in production (only in onboarding hero mocks).

**Do NOT port:**
- The `window.__twTheme` global — use React context (`useTheme`).
- Inline `style={{...}}` objects everywhere — use `StyleSheet.create` where static, context-dependent inline where theme-dependent (standard RN pattern).
- `<button onMouseDown>` haptics — use `Pressable` + `expo-haptics`.

---

## Commit sequence summary

```
Task 0: refactor: rebrand internal identifiers phonesync → twinotify
Task 1: feat(proto): encrypted envelope + ack schemas
Task 2: feat(mobile/storage): Room-backed notification state maps
Task 3: feat(mobile/listener): NotificationListenerService with reason-code filter + privacy guard
Task 4: feat(mobile/service): SyncService FGS + outbound queue + mirror poster/dismisser
Task 5: feat(mobile): expose sync service lifecycle + status from native module
Task 7: feat(relay): push confirmation_sig to Device A on /pair/complete
Task 8: feat(mobile/filter): denylist integrity check with SHA-256 gate
Task 9: feat(mobile/ui): design tokens + primitive components port
Task 10: feat(mobile/ui): onboarding flow (welcome → ready)
Task 11: feat(mobile/ui): pairing flow (QR → fingerprint → success)
Task 12: feat(mobile/ui): home + settings + filter screens
Task 13: test(phase-3): end-to-end smoke scenarios
```

Each task is a single bisectable commit. Branch: `phase-3-listener-first-mirror`. Merge to main after all checklist items pass.
