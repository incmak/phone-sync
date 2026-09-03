package co.twinotify.core.direct

import java.io.Closeable
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/**
 * Route-neutral direct-route command set. Each direct transport maps its own wire
 * frames onto these one-to-one; nothing here knows how LAN or Bluetooth encodes them.
 */
sealed interface DirectCommand {
    /** One stored E2EE envelope, byte-for-byte as persisted. */
    class Put(envelope: ByteArray) : DirectCommand {
        private val stored = envelope.copyOf()
        val envelope: ByteArray get() = stored.copyOf()
        override fun equals(other: Any?) = other is Put && MessageDigest.isEqual(stored, other.stored)
        override fun hashCode() = stored.size
        override fun toString() = "Put(envelope=<redacted:${stored.size} bytes>)"
    }

    /** The peer took durable custody of `msgId` for exactly the digest it names. */
    data class Accepted(val msgId: String, val envelopeSha256: String) : DirectCommand

    data class Ping(val token: Long) : DirectCommand
    data class Pong(val token: Long) : DirectCommand

    /** End the session with a stable, content-free code. */
    data class Close(val code: String) : DirectCommand
}

/**
 * One authenticated direct link, already bound to a known peer. [incoming] is the
 * ordered inbound stream; [send] suspends under pressure rather than dropping.
 * [close] must be safe to call more than once and from a non-cancellable context.
 */
interface DirectWire : Closeable {
    val peerDeviceId: String
    val incoming: Flow<DirectCommand>
    suspend fun send(command: DirectCommand)
}

sealed interface DirectDeliveryEvent {
    /** The peer took durable custody of one of our outbound rows. */
    data class PeerAccepted(val msgId: String, val eventType: String?) : DirectDeliveryEvent

    /** We took durable custody of one of the peer's events and acknowledged it. */
    data class Committed(val msgId: String, val duplicate: Boolean) : DirectDeliveryEvent

    /** The session ended. `code` is stable and carries no content or network detail. */
    data class Closed(val code: String) : DirectDeliveryEvent
}
