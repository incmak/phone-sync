package co.twinotify.core.service

import co.twinotify.core.storage.OutboundMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RouteKind {
    LAN,
    BLUETOOTH,
    RELAY,
    NONE;

    /** A route whose session talks to the peer without the relay. */
    val isDirect: Boolean get() = this == LAN || this == BLUETOOTH
}

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

fun interface RelayProbeScheduler {
    suspend fun ensureProbe(requestDirect: Boolean)
}

data class LanRetryPolicy(
    val delaysMs: List<Long> = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L),
) {
    init {
        require(delaysMs.isNotEmpty() && delaysMs.all { it > 0L })
    }

    fun delay(failure: Int): Long = delaysMs[failure.coerceIn(0, delaysMs.lastIndex)]
}

/**
 * The single owner of outbound delivery.
 *
 * Exactly one session is granted at a time and only that session's pump reads
 * [OutboxRepository.sendable], so two routes can never drain the outbox together.
 * Direct routes are preferred in the order given (LAN, then Bluetooth) whenever one
 * can be authenticated; relay carries delivery whenever none can. A candidate may
 * authenticate while another session owns the lease, but it is granted only after
 * that owner's close has fully joined.
 *
 * Reconnect is bounded, and its backoff resets only after a session has stayed
 * authenticated for [STABILITY_WINDOW_MS]. A route that authenticates and dies
 * immediately, over and over, therefore keeps backing off instead of spinning.
 */
