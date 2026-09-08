package co.twinotify.core.lan

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class LanTlsExporterTest {
    @Test
    fun portableContextUsesExactlyTheExportedBytes() {
        val bytes = ByteArray(32) { it.toByte() }
        val context = LanTlsExporter.context { bytes }
        assertContentEquals(bytes, context)
        assertNotSame(bytes, context)
    }

    @Test
    fun failedExporterCannotDowngradeToLegacyContext() {
        for (bytes in listOf(null, ByteArray(0), ByteArray(31), ByteArray(33))) {
            assertFailsWith<LanConnectionException> { LanTlsExporter.context { bytes } }
        }
    }
}
