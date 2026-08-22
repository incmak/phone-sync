package co.twinotify.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.twinotify.core.storage.PeerStore
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Native DataStore is authoritative. Never resurrect a service that the user stopped,
        // even if an old SharedPreferences relay_url remains from a prior app version.
        val config = runBlocking { ServiceConfigStore.read(ctx) }
        val paired = runBlocking { PeerStore.load(ctx) != null }
        val lanBound = runBlocking { PeerStore.load(ctx)?.lanBindingId != null }
        val decision = ServiceStartPolicy.decide(
            Intent.ACTION_BOOT_COMPLETED,
            config,
            paired,
            lanBound,
        )
        val start = decision as? ServiceStartDecision.Start ?: return
        val svcIntent = Intent(ctx, SyncService::class.java).apply {
            action = SyncService.ACTION_START
            // A LAN-only peer has no relay URL to carry, and must still boot.
            start.relayUrl?.let { putExtra(SyncService.EXTRA_RELAY_URL, it) }
        }
        ctx.startForegroundService(svcIntent)
    }
}
