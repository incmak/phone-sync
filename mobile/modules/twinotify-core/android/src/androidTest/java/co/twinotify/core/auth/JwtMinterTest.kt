package co.twinotify.core.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import co.twinotify.core.crypto.WrappedKeys
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class JwtMinterTest {
    private val ls = LazySodiumAndroid(SodiumAndroid())
    private val sodium = ls.sodium

    @Test
    fun mintedJwtVerifiesWithPublicKey() {
        val kp = WrappedKeys.generateSign()
        val jwt = JwtMinter.mint(deviceId = "devA", signSecret = kp.secretKey)
        val parts = jwt.split(".")
        assertEquals(3, parts.size, "JWT has three parts")

        val signingInput = "${parts[0]}.${parts[1]}"
        val sig = Base64.getUrlDecoder().decode(parts[2])
        assertEquals(Sign.BYTES, sig.size, "signature is correct size")

        val verified = sodium.crypto_sign_verify_detached(
            sig,
            signingInput.toByteArray(),
            signingInput.length.toLong(),
            kp.publicKey,
        )
        assertTrue(verified == 0, "signature must verify with own pubkey (rc=$verified)")
    }
}
