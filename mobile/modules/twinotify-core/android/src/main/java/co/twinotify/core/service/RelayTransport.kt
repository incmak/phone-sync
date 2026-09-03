package co.twinotify.core.service

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface TransportEvent {
    data object Connected : TransportEvent
    data class Authenticated(val floor: Int, val peerFeatures: Set<String> = emptySet()) : TransportEvent
    data object LegacyOnlineOnly : TransportEvent
    data class LegacyForwarded(val msgId: String) : TransportEvent
    data class RelayAccepted(val msgId: String, val acceptedAt: Long, val eventType: String? = null) : TransportEvent
    data class RelayRejected(val msgId: String, val reason: String) : TransportEvent
    data class RelayExpired(val msgId: String, val expiredAt: Long) : TransportEvent
    data class Delivery(val acceptedAt: Long, val envelope: String) : TransportEvent
    data class Closed(val reason: String?) : TransportEvent
    data class Failed(val error: Throwable) : TransportEvent
}

interface RelaySocket {
    fun send(text: String): Boolean
    fun close(code: Int = 1000, reason: String = "client stopping")
    fun cancel()
}

interface RelaySocketListener {
    fun onOpen(socket: RelaySocket)
    fun onText(socket: RelaySocket, text: String)
    fun onClosed(socket: RelaySocket, reason: String?)
    fun onFailure(socket: RelaySocket, error: Throwable)
}

fun interface RelaySocketConnector {
    fun connect(
        url: RelayWebSocketUrl,
        headers: Map<String, String>,
        listener: RelaySocketListener,
    ): RelaySocket
}

private class OkHttpRelaySocketConnector(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : RelaySocketConnector {
    override fun connect(
        url: RelayWebSocketUrl,
        headers: Map<String, String>,
        listener: RelaySocketListener,
    ): RelaySocket {
        // Keep the typed ws/wss URL through policy and only translate at the OkHttp
        // request boundary. A wss endpoint always becomes https here, never http.
        val requestBuilder = Request.Builder().url(url.asHttpUrl())
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.build()
        val relaySocketRef = AtomicReference<OkHttpRelaySocket?>()
        fun stableSocket(webSocket: WebSocket): OkHttpRelaySocket =
            relaySocketRef.updateAndGet { current -> current ?: OkHttpRelaySocket(webSocket) }!!
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen(stableSocket(webSocket))
            }
            override fun onMessage(webSocket: WebSocket, text: String) =
                listener.onText(stableSocket(webSocket), text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(stableSocket(webSocket), reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(stableSocket(webSocket), t)
        })
        return stableSocket(socket)
    }
}

private class OkHttpRelaySocket(private val socket: WebSocket) : RelaySocket {
    override fun send(text: String): Boolean = socket.send(text)
    override fun close(code: Int, reason: String) { socket.close(code, reason) }
    override fun cancel() = socket.cancel()
}

/**
 * Single-owner v2 relay loop. One bounded raw-frame channel feeds one ordered consumer; a
 * reconnect cannot start until the prior socket's session has fully terminated.
 */
