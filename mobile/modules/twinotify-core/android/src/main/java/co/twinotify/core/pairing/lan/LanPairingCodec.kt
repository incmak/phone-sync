package co.twinotify.core.pairing.lan

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

object LanPairingCodec {
    private const val MAX_QR_JSON_BYTES = 4096
    private val qrKeys = listOf(
        "v",
        "sid",
        "created_at_hint_ms",
        "lifetime_ms",
        "device_id",
        "display_name",
        "enc_pubkey",
        "sign_pubkey",
        "tls_spki_sha256",
        "session_token",
    )

    fun encodeQr(value: LanPairingQr): String = buildString {
        append('{')
        append("\"v\":").append(value.version)
        append(",\"sid\":").append(quote(value.sessionId))
        append(",\"created_at_hint_ms\":").append(value.createdAtHintMillis)
        append(",\"lifetime_ms\":").append(value.lifetimeMillis)
        append(",\"device_id\":").append(quote(value.deviceId))
        append(",\"display_name\":").append(quote(value.displayName))
        append(",\"enc_pubkey\":").append(quote(encode(value.encryptionPublicKey)))
        append(",\"sign_pubkey\":").append(quote(encode(value.signingPublicKey)))
        append(",\"tls_spki_sha256\":").append(quote(encode(value.tlsSpkiSha256)))
        append(",\"session_token\":").append(quote(encode(value.sessionToken)))
        append('}')
    }.also { require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_QR_JSON_BYTES) { "LAN pairing QR is too large" } }

    fun decodeQr(raw: String): LanPairingQr {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_QR_JSON_BYTES) { "LAN pairing QR is too large" }
        val fields = StrictFlatJson(raw).parse()
        require(fields.keys == qrKeys.toSet()) { "invalid LAN pairing QR fields" }
        return LanPairingQr(
            version = fields.intValue("v"),
            sessionId = fields.stringValue("sid"),
            createdAtHintMillis = fields.longValue("created_at_hint_ms"),
            lifetimeMillis = fields.longValue("lifetime_ms"),
            deviceId = fields.stringValue("device_id"),
            displayName = fields.stringValue("display_name"),
            encryptionPublicKey = LanPairingBytes(decode(fields.stringValue("enc_pubkey"), "encryption public key", 32)),
            signingPublicKey = LanPairingBytes(decode(fields.stringValue("sign_pubkey"), "signing public key", 32)),
            tlsSpkiSha256 = LanPairingBytes(decode(fields.stringValue("tls_spki_sha256"), "TLS pin", 32)),
            sessionToken = LanPairingBytes(decode(fields.stringValue("session_token"), "session token", 32)),
        )
    }

    fun canonicalTranscript(value: LanPairingTranscript): ByteArray {
        val participants = listOf(value.first, value.second).sortedBy { it.deviceId }
        return ByteArrayOutputStream().use { output ->
            output.write("twinotify-lan-pairing-transcript-v1".encodeToByteArray())
            writeField(output, value.sessionId.encodeToByteArray())
            writeField(output, longBytes(value.lifetimeMillis))
            writeField(output, intBytes(value.negotiatedVersion))
            participants.forEach { participant ->
                writeField(output, participant.deviceId.encodeToByteArray())
                writeField(output, participant.encryptionPublicKey.copy())
                writeField(output, participant.signingPublicKey.copy())
                writeField(output, participant.tlsSpkiSha256.copy())
                writeField(output, participant.nonce.copy())
            }
            output.toByteArray()
        }
    }

    private fun encode(value: LanPairingBytes): String = Base64.getEncoder().encodeToString(value.copy())

    private fun decode(value: String, label: String, expectedSize: Int): ByteArray = try {
        Base64.getDecoder().decode(value).also {
            require(it.size == expectedSize) { "invalid LAN pairing $label" }
        }
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("invalid LAN pairing $label")
    }

    private fun writeField(output: ByteArrayOutputStream, value: ByteArray) {
        require(value.size <= 65535) { "invalid LAN transcript field" }
        output.write(value.size ushr 8)
        output.write(value.size)
        output.write(value)
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun longBytes(value: Long): ByteArray = byteArrayOf(
        (value ushr 56).toByte(), (value ushr 48).toByte(), (value ushr 40).toByte(), (value ushr 32).toByte(),
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('\"')
        value.forEach { character ->
            when (character) {
                '\"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u${character.code.toString(16).padStart(4, '0')}") else append(character)
            }
        }
        append('\"')
    }
}

