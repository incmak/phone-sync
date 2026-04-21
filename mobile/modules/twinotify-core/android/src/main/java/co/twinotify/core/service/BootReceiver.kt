package co.twinotify.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.storage.PeerStore
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Only restart if a peer is paired AND user opted into Always-Connected (default for Phase 3).
        val peer = runBlocking { PeerStore.load(ctx) } ?: return
        val prefs = ctx.getSharedPreferences("twinotify_service", Context.MODE_PRIVATE)
        val relayUrl = prefs.getString("relay_url", null) ?: return
        val svcIntent = Intent(ctx, SyncService::class.java).apply {
            action = SyncService.ACTION_START
            putExtra(SyncService.EXTRA_RELAY_URL, relayUrl)
        }
        ctx.startForegroundService(svcIntent)
    }
}
