package co.twinotify.core.bluetooth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The listening phone publishes its LE L2CAP PSM as two big-endian bytes of service data under
 * the discovery service UUID. Everything here is compile-time arithmetic, so no radio is needed.
 */
class BluetoothPsmTest {
    @Test
    fun psmIsTwoBigEndianBytes() {
        assertContentEquals(byteArrayOf(0x00, 0x80.toByte()), BluetoothPsm.encode(0x0080))
        assertContentEquals(byteArrayOf(0x00, 0xFF.toByte()), BluetoothPsm.encode(0x00FF))
        assertContentEquals(byteArrayOf(0x12, 0x34), BluetoothPsm.encode(0x1234))
        assertEquals(BluetoothPsm.SERVICE_DATA_LENGTH, BluetoothPsm.encode(0x0081).size)
    }

    @Test
    fun everyLegalPsmSurvivesTheRoundTrip() {
        for (psm in listOf(1, 0x0080, 0x00A5, 0x00FF, 0x0100, 0x7FFF, 0xFFFF)) {
            assertEquals(psm, BluetoothPsm.decode(BluetoothPsm.encode(psm)))
        }
    }

    @Test
    fun theHighByteIsNeverSignExtended() {
        // 0x80.toByte() is negative in Kotlin; a decoder that forgets to mask reports a negative PSM.
        assertEquals(0xFFFF, BluetoothPsm.decode(byteArrayOf(0xFF.toByte(), 0xFF.toByte())))
        assertEquals(0x0080, BluetoothPsm.decode(byteArrayOf(0x00, 0x80.toByte())))
    }

    @Test
    fun missingOrMalformedServiceDataIsOneBoundedFailure() {
        val malformed = listOf(
            null,
            ByteArray(0),
            byteArrayOf(0x01),
            byteArrayOf(0x00, 0x80.toByte(), 0x00),
            byteArrayOf(0x00, 0x00),
        )
        for (bytes in malformed) {
            val error = assertFailsWith<BluetoothAssociationException> { BluetoothPsm.decode(bytes) }
            assertEquals(BluetoothAssociationFailure.PEER_PSM_UNAVAILABLE, error.failure)
            assertEquals("bluetooth_peer_psm_unavailable", error.message)
        }
    }

    @Test
    fun anUnencodablePsmIsRejectedBeforeItReachesTheAir() {
        for (psm in listOf(0, -1, 0x1_0000)) {
            assertFailsWith<IllegalArgumentException> { BluetoothPsm.encode(psm) }
        }
    }

    @Test
    fun theServiceUuidAndThePsmCannotShareOneLegacyPacket() {
        // 3 flags + 18 service UUID + 20 service data = 41 > 31, so the PSM must ride in the
        // scan response. A scanner merges the advertisement and the scan response into one
        // ScanRecord, so the picker still filters on the UUID and still reads the PSM.
        assertEquals(41, BluetoothAdvertisementLayout.SINGLE_PACKET_BYTES)
        assertFalse(BluetoothAdvertisementLayout.FITS_ONE_LEGACY_PACKET)
        assertTrue(BluetoothAdvertisementLayout.ADVERTISEMENT_BYTES <= BluetoothAdvertisementLayout.LEGACY_PAYLOAD_LIMIT)
        assertTrue(BluetoothAdvertisementLayout.SCAN_RESPONSE_BYTES <= BluetoothAdvertisementLayout.LEGACY_PAYLOAD_LIMIT)
        assertEquals(21, BluetoothAdvertisementLayout.ADVERTISEMENT_BYTES)
        assertEquals(20, BluetoothAdvertisementLayout.SCAN_RESPONSE_BYTES)
    }
}
