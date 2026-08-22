package co.twinotify.core.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DirectLanConnectorTest {
    @Test
    fun usesTheDialledConnectionWhenNothingInboundArrives() = runTest {
        val outbound = connection(initiator = LOCAL)
        val connector = connector(
            accept = { awaitCancellation() },
            dial = { outbound },
        )

        val result = connector.connect()

        assertSame(outbound, result)
    }

    @Test
    fun usesTheAcceptedConnectionWhenNoCandidateIsDiscovered() = runTest {
        val inbound = connection(initiator = PEER)
        val connector = connector(
            candidates = emptyFlow(),
            accept = { inbound },
            dial = { error("should not dial without a candidate") },
        )

        val result = connector.connect()

        assertSame(inbound, result)
    }

    @Test
    fun crossedConnectionsKeepTheSmallerDeviceInitiatedOneAndCloseTheOther() = runTest {
        // LOCAL sorts before PEER, so both phones must keep the LOCAL-initiated session.
        val outbound = connection(initiator = LOCAL, sessionSeed = 9)
        val inbound = connection(initiator = PEER, sessionSeed = 1)
        val connector = connector(accept = { inbound }, dial = { outbound })

        val result = connector.connect()

        assertSame(outbound, result)
        assertTrue(inbound.closed, "the losing connection was left open")
        assertFalse(outbound.closed)
    }

    @Test
    fun bothPhonesReachTheSameSurvivorFromTheSameTwoSessions() = runTest {
        // Same pair of sessions, opposite local roles. The survivor must match.
        val onPhoneA = connector(
            accept = { connection(initiator = PEER, sessionSeed = 1) },
            dial = { connection(initiator = LOCAL, sessionSeed = 9) },
        ).connect()
        val onPhoneB = connector(
            accept = { connection(initiator = LOCAL, sessionSeed = 9) },
            dial = { connection(initiator = PEER, sessionSeed = 1) },
        ).connect()

        assertEquals(onPhoneA.session.initiatorDeviceId, onPhoneB.session.initiatorDeviceId)
        assertTrue(onPhoneA.session.sessionId.contentEquals(onPhoneB.session.sessionId))
    }

    @Test
    fun aFailedDialStillYieldsAnAcceptedConnection() = runTest {
        val inbound = connection(initiator = PEER)
        val gate = CompletableDeferred<Unit>()
        val connector = connector(
            accept = {
                gate.await()
                inbound
            },
            dial = {
                gate.complete(Unit)
                throw IllegalStateException("connect refused")
            },
        )

        val result = connector.connect()

        assertSame(inbound, result)
    }

    @Test
    fun bothSidesFailingReportsTheFailureRatherThanHanging() = runTest {
        val connector = connector(
            accept = { throw IllegalStateException("listener closed") },
            dial = { throw IllegalStateException("connect refused") },
        )

        assertFailsWith<IllegalStateException> { connector.connect() }
    }

    @Test
    fun aSilentPeerTimesOutSoTheCoordinatorCanFallBackToRelay() = runTest {
        val connector = DirectLanConnector(
            discovery = object : LanDiscovery {
                override fun candidates(): Flow<LanCandidate> = emptyFlow()
                override suspend fun close() = Unit
            },
            listener = object : LanListener {
                override suspend fun accept(): AuthenticatedLanConnection = awaitCancellation()
                override fun close() = Unit
            },
            dialer = { awaitCancellation() },
            arbitrationGraceMillis = 50,
            connectTimeoutMillis = 500,
        )

        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { connector.connect() }
    }

    // ---- helpers ---------------------------------------------------------

    private fun connector(
        candidates: Flow<LanCandidate> = flowOf(candidate()),
        accept: suspend () -> AuthenticatedLanConnection,
        dial: suspend () -> AuthenticatedLanConnection,
    ) = DirectLanConnector(
        discovery = object : LanDiscovery {
            override fun candidates(): Flow<LanCandidate> = candidates
            override suspend fun close() = Unit
        },
        listener = object : LanListener {
            override suspend fun accept(): AuthenticatedLanConnection = accept()
            override fun close() = Unit
        },
        dialer = { dial() },
        arbitrationGraceMillis = 50,
    )

    private fun candidate() = LanCandidate(
        address = java.net.InetAddress.getLoopbackAddress(),
        port = 4444,
        network = { java.net.Socket() },
    )

    private fun connection(initiator: String, sessionSeed: Int = 1) = FakeConnection(
        LanAuthenticatedSession(
            peerDeviceId = PEER,
            initiatorDeviceId = initiator,
            sessionId = ByteArray(32) { (sessionSeed + it).toByte() },
        ),
    )

    private class FakeConnection(
        override val session: LanAuthenticatedSession,
    ) : AuthenticatedLanConnection {
        var closed = false
            private set
        private val frames = Channel<LanFrame>(Channel.UNLIMITED)
        override val incoming: Flow<LanFrame> = frames.consumeAsFlow()
        override suspend fun send(frame: LanFrame) = Unit
        override fun close() {
            closed = true
            frames.close()
        }
    }

    private companion object {
        const val LOCAL = "dev-00000000-0000-0000-0000-000000000001"
        const val PEER = "dev-00000000-0000-0000-0000-000000000002"
    }
}
