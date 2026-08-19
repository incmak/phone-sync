package co.twinotify.core.service

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Orders legacy v1 preparation and replay protection without exposing mutable test hooks.
 *
 * Only an authenticated, parsed inner event reaches the replay guard. Once marked, dispatch
 * remains deliberately non-retryable because it may perform destructive Android side effects.
 */
internal class LegacyInboundProcessor<Peer>(
    private val loadPeer: suspend () -> Peer?,
    private val decrypt: suspend (EncryptedEnvelope, Peer) -> ByteArray?,
    private val parseInner: (ByteArray) -> JSONObject?,
    private val seenOrMark: suspend (String) -> Boolean,
    private val dispatchInner: suspend (JSONObject) -> Unit,
) {
    suspend fun process(envelope: EncryptedEnvelope) {
        val peer = prepare { loadPeer() } ?: return
        val plaintext = prepare { decrypt(envelope, peer) } ?: return
        val inner = prepare { parseInner(plaintext) } ?: return
        if (seenOrMark(envelope.msgId)) return
        dispatchInner(inner)
    }

    private suspend fun <T> prepare(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
