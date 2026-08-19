package co.twinotify.core.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyInboundDispatcherTest {
    @Test
    fun missingPeerDoesNotMarkAndRetryCanDispatch() = runBlocking {
        var peer: String? = null
        val calls = mutableListOf<String>()
        val processor = processor(
            loadPeer = { calls += "load-peer"; peer },
            decrypt = { _, _ -> calls += "decrypt"; validInner() },
            parse = { bytes -> calls += "parse"; JSONObject(bytes.toString(Charsets.UTF_8)) },
            seenOrMark = { calls += "mark"; false },
            dispatch = { calls += "dispatch" },
        )

        processor.process(envelope())
        peer = "peer"
        processor.process(envelope())

        assertEquals(
            listOf("load-peer", "load-peer", "decrypt", "parse", "mark", "dispatch"),
            calls,
        )
    }

    @Test
    fun transientDecryptFailureDoesNotMarkAndRetryCanDispatch() = runBlocking {
        var decryptSucceeds = false
        val calls = mutableListOf<String>()
        val processor = processor(
            decrypt = { _, _ ->
                calls += "decrypt"
                if (decryptSucceeds) validInner() else error("transient keystore failure")
            },
            parse = { bytes -> calls += "parse"; JSONObject(bytes.toString(Charsets.UTF_8)) },
            seenOrMark = { calls += "mark"; false },
            dispatch = { calls += "dispatch" },
        )

        processor.process(envelope())
        decryptSucceeds = true
        processor.process(envelope())

        assertEquals(
            listOf("decrypt", "decrypt", "parse", "mark", "dispatch"),
            calls,
        )
    }

    @Test
    fun malformedPlaintextDoesNotMarkAndCorrectedRedeliveryCanDispatch() = runBlocking {
        var plaintext = "not-json".toByteArray()
        val calls = mutableListOf<String>()
        val processor = processor(
            decrypt = { _, _ -> calls += "decrypt"; plaintext },
            parse = { bytes ->
                calls += "parse"
                JSONObject(bytes.toString(Charsets.UTF_8))
            },
            seenOrMark = { calls += "mark"; false },
            dispatch = { calls += "dispatch" },
        )

        processor.process(envelope())
        plaintext = validInner()
        processor.process(envelope())

        assertEquals(
            listOf("decrypt", "parse", "decrypt", "parse", "mark", "dispatch"),
            calls,
        )
    }

    @Test
    fun authenticatedInnerEventMarksBeforeDispatch() = runBlocking {
        val calls = mutableListOf<String>()
        processor(
            seenOrMark = { calls += "mark"; false },
            dispatch = { calls += "dispatch" },
        ).process(envelope())

        assertEquals(listOf("mark", "dispatch"), calls)
    }

    @Test
    fun alreadySeenAuthenticatedInnerEventDoesNotDispatch() = runBlocking {
        val calls = mutableListOf<String>()
        processor(
            seenOrMark = { calls += "mark"; true },
            dispatch = { calls += "dispatch" },
        ).process(envelope())

        assertEquals(listOf("mark"), calls)
    }

    @Test
    fun dispatchFailureLeavesAuthenticatedEventMarked() = runBlocking {
        var seen = false
        var dispatches = 0
        val processor = processor(
            seenOrMark = {
                if (seen) true else false.also { seen = true }
            },
            dispatch = {
                dispatches += 1
                error("platform failure")
            },
        )

        assertFailsWith<IllegalStateException> { processor.process(envelope()) }
        processor.process(envelope())

        assertEquals(true, seen)
        assertEquals(1, dispatches)
    }

    @Test
    fun preparationDoesNotSwallowCoroutineCancellation() = runBlocking {
        val processor = processor(
            decrypt = { _, _ -> throw CancellationException("cancelled") },
            seenOrMark = { false },
            dispatch = {},
        )

        assertFailsWith<CancellationException> { processor.process(envelope()) }
        Unit
    }

    @Test
    fun replayGuardFailureDoesNotDispatchWithoutProtection() = runBlocking {
        var dispatches = 0
        val processor = processor(
            seenOrMark = { error("replay store unavailable") },
            dispatch = { dispatches += 1 },
        )

        assertFailsWith<IllegalStateException> { processor.process(envelope()) }
        assertEquals(0, dispatches)
    }

    private fun processor(
        loadPeer: suspend () -> String? = { "peer" },
        decrypt: suspend (EncryptedEnvelope, String) -> ByteArray? = { _, _ -> validInner() },
        parse: (ByteArray) -> JSONObject? = { JSONObject(it.toString(Charsets.UTF_8)) },
        seenOrMark: suspend (String) -> Boolean,
        dispatch: suspend (JSONObject) -> Unit,
    ) = LegacyInboundProcessor(
        loadPeer = loadPeer,
        decrypt = decrypt,
        parseInner = parse,
        seenOrMark = seenOrMark,
        dispatchInner = dispatch,
    )

    private fun envelope() = EncryptedEnvelope(
        msgId = "legacy-message",
        originDevice = "peer",
        ts = 1_000L,
        type = "enc",
        nonceB64 = "nonce",
        ciphertextB64 = "ciphertext",
    )

    private fun validInner() = "{\"type\":\"unpair\"}".toByteArray()
}
