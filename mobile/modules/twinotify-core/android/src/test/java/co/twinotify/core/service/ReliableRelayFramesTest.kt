package co.twinotify.core.service

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.Test
import org.json.JSONObject

class ReliableRelayFramesTest {
    @Test
    fun helloAdvertisesV2AndLegacyCompatibility() {
        val frame = JSONObject(ReliableRelayFrames.hello("0.8.0"))

        assertEquals(2, frame.getInt("v"))
        assertEquals("relay.hello", frame.getString("type"))
        assertEquals("0.8.0", frame.getString("app_version"))
        assertEquals(2, frame.getJSONArray("protocols").getInt(0))
        assertEquals(1, frame.getJSONArray("protocols").getInt(1))
    }

    @Test
    fun putCarriesTheDurableEnvelopeWithoutFlatteningIt() {
        val envelope = "{\"v\":2,\"msg_id\":\"m-1\",\"ciphertext\":\"abc\"}"
        val frame = JSONObject(ReliableRelayFrames.put(envelope))

        assertEquals(2, frame.getInt("v"))
        assertEquals("relay.put", frame.getString("type"))
        assertNotNull(frame.optJSONObject("envelope"))
        assertEquals("m-1", frame.getJSONObject("envelope").getString("msg_id"))
        assertEquals("abc", frame.getJSONObject("envelope").getString("ciphertext"))
    }
}
