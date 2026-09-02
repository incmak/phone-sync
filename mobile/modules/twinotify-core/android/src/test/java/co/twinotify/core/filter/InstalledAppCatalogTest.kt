package co.twinotify.core.filter

import android.content.pm.ApplicationInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstalledAppCatalogTest {
    @Test
    fun normalizesLauncherAppsWithSelfExcludedAndAudioDefaultFiltered() {
        val catalog = InstalledAppCatalog.normalize(
            candidates = listOf(
                InstalledAppCandidate(
                    packageName = "com.example.music",
                    displayName = "Music",
                    category = ApplicationInfo.CATEGORY_AUDIO,
                ),
                InstalledAppCandidate(
                    packageName = "co.twinotify.app",
                    displayName = "Twinotify",
                    category = ApplicationInfo.CATEGORY_UNDEFINED,
                ),
                InstalledAppCandidate(
                    packageName = "com.example.chat",
                    displayName = "Chat",
                    category = ApplicationInfo.CATEGORY_SOCIAL,
                ),
            ),
            selfPackage = "co.twinotify.app",
        )

        assertEquals(listOf("com.example.chat", "com.example.music"), catalog.map { it.packageName })
        assertFalse(catalog.any { it.packageName == "co.twinotify.app" })
        assertFalse(catalog.single { it.packageName == "com.example.chat" }.defaultFiltered)
        assertTrue(catalog.single { it.packageName == "com.example.music" }.defaultFiltered)
    }

    @Test
    fun deDuplicatesPackagesAndSortsEqualLabelsByPackageName() {
        val catalog = InstalledAppCatalog.normalize(
            candidates = listOf(
                InstalledAppCandidate("com.example.z", "Same", ApplicationInfo.CATEGORY_UNDEFINED),
                InstalledAppCandidate("com.example.a", "Same", ApplicationInfo.CATEGORY_UNDEFINED),
                InstalledAppCandidate("com.example.a", "Duplicate launcher", ApplicationInfo.CATEGORY_AUDIO),
            ),
            selfPackage = "co.twinotify.app",
        )

        assertEquals(listOf("com.example.a", "com.example.z"), catalog.map { it.packageName })
    }

    @Test
    fun fallsBackToPackageNameWhenTheDisplayLabelIsBlank() {
        val catalog = InstalledAppCatalog.normalize(
            candidates = listOf(
                InstalledAppCandidate("com.example.blank", "  ", ApplicationInfo.CATEGORY_UNDEFINED),
            ),
            selfPackage = "co.twinotify.app",
        )

        assertEquals("com.example.blank", catalog.single().displayName)
    }
}
