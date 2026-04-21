package co.twinotify.core.auth

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * Mints Ed25519-signed JWTs for relay authentication.
 * Payload: { sub, jti, iat, exp }. Algorithm: EdDSA. No external JWT library —
 * hand-rolled to avoid Auth0/jjwt Android compat headaches.
 */
object JwtMinter {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val sodium = ls.sodium

    fun mint(deviceId: String, signSecret: ByteArray, nowSec: Long = System.currentTimeMillis() / 1000): String {
        require(signSecret.size == Sign.SECRETKEYBYTES) {
            "libsodium Ed25519 secret key is ${Sign.SECRETKEYBYTES} bytes; got ${signSecret.size}"
        }
        val header = """{"alg":"EdDSA","typ":"JWT"}"""
        val payload = JSONObject(mapOf(
            "sub" to deviceId,
            "jti" to UUID.randomUUID().toString(),
            "iat" to nowSec,
            "exp" to nowSec + 60,
        )).toString()

        val b64 = { s: ByteArray -> Base64.getUrlEncoder().withoutPadding().encodeToString(s) }
        val signingInput = "${b64(header.toByteArray())}.${b64(payload.toByteArray())}"

        val sig = ByteArray(Sign.BYTES)
        val rc = sodium.crypto_sign_detached(
            sig,
            null,
            signingInput.toByteArray(),
            signingInput.length.toLong(),
            signSecret,
        )
        check(rc == 0) { "crypto_sign_detached rc=$rc" }

        return "$signingInput.${b64(sig)}"
    }
}
