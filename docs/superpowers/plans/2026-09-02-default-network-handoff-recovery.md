# Default Network Handoff Recovery Implementation Plan

**Goal:** Restart Twinotify's active transport generation when Android changes the default network so a mobile-data-to-Wi-Fi handoff recovers without reopening or restarting the app.

**Architecture:** A service-owned Android default-network observer snapshots the current `Network`, ignores the registration callback for that same network, and emits once when a different usable default network becomes available. `SyncService` sends that event through the existing `SerializedTransportRestarter`, which already cancels and joins the old coordinator before starting the replacement and therefore preserves the single outbox-drainer invariant.

**Tech Stack:** Kotlin, Android `ConnectivityManager.NetworkCallback`, coroutines, Kotlin/JVM tests, Android emulator.

## Global Constraints

- Do not change pairing state, relay URL policy, route preference, retry delay values, protocol schemas, or Room schema.
- A network handoff must use `SerializedTransportRestarter.forceRestart()`; it must not create another outbox drain path.
- The callback registration must be service-owned, idempotent, and unregistered on every service shutdown path.
- The initial callback for the already-active default network must not tear down a healthy transport.
- Network loss alone may retain the existing bounded retry path; a newly available default network must force a fresh transport generation immediately.
- Do not add dependencies or expose network identifiers through public status, logs, or evidence.

---

### Task 1: Define and test default-network transition policy

**Files:**
- Create: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/DefaultNetworkChangeObserver.kt`
- Create: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/DefaultNetworkChangeObserverTest.kt`

**Interfaces:**
- Consumes: Android's current `ConnectivityManager.activeNetwork` and later `NetworkCallback.onAvailable` / `onLost` callbacks.
- Produces: `DefaultNetworkChangeGate(initialNetwork: Any?)`, with `onAvailable(network: Any): Boolean` and `onLost(network: Any)`, plus `observeDefaultNetworkChanges(context: Context, onNetworkChanged: () -> Unit): Closeable`.

- [x] **Step 1: Write the failing transition tests**

```kotlin
class DefaultNetworkChangeObserverTest {
    @Test
    fun initialDefaultNetworkCallbackDoesNotRequestRestart() {
        val initial = Any()
        val gate = DefaultNetworkChangeGate(initial)

        assertFalse(gate.onAvailable(initial))
    }

    @Test
    fun newDefaultNetworkRequestsExactlyOneRestart() {
        val gate = DefaultNetworkChangeGate(Any())
        val replacement = Any()

        assertTrue(gate.onAvailable(replacement))
        assertFalse(gate.onAvailable(replacement))
    }

    @Test
    fun firstNetworkAfterOfflineRequestsRestart() {
        val initial = Any()
        val gate = DefaultNetworkChangeGate(initial)
        gate.onLost(initial)

        assertTrue(gate.onAvailable(Any()))
    }
}
```

- [x] **Step 2: Run the focused test and observe RED**

Run from `mobile/android`:

```bash
./gradlew :twinotify-core:testDebugUnitTest --tests co.twinotify.core.service.DefaultNetworkChangeObserverTest
```

Expected: compilation fails because `DefaultNetworkChangeGate` does not exist.

- [x] **Step 3: Implement the minimal transition gate and Android observer**

```kotlin
internal class DefaultNetworkChangeGate(initialNetwork: Any?) {
    private val monitor = Any()
    private var currentNetwork: Any? = initialNetwork

    fun onAvailable(network: Any): Boolean = synchronized(monitor) {
        if (currentNetwork == network) return@synchronized false
        currentNetwork = network
        true
    }

    fun onLost(network: Any) = synchronized(monitor) {
        if (currentNetwork == network) currentNetwork = null
    }
}

internal fun observeDefaultNetworkChanges(
    context: Context,
    onNetworkChanged: () -> Unit,
): Closeable {
    val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
        ?: return Closeable {}
    val gate = DefaultNetworkChangeGate(connectivity.activeNetwork)
    val closed = AtomicBoolean(false)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!closed.get() && gate.onAvailable(network) && !closed.get()) onNetworkChanged()
        }

        override fun onLost(network: Network) {
            if (!closed.get()) gate.onLost(network)
        }
    }
    return try {
        connectivity.registerDefaultNetworkCallback(callback)
        Closeable {
            if (closed.compareAndSet(false, true)) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
    } catch (_: Throwable) {
        closed.set(true)
        Closeable {}
    }
}
```

