package co.twinotify.core.service

import co.twinotify.core.storage.LegacyForwardResult
import co.twinotify.core.storage.OutboundMessage
import co.twinotify.core.storage.CustodyAcceptanceResult
import co.twinotify.core.storage.RelayReceiptResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class RelayTransportTest {
    private val originalId = "11111111-1111-4111-8111-111111111111"
    private val receiptId = "22222222-2222-4222-8222-222222222222"
    private val putId = "33333333-3333-4333-8333-333333333333"
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val endpoint = RelayUrlPolicy.parse("wss://relay.example/ws", debug = false).webSocket
    private val envelope = """{"v":2,"type":"enc","msg_id":"$originalId","origin_device":"dev-a","created_at":1000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}"""

    @Test
    fun acceptedEventRetainsTypeAfterCustodyDeletesTheRow() = runTest {
        val store = RecordingStore().apply { rows += outbound(putId, eventType = "unpair") }
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        advanceTimeBy(1_000L)
        runCurrent()

        connector.text(RelayFrame.Accepted(putId, 1_001L))
        runCurrent()

        assertTrue(events.contains(TransportEvent.RelayAccepted(putId, 1_001L, "unpair")))
        job.cancelAndJoin()
    }

    @Test
    fun staleAcceptanceDoesNotFabricateAnEventType() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        connector.text(RelayFrame.Accepted(putId, 1_001L))
        runCurrent()

        assertTrue(events.contains(TransportEvent.RelayAccepted(putId, 1_001L, null)))
        job.cancelAndJoin()
    }

    @Test
    fun actualTransportAckWaitsForReceiptAcceptanceAndIsMarkedOnlyAfterSend() = runTest {
        val store = RecordingStore().apply {
            linkReceiptToAck(receiptId, RelayAckRecord(originalId, digest))
        }
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()

        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2, 1), 2))
        connector.text(RelayFrame.Deliver(1_000L, envelope))
        runCurrent()
        assertTrue(connector.socket.frames.none { it is RelayFrame.Ack })
        assertTrue(events.any { it is TransportEvent.Delivery })

        connector.text(RelayFrame.Accepted(receiptId, 1_001L))
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(RelayFrame.Ack(originalId, digest)), connector.socket.frames.filterIsInstance<RelayFrame.Ack>())
        assertEquals(1, store.ackMarks)
        job.cancelAndJoin()
    }

    @Test
    fun reconnectWaitsForMatchingTerminalCallbackAfterClientClose() = runTest {
        val store = RecordingStore().apply { rows += outbound(putId) }
        val connector = AsyncConnector(
            clock = { testScheduler.currentTime },
            sendResult = { frame -> frame !is RelayFrame.Put },
        )
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, random = UpperBoundRandom, reconnect = true)
                .run(endpoint).collect()
        }
        runCurrent()
        connector.open(0)
        connector.text(0, RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, connector.sockets[0].closeCalls)

        advanceTimeBy(2_999L)
        runCurrent()
        val connectionsBeforeTerminal = connector.connectionCount
        val maxBeforeTerminal = connector.maxActiveSockets

        connector.serverClosed(0, "closed")
        runCurrent()
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals(1, connectionsBeforeTerminal)
        assertEquals(1, maxBeforeTerminal)
        assertEquals(2, connector.connectionCount)
        assertEquals(1, connector.maxActiveSockets)
        job.cancelAndJoin()
    }

    @Test
    fun missingCloseCallbackFallsBackToCancelBeforeReconnect() = runTest {
        val store = RecordingStore().apply { rows += outbound(putId) }
        val connector = AsyncConnector(
            clock = { testScheduler.currentTime },
            sendResult = { frame -> frame !is RelayFrame.Put },
        )
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, random = UpperBoundRandom, reconnect = true)
                .run(endpoint).collect()
        }
        runCurrent()
        connector.open(0)
        connector.text(0, RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        advanceTimeBy(1_000L)
        runCurrent()

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(1, connector.connectionCount)
        assertEquals(0, connector.sockets[0].cancelCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, connector.sockets[0].cancelCalls)
        assertEquals(0, connector.activeSockets)

        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals(2, connector.connectionCount)
        assertEquals(1, connector.maxActiveSockets)
        job.cancelAndJoin()
    }

    @Test
    fun lateOnOpenAfterSessionCancellationIsClosedWithoutHelloOrConnected() = runTest {
        val store = RecordingStore()
        val connector = AsyncConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()
        assertEquals(1, connector.connectionCount)

        job.cancelAndJoin()
        val callbackResult = runCatching { connector.open(0) }

        assertTrue(callbackResult.isSuccess)
        assertTrue(events.none { it is TransportEvent.Connected })
        assertTrue(connector.sockets[0].frames.none { it is RelayFrame.Hello })
        assertEquals(1, connector.sockets[0].closeCalls)
    }

    @Test
    fun distinctRejectedOpenIsCancelledAndCannotTerminateOwnedSession() = runTest {
        val connector = AsyncConnector(clock = { testScheduler.currentTime })
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(RecordingStore().repo, connector = connector, random = UpperBoundRandom, reconnect = true)
                .run(endpoint).collect(events::add)
        }
        runCurrent()
        connector.open(0)
        val rejected = connector.detachedSocket()

        connector.open(0, rejected)
        connector.rejectedSocketText(0, rejected, RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        connector.rejectedSocketClosed(0, rejected, "stale")
        runCurrent()
        advanceTimeBy(2_999L)
        runCurrent()

        assertEquals(1, rejected.closeCalls)
        assertEquals(1, rejected.cancelCalls)
        assertEquals(1, connector.connectionCount)
        assertEquals(1, connector.activeSockets)
        assertTrue(events.none { it is TransportEvent.Authenticated })
        job.cancelAndJoin()
    }

    @Test
    fun failureCompletesSessionBeforeBlockedFailureEventEmission() = runTest {
        val store = RecordingStore()
        val connector = AsyncConnector()
        val collectorRelease = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect { event ->
                if (event is TransportEvent.Connected) {
                    connected.complete(Unit)
                    collectorRelease.await()
                }
            }
        }
        runCurrent()
        connector.open(0)
        connected.await()

        val failure = connector.failAsync(0, IllegalStateException("boom"))
        assertTrue(failure.started.await(1, TimeUnit.SECONDS))
        runCurrent()
        val closeCallsBeforeCollectorRelease = connector.sockets[0].closeCalls

        collectorRelease.complete(Unit)
        runCurrent()
        failure.thread.join(1_000L)
        job.cancelAndJoin()
        assertEquals(1, closeCallsBeforeCollectorRelease)
        assertTrue(!failure.thread.isAlive)
    }

    @Test
    fun deliveryBeforeCapabilitiesIsRejectedAndNeverEmitted() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()

        connector.text(RelayFrame.Deliver(1_000L, envelope))
        runCurrent()

        assertTrue(events.any { it is TransportEvent.Failed })
        assertTrue(events.none { it is TransportEvent.Delivery })
        assertEquals(1, connector.socket.closeCalls)
        job.join()
    }

    @Test
    fun preAuthViolationDoesNotDrainLaterQueuedFrames() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()

        connector.text(RelayFrame.Deliver(1_000L, envelope))
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        connector.text(RelayFrame.Deliver(1_001L, envelope))
        job.join()

        assertTrue(events.any { it is TransportEvent.Failed })
        assertTrue(events.none { it is TransportEvent.Authenticated })
        assertTrue(events.none { it is TransportEvent.Delivery })
    }

    @Test
    fun cancellationClosesSocketExactlyOnceAndStopsSessionWorkers() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = true).run(endpoint).collect()
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        runCurrent()

        job.cancelAndJoin()
        runCurrent()

        assertEquals(1, connector.socket.closeCalls)
        assertEquals(0, connector.activeSockets)
        assertEquals(1, connector.maxActiveSockets)
    }

    @Test
    fun normalCloseDrainsQueuedFramesInCallbackOrderBeforeReturning() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()

        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        connector.text(RelayFrame.Accepted(receiptId, 1_001L))
        connector.text(RelayFrame.Deliver(1_002L, envelope))
        connector.serverClosed("done")
        job.join()

        assertEquals(listOf(receiptId), store.accepted)
        assertEquals(
            listOf("authenticated", "accepted", "delivery", "closed"),
            events.mapNotNull {
                when (it) {
                    is TransportEvent.Authenticated -> "authenticated"
                    is TransportEvent.RelayAccepted -> "accepted"
                    is TransportEvent.Delivery -> "delivery"
                    is TransportEvent.Closed -> "closed"
                    else -> null
                }
            },
        )
        assertEquals(1, connector.socket.closeCalls)
    }

    @Test
    fun reconnectWaitsForPriorConsumerCleanupAndNeverOwnsTwoSockets() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = RecordingStore(blockAcceptanceUntil = release)
        val connector = ManualConnector()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, random = UpperBoundRandom, reconnect = true)
                .run(endpoint).collect()
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        connector.text(RelayFrame.Accepted(receiptId, 1_001L))
        store.acceptanceEntered.await()

        connector.serverClosed("retry")
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(1, connector.connectionCount, "reconnect must wait for the old consumer to finish")

        release.complete(Unit)
        runCurrent()
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals(2, connector.connectionCount)
        assertEquals(1, connector.maxActiveSockets)
        job.cancelAndJoin()
    }

    @Test
    fun helloPrecedesCapabilitiesAndOutboxFlushIsGatedByCapabilities() = runTest {
        val store = RecordingStore().apply { rows += outbound(putId) }
        val connector = ManualConnector()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect()
        }
        runCurrent()

        assertEquals(listOf<RelayFrame>(RelayFrame.Hello(listOf(2, 1), "0.8.0")), connector.socket.frames)
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(connector.socket.frames.none { it is RelayFrame.Put })

        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, connector.socket.frames.filterIsInstance<RelayFrame.Put>().size)
        job.cancelAndJoin()
    }

    @Test
    fun duplicateCapabilitiesDoNotRestartHealthySessionClock() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(
                store.repo,
                connector = connector,
                random = UpperBoundRandom,
                clock = { testScheduler.currentTime },
                reconnect = true,
            ).run(endpoint).collect(events::add)
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        runCurrent()
        advanceTimeBy(29_999L)
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        runCurrent()
        advanceTimeBy(2L)
        connector.serverClosed("healthy")
        runCurrent()

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, connector.connectionCount, "30 continuous authenticated seconds reset reconnect delay to 1s")
        job.cancelAndJoin()
    }

    @Test
    fun closeRacingFirstCapabilitiesCannotObserveAuthBeforeTimestamp() = runTest {
        val store = RecordingStore()
        lateinit var connector: ManualConnector
        var clockCalls = 0
        val clock = {
            if (clockCalls++ == 0) {
                connector.serverClosed("auth-race")
                100L
            } else {
                30_100L
            }
        }
        connector = ManualConnector()
        val job = backgroundScope.launch {
            RelayTransport(
                store.repo,
                connector = connector,
                random = UpperBoundRandom,
                clock = clock,
                reconnect = true,
            ).run(endpoint).collect()
        }
        runCurrent()

        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, connector.connectionCount, "a close before auth publication is not a healthy session")
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(2, connector.connectionCount)
        job.cancelAndJoin()
    }

    @Test
    fun decorrelatedReconnectJitterIsDeterministicAndBounded() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector(clock = { testScheduler.currentTime })
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, random = UpperBoundRandom, reconnect = true)
                .run(endpoint).collect()
        }
        runCurrent()

        val expectedDelays = listOf(2_999L, 8_996L, 26_987L, 59_999L)
        expectedDelays.forEach { delayMs ->
            connector.serverClosed("retry")
            runCurrent()
            advanceTimeBy(delayMs)
            runCurrent()
        }

        assertEquals(listOf(0L, 2_999L, 11_995L, 38_982L, 98_981L), connector.connectionTimes)
        assertTrue(connector.connectionTimes.zipWithNext { a, b -> b - a }.all { it in 1_000L..60_000L })
        job.cancelAndJoin()
    }

    @Test
    fun floorTwoCannotDowngradeOnLaterCapabilities() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()

        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(1), 1))
        runCurrent()

        assertEquals(listOf(2, 2), events.filterIsInstance<TransportEvent.Authenticated>().map { it.floor })
        assertTrue(events.none { it is TransportEvent.LegacyOnlineOnly })
        job.cancelAndJoin()
    }

    @Test
    fun authHeadersProviderIsInvokedFreshForEveryReconnect() = runTest {
        val store = RecordingStore()
        val connector = ManualConnector()
        var headerCalls = 0
        val job = backgroundScope.launch {
            RelayTransport(
                store.repo,
                authHeadersProvider = { mapOf("Authorization" to "Bearer token-${++headerCalls}") },
                connector = connector,
                random = UpperBoundRandom,
                reconnect = true,
            ).run(endpoint).collect()
        }
        runCurrent()
        connector.serverClosed("retry")
        advanceTimeBy(2_999L)
        runCurrent()

        assertEquals(2, connector.connectionCount)
        assertEquals(2, headerCalls)
        assertEquals(
            listOf("Bearer token-1", "Bearer token-2"),
            connector.headers.map { it.getValue("Authorization") },
        )
        job.cancelAndJoin()
    }

    @Test
    fun failedAckWriteStaysReadyAndReconnectResendsIt() = runTest {
        val store = RecordingStore().apply {
            readyAck = RelayAckRecord(originalId, digest)
        }
        lateinit var connector: ManualConnector
        connector = ManualConnector(sendResult = { frame -> frame !is RelayFrame.Ack || connectionCount > 1 })
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, random = UpperBoundRandom, reconnect = true)
                .run(endpoint).collect()
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, store.ackMarks)
        connector.serverClosed("write failed")
        advanceTimeBy(2_999L)
        runCurrent()

        assertEquals(2, connector.connectionCount)
        assertEquals(1, connector.sockets.first().closeCalls)
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, store.ackMarks)
        assertEquals(2, connector.sockets.flatMap { it.frames }.filterIsInstance<RelayFrame.Ack>().size)
        job.cancelAndJoin()
    }

    @Test
    fun failedPutWriteStaysSendableAndClosesSessionForReconnect() = runTest {
        val store = RecordingStore().apply { rows += outbound(putId) }
        val connector = ManualConnector(sendResult = { frame -> frame is RelayFrame.Hello })
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, random = UpperBoundRandom, reconnect = true)
                .run(endpoint).collect()
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2))
        advanceTimeBy(1_000L)
        runCurrent()
        connector.serverClosed("write failed")
        advanceTimeBy(2_999L)
        runCurrent()

        assertEquals(0, store.putMarks)
        assertEquals(2, connector.connectionCount)
        assertEquals(1, connector.sockets.first().closeCalls)
        job.cancelAndJoin()
    }

    @Test
    fun floorOneUsesExplicitLegacyForwardAndRetiresOnlyAfterForwarded() = runTest {
        val store = RecordingStore().apply { rows += outbound(putId, protocolVersion = 1) }
        val connector = ManualConnector(sendResult = { frame ->
            if (frame is RelayFrame.Put) {
                text(RelayFrame.LegacyForwarded(putId))
                serverClosed("done")
            }
            true
        })
        val events = mutableListOf<TransportEvent>()
        val job = backgroundScope.launch {
            RelayTransport(store.repo, connector = connector, reconnect = false).run(endpoint).collect(events::add)
        }
        runCurrent()
        connector.text(RelayFrame.Capabilities(listOf(2, 1), listOf(1), 1))
        advanceTimeBy(1_000L)
        job.join()

        assertTrue(events.any { it is TransportEvent.LegacyOnlineOnly })
        assertTrue(events.any { it is TransportEvent.LegacyForwarded })
        assertTrue(connector.socket.frames.any { it is RelayFrame.Put })
        assertTrue(store.rows.isEmpty())
    }

    private fun outbound(
        id: String,
        protocolVersion: Int = 2,
        eventType: String = "notif.post",
    ) = OutboundMessage(
        msgId = id,
        canonId = null,
        sequence = null,
        eventType = eventType,
        protocolVersion = protocolVersion,
        envelopeJson = if (protocolVersion == 2) envelope.replace(originalId, id) else
            """{"v":1,"type":"enc","msg_id":"$id","origin_device":"dev-a","ts":1000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}""",
        envelopeSha256 = digest,
        byteSize = 1,
        createdAt = 1,
        expiresAt = 10_000,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 0,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = true,
    )

    private class ManualConnector(
        private val sendResult: ManualConnector.(RelayFrame) -> Boolean = { true },
        private val clock: () -> Long = { 0L },
    ) : RelaySocketConnector {
        private val listeners = mutableListOf<RelaySocketListener>()
        val sockets = mutableListOf<RecordingRelaySocket>()
        val headers = mutableListOf<Map<String, String>>()
        val connectionTimes = mutableListOf<Long>()
        var activeSockets = 0
            private set
        var maxActiveSockets = 0
            private set
        val connectionCount: Int get() = sockets.size
        val socket: RecordingRelaySocket get() = sockets.last()

        override fun connect(
            url: RelayWebSocketUrl,
            headers: Map<String, String>,
            listener: RelaySocketListener,
        ): RelaySocket {
            listeners += listener
            this.headers += headers
            connectionTimes += clock()
            activeSockets += 1
            maxActiveSockets = maxOf(maxActiveSockets, activeSockets)
            val next = RecordingRelaySocket(
                sendResult = { frame -> sendResult(frame) },
                onClose = { activeSockets -= 1 },
            )
            sockets += next
            listener.onOpen(next)
            return next
        }

        fun text(frame: RelayFrame) = listeners.last().onText(socket, RelayFrameCodec.encode(frame))
        fun serverClosed(reason: String) = listeners.last().onClosed(socket, reason)
    }

    private class RecordingRelaySocket(
        private val sendResult: (RelayFrame) -> Boolean,
        private val onClose: () -> Unit,
    ) : RelaySocket {
        val frames = mutableListOf<RelayFrame>()
        var closeCalls = 0
            private set

        override fun send(text: String): Boolean {
            val frame = RelayFrameCodec.decode(text)
            frames += frame
            return sendResult(frame)
        }

        override fun close(code: Int, reason: String) {
            closeCalls += 1
            if (closeCalls == 1) onClose()
        }

        override fun cancel() = Unit
    }

    private class AsyncConnector(
        private val clock: () -> Long = { 0L },
        private val sendResult: (RelayFrame) -> Boolean = { true },
    ) : RelaySocketConnector {
        private val listeners = mutableListOf<RelaySocketListener>()
        val sockets = mutableListOf<AsyncRelaySocket>()
        val connectionTimes = mutableListOf<Long>()
        var activeSockets = 0
            private set
        var maxActiveSockets = 0
            private set
        val connectionCount: Int get() = sockets.size

        override fun connect(
            url: RelayWebSocketUrl,
            headers: Map<String, String>,
            listener: RelaySocketListener,
        ): RelaySocket {
            listeners += listener
            connectionTimes += clock()
            activeSockets += 1
            maxActiveSockets = maxOf(maxActiveSockets, activeSockets)
            val socket = AsyncRelaySocket(
                sendResult = sendResult,
                onTerminal = { activeSockets -= 1 },
            )
            sockets += socket
            return socket
        }

        fun open(index: Int, socket: AsyncRelaySocket = sockets[index]) = listeners[index].onOpen(socket)
        fun text(index: Int, frame: RelayFrame) =
            listeners[index].onText(sockets[index], RelayFrameCodec.encode(frame))

        fun detachedSocket() = AsyncRelaySocket(sendResult = sendResult, onTerminal = {})

        fun rejectedSocketText(index: Int, socket: AsyncRelaySocket, frame: RelayFrame) {
            listeners[index].onText(socket, RelayFrameCodec.encode(frame))
        }

        fun rejectedSocketClosed(index: Int, socket: AsyncRelaySocket, reason: String) {
            socket.markTerminal()
            listeners[index].onClosed(socket, reason)
        }

        fun serverClosed(index: Int, reason: String) {
            sockets[index].markTerminal()
            listeners[index].onClosed(sockets[index], reason)
        }

        fun failAsync(index: Int, error: Throwable): AsyncFailure {
            val started = CountDownLatch(1)
            val failureThread = thread(name = "relay-failure-test") {
                sockets[index].markTerminal()
                started.countDown()
                listeners[index].onFailure(sockets[index], error)
            }
            return AsyncFailure(started, failureThread)
        }
    }

    private class AsyncRelaySocket(
        private val sendResult: (RelayFrame) -> Boolean,
        private val onTerminal: () -> Unit,
    ) : RelaySocket {
        val frames = mutableListOf<RelayFrame>()
        var closeCalls = 0
            private set
        var cancelCalls = 0
            private set
        private var terminal = false

        override fun send(text: String): Boolean {
            val frame = RelayFrameCodec.decode(text)
            frames += frame
            return sendResult(frame)
        }

        override fun close(code: Int, reason: String) {
            closeCalls += 1
        }

        override fun cancel() {
            cancelCalls += 1
            markTerminal()
        }

        fun markTerminal() {
            if (!terminal) {
                terminal = true
                onTerminal()
            }
        }
    }

    private data class AsyncFailure(
        val started: CountDownLatch,
        val thread: Thread,
    )

    private class RecordingStore(
        private val blockAcceptanceUntil: CompletableDeferred<Unit>? = null,
    ) : OutboxStore {
        val repo = OutboxRepository(this, clock = { 1_000L })
        val rows = mutableListOf<OutboundMessage>()
        val accepted = mutableListOf<String>()
        val acceptanceEntered = CompletableDeferred<Unit>()
        var readyAck: RelayAckRecord? = null
        private var linkedReceipt: Pair<String, RelayAckRecord>? = null
        var putMarks = 0
        var ackMarks = 0

        fun linkReceiptToAck(receiptMsgId: String, ack: RelayAckRecord) {
            linkedReceipt = receiptMsgId to ack
        }

        override suspend fun sendable(now: Long, limit: Int) = rows.take(limit)

        override suspend fun markSent(msgId: String, retryAt: Long): Int {
            putMarks += 1
            rows.removeAll { it.msgId == msgId }
            return 1
        }

        override suspend fun legacyForwarded(msgId: String, forwardedAt: Long): LegacyForwardResult {
            val removed = rows.removeAll { it.msgId == msgId }
            return if (removed) LegacyForwardResult.Deleted else LegacyForwardResult.Missing
        }

        override suspend fun acceptCustody(
            msgId: String,
            route: CustodyRoute,
            acceptedAt: Long,
            retryAt: Long,
        ): CustodyAcceptanceResult {
            acceptanceEntered.complete(Unit)
            blockAcceptanceUntil?.let { withContext(NonCancellable) { it.await() } }
            accepted += msgId
            linkedReceipt?.takeIf { it.first == msgId }?.let { (_, ack) ->
                readyAck = ack
                linkedReceipt = null
            }
            return CustodyAcceptanceResult.Accepted
        }

        override suspend fun applyPeerReceipt(
            ackedMsgId: String,
            envelopeSha256: String,
            status: String,
            reason: String?,
            occurredAt: Long,
        ) = RelayReceiptResult.Deleted

        override suspend fun rejectRelay(msgId: String, reason: String, occurredAt: Long, retryAt: Long) =
            RelayRejectionResult.Retained

        override suspend fun expireRelay(msgId: String, expiredAt: Long) = RelayReceiptResult.Deleted

        override suspend fun readyRelayAcks(limit: Int) =
            listOfNotNull(readyAck).take(limit)

        override suspend fun markRelayAckSent(msgId: String, envelopeSha256: String): Int {
            ackMarks += 1
            readyAck = null
            return 1
        }
    }

    private data object UpperBoundRandom : Random() {
        override fun nextBits(bitCount: Int): Int = -1 ushr (32 - bitCount)
        override fun nextLong(from: Long, until: Long): Long = until - 1
    }

}
