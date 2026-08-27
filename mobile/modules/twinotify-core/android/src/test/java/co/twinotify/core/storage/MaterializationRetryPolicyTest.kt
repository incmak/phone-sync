package co.twinotify.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class MaterializationRetryPolicyTest {
    @Test
    fun retryDelayAndDueSaturateAtTheBoundedMaximum() {
        assertEquals(5_000L, boundedMaterializationRetryDelay(1))
        assertEquals(300_000L, boundedMaterializationRetryDelay(Int.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, saturatingMaterializationRetryDue(Long.MAX_VALUE - 1L, 5_000L))
    }
}
