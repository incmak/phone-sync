package co.twinotify.core.lan

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LanAdvertisement {
    private const val DOMAIN = "twinotify:lan-advertisement:v1"
    private const val SECRET_BYTES = 32
    private const val OUTPUT_BYTES = 16

    fun derive(secret: ByteArray, advertiserDeviceId: String, utcEpochDay: Long): String =
        deriveForTest(secret, DOMAIN, advertiserDeviceId, utcEpochDay)

    internal fun deriveForTest(
        secret: ByteArray,
        domain: String,
        advertiserDeviceId: String,
        utcEpochDay: Long,
    ): String {
        require(secret.size == SECRET_BYTES) { "invalid LAN advertisement secret" }
        require(advertiserDeviceId.isNotEmpty() && advertiserDeviceId.encodeToByteArray().size <= 256) {
            "invalid LAN advertiser device ID"
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.copyOf(), "HmacSHA256"))
        mac.update(lengthDelimited(domain.encodeToByteArray()))
        mac.update(lengthDelimited(advertiserDeviceId.encodeToByteArray()))
        mac.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(utcEpochDay).array())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal().copyOf(OUTPUT_BYTES))
    }

    private fun lengthDelimited(value: ByteArray): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + value.size).putInt(value.size).put(value).array()
}

enum class LanAdvertisementMatch {
    MATCHED,
    CLOCK_SKEW,
    UNRECOGNIZED,
}

data class LanAdvertisementExpectations(
    val acceptedIds: Set<String>,
    val clockSkewIds: Set<String>,
)

class LanAdvertisementMatcher(secret: ByteArray, private val peerDeviceId: String) {
    private val storedSecret = secret.copyOf()

    fun matches(advertisementId: String, localUtcEpochDay: Long): Boolean =
        classify(advertisementId, localUtcEpochDay) == LanAdvertisementMatch.MATCHED

    fun classify(advertisementId: String, localUtcEpochDay: Long): LanAdvertisementMatch {
        val expectations = expectations(localUtcEpochDay)
        return when {
            expectations.acceptedIds.constantTimeContains(advertisementId) -> LanAdvertisementMatch.MATCHED
            expectations.clockSkewIds.constantTimeContains(advertisementId) -> LanAdvertisementMatch.CLOCK_SKEW
            else -> LanAdvertisementMatch.UNRECOGNIZED
        }
    }

    /** Skew IDs are diagnostic only and are never eligible discovery candidates. */
    fun expectations(localUtcEpochDay: Long): LanAdvertisementExpectations {
        val accepted = (-1L..1L).mapTo(linkedSetOf()) { offset -> derive(localUtcEpochDay + offset) }
        val skew = (-MAX_DIAGNOSTIC_SKEW_DAYS..MAX_DIAGNOSTIC_SKEW_DAYS)
            .filterNot { it in -1L..1L }
            .mapTo(linkedSetOf()) { offset -> derive(localUtcEpochDay + offset) }
        return LanAdvertisementExpectations(accepted.toSet(), skew.toSet())
    }

    private fun derive(day: Long) = LanAdvertisement.derive(storedSecret, peerDeviceId, day)

    private fun Set<String>.constantTimeContains(candidate: String): Boolean {
        var matched = false
        forEach { expected ->
            matched = MessageDigest.isEqual(expected.encodeToByteArray(), candidate.encodeToByteArray()) || matched
        }
        return matched
    }

    private companion object {
        const val MAX_DIAGNOSTIC_SKEW_DAYS = 7L
    }
}
