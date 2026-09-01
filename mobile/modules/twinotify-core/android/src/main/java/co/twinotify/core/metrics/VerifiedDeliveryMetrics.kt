package co.twinotify.core.metrics

import java.lang.Math.subtractExact
import java.time.Instant
import java.time.ZoneId

data class LocalDayWindow(
    val startInclusive: Long,
    val endExclusive: Long,
    val dateKey: String,
)

sealed interface DeliveryLatencyEvidence {
    data class Measured(val milliseconds: Long) : DeliveryLatencyEvidence
    data object ClockSkew : DeliveryLatencyEvidence
    data object Implausible : DeliveryLatencyEvidence
    data object Unavailable : DeliveryLatencyEvidence
}

fun localDayWindow(
    nowMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): LocalDayWindow {
    val date = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
    return LocalDayWindow(
        startInclusive = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endExclusive = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        dateKey = date.toString(),
    )
}

fun deliveryLatencyEvidence(
    senderCreatedAt: Long,
    peerReceiptCreatedAt: Long?,
): DeliveryLatencyEvidence {
    if (peerReceiptCreatedAt == null) return DeliveryLatencyEvidence.Unavailable
    val latency = runCatching { subtractExact(peerReceiptCreatedAt, senderCreatedAt) }
        .getOrElse { return DeliveryLatencyEvidence.Implausible }
    return when {
        latency < 0 -> DeliveryLatencyEvidence.ClockSkew
        latency > MAX_VERIFIED_DELIVERY_LATENCY_MS -> DeliveryLatencyEvidence.Implausible
        else -> DeliveryLatencyEvidence.Measured(latency)
    }
}

const val MAX_VERIFIED_DELIVERY_LATENCY_MS = 24L * 60L * 60L * 1_000L + 5L * 60L * 1_000L
