package co.twinotify.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RelayFrameCodecTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val envelope = """{"v":2,"type":"enc","msg_id":"$id","origin_device":"dev-a","created_at":1000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}"""
    private val legacyEnvelope = """{"v":1,"type":"enc","msg_id":"$id","origin_device":"dev-a","ts":1000,"nonce":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","ciphertext":"Y2lwaGVydGV4dA=="}"""

    @Test
    fun roundTripsEveryControlFrame() {
        val frames = listOf<RelayFrame>(
            RelayFrame.Hello(listOf(2, 1), "0.8.0"),
            RelayFrame.Put(envelope),
            RelayFrame.Ack(id, digest),
            RelayFrame.Accepted(id, 1000),
            RelayFrame.LegacyForwarded(id),
            RelayFrame.Deliver(1000, envelope),
            RelayFrame.Rejected(id, "mailbox_full"),
            RelayFrame.Rejected(id, "server_capacity"),
            RelayFrame.Expired(id, 2000),
            RelayFrame.Capabilities(listOf(2, 1), listOf(2), 2),
        )
        frames.forEach { frame ->
            val decoded = RelayFrameCodec.decode(RelayFrameCodec.encode(frame))
            when {
                frame is RelayFrame.Put && decoded is RelayFrame.Put ->
                    assertEquals(
                        frame.envelope.let { co.twinotify.core.protocol.ProtocolJson.decodeEnvelope(it) },
                        decoded.envelope.let { co.twinotify.core.protocol.ProtocolJson.decodeEnvelope(it) },
                    )
                frame is RelayFrame.Deliver && decoded is RelayFrame.Deliver -> {
                    assertEquals(frame.acceptedAt, decoded.acceptedAt)
                    assertEquals(
                        frame.envelope.let { co.twinotify.core.protocol.ProtocolJson.decodeEnvelope(it) },
                        decoded.envelope.let { co.twinotify.core.protocol.ProtocolJson.decodeEnvelope(it) },
                    )
                }
                else -> assertEquals(frame, decoded, "frame=$frame decoded=$decoded raw=${RelayFrameCodec.encode(frame)}")
            }
        }
    }

    @Test
    fun rejectsUnknownFieldsWrongVersionAndBadDigest() {
        assertFailsWith<IllegalArgumentException> {
            RelayFrameCodec.decode("""{"v":2,"type":"relay.hello","protocols":[2],"app_version":"x","extra":true}""")
        }
        assertFailsWith<IllegalArgumentException> {
            RelayFrameCodec.decode("""{"v":1,"type":"relay.hello","protocols":[2],"app_version":"x"}""")
        }
        assertFailsWith<IllegalArgumentException> { RelayFrameCodec.encode(RelayFrame.Ack(id, "bad")) }
        assertFailsWith<IllegalArgumentException> { RelayFrameCodec.decode("""{"v":2,"type":"relay.rejected","msg_id":"$id","reason":"other"}""") }
    }

    @Test
    fun relayPutDoesNotAcceptPlaintextEnvelope() {
        assertFailsWith<IllegalArgumentException> {
            RelayFrameCodec.decode("""{"v":2,"type":"relay.put","envelope":{"v":2,"type":"plain"}}""")
        }
        assertIs<RelayFrame.Put>(RelayFrameCodec.decode(RelayFrameCodec.encode(RelayFrame.Put(envelope))))
    }

    @Test
    fun relayPutWrapsExplicitLegacyV1Envelope() {
        val decoded = RelayFrameCodec.decode(RelayFrameCodec.encode(RelayFrame.Put(legacyEnvelope))) as RelayFrame.Put
        assertEquals(1, org.json.JSONObject(decoded.envelope).getInt("v"))
        assertEquals(id, org.json.JSONObject(decoded.envelope).getString("msg_id"))
    }
}
