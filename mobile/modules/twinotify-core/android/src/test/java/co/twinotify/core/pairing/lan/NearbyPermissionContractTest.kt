package co.twinotify.core.pairing.lan

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearbyPermissionContractTest {
    @Test
    fun target36ManifestUsesNearbyPermissionWithoutAndroid17LocalNetworkOptIn() {
        val projectDir = File(requireNotNull(System.getProperty("user.dir")))
        val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertFalse(
            manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"),
            "Target SDK 36 must not opt into the Android 17 local-network permission contract",
        )
    }
}
