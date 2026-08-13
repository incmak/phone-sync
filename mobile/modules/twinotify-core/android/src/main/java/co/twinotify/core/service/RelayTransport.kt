package co.twinotify.core.service

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface TransportEvent {
    data object Connected : TransportEvent
    data class Authenticated(val floor: Int) : TransportEvent
    data object LegacyOnlineOnly : TransportEvent
    data class LegacyForwarded(val msgId: String) : TransportEvent
    data class RelayAccepted(val msgId: String, val acceptedAt: Long) : TransportEvent
    data class RelayRejected(val msgId: String, val reason: String) : TransportEvent
    data class RelayExpired(val msgId: String, val expiredAt: Long) : TransportEvent
    data class Delivery(val acceptedAt: Long, val envelope: String) : TransportEvent
    data class Closed(val reason: String?) : TransportEvent
    data class Failed(val error: Throwable) : TransportEvent
}

interface RelaySocket {
    fun send(text: String): Boolean
    fun close(code: Int = 1000, reason: String = "client stopping")
}

interface RelaySocketListener {
    fun onOpen(socket: RelaySocket)
    fun onText(text: String)
    fun onClosed(reason: String?)
    fun onFailure(error: Throwable)
}

fun interface RelaySocketConnector {
    fun connect(url: RelayWebSocketUrl, listener: RelaySocketListener): RelaySocket
}

private class OkHttpRelaySocketConnector(
    private val headersProvider: () -> Map<String, String> = { emptyMap() },
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : RelaySocketConnector {
    override fun connect(url: RelayWebSocketUrl, listener: RelaySocketListener): RelaySocket {
        // Keep the typed ws/wss URL through policy and only translate at the OkHttp
        // request boundary. A wss endpoint always becomes https here, never http.
        val requestBuilder = Request.Builder().url(url.asHttpUrl())
        headersProvider().forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.build()
        lateinit var socket: WebSocket
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen(OkHttpRelaySocket(webSocket))
            }
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
        })
        return OkHttpRelaySocket(socket)
    }
}

private class OkHttpRelaySocket(private val socket: WebSocket) : RelaySocket {
    override fun send(text: String): Boolean = socket.send(text)
    override fun close(code: Int, reason: String) { socket.close(code, reason) }
}

/**
 * Single-owner v2 relay loop. One bounded raw-frame channel feeds one ordered consumer; a
 * reconnect cannot start until the prior socket's session has fully terminated.
 */
