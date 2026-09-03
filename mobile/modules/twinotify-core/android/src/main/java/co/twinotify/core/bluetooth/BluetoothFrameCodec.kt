package co.twinotify.core.bluetooth

import co.twinotify.core.direct.FrameJson
import co.twinotify.core.direct.FrameJsonFailure
import co.twinotify.core.lan.LanFrameLimits
import java.nio.ByteBuffer
import org.json.JSONObject

/**
 * Four-byte big-endian body length, then one JSON object with an exact key set. The
 * byte limits are [LanFrameLimits] by reference so both direct routes share one bound.
 */
object BluetoothFrameCodec {
    private val fields = FrameJson { problem ->
        fail(
            when (problem) {
                FrameJsonFailure.INVALID_UTF8 -> BluetoothFrameFailure.INVALID_UTF8
                FrameJsonFailure.INVALID_JSON -> BluetoothFrameFailure.INVALID_JSON
                FrameJsonFailure.DUPLICATE_KEY -> BluetoothFrameFailure.DUPLICATE_KEY
                FrameJsonFailure.INVALID_FIELDS -> BluetoothFrameFailure.INVALID_FIELDS
            },
        )
    }

    fun encode(frame: BluetoothFrame): ByteArray {
        require(frame.version == BluetoothFrame.VERSION)
        val json = JSONObject().put("v", frame.version)
        when (frame) {
            is BluetoothFrame.Put -> {
                if (frame.envelope.size > LanFrameLimits.MAX_ENVELOPE_BYTES) fail(BluetoothFrameFailure.ENVELOPE_TOO_LARGE)
                json.put("type", "bt.put").put("envelope", fields.decodeUtf8(frame.envelope))
            }
            is BluetoothFrame.Accepted -> json.put("type", "bt.accepted")
                .put("msg_id", frame.msgId).put("envelope_sha256", frame.envelopeSha256)
            is BluetoothFrame.Ping -> json.put("type", "bt.ping").put("token", frame.token)
            is BluetoothFrame.Pong -> json.put("type", "bt.pong").put("token", frame.token)
            is BluetoothFrame.Close -> json.put("type", "bt.close").put("code", frame.code)
        }
        val body = json.toString().encodeToByteArray()
        if (body.size > LanFrameLimits.MAX_FRAME_BYTES) fail(BluetoothFrameFailure.FRAME_TOO_LARGE)
        return prefixed(body)
    }

    fun decode(frameBytes: ByteArray): BluetoothFrame {
        if (frameBytes.size < LanFrameLimits.PREFIX_BYTES) fail(BluetoothFrameFailure.TRUNCATED)
        val length = bodyLength(frameBytes, LanFrameLimits.MAX_FRAME_BYTES, BluetoothFrameFailure.FRAME_TOO_LARGE)
        val expected = LanFrameLimits.PREFIX_BYTES.toLong() + length
        if (frameBytes.size.toLong() < expected) fail(BluetoothFrameFailure.TRUNCATED)
        if (frameBytes.size.toLong() > expected) fail(BluetoothFrameFailure.TRAILING_BYTES)
        val raw = fields.decodeUtf8(frameBytes.copyOfRange(LanFrameLimits.PREFIX_BYTES, frameBytes.size))
        val json = fields.parseObject(raw)
        if (fields.int(json, "v") != BluetoothFrame.VERSION) fail(BluetoothFrameFailure.UNSUPPORTED_VERSION)
        return when (fields.string(json, "type")) {
            "bt.put" -> {
                fields.requireKeys(json, setOf("v", "type", "envelope"))
                val envelope = fields.string(json, "envelope").encodeToByteArray()
                if (envelope.size > LanFrameLimits.MAX_ENVELOPE_BYTES) fail(BluetoothFrameFailure.ENVELOPE_TOO_LARGE)
                BluetoothFrame.Put(envelope)
            }
            "bt.accepted" -> {
                fields.requireKeys(json, setOf("v", "type", "msg_id", "envelope_sha256"))
                val msgId = fields.string(json, "msg_id")
                val digest = fields.string(json, "envelope_sha256")
                if (!FrameJson.isCanonicalUuid(msgId) || !FrameJson.isLowercaseSha256(digest)) {
                    fail(BluetoothFrameFailure.INVALID_VALUE)
                }
                BluetoothFrame.Accepted(msgId, digest)
            }
            "bt.ping" -> decodeToken(json, ping = true)
            "bt.pong" -> decodeToken(json, ping = false)
            "bt.close" -> {
                fields.requireKeys(json, setOf("v", "type", "code"))
                val code = fields.string(json, "code")
                if (!FrameJson.isCloseCode(code)) fail(BluetoothFrameFailure.INVALID_VALUE)
                BluetoothFrame.Close(code)
            }
            else -> fail(BluetoothFrameFailure.UNSUPPORTED_TYPE)
        }
    }

    /**
     * The body length a prefix claims, judged before any body byte is read or allocated.
     * [tooLarge] names the bound being enforced (frame or handshake control message).
     */
    internal fun bodyLength(prefix: ByteArray, maxBytes: Int, tooLarge: BluetoothFrameFailure): Int {
        val length = ByteBuffer.wrap(prefix, 0, LanFrameLimits.PREFIX_BYTES).int
        if (length <= 0) fail(BluetoothFrameFailure.INVALID_LENGTH)
        if (length > maxBytes) fail(tooLarge)
        return length
    }

    internal fun prefixed(body: ByteArray): ByteArray =
        ByteBuffer.allocate(LanFrameLimits.PREFIX_BYTES + body.size).putInt(body.size).put(body).array()

    private fun decodeToken(json: JSONObject, ping: Boolean): BluetoothFrame {
        fields.requireKeys(json, setOf("v", "type", "token"))
        val token = fields.long(json, "token")
        if (token < 0) fail(BluetoothFrameFailure.INVALID_VALUE)
        return if (ping) BluetoothFrame.Ping(token) else BluetoothFrame.Pong(token)
    }

    private fun fail(failure: BluetoothFrameFailure): Nothing = throw BluetoothFrameException(failure)
}
