package co.twinotify.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.pairing.UnpairWipeOrder
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CryptoStoreTest {
    @Test
    fun roundtripKeys() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        CryptoStore.rotate(ctx)
        val (box1, sign1) = CryptoStore.loadOrGenerate(ctx)
        val (box2, sign2) = CryptoStore.loadOrGenerate(ctx)
        assertTrue(box1.publicKey.contentEquals(box2.publicKey), "box public key stable")
        assertTrue(box1.secretKey.contentEquals(box2.secretKey), "box secret key stable")
        assertTrue(sign1.publicKey.contentEquals(sign2.publicKey), "sign public key stable")
        assertTrue(sign1.secretKey.contentEquals(sign2.secretKey), "sign secret key stable")
    }

    @Test
    fun rotateChangesKeys() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        CryptoStore.rotate(ctx)
        val (firstBox, _) = CryptoStore.loadOrGenerate(ctx)
        CryptoStore.rotate(ctx)
        val (secondBox, _) = CryptoStore.loadOrGenerate(ctx)
        assertTrue(!firstBox.publicKey.contentEquals(secondBox.publicKey), "rotation produces new keys")
    }

    @Test
    fun lanBindingIsClearedBeforeApplicationKeyRotation() = runBlocking {
        val steps = mutableListOf<String>()

        UnpairWipeOrder(
            clearLanBinding = { steps += "lan-binding" },
            deleteLanIdentity = { steps += "lan-identity" },
        ).beforeApplicationKeyRotation { steps += "application-keys" }

        assertTrue(steps.indexOf("lan-binding") < steps.indexOf("application-keys"))
    }
}