class RelayTransport(
    private val outbox: OutboxRepository,
    private val authHeadersProvider: () -> Map<String, String> = { emptyMap() },
    private val connector: RelaySocketConnector = OkHttpRelaySocketConnector(authHeadersProvider),
    private val appVersion: String = "0.8.0",
    private val scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO),
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val reconnect: Boolean = true,
) {
    fun run(url: RelayWebSocketUrl): Flow<TransportEvent> = channelFlow {
        var previousDelay = MIN_BACKOFF_MS
        var attempt = 0
        while (isActive) {
            val session = runSession(url) { event -> trySend(event).isSuccess }
            if (!reconnect) break
            if (session.authenticatedForMs >= AUTH_RESET_MS) attempt = 0 else attempt += 1
            if (!isActive) break
            val delayMs = decorrelatedDelay(previousDelay, attempt)
            previousDelay = delayMs
            kotlinx.coroutines.delay(delayMs)
        }
    }

    private suspend fun runSession(url: RelayWebSocketUrl, emit: (TransportEvent) -> Boolean): SessionResult {
        val raw = Channel<String>(RAW_FRAME_CAPACITY)
        val closed = CompletableDeferred<SessionResult>()
        val authenticated = AtomicBoolean(false)
        val negotiatedFloor = java.util.concurrent.atomic.AtomicInteger(0)
        val overflowed = AtomicBoolean(false)
        var authenticatedAt = 0L
        var socketRef: RelaySocket? = null
        val listener = object : RelaySocketListener {
            override fun onOpen(socket: RelaySocket) {
                socketRef = socket
                emit(TransportEvent.Connected)
                socket.send(RelayFrameCodec.encode(RelayFrame.Hello(listOf(2, 1), appVersion)))
            }
            override fun onText(text: String) {
                if (!RawFrameIngress(raw).offer(text) && overflowed.compareAndSet(false, true)) {
                    val error = IllegalStateException("relay control frame buffer saturated")
                    emit(TransportEvent.Failed(error))
                    closed.complete(SessionResult(if (authenticated.get()) clock() - authenticatedAt else 0, error.message))
                    socketRef?.close(1009, "relay control frame buffer full")
                }
            }
            override fun onClosed(reason: String?) { closed.complete(SessionResult(if (authenticated.get()) clock() - authenticatedAt else 0, reason)) }
            override fun onFailure(error: Throwable) {
                emit(TransportEvent.Failed(error))
                closed.complete(SessionResult(if (authenticated.get()) clock() - authenticatedAt else 0, error.message))
            }
        }
        try {
            connector.connect(url, listener)
        } catch (error: Throwable) {
            emit(TransportEvent.Failed(error))
            closed.complete(SessionResult(0, error.message))
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
                        authenticated.set(true)
                        authenticatedAt = clock()
                        negotiatedFloor.set(frame.floor)
                        emit(TransportEvent.Authenticated(frame.floor))
                        if (frame.floor >= 2) {
                            flushV2(socketRef, emit)
                        } else {
                            emit(TransportEvent.LegacyOnlineOnly)
                            flushLegacy(socketRef, emit)
                        }
                    }
                    is RelayFrame.Accepted -> {
                        outbox.onRelayAccepted(frame.msgId, frame.acceptedAt)
                        emit(TransportEvent.RelayAccepted(frame.msgId, frame.acceptedAt))
                        flushReadyAcks(socketRef)
                        if (negotiatedFloor.get() == 1) flushLegacy(socketRef, emit) else flushV2(socketRef, emit)
                    }
                    is RelayFrame.LegacyForwarded -> {
                        outbox.onLegacyForwarded(frame.msgId, clock())
                        emit(TransportEvent.LegacyForwarded(frame.msgId))
                    }
                    is RelayFrame.Rejected -> {
                        outbox.onRelayRejected(frame.msgId, frame.reason, attempt = 0)
                        emit(TransportEvent.RelayRejected(frame.msgId, frame.reason))
                    }
                    is RelayFrame.Expired -> {
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
        val flusher = sessionScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                if (authenticated.get()) {
                    if (negotiatedFloor.get() == 1) flushLegacy(socketRef, emit) else flushV2(socketRef, emit)
                }
            }
        }
        val result = closed.await()
        consumer.cancel()
        flusher.cancel()
        raw.close()
        socketRef?.close(1000)
        emit(TransportEvent.Closed(result.reason))
        return result
    }

    private suspend fun flushV2(socket: RelaySocket?, emit: (TransportEvent) -> Boolean) {
        if (socket == null) return
        flushReadyAcks(socket)
        // Floor 2 can durably carry both v2 and migrated v1 envelopes. The codec
        // validates each version while preserving the envelope bytes.
        for (row in outbox.sendable(limit = 32, now = clock())) {
            if (!socket.send(RelayFrameCodec.encode(RelayFrame.Put(row.envelopeJson)))) break
            outbox.markSent(row.msgId, row.attempts, clock())
        }
    }

    private suspend fun flushLegacy(socket: RelaySocket?, emit: (TransportEvent) -> Boolean) {
        if (socket == null) return
        for (row in outbox.sendable(limit = 32, now = clock()).filter { it.protocolVersion == 1 }) {
            if (!socket.send(RelayFrameCodec.encode(RelayFrame.Put(row.envelopeJson)))) break
            outbox.markSent(row.msgId, row.attempts, clock())
        }
    }

    private suspend fun flushReadyAcks(socket: RelaySocket?) {
        if (socket == null) return
        for (ack in outbox.readyRelayAcks(32)) {
            if (!socket.send(RelayFrameCodec.encode(RelayFrame.Ack(ack.msgId, ack.envelopeSha256)))) break
            outbox.markRelayAckSent(ack)
        }
    }

    private fun decorrelatedDelay(previous: Long, attempt: Int): Long {
        if (attempt <= 0) return MIN_BACKOFF_MS
        val upper = min(MAX_BACKOFF_MS, previous * 3)
        return random.nextLong(MIN_BACKOFF_MS, upper.coerceAtLeast(MIN_BACKOFF_MS + 1))
    }

    private data class SessionResult(val authenticatedForMs: Long, val reason: String?)

    private companion object {
        const val RAW_FRAME_CAPACITY = 128
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val AUTH_RESET_MS = 30_000L
    }
}

/** Non-dropping callback bridge: a full channel is a session failure, never a silent loss. */
internal class RawFrameIngress(private val channel: Channel<String>) {
    fun offer(frame: String): Boolean = channel.trySend(frame).isSuccess
}
