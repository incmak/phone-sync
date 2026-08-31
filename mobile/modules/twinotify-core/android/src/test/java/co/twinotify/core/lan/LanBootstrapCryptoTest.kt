package co.twinotify.core.lan

import co.twinotify.core.storage.LanBinding
import co.twinotify.core.storage.sameTrustMaterial
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanBootstrapCryptoTest {
    private val sharedKey = bytes(0x70)
    private val precomputer = BoxPrecomputer { _, _ -> sharedKey.copyOf() }

    @Test
    fun derivationIsSymmetricAndUsesUnsignedUtf8IdentityOrdering() {
        // Kotlin String ordering puts the emoji surrogate before U+E000, while
        // unsigned UTF-8 byte ordering puts U+E000 (0xee) before emoji (0xf0).
        val first = identity("\uE000", 0x10, 0x20)
        val second = identity("😀", 0x30, 0x40)

        val fromFirst = LanBootstrapCrypto.derive(first, second, bytes(0x50), precomputer)
        val fromSecond = LanBootstrapCrypto.derive(second, first, bytes(0x60), precomputer)
        val expectedContext = context(first, second)

        assertContentEquals(fromFirst.bindingContextSha256, fromSecond.bindingContextSha256)
        assertContentEquals(fromFirst.lanSecret, fromSecond.lanSecret)
        assertContentEquals(sha256(expectedContext), fromFirst.bindingContextSha256)
        assertContentEquals(hkdfEquivalent(sharedKey, expectedContext), fromFirst.lanSecret)
    }

    @Test
    fun contextIsLengthDelimitedWithUnsignedBigEndianLengths() {
        val first = identity("a", 0x01, 0x21)
        val second = identity("bc", 0x41, 0x61)
        val material = LanBootstrapCrypto.derive(first, second, bytes(0x71), precomputer)

        assertContentEquals(sha256(context(first, second)), material.bindingContextSha256)
    }

    @Test
    fun equalDeviceIdsAndWrongKeySizesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            LanBootstrapCrypto.derive(identity("same", 1, 2), identity("same", 3, 4), bytes(5), precomputer)
        }
        for (badSize in listOf(31, 33)) {
            assertFailsWith<IllegalArgumentException> {
                LanBootstrapCrypto.derive(
                    LanBootstrapIdentity("a", ByteArray(badSize), bytes(2)),
                    identity("b", 3, 4),
                    bytes(5),
                    precomputer,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                LanBootstrapCrypto.derive(
                    LanBootstrapIdentity("a", bytes(1), ByteArray(badSize)),
                    identity("b", 3, 4),
                    bytes(5),
                    precomputer,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                LanBootstrapCrypto.derive(identity("a", 1, 2), identity("b", 3, 4), ByteArray(badSize), precomputer)
            }
        }
    }

    @Test
    fun resultAccessorsAreDefensiveAndIdentityChangesRekey() {
        val local = identity("a", 0x10, 0x20)
        val peer = identity("b", 0x30, 0x40)
        val baseline = LanBootstrapCrypto.derive(local, peer, bytes(0x50), precomputer)
        val exposedSecret = baseline.lanSecret
        val exposedDigest = baseline.bindingContextSha256
        exposedSecret.fill(0)
        exposedDigest.fill(0)

        assertFalse(baseline.lanSecret.all { it == 0.toByte() })
        assertFalse(baseline.bindingContextSha256.all { it == 0.toByte() })

        val variants = listOf(
            identity("c", 0x30, 0x40),
            identity("b", 0x31, 0x40),
            identity("b", 0x30, 0x41),
        )
        variants.forEach { changedPeer ->
            val changed = LanBootstrapCrypto.derive(local, changedPeer, bytes(0x50), precomputer)
            assertFalse(baseline.bindingContextSha256.contentEquals(changed.bindingContextSha256))
            assertFalse(baseline.lanSecret.contentEquals(changed.lanSecret))
        }
    }

    @Test
    fun nativeFailureMapsToStableCodeWithoutLeakingDetails() {
        val error = assertFailsWith<LanBootstrapCryptoException> {
            LanBootstrapCrypto.derive(identity("a", 1, 2), identity("b", 3, 4), bytes(5)) { _, _ ->
                error("secret native detail")
            }
        }

        assertEquals("lan_bootstrap_crypto_unavailable", error.message)
        assertFalse(error.stackTraceToString().contains("secret native detail"))
    }

    @Test
    fun trustComparisonIgnoresPairingTimeButRequiresPinSecretAndProtocol() {
        val baseline = LanBinding(bytes(0x10), bytes(0x20), 1, 100)
        assertTrue(baseline.sameTrustMaterial(LanBinding(bytes(0x10), bytes(0x20), 1, 999)))
        assertFalse(baseline.sameTrustMaterial(LanBinding(bytes(0x11), bytes(0x20), 1, 100)))
        assertFalse(baseline.sameTrustMaterial(LanBinding(bytes(0x10), bytes(0x21), 1, 100)))
        assertEquals(1, baseline.protocolVersion)
    }

    private fun identity(deviceId: String, encSeed: Int, signSeed: Int) =
        LanBootstrapIdentity(deviceId, bytes(encSeed), bytes(signSeed))

    private fun context(first: LanBootstrapIdentity, second: LanBootstrapIdentity): ByteArray {
        val ordered = listOf(first, second).sortedWith { left, right ->
            compareUnsigned(left.deviceId.toByteArray(), right.deviceId.toByteArray())
        }
        return ByteArrayOutputStream().use { bytes ->
            bytes.write("twinotify-lan-binding-context-v1\n".toByteArray())
            DataOutputStream(bytes).use { out ->
                ordered.forEach { value ->
                    writeLengthDelimited(out, value.deviceId.toByteArray())
                    writeLengthDelimited(out, value.encryptionPublicKey)
                    writeLengthDelimited(out, value.signingPublicKey)
                }
            }
            bytes.toByteArray()
        }
    }

    private fun writeLengthDelimited(out: DataOutputStream, value: ByteArray) {
        out.writeInt(value.size)
        out.write(value)
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    private fun hkdfEquivalent(shared: ByteArray, context: ByteArray): ByteArray {
        val salt = sha256(context)
        val prk = hmac(salt, shared)
        return hmac(prk, "twinotify-lan-secret-v1\n".toByteArray() + byteArrayOf(1))
    }

    private fun hmac(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private fun bytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }
}
