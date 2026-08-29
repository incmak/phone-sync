package co.twinotify.core.actions

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ActionDescriptorFactoryTest {
    @Test
    fun prepareBindsOpaqueDescriptorsAndHandlesToOneSequence() {
        val ids = ArrayDeque(listOf("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"))
        val prepared = ActionDescriptorFactory { UUID.fromString(ids.removeFirst()) }.prepare(
            sourceKey = "source",
            packageName = "pkg",
            sequence = 7,
            candidates = listOf(
                ActionCandidate("Reply", 1, true, "Message", "reply-handle"),
                ActionCandidate("Archive", 5, false, null, "archive-handle"),
            ),
        )

        assertEquals(listOf("Reply", "Archive"), prepared.descriptors.map { it.title })
        assertEquals(7, prepared.generation.sequence)
        assertEquals(
            mapOf(
                "11111111-1111-4111-8111-111111111111" to "reply-handle",
                "22222222-2222-4222-8222-222222222222" to "archive-handle",
            ),
            prepared.generation.handlesByActionId,
        )
    }

    @Test
    fun eachPreparationMintsFreshIdsForACasRetry() {
        var next = 0
        val factory = ActionDescriptorFactory { UUID(0, (++next).toLong()) }
        val candidate = listOf(ActionCandidate("Open", 0, false, null, "handle"))

        val losing = factory.prepare("source", "pkg", 4, candidate)
        val winning = factory.prepare("source", "pkg", 5, candidate)

        assertNotEquals(losing.descriptors.single().action_id, winning.descriptors.single().action_id)
        assertTrue(losing.generation.handlesByActionId.keys.intersect(winning.generation.handlesByActionId.keys).isEmpty())
    }
}
