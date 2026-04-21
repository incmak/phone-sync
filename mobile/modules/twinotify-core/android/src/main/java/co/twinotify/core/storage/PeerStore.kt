package co.twinotify.core.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.peerDs by preferencesDataStore("twinotify_peer")

data class PeerRecord(val deviceId: String, val encPubkey: ByteArray, val signPubkey: ByteArray)

object PeerStore {
    private val KEY_PEER_DEVICE = stringPreferencesKey("peer_device_id")
    private val KEY_PEER_ENC    = byteArrayPreferencesKey("peer_enc_pubkey")
    private val KEY_PEER_SIGN   = byteArrayPreferencesKey("peer_sign_pubkey")

    suspend fun save(ctx: Context, r: PeerRecord) {
        ctx.peerDs.edit { e ->
            e[KEY_PEER_DEVICE] = r.deviceId
            e[KEY_PEER_ENC]    = r.encPubkey
            e[KEY_PEER_SIGN]   = r.signPubkey
        }
    }

    suspend fun load(ctx: Context): PeerRecord? {
        val prefs = ctx.peerDs.data.first()
        val dev  = prefs[KEY_PEER_DEVICE] ?: return null
        val enc  = prefs[KEY_PEER_ENC]    ?: return null
        val sign = prefs[KEY_PEER_SIGN]   ?: return null
        return PeerRecord(dev, enc, sign)
    }

    suspend fun clear(ctx: Context) {
        ctx.peerDs.edit { it.clear() }
    }
}
