package expo.modules.phonesynccore.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
class EncrypterTest {

    @Test
    fun roundtripAtoB() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        CryptoStore.rotate(ctx)
        val (aBox, _) = CryptoStore.loadOrGenerate(ctx)
        // Simulate B by generating a second box keypair (no persistence needed)
        val bBox = WrappedKeys.generateBox()

        val nonce = NonceSource.next(ctx)
        val plaintext = "hello from A".toByteArray()

        val ct = Encrypter.encrypt(plaintext, nonce, bBox.publicKey, aBox.secretKey)
        val recovered = Encrypter.decrypt(ct, nonce, aBox.publicKey, bBox.secretKey)
        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun wrongKeyFailsDecryption() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        CryptoStore.rotate(ctx)
        val (aBox, _) = CryptoStore.loadOrGenerate(ctx)
        val bBox = WrappedKeys.generateBox()
        val attacker = WrappedKeys.generateBox()

        val nonce = NonceSource.next(ctx)
        val ct = Encrypter.encrypt("secret".toByteArray(), nonce, bBox.publicKey, aBox.secretKey)

        // Attacker uses their own secret key — MAC must not verify
        try {
            Encrypter.decrypt(ct, nonce, aBox.publicKey, attacker.secretKey)
            fail("Expected DecryptError.AuthFailed but no exception was thrown")
        } catch (e: Encrypter.DecryptError.AuthFailed) {
            // Expected — MAC verification correctly rejected the wrong key
        }
    }

    @Test
    fun emptyPlaintextRoundtrip() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val aBox = WrappedKeys.generateBox()
        val bBox = WrappedKeys.generateBox()
        val nonce = NonceSource.next(ctx)

        val ct = Encrypter.encrypt(ByteArray(0), nonce, bBox.publicKey, aBox.secretKey)
        val recovered = Encrypter.decrypt(ct, nonce, aBox.publicKey, bBox.secretKey)
        assertContentEquals(ByteArray(0), recovered)
    }

    @Test
    fun truncatedCiphertextThrowsSizeMismatch() {
        val tooShort = ByteArray(5) // less than Box.MACBYTES (16)
        val nonce = ByteArray(24)
        val pub = ByteArray(32)
        val sec = ByteArray(32)
        try {
            Encrypter.decrypt(tooShort, nonce, pub, sec)
            fail("Expected DecryptError.SizeMismatch")
        } catch (e: Encrypter.DecryptError.SizeMismatch) {
            // Expected
        }
    }

    @Test
    fun nonceMustBe24Bytes() {
        val aBox = WrappedKeys.generateBox()
        val bBox = WrappedKeys.generateBox()
        val badNonce = ByteArray(12)
        try {
            Encrypter.encrypt("x".toByteArray(), badNonce, bBox.publicKey, aBox.secretKey)
            fail("Expected IllegalArgumentException for wrong nonce size")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }
}
