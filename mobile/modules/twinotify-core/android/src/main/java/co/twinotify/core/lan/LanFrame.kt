package co.twinotify.core.lan

import java.security.MessageDigest

sealed interface LanFrame {
    val version: Int

    class Hello(data: ByteArray, override val version: Int = VERSION) : LanFrame {
        private val stored = data.copyOf()
        val data: ByteArray get() = stored.copyOf()
        override fun equals(other: Any?) = other is Hello && version == other.version && MessageDigest.isEqual(stored, other.stored)
        override fun hashCode() = 31 * version
        override fun toString() = "Hello(version=$version, data=<redacted:${stored.size} bytes>)"
    }

    class HelloAck(data: ByteArray, override val version: Int = VERSION) : LanFrame {
        private val stored = data.copyOf()
        val data: ByteArray get() = stored.copyOf()
        override fun equals(other: Any?) = other is HelloAck && version == other.version && MessageDigest.isEqual(stored, other.stored)
        override fun hashCode() = 37 * version
        override fun toString() = "HelloAck(version=$version, data=<redacted:${stored.size} bytes>)"
    }

    class Put(envelope: ByteArray, override val version: Int = VERSION) : LanFrame {
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
    ) : LanFrame

    data class Ping(val token: Long, override val version: Int = VERSION) : LanFrame
    data class Pong(val token: Long, override val version: Int = VERSION) : LanFrame
    data class Close(val code: String, override val version: Int = VERSION) : LanFrame

    companion object {
        const val VERSION = 1
    }
}

object LanFrameLimits {
    const val MAX_ENVELOPE_BYTES = 1_048_576
    const val MAX_CONTROL_BYTES = 16_384
    const val MAX_BUFFERED_FRAMES = 4
    const val MAX_BUFFERED_BYTES = 4_260_000
    // Four legal frames, each including this body ceiling and its prefix, fit the byte budget.
    const val MAX_FRAME_BYTES = 1_064_996
    const val PREFIX_BYTES = 4
}

enum class LanFrameFailure(val code: String) {
    INVALID_LENGTH("lan_frame_invalid_length"),
    FRAME_TOO_LARGE("lan_frame_too_large"),
    TRUNCATED("lan_frame_truncated"),
    TRAILING_BYTES("lan_frame_trailing_bytes"),
    INVALID_UTF8("lan_frame_invalid_utf8"),
    INVALID_JSON("lan_frame_invalid_json"),
    DUPLICATE_KEY("lan_frame_duplicate_key"),
    INVALID_FIELDS("lan_frame_invalid_fields"),
    UNSUPPORTED_VERSION("lan_frame_unsupported_version"),
    UNSUPPORTED_TYPE("lan_frame_unsupported_type"),
    INVALID_BASE64("lan_frame_invalid_base64"),
    CONTROL_TOO_LARGE("lan_frame_control_too_large"),
    ENVELOPE_TOO_LARGE("lan_frame_envelope_too_large"),
    INVALID_VALUE("lan_frame_invalid_value"),
}

class LanFrameException(val failure: LanFrameFailure) : IllegalArgumentException(failure.code)

/** Count and byte admission are one atomic operation; failed admission never changes accounting. */
class LanFrameBuffer {
    private val frames = ArrayDeque<ByteArray>(LanFrameLimits.MAX_BUFFERED_FRAMES)
    var bufferedFrames: Int = 0
        private set
    var bufferedBytes: Int = 0
        private set

    @Synchronized
    fun tryOffer(frameBytes: ByteArray): Boolean {
        val bytes = frameBytes.size
        require(bytes in (LanFrameLimits.PREFIX_BYTES + 1)..
            (LanFrameLimits.MAX_FRAME_BYTES + LanFrameLimits.PREFIX_BYTES))
        if (bufferedFrames >= LanFrameLimits.MAX_BUFFERED_FRAMES) return false
        if (bufferedBytes > LanFrameLimits.MAX_BUFFERED_BYTES - bytes) return false
        frames.addLast(frameBytes.copyOf())
        bufferedFrames += 1
        bufferedBytes += bytes
        return true
    }

    @Synchronized
    fun poll(): ByteArray? {
        val frame = frames.removeFirstOrNull() ?: return null
        bufferedFrames -= 1
        bufferedBytes -= frame.size
        return frame
    }
}
