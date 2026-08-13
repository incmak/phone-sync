package co.twinotify.core.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.crypto.WrappedKeys
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Sign
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairNotifyClientAuthTest {
    @Test
    fun authenticatedHeadersUseRelayCanonicalProofAndStandardBase64() {
        val sign = WrappedKeys.generateSign()
        val token = "pt-test-token"
        val role = "A"
        val deviceId = "device-a"
        val headers = PairNotifyClient.authenticatedHeaders(token, role, deviceId, sign.secretKey)

        assertEquals(deviceId, headers["X-Twinotify-Device-ID"])
        val encoded = headers["X-Twinotify-Pair-Signature"] ?: error("signature header missing")
        val signature = Base64.getDecoder().decode(encoded)
        assertEquals(Sign.BYTES, signature.size)
        val message = "twinotify-pair-notify-v1\n$token\n$role\n$deviceId".toByteArray()
        val sodium = LazySodiumAndroid(SodiumAndroid()).sodium
        assertEquals(0, sodium.crypto_sign_verify_detached(signature, message, message.size.toLong(), sign.publicKey))
        assertTrue(encoded.matches(Regex("[A-Za-z0-9+/]+=*")), "relay requires standard Base64")
    }
}
