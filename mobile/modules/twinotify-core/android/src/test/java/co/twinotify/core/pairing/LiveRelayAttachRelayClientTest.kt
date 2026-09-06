package co.twinotify.core.pairing

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The relay's peer.hello frame is the only place an attach turns relay bytes into an identity, and
 * the coordinator's whole safety argument rests on comparing those bytes to the trusted peer. A
 * silently truncated or mis-decoded key here would turn a real mismatch into an accidental match.
 */
class LiveRelayAttachRelayClientTest {
    private val enc = ByteArray(32) { it.toByte() }
    private val sign = ByteArray(32) { (it + 100).toByte() }

    private fun frame(
        deviceId: String = "peer-device",
        encB64: String = Base64.getEncoder().encodeToString(enc),
        signB64: String = Base64.getEncoder().encodeToString(sign),
        extra: String = "",
    ) = """{"type":"peer.hello","device_id":"$deviceId","enc_pubkey":"$encB64","sign_pubkey":"$signB64"$extra}"""

    @Test
    fun parsesTheIdentityFieldsExactly() {
        val hello = LiveRelayAttachRelayClient.parsePeerHello(frame())

        assertEquals("peer-device", hello.deviceId)
        assertContentEquals(enc, hello.encPubkey)
        assertContentEquals(sign, hello.signPubkey)
    }

    @Test
    fun toleratesUnknownFieldsTheRelayMayAdd() {
        val hello = LiveRelayAttachRelayClient.parsePeerHello(
            frame(extra = ""","display_name":"Peer","future_field":1"""),
        )

        assertEquals("peer-device", hello.deviceId)
        assertContentEquals(enc, hello.encPubkey)
    }

    @Test
    fun failsLoudlyOnAMissingOrUndecodableKeyRatherThanYieldingEmptyBytes() {
        val malformed = listOf(
            """{"type":"peer.hello","device_id":"peer-device","sign_pubkey":"AAAA"}""",
            """{"type":"peer.hello","enc_pubkey":"AAAA","sign_pubkey":"AAAA"}""",
            frame(encB64 = "not base64 at all!!"),
        )

        malformed.forEach { raw ->
            assertFailsWith<Exception> { LiveRelayAttachRelayClient.parsePeerHello(raw) }
        }
    }

    @Test
    fun theAttachWaitIsShorterThanThePairingScreenWait() {
        // An attach happens with the peer already connected on a direct route, so a long wait is
        // a failure rather than patience.
        assert(LiveRelayAttachRelayClient.DEFAULT_AWAIT_TIMEOUT_MS < 5 * 60 * 1000L)
    }
}
