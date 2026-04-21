package co.twinotify.core.service

import co.twinotify.core.storage.OutboundEvent
import co.twinotify.core.storage.OutboundEventDao
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OutboundQueueTest {
    class FakeDao : OutboundEventDao() {
        private val list = mutableListOf<OutboundEvent>()
        private var nextId = 1L
        override suspend fun insertRaw(event: OutboundEvent): Long {
            val e = event.copy(id = nextId++)
            list += e
            return e.id
        }
        override suspend fun drain(limit: Int): List<OutboundEvent> = list.take(limit)
        override suspend fun ack(id: Long) { list.removeAll { it.id == id } }
        override suspend fun count(): Int = list.size
        override suspend fun dropOldest(n: Int) {
            repeat(n.coerceAtMost(list.size)) { list.removeAt(0) }
        }
        // Replicate the @Transaction logic without Room's transaction machinery.
        override suspend fun enqueueCapped(event: OutboundEvent, maxSize: Int): Long {
            val size = count()
            if (size >= maxSize) dropOldest(1 + (size - maxSize))
            return insertRaw(event)
        }
    }

    @Test fun enqueue_assignsIdAndIncrementsCount() = runBlocking {
        val q = OutboundQueue(FakeDao())
        q.enqueue("ct1", "n1", "m1")
        q.enqueue("ct2", "n2", "m2")
        assertEquals(2, q.count())
    }

    @Test fun enqueue_over1000_dropsOldest() = runBlocking {
        val dao = FakeDao()
        val q = OutboundQueue(dao)
        repeat(OutboundQueue.MAX_SIZE) { i -> q.enqueue("ct$i", "n$i", "m$i") }
        q.enqueue("ct-last", "n-last", "m-last")
        assertEquals(OutboundQueue.MAX_SIZE, q.count())
        val drained = q.drain()
        // Oldest dropped was ct0 — drain should no longer contain it
        assertFalse(drained.any { it.ciphertextB64 == "ct0" })
    }

    @Test fun ack_removes() = runBlocking {
        val q = OutboundQueue(FakeDao())
        val id = q.enqueue("ct", "n", "m")
        q.ack(id)
        assertEquals(0, q.count())
    }

    @Test fun drain_respects_batch_limit() = runBlocking {
        val dao = FakeDao()
        val q = OutboundQueue(dao)
        repeat(50) { i -> q.enqueue("ct$i", "n$i", "m$i") }
        // DRAIN_BATCH is 32 (private), but drain() should return ≤32 items
        val batch = q.drain()
        assertEquals(32, batch.size)
    }
}
