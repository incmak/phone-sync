package co.twinotify.core.metrics

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifiedDeliveryMetricsTest {
    @Test
    fun localDayWindowUsesTheCurrentPhoneTimezoneInsteadOfUtcMidnight() {
        val now = Instant.parse("2026-09-01T00:30:00Z").toEpochMilli()

        assertEquals(
            LocalDayWindow(
                startInclusive = Instant.parse("2026-08-31T18:30:00Z").toEpochMilli(),
                endExclusive = Instant.parse("2026-09-01T18:30:00Z").toEpochMilli(),
                dateKey = "2026-09-01",
            ),
            localDayWindow(now, ZoneId.of("Asia/Kolkata")),
        )
    }

    @Test
    fun localDayWindowHonorsDaylightSavingTransitions() {
        val now = Instant.parse("2026-11-01T16:00:00Z").toEpochMilli()

        assertEquals(
            25L * 60L * 60L * 1_000L,
            localDayWindow(now, ZoneId.of("America/New_York")).let {
                it.endExclusive - it.startInclusive
            },
        )
    }

    @Test
    fun authenticatedLatencyKeepsZeroAndClassifiesUnusableEvidence() {
        assertEquals(DeliveryLatencyEvidence.Measured(0), deliveryLatencyEvidence(1_000, 1_000))
        assertEquals(DeliveryLatencyEvidence.ClockSkew, deliveryLatencyEvidence(1_001, 1_000))
        assertEquals(DeliveryLatencyEvidence.Unavailable, deliveryLatencyEvidence(1_000, null))
        assertEquals(
            DeliveryLatencyEvidence.Implausible,
            deliveryLatencyEvidence(0, MAX_VERIFIED_DELIVERY_LATENCY_MS + 1),
        )
    }
}
