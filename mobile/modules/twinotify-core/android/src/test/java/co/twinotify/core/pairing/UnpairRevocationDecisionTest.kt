package co.twinotify.core.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class UnpairRevocationDecisionTest {
    @Test
    fun noPeerDoesNotRequireRemoteRevocation() {
        assertEquals(
            UnpairRevocationDecision.NoPeer,
            UnpairRevocationPolicy.decide(
                peerPresent = false,
                relayRevocationRequired = null,
                lanBindingId = null,
                relayUrl = null,
            ),
        )
    }

    @Test
    fun relayPairRequiresCanonicalRemoteRevocation() {
        assertEquals(
            UnpairRevocationDecision.Relay("wss://relay.example.test"),
            UnpairRevocationPolicy.decide(
                peerPresent = true,
                relayRevocationRequired = true,
                lanBindingId = null,
                relayUrl = "  wss://relay.example.test/  ",
            ),
        )
    }

    @Test
    fun offlineOnlyPairSkipsInapplicableRemoteRevocation() {
        assertEquals(
            UnpairRevocationDecision.OfflineOnly,
            UnpairRevocationPolicy.decide(
                peerPresent = true,
                relayRevocationRequired = false,
                lanBindingId = "binding-id",
                relayUrl = null,
            ),
        )
    }

    @Test
    fun legacyPeerWithoutAnyRouteFailsClosed() {
        val error = assertFailsWith<IllegalStateException> {
            UnpairRevocationPolicy.decide(
                peerPresent = true,
                relayRevocationRequired = null,
                lanBindingId = null,
                relayUrl = null,
            )
        }

        assertEquals("missing_relay_url", error.message)
    }

    @Test
    fun blankRelayUrlIsAbsent() {
        assertEquals(
            UnpairRevocationDecision.OfflineOnly,
            UnpairRevocationPolicy.decide(
                peerPresent = true,
                relayRevocationRequired = false,
                lanBindingId = "binding-id",
                relayUrl = "   ",
            ),
        )
    }

    @Test
    fun explicitlyOfflinePairIgnoresConfiguredButUnenrolledRelay() {
        assertEquals(
            UnpairRevocationDecision.OfflineOnly,
            UnpairRevocationPolicy.decide(
                peerPresent = true,
                relayRevocationRequired = false,
                lanBindingId = "binding-id",
                relayUrl = "wss://relay.example.test",
            ),
        )
    }

    @Test
    fun explicitlyRelayEnrolledPairFailsWhenEndpointIsMissing() {
        val error = assertFailsWith<IllegalStateException> {
            UnpairRevocationPolicy.decide(
                peerPresent = true,
                relayRevocationRequired = true,
                lanBindingId = "binding-id",
                relayUrl = null,
            )
        }

        assertEquals("missing_relay_url", error.message)
    }

    @Test
    fun legacyLanPairWithoutConfiguredRelayIsTreatedAsOfflineOnly() {
        assertEquals(
            UnpairRevocationDecision.OfflineOnly,
            UnpairRevocationPolicy.decide(
                peerPresent = true,
                relayRevocationRequired = null,
                lanBindingId = "legacy-binding",
                relayUrl = null,
            ),
        )
    }

    @Test
    fun offlineOnlyExecutionDoesNotMarkOrRevoke() = runBlocking {
        val effects = mutableListOf<String>()

        UnpairRevocationExecutor.execute(
            decision = UnpairRevocationDecision.OfflineOnly,
            markRevocationIntent = {
                effects += "mark"
                true
            },
            revoke = { _, _ -> effects += "revoke" },
        )

        assertEquals(emptyList(), effects)
    }

    @Test
    fun relayExecutionMarksBeforeRevoking() = runBlocking {
        val effects = mutableListOf<String>()

        UnpairRevocationExecutor.execute(
            decision = UnpairRevocationDecision.Relay("wss://relay.example.test"),
            markRevocationIntent = {
                effects += "mark"
                true
            },
            revoke = { relayUrl, markerPresent ->
                effects += "revoke:$relayUrl:$markerPresent"
            },
        )

        assertEquals(
            listOf("mark", "revoke:wss://relay.example.test:true"),
            effects,
        )
    }
}
