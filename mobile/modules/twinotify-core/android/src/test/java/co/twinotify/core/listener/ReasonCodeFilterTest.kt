package co.twinotify.core.listener

import kotlin.test.Test
import kotlin.test.assertEquals

class ReasonCodeFilterTest {
    data class Case(
        val name: String,
        val ownPkg: Boolean,
        val canonInPending: Boolean,
        val reason: Int,
        val expected: FilterResult,
    )

    private val cases = listOf(
        // isOwnPkg + canonInPending = Suppress, regardless of reason.
        Case("ownPkg + pending + reason10 → Suppress", true,  true,  10, FilterResult.Suppress),
        Case("ownPkg + pending + reason2 → Suppress", true,  true,  2,  FilterResult.Suppress),
        // ownPkg + NOT pending = user dismissed our mirror → Emit(user_swipe)
        Case("ownPkg + !pending → Emit(user_swipe)",  true,  false, 2,  FilterResult.Emit("user_swipe")),
        Case("ownPkg + !pending + click → Emit(user_swipe)", true, false, 1, FilterResult.Emit("user_swipe")),
        // NOT ownPkg + pending = Suppress (echo of cancel we triggered)
        Case("!ownPkg + pending → Suppress", false, true, 10, FilterResult.Suppress),
        Case("!ownPkg + pending + click → Suppress", false, true, 1, FilterResult.Suppress),
        // NOT ownPkg + NOT pending — reason matters
        Case("!ownPkg + !pending + reason 1 → user_click", false, false, 1, FilterResult.Emit("user_click")),
        Case("!ownPkg + !pending + reason 2 → user_swipe", false, false, 2, FilterResult.Emit("user_swipe")),
        Case("!ownPkg + !pending + reason 3 → user_swipe", false, false, 3, FilterResult.Emit("user_swipe")),
        Case("!ownPkg + !pending + reason 8 → app_cancel", false, false, 8, FilterResult.Emit("app_cancel")),
        Case("!ownPkg + !pending + reason 9 → app_cancel", false, false, 9, FilterResult.Emit("app_cancel")),
        Case("!ownPkg + !pending + reason 4 → NoEmit", false, false, 4, FilterResult.NoEmit),
        Case("!ownPkg + !pending + reason 6 → NoEmit", false, false, 6, FilterResult.NoEmit),
        Case("!ownPkg + !pending + reason 10 → NoEmit", false, false, 10, FilterResult.NoEmit),
        Case("!ownPkg + !pending + reason 12 → NoEmit", false, false, 12, FilterResult.NoEmit),
        Case("!ownPkg + !pending + reason 13 → NoEmit", false, false, 13, FilterResult.NoEmit),
        Case("!ownPkg + !pending + reason 14 → NoEmit", false, false, 14, FilterResult.NoEmit),
        Case("!ownPkg + !pending + unknown reason 99 → NoEmit", false, false, 99, FilterResult.NoEmit),
    )

    @Test
    fun truthTable() {
        for (c in cases) {
            val got = ReasonCodeFilter.filter(c.ownPkg, c.canonInPending, c.reason)
            assertEquals(c.expected, got, c.name)
        }
    }
}
