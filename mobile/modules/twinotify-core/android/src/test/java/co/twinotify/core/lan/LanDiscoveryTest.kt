package co.twinotify.core.lan

import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LanDiscoveryTest {
    @Test
    fun txtRecordIsClosedWorldAndPrivacySafe() {
        val advertisement = "g08A2xG_6-WMx9D8X9P2zQ"
        val txt = LanDiscoveryContract.txt(advertisement, LanCapabilities.DIRECT_V1)

        assertEquals(setOf("v", "ad", "caps"), txt.keys)
        assertEquals("1", txt.getValue("v").decodeToString())
        assertEquals(advertisement, txt.getValue("ad").decodeToString())
        assertEquals("1", txt.getValue("caps").decodeToString())
        assertTrue(LanDiscoveryContract.matches(txt, setOf(advertisement)))
        assertFalse(LanDiscoveryContract.matches(txt + ("device_id" to "secret".encodeToByteArray()), setOf(advertisement)))
        assertFalse(LanDiscoveryContract.matches(txt + ("name" to "Phone".encodeToByteArray()), setOf(advertisement)))
        assertFalse(LanDiscoveryContract.matches(txt + ("caps" to "+1".encodeToByteArray()), setOf(advertisement)))
        assertFalse(LanDiscoveryContract.matches(txt + ("caps" to "01".encodeToByteArray()), setOf(advertisement)))
    }

    @Test
    fun candidatesRetainOriginatingNetworkAndDoNotExposeItInText() {
        val network = FakeLanNetwork()
        val candidate = LanCandidate(InetAddress.getLoopbackAddress(), 4455, network)

        assertSame(network, candidate.network)
        assertSame(network.socket, candidate.network.openSocket())
        assertFalse(candidate.toString().contains(candidate.address.hostAddress.orEmpty()))
    }

    private class FakeLanNetwork : LanNetwork {
        val socket = Socket()
        override fun openSocket(): Socket = socket
    }
}
