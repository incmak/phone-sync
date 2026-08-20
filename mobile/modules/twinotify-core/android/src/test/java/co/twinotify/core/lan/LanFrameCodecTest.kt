package co.twinotify.core.lan

import java.nio.ByteBuffer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanFrameCodecTest {
    @Test
    fun roundTripsEveryClosedWorldFrameAndCopiesBytes() {
        val control = ByteArray(32) { it.toByte() }
        val envelope = "{\"v\":2,\"type\":\"enc\"}".encodeToByteArray()
        val frames = listOf(
            LanFrame.Hello(control),
            LanFrame.HelloAck(control),
            LanFrame.Put(envelope),
            LanFrame.Accepted(MSG_ID, DIGEST),
            LanFrame.Ping(7),
            LanFrame.Pong(7),
            LanFrame.Close("normal"),
        )

        frames.forEach { frame ->
            val decoded = LanFrameCodec.decode(LanFrameCodec.encode(frame))
            assertEquals(frame, decoded)
        }
        val value = LanFrame.Put(envelope)
        envelope[0] = 0
        val extracted = value.envelope
        extracted[1] = 0
        assertContentEquals("{\"v\":2,\"type\":\"enc\"}".encodeToByteArray(), value.envelope)
        assertFalse(value.toString().contains("ciphertext"))
    }

    @Test
    fun rejectsUnknownDuplicateOrMissingFieldsAndVersions() {
        rejectJson("{\"v\":2,\"type\":\"lan.ping\",\"token\":1}", LanFrameFailure.UNSUPPORTED_VERSION)
        rejectJson("{\"v\":1,\"type\":\"lan.other\"}", LanFrameFailure.UNSUPPORTED_TYPE)
        rejectJson("{\"v\":1,\"type\":\"lan.ping\",\"token\":1,\"extra\":true}", LanFrameFailure.INVALID_FIELDS)
        rejectJson("{\"v\":1,\"type\":\"lan.ping\",\"token\":1,\"token\":2}", LanFrameFailure.DUPLICATE_KEY)
        rejectJson("{\"v\":1,\"type\":\"lan.accepted\",\"msg_id\":\"$MSG_ID\"}", LanFrameFailure.INVALID_FIELDS)
    }

    @Test
    fun rejectsMalformedLengthsUtf8AndTrailingBytes() {
        assertFailure(byteArrayOf(0, 0, 0, 0), LanFrameFailure.INVALID_LENGTH)
        assertFailure(ByteBuffer.allocate(4).putInt(LanFrameLimits.MAX_FRAME_BYTES + 1).array(), LanFrameFailure.FRAME_TOO_LARGE)
        assertFailure(byteArrayOf(0, 0, 0, 2, '{'.code.toByte()), LanFrameFailure.TRUNCATED)
        assertFailure(byteArrayOf(0, 0, 0, 2, 0xc3.toByte(), 0x28), LanFrameFailure.INVALID_UTF8)
        val valid = framed("{\"v\":1,\"type\":\"lan.ping\",\"token\":1}")
        assertFailure(valid + 0, LanFrameFailure.TRAILING_BYTES)
    }

    @Test
    fun rejectsInvalidIdsDigestsBase64AndOversizedPayloads() {
        rejectJson("{\"v\":1,\"type\":\"lan.accepted\",\"msg_id\":\"not-a-uuid\",\"envelope_sha256\":\"$DIGEST\"}", LanFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"lan.accepted\",\"msg_id\":\"$MSG_ID\",\"envelope_sha256\":\"${"A".repeat(64)}\"}", LanFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"lan.hello\",\"data\":\"%%%\"}", LanFrameFailure.INVALID_BASE64)
        rejectJson("{\"v\":1,\"type\":\"lan.hello\",\"data\":\"YQ\"}", LanFrameFailure.INVALID_BASE64)
        val tooMuchControl = Base64.getEncoder().encodeToString(ByteArray(LanFrameLimits.MAX_CONTROL_BYTES + 1))
        rejectJson("{\"v\":1,\"type\":\"lan.hello\",\"data\":\"$tooMuchControl\"}", LanFrameFailure.CONTROL_TOO_LARGE)
        val tooMuchEnvelope = "x".repeat(LanFrameLimits.MAX_ENVELOPE_BYTES + 1)
        rejectJson("{\"v\":1,\"type\":\"lan.put\",\"envelope\":\"$tooMuchEnvelope\"}", LanFrameFailure.ENVELOPE_TOO_LARGE)
    }

    @Test
    fun byteBudgetAcceptsFourMaximumFramesAndRejectsTheFifthWithoutGrowth() {
        val buffer = LanFrameBuffer()
        val retained = LanFrameCodec.encode(
            LanFrame.Put(ByteArray(LanFrameLimits.MAX_ENVELOPE_BYTES) { 'a'.code.toByte() }),
        )
        val originalFirst = retained[0]
        repeat(LanFrameLimits.MAX_BUFFERED_FRAMES) { assertTrue(buffer.tryOffer(retained)) }
        retained[0] = (retained[0].toInt() xor 0xff).toByte()
        val bytesBefore = buffer.bufferedBytes
        val fifth = LanFrameCodec.encode(LanFrame.Ping(9))

        assertFalse(buffer.tryOffer(fifth))
        assertEquals(bytesBefore, buffer.bufferedBytes)
        assertEquals(LanFrameLimits.MAX_BUFFERED_FRAMES, buffer.bufferedFrames)
        assertTrue(bytesBefore <= LanFrameLimits.MAX_BUFFERED_BYTES)

        assertEquals(originalFirst, buffer.poll()!![0])
        assertTrue(buffer.tryOffer(fifth))
    }

    private fun rejectJson(json: String, failure: LanFrameFailure) = assertFailure(framed(json), failure)

    private fun assertFailure(bytes: ByteArray, failure: LanFrameFailure) {
        val error = assertFailsWith<LanFrameException> { LanFrameCodec.decode(bytes) }
        assertEquals(failure, error.failure)
        assertEquals(failure.code, error.message)
    }

    private fun framed(json: String): ByteArray {
        val body = json.encodeToByteArray()
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    private companion object {
        const val MSG_ID = "9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af"
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
