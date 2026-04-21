package co.twinotify.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.listener.PendingPeerCancel
import co.twinotify.core.listener.TwinotifyNotificationListener
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
                // Emit notif.cancel reason=user_click via the installed sink.
                val sink = TwinotifyNotificationListener.currentSink()
                sink.enqueueCancel(canonId, "user_click", originDevice, System.currentTimeMillis())
            } finally { pendingResult.finish() }
        }
    }
}
