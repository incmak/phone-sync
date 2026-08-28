package co.twinotify.core.call

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal const val CALL_SHUTDOWN_FAILED = "call_shutdown_failed"
internal const val CALL_SHUTDOWN_STALE = "call_shutdown_stale"

private const val SHUTDOWN_MAX_ATTEMPTS = 3
private const val SHUTDOWN_RETRY_DELAY_MS = 1_000L

internal sealed interface GracefulCallShutdownResult {
    data object Completed : GracefulCallShutdownResult
    data class Failed(val code: String) : GracefulCallShutdownResult
}

internal data class CallShutdownConfigIntent(
    val disableCallCapture: Boolean,
    val disableService: Boolean,
) {
    fun mergedWith(other: CallShutdownConfigIntent): CallShutdownConfigIntent =
        CallShutdownConfigIntent(
            disableCallCapture = disableCallCapture || other.disableCallCapture,
            disableService = disableService || other.disableService,
        )

    internal fun covers(other: CallShutdownConfigIntent): Boolean =
        (disableCallCapture || !other.disableCallCapture) &&
            (disableService || !other.disableService)

    internal companion object {
        val None = CallShutdownConfigIntent(
            disableCallCapture = false,
            disableService = false,
        )
    }
}

internal suspend fun gracefullyShutdownCallCapture(
    quiesceAndTerminalize: suspend () -> Unit,
    reportFailure: (String) -> Unit,
    delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): GracefulCallShutdownResult = retryCallShutdownPhase(
    operation = quiesceAndTerminalize,
    failureCode = { failure ->
        if ((failure as? ActiveCallRecoveryException)?.code == "call_recovery_stale") {
            CALL_SHUTDOWN_STALE
        } else {
            CALL_SHUTDOWN_FAILED
        }
    },
    reportFailure = reportFailure,
    delayBeforeRetry = delayBeforeRetry,
)

internal suspend fun persistDisabledForCallShutdown(
    persistDisabled: suspend () -> Unit,
    reportFailure: (String) -> Unit,
    delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): GracefulCallShutdownResult = retryCallShutdownPhase(
    operation = persistDisabled,
    failureCode = { CALL_SHUTDOWN_FAILED },
    reportFailure = reportFailure,
    delayBeforeRetry = delayBeforeRetry,
)

private suspend fun retryCallShutdownPhase(
    operation: suspend () -> Unit,
    failureCode: (Exception) -> String,
    reportFailure: (String) -> Unit,
    delayBeforeRetry: suspend (Long) -> Unit,
): GracefulCallShutdownResult {
    repeat(SHUTDOWN_MAX_ATTEMPTS) { attempt ->
        try {
            operation()
            return GracefulCallShutdownResult.Completed
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val code = failureCode(failure)
            try {
                reportFailure(code)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Health reporting is advisory and cannot consume the retry budget.
            }
            if (attempt == SHUTDOWN_MAX_ATTEMPTS - 1) {
                return GracefulCallShutdownResult.Failed(code)
            }
            delayBeforeRetry(SHUTDOWN_RETRY_DELAY_MS)
        }
    }
    error("unreachable shutdown retry state")
}

