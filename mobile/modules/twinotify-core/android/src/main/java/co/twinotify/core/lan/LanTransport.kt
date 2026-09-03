package co.twinotify.core.lan

import co.twinotify.core.direct.DirectCommand
import co.twinotify.core.direct.DirectDelivery
import co.twinotify.core.direct.DirectDeliveryEvent
import co.twinotify.core.direct.DirectWire
import co.twinotify.core.service.AuthenticatedRouteSession
import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.service.RouteKind
import co.twinotify.core.service.TransportRoute
import co.twinotify.core.storage.OutboundMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * One authenticated direct LAN session.
 *
 * This is a wire adapter only: every ordering, custody and shutdown invariant lives
 * in [DirectDelivery], which this class runs under [CustodyRoute.LAN]. The mapping
 * between [LanFrame] and [DirectCommand] is one-to-one and carries no state, so the
 * bytes on the LAN socket are exactly what they were before the engine was shared.
 *
 * The writer queue is bounded by the connection to [LanFrameLimits.MAX_BUFFERED_FRAMES];
 * because every legal frame is capped at [LanFrameLimits.MAX_FRAME_BYTES], that count
 * bound is also the byte bound of [LanFrameLimits.MAX_BUFFERED_BYTES].
 */
class LanTransport(
    connection: AuthenticatedLanConnection,
    outbox: OutboxRepository,
    heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    dispatch: suspend (String) -> InboundDispatchResult,
) {
    private val delivery = DirectDelivery(
        wire = LanDirectWire(connection),
        outbox = outbox,
        custodyRoute = CustodyRoute.LAN,
        heartbeatIntervalMillis = heartbeatIntervalMillis,
        dispatch = dispatch,
    )

    val peerDeviceId: String get() = delivery.peerDeviceId

    /** Write one stored row to the peer, byte-for-byte as persisted. See [DirectDelivery.send]. */
    suspend fun send(message: OutboundMessage) = delivery.send(message)

    /** Ask the peer to end the session with a stable code. */
    suspend fun close(code: String) = delivery.close(code)

    /** Run the session. See [DirectDelivery.run]; this only renames the events. */
    fun run(): Flow<LanTransportEvent> = delivery.run().map { event ->
        when (event) {
            is DirectDeliveryEvent.PeerAccepted -> LanTransportEvent.PeerAccepted(event.msgId, event.eventType)
            is DirectDeliveryEvent.Committed -> LanTransportEvent.Committed(event.msgId, event.duplicate)
            is DirectDeliveryEvent.Closed -> LanTransportEvent.Closed(event.code)
        }
    }

    private companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 3_000L
    }
}

/** Stateless one-to-one mapping between the LAN frame set and the direct command set. */
private class LanDirectWire(private val connection: AuthenticatedLanConnection) : DirectWire {
    override val peerDeviceId: String get() = connection.peerDeviceId

    override val incoming: Flow<DirectCommand> = connection.incoming.map { frame ->
        when (frame) {
            is LanFrame.Put -> DirectCommand.Put(frame.envelope)
            is LanFrame.Accepted -> DirectCommand.Accepted(frame.msgId, frame.envelopeSha256)
            is LanFrame.Ping -> DirectCommand.Ping(frame.token)
            is LanFrame.Pong -> DirectCommand.Pong(frame.token)
            is LanFrame.Close -> DirectCommand.Close(frame.code)
            // The connection refuses handshake frames after authentication. Should one
            // arrive anyway, the session ends with the same stable code it always used,
            // without answering the peer.
            is LanFrame.Hello, is LanFrame.HelloAck -> DirectCommand.Close("unexpected_handshake_frame")
        }
    }

    override suspend fun send(command: DirectCommand) = connection.send(
        when (command) {
            is DirectCommand.Put -> LanFrame.Put(command.envelope)
            is DirectCommand.Accepted -> LanFrame.Accepted(command.msgId, command.envelopeSha256)
            is DirectCommand.Ping -> LanFrame.Ping(command.token)
            is DirectCommand.Pong -> LanFrame.Pong(command.token)
            is DirectCommand.Close -> LanFrame.Close(command.code)
        },
    )

    override fun close() = connection.close()
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
