package co.twinotify.core.lan

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

object LanFrameCodec {
    private val digestRegex = Regex("^[0-9a-f]{64}$")
    private val closeCodeRegex = Regex("^[a-z][a-z0-9_]{0,63}$")

    fun encode(frame: LanFrame): ByteArray {
        require(frame.version == LanFrame.VERSION)
        val json = JSONObject().put("v", frame.version)
        when (frame) {
            is LanFrame.Hello -> json.put("type", "lan.hello").put("data", encodeBase64(frame.data))
            is LanFrame.HelloAck -> json.put("type", "lan.hello_ack").put("data", encodeBase64(frame.data))
            is LanFrame.Put -> {
                val envelope = decodeUtf8(frame.envelope)
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
        val raw = decodeUtf8(frameBytes.copyOfRange(LanFrameLimits.PREFIX_BYTES, frameBytes.size))
        rejectDuplicateTopLevelKeys(raw)
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            fail(LanFrameFailure.INVALID_JSON)
        }
        val version = strictInt(json, "v")
        if (version != LanFrame.VERSION) fail(LanFrameFailure.UNSUPPORTED_VERSION)
        return when (val type = strictString(json, "type")) {
            "lan.hello" -> decodeControl(json, setOf("v", "type", "data"), false)
            "lan.hello_ack" -> decodeControl(json, setOf("v", "type", "data"), true)
            "lan.put" -> {
                requireKeys(json, setOf("v", "type", "envelope"))
                val envelope = strictString(json, "envelope").encodeToByteArray()
                if (envelope.size > LanFrameLimits.MAX_ENVELOPE_BYTES) fail(LanFrameFailure.ENVELOPE_TOO_LARGE)
                LanFrame.Put(envelope)
            }
            "lan.accepted" -> {
                requireKeys(json, setOf("v", "type", "msg_id", "envelope_sha256"))
                val msgId = strictString(json, "msg_id")
                val digest = strictString(json, "envelope_sha256")
                if (!isCanonicalUuid(msgId) || !digestRegex.matches(digest)) fail(LanFrameFailure.INVALID_VALUE)
                LanFrame.Accepted(msgId, digest)
            }
            "lan.ping" -> decodeToken(json, true)
            "lan.pong" -> decodeToken(json, false)
            "lan.close" -> {
                requireKeys(json, setOf("v", "type", "code"))
                val code = strictString(json, "code")
                if (!closeCodeRegex.matches(code)) fail(LanFrameFailure.INVALID_VALUE)
                LanFrame.Close(code)
            }
            else -> fail(LanFrameFailure.UNSUPPORTED_TYPE)
        }
    }

    private fun decodeControl(json: JSONObject, keys: Set<String>, ack: Boolean): LanFrame {
        requireKeys(json, keys)
        val encoded = strictString(json, "data")
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
        requireKeys(json, setOf("v", "type", "token"))
        val token = strictLong(json, "token")
        if (token < 0) fail(LanFrameFailure.INVALID_VALUE)
        return if (ping) LanFrame.Ping(token) else LanFrame.Pong(token)
    }

    private fun requireKeys(json: JSONObject, expected: Set<String>) {
        val actual = buildSet {
            val iterator = json.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (actual != expected) fail(LanFrameFailure.INVALID_FIELDS)
    }

    private fun strictString(json: JSONObject, key: String): String = try {
        json.get(key) as? String ?: fail(LanFrameFailure.INVALID_FIELDS)
    } catch (_: JSONException) {
        fail(LanFrameFailure.INVALID_FIELDS)
    }

    private fun strictInt(json: JSONObject, key: String): Int = try {
        val value = json.get(key)
        if (value !is Int) fail(LanFrameFailure.INVALID_FIELDS)
        value
    } catch (_: JSONException) {
        fail(LanFrameFailure.INVALID_FIELDS)
    }

    private fun strictLong(json: JSONObject, key: String): Long = try {
        when (val value = json.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> fail(LanFrameFailure.INVALID_FIELDS)
        }
    } catch (_: JSONException) {
        fail(LanFrameFailure.INVALID_FIELDS)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        fail(LanFrameFailure.INVALID_UTF8)
    }

    /** JSONObject accepts duplicate names, so reject them before object construction. */
    private fun rejectDuplicateTopLevelKeys(raw: String) {
        val keys = mutableSetOf<String>()
        var depth = 0
        var index = 0
        while (index < raw.length) {
            when (raw[index]) {
                '{' -> { depth++; index++ }
                '}' -> { depth--; index++ }
                '"' -> {
                    val start = index
                    index++
                    var escaped = false
                    while (index < raw.length) {
                        val current = raw[index++]
                        if (escaped) escaped = false
                        else if (current == '\\') escaped = true
                        else if (current == '"') break
                    }
                    if (index > raw.length || raw.getOrNull(index - 1) != '"') fail(LanFrameFailure.INVALID_JSON)
                    if (depth == 1) {
                        var next = index
                        while (next < raw.length && raw[next].isWhitespace()) next++
                        if (raw.getOrNull(next) == ':') {
                            val key = try { JSONObject("{\"k\":${raw.substring(start, index)}}").getString("k") }
                            catch (_: JSONException) { fail(LanFrameFailure.INVALID_JSON) }
                            if (!keys.add(key)) fail(LanFrameFailure.DUPLICATE_KEY)
                        }
                    }
                }
                else -> index++
            }
        }
    }

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.size > LanFrameLimits.MAX_CONTROL_BYTES) fail(LanFrameFailure.CONTROL_TOO_LARGE)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun fail(failure: LanFrameFailure): Nothing = throw LanFrameException(failure)
}
