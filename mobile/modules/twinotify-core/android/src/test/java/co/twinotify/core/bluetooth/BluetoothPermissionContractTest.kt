package co.twinotify.core.bluetooth

import android.content.pm.ServiceInfo
import co.twinotify.core.service.foregroundServiceTypesFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BluetoothPermissionContractTest {
    private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
    private val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()

    @Test
    fun manifestDeclaresOnlyTheNearbyBluetoothPermissions() {
        val scanElement = Regex(
            """<uses-permission\s+android:name="android\.permission\.BLUETOOTH_SCAN"[^>]*/>""",
        ).find(manifest)
        assertTrue(scanElement != null, "BLUETOOTH_SCAN must be declared")
        assertTrue(
            scanElement.value.contains("""android:usesPermissionFlags="neverForLocation""""),
            "BLUETOOTH_SCAN must assert neverForLocation",
        )
        assertTrue(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_ADVERTISE"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
    }

    @Test
    fun manifestDeclaresTheCompanionDeviceSetupFeature() {
        // CompanionDeviceManager.associate throws IllegalStateException without this, so the
        // association flow fails on every device. Only a device run catches it otherwise.
        val declaration = Regex(
            """<uses-feature\s+android:name="android\.software\.companion_device_setup"\s+android:required="false"\s*/>""",
        ).find(manifest)
        assertNotNull(declaration, "companion device setup must be declared as an optional feature")
    }

    @Test
    fun syncServiceDeclaresRemoteMessagingAndConnectedDevice() {
        val service = Regex(
            """<service\s+android:name="co\.twinotify\.core\.service\.SyncService"[^>]*/>""",
        ).find(manifest)
        assertTrue(service != null, "SyncService must be declared")
        assertTrue(service.value.contains("""android:foregroundServiceType="remoteMessaging|connectedDevice""""))
    }

    @Test
    fun forbiddenPermissionsAreAbsent() {
        val forbidden = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.RECORD_AUDIO",
            "android.permission.BLUETOOTH_PRIVILEGED",
            "android.permission.MANAGE_ONGOING_CALLS",
            "android.permission.BLUETOOTH_ADMIN",
            "android.permission.BLUETOOTH\"",
            "COMPANION_DEVICE_WATCH",
            "DEVICE_PROFILE_WATCH",
            "REQUEST_COMPANION",
        )
        for (name in forbidden) {
            assertFalse(manifest.contains(name), "manifest must not carry $name")
        }
    }

    @Test
    fun bluetoothSourcesUseNoWatchProfileNoSingleDeviceShortcutAndNoClassicSocket() {
        val sources = File(projectDir, "src/main/java/co/twinotify/core/bluetooth")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertTrue(sources.isNotBlank(), "bluetooth package sources must exist")
        for (banned in listOf(
            "setDeviceProfile(",
            "DEVICE_PROFILE_WATCH",
            "setSingleDevice(true)",
            "Rfcomm",
            "00001101-0000-1000-8000-00805f9b34fb",
            "startDiscovery(",
            "cancelDiscovery(",
        )) {
            assertFalse(sources.contains(banned, ignoreCase = true), "bluetooth sources must not use $banned")
        }
    }

    @Test
    fun foregroundServiceTypeAddsConnectedDeviceOnlyWhenTheRouteIsActive() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            foregroundServiceTypesFor(bluetoothRouteActive = false),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            foregroundServiceTypesFor(bluetoothRouteActive = true),
        )
    }
}
