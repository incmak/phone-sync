package co.twinotify.core.lan

import co.twinotify.core.direct.FrameJson
import co.twinotify.core.direct.FrameJsonFailure
import java.nio.ByteBuffer
import java.util.Base64
import org.json.JSONObject

object LanFrameCodec {
    private val fields = FrameJson { problem ->
        fail(
            when (problem) {
                FrameJsonFailure.INVALID_UTF8 -> LanFrameFailure.INVALID_UTF8
                FrameJsonFailure.INVALID_JSON -> LanFrameFailure.INVALID_JSON
                FrameJsonFailure.DUPLICATE_KEY -> LanFrameFailure.DUPLICATE_KEY
                FrameJsonFailure.INVALID_FIELDS -> LanFrameFailure.INVALID_FIELDS
            },
        )
    }

    fun encode(frame: LanFrame): ByteArray {
        require(frame.version == LanFrame.VERSION)
        val json = JSONObject().put("v", frame.version)
        when (frame) {
            is LanFrame.Hello -> json.put("type", "lan.hello").put("data", encodeBase64(frame.data))
            is LanFrame.HelloAck -> json.put("type", "lan.hello_ack").put("data", encodeBase64(frame.data))
            is LanFrame.Put -> {
                val envelope = fields.decodeUtf8(frame.envelope)
                if (frame.envelope.size > LanFrameLimits.MAX_ENVELOPE_BYTES) fail(LanFrameFailure.ENVELOPE_TOO_LARGE)
                json.put("type", "lan.put").put("envelope", envelope)
            }
            is LanFrame.Accepted -> json.put("type", "lan.accepted")
                .put("msg_id", frame.msgId).put("envelope_sha256", frame.envelopeSha256)
            is LanFrame.Ping -> json.put("type", "lan.ping").put("token", frame.token)
            is LanFrame.Pong -> json.put("type", "lan.pong").put("token", frame.token)
            is LanFrame.Close -> json.put("type", "lan.close").put("code", frame.code)
        }
        val body = json.toString().encodeToByteArray()
        if (body.size > LanFrameLimits.MAX_FRAME_BYTES) fail(LanFrameFailure.FRAME_TOO_LARGE)
        return ByteBuffer.allocate(LanFrameLimits.PREFIX_BYTES + body.size).putInt(body.size).put(body).array()
    }

    fun decode(frameBytes: ByteArray): LanFrame {
        if (frameBytes.size < LanFrameLimits.PREFIX_BYTES) fail(LanFrameFailure.TRUNCATED)
        val length = ByteBuffer.wrap(frameBytes, 0, LanFrameLimits.PREFIX_BYTES).int
        if (length <= 0) fail(LanFrameFailure.INVALID_LENGTH)
        if (length > LanFrameLimits.MAX_FRAME_BYTES) fail(LanFrameFailure.FRAME_TOO_LARGE)
        val expected = LanFrameLimits.PREFIX_BYTES.toLong() + length
        if (frameBytes.size.toLong() < expected) fail(LanFrameFailure.TRUNCATED)
        if (frameBytes.size.toLong() > expected) fail(LanFrameFailure.TRAILING_BYTES)
        val raw = fields.decodeUtf8(frameBytes.copyOfRange(LanFrameLimits.PREFIX_BYTES, frameBytes.size))
        val json = fields.parseObject(raw)
        val version = fields.int(json, "v")
        if (version != LanFrame.VERSION) fail(LanFrameFailure.UNSUPPORTED_VERSION)
        return when (fields.string(json, "type")) {
            "lan.hello" -> decodeControl(json, setOf("v", "type", "data"), false)
            "lan.hello_ack" -> decodeControl(json, setOf("v", "type", "data"), true)
            "lan.put" -> {
                fields.requireKeys(json, setOf("v", "type", "envelope"))
                val envelope = fields.string(json, "envelope").encodeToByteArray()
                if (envelope.size > LanFrameLimits.MAX_ENVELOPE_BYTES) fail(LanFrameFailure.ENVELOPE_TOO_LARGE)
                LanFrame.Put(envelope)
            }
            "lan.accepted" -> {
                fields.requireKeys(json, setOf("v", "type", "msg_id", "envelope_sha256"))
                val msgId = fields.string(json, "msg_id")
                val digest = fields.string(json, "envelope_sha256")
                if (!FrameJson.isCanonicalUuid(msgId) || !FrameJson.isLowercaseSha256(digest)) fail(LanFrameFailure.INVALID_VALUE)
                LanFrame.Accepted(msgId, digest)
            }
            "lan.ping" -> decodeToken(json, true)
            "lan.pong" -> decodeToken(json, false)
            "lan.close" -> {
                fields.requireKeys(json, setOf("v", "type", "code"))
                val code = fields.string(json, "code")
                if (!FrameJson.isCloseCode(code)) fail(LanFrameFailure.INVALID_VALUE)
                LanFrame.Close(code)
            }
            else -> fail(LanFrameFailure.UNSUPPORTED_TYPE)
        }
    }

    private fun decodeControl(json: JSONObject, keys: Set<String>, ack: Boolean): LanFrame {
        fields.requireKeys(json, keys)
        val encoded = fields.string(json, "data")
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            fail(LanFrameFailure.INVALID_BASE64)
        }
        if (Base64.getEncoder().encodeToString(decoded) != encoded) fail(LanFrameFailure.INVALID_BASE64)
        if (decoded.size > LanFrameLimits.MAX_CONTROL_BYTES) fail(LanFrameFailure.CONTROL_TOO_LARGE)
        return if (ack) LanFrame.HelloAck(decoded) else LanFrame.Hello(decoded)
    }

    private fun decodeToken(json: JSONObject, ping: Boolean): LanFrame {
        fields.requireKeys(json, setOf("v", "type", "token"))
        val token = fields.long(json, "token")
        if (token < 0) fail(LanFrameFailure.INVALID_VALUE)
        return if (ping) LanFrame.Ping(token) else LanFrame.Pong(token)
    }

    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.size > LanFrameLimits.MAX_CONTROL_BYTES) fail(LanFrameFailure.CONTROL_TOO_LARGE)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun fail(failure: LanFrameFailure): Nothing = throw LanFrameException(failure)
}
