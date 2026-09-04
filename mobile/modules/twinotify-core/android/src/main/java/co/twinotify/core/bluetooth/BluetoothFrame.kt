package co.twinotify.core.bluetooth

import co.twinotify.core.direct.DirectCommand
import java.security.MessageDigest

/**
 * Closed-world post-handshake frame set for the L2CAP stream. It maps one-to-one onto
 * [DirectCommand]; the handshake messages are not frames and never appear here.
 */
sealed interface BluetoothFrame {
    val version: Int

    class Put(envelope: ByteArray, override val version: Int = VERSION) : BluetoothFrame {
        private val stored = envelope.copyOf()
        val envelope: ByteArray get() = stored.copyOf()
        override fun equals(other: Any?) = other is Put && version == other.version && MessageDigest.isEqual(stored, other.stored)
        override fun hashCode() = 41 * version
        override fun toString() = "Put(version=$version, envelope=<redacted:${stored.size} bytes>)"
    }

    data class Accepted(
        val msgId: String,
        val envelopeSha256: String,
        override val version: Int = VERSION,
    ) : BluetoothFrame

    data class Ping(val token: Long, override val version: Int = VERSION) : BluetoothFrame
    data class Pong(val token: Long, override val version: Int = VERSION) : BluetoothFrame
    data class Close(val code: String, override val version: Int = VERSION) : BluetoothFrame

    companion object {
        const val VERSION = 1
    }
}

enum class BluetoothFrameFailure(val code: String) {
    INVALID_LENGTH("bluetooth_frame_invalid_length"),
    FRAME_TOO_LARGE("bluetooth_frame_too_large"),
    TRUNCATED("bluetooth_frame_truncated"),
    TRAILING_BYTES("bluetooth_frame_trailing_bytes"),
    INVALID_UTF8("bluetooth_frame_invalid_utf8"),
    INVALID_JSON("bluetooth_frame_invalid_json"),
    DUPLICATE_KEY("bluetooth_frame_duplicate_key"),
    INVALID_FIELDS("bluetooth_frame_invalid_fields"),
    UNSUPPORTED_VERSION("bluetooth_frame_unsupported_version"),
    UNSUPPORTED_TYPE("bluetooth_frame_unsupported_type"),
    CONTROL_TOO_LARGE("bluetooth_frame_control_too_large"),
    ENVELOPE_TOO_LARGE("bluetooth_frame_envelope_too_large"),
    INVALID_VALUE("bluetooth_frame_invalid_value"),
}

class BluetoothFrameException(val failure: BluetoothFrameFailure) : IllegalArgumentException(failure.code)

internal fun BluetoothFrame.toCommand(): DirectCommand = when (this) {
    is BluetoothFrame.Put -> DirectCommand.Put(envelope)
    is BluetoothFrame.Accepted -> DirectCommand.Accepted(msgId, envelopeSha256)
    is BluetoothFrame.Ping -> DirectCommand.Ping(token)
    is BluetoothFrame.Pong -> DirectCommand.Pong(token)
    is BluetoothFrame.Close -> DirectCommand.Close(code)
}

internal fun DirectCommand.toFrame(): BluetoothFrame = when (this) {
    is DirectCommand.Put -> BluetoothFrame.Put(envelope)
    is DirectCommand.Accepted -> BluetoothFrame.Accepted(msgId, envelopeSha256)
    is DirectCommand.Ping -> BluetoothFrame.Ping(token)
    is DirectCommand.Pong -> BluetoothFrame.Pong(token)
    is DirectCommand.Close -> BluetoothFrame.Close(code)
}
