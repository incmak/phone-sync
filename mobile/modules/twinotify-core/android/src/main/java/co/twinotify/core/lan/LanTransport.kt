package co.twinotify.core.lan

import co.twinotify.core.service.AuthenticatedRouteSession
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.service.OutboxTransition
import co.twinotify.core.service.RouteKind
import co.twinotify.core.service.TransportRoute
import co.twinotify.core.storage.OutboundMessage
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LanTransportEvent {
    /** The peer took durable custody of one of our outbound rows. */
    data class PeerAccepted(val msgId: String, val eventType: String? = null) : LanTransportEvent

    /** We took durable custody of one of the peer's events and acknowledged it. */
    data class Committed(val msgId: String, val duplicate: Boolean) : LanTransportEvent

    /** The session ended. `code` is stable and carries no content or network detail. */
    data class Closed(val code: String) : LanTransportEvent
}

/**
 * One authenticated direct-route session.
 *
 * It owns no outbox loop: the coordinator selects due rows and calls [send]. Here
 * there is exactly one ordered inbound processor and exactly one writer, and both
 * suspend under pressure rather than dropping anything.
 *
 * Ordering and bounds come from construction rather than from bookkeeping. The
 * inbound path reads, commits and acknowledges one frame at a time on a single
 * coroutine, so a slow commit applies TCP backpressure to the peer and no frame is
 * ever committed without being acknowledged in the same step. The writer queue is
 * bounded to [LanFrameLimits.MAX_BUFFERED_FRAMES]; because every legal frame is
 * capped at [LanFrameLimits.MAX_FRAME_BYTES], that count bound is also the byte
 * bound of [LanFrameLimits.MAX_BUFFERED_BYTES].
 */
