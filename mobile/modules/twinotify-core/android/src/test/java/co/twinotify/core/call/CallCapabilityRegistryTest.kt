package co.twinotify.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CallCapabilityRegistryTest {
    @Test
    fun lookupRequiresTheExactCanonSequenceControlAndKind() {
        val registry = CallCapabilityRegistry<String>()
        registry.install(
            CANON,
            CallCapabilityGeneration(
                sequence = 2,
                sourceKey = "source",
                controls = mapOf(
                    CONTROL to RegisteredCallControl(CallControlKind.ANSWER, "token"),
                ),
            ),
        )

        val found = assertIs<CallCapabilityLookup.Found<String>>(
            registry.lookup(CANON, 2, CONTROL, CallControlKind.ANSWER),
        )
        assertEquals("token", found.handle)
        assertIs<CallCapabilityLookup.MissingGeneration>(
            registry.lookup("call:$OTHER_SESSION", 2, CONTROL, CallControlKind.ANSWER),
        )
        assertIs<CallCapabilityLookup.StaleGeneration>(
            registry.lookup(CANON, 1, CONTROL, CallControlKind.ANSWER),
        )
        assertIs<CallCapabilityLookup.MissingControl>(
            registry.lookup(CANON, 2, OTHER_CONTROL, CallControlKind.ANSWER),
        )
        assertIs<CallCapabilityLookup.MissingControl>(
            registry.lookup(CANON, 2, CONTROL, CallControlKind.DECLINE),
        )
    }

    @Test
    fun installCopiesTheControlMapAndNewGenerationReplacesTheOldOne() {
        val controls = linkedMapOf(
            CONTROL to RegisteredCallControl(CallControlKind.ANSWER, "original"),
        )
        val registry = CallCapabilityRegistry<String>()
        registry.install(CANON, CallCapabilityGeneration(2, "source-2", controls))

        controls.clear()
        controls[OTHER_CONTROL] = RegisteredCallControl(CallControlKind.DECLINE, "mutated")

        val original = assertIs<CallCapabilityLookup.Found<String>>(
            registry.lookup(CANON, 2, CONTROL, CallControlKind.ANSWER),
        )
        assertEquals("original", original.handle)
        assertIs<CallCapabilityLookup.MissingControl>(
            registry.lookup(CANON, 2, OTHER_CONTROL, CallControlKind.DECLINE),
        )

        registry.install(
            CANON,
            CallCapabilityGeneration(
                sequence = 3,
                sourceKey = "source-3",
                controls = mapOf(
                    OTHER_CONTROL to RegisteredCallControl(CallControlKind.HANG_UP, "replacement"),
                ),
            ),
        )
        assertIs<CallCapabilityLookup.StaleGeneration>(
            registry.lookup(CANON, 2, CONTROL, CallControlKind.ANSWER),
        )
        val replacement = assertIs<CallCapabilityLookup.Found<String>>(
            registry.lookup(CANON, 3, OTHER_CONTROL, CallControlKind.HANG_UP),
        )
        assertEquals("replacement", replacement.handle)
    }

    @Test
    fun purgeAndClearRemoveOnlyTheRequestedGenerations() {
        val registry = CallCapabilityRegistry<String>()
        registry.install(CANON, generation(sequence = 2, handle = "first"))
        registry.install("call:$OTHER_SESSION", generation(sequence = 4, handle = "second"))

        registry.purge(CANON)
        assertIs<CallCapabilityLookup.MissingGeneration>(
            registry.lookup(CANON, 2, CONTROL, CallControlKind.ANSWER),
        )
        assertIs<CallCapabilityLookup.Found<String>>(
            registry.lookup("call:$OTHER_SESSION", 4, CONTROL, CallControlKind.ANSWER),
        )

        registry.clear()
        assertIs<CallCapabilityLookup.MissingGeneration>(
            registry.lookup("call:$OTHER_SESSION", 4, CONTROL, CallControlKind.ANSWER),
        )
    }

    private fun generation(sequence: Long, handle: String) = CallCapabilityGeneration(
        sequence = sequence,
        sourceKey = "source-$sequence",
        controls = mapOf(
            CONTROL to RegisteredCallControl(CallControlKind.ANSWER, handle),
        ),
    )

    private companion object {
        const val SESSION = "11111111-1111-4111-8111-111111111111"
        const val OTHER_SESSION = "22222222-2222-4222-8222-222222222222"
        const val CANON = "call:$SESSION"
        const val CONTROL = "2a846785-e576-47d0-8c4b-e4fba30d88bd"
        const val OTHER_CONTROL = "0d47171d-c1ae-463a-bae7-3e8778517c0f"
    }
}
