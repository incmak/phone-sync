package co.twinotify.core.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationBrandResourceTest {
    private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
    private val sourceRoot = File(projectDir, "src/main/java/co/twinotify/core")

    @Test
    fun everyProductionNotificationUsesTheTwinotifySmallIcon() {
        val sources = listOf(
            File(sourceRoot, "service/MirrorPoster.kt").readText(),
            File(sourceRoot, "service/ForegroundNotificationFactory.kt").readText(),
            File(sourceRoot, "call/CallStateMaterializer.kt").readText(),
        )

        sources.forEach { source ->
            assertTrue(source.contains("R.drawable.ic_stat_twinotify"))
            assertFalse(source.contains("android.R.drawable.ic_dialog_info"))
            assertFalse(source.contains("android.R.drawable.stat_sys_data_bluetooth"))
        }
    }

    @Test
    fun statusIconIsAnOpaqueMonochromeVectorOnTransparentGround() {
        val vector = File(projectDir, "src/main/res/drawable/ic_stat_twinotify.xml").readText()

        assertTrue(vector.contains("<vector"))
        assertTrue(vector.contains("android:fillColor=\"#FFFFFFFF\""))
        assertFalse(vector.contains("gradient"))
        assertFalse(vector.contains("fillAlpha"))
    }

    @Test
    fun mirrorFallsBackFromLargeArtworkToCapturedSmallArtwork() {
        val source = File(sourceRoot, "service/MirrorPoster.kt").readText()

        assertTrue(source.contains("val sourceArtwork = largeIcon ?: smallIcon"))
        assertTrue(source.contains("setLargeIcon(sourceArtwork)"))
    }
}
