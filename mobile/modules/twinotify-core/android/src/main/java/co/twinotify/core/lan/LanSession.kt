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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
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
    private val localDeviceId: String,
    private val peerDeviceId: String,
    private val arbitrationGraceMillis: Long = DEFAULT_ARBITRATION_GRACE_MILLIS,
    private val fallbackDialDelayMillis: Long = DEFAULT_FALLBACK_DIAL_DELAY_MILLIS,
    private val preferredConnectionWaitMillis: Long = DEFAULT_PREFERRED_CONNECTION_WAIT_MILLIS,
    private val closeListener: () -> Unit = { listener.close() },
    private val closeDiscovery: suspend () -> Unit = { discovery.close() },
    /**
     * Whole-attempt ceiling. Without it a silent listener would block the route
     * open forever and the coordinator could never fall back to the relay.
     */
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
) {
    init {
        require(localDeviceId != peerDeviceId) { "lan_identity_collision" }
        require(arbitrationGraceMillis > 0)
        require(fallbackDialDelayMillis >= 0)
        require(preferredConnectionWaitMillis >= arbitrationGraceMillis)
        require(connectTimeoutMillis > fallbackDialDelayMillis + preferredConnectionWaitMillis)
    }

    private val preferredInitiatorDeviceId = minOf(localDeviceId, peerDeviceId)

    suspend fun connect(): AuthenticatedLanConnection = try {
        withTimeout(connectTimeoutMillis) {
            coroutineScope {
                // Each side reports its own outcome, so one failing does not cancel the
                // other. A refused dial must still let an inbound connection land.
                val inbound = async { attempt { listener.accept() } }
                val outbound = async {
                    // Normally only the stable, identity-selected initiator dials. The other
                    // phone retains a delayed reverse dial so asymmetric OEM/firewall behavior
                    // can still recover without creating routine crossed connections.
                    if (localDeviceId != preferredInitiatorDeviceId) {
                        delay(fallbackDialDelayMillis)
                    }
                    attempt { dialer.dial(discovery.candidates().first()) }
                }
                try {
                    race(inbound, outbound)
                } finally {
                    withContext(NonCancellable) {
                        inbound.cancel()
                        outbound.cancel()

                        // SSLServerSocket.accept() is not reliably interruptible on Android.
                        // Close the owned listener before joining the losing branch, otherwise
                        // coroutineScope can hold an authenticated winner until the outer
                        // timeout tears that winner down as well.
                        runCatching { closeListener() }
                        try {
                            closeDiscovery()
                        } catch (_: Exception) {
                            // Closing discovery is best-effort cleanup. The branch joins below
                            // are what prevent connector work from escaping this attempt.
                        }
                        inbound.join()
                        outbound.join()
                    }
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw LanConnectionException(LanConnectionFailure.TIMEOUT)
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
        // If the first authenticated half has the non-preferred initiator, give the
        // deterministic half longer to finish. Otherwise two slow crossed handshakes can
        // make the phones keep opposite sockets and repeatedly tear each other down.
        val otherWait = if (winner.session.initiatorDeviceId == preferredInitiatorDeviceId) {
            arbitrationGraceMillis
        } else {
            preferredConnectionWaitMillis
        }
        val other = withTimeoutOrNull(otherWait) { pending.await() }?.getOrNull()
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
        /**
         * Physical Android TLS/Keystore handshakes can complete more than a second apart on
         * the two halves of a crossed dial. Keep this below the whole-attempt ceiling while
         * giving both phones enough time to arbitrate the same authenticated session.
         */
        const val DEFAULT_ARBITRATION_GRACE_MILLIS = 2_000L
        const val DEFAULT_FALLBACK_DIAL_DELAY_MILLIS = 4_000L
        const val DEFAULT_PREFERRED_CONNECTION_WAIT_MILLIS = 6_000L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000L
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
