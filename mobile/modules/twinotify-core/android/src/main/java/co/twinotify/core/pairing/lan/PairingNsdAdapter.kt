package co.twinotify.core.pairing.lan

import java.net.InetAddress
import java.net.Socket

/** Opaque handle whose identity is preserved for the matching unregister call. */
class PairingAdvertisement internal constructor(internal val listener: Any)

fun interface PairingNetwork {
    /** Returns an unconnected socket already bound to this Android Network. */
    fun openSocket(): Socket
}

data class PairingNsdEndpoint(
    val address: InetAddress,
    val port: Int,
    val network: PairingNetwork,
) {
    init {
        require(port in 1..65535) { "invalid_pairing_port" }
    }
}

interface PairingNsdAdapter {
    suspend fun register(sessionId: String, port: Int): PairingAdvertisement
    suspend fun resolve(sessionId: String): PairingNsdEndpoint
    suspend fun unregister(advertisement: PairingAdvertisement)
    suspend fun stopDiscovery()
}

internal object PairingNsdContract {
    const val SERVICE_TYPE = "_twinotify-pair._tcp."
    const val VERSION = "1"
    private val allowedKeys = setOf("session", "version")

    fun txt(sessionId: String): Map<String, ByteArray> {
        validateSessionId(sessionId)
        return mapOf(
            "session" to sessionId.encodeToByteArray(),
            "version" to VERSION.encodeToByteArray(),
        )
    }

    fun matchesSession(attributes: Map<String, ByteArray>, expectedSessionId: String): Boolean {
        if (attributes.keys != allowedKeys) return false
        val session = attributes["session"]?.decodeStrictUtf8() ?: return false
        val version = attributes["version"]?.decodeStrictUtf8() ?: return false
        return version == VERSION && session == expectedSessionId
    }
}

internal fun ByteArray.decodeStrictUtf8(): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(this))
        .toString()
} catch (_: java.nio.charset.CharacterCodingException) {
    null
}
