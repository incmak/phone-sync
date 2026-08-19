package co.twinotify.core.pairing

sealed interface UnpairRevocationDecision {
    data object NoPeer : UnpairRevocationDecision
    data object OfflineOnly : UnpairRevocationDecision
    data class Relay(val relayUrl: String) : UnpairRevocationDecision
}

object UnpairRevocationPolicy {
    fun decide(
        peerPresent: Boolean,
        relayRevocationRequired: Boolean?,
        lanBindingId: String?,
        relayUrl: String?,
    ): UnpairRevocationDecision {
        if (!peerPresent) return UnpairRevocationDecision.NoPeer

        val canonicalRelayUrl = relayUrl
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
        if (relayRevocationRequired == false) {
            return UnpairRevocationDecision.OfflineOnly
        }
        if (canonicalRelayUrl != null) {
            return UnpairRevocationDecision.Relay(canonicalRelayUrl)
        }
        if (relayRevocationRequired == null && !lanBindingId.isNullOrBlank()) {
            return UnpairRevocationDecision.OfflineOnly
        }
        throw IllegalStateException("missing_relay_url")
    }
}

object UnpairRevocationExecutor {
    suspend fun execute(
        decision: UnpairRevocationDecision,
        markRevocationIntent: suspend () -> Boolean,
        revoke: suspend (relayUrl: String, revocationMarkerPresent: Boolean) -> Unit,
    ) {
        if (decision !is UnpairRevocationDecision.Relay) return
        val markerPresent = markRevocationIntent()
        revoke(decision.relayUrl, markerPresent)
    }
}
