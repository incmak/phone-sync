package co.twinotify.core.bluetooth

import co.twinotify.core.lan.LanFrameLimits
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothFrameCodecTest {
    @Test
    fun roundTripsEveryClosedWorldFrameAndCopiesBytes() {
        val envelope = "{\"v\":2,\"type\":\"enc\"}".encodeToByteArray()
        val frames = listOf(
            BluetoothFrame.Put(envelope),
            BluetoothFrame.Accepted(MSG_ID, DIGEST),
            BluetoothFrame.Ping(7),
            BluetoothFrame.Pong(7),
            BluetoothFrame.Close("peer_closed"),
        )

        frames.forEach { frame ->
            val encoded = BluetoothFrameCodec.encode(frame)
            assertEquals(encoded.size - 4, ByteBuffer.wrap(encoded, 0, 4).int)
            assertEquals(frame, BluetoothFrameCodec.decode(encoded))
        }
        val put = BluetoothFrame.Put(envelope)
        envelope[0] = 'x'.code.toByte()
        assertContentEquals("{\"v\":2,\"type\":\"enc\"}".encodeToByteArray(), put.envelope)
        put.envelope[0] = 'y'.code.toByte()
        assertContentEquals("{\"v\":2,\"type\":\"enc\"}".encodeToByteArray(), put.envelope)
        assertTrue(put.toString().contains("redacted"))
        assertFalse(put.toString().contains("enc"))
        val ping = BluetoothFrameCodec.encode(BluetoothFrame.Ping(7))
        assertEquals("{\"v\":1,\"type\":\"bt.ping\",\"token\":7}", ping.copyOfRange(4, ping.size).decodeToString())
    }

    @Test
    fun codecRejectsOversizeTruncationDuplicatesAndPrivateUnknownFields() {
        assertFailure(frameWithLength(1_064_997), BluetoothFrameFailure.FRAME_TOO_LARGE)
        assertFailure(frameWithLength(1_064_996), BluetoothFrameFailure.TRUNCATED)
        assertFailure(byteArrayOf(0, 0, 0, 10, 1), BluetoothFrameFailure.TRUNCATED)
        rejectJson("{\"v\":1,\"v\":1,\"type\":\"bt.ping\",\"token\":1}", BluetoothFrameFailure.DUPLICATE_KEY)
        rejectJson("{\"v\":1,\"type\":\"bt.ping\",\"token\":1,\"address\":\"private\"}", BluetoothFrameFailure.INVALID_FIELDS)
        rejectJson("{\"v\":1,\"type\":\"bt.accepted\",\"msg_id\":\"$MSG_ID\"}", BluetoothFrameFailure.INVALID_FIELDS)
        rejectJson("{\"v\":1,\"type\":\"bt.ping\",\"token\":\"1\"}", BluetoothFrameFailure.INVALID_FIELDS)
    }

    @Test
    fun limitsAreTheSharedDirectRouteLimits() {
        assertEquals(1_064_996, LanFrameLimits.MAX_FRAME_BYTES)
        assertEquals(1_048_576, LanFrameLimits.MAX_ENVELOPE_BYTES)
        assertEquals(16_384, LanFrameLimits.MAX_CONTROL_BYTES)
        assertEquals(4, LanFrameLimits.MAX_BUFFERED_FRAMES)
        assertEquals(4_260_000, LanFrameLimits.MAX_BUFFERED_BYTES)
        val maximal = BluetoothFrameCodec.encode(BluetoothFrame.Put(ByteArray(LanFrameLimits.MAX_ENVELOPE_BYTES) { 'a'.code.toByte() }))
        assertTrue(maximal.size - 4 <= LanFrameLimits.MAX_FRAME_BYTES)
        val tooMuchEnvelope = "x".repeat(LanFrameLimits.MAX_ENVELOPE_BYTES + 1)
        rejectJson("{\"v\":1,\"type\":\"bt.put\",\"envelope\":\"$tooMuchEnvelope\"}", BluetoothFrameFailure.ENVELOPE_TOO_LARGE)
        val oversizedPut = assertFailsWith<BluetoothFrameException> {
            BluetoothFrameCodec.encode(BluetoothFrame.Put(ByteArray(LanFrameLimits.MAX_ENVELOPE_BYTES + 1) { 'a'.code.toByte() }))
        }
        assertEquals(BluetoothFrameFailure.ENVELOPE_TOO_LARGE, oversizedPut.failure)
    }

    @Test
    fun rejectsMalformedLengthsUtf8VersionsTypesAndTrailingBytes() {
        assertFailure(byteArrayOf(0, 0, 0, 0), BluetoothFrameFailure.INVALID_LENGTH)
        assertFailure(byteArrayOf(0, 0), BluetoothFrameFailure.TRUNCATED)
        assertFailure(byteArrayOf(0, 0, 0, 2, 0xc3.toByte(), 0x28), BluetoothFrameFailure.INVALID_UTF8)
        assertFailure(framed("{\"v\":1,\"type\":\"bt.ping\",\"token\":1}") + 0, BluetoothFrameFailure.TRAILING_BYTES)
        rejectJson("{\"v\":2,\"type\":\"bt.ping\",\"token\":1}", BluetoothFrameFailure.UNSUPPORTED_VERSION)
        rejectJson("{\"v\":1,\"type\":\"lan.ping\",\"token\":1}", BluetoothFrameFailure.UNSUPPORTED_TYPE)
        rejectJson("{\"v\":1,\"type\":\"bt.hello\",\"data\":\"AA==\"}", BluetoothFrameFailure.UNSUPPORTED_TYPE)
        rejectJson("{\"v\":1,\"type\":\"bt.ping\",\"token\":1", BluetoothFrameFailure.INVALID_JSON)
        rejectJson("[1]", BluetoothFrameFailure.INVALID_JSON)
    }

    @Test
    fun rejectsNoncanonicalUuidsUppercaseDigestsBadCloseCodesAndNegativeTokens() {
        rejectJson("{\"v\":1,\"type\":\"bt.accepted\",\"msg_id\":\"${MSG_ID.uppercase()}\",\"envelope_sha256\":\"$DIGEST\"}", BluetoothFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"bt.accepted\",\"msg_id\":\"not-a-uuid\",\"envelope_sha256\":\"$DIGEST\"}", BluetoothFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"bt.accepted\",\"msg_id\":\"$MSG_ID\",\"envelope_sha256\":\"${DIGEST.uppercase()}\"}", BluetoothFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"bt.accepted\",\"msg_id\":\"$MSG_ID\",\"envelope_sha256\":\"${DIGEST.dropLast(1)}\"}", BluetoothFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"bt.close\",\"code\":\"Peer Closed\"}", BluetoothFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"bt.close\",\"code\":\"\"}", BluetoothFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"bt.ping\",\"token\":-1}", BluetoothFrameFailure.INVALID_VALUE)
        rejectJson("{\"v\":1,\"type\":\"bt.pong\",\"token\":-1}", BluetoothFrameFailure.INVALID_VALUE)
    }

    @Test
    fun everyFailureCodeIsBluetoothScoped() {
        BluetoothFrameFailure.entries.forEach { failure ->
            assertTrue(failure.code.startsWith("bluetooth_frame_"), failure.code)
        }
    }

    private fun rejectJson(json: String, failure: BluetoothFrameFailure) = assertFailure(framed(json), failure)

    private fun assertFailure(bytes: ByteArray, failure: BluetoothFrameFailure) {
        val error = assertFailsWith<BluetoothFrameException> { BluetoothFrameCodec.decode(bytes) }
        assertEquals(failure, error.failure)
        assertEquals(failure.code, error.message)
    }

    private fun framed(json: String): ByteArray {
        val body = json.encodeToByteArray()
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    /** Only the prefix and one body byte: the length claim is what the codec must judge first. */
    private fun frameWithLength(length: Int): ByteArray = ByteBuffer.allocate(5).putInt(length).put(1).array()

    private companion object {
        const val MSG_ID = "9f633ff1-0bdd-4a95-bb9e-5d9e0ef8f6af"
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
