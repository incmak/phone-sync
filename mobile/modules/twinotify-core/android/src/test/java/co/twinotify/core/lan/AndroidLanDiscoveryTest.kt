package co.twinotify.core.lan

import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLanDiscoveryTest {
    @Test
    fun matchingCandidateIsNetworkBoundAndNetworkLossInvalidatesItOnce() = runTest {
        val platform = FakePlatform()
        val discovery = AndroidLanDiscovery(platform, setOf("expected"))
        val first = async { discovery.candidates().first() }

        platform.emit("other")
        assertFalse(first.isCompleted)
        platform.emit("expected")
        val candidate = first.await()
        assertEquals(4455, candidate.port)
        assertEquals(platform.socket, candidate.network.openSocket())

        platform.loseNetwork()
        platform.loseNetwork()
        assertEquals(1, platform.networkLossEvents)
        discovery.close()
    }

    @Test
    fun closeAndFailureBalanceRegistrationDiscoveryResolutionAndMulticastExactlyOnce() = runTest {
        val platform = FakePlatform()
        val discovery = AndroidLanDiscovery(platform, setOf("expected"))
        platform.fail()

        discovery.close()
        discovery.close()

        assertEquals(1, platform.starts)
        assertEquals(1, platform.stops)
        assertEquals(1, platform.multicastAcquires)
        assertEquals(1, platform.multicastReleases)
        assertEquals(1, platform.registrationStops)
        assertEquals(1, platform.discoveryStops)
        assertEquals(1, platform.resolutionStops)
    }

    @Test
    fun localWifiRequestDoesNotRequireInternetOrValidation() {
        val policy = AndroidLanDiscovery.networkPolicy()

        assertTrue(policy.wifi)
        assertFalse(policy.requiresInternet)
        assertFalse(policy.requiresValidated)
    }

    @Test
    fun clockSkewIsExposedAsStableTypedFailure() = runTest {
        val platform = FakePlatform()
        val discovery = AndroidLanDiscovery(platform, setOf("expected"))
        val failure = async { discovery.failures().first() }

        platform.clockSkew()

        assertEquals(LanDiscoveryFailure.CLOCK_SKEW, failure.await())
        assertEquals("lan_clock_skew", failure.await().code)
        assertEquals(1, platform.stops)
    }

    private class FakePlatform : LanDiscoveryPlatform {
        var starts = 0
        var stops = 0
        var multicastAcquires = 0
        var multicastReleases = 0
        var registrationStops = 0
        var discoveryStops = 0
        var resolutionStops = 0
        var networkLossEvents = 0
        val socket = Socket()
        private var callback: LanDiscoveryPlatform.Callback? = null
        private var lost = false

        override fun start(callback: LanDiscoveryPlatform.Callback) {
            starts++
            multicastAcquires++
            this.callback = callback
        }

        override fun stop() {
            stops++
            multicastReleases++
            registrationStops++
            discoveryStops++
            resolutionStops++
        }

        fun emit(advertisementId: String) {
            callback?.onCandidate(
                LanDiscoveredService(
                    advertisementId,
                    InetAddress.getLoopbackAddress(),
                    4455,
                    object : LanNetwork { override fun openSocket() = socket },
                ),
            )
        }

        fun loseNetwork() {
            if (lost) return
            lost = true
            networkLossEvents++
            callback?.onNetworkLost()
        }

        fun fail() = callback?.onFailure(LanDiscoveryFailure.UNAVAILABLE)
        fun clockSkew() = callback?.onFailure(LanDiscoveryFailure.CLOCK_SKEW)
    }
}