private class StrictFlatJson(private val input: String) {
    private var position = 0

    fun parse(): Map<String, Any> {
        whitespace()
        require(take() == '{') { "invalid LAN pairing QR JSON" }
        whitespace()
        val fields = linkedMapOf<String, Any>()
        if (peek() == '}') {
            position++
        } else {
            while (true) {
                val key = string()
                require(fields[key] == null) { "invalid LAN pairing QR JSON" }
                whitespace()
                require(take() == ':') { "invalid LAN pairing QR JSON" }
                whitespace()
                fields[key] = value()
                whitespace()
                when (take()) {
                    '}' -> break
                    ',' -> whitespace()
                    else -> throw IllegalArgumentException("invalid LAN pairing QR JSON")
                }
            }
        }
        whitespace()
        require(position == input.length) { "invalid LAN pairing QR JSON" }
        return fields
    }

    private fun value(): Any = when (peek()) {
        '\"' -> string()
        in '0'..'9', '-' -> number()
        else -> throw IllegalArgumentException("invalid LAN pairing QR JSON")
    }

    private fun string(): String {
        require(take() == '\"') { "invalid LAN pairing QR JSON" }
        val output = StringBuilder()
        while (true) {
            val character = take()
            when (character) {
                '\"' -> return output.toString()
                '\\' -> output.append(escape())
                in '\u0000'..'\u001f' -> throw IllegalArgumentException("invalid LAN pairing QR JSON")
                else -> output.append(character)
            }
        }
    }

    private fun escape(): Char = when (val escaped = take()) {
        '\"', '\\', '/' -> escaped
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            val hex = input.substring(position, (position + 4).also { require(it <= input.length) { "invalid LAN pairing QR JSON" } })
            require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid LAN pairing QR JSON" }
            position += 4
            hex.toInt(16).toChar()
        }
        else -> throw IllegalArgumentException("invalid LAN pairing QR JSON")
    }

    private fun number(): Long {
        val start = position
        if (peek() == '-') position++
        val first = take()
        require(first in '0'..'9') { "invalid LAN pairing QR JSON" }
        if (first == '0') require(peek() !in '0'..'9') { "invalid LAN pairing QR JSON" }
        while (peek() in '0'..'9') position++
        require(peek() !in charArrayOf('.', 'e', 'E')) { "invalid LAN pairing QR JSON" }
        return input.substring(start, position).toLongOrNull()
            ?: throw IllegalArgumentException("invalid LAN pairing QR JSON")
    }

    private fun whitespace() {
        while (peek() in charArrayOf(' ', '\n', '\r', '\t')) position++
    }

    private fun peek(): Char = if (position < input.length) input[position] else '\u0000'

    private fun take(): Char {
        require(position < input.length) { "invalid LAN pairing QR JSON" }
        return input[position++]
    }
}

private fun Map<String, Any>.stringValue(key: String): String = this[key] as? String
    ?: throw IllegalArgumentException("invalid LAN pairing QR $key")

private fun Map<String, Any>.longValue(key: String): Long = this[key] as? Long
    ?: throw IllegalArgumentException("invalid LAN pairing QR $key")

private fun Map<String, Any>.intValue(key: String): Int = longValue(key).let {
    require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "invalid LAN pairing QR $key" }
    it.toInt()
}
