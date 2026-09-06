package co.twinotify.core.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Twinotify's mirrored notifications carry their own tone rather than the system default, so a
 * mirrored notification is identifiable by ear.
 *
 * Android fixes a channel's sound when it first creates the channel: `createNotificationChannel`
 * on an existing id updates only name, description and (downward) importance, and deleting a
 * channel does not reset the settings Android remembers for that id. Giving mirrors a tone
 * therefore required a new channel id, and these tests pin that reasoning so nobody later
 * "simplifies" it back to a `setSound` call that the platform silently ignores.
 */
class NotificationSoundIdentityTest {
    private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
    private val setupSource =
        File(projectDir, "src/main/java/co/twinotify/core/service/NotifChannelSetup.kt").readText()

    @Test
    fun mirrorChannelShipsItsOwnSoundResource() {
        val sound = File(projectDir, "src/main/res/raw/twinotify_notification.ogg")

        assertTrue(sound.isFile, "notification tone asset is missing")
        assertTrue(sound.length() in 1_000..512_000, "tone should be small: ${sound.length()} bytes")
        assertEquals("OggS", sound.inputStream().use { String(it.readNBytes(4)) })
    }

    @Test
    fun mirrorChannelIsCreatedWithThatSoundAndNotificationAudioAttributes() {
        assertTrue(setupSource.contains("R.raw.twinotify_notification"))
        assertTrue(setupSource.contains("setSound("))
        assertTrue(setupSource.contains("USAGE_NOTIFICATION"))
        assertTrue(setupSource.contains("CONTENT_TYPE_SONIFICATION"))
    }

    @Test
    fun mirrorChannelIdWasVersionedBecauseAnExistingChannelKeepsItsOldSound() {
        assertTrue(
            setupSource.contains("CHANNEL_MIRRORS = \"mirrored_notifications_v2\""),
            "a new id is the only way an installed app can change a channel's sound",
        )
        assertTrue(
            setupSource.contains("deleteNotificationChannel(CHANNEL_MIRRORS_LEGACY)"),
            "the superseded channel must be removed so users see one entry, not two",
        )
        assertTrue(setupSource.contains("CHANNEL_MIRRORS_LEGACY = \"mirrored_notifications\""))
    }

    @Test
    fun statusAndCallChannelsKeepTheirOwnSoundBehaviour() {
        // The foreground status channel is silent by design and the call channel must keep
        // ring-like platform behaviour, so neither gets the mirror tone.
        val mirrorBlock = setupSource
            .substringAfter("CHANNEL_MIRRORS,")
            .substringBefore("CHANNEL_FGS")

        assertTrue(mirrorBlock.contains("R.raw.twinotify_notification"))
        assertEquals(1, Regex("R\\.raw\\.twinotify_notification").findAll(setupSource).count())
    }
}
