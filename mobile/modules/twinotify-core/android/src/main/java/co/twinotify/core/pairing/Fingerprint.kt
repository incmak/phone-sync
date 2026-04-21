package co.twinotify.core.pairing

import java.security.MessageDigest

/**
 * SHA-256(enc_pubkey || sign_pubkey), formatted as 16 groups of 4 uppercase hex
 * chars separated by dashes, e.g. "A1B2-C3D4-...-E5F6".
 * Shown to user during pairing for out-of-band comparison.
 */
object Fingerprint {
    fun of(encPubkey: ByteArray, signPubkey: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(encPubkey)
        md.update(signPubkey)
        val digest = md.digest()  // 32 bytes
        val hex = digest.joinToString("") { "%02X".format(it) }  // 64 chars
        return hex.chunked(4).joinToString("-")
    }
}
