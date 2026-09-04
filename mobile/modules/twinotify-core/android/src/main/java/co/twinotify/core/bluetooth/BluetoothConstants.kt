package co.twinotify.core.bluetooth

import java.util.UUID

/** Fixed identifiers for the Bluetooth direct route. Both phones must agree on every value. */
object BluetoothConstants {
    /**
     * BLE service advertised only while a route attempt or the association flow is open. It is
     * both the picker's scan filter and the key of the service data carrying the L2CAP PSM.
     */
    val DISCOVERY_SERVICE_UUID: UUID = UUID.fromString("5d7101b8-cad0-4d22-a41e-5457494e4f54")

    const val ROUTE_LABEL = "bluetooth-l2cap-v1"
    const val PROTOCOL_VERSION = 1
}

/**
 * The LE L2CAP PSM the listening phone publishes, as two big-endian bytes of service data under
 * [BluetoothConstants.DISCOVERY_SERVICE_UUID]. The PSM is assigned by the local stack when the
 * server socket opens, so it is discovered rather than fixed and has to travel over the air.
 */
object BluetoothPsm {
    const val SERVICE_DATA_LENGTH = 2
    private const val MIN_PSM = 1
    private const val MAX_PSM = 0xFFFF

    fun encode(psm: Int): ByteArray {
        require(psm in MIN_PSM..MAX_PSM) { "bluetooth_local_psm_invalid" }
        return byteArrayOf((psm shr 8).toByte(), psm.toByte())
    }

    /** A missing or malformed advertisement is one bounded failure, never a guessed PSM. */
    fun decode(serviceData: ByteArray?): Int {
        if (serviceData == null || serviceData.size != SERVICE_DATA_LENGTH) {
            throw BluetoothAssociationException(BluetoothAssociationFailure.PEER_PSM_UNAVAILABLE)
        }
        val psm = ((serviceData[0].toInt() and 0xFF) shl 8) or (serviceData[1].toInt() and 0xFF)
        if (psm < MIN_PSM) throw BluetoothAssociationException(BluetoothAssociationFailure.PEER_PSM_UNAVAILABLE)
        return psm
    }
}

/**
 * Why the PSM rides in the scan response.
 *
 * A legacy advertisement carries at most 31 payload bytes. A connectable advertisement already
 * spends 3 on the flags field the stack inserts, a 128-bit service UUID field costs 18, and the
 * service data field repeats that same 128-bit UUID before its 2 payload bytes, costing 20. All
 * three together are 41 bytes and cannot be advertised at once. Splitting them works because a
 * scanner merges the advertisement and the scan response into a single [ScanRecord]: the picker
 * still filters on the service UUID from the advertisement and still reads the PSM from the
 * service data in the scan response.
 */
object BluetoothAdvertisementLayout {
    const val LEGACY_PAYLOAD_LIMIT = 31
    const val FLAGS_FIELD_BYTES = 3
    const val SERVICE_UUID_FIELD_BYTES = 18
    const val SERVICE_DATA_FIELD_BYTES = 2 + 16 + BluetoothPsm.SERVICE_DATA_LENGTH

    const val ADVERTISEMENT_BYTES = FLAGS_FIELD_BYTES + SERVICE_UUID_FIELD_BYTES
    const val SCAN_RESPONSE_BYTES = SERVICE_DATA_FIELD_BYTES
    const val SINGLE_PACKET_BYTES = ADVERTISEMENT_BYTES + SERVICE_DATA_FIELD_BYTES

    const val FITS_ONE_LEGACY_PACKET = SINGLE_PACKET_BYTES <= LEGACY_PAYLOAD_LIMIT
}
