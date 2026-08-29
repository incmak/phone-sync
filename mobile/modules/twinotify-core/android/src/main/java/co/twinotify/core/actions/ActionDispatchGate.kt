package co.twinotify.core.actions

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Dormant production seam. Only the debug-only E2E receiver can arm it. */
internal object ActionDispatchGate {
    private val pause = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun arm(): Boolean = pause.compareAndSet(null, CompletableDeferred())

    fun release(): Boolean = pause.getAndSet(null)?.let {
        it.complete(Unit)
        true
    } ?: false

    suspend fun awaitIfArmed() {
        pause.get()?.await()
    }
}
