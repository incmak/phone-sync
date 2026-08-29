package co.twinotify.core.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ActionRegistryTest {
    @Test
    fun installAtomicallyReplacesTheWholeGenerationAndRejectsStaleLookups() {
        val registry = ActionRegistry<String>()
        registry.install("canon", ActionGeneration(4, "source-4", "pkg", mapOf("old" to "old-handle")))
        registry.install("canon", ActionGeneration(5, "source-5", "pkg", mapOf("new" to "new-handle")))

        assertIs<ActionLookup.StaleGeneration>(registry.lookup("canon", 4, "old"))
        val found = assertIs<ActionLookup.Found<String>>(registry.lookup("canon", 5, "new"))
        assertEquals("new-handle", found.handle)
        assertEquals("source-5", found.generation.sourceKey)
    }

    @Test
    fun purgeAndClearFailClosed() {
        val registry = ActionRegistry<String>()
        registry.install("one", ActionGeneration(1, "source", "pkg", mapOf("a" to "handle")))
        registry.purge("one")
        assertIs<ActionLookup.MissingGeneration>(registry.lookup("one", 1, "a"))

        registry.install("one", ActionGeneration(2, "source", "pkg", mapOf("b" to "handle")))
        registry.install("two", ActionGeneration(1, "source", "pkg", mapOf("c" to "handle")))
        registry.clear()
        assertIs<ActionLookup.MissingGeneration>(registry.lookup("one", 2, "b"))
        assertIs<ActionLookup.MissingGeneration>(registry.lookup("two", 1, "c"))
    }
}