/** Deduplicates shutdown work and reserves capture admission until every requested bit persists. */
internal class GracefulCallShutdownGate(
    private val afterGenerationSealed: suspend () -> Unit = {},
) {
    private class ShutdownGeneration(
        val deferred: Deferred<GracefulCallShutdownResult>,
    ) {
        var releaseAdmission = false
    }

    private var active: ShutdownGeneration? = null
    private var queued: ShutdownGeneration? = null
    private var requestedIntent = CallShutdownConfigIntent.None
    private var coveredIntent = CallShutdownConfigIntent.None
    private var reserved = false
    private var acceptingIntents = false
    private var managedPersistenceUsed = false
    private var sealedIntent: CallShutdownConfigIntent? = null
    private var retainedFailureCode: String? = null
    private var releaseSignal = completedSignal()

    @Synchronized
    fun start(
        scope: CoroutineScope,
        intent: CallShutdownConfigIntent,
        shutdown: suspend () -> GracefulCallShutdownResult,
    ): Deferred<GracefulCallShutdownResult> {
        requestedIntent = requestedIntent.mergedWith(intent)
        if (!reserved) {
            reserved = true
            releaseSignal = CompletableDeferred()
        }
        active?.let { current ->
            if (acceptingIntents && !current.deferred.isCompleted) return current.deferred
            queued?.takeIf { !it.deferred.isCompleted }?.let { return it.deferred }
            return newGeneration(scope, shutdown).also { successor ->
                queued = successor
                installCompletion(successor)
            }.deferred
        }

        return newGeneration(scope, shutdown).also { next ->
            active = next
            prepareActiveGeneration()
            installCompletion(next)
            next.deferred.start()
        }.deferred
    }

    private fun newGeneration(
        scope: CoroutineScope,
        shutdown: suspend () -> GracefulCallShutdownResult,
    ): ShutdownGeneration {
        lateinit var generation: ShutdownGeneration
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            val result = shutdown()
            synchronized(this@GracefulCallShutdownGate) {
                val intent = sealedIntent ?: requestedIntent.also { sealedIntent = it }
                if (result == GracefulCallShutdownResult.Completed) {
                    if (!managedPersistenceUsed) coveredIntent = coveredIntent.mergedWith(intent)
                    generation.releaseAdmission = coveredIntent.covers(intent)
                } else if (result is GracefulCallShutdownResult.Failed) {
                    retainedFailureCode = result.code
                }
                acceptingIntents = false
            }
            afterGenerationSealed()
            result
        }
        generation = ShutdownGeneration(deferred)
        return generation
    }

    private fun prepareActiveGeneration() {
        acceptingIntents = true
        managedPersistenceUsed = false
        sealedIntent = null
    }

    private fun installCompletion(generation: ShutdownGeneration) {
        generation.deferred.invokeOnCompletion { failure ->
            var promote: ShutdownGeneration? = null
            synchronized(this) {
                if (failure != null && retainedFailureCode == null) {
                    retainedFailureCode = CALL_SHUTDOWN_FAILED
                }
                if (active === generation) {
                    active = null
                    val successor = queued
                    queued = null
                    if (successor != null && !successor.deferred.isCompleted) {
                        active = successor
                        prepareActiveGeneration()
                        promote = successor
                    } else if (successor == null && failure == null && generation.releaseAdmission) {
                        requestedIntent = CallShutdownConfigIntent.None
                        coveredIntent = CallShutdownConfigIntent.None
                        acceptingIntents = false
                        managedPersistenceUsed = false
                        sealedIntent = null
                        retainedFailureCode = null
                        reserved = false
                        releaseSignal.complete(Unit)
                    }
                }
            }
            promote?.deferred?.start()
        }
    }

    /**
     * Persists stable snapshots outside the monitor until the last successful snapshot covers
     * every monotonically merged request. A stronger request therefore gets one new phase.
     */
    suspend fun persistMergedIntent(
        persist: suspend (CallShutdownConfigIntent) -> GracefulCallShutdownResult,
    ): GracefulCallShutdownResult {
        synchronized(this) { managedPersistenceUsed = true }
        while (true) {
            val snapshot = synchronized(this) { requestedIntent }
            val result = persist(snapshot)
            if (result != GracefulCallShutdownResult.Completed) return result
            val complete = synchronized(this) {
                coveredIntent = coveredIntent.mergedWith(snapshot)
                coveredIntent.covers(requestedIntent).also { covered ->
                    if (covered) {
                        sealedIntent = requestedIntent
                        acceptingIntents = false
                    }
                }
            }
            if (complete) return GracefulCallShutdownResult.Completed
        }
    }

    @Synchronized
    fun isReserved(): Boolean = reserved

    suspend fun awaitRelease() {
        val signal = synchronized(this) {
            if (!reserved) return
            releaseSignal
        }
        signal.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitReleaseForEnable() {
        while (true) {
            val generation = synchronized(this) {
                if (!reserved) return
                active ?: throw ActiveCallRecoveryException(
                    retainedFailureCode ?: CALL_SHUTDOWN_FAILED,
                )
            }
            val result = try {
                generation.deferred.await()
            } catch (cancellation: CancellationException) {
                val completedCancellation = if (generation.deferred.isCompleted) {
                    generation.deferred.getCompletionExceptionOrNull() as? CancellationException
                } else {
                    null
                }
                if (completedCancellation == null) {
                    // Read the cancellation recorded by this waiter without stack recovery.
                    currentCoroutineContext().ensureActive()
                }
                throw (completedCancellation ?: cancellation)
            }
            when (result) {
                GracefulCallShutdownResult.Completed -> Unit
                is GracefulCallShutdownResult.Failed ->
                    throw ActiveCallRecoveryException(result.code)
            }
        }
    }

    private companion object {
        fun completedSignal(): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().apply { complete(Unit) }
    }
}
