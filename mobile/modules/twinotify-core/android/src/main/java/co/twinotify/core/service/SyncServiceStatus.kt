package co.twinotify.core.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class SyncState { DISCONNECTED, CONNECTING, CONNECTED, LEGACY_ONLINE_ONLY, OFFLINE_QUEUED }

object SyncServiceStatus {
    private val _state = MutableStateFlow(SyncState.DISCONNECTED)
    val state: StateFlow<SyncState> = _state
    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount
    private val _peerUnpaired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val peerUnpaired: SharedFlow<Unit> = _peerUnpaired

    fun setState(s: SyncState) { _state.value = s }
    fun setQueuedCount(n: Int) { _queuedCount.value = n }
    fun notifyPeerUnpaired() { _peerUnpaired.tryEmit(Unit) }
}
