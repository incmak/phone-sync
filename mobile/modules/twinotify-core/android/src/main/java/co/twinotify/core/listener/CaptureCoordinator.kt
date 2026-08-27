package co.twinotify.core.listener

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-scoped ordered capture coordinator. Each canonical notification owns one actor lane;
 * unrelated notifications are allowed to prepare and persist concurrently.
 */
class CaptureCoordinator(
    private val scope: CoroutineScope,
    private val persister: CapturePersister,
    private val laneIdleMs: Long = DEFAULT_LANE_IDLE_MS,
    internal val onLaneCompletionForTest: ((Throwable?) -> Unit)? = null,
    internal val beforePairingDeferralForTest: (suspend () -> Unit)? = null,
    internal val afterPairingDeferralForTest: (suspend () -> Unit)? = null,
    internal val beforeLaneRetirementForTest: (suspend () -> Unit)? = null,
) {
    private class Lane(
        val signal: Channel<Unit> = Channel(Channel.CONFLATED),
        var active: Boolean = true,
        var waitingForPeer: Boolean = false,
        var resumeRequested: Boolean = false,
        var head: CaptureCommand? = null,
        var latest: CaptureCommand? = null,
    )

    private val lanes = ConcurrentHashMap<String, Lane>()
    private val deferredUntilPaired = ConcurrentHashMap<String, CaptureCommand>()
    private val laneLock = Any()
    private var pairingGeneration = 0L

    init {
        require(laneIdleMs > 0) { "lane idle timeout must be positive" }
    }

    /** Enqueue without blocking the notification-listener callback. */
    fun submit(command: CaptureCommand): Boolean {
        if (!scope.isActive) return false
        synchronized(laneLock) {
            if (!scope.isActive) return false
            var lane = lanes[command.canonId]
            if (lane == null || !lane.active) {
                lane = Lane()
                lanes[command.canonId] = lane
                launchLane(command.canonId, lane)
            }
            if (lane.waitingForPeer) {
                // Before pairing there is no key with which to encrypt. Keep only the latest
                // state command per canonical ID, matching the safe compaction rule.
                deferredUntilPaired[command.canonId] = command
                return true
            }
            enqueueLocked(lane, command)
            return true
        }
    }

    private fun launchLane(canonId: String, lane: Lane): Job = scope.launch {
        var inFlight: CaptureCommand? = null
        try {
            while (isActive) {
                val command = nextCommand(canonId, lane) ?: return@launch
                inFlight = command
                persistSafely(command, lane)
                completeHead(canonId, lane, command)
                inFlight = null
            }
        } finally {
            withContext(NonCancellable) {
                beforeLaneRetirementForTest?.invoke()
            }
            retireLane(canonId, lane, inFlight)?.let { successor ->
                // The replacement is installed under laneLock before this launch. Starting it
                // afterward avoids reentrancy while preserving submits that arrive at the edge.
                launchLane(canonId, successor)
            }
        }
    }.also { job ->
        onLaneCompletionForTest?.let(job::invokeOnCompletion)
    }

    /** Returns the stable head without treating a coalesced later state as the next head early. */
    private suspend fun nextCommand(canonId: String, lane: Lane): CaptureCommand? {
        while (currentCoroutineContext().isActive) {
            synchronized(laneLock) {
                lane.head?.let { return it }
            }
            val signalled = withTimeoutOrNull(laneIdleMs) { lane.signal.receiveCatching().getOrNull() }
            if (signalled != null) continue
            synchronized(laneLock) {
                // A producer may have won the map lock just as receive timed out. Read the head
                // once before retirement so the idle-window transition cannot drop it.
                lane.head?.let { return it }
                if (lanes[canonId] === lane) {
                    lane.active = false
                    lanes.remove(canonId, lane)
                }
            }
            return null
        }
        return null
    }

    /** Advances exactly one ordered head, compacting only later state for this canonical ID. */
    private fun completeHead(canonId: String, lane: Lane, command: CaptureCommand) {
        synchronized(laneLock) {
            check(lane.head === command) { "capture lane head changed while persisting" }
            if (lane.waitingForPeer) {
                if (lane.resumeRequested) {
                    // Pairing resumed while the no-peer result was being completed. Prefer the
                    // retained later state; otherwise preserve this never-accepted head to retry.
                    lane.waitingForPeer = false
                    lane.resumeRequested = false
                    val deferredLater = deferredUntilPaired.remove(canonId)
                    lane.head = deferredLater ?: lane.latest ?: command
                    lane.latest = null
                    return
                }
                // A newer state may have arrived while the head discovered that pairing is absent.
                // It supersedes the deferred head only if no submit has already replaced that
                // head after the pairing failure was recorded.
                if (deferredUntilPaired[canonId] === command) {
                    lane.latest?.let { deferredUntilPaired[canonId] = it }
                }
                lane.head = null
                lane.latest = null
            } else {
                lane.head = lane.latest
                lane.latest = null
            }
        }
    }

    /** The bounded lane holds an ordered head plus only the latest later state. */
    private fun enqueueLocked(lane: Lane, command: CaptureCommand) {
        if (lane.head == null) {
            lane.head = command
        } else {
            lane.latest = command
        }
        lane.signal.trySend(Unit)
    }

    /** Retires only this worker, preserving a later state that arrived before cancellation won. */
    private fun retireLane(canonId: String, lane: Lane, inFlight: CaptureCommand?): Lane? =
        synchronized(laneLock) {
            if (lanes[canonId] !== lane) return@synchronized null
            val successor = if (lane.head === inFlight) {
                // The aborted head is never replayed. A terminal/latest state submitted while
                // it was in flight remains the sole successor.
                lane.latest
            } else {
                // The worker had already advanced, so retain its current ordered head.
                lane.head ?: lane.latest
            }
            lane.active = false
            lanes.remove(canonId, lane)
            if (successor == null || !scope.isActive) return@synchronized null
            Lane().also { replacement ->
                lanes[canonId] = replacement
                enqueueLocked(replacement, successor)
            }
        }

    private suspend fun persistSafely(command: CaptureCommand, lane: Lane) {
        var delayMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            val attemptPairingGeneration = synchronized(laneLock) { pairingGeneration }
            try {
                persister.persist(command)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: CaptureNotPairedException) {
                beforePairingDeferralForTest?.invoke()
                val deferralPublished = synchronized(laneLock) {
                    if (pairingGeneration != attemptPairingGeneration) {
                        false
                    } else {
                        // Either publish the no-peer transition as one locked state change or
                        // retry after a resume. There is no stale publication window between
                        // observing the generation and recording this lane's deferred head.
                        deferredUntilPaired[command.canonId] = command
                        if (lanes[command.canonId] === lane) lane.waitingForPeer = true
                        true
                    }
                }
                if (!deferralPublished) continue
                afterPairingDeferralForTest?.invoke()
                return
            } catch (error: CapturePermanentException) {
                // This command's immutable snapshot cannot become valid by retrying. Drop only
                // this head so a newer state already retained by the same lane can proceed.
                logPermanentFailure()
                return
            } catch (error: Throwable) {
                // Keep the command at the head of its canonical lane until the Room/crypto
                // boundary accepts it. This preserves callback order through temporary keystore,
                // pairing, or database failures without killing unrelated lanes.
                logPersistFailure()
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    internal fun activeLaneCountForTest(): Int = lanes.size

    /** Called after peer keys are stored, and on listener rebind. */
    fun resumeDeferred() {
        synchronized(laneLock) {
            pairingGeneration += 1
            val pending = deferredUntilPaired.values.toList()
            deferredUntilPaired.clear()
            pending.forEach { command ->
                var lane = lanes[command.canonId]
                if (lane != null && lane.active && lane.waitingForPeer && lane.head === command) {
                    // The worker still owns this head and will atomically promote its latest
                    // state in completeHead. Re-enqueuing here would replace that latest with
                    // the old head and can cause a duplicate persistence attempt.
                    lane.resumeRequested = true
                    return@forEach
                }
                if (lane == null || !lane.active) {
                    lane = Lane()
                    lanes[command.canonId] = lane
                    launchLane(command.canonId, lane)
                }
                lane.waitingForPeer = false
                enqueueLocked(lane, command)
            }
        }
    }

    internal fun deferredCountForTest(): Int = deferredUntilPaired.size

    private fun logPersistFailure() {
        runCatching {
            android.util.Log.e(TAG, retryableCaptureFailureLogMessage())
        }
    }

    private fun logPermanentFailure() {
        runCatching {
            android.util.Log.w(TAG, permanentCaptureFailureLogMessage())
        }
    }

    companion object {
        private const val TAG = "TwinotifyCapture"
        private const val DEFAULT_LANE_IDLE_MS = 30_000L
        private const val INITIAL_RETRY_DELAY_MS = 250L
        private const val MAX_RETRY_DELAY_MS = 30_000L

        @Volatile private var instance: CaptureCoordinator? = null

        fun get(context: Context): CaptureCoordinator = instance ?: synchronized(this) {
            instance ?: CaptureCoordinator(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                persister = DurableCapturePersister(context.applicationContext),
            ).also { instance = it }
        }

        internal fun resetForTest() {
            instance = null
        }
    }
}

/** A bounded diagnostic intentionally containing no notification-derived identifiers or content. */
internal fun permanentCaptureFailureLogMessage(): String =
    "capture persist discarded invalid state code=capture_validation"

/** A bounded diagnostic intentionally containing no notification-derived identifiers or content. */
internal fun retryableCaptureFailureLogMessage(): String =
    "capture persist failed code=retryable subsystem=capture"
