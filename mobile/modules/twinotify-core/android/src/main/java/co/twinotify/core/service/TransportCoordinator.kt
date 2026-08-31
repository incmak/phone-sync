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
    /** Authenticated peer requests that both phones overlap one bounded LAN attempt. */
    private val directAttemptRequests: Flow<Unit> = emptyFlow(),
    private val relayProbeScheduler: RelayProbeScheduler = RelayProbeScheduler {},
    private val lanRetryPolicy: LanRetryPolicy = LanRetryPolicy(),
    private val relayProbeIntervalMs: Long = 60_000L,
    private val directAttemptFloorMs: Long = 15_000L,
    private val onEstablishedFailure: (Throwable) -> Unit = {},
) {
    private val healthState = MutableStateFlow(RouteHealth())
    val health: StateFlow<RouteHealth> = healthState.asStateFlow()

    /** Last backoff the coordinator waited. Zero once health has been sustained. */
    @Volatile
    var lastBackoffMs: Long = 0L
        private set

    /** Last delay assigned only to a failed direct attempt. Relay reconnect is separate. */
    @Volatile
    var lastLanBackoffMs: Long = 0L
        private set

    suspend fun run() {
        if (preferLan && lan != null && relay != null) {
            runLanPreferred(lan, relay)
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
     * LAN-preferred operation keeps relay delivery live while an ungranted LAN candidate
     * authenticates. The candidate is granted only after relay close has fully joined.
     */
    private suspend fun runLanPreferred(lanRoute: TransportRoute, relayRoute: TransportRoute) {
        val lanRetry = LanRetryState()
        var openRelayFirst = false
        var relayFailures = 0
        while (currentCoroutineContext().isActive) {
            if (!openRelayFirst && clock() >= lanRetry.nextAttemptAt) {
                publish(RouteKind.NONE, RoutePhase.CONNECTING)
                val direct = openRoute(lanRoute)
                lanRetry.lastAttemptAt = clock()
                if (direct != null) {
                    publish(RouteKind.LAN, RoutePhase.AUTHENTICATED)
                    val authenticatedAt = clock()
                    carryOwned(direct)
                    recordLanSessionEnd(lanRetry, authenticatedAt)
                    publish(RouteKind.NONE, RoutePhase.RECONNECTING)
                    // Direct loss must never make relay wait behind the LAN cooldown.
                    openRelayFirst = true
                    continue
                }
                recordLanFailure(lanRetry)
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

            val relayResult = try {
                carryRelayUntilPromotion(relaySession, lanRoute, lanRetry)
            } catch (error: Throwable) {
                try {
                    closeGrantedSession(relaySession, COORDINATOR_STOPPED)
                } catch (closeError: Throwable) {
                    if (error !is CancellationException) throw closeError
                }
                throw error
            }
            when (val result = relayResult) {
                is RelayCarryResult.Promoted -> {
                    try {
                        closeGrantedSession(relaySession, ROUTE_PROMOTED_TO_LAN)
                    } catch (error: Throwable) {
                        withContext(NonCancellable) { result.session.close(LAN_PROMOTION_FAILED) }
                        throw error
                    }
                    publish(RouteKind.LAN, RoutePhase.AUTHENTICATED)
                    val authenticatedAt = result.authenticatedAt
                    carryOwned(result.session)
                    recordLanSessionEnd(lanRetry, authenticatedAt)
                    publish(RouteKind.NONE, RoutePhase.RECONNECTING)
                    openRelayFirst = true
                }
                is RelayCarryResult.Ended -> {
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

    private suspend fun carryRelayUntilPromotion(
        relaySession: AuthenticatedRouteSession,
        lanRoute: TransportRoute,
        retry: LanRetryState,
    ): RelayCarryResult = coroutineScope {
        val ownerContext = currentCoroutineContext()
        val signals = Channel<RelaySignal>(Channel.UNLIMITED)
        val relayWatcher = launch {
            val failure = try {
                relaySession.awaitClosed()
                null
            } catch (error: Throwable) {
                error
            }
            signals.send(RelaySignal.RelayEnded(failure))
        }
        val relayPump = if (relaySession.selfDraining) null else launch {
            val failure = try {
                pump(relaySession)
                null
            } catch (error: Throwable) {
                error
            }
            signals.send(RelaySignal.RelayEnded(failure))
        }
        val retryWatcher = launch {
            retryRequests.collect { signals.send(RelaySignal.UserRetry) }
        }
        val directWatcher = launch {
            directAttemptRequests.collect { signals.send(RelaySignal.DirectRequest) }
        }
        val probeTicker = launch {
            while (currentCoroutineContext().isActive) {
                ensureProbeSafely(requestDirect = false)
                delay(relayProbeIntervalMs)
            }
        }
        var candidate: AuthenticatedRouteSession? = null
        var handedOff = false
        try {
            while (currentCoroutineContext().isActive) {
                val waitMs = (retry.nextAttemptAt - clock()).coerceAtLeast(0L)
                val timer = launch {
                    delay(waitMs)
                    signals.send(RelaySignal.CooldownDue)
                }
                val signal = try {
                    signals.receive()
                } finally {
                    timer.cancelAndJoin()
                }
                when (signal) {
                    is RelaySignal.RelayEnded -> return@coroutineScope RelayCarryResult.Ended(signal.failure)
                    RelaySignal.DirectRequest -> {
                        val sinceAttempt = retry.lastAttemptAt?.let { clock() - it } ?: Long.MAX_VALUE
                        if (sinceAttempt < directAttemptFloorMs) continue
                    }
                    RelaySignal.UserRetry,
                    RelaySignal.CooldownDue,
                    -> Unit
                }

                retry.lastAttemptAt = clock()
                ensureProbeSafely(requestDirect = true)
                candidate = openRoute(lanRoute)
                if (candidate == null) {
                    recordLanFailure(retry)
                    continue
                }

                // If relay ended during open(), the authenticated candidate still wins.
                handedOff = true
                return@coroutineScope RelayCarryResult.Promoted(candidate, clock())
            }
            RelayCarryResult.Ended(null)
        } finally {
            withContext(NonCancellable) {
                relayWatcher.cancelAndJoin()
                relayPump?.cancelAndJoin()
                retryWatcher.cancelAndJoin()
                directWatcher.cancelAndJoin()
                probeTicker.cancelAndJoin()
                signals.close()
                if (!handedOff || !ownerContext.isActive) {
                    candidate?.close(LAN_PROMOTION_FAILED)
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
            // Probe persistence is retried on the next tick; relay delivery stays live.
        }
    }

    private fun recordLanSessionEnd(retry: LanRetryState, authenticatedAt: Long) {
        if (clock() - authenticatedAt >= STABILITY_WINDOW_MS) {
            retry.failures = 0
            retry.nextAttemptAt = clock()
            lastLanBackoffMs = 0L
        } else {
            recordLanFailure(retry)
        }
    }

    private fun recordLanFailure(retry: LanRetryState) {
        val wait = lanRetryPolicy.delay(retry.failures)
        retry.failures += 1
        retry.nextAttemptAt = clock() + wait
        lastLanBackoffMs = wait
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
        private const val LAN_PROMOTION_FAILED = "lan_promotion_failed"

        /** How long a session must stay authenticated before its route counts as healthy. */
        const val STABILITY_WINDOW_MS = 30_000L
    }

    private data class LanRetryState(
        var failures: Int = 0,
        var nextAttemptAt: Long = 0L,
        var lastAttemptAt: Long? = null,
    )

    private sealed interface RelayCarryResult {
        data class Promoted(
            val session: AuthenticatedRouteSession,
            val authenticatedAt: Long,
        ) : RelayCarryResult

        data class Ended(val failure: Throwable?) : RelayCarryResult
    }

    private sealed interface RelaySignal {
        data object CooldownDue : RelaySignal
        data object UserRetry : RelaySignal
        data object DirectRequest : RelaySignal
        data class RelayEnded(val failure: Throwable?) : RelaySignal
    }
}
