package co.twinotify.core.call

import android.app.PendingIntent
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

interface CallControlCaptureSink {
    fun onPosted(snapshot: CallCapabilityCandidate<PendingIntent>): Boolean
    fun onRemoved(sourceKey: String)
}

/** Process bridge between Android's listener service and the transport-owned call coordinator. */
object CallControlCaptureBridge {
    private val sink = AtomicReference<CallControlCaptureSink?>(null)

    fun attach(value: CallControlCaptureSink) {
        sink.set(value)
    }

    fun detach(value: CallControlCaptureSink) {
        sink.compareAndSet(value, null)
    }

    fun posted(value: CallCapabilityCandidate<PendingIntent>): Boolean =
        sink.get()?.onPosted(value) == true

    fun removed(sourceKey: String) {
        sink.get()?.onRemoved(sourceKey)
    }
}

/** Memory-only origin handles. Process restart intentionally invalidates every capability UUID. */
object ProcessCallCapabilityRegistry {
    val registry = CallCapabilityRegistry<PendingIntent>()
}

internal fun callControlCaptureEnabled(
    serviceEnabled: Boolean,
    callStateEnabled: Boolean,
    controlsEnabled: Boolean,
): Boolean = serviceEnabled && callStateEnabled && controlsEnabled

/** Enter the coordinator mutex on the caller thread so callback order cannot invert on IO. */
internal fun launchOrderedCallControlMutation(
    scope: CoroutineScope,
    mutation: suspend () -> Unit,
): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { mutation() }

/**
 * Pure candidate lifecycle shared by the Android bridge and JVM tests. Publication callbacks are
 * intentionally non-blocking at the listener boundary; the service schedules durable work.
 */
internal class CallControlCaptureSession<T>(
    private val defaultDialerPackage: () -> String?,
    private val currentCall: () -> CallControlSessionSnapshot?,
    private val publish: (String, Map<CallControlKind, T>) -> Unit,
    private val clearPublished: () -> Unit,
    private val selector: CallCapabilitySelector = CallCapabilitySelector(),
) {
    private val lock = Any()
    private val candidates = linkedMapOf<String, CallCapabilityCandidate<T>>()
    private var published: PublishedCandidate<T>? = null

    fun onPosted(candidate: CallCapabilityCandidate<T>): Boolean = synchronized(lock) {
        val duplicate = candidates[candidate.sourceKey] == candidate
        candidates[candidate.sourceKey] = candidate
        evaluate(triggerSourceKey = candidate.sourceKey, allowRepublish = !duplicate)
    }

    fun onRemoved(sourceKey: String) = synchronized(lock) {
        candidates.remove(sourceKey)
        evaluate(triggerSourceKey = null, allowRepublish = true)
        Unit
    }

    fun onCallStateChanged() = synchronized(lock) {
        evaluate(triggerSourceKey = null, allowRepublish = true)
        Unit
    }

    /** A stale durable sequence was terminally discarded; mint a newer generation after rebase. */
    fun onStateCommitRejected() = synchronized(lock) {
        published = null
        evaluate(triggerSourceKey = null, allowRepublish = true)
        Unit
    }

    /** Disable, unpair, and process teardown all invalidate candidates and installed handles. */
    fun clear() = synchronized(lock) {
        candidates.clear()
        published = null
        clearPublished()
    }

    private fun evaluate(triggerSourceKey: String?, allowRepublish: Boolean): Boolean {
        val call = currentCall()
        if (call == null) {
            clearIfPublished()
            return false
        }
        return when (val selection = selector.select(
            defaultDialerPackage = defaultDialerPackage(),
            state = call.state,
            direction = call.direction,
            candidates = candidates.values.toList(),
        )) {
            is CallCapabilitySelection.None -> {
                clearIfPublished()
                false
            }
            is CallCapabilitySelection.Ready -> {
                val next = PublishedCandidate(call, selection.sourceKey, selection.handles)
                if (allowRepublish && published != next) {
                    published = next
                    publish(selection.sourceKey, selection.handles)
                }
                selection.sourceKey == triggerSourceKey
            }
        }
    }

    private fun clearIfPublished() {
        if (published == null) return
        published = null
        clearPublished()
    }
}

private data class PublishedCandidate<T>(
    val call: CallControlSessionSnapshot,
    val sourceKey: String,
    val handles: Map<CallControlKind, T>,
)
