package co.twinotify.core.detail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceAppLauncherTest {
    @Test
    fun installedPackageWithLauncherOpensAtTapTime() {
        val platform = FakeSourceAppPlatform(installed = true, hasLauncher = true, launchSucceeds = true)

        assertEquals(SourceLaunchResult.Launched, SourceAppLauncher(platform).launch("com.example"))
        assertEquals(listOf("installed:com.example", "launcher:com.example", "launch:com.example"), platform.events)
    }

    @Test
    fun missingPackageAndPackageWithoutLauncherAreDistinctFallbacks() {
        assertEquals(
            SourceLaunchResult.PackageMissing,
            SourceAppLauncher(FakeSourceAppPlatform(installed = false)).launch("com.missing"),
        )
        assertEquals(
            SourceLaunchResult.NoLauncher,
            SourceAppLauncher(FakeSourceAppPlatform(installed = true, hasLauncher = false)).launch("com.headless"),
        )
    }

    @Test
    fun uninstallOrLaunchFailureAfterPostFallsBackInsteadOfThrowing() {
        val uninstalled = FakeSourceAppPlatform(installed = true, hasLauncher = true, launchSucceeds = true)
        uninstalled.installed = false
        assertEquals(SourceLaunchResult.PackageMissing, SourceAppLauncher(uninstalled).launch("com.example"))

        assertEquals(
            SourceLaunchResult.LaunchFailed,
            SourceAppLauncher(FakeSourceAppPlatform(installed = true, hasLauncher = true, launchSucceeds = false))
                .launch("com.example"),
        )
    }

    @Test
    fun androidLauncherUsesTheVisibilityIndependentFrontDoorIntentSender() {
        val source = File(
            System.getProperty("user.dir"),
            "src/main/java/co/twinotify/core/detail/SourceAppLauncher.kt",
        ).readText()
        val androidPlatform = source.substringAfter("class AndroidSourceAppPlatform")

        assertTrue(androidPlatform.contains("getLaunchIntentSenderForPackage"))
        assertFalse(androidPlatform.contains("getApplicationInfo"))
        assertFalse(androidPlatform.contains("getLaunchIntentForPackage"))
    }

    private class FakeSourceAppPlatform(
        var installed: Boolean,
        private val hasLauncher: Boolean = false,
        private val launchSucceeds: Boolean = false,
    ) : SourceAppPlatform {
        val events = mutableListOf<String>()

        override fun isInstalled(packageName: String): Boolean {
            events += "installed:$packageName"
            return installed
        }

        override fun hasLauncher(packageName: String): Boolean {
            events += "launcher:$packageName"
            return hasLauncher
        }

        override fun launch(packageName: String): Boolean {
            events += "launch:$packageName"
            return launchSucceeds
        }
    }
}