class LanTransport(
    private val connection: AuthenticatedLanConnection,
    private val outbox: OutboxRepository,
    private val heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    private val dispatch: suspend (String) -> InboundDispatchResult,
) {
    init {
        require(heartbeatIntervalMillis > 0)
    }

    val peerDeviceId: String get() = connection.peerDeviceId

    /**
     * Digest we actually put on the wire per in-flight message. An acknowledgement
     * that does not match what we sent must not take custody of the stored row.
     */
    private data class InFlight(val envelopeSha256: String, val eventType: String)

    private val inFlight = Collections.synchronizedMap(HashMap<String, InFlight>())

    private val started = AtomicBoolean(false)

    /**
     * Write one stored row to the peer, byte-for-byte as persisted. Returns only once
     * the frame has reached the connection, so a caller is never told a row was sent
     * while it still sits in a queue that teardown could discard. Concurrent callers
     * serialize on the connection's single writer and suspend rather than drop.
     */
    suspend fun send(message: OutboundMessage) {
        inFlight[message.msgId] = InFlight(message.envelopeSha256, message.eventType)
        connection.send(LanFrame.Put(message.envelopeJson.encodeToByteArray()))
    }

    /** Ask the peer to end the session with a stable code. */
    suspend fun close(code: String) {
        connection.send(LanFrame.Close(code))
    }

    /**
     * Run the session. The returned flow is the single ordered inbound processor.
     * It completes when the peer closes, when the connection breaks, or when a
     * protocol violation ends the session.
     */
    fun run(): Flow<LanTransportEvent> = channelFlow {
        // `send` would otherwise read as either this scope's emit or LanTransport.send.
        val events = this
        if (!started.compareAndSet(false, true)) {
            events.send(LanTransportEvent.Closed("session_already_started"))
            return@channelFlow
        }
        val heartbeat = launch {
            var token = 0L
            while (isActive) {
                delay(heartbeatIntervalMillis)
                try {
                    connection.send(LanFrame.Ping(token++))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Unblock the inbound reader so the session reports connection_lost.
                    connection.close()
                    return@launch
                }
            }
        }
        try {
            connection.incoming.collect { frame ->
                when (frame) {
                    is LanFrame.Put -> {
                        val outcome = commitInbound(frame)
                        if (outcome is LanTransportEvent.Closed) throw SessionEnd(outcome)
                        events.send(outcome)
                    }
                    is LanFrame.Accepted -> {
                        val outcome = acknowledgeOutbound(frame)
                        if (outcome is LanTransportEvent.Closed) throw SessionEnd(outcome)
                        if (outcome != null) events.send(outcome)
                    }
                    is LanFrame.Ping -> connection.send(LanFrame.Pong(frame.token))
                    is LanFrame.Pong -> Unit
                    is LanFrame.Close -> throw SessionEnd(LanTransportEvent.Closed(frame.code))
                    // The connection refuses handshake frames after authentication.
                    is LanFrame.Hello, is LanFrame.HelloAck ->
                        throw SessionEnd(LanTransportEvent.Closed("unexpected_handshake_frame"))
                }
            }
            events.send(LanTransportEvent.Closed("peer_closed"))
        } catch (end: SessionEnd) {
            // A terminal frame must stop the session, not merely skip to the next frame.
            events.send(end.event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            events.send(LanTransportEvent.Closed("connection_lost"))
        } finally {
            withContext(NonCancellable) {
                heartbeat.cancelAndJoin()
                connection.close()
            }
        }
    }

    private companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 3_000L
    }

    /** Not a coroutine cancellation: it unwinds one collect to end one session. */
    private class SessionEnd(val event: LanTransportEvent) : Exception(null, null, false, false)

    /**
     * Commit one inbound envelope, then acknowledge it. The acknowledgement is written
     * only after [dispatch] returns, which is after its Room transaction boundary, so a
     * peer is never told to release a row we have not durably stored.
     */
    private suspend fun commitInbound(frame: LanFrame.Put): LanTransportEvent {
        val envelope = frame.envelope.decodeToString()
        val result = try {
            dispatch(envelope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Custody is unproven, so refuse rather than acknowledge. The peer retries.
            connection.send(LanFrame.Close("dispatch_failed"))
            return LanTransportEvent.Closed("dispatch_failed")
        }
        return when (result) {
            is InboundDispatchResult.Accepted -> {
                connection.send(LanFrame.Accepted(result.msgId, result.envelopeSha256))
                LanTransportEvent.Committed(result.msgId, duplicate = false)
            }
            is InboundDispatchResult.Duplicate -> {
                // Replays the original acceptance without rematerializing anything.
                connection.send(LanFrame.Accepted(result.msgId, result.envelopeSha256))
                LanTransportEvent.Committed(result.msgId, duplicate = true)
            }
            is InboundDispatchResult.AcceptedAfterCustody -> try {
                connection.send(LanFrame.Accepted(result.msgId, result.envelopeSha256))
                LanTransportEvent.Committed(result.msgId, duplicate = false)
            } finally {
                // The authenticated control has already completed shutdown + wipe. Service stop
                // must happen after the acceptance attempt and must survive write cancellation.
                result.finalizeAfterCustody()
            }
            is InboundDispatchResult.Rejected -> {
                connection.send(LanFrame.Close(result.code))
                LanTransportEvent.Closed(result.code)
            }
        }
    }

    /** Take custody of one outbound row, but only for the digest we actually sent. */
    private suspend fun acknowledgeOutbound(frame: LanFrame.Accepted): LanTransportEvent? {
        val sent = inFlight[frame.msgId]
        if (sent != null && sent.envelopeSha256 != frame.envelopeSha256) {
            connection.send(LanFrame.Close("ack_digest_mismatch"))
            return LanTransportEvent.Closed("ack_digest_mismatch")
        }
        inFlight.remove(frame.msgId)
        return when (outbox.onLanAccepted(frame.msgId)) {
            // Ordinary rows stay until an authenticated peer receipt; receipt rows are deleted.
            OutboxTransition.Retained,
            OutboxTransition.Deleted,
            -> LanTransportEvent.PeerAccepted(frame.msgId, sent?.eventType)
            // Nothing to take custody of, and nothing worth ending the session over.
            else -> null
        }
    }
}

/**
 * Adapts one direct LAN session to the coordinator's route contract.
 *
 * [open] returns only once the connection is authenticated and the session's inbound
 * processor is running, so the coordinator never treats an unauthenticated socket as a
 * usable route. The session is not self-draining: the coordinator's pump selects rows.
 */
class LanRoute(
    private val connect: suspend () -> AuthenticatedLanConnection,
    private val outbox: OutboxRepository,
    private val dispatch: suspend (String) -> InboundDispatchResult,
    private val onEvent: suspend (LanTransportEvent) -> Unit = {},
) : TransportRoute {
    override val kind: RouteKind = RouteKind.LAN

    override suspend fun open(): AuthenticatedRouteSession {
        val connection = connect()
        val transport = LanTransport(connection, outbox, dispatch = dispatch)
        val closed = CompletableDeferred<String>()
        // The returned route session owns this worker. In particular, do not attach it to
        // TransportCoordinator's temporary relay-promotion scope: that scope must return after
        // authentication so it can close relay and grant LAN the single drainer lease.
        val session = CoroutineScope(currentCoroutineContext().minusKey(Job)).launch {
            try {
                transport.run().collect { event ->
                    onEvent(event)
                    if (event is LanTransportEvent.Closed) closed.complete(event.code)
                }
            } finally {
                closed.complete("session_ended")
            }
        }
        return LanRouteSession(transport, closed, session)
    }
}

private class LanRouteSession(
    private val transport: LanTransport,
    private val closed: CompletableDeferred<String>,
    private val session: Job,
) : AuthenticatedRouteSession {
    override val kind: RouteKind = RouteKind.LAN

    override suspend fun send(message: OutboundMessage) = transport.send(message)

    override suspend fun awaitClosed(): String = closed.await()

    override suspend fun close(code: String) {
        val cancellation = try {
            transport.close(code)
            null
        } catch (error: CancellationException) {
            error
        } catch (_: Throwable) {
            null
        }
        withContext(NonCancellable) {
            session.cancelAndJoin()
            closed.complete(code)
        }
        cancellation?.let { throw it }
    }
}
