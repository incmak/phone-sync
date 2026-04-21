package co.twinotify.core.listener

import kotlin.test.Test
import kotlin.test.assertEquals

class CanonIdBuilderTest {
    @Test fun withTag() = assertEquals("devA:com.whatsapp:42:convo-1",
        CanonIdBuilder.build("devA", "com.whatsapp", 42, "convo-1"))
    @Test fun nullTag() = assertEquals("devA:com.whatsapp:42:",
        CanonIdBuilder.build("devA", "com.whatsapp", 42, null))
    @Test fun emptyTag() = assertEquals("devA:com.whatsapp:42:",
        CanonIdBuilder.build("devA", "com.whatsapp", 42, ""))
    @Test fun zeroId() = assertEquals("devB:pkg:0:t",
        CanonIdBuilder.build("devB", "pkg", 0, "t"))
}
