package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel

class RelayRawFrameIngressTest {
    @Test
    fun saturationIsReportedInsteadOfDroppingSilently() {
        val channel = Channel<String>(capacity = 1)
        val ingress = RawFrameIngress(channel)
        assertTrue(ingress.offer("first"))
        assertFalse(ingress.offer("second"))
        channel.close()
    }
}
