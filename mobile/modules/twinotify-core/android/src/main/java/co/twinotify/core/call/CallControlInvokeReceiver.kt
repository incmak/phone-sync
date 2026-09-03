package co.twinotify.core.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallControlInvokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val identity = MirrorCallControlIntent.parse(intent?.dataString) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                MirrorCallControlInvoker.production(context.applicationContext).invoke(identity)
            } finally {
                pending.finish()
            }
        }
    }
}
