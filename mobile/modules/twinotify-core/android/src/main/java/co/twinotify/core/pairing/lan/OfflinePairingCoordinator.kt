package co.twinotify.core.pairing.lan

import java.security.MessageDigest

/**
 * Serial, synchronous coordinator for one ephemeral pairing ceremony. Adapters
 * must marshal transport callbacks onto one owner before invoking this class.
 * It deliberately holds no recoverable session state: process restart starts IDLE.
 */
class OfflinePairingCoordinator(
    private val role: OfflinePairingRole,
    private val localIdentity: OfflinePairingIdentity,
    private val port: OfflinePairingPort,
    private val committer: OfflinePairingCommitter,
    private val crypto: OfflinePairingCrypto = ProductionOfflinePairingCrypto,
    private val statusSink: (OfflinePairingStatus) -> Unit = {},
) {
    var status: OfflinePairingStatus = OfflinePairingStatus(OfflinePairingState.IDLE)
        private set

    private var qr: LanPairingQr? = null
    private var deadlineMillis: Long? = null
    private var presentedTlsPin: ByteArray? = null
    private var peerHello: LanPairingHello? = null
    private var transcript: ByteArray? = null
    private var ownSignature: ByteArray? = null
    private var peerSignature: ByteArray? = null // Exactly one 64-byte provisional frame.

    fun start(session: LanPairingQr, nonce: ByteArray) = synchronized(this) {
        if (status.state != OfflinePairingState.IDLE || nonce.size != 32) return@synchronized abort(OfflinePairingError.INVALID_FRAME)
        clearProvisional()
        qr = session
        deadlineMillis = safeDeadline(port.monotonicMillis(), session.lifetimeMillis) ?: return@synchronized abort(OfflinePairingError.EXPIRED)
        val hello = localHello(nonce)
        peerHello = null
        if (role == OfflinePairingRole.INITIATOR) {
            transition(OfflinePairingState.ADVERTISING)
            port.advertise(session.sessionId)
        } else {
            transition(OfflinePairingState.RESOLVING)
            port.resolve(session.sessionId, session.tlsSpkiSha256.copy())
        }
        // The local nonce is retained only as a typed Hello and becomes part of
        // the transcript after peer authentication; it is then cleared at end.
        localHello = hello
    }

    fun onTlsAuthenticated(peerTlsSpkiSha256: ByteArray) = synchronized(this) {
        if (!isActiveTransportState()) return@synchronized
        if (expired()) return@synchronized abort(OfflinePairingError.EXPIRED)
        if (peerTlsSpkiSha256.size != 32) return@synchronized abort(OfflinePairingError.IDENTITY_MISMATCH)
        val session = qr ?: return@synchronized abort(OfflinePairingError.INVALID_FRAME)
        if (role == OfflinePairingRole.JOINER && !MessageDigest.isEqual(peerTlsSpkiSha256, session.tlsSpkiSha256.copy())) {
            return@synchronized abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        presentedTlsPin = peerTlsSpkiSha256.copyOf()
        transition(OfflinePairingState.TLS_AUTHENTICATED)
        port.send(OfflinePairingFrame.Hello(session.sessionId, session.lifetimeMillis, localHello ?: return@synchronized abort(OfflinePairingError.INVALID_FRAME)))
    }

    fun onPeerFrame(frame: OfflinePairingFrame) = synchronized(this) {
        if (status.state == OfflinePairingState.IDLE || status.state == OfflinePairingState.COMPLETE) return@synchronized
        if (expired()) return@synchronized abort(OfflinePairingError.EXPIRED)
        val session = qr ?: return@synchronized abort(OfflinePairingError.INVALID_FRAME)
        if (frame.sessionId != session.sessionId) return@synchronized abort(OfflinePairingError.IDENTITY_MISMATCH)
        when (frame) {
            is OfflinePairingFrame.Hello -> receiveHello(session, frame)
            is OfflinePairingFrame.Signature -> receiveSignature(frame)
        }
    }

    fun confirmLocally() = synchronized(this) {
        if (expired()) return@synchronized abort(OfflinePairingError.EXPIRED)
        if (status.state != OfflinePairingState.VERIFY_CODE) return@synchronized
        val canonical = transcript ?: return@synchronized abort(OfflinePairingError.INVALID_FRAME)
        transition(OfflinePairingState.LOCAL_CONFIRMED)
        val signature = try {
            crypto.signTranscript(canonical, localIdentity.signingSecretKey)
        } catch (_: Exception) {
            return@synchronized abort(OfflinePairingError.INVALID_FRAME)
        }
        if (signature.size != SIGNATURE_BYTES) return@synchronized abort(OfflinePairingError.INVALID_FRAME)
        ownSignature = signature.copyOf()
        port.send(OfflinePairingFrame.Signature(qr!!.sessionId, signature))
        commitIfMutual()
    }

    fun cancel() = synchronized(this) { abort(OfflinePairingError.CANCELLED) }

    internal fun provisionalSignatureSizeForTest(): Int = synchronized(this) { peerSignature?.size ?: 0 }

    private var localHello: LanPairingHello? = null

    private fun receiveHello(session: LanPairingQr, frame: OfflinePairingFrame.Hello) {
        if (status.state != OfflinePairingState.TLS_AUTHENTICATED && status.state != OfflinePairingState.VERIFY_CODE &&
            status.state != OfflinePairingState.LOCAL_CONFIRMED
        ) return abort(OfflinePairingError.INVALID_FRAME)
        if (frame.lifetimeMillis != session.lifetimeMillis) return abort(OfflinePairingError.IDENTITY_MISMATCH)
        val prior = peerHello
        if (prior != null) {
            if (sameHello(prior, frame.hello)) return
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        if (frame.hello.deviceId == localIdentity.deviceId || !matchesPresentedPin(frame.hello) || !matchesQrPeer(session, frame.hello)) {
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        if (!matchesExpectedRelayPeer(frame.hello)) return abort(OfflinePairingError.IDENTITY_MISMATCH)
        peerHello = frame.hello
        val local = localHello ?: return abort(OfflinePairingError.INVALID_FRAME)
        val assembled = try {
            LanPairingTranscript(session.sessionId, session.lifetimeMillis, session.version, local, frame.hello)
        } catch (_: IllegalArgumentException) {
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        transcript = try {
            crypto.canonicalTranscript(assembled)
        } catch (_: Exception) {
            return abort(OfflinePairingError.INVALID_FRAME)
        }
        val sas = try {
            crypto.shortAuthenticationString(transcript!!)
        } catch (_: Exception) {
            return abort(OfflinePairingError.INVALID_FRAME)
        }
        if (!sas.matches(Regex("[0-9]{6}"))) return abort(OfflinePairingError.INVALID_FRAME)
        transition(OfflinePairingState.VERIFY_CODE, sas)
    }

    private fun receiveSignature(frame: OfflinePairingFrame.Signature) {
        val canonical = transcript ?: return abort(OfflinePairingError.INVALID_FRAME)
        val peer = peerHello ?: return abort(OfflinePairingError.INVALID_FRAME)
        val signature = frame.signature
        if (signature.size != SIGNATURE_BYTES || !crypto.verifyTranscript(canonical, signature, peer.signingPublicKey.copy())) {
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        val prior = peerSignature
        if (prior != null) {
            if (MessageDigest.isEqual(prior, signature)) return
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        peerSignature = signature.copyOf()
        commitIfMutual()
    }

    private fun commitIfMutual() {
        if (ownSignature == null || peerSignature == null || status.state !in setOf(OfflinePairingState.LOCAL_CONFIRMED, OfflinePairingState.VERIFY_CODE)) return
        if (status.state != OfflinePairingState.LOCAL_CONFIRMED) return // Peer confirmation must not substitute for ours.
        val session = qr ?: return abort(OfflinePairingError.INVALID_FRAME)
        val canonical = transcript ?: return abort(OfflinePairingError.INVALID_FRAME)
        val peer = peerHello ?: return abort(OfflinePairingError.INVALID_FRAME)
        transition(OfflinePairingState.MUTUALLY_SIGNED)
        val secret = try { crypto.derivePairSecret(session.sessionToken.copy(), canonical) } catch (_: Exception) { return abort(OfflinePairingError.INVALID_FRAME) }
        if (secret.size != 32) return abort(OfflinePairingError.INVALID_FRAME)
        val committed = try {
            committer.commit(OfflinePairingCommit(
                peer.deviceId, peer.encryptionPublicKey.copy(), peer.signingPublicKey.copy(), peer.tlsSpkiSha256.copy(), secret, session.version,
            ))
        } catch (_: Exception) {
            false
        }
        secret.fill(0)
        if (!committed) return abort(OfflinePairingError.COMMIT_FAILED)
        transition(OfflinePairingState.COMMITTED)
        clearProvisional()
        qr = null
        deadlineMillis = null
        presentedTlsPin = null
        localHello = null
        transition(OfflinePairingState.COMPLETE)
        port.close()
    }

    private fun matchesExpectedRelayPeer(peer: LanPairingHello): Boolean = committer.existingPeer()?.exactlyMatches(peer) ?: true

    /** The joining side already knows the initiator's complete application identity from QR. */
    private fun matchesQrPeer(session: LanPairingQr, peer: LanPairingHello): Boolean = role != OfflinePairingRole.JOINER ||
        (session.deviceId == peer.deviceId &&
            MessageDigest.isEqual(session.encryptionPublicKey.copy(), peer.encryptionPublicKey.copy()) &&
            MessageDigest.isEqual(session.signingPublicKey.copy(), peer.signingPublicKey.copy()) &&
            MessageDigest.isEqual(session.tlsSpkiSha256.copy(), peer.tlsSpkiSha256.copy()))

    private fun matchesPresentedPin(hello: LanPairingHello): Boolean = presentedTlsPin?.let {
        MessageDigest.isEqual(it, hello.tlsSpkiSha256.copy())
    } ?: false

    private fun localHello(nonce: ByteArray) = LanPairingHello(
        localIdentity.deviceId, LanPairingBytes(localIdentity.encryptionPublicKey), LanPairingBytes(localIdentity.signingPublicKey),
        LanPairingBytes(localIdentity.tlsSpkiSha256), LanPairingBytes(nonce),
    )

    private fun sameHello(first: LanPairingHello, second: LanPairingHello): Boolean =
        first.deviceId == second.deviceId && MessageDigest.isEqual(first.encryptionPublicKey.copy(), second.encryptionPublicKey.copy()) &&
            MessageDigest.isEqual(first.signingPublicKey.copy(), second.signingPublicKey.copy()) &&
            MessageDigest.isEqual(first.tlsSpkiSha256.copy(), second.tlsSpkiSha256.copy()) && MessageDigest.isEqual(first.nonce.copy(), second.nonce.copy())

    private fun expired(): Boolean = deadlineMillis?.let { port.monotonicMillis() >= it } ?: false
    private fun isActiveTransportState() = status.state == OfflinePairingState.ADVERTISING || status.state == OfflinePairingState.RESOLVING
    private fun safeDeadline(now: Long, duration: Long): Long? = if (now < 0 || duration <= 0 || Long.MAX_VALUE - now < duration) null else now + duration

    private fun transition(state: OfflinePairingState, sas: String? = status.sas) {
        status = OfflinePairingStatus(state, null, sas)
        statusSink(status)
    }

    private fun abort(error: OfflinePairingError) {
        clearProvisional()
        qr = null
        deadlineMillis = null
        presentedTlsPin = null
        localHello = null
        port.close()
        status = OfflinePairingStatus(OfflinePairingState.IDLE, error)
        statusSink(status)
    }

    private fun clearProvisional() {
        peerSignature?.fill(0)
        ownSignature?.fill(0)
        transcript?.fill(0)
        peerSignature = null
        ownSignature = null
        transcript = null
        peerHello = null
    }

    private companion object { const val SIGNATURE_BYTES = 64 }
}
