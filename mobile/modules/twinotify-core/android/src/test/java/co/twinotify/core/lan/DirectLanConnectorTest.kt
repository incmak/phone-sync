package co.twinotify.core.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

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
    fun defaultArbitrationWaitsForAValidCrossedConnectionOnRealisticHardwareLatency() = runTest {
        val first = connection(initiator = PEER, sessionSeed = 1)
        val preferred = connection(initiator = LOCAL, sessionSeed = 9)
        val connector = DirectLanConnector(
            discovery = object : LanDiscovery {
                override fun candidates(): Flow<LanCandidate> = flowOf(candidate())
                override suspend fun close() = Unit
            },
            listener = object : LanListener {
                override suspend fun accept(): AuthenticatedLanConnection = first
                override fun close() = Unit
            },
            dialer = {
                delay(1_200)
                preferred
            },
            localDeviceId = LOCAL,
            peerDeviceId = PEER,
        )

        val result = connector.connect()

        assertSame(preferred, result)
        assertTrue(first.closed, "the early crossed connection won before arbitration completed")
    }

    @Test
    fun lateCrossedConnectionsCannotMakeTheTwoPhonesKeepOppositeSessions() = runTest {
        val onPhoneA = DirectLanConnector(
            discovery = discovery(),
            listener = listener { connection(initiator = PEER, sessionSeed = 1) },
            dialer = {
                delay(2_500)
                connection(initiator = LOCAL, sessionSeed = 9)
            },
            localDeviceId = LOCAL,
            peerDeviceId = PEER,
            arbitrationGraceMillis = 2_000,
        ).connect()
        val onPhoneB = DirectLanConnector(
            discovery = discovery(),
            listener = listener { connection(initiator = LOCAL, sessionSeed = 9) },
            dialer = {
                delay(2_500)
                connection(initiator = PEER, sessionSeed = 1)
            },
            localDeviceId = PEER,
            peerDeviceId = LOCAL,
            arbitrationGraceMillis = 2_000,
        ).connect()

        assertEquals(
            onPhoneA.session.initiatorDeviceId,
            onPhoneB.session.initiatorDeviceId,
            "late crossed handshakes made the peers select opposite TCP sessions",
        )
    }

    @Test
    fun nonPreferredPhoneDoesNotCreateACrossedDialWhenPreferredInboundArrives() = runTest {
        var dialled = false
        val preferred = connection(initiator = LOCAL)
        val connector = DirectLanConnector(
            discovery = discovery(),
            listener = listener {
                delay(100)
                preferred
            },
            dialer = {
                dialled = true
                connection(initiator = PEER)
            },
            localDeviceId = PEER,
            peerDeviceId = LOCAL,
            fallbackDialDelayMillis = 3_000,
        )

        assertSame(preferred, connector.connect())
        assertFalse(dialled, "the delayed fallback dial raced a healthy preferred connection")
    }

    @Test
    fun nonPreferredPhoneStillDialsAfterThePreferredDirectionFails() = runTest {
        val fallback = connection(initiator = PEER)
        val connector = DirectLanConnector(
            discovery = discovery(),
            listener = listener { awaitCancellation() },
            dialer = { fallback },
            localDeviceId = PEER,
            peerDeviceId = LOCAL,
            arbitrationGraceMillis = 50,
            fallbackDialDelayMillis = 100,
            preferredConnectionWaitMillis = 200,
        )

        assertSame(fallback, connector.connect())
    }

    @Test
    fun authenticatedWinnerClosesAnInterruptResistantPendingListenerBeforeReturning() = runTest {
        val released = CompletableDeferred<Unit>()
        var listenerCloses = 0
        val winner = connection(initiator = LOCAL)
        val connector = DirectLanConnector(
            discovery = discovery(),
            listener = object : LanListener {
                override suspend fun accept(): AuthenticatedLanConnection = withContext(NonCancellable) {
                    released.await()
                    error("listener_closed")
                }

                override fun close() {
                    listenerCloses += 1
                    released.complete(Unit)
                }
            },
            dialer = { winner },
            localDeviceId = LOCAL,
            peerDeviceId = PEER,
            arbitrationGraceMillis = 50,
            fallbackDialDelayMillis = 0,
            preferredConnectionWaitMillis = 50,
            connectTimeoutMillis = 500,
        )

        val result = async { connector.connect() }
        try {
            advanceTimeBy(100)
            runCurrent()
            assertTrue(result.isCompleted, "the authenticated winner remained blocked behind accept()")
            assertSame(winner, result.await())
            assertEquals(1, listenerCloses)
        } finally {
            released.complete(Unit)
            result.cancelAndJoin()
        }
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
            localDeviceId = LOCAL,
            peerDeviceId = PEER,
            arbitrationGraceMillis = 50,
            fallbackDialDelayMillis = 0,
            preferredConnectionWaitMillis = 50,
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
        localDeviceId = LOCAL,
        peerDeviceId = PEER,
        arbitrationGraceMillis = 50,
    )

    private fun discovery() = object : LanDiscovery {
        override fun candidates(): Flow<LanCandidate> = flowOf(candidate())
        override suspend fun close() = Unit
    }

    private fun listener(accept: suspend () -> AuthenticatedLanConnection) = object : LanListener {
        override suspend fun accept(): AuthenticatedLanConnection = accept()
        override fun close() = Unit
    }

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
