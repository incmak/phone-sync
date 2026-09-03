package co.twinotify.core.direct

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

/** What strict JSON handling can find wrong. Each codec maps these onto its own bounded failure set. */
internal enum class FrameJsonFailure { INVALID_UTF8, INVALID_JSON, DUPLICATE_KEY, INVALID_FIELDS }

/**
 * Strict JSON field handling shared by every direct-route frame codec, so the LAN and
 * Bluetooth wire formats cannot drift apart in what they accept. [fail] turns a
 * problem into the owning codec's exception and never returns.
 */
internal class FrameJson(private val fail: (FrameJsonFailure) -> Nothing) {
    fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        fail(FrameJsonFailure.INVALID_UTF8)
    }

    /** Duplicate top-level names are rejected before construction; JSONObject would silently keep the last one. */
    fun parseObject(raw: String): JSONObject {
        rejectDuplicateTopLevelKeys(raw)
        return try {
            JSONObject(raw)
        } catch (_: JSONException) {
            fail(FrameJsonFailure.INVALID_JSON)
        }
    }

    fun requireKeys(json: JSONObject, expected: Set<String>) {
        val actual = buildSet {
            val iterator = json.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (actual != expected) fail(FrameJsonFailure.INVALID_FIELDS)
    }

    fun string(json: JSONObject, key: String): String = try {
        json.get(key) as? String ?: fail(FrameJsonFailure.INVALID_FIELDS)
    } catch (_: JSONException) {
        fail(FrameJsonFailure.INVALID_FIELDS)
    }

    fun int(json: JSONObject, key: String): Int = try {
        val value = json.get(key)
        if (value !is Int) fail(FrameJsonFailure.INVALID_FIELDS)
        value
    } catch (_: JSONException) {
        fail(FrameJsonFailure.INVALID_FIELDS)
    }

    fun long(json: JSONObject, key: String): Long = try {
        when (val value = json.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> fail(FrameJsonFailure.INVALID_FIELDS)
        }
    } catch (_: JSONException) {
        fail(FrameJsonFailure.INVALID_FIELDS)
    }

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
                    if (index > raw.length || raw.getOrNull(index - 1) != '"') fail(FrameJsonFailure.INVALID_JSON)
                    if (depth == 1) {
                        var next = index
                        while (next < raw.length && raw[next].isWhitespace()) next++
                        if (raw.getOrNull(next) == ':') {
                            val key = try { JSONObject("{\"k\":${raw.substring(start, index)}}").getString("k") }
                            catch (_: JSONException) { fail(FrameJsonFailure.INVALID_JSON) }
                            if (!keys.add(key)) fail(FrameJsonFailure.DUPLICATE_KEY)
                        }
                    }
                }
                else -> index++
            }
        }
    }

    companion object {
        private val digestRegex = Regex("^[0-9a-f]{64}$")
        private val closeCodeRegex = Regex("^[a-z][a-z0-9_]{0,63}$")

        /** Lowercase, hyphenated, exactly what `UUID.toString` would print. */
        fun isCanonicalUuid(value: String): Boolean = try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }

        fun isLowercaseSha256(value: String): Boolean = digestRegex.matches(value)

        fun isCloseCode(value: String): Boolean = closeCodeRegex.matches(value)
    }
}
