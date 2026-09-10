package co.twinotify.core.direct

import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.service.OutboxTransition
import co.twinotify.core.storage.OutboundMessage
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One authenticated direct-route session over any [DirectWire].
 *
 * It owns no outbox loop: the coordinator selects due rows and calls [send]. Here
 * there is exactly one ordered inbound processor and exactly one writer, and both
 * suspend under pressure rather than dropping anything.
 *
 * Ordering and bounds come from construction rather than from bookkeeping. The
 * inbound processor commits and acknowledges one command at a time. A rendezvous
 * reader may read one next bounded frame while that processor waits for a write,
 * releasing duplex link credits without adding an unbounded queue. Slow commits
 * still apply backpressure, and custody is acknowledged only after durable commit.
 *
 * Durable custody is recorded under [custodyRoute], which must name a direct route.
 * Relay custody has its own protocol adapter and never flows through here.
 */
class DirectDelivery(
    private val wire: DirectWire,
    private val outbox: OutboxRepository,
    private val custodyRoute: CustodyRoute,
    private val heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    /**
     * How long a session may hear nothing at all before it is treated as dead.
     *
     * Several heartbeat intervals, so an ordinary stalled write or a slow link does not end a
     * healthy session, while a peer that has genuinely vanished is noticed in seconds rather
     * than never.
     */
    private val livenessTimeoutMillis: Long = heartbeatIntervalMillis * LIVENESS_INTERVALS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatch: suspend (String) -> InboundDispatchResult,
) {
    init {
        require(custodyRoute == CustodyRoute.LAN || custodyRoute == CustodyRoute.BLUETOOTH) {
            "direct delivery requires a direct custody route"
        }
        require(heartbeatIntervalMillis > 0)
        require(livenessTimeoutMillis > heartbeatIntervalMillis) {
            "liveness must outlast a single heartbeat"
        }
    }

    val peerDeviceId: String get() = wire.peerDeviceId

    /**
     * Digest we actually put on the wire per in-flight message. An acknowledgement
     * that does not match what we sent must not take custody of the stored row.
     */
    private data class InFlight(val envelopeSha256: String, val eventType: String)

    private val inFlight = Collections.synchronizedMap(HashMap<String, InFlight>())

    private val started = AtomicBoolean(false)

    /** When the peer last proved it was there. Any inbound frame counts. */
    private val lastInboundAt = java.util.concurrent.atomic.AtomicLong(clock())

    /**
     * Write one stored row to the peer, byte-for-byte as persisted. Returns only once
     * the command has reached the wire, so a caller is never told a row was sent
     * while it still sits in a queue that teardown could discard. Concurrent callers
     * serialize on the wire's single writer and suspend rather than drop.
     */
    suspend fun send(message: OutboundMessage) {
        recordSent(message)
        wire.send(DirectCommand.Put(message.envelopeJson.encodeToByteArray()))
    }

    /** Remember what [send] is about to put on the wire so only a matching ack takes custody. */
    internal fun recordSent(message: OutboundMessage) {
        inFlight[message.msgId] = InFlight(message.envelopeSha256, message.eventType)
    }

    /** Ask the peer to end the session with a stable code. */
    suspend fun close(code: String) {
        wire.send(DirectCommand.Close(code))
    }

    /**
     * Run the session. The returned flow is the single ordered inbound processor.
     * It completes when the peer closes, when the wire breaks, or when a protocol
     * violation ends the session. Cancellation propagates; the wire is closed and the
     * heartbeat joined non-cancellably on every exit.
     */
    fun run(): Flow<DirectDeliveryEvent> = channelFlow {
        // `send` would otherwise read as either this scope's emit or DirectDelivery.send.
        val events = this
        if (!started.compareAndSet(false, true)) {
            events.send(DirectDeliveryEvent.Closed("session_already_started"))
            return@channelFlow
        }
        // A phone that walks off the Wi-Fi never sends a FIN, so the socket stays writable on
        // this side and only the missing answers reveal that nobody is there. Without this the
        // surviving half held the session open indefinitely, and because it was carrying a
        // granted direct route it stopped listening too: measured on hardware, the returning
        // phone knocked every 60s for over eight minutes against a peer silent since it left.
        //
        // Deliberately its own coroutine that never sends. The first version shared the ping
        // loop, and a ping into a full send buffer blocks until the socket closes, so the check
        // it was supposed to reach never ran again. The watchdog must not depend on the wire.
        val liveness = launch {
            while (isActive) {
                delay(heartbeatIntervalMillis)
                if (clock() - lastInboundAt.get() > livenessTimeoutMillis) {
                    wire.close()
                    return@launch
                }
            }
        }
        val heartbeat = launch {
            var token = 0L
            while (isActive) {
                delay(heartbeatIntervalMillis)
                try {
                    wire.send(DirectCommand.Ping(token++))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Unblock the inbound reader so the session reports connection_lost.
                    wire.close()
                    return@launch
                }
            }
        }
        try {
            // Liveness is stamped as each frame is read off the wire, upstream of the buffer,
            // not as the processor gets to it. The first version stamped in the collector, and a
            // freshly reconnected pair flushing its backlog kept the processor waiting behind the
            // writer for longer than the window, so a healthy link was closed 14s after it came
            // up. The small lookahead lets the reader keep taking pongs while the processor is
            // busy; every frame is size-capped, so the bound on memory is unchanged in kind.
            wire.incoming
                .onEach { lastInboundAt.set(clock()) }
                .buffer(capacity = INBOUND_LOOKAHEAD_FRAMES)
                .collect { command ->
                when (command) {
                    is DirectCommand.Put -> {
                        val outcome = commitInbound(command)
                        if (outcome is DirectDeliveryEvent.Closed) throw SessionEnd(outcome)
                        if (outcome != null) events.send(outcome)
                    }
                    is DirectCommand.Accepted -> {
                        val outcome = accept(command)
                        if (outcome is DirectDeliveryEvent.Closed) throw SessionEnd(outcome)
                        if (outcome != null) events.send(outcome)
                    }
                    is DirectCommand.Ping -> wire.send(DirectCommand.Pong(command.token))
                    // The answer's only job is to prove the peer is there, which the
                    // timestamp above has already recorded.
                    is DirectCommand.Pong -> Unit
                    is DirectCommand.Close -> throw SessionEnd(DirectDeliveryEvent.Closed(command.code))
                }
            }
            events.send(DirectDeliveryEvent.Closed("peer_closed"))
        } catch (end: SessionEnd) {
            // A terminal command must stop the session, not merely skip to the next one.
            events.send(end.event)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            events.send(DirectDeliveryEvent.Closed("connection_lost"))
        } finally {
            withContext(NonCancellable) {
                liveness.cancelAndJoin()
                heartbeat.cancelAndJoin()
                wire.close()
            }
        }
    }

    private companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 3_000L

        /** Silent heartbeat intervals before a peer is treated as gone: 18s at the default. */
        const val LIVENESS_INTERVALS = 6

        /** Frames the reader may take ahead of the processor; enough to keep hearing pings. */
        const val INBOUND_LOOKAHEAD_FRAMES = 16
    }

    /** Not a coroutine cancellation: it unwinds one collect to end one session. */
    private class SessionEnd(val event: DirectDeliveryEvent) : Exception(null, null, false, false)

    /**
     * Commit one inbound envelope, then acknowledge it. The acknowledgement is written
     * only after [dispatch] returns, which is after its Room transaction boundary, so a
     * peer is never told to release a row we have not durably stored.
     *
     * Returns null when the dispatcher deferred: nothing is acknowledged and the session is
     * kept, so the peer still holds the row and retries it on a later pass.
     */
    private suspend fun commitInbound(command: DirectCommand.Put): DirectDeliveryEvent? {
        val envelope = command.envelope.decodeToString()
        val result = try {
            dispatch(envelope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Custody is unproven, so refuse rather than acknowledge. The peer retries.
            wire.send(DirectCommand.Close("dispatch_failed"))
            return DirectDeliveryEvent.Closed("dispatch_failed")
        }
        return when (result) {
            is InboundDispatchResult.Accepted -> {
                wire.send(DirectCommand.Accepted(result.msgId, result.envelopeSha256))
                DirectDeliveryEvent.Committed(result.msgId, duplicate = false)
            }
            is InboundDispatchResult.Duplicate -> {
                // Replays the original acceptance without rematerializing anything.
                wire.send(DirectCommand.Accepted(result.msgId, result.envelopeSha256))
                DirectDeliveryEvent.Committed(result.msgId, duplicate = true)
            }
            is InboundDispatchResult.AcceptedAfterCustody -> try {
                wire.send(DirectCommand.Accepted(result.msgId, result.envelopeSha256))
                DirectDeliveryEvent.Committed(result.msgId, duplicate = false)
            } finally {
                // The authenticated control has already completed shutdown + wipe. Service stop
                // must happen after the acceptance attempt and must survive write cancellation.
                result.finalizeAfterCustody()
            }
            is InboundDispatchResult.Rejected -> {
                wire.send(DirectCommand.Close(result.code))
                DirectDeliveryEvent.Closed(result.code)
            }
            // No acknowledgement and no close: custody is unproven but the route is healthy.
            is InboundDispatchResult.Deferred -> null
            // Acknowledged so the peer releases a row we will never be able to apply. Refusing
            // instead would end the session and have the peer resend it on the next one.
            is InboundDispatchResult.Discarded -> {
                wire.send(DirectCommand.Accepted(result.msgId, result.envelopeSha256))
                DirectDeliveryEvent.Committed(result.msgId, duplicate = false)
            }
        }
    }

    /**
     * Take custody of one outbound row, but only for the digest we actually sent.
     * Returns null when there is nothing to take custody of and nothing worth ending
     * the session over. The ordered processor calls this; it is exposed only for tests.
     */
    internal suspend fun accept(command: DirectCommand.Accepted): DirectDeliveryEvent? {
        val sent = inFlight[command.msgId]
        if (sent != null && sent.envelopeSha256 != command.envelopeSha256) {
            wire.send(DirectCommand.Close("ack_digest_mismatch"))
            return DirectDeliveryEvent.Closed("ack_digest_mismatch")
        }
        inFlight.remove(command.msgId)
        return when (outbox.onDirectAccepted(command.msgId, custodyRoute)) {
            // Ordinary rows stay until an authenticated peer receipt; receipt rows are deleted.
            OutboxTransition.Retained,
            OutboxTransition.Deleted,
            -> DirectDeliveryEvent.PeerAccepted(command.msgId, sent?.eventType)
            else -> null
        }
    }
}
