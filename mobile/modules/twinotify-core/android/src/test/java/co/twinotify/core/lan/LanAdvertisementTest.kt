package co.twinotify.core.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanAdvertisementTest {
    @Test
    fun derivationBindsSecretProtocolDeviceAndSignedEpochDay() {
        val secret = ByteArray(32) { it.toByte() }
        val base = LanAdvertisement.derive(secret, DEVICE_A, 20_000)

        assertEquals(base, LanAdvertisement.derive(secret, DEVICE_A, 20_000))
        assertNotEquals(base, LanAdvertisement.derive(secret, DEVICE_B, 20_000))
        assertNotEquals(base, LanAdvertisement.derive(secret, DEVICE_A, 20_001))
        assertNotEquals(base, LanAdvertisement.derive(ByteArray(32) { (it + 1).toByte() }, DEVICE_A, 20_000))
        assertNotEquals(base, LanAdvertisement.deriveForTest(secret, "other-domain", DEVICE_A, 20_000))
        assertFalse(base.contains(DEVICE_A))
    }

    @Test
    fun expectedPeerMatchesOnlyCurrentAndAdjacentUtcDays() {
        val secret = ByteArray(32) { 7 }
        val matcher = LanAdvertisementMatcher(secret, DEVICE_B)

        assertTrue(matcher.matches(LanAdvertisement.derive(secret, DEVICE_B, 19_999), 20_000))
        assertTrue(matcher.matches(LanAdvertisement.derive(secret, DEVICE_B, 20_000), 20_000))
        assertTrue(matcher.matches(LanAdvertisement.derive(secret, DEVICE_B, 20_001), 20_000))
        assertFalse(matcher.matches(LanAdvertisement.derive(secret, DEVICE_B, 19_998), 20_000))
        assertFalse(matcher.matches(LanAdvertisement.derive(secret, DEVICE_B, 20_002), 20_000))
        assertFalse(matcher.matches(LanAdvertisement.derive(secret, DEVICE_A, 20_000), 20_000))
    }

    @Test
    fun validPeerOutsideAcceptanceWindowIsTypedAsClockSkewButNeverMatched() {
        val secret = ByteArray(32) { 7 }
        val matcher = LanAdvertisementMatcher(secret, DEVICE_B)
        val skewed = LanAdvertisement.derive(secret, DEVICE_B, 20_002)

        assertEquals(LanAdvertisementMatch.CLOCK_SKEW, matcher.classify(skewed, 20_000))
        assertFalse(matcher.matches(skewed, 20_000))
        assertEquals(LanAdvertisementMatch.UNRECOGNIZED, matcher.classify("g08A2xG_6-WMx9D8X9P2zQ", 20_000))
        val expectations = matcher.expectations(20_000)
        assertTrue(skewed in expectations.clockSkewIds)
        assertFalse(skewed in expectations.acceptedIds)
    }

    private companion object {
        const val DEVICE_A = "dev-9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af"
        const val DEVICE_B = "dev-a70446b3-a355-46cc-9e62-069a0bfe2e10"
    }
}
