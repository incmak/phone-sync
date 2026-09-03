package co.twinotify.core.bluetooth

import co.twinotify.core.direct.DirectDelivery
import co.twinotify.core.direct.DirectDeliveryEvent
import co.twinotify.core.service.AuthenticatedRouteSession
import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.InboundDispatchResult
import co.twinotify.core.service.OutboxRepository
import co.twinotify.core.service.RouteKind
import co.twinotify.core.service.TransportRoute
import co.twinotify.core.storage.OutboundMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One authenticated direct Bluetooth session.
 *
 * A wire adapter only: every ordering, custody and shutdown invariant lives in
 * [DirectDelivery], which this class runs under [CustodyRoute.BLUETOOTH]. The wire is
 * typed as [AuthenticatedBluetoothWire] on purpose, so an RFCOMM stream that has not
 * passed the signed handshake cannot be turned into a route session at all.
 */
class BluetoothTransport(
    wire: AuthenticatedBluetoothWire,
    outbox: OutboxRepository,
    heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    dispatch: suspend (String) -> InboundDispatchResult,
) {
    private val delivery = DirectDelivery(
        wire = wire,
        outbox = outbox,
        custodyRoute = CustodyRoute.BLUETOOTH,
        heartbeatIntervalMillis = heartbeatIntervalMillis,
        dispatch = dispatch,
    )

    val peerDeviceId: String get() = delivery.peerDeviceId

    /** Write one stored row to the peer, byte-for-byte as persisted. See [DirectDelivery.send]. */
    suspend fun send(message: OutboundMessage) = delivery.send(message)

    /** Ask the peer to end the session with a stable code; the wire bounds the frame. */
    suspend fun close(code: String) = delivery.close(code)

    /** Run the session. See [DirectDelivery.run]. */
    fun run(): Flow<DirectDeliveryEvent> = delivery.run()

    private companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 3_000L
    }
}

/**
 * Adapts one direct Bluetooth session to the coordinator's route contract.
 *
 * [open] returns only once [connect] has produced an authenticated wire and the session's
 * inbound processor is running. The session is not self-draining: the coordinator's pump
 * selects rows, so authenticating here never grants an outbox lease.
 */
class BluetoothRoute(
    private val connect: suspend () -> AuthenticatedBluetoothWire,
    private val outbox: OutboxRepository,
    private val dispatch: suspend (String) -> InboundDispatchResult,
    private val onEvent: suspend (DirectDeliveryEvent) -> Unit = {},
    private val heartbeatIntervalMillis: Long = 3_000L,
) : TransportRoute {
    override val kind: RouteKind = RouteKind.BLUETOOTH

    override suspend fun open(): AuthenticatedRouteSession {
        val wire = connect()
        val transport = BluetoothTransport(wire, outbox, heartbeatIntervalMillis, dispatch)
        val closed = CompletableDeferred<String>()
        // The returned route session owns this worker. In particular, do not attach it to
        // TransportCoordinator's temporary promotion scope: that scope must return after
        // authentication so it can close the current owner and grant this session the lease.
        val worker = CoroutineScope(currentCoroutineContext().minusKey(Job)).launch {
            try {
                transport.run().collect { event ->
                    onEvent(event)
                    if (event is DirectDeliveryEvent.Closed) closed.complete(event.code)
                }
            } finally {
                closed.complete("session_ended")
            }
        }
        return BluetoothRouteSession(transport, wire, closed, worker)
    }
}

private class BluetoothRouteSession(
    private val transport: BluetoothTransport,
    private val wire: AuthenticatedBluetoothWire,
    private val closed: CompletableDeferred<String>,
    private val worker: Job,
) : AuthenticatedRouteSession {
    private val closeStarted = AtomicBoolean(false)
    override val kind: RouteKind = RouteKind.BLUETOOTH

    override suspend fun send(message: OutboundMessage) = transport.send(message)

    override suspend fun awaitClosed(): String = closed.await()

    /**
     * Sends one bounded `bt.close`, closes the socket, and joins the worker under
     * [NonCancellable]. Exactly one stable close code is completed: the caller's code, unless
     * the peer or the wire already ended the session, in which case that earlier code stands.
     * A second call is a no-op, so the peer never sees two close frames for one session.
     */
    override suspend fun close(code: String) {
        if (!closeStarted.compareAndSet(false, true)) return
        val cancellation = try {
            transport.close(code)
            null
        } catch (error: CancellationException) {
            error
        } catch (_: Throwable) {
            null
        }
        withContext(NonCancellable) {
            // Fix the code before the socket close unblocks the reader, whose own end of
            // session would otherwise race this one for the deferred.
            closed.complete(code)
            wire.close()
            worker.cancelAndJoin()
        }
        cancellation?.let { throw it }
    }
}
