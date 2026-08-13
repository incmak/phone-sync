package co.twinotify.core.listener

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-scoped ordered capture coordinator. Each canonical notification owns one actor lane;
 * unrelated notifications are allowed to prepare and persist concurrently.
 */
class CaptureCoordinator(
    private val scope: CoroutineScope,
    private val persister: CapturePersister,
    private val laneIdleMs: Long = DEFAULT_LANE_IDLE_MS,
) {
    private class Lane(
        val channel: Channel<CaptureCommand>,
        var active: Boolean = true,
        var waitingForPeer: Boolean = false,
    )

    private val lanes = ConcurrentHashMap<String, Lane>()
    private val deferredUntilPaired = ConcurrentHashMap<String, CaptureCommand>()
    private val laneLock = Any()

    init {
        require(laneIdleMs > 0) { "lane idle timeout must be positive" }
    }

    /** Enqueue without blocking the notification-listener callback. */
    fun submit(command: CaptureCommand): Boolean {
        synchronized(laneLock) {
            var lane = lanes[command.canonId]
            if (lane == null || !lane.active) {
                lane = Lane(Channel(Channel.UNLIMITED))
                lanes[command.canonId] = lane
                launchLane(command.canonId, lane)
            }
            if (lane.waitingForPeer) {
                // Before pairing there is no key with which to encrypt. Keep only the latest
                // state command per canonical ID, matching the safe compaction rule.
                deferredUntilPaired[command.canonId] = command
                return true
            }
            // The map lock is shared with timeout retirement. A successful send cannot be left
            // behind after a lane has been removed.
            return lane.channel.trySend(command).isSuccess
        }
    }

    private fun launchLane(canonId: String, lane: Lane): Job = scope.launch {
        while (isActive) {
            val command = withTimeoutOrNull(laneIdleMs) { lane.channel.receiveCatching().getOrNull() }
            if (command == null) {
                val boundaryCommand: CaptureCommand?
                synchronized(laneLock) {
                    // A producer may have won the map lock just as receive timed out. Drain one
                    // boundary item before retiring so the idle-window transition cannot drop it.
                    boundaryCommand = lane.channel.tryReceive().getOrNull()
                    if (boundaryCommand == null && lanes[canonId] === lane) {
                        lane.active = false
                        lanes.remove(canonId, lane)
                    }
                }
                if (boundaryCommand == null) return@launch
                persistSafely(boundaryCommand)
            } else {
                persistSafely(command)
            }
        }
    }

    private suspend fun persistSafely(command: CaptureCommand) {
        var delayMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            try {
                persister.persist(command)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: CaptureNotPairedException) {
                synchronized(laneLock) {
                    deferredUntilPaired[command.canonId] = command
                    lanes[command.canonId]?.waitingForPeer = true
                }
                return
            } catch (error: Throwable) {
                // Keep the command at the head of its canonical lane until the Room/crypto
                // boundary accepts it. This preserves callback order through temporary keystore,
                // pairing, or database failures without killing unrelated lanes.
                logPersistFailure(command, error)
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    internal fun activeLaneCountForTest(): Int = lanes.size

    /** Called after peer keys are stored, and on listener rebind. */
    fun resumeDeferred() {
        synchronized(laneLock) {
            val pending = deferredUntilPaired.values.toList()
            deferredUntilPaired.clear()
            pending.forEach { command ->
                var lane = lanes[command.canonId]
                if (lane == null || !lane.active) {
                    lane = Lane(Channel(Channel.UNLIMITED))
                    lanes[command.canonId] = lane
                    launchLane(command.canonId, lane)
                }
                lane.waitingForPeer = false
                lane.channel.trySend(command)
            }
        }
    }

    internal fun deferredCountForTest(): Int = deferredUntilPaired.size

    private fun logPersistFailure(command: CaptureCommand, error: Throwable) {
        runCatching {
            android.util.Log.e(
                TAG,
                "capture persist failed; retrying canon=${command.canonId} source=${command.sourceKey}",
                error,
            )
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
