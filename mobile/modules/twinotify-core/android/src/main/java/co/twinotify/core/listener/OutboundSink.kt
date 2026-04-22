package co.twinotify.core.listener

interface OutboundSink {
    suspend fun enqueuePost(post: NotifPostJson)
    suspend fun enqueueCancel(canonId: String, reason: String, originDevice: String, tsMs: Long)
    suspend fun enqueueUnpair(reason: String, originDevice: String, tsMs: Long)
}

/** Phase 3 placeholder until SyncService/OutboundQueue land in Task 4. Logs to logcat. */
object LoggingOutboundSink : OutboundSink {
    override suspend fun enqueuePost(post: NotifPostJson) {
        android.util.Log.i("Twinotify", "OUTBOUND post: canon=${post.canon_id} title=${post.title?.take(40)}")
    }
    override suspend fun enqueueCancel(canonId: String, reason: String, originDevice: String, tsMs: Long) {
        android.util.Log.i("Twinotify", "OUTBOUND cancel: canon=$canonId reason=$reason")
    }
    override suspend fun enqueueUnpair(reason: String, originDevice: String, tsMs: Long) {
        android.util.Log.i("Twinotify", "OUTBOUND unpair: reason=$reason origin=$originDevice")
    }
}
