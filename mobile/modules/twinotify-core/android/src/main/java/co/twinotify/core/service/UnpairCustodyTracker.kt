package co.twinotify.core.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class UnpairCustodyTracker {
    private val pending = mutableMapOf<String, CompletableDeferred<CustodyRoute>>()

    @Synchronized
    fun reserve(msgId: String): UnpairCustodyReservation {
        require(msgId.isNotBlank()) { "unpair custody message ID is required" }
        check(msgId !in pending) { "unpair custody message ID is already reserved" }
        val deferred = CompletableDeferred<CustodyRoute>()
        pending[msgId] = deferred
        return UnpairCustodyReservation(msgId, deferred, this)
    }

    fun accept(msgId: String, route: CustodyRoute): Boolean {
        val deferred = synchronized(this) { pending[msgId] } ?: return false
        return deferred.complete(route)
    }

    @Synchronized
    internal fun pendingCount(): Int = pending.size

    @Synchronized
    internal fun removeExact(msgId: String, deferred: CompletableDeferred<CustodyRoute>) {
        if (pending[msgId] === deferred) pending.remove(msgId)
    }
}

internal class UnpairCustodyReservation(
    private val msgId: String,
    private val deferred: CompletableDeferred<CustodyRoute>,
    private val owner: UnpairCustodyTracker,
) {
    suspend fun await(timeoutMillis: Long): CustodyRoute? = try {
        withTimeoutOrNull(timeoutMillis) { deferred.await() }
    } finally {
        owner.removeExact(msgId, deferred)
    }

    fun close() {
        owner.removeExact(msgId, deferred)
        deferred.cancel()
    }
}

internal class PreparedLocalUnpairService private constructor(
    val transportAvailable: Boolean,
    private val tracker: UnpairCustodyTracker?,
    private val onReserve: () -> Unit,
    private val quiesce: suspend () -> Unit,
) {
    private val quiesceMutex = Mutex()
    private var quiesced = false

    fun reserveCustody(msgId: String): UnpairCustodyReservation? {
        if (!transportAvailable) return null
        onReserve()
        return checkNotNull(tracker).reserve(msgId)
    }

    suspend fun quiesceAndAwait() {
        quiesceMutex.withLock {
            if (quiesced) return
            quiesce()
            quiesced = true
        }
    }

    companion object {
        fun available(
            tracker: UnpairCustodyTracker,
            onReserve: () -> Unit = {},
            quiesce: suspend () -> Unit,
        ) = PreparedLocalUnpairService(true, tracker, onReserve, quiesce)

        fun unavailable(
            quiesce: suspend () -> Unit,
        ) = PreparedLocalUnpairService(false, null, {}, quiesce)
    }
}

internal fun preparedLocalUnpairService(
    activeTransportJob: Job?,
    tracker: UnpairCustodyTracker,
    quiesce: suspend () -> Unit,
): PreparedLocalUnpairService = if (activeTransportJob?.isActive == true) {
    PreparedLocalUnpairService.available(tracker, quiesce = quiesce)
} else {
    PreparedLocalUnpairService.unavailable(quiesce)
}
