package co.twinotify.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(ctx: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> RecoveryTrigger.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> RecoveryTrigger.PACKAGE_REPLACED
            else -> return
        }
        val pendingResult = goAsync()
        scope.launch {
            try {
                TransportRecoveryAuthority.recover(ctx.applicationContext, trigger)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
