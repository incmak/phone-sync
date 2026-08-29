package co.twinotify.core.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ActionInvokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val identity = MirrorActionIntent.parse(intent?.dataString) ?: return
        val replyText = intent?.let(RemoteInput::getResultsFromIntent)
            ?.getCharSequence(MirrorActionIntent.REMOTE_INPUT_KEY)
            ?.toString()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                MirrorActionInvoker.production(context.applicationContext).invoke(identity, replyText)
            } finally {
                pending.finish()
            }
        }
    }
}
