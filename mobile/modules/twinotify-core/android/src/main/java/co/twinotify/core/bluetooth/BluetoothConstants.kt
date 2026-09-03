package co.twinotify.core.bluetooth

import java.util.UUID

/** Fixed identifiers for the Bluetooth direct route. Both phones must agree on every value. */
object BluetoothConstants {
    /** Private secure-RFCOMM service. Never the public SPP UUID. */
    val RFCOMM_SERVICE_UUID: UUID = UUID.fromString("7c6f5d5e-6f54-4f6e-9b63-5457494e4f54")

    /** BLE service advertised only while the user is inside the association flow. */
    val DISCOVERY_SERVICE_UUID: UUID = UUID.fromString("5d7101b8-cad0-4d22-a41e-5457494e4f54")

    const val ROUTE_LABEL = "bluetooth-rfcomm-v1"
    const val PROTOCOL_VERSION = 1
}