class RelayTransport(
    private val outbox: OutboxRepository,
    private val authHeadersProvider: () -> Map<String, String> = { emptyMap() },
    private val connector: RelaySocketConnector = OkHttpRelaySocketConnector(),
    private val appVersion: String = "0.8.0",
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val reconnect: Boolean = true,
    private val afterCleanup: () -> Unit = {},
) {
    private val pendingEventTypes = Collections.synchronizedMap(HashMap<String, String>())
    fun run(url: RelayWebSocketUrl): Flow<TransportEvent> = channelFlow {
        var previousDelay = MIN_BACKOFF_MS
        var attempt = 0
        while (isActive) {
            // Use a suspending send at the transport boundary. A Delivery is durable only
            // after SyncService has accepted it into its bounded ordered worker; dropping a
            // channelFlow trySend here would strand relay custody with no receipt.
            val session = runSession(url) { event -> send(event) }
            if (!reconnect) break
            if (session.authenticatedForMs >= AUTH_RESET_MS) attempt = 0 else attempt += 1
            if (!isActive) break
            val delayMs = decorrelatedDelay(previousDelay, attempt)
            previousDelay = delayMs
            kotlinx.coroutines.delay(delayMs)
        }
    }.buffer(Channel.RENDEZVOUS)

    private suspend fun runSession(url: RelayWebSocketUrl, emit: suspend (TransportEvent) -> Unit): SessionResult {
        val raw = Channel<String>(RAW_FRAME_CAPACITY)
        val closed = CompletableDeferred<SessionResult>()
        val terminalCallback = CompletableDeferred<Unit>()
        val authenticated = AtomicBoolean(false)
        val authenticatedAt = AtomicLong(0L)
        val negotiatedFloor = AtomicInteger(0)
        val socketRef = AtomicReference<RelaySocket?>(null)
        val acceptingCallbacks = AtomicBoolean(true)
        val lifecycleLock = Any()
        val closedSockets = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<RelaySocket, Boolean>()),
        )
        val cancelledSockets = Collections.synchronizedSet(
            Collections.newSetFromMap(IdentityHashMap<RelaySocket, Boolean>()),
        )
        var normalTermination = false

        fun closeOnce(socket: RelaySocket) {
            if (closedSockets.add(socket)) socket.close(1000)
        }

        fun cancelOnce(socket: RelaySocket) {
            if (cancelledSockets.add(socket)) socket.cancel()
        }

        fun sessionResult(reason: String?): SessionResult = SessionResult(
            authenticatedForMs = if (authenticated.get()) {
                (clock() - authenticatedAt.get()).coerceAtLeast(0L)
            } else {
                0L
            },
            reason = reason,
        )

        fun endSession(reason: String?) {
            closed.complete(sessionResult(reason))
        }

        val listener = object : RelaySocketListener {
            override fun onOpen(socket: RelaySocket) {
                synchronized(lifecycleLock) {
                    val current = socketRef.get()
                    val ownsSocket = current === socket ||
                        (current == null && socketRef.compareAndSet(null, socket))
                    if (!acceptingCallbacks.get() || !ownsSocket) {
                        closeOnce(socket)
                        cancelOnce(socket)
                        return
                    }
                    // OkHttp callbacks are not suspendable. Control/error events are rare and
                    // must still be lossless, so bridge them synchronously rather than silently
                    // discarding a full channelFlow buffer.
                    val connected = runCatching { runBlocking { emit(TransportEvent.Connected) } }.isSuccess
                    if (!connected || !acceptingCallbacks.get()) {
                        closeOnce(socket)
                        cancelOnce(socket)
                        return
                    }
                    if (!socket.send(RelayFrameCodec.encode(
                            RelayFrame.Hello(listOf(2, 1), appVersion, RelayFeatures.CURRENT),
                        ))) {
                        endSession("relay hello write failed")
                    }
                }
            }
            override fun onText(socket: RelaySocket, text: String) {
                val ownsActiveSession = synchronized(lifecycleLock) {
                    acceptingCallbacks.get() && socketRef.get() === socket
                }
                if (!ownsActiveSession) {
                    if (socketRef.get() !== socket) cancelOnce(socket)
                    return
                }
                // OkHttp delivers messages on a single callback thread. Suspend that callback
                // at the bounded ingress channel instead of closing a healthy session when the
                // relay sends a mailbox burst. This is backpressure, not an unbounded queue.
                runCatching { runBlocking { raw.send(text) } }
            }
            override fun onClosed(socket: RelaySocket, reason: String?) {
                val ownsSocket = synchronized(lifecycleLock) {
                    val current = socketRef.get()
                    current === socket || (current == null && socketRef.compareAndSet(null, socket))
                }
                if (!ownsSocket) {
                    cancelOnce(socket)
                    return
                }
                terminalCallback.complete(Unit)
                endSession(reason)
            }
            override fun onFailure(socket: RelaySocket, error: Throwable) {
                val ownsSocket = synchronized(lifecycleLock) {
                    val current = socketRef.get()
                    current === socket || (current == null && socketRef.compareAndSet(null, socket))
                }
                if (!ownsSocket) {
                    cancelOnce(socket)
                    return
                }
                terminalCallback.complete(Unit)
                endSession(error.message)
                runCatching { runBlocking { emit(TransportEvent.Failed(error)) } }
            }
        }
        val sessionScope = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext)
        val consumer = sessionScope.launch {
            for (text in raw) {
                val parsed = runCatching { RelayFrameCodec.decode(text) }
                if (parsed.isFailure) {
                    emit(TransportEvent.Failed(parsed.exceptionOrNull()!!))
                    continue
                }
                val frame = parsed.getOrThrow()
                when (frame) {
                    is RelayFrame.Capabilities -> {
                        if (!authenticated.get()) {
                            authenticatedAt.set(clock())
                            authenticated.set(true)
                        }
                        val floor = negotiatedFloor.updateAndGet { current -> maxOf(current, frame.floor) }
                        emit(TransportEvent.Authenticated(floor, frame.peerFeatures))
                        if (floor == 1) {
                            emit(TransportEvent.LegacyOnlineOnly)
                        }
                    }
                    else -> if (!authenticated.get()) {
                        val error = IllegalStateException("relay frame ${frame.type} arrived before capabilities")
                        emit(TransportEvent.Failed(error))
                        endSession(error.message)
                        break
                    } else when (frame) {
                    is RelayFrame.Accepted -> {
                        val eventType = pendingEventTypes.remove(frame.msgId)
                        outbox.onRelayAccepted(frame.msgId, frame.acceptedAt)
                        emit(TransportEvent.RelayAccepted(frame.msgId, frame.acceptedAt, eventType))
                        // The periodic flusher owns outbound puts. Re-querying the whole
                        // sendable/ACK sets for every accepted frame can starve raw-frame
                        // draining during a reconnect burst and trigger the overflow guard.
                    }
                    is RelayFrame.LegacyForwarded -> {
                        pendingEventTypes.remove(frame.msgId)
                        outbox.onLegacyForwarded(frame.msgId, clock())
                        emit(TransportEvent.LegacyForwarded(frame.msgId))
                    }
                    is RelayFrame.Rejected -> {
                        pendingEventTypes.remove(frame.msgId)
                        outbox.onRelayRejected(frame.msgId, frame.reason, attempt = 0)
                        emit(TransportEvent.RelayRejected(frame.msgId, frame.reason))
                    }
                    is RelayFrame.Expired -> {
                        pendingEventTypes.remove(frame.msgId)
                        outbox.onRelayExpired(frame.msgId, frame.expiredAt)
                        emit(TransportEvent.RelayExpired(frame.msgId, frame.expiredAt))
                    }
                    is RelayFrame.Deliver -> {
                        // Applying/decrypting is owned by the inbound processor. It must create a
                        // peer receipt and wait for that receipt's relay.accepted before ACK.
                        emit(TransportEvent.Delivery(frame.acceptedAt, frame.envelope))
                    }
                    else -> Unit
                    }
                }
            }
        }
        val flusher = sessionScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                if (authenticated.get()) {
                    val sent = if (negotiatedFloor.get() == 1) {
                        flushLegacy(socketRef.get())
                    } else {
                        flushV2(socketRef.get())
                    }
                    if (!sent) {
                        endSession("relay socket write failed")
                        break
                    }
                }
            }
        }
        try {
            val socket = connector.connect(url, authHeadersProvider(), listener)
            if (!socketRef.compareAndSet(null, socket) && socketRef.get() !== socket) {
                closeOnce(socket)
                cancelOnce(socket)
            }
        } catch (error: Throwable) {
            emit(TransportEvent.Failed(error))
            endSession(error.message)
        }

        var result: SessionResult? = null
        try {
            result = closed.await()
            normalTermination = true
            return result
        } finally {
            withContext(NonCancellable) {
                synchronized(lifecycleLock) { acceptingCallbacks.set(false) }
                raw.close()
                flusher.cancelAndJoin()
                if (normalTermination) consumer.join() else consumer.cancelAndJoin()
                socketRef.get()?.let { socket ->
                    closeOnce(socket)
                    if (!terminalCallback.isCompleted) {
                        val closedGracefully = withTimeoutOrNull(CLOSE_HANDSHAKE_TIMEOUT_MS) {
                            terminalCallback.await()
                            true
                        } ?: false
                        if (!closedGracefully) cancelOnce(socket)
                    }
                }
            }
            afterCleanup()
            if (normalTermination) emit(TransportEvent.Closed(result?.reason))
        }
    }

    private suspend fun flushV2(socket: RelaySocket?): Boolean {
        if (socket == null) return true
        if (!flushReadyAcks(socket)) return false
        // Floor 2 can durably carry both v2 and migrated v1 envelopes. The codec
        // validates each version while preserving the envelope bytes.
        for (row in outbox.sendable(limit = OUTBOX_FLUSH_MAX_ITEMS, now = clock())) {
            pendingEventTypes[row.msgId] = row.eventType
            if (!socket.send(RelayFrameCodec.encode(RelayFrame.Put(row.envelopeJson)))) {
                pendingEventTypes.remove(row.msgId)
                return false
            }
            outbox.markSent(row.msgId, row.attempts, clock())
        }
        return true
    }

    private suspend fun flushLegacy(socket: RelaySocket?): Boolean {
        if (socket == null) return true
        for (row in outbox.sendable(limit = 32, now = clock()).filter { it.protocolVersion == 1 }) {
            if (!socket.send(RelayFrameCodec.encode(RelayFrame.Put(row.envelopeJson)))) return false
            outbox.markSent(row.msgId, row.attempts, clock())
        }
        return true
    }

    private suspend fun flushReadyAcks(socket: RelaySocket?): Boolean {
        if (socket == null) return true
        for (ack in outbox.readyRelayAcks(32)) {
            if (!socket.send(RelayFrameCodec.encode(RelayFrame.Ack(ack.msgId, ack.envelopeSha256)))) return false
            outbox.markRelayAckSent(ack)
        }
        return true
    }

    private fun decorrelatedDelay(previous: Long, attempt: Int): Long {
        if (attempt <= 0) return MIN_BACKOFF_MS
        val upper = min(MAX_BACKOFF_MS, previous * 3)
        return random.nextLong(MIN_BACKOFF_MS, upper.coerceAtLeast(MIN_BACKOFF_MS + 1))
    }

    private data class SessionResult(val authenticatedForMs: Long, val reason: String?)

    private companion object {
        // A mailbox record can fan out into delivery, accepted, receipt, and expiry control
        // frames during reconnect. Keep ingress bounded, but large enough to absorb one relay
        // handshake burst without closing a healthy session with 1009.
        // Each legal relay frame may approach 1 MiB. Keep the ingress budget bounded to
        // Four frames per ingress lane keeps the aggregate worst-case payload below roughly
        // 17 MiB of UTF-16 storage (the flow boundary itself is rendezvous-buffered).
        const val RAW_FRAME_CAPACITY = 4
        // A legal relay frame can approach 1 MiB, while OkHttp closes a WebSocket whose
        // pending send queue exceeds 16 MiB. Keep each flush comfortably below that bound.
        const val OUTBOX_FLUSH_MAX_ITEMS = 4
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val AUTH_RESET_MS = 30_000L
        const val CLOSE_HANDSHAKE_TIMEOUT_MS = 5_000L
    }
}
