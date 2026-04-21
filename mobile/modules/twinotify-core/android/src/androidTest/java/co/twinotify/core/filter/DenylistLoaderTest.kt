package co.twinotify.core.filter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DenylistLoaderTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun load_matchesHash() {
        val set = DenylistLoader.load(ctx)
        assertTrue(set.isNotEmpty())
        assertTrue(set.contains("com.authy.authy"))
        assertTrue(set.contains("com.google.android.apps.authenticator2"))
        assertFalse(set.contains("com.random.notdenied"))
    }

    @Test
    fun contains_looksUpCorrectly() {
        assertTrue(DenylistLoader.contains("com.venmo", ctx))
        assertFalse(DenylistLoader.contains("com.whatsapp", ctx))
    }

    @Test
    fun parseAndVerify_throwsOnTamperedBytes() {
        val tampered = """{"version":1,"packages":["com.evil.bank"]}""".toByteArray()
        assertFailsWith<SecurityException> {
            DenylistLoader.parseAndVerify(tampered)
        }
    }

    @Test
    fun parseAndVerify_acceptsRealAssetBytes() {
        val bytes = ctx.assets.open("default-denylist.json").use { it.readBytes() }
        val set = DenylistLoader.parseAndVerify(bytes)
        assertTrue(set.contains("com.authy.authy"))
    }
}
