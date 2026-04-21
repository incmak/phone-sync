package co.twinotify.core.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SyncState { DISCONNECTED, CONNECTING, CONNECTED, OFFLINE_QUEUED }

object SyncServiceStatus {
    private val _state = MutableStateFlow(SyncState.DISCONNECTED)
    val state: StateFlow<SyncState> = _state
    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount

    fun setState(s: SyncState) { _state.value = s }
    fun setQueuedCount(n: Int) { _queuedCount.value = n }
}
