package co.twinotify.core.lan

import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.flow.Flow

interface LanDiscovery {
    fun candidates(): Flow<LanCandidate>
    suspend fun close()
}

fun interface LanNetwork {
    fun openSocket(): Socket
}

class LanCandidate(
    val address: InetAddress,
    val port: Int,
    val network: LanNetwork,
) {
    init { require(port in 1..65535) { "lan_candidate_invalid_port" } }
    override fun toString() = "LanCandidate(port=$port, network=<bound>)"
}

object LanCapabilities {
    const val DIRECT_V1 = 1
    const val REVIEWED_MASK = DIRECT_V1
}

internal object LanDiscoveryContract {
    const val SERVICE_TYPE = "_twinotify._tcp."
    const val VERSION = "1"
    private val allowedKeys = setOf("v", "ad", "caps")
    private val advertisementRegex = Regex("^[A-Za-z0-9_-]{22}$")

    fun txt(advertisementId: String, capabilities: Int): Map<String, ByteArray> {
        require(advertisementRegex.matches(advertisementId)) { "lan_advertisement_invalid" }
        require(capabilities > 0 && capabilities and LanCapabilities.REVIEWED_MASK == capabilities) {
            "lan_capabilities_invalid"
        }
        return mapOf(
            "v" to VERSION.encodeToByteArray(),
            "ad" to advertisementId.encodeToByteArray(),
            "caps" to capabilities.toString().encodeToByteArray(),
        )
    }

    fun matches(attributes: Map<String, ByteArray>, expectedAdvertisementIds: Set<String>): Boolean {
        if (attributes.keys != allowedKeys) return false
        val version = attributes["v"]?.strictUtf8() ?: return false
        val ad = attributes["ad"]?.strictUtf8() ?: return false
        val capabilitiesText = attributes["caps"]?.strictUtf8() ?: return false
        val capabilities = capabilitiesText.toIntOrNull() ?: return false
        return version == VERSION && advertisementRegex.matches(ad) && ad in expectedAdvertisementIds &&
            capabilitiesText == capabilities.toString() &&
            capabilities > 0 && capabilities and LanCapabilities.REVIEWED_MASK == capabilities
    }
}

private fun ByteArray.strictUtf8(): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this)).toString()
} catch (_: Exception) {
    null
}
