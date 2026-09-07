package co.twinotify.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.pairing.Fingerprint
import co.twinotify.core.pairing.PairConfirmation
import co.twinotify.core.pairing.PairNotifyClient
import co.twinotify.core.pairing.PairProtocol
import com.goterl.lazysodium.SodiumAndroid
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Public test keys only. Does not touch application keys, pairing or notification state. */
@RunWith(AndroidJUnit4::class)
class SharedCryptoVectorTest {
    @Test
    fun sharedWireKnownAnswers() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val vector = context.assets.open("known-answer-v1.json").bufferedReader().use { JSONObject(it.readText()) }
        fun bytes(key: String): ByteArray = vector.getString(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nonce = NonceSource.encode(bytes("nonce_prefix"), vector.getString("nonce_counter").toLong())
        assertContentEquals(bytes("nonce"), nonce)
        assertContentEquals(bytes("ciphertext"), Encrypter.encrypt(bytes("plaintext"), nonce, bytes("b_box_public"), bytes("a_box_secret")))
        assertContentEquals(bytes("plaintext"), Encrypter.decrypt(bytes("ciphertext"), nonce, bytes("a_box_public"), bytes("b_box_secret")))
        assertEquals(vector.getString("fingerprint"), Fingerprint.of(bytes("a_box_public"), bytes("a_sign_public")))
        val sodium = SodiumAndroid()
        fun sign(message: ByteArray, secret: ByteArray): ByteArray {
            val signature = ByteArray(64)
            assertEquals(0, sodium.crypto_sign_detached(signature, null, message, message.size.toLong(), secret))
            return signature
        }
        assertContentEquals(bytes("message_signature"), sign(bytes("plaintext"), bytes("a_sign_secret")))
        val token = vector.getString("token")
        val sigA = PairProtocol.deviceASignConfirmation(token, bytes("a_box_public"), bytes("a_sign_public"),
            bytes("b_box_public"), bytes("b_sign_public"), bytes("a_sign_secret"))
        assertContentEquals(bytes("initiator_signature"), sigA)
        val transcriptB = PairConfirmation.responderMessage(token, bytes("a_box_public"), bytes("a_sign_public"),
            bytes("b_box_public"), bytes("b_sign_public"), sigA)
        assertContentEquals(bytes("responder_message"), transcriptB)
        assertContentEquals(bytes("responder_signature"), sign(transcriptB, bytes("b_sign_secret")))
        val headers = PairNotifyClient.authenticatedHeaders(token, "B", "dev-b", bytes("b_sign_secret"))
        assertEquals(Base64.getEncoder().encodeToString(bytes("notify_signature")), headers["X-Twinotify-Pair-Signature"])
        val tampered = bytes("ciphertext").also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFails { Encrypter.decrypt(tampered, nonce, bytes("a_box_public"), bytes("b_box_secret")) }
    }
}
