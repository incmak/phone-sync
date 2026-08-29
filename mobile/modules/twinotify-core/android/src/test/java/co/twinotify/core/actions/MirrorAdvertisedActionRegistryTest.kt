package co.twinotify.core.actions

import co.twinotify.core.listener.NotifActionJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorAdvertisedActionRegistryTest {
    @Test
    fun updateKeepsPriorAdvertisedGenerationUntilInvocationTtl() {
        var now = 1_000L
        val registry = MirrorAdvertisedActionRegistry(clock = { now })
        val first = action("11111111-1111-4111-8111-111111111111")
        val second = action("22222222-2222-4222-8222-222222222222")

        registry.install("canon", 7, "mirror-tag", 42, listOf(first))
        now += 1_000L
        registry.install("canon", 8, "mirror-tag", 42, listOf(second))

        assertEquals(7L, registry.lookup(identity(first.action_id))?.notificationSequence)
        assertEquals(8L, registry.lookup(identity(second.action_id))?.notificationSequence)

        now = 121_001L
        assertNull(registry.lookup(identity(first.action_id)))
        assertEquals(8L, registry.lookup(identity(second.action_id))?.notificationSequence)
    }

    @Test
    fun cancelPurgesEveryGenerationForTheMirrorIdentity() {
        val registry = MirrorAdvertisedActionRegistry(clock = { 1_000L })
        val first = action("11111111-1111-4111-8111-111111111111")
        val second = action("22222222-2222-4222-8222-222222222222")
        registry.install("canon", 7, "mirror-tag", 42, listOf(first))
        registry.install("canon", 8, "mirror-tag", 42, listOf(second))

        registry.purge("mirror-tag", 42)

        assertNull(registry.lookup(identity(first.action_id)))
        assertNull(registry.lookup(identity(second.action_id)))
    }

    @Test
    fun registryIsBoundedAndEvictsTheOldestAdvertisedCapabilities() {
        val registry = MirrorAdvertisedActionRegistry(maxEntries = 2, clock = { 1_000L })
        val first = action("11111111-1111-4111-8111-111111111111")
        val second = action("22222222-2222-4222-8222-222222222222")
        val third = action("33333333-3333-4333-8333-333333333333")

        registry.install("canon", 1, "mirror-tag", 42, listOf(first, second, third))

        assertNull(registry.lookup(identity(first.action_id)))
        assertTrue(registry.lookup(identity(second.action_id)) != null)
        assertTrue(registry.lookup(identity(third.action_id)) != null)
    }

    private fun identity(actionId: String) = ActionInvokeIdentity("mirror-tag", 42, actionId)

    private fun action(id: String) = NotifActionJson(
        action_id = id,
        title = "Mark read",
        semantic = 2,
        reply = false,
        reply_label = null,
    )
}
