package co.twinotify.core.filter

import java.io.File
import org.junit.Test
import kotlin.test.assertContains

class FilterBridgeContractTest {
    @Test
    fun nativeBridgeExposesTheInstalledAppCatalogWithStableFields() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/co/twinotify/core/TwinotifyCoreModule.kt",
        ).readText()

        val bridge = source
            .substringAfter("AsyncFunction(\"getFilterableApps\")")
            .substringBefore("AsyncFunction(\"getUserDenylist\")")

        assertContains(bridge, "InstalledAppCatalog.load")
        assertContains(bridge, "\"packageName\"")
        assertContains(bridge, "\"displayName\"")
        assertContains(bridge, "\"artworkDataUri\"")
        assertContains(bridge, "\"defaultFiltered\"")
        assertContains(bridge, "\"alwaysFiltered\"")
    }
}
