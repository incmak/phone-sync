package co.twinotify.core.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairingRelayEndpointTest {
    @Test
    fun releasePairingRejectsEveryCleartextRelaySpelling() {
        assertFailsWith<IllegalArgumentException> {
            PairingRelayEndpoint.http("http://relay.example", "pair", "init", debug = false)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingRelayEndpoint.http("ws://relay.example/ws", "pair", "hello", debug = false)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingRelayEndpoint.notify("http://relay.example", "token", "A", debug = false)
        }
    }

    @Test
    fun securePairingBuildsHttpAndWebSocketEndpointsWithoutStringConcatenation() {
        assertEquals(
            "https://relay.example/pair/init",
            PairingRelayEndpoint.http("wss://relay.example/ws", "pair", "init", debug = false).toString(),
        )
        assertEquals(
            "https://relay.example/pair/notify?token=a%26b&role=B",
            PairingRelayEndpoint.notify("https://relay.example", "a&b", "B", debug = false).toString(),
        )
    }

    @Test
    fun debugCleartextRemainsLimitedToLoopback() {
        assertEquals(
            "http://127.0.0.1:8080/pair/complete",
            PairingRelayEndpoint.http("ws://127.0.0.1:8080/ws", "pair", "complete", debug = true).toString(),
        )
        assertFailsWith<IllegalArgumentException> {
            PairingRelayEndpoint.notify("ws://10.0.0.2:8080", "token", "A", debug = true)
        }
    }
}
