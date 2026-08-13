package co.twinotify.core.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RevocationPolicyTest {
    @Test
    fun noContentIsTerminalSuccess() {
        assertEquals(
            RevokeOutcome.Revoked,
            RevocationPolicy.classify(204, revocationMarkerPresent = true),
        )
    }

    @Test
    fun unauthorizedAfterPersistedIntentMeansRelayAlreadyRevoked() {
        assertEquals(
            RevokeOutcome.AlreadyRevoked,
            RevocationPolicy.classify(401, revocationMarkerPresent = true),
        )
    }

    @Test
    fun unauthorizedWithoutPersistedIntentIsNotAcceptedAsSuccess() {
        assertFailsWith<IllegalStateException> {
            RevocationPolicy.classify(401, revocationMarkerPresent = false)
        }
    }

    @Test
    fun unpairOrderRequiresServiceShutdownBeforeWipe() {
        UnpairOrderPolicy.validate(
            listOf(
                UnpairStep.StopService,
                UnpairStep.AwaitServiceStopped,
                UnpairStep.RevokePeer,
                UnpairStep.WipeLocal,
            ),
        )
    }

    @Test
    fun unpairOrderRejectsWipeBeforeShutdown() {
        assertFailsWith<IllegalArgumentException> {
            UnpairOrderPolicy.validate(
                listOf(UnpairStep.StopService, UnpairStep.WipeLocal, UnpairStep.AwaitServiceStopped),
            )
        }
    }
}