class TransportCoordinator(
    private val outbox: OutboxRepository,
    lan: TransportRoute?,
    private val relay: TransportRoute?,
    bluetooth: TransportRoute? = null,
    /** Chooses route order only; the coordinator still grants exactly one session. */
    private val preferDirect: Boolean = true,
    private val queuedCount: suspend () -> Int = { 0 },
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idlePollMs: Long = 1_000L,
    /** Emits when a user asks to reconnect now, cutting the current backoff short. */
    private val retryRequests: Flow<Unit> = emptyFlow(),
    /** Authenticated peer requests that both phones overlap one bounded direct attempt. */
    private val directAttemptRequests: Flow<Unit> = emptyFlow(),
    private val relayProbeScheduler: RelayProbeScheduler = RelayProbeScheduler {},
    private val lanRetryPolicy: LanRetryPolicy = LanRetryPolicy(),
    private val relayProbeIntervalMs: Long = 60_000L,
    private val directAttemptFloorMs: Long = 15_000L,
    private val onEstablishedFailure: (Throwable) -> Unit = {},
) {
    /** Direct routes in preference order. LAN outranks Bluetooth. */
    private val directRoutes: List<TransportRoute> = listOfNotNull(lan, bluetooth).also { routes ->
        require(routes.map { it.kind }.distinct().size == routes.size) { "duplicate direct route kind" }
        require(routes.all { it.kind.isDirect }) { "direct routes must be LAN or Bluetooth" }
    }

    private val healthState = MutableStateFlow(RouteHealth())
    val health: StateFlow<RouteHealth> = healthState.asStateFlow()

    /** Last backoff the coordinator waited. Zero once health has been sustained. */
    @Volatile
    var lastBackoffMs: Long = 0L
        private set

    /** Last delay assigned only to a failed LAN attempt. Relay reconnect is separate. */
    @Volatile
    var lastLanBackoffMs: Long = 0L
        private set

    /** Last delay assigned only to a failed Bluetooth attempt. */
    @Volatile
    var lastBluetoothBackoffMs: Long = 0L
        private set

    suspend fun run() {
        val relayRoute = relay
        if (preferDirect && directRoutes.isNotEmpty() && relayRoute != null) {
            runDirectPreferred(relayRoute)
        } else {
            runStaticPreference()
        }
    }

    private suspend fun runStaticPreference() {
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
            } catch (error: Exception) {
                runCatching { onEstablishedFailure(error) }
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

    /**
     * Direct-preferred operation keeps the current owner live while an ungranted candidate
     * authenticates. Relay is the owner of last resort; a lower-priority direct owner keeps
     * probing only the direct routes ranked above it. Each direct route has its own cooldown.
     */
    private suspend fun runDirectPreferred(relayRoute: TransportRoute) {
        val retries = directRoutes.associateWith { DirectRetryState(it.kind) }
        var openRelayFirst = false
        var relayFailures = 0
        while (currentCoroutineContext().isActive) {
            if (!openRelayFirst) {
                val direct = openDueDirect(retries)
                if (direct != null) {
                    carryGrantedDirect(direct.route, direct.session, clock(), retries)
                    // Direct loss must never make relay wait behind a direct cooldown.
                    openRelayFirst = true
                    continue
                }
            }
            openRelayFirst = false

            publish(RouteKind.NONE, RoutePhase.CONNECTING)
            val relaySession = openRoute(relayRoute)
            if (relaySession == null) {
                relayFailures += 1
                publish(RouteKind.NONE, RoutePhase.RECONNECTING)
                backOff(relayFailures)
                continue
            }
            relayFailures = 0
            publish(RouteKind.RELAY, RoutePhase.AUTHENTICATED)

            when (val result = carryOwnerUntilPromotion(relaySession, directRoutes, retries, probeTicker = true)) {
                is CarryResult.Promoted -> {
                    handOff(relaySession, result)
                    carryGrantedDirect(result.route, result.session, result.authenticatedAt, retries)
                    openRelayFirst = true
                }
                is CarryResult.Ended -> {
                    result.failure?.let {
                        if (it is CancellationException) throw it
                        runCatching { onEstablishedFailure(it) }
                    }
                    closeGrantedSession(
                        relaySession,
                        if (result.failure == null) COORDINATOR_STOPPED else ESTABLISHED_ROUTE_FAILURE,
                    )
                    relayFailures += 1
                    publish(RouteKind.NONE, RoutePhase.RECONNECTING)
                    backOff(relayFailures)
                }
            }
        }
    }

    /** Attempts direct routes in preference order, but only those whose cooldown is due. */
    private suspend fun openDueDirect(retries: Map<TransportRoute, DirectRetryState>): Granted? {
        for (route in directRoutes) {
            val retry = retries.getValue(route)
            if (clock() < retry.nextAttemptAt) continue
            publish(RouteKind.NONE, RoutePhase.CONNECTING)
            val session = openRoute(route)
            retry.lastAttemptAt = clock()
            if (session != null) return Granted(route, session)
            recordDirectFailure(retry)
        }
        return null
    }

    /**
     * Carries one granted direct session to its end. A lower-priority owner keeps probing the
     * direct routes ranked above it and hands the lease over when one authenticates; the
     * top-ranked owner has nothing to probe and is simply carried.
     */
    private suspend fun carryGrantedDirect(
        grantedRoute: TransportRoute,
        grantedSession: AuthenticatedRouteSession,
        grantedAt: Long,
        retries: Map<TransportRoute, DirectRetryState>,
    ) {
        var route = grantedRoute
        var session = grantedSession
        var authenticatedAt = grantedAt
        while (true) {
            publish(session.kind, RoutePhase.AUTHENTICATED)
            val candidates = directRoutes.subList(0, directRoutes.indexOf(route))
            if (candidates.isEmpty()) {
                carryOwned(session)
                recordDirectSessionEnd(retries.getValue(route), authenticatedAt)
                publish(RouteKind.NONE, RoutePhase.RECONNECTING)
                return
            }
            when (val result = carryOwnerUntilPromotion(session, candidates, retries, probeTicker = false)) {
                is CarryResult.Promoted -> {
                    handOff(session, result)
                    route = result.route
                    session = result.session
                    authenticatedAt = result.authenticatedAt
                }
                is CarryResult.Ended -> {
                    endDirectOwner(session, result.failure)
                    recordDirectSessionEnd(retries.getValue(route), authenticatedAt)
                    publish(RouteKind.NONE, RoutePhase.RECONNECTING)
                    return
                }
            }
        }
    }

    /** Runs the promotion loop, closing the owner if the loop itself throws. */
    private suspend fun carryOwnerUntilPromotion(
        owner: AuthenticatedRouteSession,
        candidates: List<TransportRoute>,
        retries: Map<TransportRoute, DirectRetryState>,
        probeTicker: Boolean,
    ): CarryResult = try {
        carryUntilPromotion(owner, candidates, retries, probeTicker)
    } catch (error: Throwable) {
        try {
            closeGrantedSession(owner, COORDINATOR_STOPPED)
        } catch (closeError: Throwable) {
            if (error !is CancellationException) throw closeError
        }
        throw error
    }

    /** Closes the current owner fully before the authenticated candidate may drain anything. */
    private suspend fun handOff(owner: AuthenticatedRouteSession, result: CarryResult.Promoted) {
        try {
            closeGrantedSession(owner, promotedTo(result.session.kind))
        } catch (error: Throwable) {
            withContext(NonCancellable) { result.session.close(promotionFailed(result.session.kind)) }
            throw error
        }
    }

    private suspend fun endDirectOwner(session: AuthenticatedRouteSession, failure: Throwable?) {
        val cancellation = failure as? CancellationException
        if (failure != null && cancellation == null) runCatching { onEstablishedFailure(failure) }
        try {
            closeGrantedSession(session, if (failure == null) COORDINATOR_STOPPED else ESTABLISHED_ROUTE_FAILURE)
        } catch (error: CancellationException) {
            if (cancellation == null) throw error
        } catch (_: Throwable) {
            // The route is already terminal; its stable close code is sufficient.
        }
        cancellation?.let { throw it }
    }

    private suspend fun openRoute(route: TransportRoute): AuthenticatedRouteSession? = try {
        route.open()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    private suspend fun carryOwned(session: AuthenticatedRouteSession) {
        var closeCode = COORDINATOR_STOPPED
        var carryCancellation: CancellationException? = null
        try {
            carry(session)
        } catch (error: CancellationException) {
            carryCancellation = error
            throw error
        } catch (error: Throwable) {
            closeCode = ESTABLISHED_ROUTE_FAILURE
            runCatching { onEstablishedFailure(error) }
        } finally {
            try {
                closeGrantedSession(session, closeCode)
            } catch (error: CancellationException) {
                if (carryCancellation == null) throw error
            } catch (_: Throwable) {
                // The route is already terminal; its stable close code is sufficient.
            }
        }
    }

    /**
     * Carries [owner] while probing [candidates] in preference order. A candidate that
     * authenticates is returned ungranted: the caller must close the owner first. The
     * owner's pump is joined before this returns, so the outbox never has two readers.
     */
    private suspend fun carryUntilPromotion(
        owner: AuthenticatedRouteSession,
        candidates: List<TransportRoute>,
        retries: Map<TransportRoute, DirectRetryState>,
        probeTicker: Boolean,
    ): CarryResult = coroutineScope {
        val ownerContext = currentCoroutineContext()
        val signals = Channel<CarrySignal>(Channel.UNLIMITED)
        val ownerWatcher = launch {
            val failure = try {
                owner.awaitClosed()
                null
            } catch (error: Throwable) {
                error
            }
            signals.send(CarrySignal.OwnerEnded(failure))
        }
        val ownerPump = if (owner.selfDraining) null else launch {
            val failure = try {
                pump(owner)
                null
            } catch (error: Throwable) {
                error
            }
            signals.send(CarrySignal.OwnerEnded(failure))
        }
        val retryWatcher = launch {
            retryRequests.collect { signals.send(CarrySignal.UserRetry) }
        }
        val directWatcher = launch {
            directAttemptRequests.collect { signals.send(CarrySignal.DirectRequest) }
        }
        val ticker = if (!probeTicker) null else launch {
            while (currentCoroutineContext().isActive) {
                ensureProbeSafely(requestDirect = false)
                delay(relayProbeIntervalMs)
            }
        }
        var candidate: AuthenticatedRouteSession? = null
        var handedOff = false
        try {
            while (currentCoroutineContext().isActive) {
                val nextDue = candidates.minOf { retries.getValue(it).nextAttemptAt }
                val waitMs = (nextDue - clock()).coerceAtLeast(0L)
                val timer = launch {
                    delay(waitMs)
                    signals.send(CarrySignal.CooldownDue)
                }
                val signal = try {
                    signals.receive()
                } finally {
                    timer.cancelAndJoin()
                }
                val now = clock()
                val due = when (signal) {
                    is CarrySignal.OwnerEnded -> return@coroutineScope CarryResult.Ended(signal.failure)
                    // The anti-storm floor applies per route: a peer request never reopens a
                    // route attempted within the last floor interval.
                    CarrySignal.DirectRequest -> candidates.filter { route ->
                        val sinceAttempt = retries.getValue(route).lastAttemptAt?.let { now - it } ?: Long.MAX_VALUE
                        sinceAttempt >= directAttemptFloorMs
                    }
                    CarrySignal.UserRetry -> candidates
                    CarrySignal.CooldownDue -> candidates.filter { retries.getValue(it).nextAttemptAt <= now }
                }
                if (due.isEmpty()) continue

                ensureProbeSafely(requestDirect = true)
                for (route in due) {
                    val retry = retries.getValue(route)
                    retry.lastAttemptAt = clock()
                    candidate = openRoute(route)
                    if (candidate == null) {
                        recordDirectFailure(retry)
                        continue
                    }
                    // If the owner ended during open(), the authenticated candidate still wins.
                    handedOff = true
                    return@coroutineScope CarryResult.Promoted(route, candidate, clock())
                }
            }
            CarryResult.Ended(null)
        } finally {
            withContext(NonCancellable) {
                ownerWatcher.cancelAndJoin()
                ownerPump?.cancelAndJoin()
                retryWatcher.cancelAndJoin()
                directWatcher.cancelAndJoin()
                ticker?.cancelAndJoin()
                signals.close()
                val unused = candidate
                if (unused != null && (!handedOff || !ownerContext.isActive)) {
                    unused.close(promotionFailed(unused.kind))
                }
            }
        }
    }

    private suspend fun ensureProbeSafely(requestDirect: Boolean) {
        try {
            relayProbeScheduler.ensureProbe(requestDirect)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Probe persistence is retried on the next tick; delivery stays live.
        }
    }

    private fun recordDirectSessionEnd(retry: DirectRetryState, authenticatedAt: Long) {
        if (clock() - authenticatedAt >= STABILITY_WINDOW_MS) {
            retry.failures = 0
            retry.nextAttemptAt = clock()
            recordDirectBackoff(retry.kind, 0L)
        } else {
            recordDirectFailure(retry)
        }
    }

    private fun recordDirectFailure(retry: DirectRetryState) {
        val wait = lanRetryPolicy.delay(retry.failures)
        retry.failures += 1
        retry.nextAttemptAt = clock() + wait
        recordDirectBackoff(retry.kind, wait)
    }

    private fun recordDirectBackoff(kind: RouteKind, wait: Long) {
        when (kind) {
            RouteKind.LAN -> lastLanBackoffMs = wait
            RouteKind.BLUETOOTH -> lastBluetoothBackoffMs = wait
            RouteKind.RELAY, RouteKind.NONE -> Unit
        }
    }

    /** Open routes in the configured order, granting at most one authenticated session. */
    private suspend fun openPreferred(): AuthenticatedRouteSession? {
        val relayRoute = listOfNotNull(relay)
        val routes = if (preferDirect) directRoutes + relayRoute else relayRoute + directRoutes
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
        try {
            withContext(NonCancellable) { session.close(code) }
        } catch (error: CancellationException) {
            // Coroutine stack-trace recovery may wrap a route-supplied cancellation.
            // Preserve the original object because callers use cancellation identity.
            throw (error.cause as? CancellationException ?: error)
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
        private const val ROUTE_PROMOTED_TO_LAN = "route_promoted_to_lan"
        private const val ROUTE_PROMOTED_TO_BLUETOOTH = "route_promoted_to_bluetooth"
        private const val LAN_PROMOTION_FAILED = "lan_promotion_failed"
        private const val BLUETOOTH_PROMOTION_FAILED = "bluetooth_promotion_failed"

        /** How long a session must stay authenticated before its route counts as healthy. */
        const val STABILITY_WINDOW_MS = 30_000L

        private fun promotedTo(kind: RouteKind): String =
            if (kind == RouteKind.BLUETOOTH) ROUTE_PROMOTED_TO_BLUETOOTH else ROUTE_PROMOTED_TO_LAN

        private fun promotionFailed(kind: RouteKind): String =
            if (kind == RouteKind.BLUETOOTH) BLUETOOTH_PROMOTION_FAILED else LAN_PROMOTION_FAILED
    }

    /** Per-route cooldown state. Every direct route backs off independently of the others. */
    private class DirectRetryState(
        val kind: RouteKind,
        var failures: Int = 0,
        var nextAttemptAt: Long = 0L,
        var lastAttemptAt: Long? = null,
    )

    private class Granted(val route: TransportRoute, val session: AuthenticatedRouteSession)

    private sealed interface CarryResult {
        class Promoted(
            val route: TransportRoute,
            val session: AuthenticatedRouteSession,
            val authenticatedAt: Long,
        ) : CarryResult

        class Ended(val failure: Throwable?) : CarryResult
    }

    private sealed interface CarrySignal {
        data object CooldownDue : CarrySignal
        data object UserRetry : CarrySignal
        data object DirectRequest : CarrySignal
        class OwnerEnded(val failure: Throwable?) : CarrySignal
    }
}
