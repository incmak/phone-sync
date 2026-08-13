package co.twinotify.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.listener.PendingPeerCancel
import co.twinotify.core.listener.DurableOutboundSink
import co.twinotify.core.storage.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MirrorTapReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val canonId = intent.getStringExtra("canon_id") ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val originDevice = DeviceIdentity.getOrCreate(ctx)
                // Mark our own cancel so the listener suppresses the echo.
                PendingPeerCancel.add(canonId)
                // Emit notif.cancel through the application-scoped durable capture sink.
                val sink = DurableOutboundSink.get(ctx)
                sink.enqueueCancel(canonId, "user_click", originDevice, System.currentTimeMillis())
            } finally { pendingResult.finish() }
        }
    }
}
