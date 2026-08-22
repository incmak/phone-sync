package co.twinotify.core.lan

import java.io.Closeable
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Accepts one inbound direct connection that has already passed pin and signed hello. */
interface LanListener : Closeable {
    suspend fun accept(): AuthenticatedLanConnection
}

/** Dials one discovered candidate and authenticates it. */
fun interface LanDialer {
    suspend fun dial(candidate: LanCandidate): AuthenticatedLanConnection
}

/**
 * Produces the one direct connection the coordinator may use.
 *
 * Both phones advertise and both discover, so both may dial at the same instant.
 * Taking whichever authenticates first would let the two devices keep different
 * halves of a crossed pair. Instead the first success waits a bounded moment for
 * its opposite, and when both exist [LanConnectionArbiter] picks the survivor.
 * That decision reads only the two sessions, so both phones reach it independently
 * and agree without exchanging another message.
 */
class DirectLanConnector(
    private val discovery: LanDiscovery,
    private val listener: LanListener,
    private val dialer: LanDialer,
    private val arbitrationGraceMillis: Long = DEFAULT_ARBITRATION_GRACE_MILLIS,
    /**
     * Whole-attempt ceiling. Without it a silent listener would block the route
     * open forever and the coordinator could never fall back to the relay.
     */
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
) {
    suspend fun connect(): AuthenticatedLanConnection = withTimeout(connectTimeoutMillis) {
        coroutineScope {
            // Each side reports its own outcome, so one failing does not cancel the
            // other. A refused dial must still let an inbound connection land.
            val inbound = async { attempt { listener.accept() } }
            val outbound = async { attempt { dialer.dial(discovery.candidates().first()) } }
            try {
                race(inbound, outbound)
            } finally {
                inbound.cancel()
                outbound.cancel()
            }
        }
    }

    private suspend fun race(
        inbound: Deferred<Result<AuthenticatedLanConnection>>,
        outbound: Deferred<Result<AuthenticatedLanConnection>>,
    ): AuthenticatedLanConnection {
        val (first, pending) = select<Pair<Result<AuthenticatedLanConnection>, Deferred<Result<AuthenticatedLanConnection>>>> {
            inbound.onAwait { it to outbound }
            outbound.onAwait { it to inbound }
        }

        val winner = first.getOrNull()
            // That side failed, so the other is the only remaining chance.
            ?: return pending.await().getOrElse { throw first.exceptionOrNull() ?: it }

        val other = withTimeoutOrNull(arbitrationGraceMillis) { pending.await() }?.getOrNull()
            ?: return winner

        return if (LanConnectionArbiter.prefer(other.session, winner.session)) {
            winner.close()
            other
        } else {
            other.close()
            winner
        }
    }

    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private companion object {
        /** Long enough for a crossed dial to land, short enough not to stall a route. */
        const val DEFAULT_ARBITRATION_GRACE_MILLIS = 750L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 8_000L
    }
}

/**
 * Accepts direct connections on a pinned TLS server socket.
 *
 * Every accepted socket goes through the same [LanConnectionFactory] as a dialled
 * one, so an inbound peer is held to the identical pin, signed hello, and timeout.
 * Accepting is not trusting.
 */
class JsseLanListener(
    private val serverSocket: SSLServerSocket,
    expectedPeerTlsPin: ByteArray,
    private val handshakeFactory: () -> LanSocketHandshake,
) : LanListener {
    private val expectedPin = expectedPeerTlsPin.copyOf()

    override suspend fun accept(): AuthenticatedLanConnection {
        val socket = runInterruptible(Dispatchers.IO) { serverSocket.accept() as SSLSocket }
        return LanConnectionFactory(
            socketProvider = { JsseLanTlsSocket(socket) },
            expectedPeerTlsPin = expectedPin,
            handshake = handshakeFactory(),
        ).connect()
    }

    override fun close() {
        runCatching { serverSocket.close() }
    }
}

/**
 * Dials a discovered candidate over its own bound network, so a direct attempt
 * cannot be silently carried by mobile data instead of the shared Wi-Fi.
 */
class JsseLanDialer(
    private val clientContext: SSLContext,
    expectedPeerTlsPin: ByteArray,
    private val handshakeFactory: () -> LanSocketHandshake,
) : LanDialer {
    private val expectedPin = expectedPeerTlsPin.copyOf()

    override suspend fun dial(candidate: LanCandidate): AuthenticatedLanConnection =
        LanConnectionFactory(
            socketProvider = {
                val plain = runInterruptible(Dispatchers.IO) {
                    candidate.network.openSocket().apply {
                        connect(InetSocketAddress(candidate.address, candidate.port), CONNECT_TIMEOUT_MILLIS)
                    }
                }
                val tls = runInterruptible(Dispatchers.IO) {
                    clientContext.socketFactory.createSocket(
                        plain,
                        candidate.address.hostAddress,
                        candidate.port,
                        true,
                    ) as SSLSocket
                }
                JsseLanTlsSocket(tls)
            },
            expectedPeerTlsPin = expectedPin,
            handshake = handshakeFactory(),
        ).connect()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 4_000
    }
}
