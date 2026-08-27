package co.twinotify.core.service

import co.twinotify.core.storage.OutboundMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RouteKind { LAN, RELAY, NONE }

enum class RoutePhase { IDLE, CONNECTING, AUTHENTICATED, RECONNECTING }

/** Everything the UI is allowed to know about delivery. It carries no network detail. */
data class RouteHealth(
    val active: RouteKind = RouteKind.NONE,
    val phase: RoutePhase = RoutePhase.IDLE,
    val queuedCount: Int = 0,
)

/** One authenticated session, already past its own handshake. */
interface AuthenticatedRouteSession {
    val kind: RouteKind
    suspend fun send(message: OutboundMessage)

    /** Suspends until the session ends, returning a stable code. */
    suspend fun awaitClosed(): String
    suspend fun close(code: String)

    /**
     * True when the session drains the outbox itself. The relay loop does, because it
     * interleaves durable sends with relay acknowledgements and the legacy v1 path.
     * The coordinator then runs no pump of its own, so the row-selecting owner is
     * still exactly one: whichever session is currently granted.
     */
    val selfDraining: Boolean get() = false
}

interface TransportRoute {
    val kind: RouteKind

    /** Opens and authenticates, or throws to say this route is unavailable right now. */
    suspend fun open(): AuthenticatedRouteSession
}

/**
 * The single owner of outbound delivery.
 *
 * Exactly one session is granted at a time and only that session's pump reads
 * [OutboxRepository.sendable], so two routes can never drain the outbox together.
 * A direct LAN session is preferred whenever one can be authenticated; relay
 * carries delivery whenever it cannot.
 *
 * Reconnect is bounded, and its backoff resets only after a session has stayed
 * authenticated for [STABILITY_WINDOW_MS]. A route that authenticates and dies
 * immediately, over and over, therefore keeps backing off instead of spinning.
 */
class TransportCoordinator(
    private val outbox: OutboxRepository,
    private val lan: TransportRoute?,
    private val relay: TransportRoute?,
    /** Chooses route order only; the coordinator still grants exactly one session. */
    private val preferLan: Boolean = true,
    private val queuedCount: suspend () -> Int = { 0 },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idlePollMs: Long = 1_000L,
    /** Emits when a user asks to reconnect now, cutting the current backoff short. */
    private val retryRequests: Flow<Unit> = emptyFlow(),
) {
    private val healthState = MutableStateFlow(RouteHealth())
    val health: StateFlow<RouteHealth> = healthState.asStateFlow()

    /** Last backoff the coordinator waited. Zero once health has been sustained. */
    @Volatile
    var lastBackoffMs: Long = 0L
        private set

    suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            publish(RouteKind.NONE, RoutePhase.CONNECTING)
            val session = openPreferred()
            if (session == null) {
                attempt += 1
                publish(RouteKind.NONE, RoutePhase.RECONNECTING)
                backOff(attempt)
                continue
            }
            publish(session.kind, RoutePhase.AUTHENTICATED)
            val authenticatedAt = clock()
            var closeCode = COORDINATOR_STOPPED
            var carryCancellation: CancellationException? = null
            try {
                carry(session)
            } catch (error: CancellationException) {
                carryCancellation = error
                throw error
            } catch (_: Exception) {
                closeCode = ESTABLISHED_ROUTE_FAILURE
            } finally {
                try {
                    // Cleanup owns the granted session until close has fully joined it,
                    // even when the coordinator itself is being cancelled.
                    closeGrantedSession(session, closeCode)
                } catch (error: CancellationException) {
                    // Never replace the cancellation that ended carry with a cleanup one.
                    if (carryCancellation == null) throw error
                } catch (_: Exception) {
                    // Closing is still inside the established-session failure boundary.
                    // The bounded code above is the only failure detail given to routes.
                }
            }
            // Only a session that held for the full window counts as healthy.
            val sustained = clock() - authenticatedAt >= STABILITY_WINDOW_MS
            attempt = if (sustained) 0 else attempt + 1
            publish(RouteKind.NONE, RoutePhase.RECONNECTING)
            if (sustained) lastBackoffMs = 0L else backOff(attempt)
        }
    }

    /** Open routes in the configured order, granting at most one authenticated session. */
    private suspend fun openPreferred(): AuthenticatedRouteSession? {
        val routes = if (preferLan) listOfNotNull(lan, relay) else listOfNotNull(relay, lan)
        for (route in routes) {
            val session = try {
                route.open()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                continue
            }
            return session
        }
        return null
    }

    /**
     * Drive one granted session until it closes. Only one session is ever granted, so
     * whether the rows are selected here or inside a self-draining session, exactly one
     * owner reads the outbox at a time.
     */
    private suspend fun carry(session: AuthenticatedRouteSession) {
        val failure = coroutineScope {
            val ended = CompletableDeferred<Throwable?>()
            val closed = launch {
                try {
                    session.awaitClosed()
                    ended.complete(null)
                } catch (error: Throwable) {
                    // Complete with the object instead of completing exceptionally so
                    // coroutine stack-trace recovery cannot replace cancellation identity.
                    ended.complete(error)
                }
            }
            val pump = if (session.selfDraining) null else launch {
                try {
                    pump(session)
                    ended.complete(null)
                } catch (error: Throwable) {
                    ended.complete(error)
                }
            }
            try {
                ended.await()
            } finally {
                closed.cancel()
                pump?.cancel()
            }
        }
        failure?.let { throw it }
    }

    private suspend fun closeGrantedSession(session: AuthenticatedRouteSession, code: String) {
        if (currentCoroutineContext().isActive) {
            session.close(code)
        } else {
            withContext(NonCancellable) { session.close(code) }
        }
    }

    private suspend fun pump(session: AuthenticatedRouteSession) {
        while (currentCoroutineContext().isActive) {
            val due = outbox.sendable()
            if (due.isEmpty()) {
                publish(session.kind, RoutePhase.AUTHENTICATED)
                delay(idlePollMs)
                continue
            }
            for (message in due) {
                session.send(message)
                // A row stays due until custody, so an interrupted session may resend it.
                // Custody itself is idempotent, so exactly one acceptance survives.
                outbox.markSent(message.msgId, message.attempts)
            }
            publish(session.kind, RoutePhase.AUTHENTICATED)
        }
    }

    private suspend fun backOff(attempt: Int) {
        val wait = retryPolicy.delay(attempt - 1)
        lastBackoffMs = wait
        // An explicit retry skips the remaining wait without discarding the attempt
        // count, so a user can ask for one reconnection without disarming backoff.
        withTimeoutOrNull(wait) {
            try {
                retryRequests.first()
            } catch (_: NoSuchElementException) {
                // A flow that completes without emitting must still let the full
                // backoff elapse, or the coordinator would spin instead of waiting.
                awaitCancellation()
            }
        }
    }

    private suspend fun publish(active: RouteKind, phase: RoutePhase) {
        healthState.value = RouteHealth(
            active = active,
            phase = phase,
            queuedCount = runCatching { queuedCount() }.getOrDefault(healthState.value.queuedCount),
        )
    }

    companion object {
        private const val COORDINATOR_STOPPED = "coordinator_stopped"
        private const val ESTABLISHED_ROUTE_FAILURE = "established_route_failure"

        /** How long a session must stay authenticated before its route counts as healthy. */
        const val STABILITY_WINDOW_MS = 30_000L
    }
}