- [x] **Step 4: Run the focused test and observe GREEN**

Run the Step 2 command again.

Expected: all three tests pass.

### Task 2: Wire the observer to the serialized transport owner

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt`

**Interfaces:**
- Consumes: `observeDefaultNetworkChanges(...)` from Task 1 and existing `SerializedTransportRestarter.forceRestart()`.
- Produces: exactly one service-owned callback registration while transport is enabled; shutdown closes it before coordinator jobs are cancelled.

- [x] **Step 1: Add a failing source-wiring lifecycle test**

```kotlin
@Test
fun serviceOwnsDefaultNetworkHandoffRecoveryForItsFullTransportLifetime() {
    val sourceRoot = File(System.getProperty("user.dir"), "src/main/java/co/twinotify/core")
    val observer = File(sourceRoot, "service/DefaultNetworkChangeObserver.kt").readText()
    val service = File(sourceRoot, "service/SyncService.kt").readText()
    val start = service.substringAfter("onStart = {").substringBefore("START_STICKY")
    val destroy = service.substringAfter("override fun onDestroy()").substringBefore("override fun onBind")
    val unpair = service.substringAfter("private suspend fun shutdownForUnpair").substringBefore("private fun startTransport")
    val actionStop = service.substringAfter("private suspend fun finalizeActionStop").substringBefore("private fun stopCallCapture")

    assertTrue(observer.contains("registerDefaultNetworkCallback"))
    assertSourceOrder(start, "ensureDefaultNetworkObserver()", "routePreferenceRestarter.ensureStarted()")
    assertTrue(destroy.contains("stopDefaultNetworkObserver()"))
    assertTrue(unpair.contains("stopDefaultNetworkObserver()"))
    assertTrue(actionStop.contains("stopDefaultNetworkObserver()"))
}
```

- [x] **Step 2: Run the focused lifecycle test and observe RED**

```bash
./gradlew :twinotify-core:testDebugUnitTest --tests co.twinotify.core.service.ServiceLifecycleTest.serviceOwnsDefaultNetworkHandoffRecoveryForItsFullTransportLifetime
```

Expected: failure because `SyncService` does not own or close a default-network observer.

- [x] **Step 3: Add the minimal service lifecycle wiring**

Add one `Closeable?` field and idempotent helpers:

```kotlin
private var defaultNetworkObserver: Closeable? = null

private fun ensureDefaultNetworkObserver() {
    if (defaultNetworkObserver != null) return
    defaultNetworkObserver = observeDefaultNetworkChanges(applicationContext) {
        routePreferenceRestarter.forceRestart()
    }
}

private fun stopDefaultNetworkObserver() {
    defaultNetworkObserver?.close()
    defaultNetworkObserver = null
}
```

Call `ensureDefaultNetworkObserver()` immediately before `routePreferenceRestarter.ensureStarted()` in the accepted start path. Call `stopDefaultNetworkObserver()` in `onDestroy`, `shutdownForUnpair`, and `finalizeActionStop` before cancelling the route-preference owner.

- [x] **Step 4: Run both focused test classes and observe GREEN**

```bash
./gradlew :twinotify-core:testDebugUnitTest --tests co.twinotify.core.service.DefaultNetworkChangeObserverTest --tests co.twinotify.core.service.ServiceLifecycleTest
```

Expected: both classes pass, including existing serialized-restarter and lifecycle tests.

### Task 3: Make transport generation shutdown cancellation-safe

**Files:**
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt`
- Modify: `mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveServiceTransportTest.kt`
- Modify: `mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt`

