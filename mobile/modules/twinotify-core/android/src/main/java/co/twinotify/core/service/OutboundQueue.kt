package co.twinotify.core.service

import co.twinotify.core.storage.OutboundEvent
import co.twinotify.core.storage.OutboundEventDao

class OutboundQueue(private val dao: OutboundEventDao) {
    companion object {
        const val MAX_SIZE = 1000
        private const val DRAIN_BATCH = 32
    }

    suspend fun enqueue(ciphertextB64: String, nonceB64: String, msgId: String): Long {
        return dao.enqueueCapped(
            OutboundEvent(
                ciphertextB64 = ciphertextB64,
                nonceB64 = nonceB64,
                msgId = msgId,
                createdTs = System.currentTimeMillis(),
            ),
            MAX_SIZE,
        )
    }

    suspend fun drain(): List<OutboundEvent> = dao.drain(DRAIN_BATCH)
    suspend fun ack(id: Long) = dao.ack(id)
    suspend fun count(): Int = dao.count()
}
