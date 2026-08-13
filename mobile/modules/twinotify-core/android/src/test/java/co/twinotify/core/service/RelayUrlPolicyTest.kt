package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayUrlPolicyTest {
    @Test
    fun releaseRequiresTlsAndDerivesSeparateWsEndpoint() {
        val endpoints = RelayUrlPolicy.parse("https://relay.example/ws", debug = false)
        assertEquals("https", endpoints.http.scheme)
        assertEquals("wss", endpoints.webSocket.scheme)
        assertEquals("/", endpoints.http.encodedPath)
        assertEquals("/ws", endpoints.webSocket.encodedPath)
        assertFailsWith<IllegalArgumentException> { RelayUrlPolicy.parse("http://relay.example", debug = false) }
        assertFailsWith<IllegalArgumentException> { RelayUrlPolicy.parse("ws://relay.example/ws", debug = false) }
    }

    @Test
    fun debugAllowsOnlyLoopbackCleartext() {
        assertEquals("http", RelayUrlPolicy.parse("http://127.0.0.1:8080", debug = true).http.scheme)
        assertEquals("ws", RelayUrlPolicy.parse("http://localhost:8080", debug = true).webSocket.scheme)
        assertFailsWith<IllegalArgumentException> { RelayUrlPolicy.parse("http://10.0.0.2:8080", debug = true) }
    }

    @Test
    fun explicitWssNeverDowngradesAndKeepsPathAndQuery() {
        val endpoints = RelayUrlPolicy.parse("wss://relay.example:8443/custom/ws?token=x", debug = false)
        assertEquals("wss", endpoints.webSocket.scheme)
        assertEquals("https", endpoints.webSocket.asHttpUrl().scheme)
        assertEquals("relay.example", endpoints.webSocket.asHttpUrl().host)
        assertEquals(8443, endpoints.webSocket.asHttpUrl().port)
        assertEquals("/custom/ws", endpoints.webSocket.encodedPath)
        assertEquals("token=x", endpoints.webSocket.encodedQuery)
        assertEquals("wss://relay.example:8443/custom/ws?token=x", endpoints.webSocket.toString())
    }
}