**Finding:** The first emulator handoff run proved that the callback fired but
the route generation remained at 1. Focused, content-free cleanup tracing then
showed two cancellation hazards: a cancelled service loop could skip joining
its health publisher, and a pre-auth relay failure could suspend forever while
publishing `Closed` from `NonCancellable` after its adapter stopped collecting.

- [x] **Step 1: Add a failing service-loop cleanup regression**

Assert that cancelling `LiveServiceTransportLoop` finishes both the coordinator
and health publisher. The test failed before `healthPublisher.cancelAndJoin()`
was protected by `withContext(NonCancellable)`.

- [x] **Step 2: Re-run the emulator transition and isolate the remaining relay cleanup stall**

The callback and service-loop cleanup completed, but the generation still did
not advance. Temporary local tracing proved every explicit relay resource was
closed while the terminal flow emission remained suspended; all tracing was
removed from the final build.

- [x] **Step 3: Add a failing pre-auth relay cleanup regression**

`RelayTransportTest.preAuthFailureCollectorCancellationCompletesRelayCleanup`
failed because cleanup did not return after a route adapter rejected a
pre-authentication failure.

- [x] **Step 4: Keep resource cleanup non-cancellable and terminal delivery cancellable**

Close the raw channel, workers, and socket inside `NonCancellable`, then publish
the optional `Closed` event in the original caller context. The new regression
and the existing ordered normal-close test both pass.

### Task 4: Verify the handoff and record product status

**Files:**
- Modify: `docs/product-backlog.md`
- Modify: `docs/superpowers/plans/2026-09-02-default-network-handoff-recovery.md`

**Interfaces:**
- Consumes: the stable debug signer already installed on `emulator-5558` and its content-free debug route evidence.
- Produces: automated verification, an emulator-observed route-generation restart, and an explicit physical two-phone follow-up.

- [x] **Step 1: Run the complete native verification gate**

```bash
./gradlew :twinotify-core:lintDebug :twinotify-core:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` with no failing lint or tests.

- [x] **Step 2: Exercise a default-network transition on the emulator**

Install the fresh debug APK over the same debug signer without clearing data.
Record the content-free route generation, disable emulator data until Android
reports no active default network, restore data so Android creates a replacement
network, and poll until the generation increments. Verify the service remains
enabled and exactly one `SyncService` is active. Restore the emulator's original
radio state after the check.

Expected: the new default network increments `route_generation` without launching Twinotify or tapping Retry; no second service/coordinator is observed.

Observed on `emulator-5558` (Android 17/API 37): the baseline remained at
generation 1 after install, Android then reported no active default network,
and the replacement network advanced Twinotify to generation 2 while paired
and enabled. `dumpsys activity services` reported exactly one `SyncService`.
The fixture's configured relay was unavailable, so route authentication was
not used as the recovery oracle.

- [x] **Step 3: Update backlog truth**

Add a P0 source-complete row for live default-network handoff recovery. Keep physical two-phone mobile-data-to-Wi-Fi and Wi-Fi-to-mobile evidence pending; emulator evidence must not close that physical/OEM requirement.

- [x] **Step 4: Run repository checks and inspect the diff**

```bash
git diff --check
cd mobile && npm run typecheck && npm test -- --runInBand
```

Observed: `git diff --check` and TypeScript typecheck passed; Jest passed
35 suites / 237 tests. The diff also includes the relay/service cancellation
fix and its focused regressions from Task 3.

- [x] **Step 5: Commit the verified fix**

```bash
git add docs/product-backlog.md docs/superpowers/plans/2026-09-02-default-network-handoff-recovery.md mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/DefaultNetworkChangeObserver.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/RelayTransport.kt mobile/modules/twinotify-core/android/src/main/java/co/twinotify/core/service/SyncService.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/DefaultNetworkChangeObserverTest.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/LiveServiceTransportTest.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/RelayTransportTest.kt mobile/modules/twinotify-core/android/src/test/java/co/twinotify/core/service/ServiceLifecycleTest.kt
git commit -m "fix(mobile/service): recover after network handoff"
```
