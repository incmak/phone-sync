package co.twinotify.core.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FingerprintTest {
    @Test
    fun formatIs16GroupsOf4Hex() {
        val fp = Fingerprint.of(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 1).toByte() })
        val groups = fp.split("-")
        assertEquals(16, groups.size, "16 groups")
        for (g in groups) {
            assertEquals(4, g.length, "each group is 4 chars")
            assertTrue(g.all { it in '0'..'9' || it in 'A'..'F' }, "uppercase hex only: $g")
        }
    }

    @Test
    fun sameInputProducesSameFingerprint() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertEquals(Fingerprint.of(a, b), Fingerprint.of(a, b))
    }

    @Test
    fun differentInputProducesDifferentFingerprint() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val c = ByteArray(32) { 3 }
        assertTrue(Fingerprint.of(a, b) != Fingerprint.of(a, c))
    }
}
