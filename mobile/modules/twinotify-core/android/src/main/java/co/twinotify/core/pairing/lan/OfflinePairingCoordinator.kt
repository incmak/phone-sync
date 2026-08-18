package co.twinotify.core.pairing.lan

import java.security.MessageDigest

/**
 * Serial coordinator for one ephemeral pairing ceremony. Public events and
 * external effects share one explicit action queue. State is assigned before
 * an effect runs, and a reentrant event is processed before that effect's
 * continuation. No port, observer, crypto, or persistence callback runs while
 * the coordinator's queue monitor is held.
 */
class OfflinePairingCoordinator(
    private val role: OfflinePairingRole,
    private val localIdentity: OfflinePairingIdentity,
    private val port: OfflinePairingPort,
    private val committer: OfflinePairingCommitter,
    private val crypto: OfflinePairingCrypto = ProductionOfflinePairingCrypto,
    private val statusSink: (OfflinePairingStatus) -> Unit = {},
) {
    @Volatile
    var status: OfflinePairingStatus = OfflinePairingStatus(OfflinePairingState.IDLE)
        private set

    private val actionMonitor = Any()
    private val actions = ArrayDeque<() -> Unit>()
    private var draining = false
    private var inExternalEffect = false

    private var generation = 0L
    private var qr: LanPairingQr? = null
    private var deadlineMillis: Long? = null
    private var presentedTlsPin: ByteArray? = null
    private var localHello: LanPairingHello? = null
    private var pendingPeerHello: LanPairingHello? = null
    private var peerHello: LanPairingHello? = null
    private var transcript: ByteArray? = null
    private var ownSignature: ByteArray? = null
    private var pendingPeerSignature: ByteArray? = null
    private var peerSignature: ByteArray? = null
    private var signatureSent = false
    private var deriveScheduled = false
    private var closeScheduled = false

    fun start(session: LanPairingQr, nonce: ByteArray) = submitPublic {
        val now = monotonicOrNull()
        if (status.state != OfflinePairingState.IDLE || nonce.size != NONCE_BYTES || now == null) {
            abort(OfflinePairingError.INVALID_FRAME)
            return@submitPublic
        }
        val deadline = safeDeadline(now, session.lifetimeMillis)
        if (deadline == null) {
            abort(OfflinePairingError.EXPIRED)
            return@submitPublic
        }
        clearProvisional()
        generation++
        qr = session
        deadlineMillis = deadline
        presentedTlsPin = null
        localHello = localHello(nonce)
        closeScheduled = false
        val token = generation
        val state = if (role == OfflinePairingRole.INITIATOR) OfflinePairingState.ADVERTISING else OfflinePairingState.RESOLVING
        transition(state)
        transportEffect(token, setOf(state),
            call = {
                if (role == OfflinePairingRole.INITIATOR) port.advertise(session.sessionId)
                else port.resolve(session.sessionId, session.tlsSpkiSha256.copy())
            },
        )
    }

    fun onTlsAuthenticated(peerTlsSpkiSha256: ByteArray) = submitPublic {
        val now = monotonicOrNull()
        if (!isActiveTransportState()) return@submitPublic
        if (now == null) return@submitPublic abort(OfflinePairingError.INVALID_FRAME)
        if (expiredAt(now)) return@submitPublic abort(OfflinePairingError.EXPIRED)
        if (peerTlsSpkiSha256.size != TLS_PIN_BYTES) return@submitPublic abort(OfflinePairingError.IDENTITY_MISMATCH)
        val session = qr ?: return@submitPublic abort(OfflinePairingError.INVALID_FRAME)
        if (role == OfflinePairingRole.JOINER && !MessageDigest.isEqual(peerTlsSpkiSha256, session.tlsSpkiSha256.copy())) {
            return@submitPublic abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        val hello = localHello ?: return@submitPublic abort(OfflinePairingError.INVALID_FRAME)
        presentedTlsPin = peerTlsSpkiSha256.copyOf()
        transition(OfflinePairingState.TLS_AUTHENTICATED)
        transportEffect(generation, setOf(OfflinePairingState.TLS_AUTHENTICATED), call = {
            port.send(OfflinePairingFrame.Hello(session.sessionId, session.lifetimeMillis, hello))
        })
    }

    fun onPeerFrame(frame: OfflinePairingFrame) = submitPublic {
        val now = monotonicOrNull()
        if (status.state == OfflinePairingState.IDLE || status.state == OfflinePairingState.COMPLETE) return@submitPublic
        if (now == null) return@submitPublic abort(OfflinePairingError.INVALID_FRAME)
        if (expiredAt(now)) return@submitPublic abort(OfflinePairingError.EXPIRED)
        val session = qr ?: return@submitPublic abort(OfflinePairingError.INVALID_FRAME)
        if (frame.sessionId != session.sessionId) return@submitPublic abort(OfflinePairingError.IDENTITY_MISMATCH)
        when (frame) {
            is OfflinePairingFrame.Hello -> receiveHello(session, frame)
            is OfflinePairingFrame.Signature -> receiveSignature(frame)
        }
    }

    fun confirmLocally() = submitPublic {
        val now = monotonicOrNull()
        if (now == null) return@submitPublic abort(OfflinePairingError.INVALID_FRAME)
        if (expiredAt(now)) return@submitPublic abort(OfflinePairingError.EXPIRED)
        if (status.state != OfflinePairingState.VERIFY_CODE) return@submitPublic
        val canonical = transcript?.copyOf() ?: return@submitPublic abort(OfflinePairingError.INVALID_FRAME)
        transition(OfflinePairingState.LOCAL_CONFIRMED)
        val token = generation
        cryptoEffect(token, setOf(OfflinePairingState.LOCAL_CONFIRMED),
            call = { crypto.signTranscript(canonical, localIdentity.signingSecretKey) },
            onResult = { signature ->
                canonical.fill(0)
                if (signature.size != SIGNATURE_BYTES) {
                    signature.fill(0)
                    abort(OfflinePairingError.INVALID_FRAME)
                    return@cryptoEffect
                }
                ownSignature = signature.copyOf()
                prepareMutual()
                val session = qr ?: return@cryptoEffect abort(OfflinePairingError.INVALID_FRAME)
                transportEffect(token, setOf(OfflinePairingState.LOCAL_CONFIRMED, OfflinePairingState.MUTUALLY_SIGNED),
                    call = { port.send(OfflinePairingFrame.Signature(session.sessionId, signature)) },
                    onSuccess = {
                        signature.fill(0)
                        signatureSent = true
                        if (status.state == OfflinePairingState.MUTUALLY_SIGNED) scheduleDerive()
                    },
                    onDiscard = { signature.fill(0) },
                )
            },
            onDiscard = { canonical.fill(0); it?.fill(0) },
        )
    }

    fun cancel() = submitPublic {
        val now = monotonicOrNull()
        if (now != null && expiredAt(now)) abort(OfflinePairingError.EXPIRED)
        else abort(OfflinePairingError.CANCELLED)
    }

    internal fun provisionalSignatureSizeForTest(): Int = peerSignature?.size ?: pendingPeerSignature?.size ?: 0

    private fun receiveHello(session: LanPairingQr, frame: OfflinePairingFrame.Hello) {
        if (status.state != OfflinePairingState.TLS_AUTHENTICATED && status.state != OfflinePairingState.VERIFY_CODE &&
            status.state != OfflinePairingState.LOCAL_CONFIRMED
        ) return abort(OfflinePairingError.INVALID_FRAME)
        if (frame.lifetimeMillis != session.lifetimeMillis) return abort(OfflinePairingError.IDENTITY_MISMATCH)
        peerHello?.let { prior ->
            if (sameHello(prior, frame.hello)) return
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        pendingPeerHello?.let { pending ->
            if (sameHello(pending, frame.hello)) return
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        if (frame.hello.deviceId == localIdentity.deviceId || !matchesPresentedPin(frame.hello) || !matchesQrPeer(session, frame.hello)) {
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        if (status.state != OfflinePairingState.TLS_AUTHENTICATED) return abort(OfflinePairingError.INVALID_FRAME)
        pendingPeerHello = frame.hello
        val token = generation
        checkedExternal(token, setOf(OfflinePairingState.TLS_AUTHENTICATED),
            call = { committer.existingPeer() },
            onResult = { existing ->
                if (existing != null && !existing.exactlyMatches(frame.hello)) {
                    abort(OfflinePairingError.IDENTITY_MISMATCH)
                    return@checkedExternal
                }
                buildTranscript(token, session, frame.hello)
            },
        )
    }

    private fun buildTranscript(token: Long, session: LanPairingQr, peer: LanPairingHello) {
        val local = localHello ?: return abort(OfflinePairingError.INVALID_FRAME)
        val assembled = try {
            LanPairingTranscript(session.sessionId, session.lifetimeMillis, session.version, local, peer)
        } catch (_: IllegalArgumentException) {
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        cryptoEffect(token, setOf(OfflinePairingState.TLS_AUTHENTICATED),
            call = { crypto.canonicalTranscript(assembled) },
            onResult = { canonical ->
                cryptoEffect(token, setOf(OfflinePairingState.TLS_AUTHENTICATED),
                    call = { crypto.shortAuthenticationString(canonical) },
                    onResult = { sas ->
                        if (!sas.matches(Regex("[0-9]{6}"))) {
                            canonical.fill(0)
                            abort(OfflinePairingError.INVALID_FRAME)
                            return@cryptoEffect
                        }
                        peerHello = peer
                        pendingPeerHello = null
                        transcript = canonical.copyOf()
                        canonical.fill(0)
                        transition(OfflinePairingState.VERIFY_CODE, sas)
                    },
                    onDiscard = { canonical.fill(0) },
                )
            },
        )
    }

    private fun receiveSignature(frame: OfflinePairingFrame.Signature) {
        val canonical = transcript?.copyOf() ?: return abort(OfflinePairingError.INVALID_FRAME)
        val peer = peerHello ?: return abort(OfflinePairingError.INVALID_FRAME)
        val signature = frame.signature
        peerSignature?.let { prior ->
            canonical.fill(0)
            if (MessageDigest.isEqual(prior, signature)) return
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        pendingPeerSignature?.let { pending ->
            canonical.fill(0)
            if (MessageDigest.isEqual(pending, signature)) return
            return abort(OfflinePairingError.IDENTITY_MISMATCH)
        }
        pendingPeerSignature = signature.copyOf()
        val token = generation
        cryptoEffect(token, setOf(OfflinePairingState.VERIFY_CODE, OfflinePairingState.LOCAL_CONFIRMED),
            call = { crypto.verifyTranscript(canonical, signature, peer.signingPublicKey.copy()) },
            onResult = { verified ->
                canonical.fill(0)
                if (!verified) return@cryptoEffect abort(OfflinePairingError.IDENTITY_MISMATCH)
                peerSignature = signature.copyOf()
                pendingPeerSignature?.fill(0)
                pendingPeerSignature = null
                prepareMutual()
            },
            onDiscard = { canonical.fill(0) },
        )
    }

    private fun prepareMutual() {
        if (ownSignature == null || peerSignature == null || status.state != OfflinePairingState.LOCAL_CONFIRMED) return
        val now = monotonicOrNull()
        if (now == null) return abort(OfflinePairingError.INVALID_FRAME)
        if (expiredAt(now)) return abort(OfflinePairingError.EXPIRED)
        transition(OfflinePairingState.MUTUALLY_SIGNED)
        if (signatureSent) scheduleDerive()
    }

    private fun scheduleDerive() {
        if (deriveScheduled || status.state != OfflinePairingState.MUTUALLY_SIGNED) return
        val session = qr ?: return abort(OfflinePairingError.INVALID_FRAME)
        val canonical = transcript?.copyOf() ?: return abort(OfflinePairingError.INVALID_FRAME)
        val peer = peerHello ?: return abort(OfflinePairingError.INVALID_FRAME)
        deriveScheduled = true
        val token = generation
        cryptoEffect(token, setOf(OfflinePairingState.MUTUALLY_SIGNED),
            call = { crypto.derivePairSecret(session.sessionToken.copy(), canonical) },
            onResult = { secret ->
                canonical.fill(0)
                deriveScheduled = false
                if (secret.size != SECRET_BYTES) {
                    secret.fill(0)
                    abort(OfflinePairingError.INVALID_FRAME)
                    return@cryptoEffect
                }
                scheduleCommit(token, session, peer, secret)
            },
            onDiscard = { canonical.fill(0); it?.fill(0) },
        )
    }

    private fun scheduleCommit(token: Long, session: LanPairingQr, peer: LanPairingHello, secret: ByteArray) {
        enqueueInternal {
            if (!current(token, setOf(OfflinePairingState.MUTUALLY_SIGNED))) {
                secret.fill(0)
                return@enqueueInternal
            }
            val now = externalCall { port.monotonicMillis() }.getOrNull()
            enqueueInternal continuation@{
                if (!current(token, setOf(OfflinePairingState.MUTUALLY_SIGNED))) {
                    secret.fill(0)
                    return@continuation
                }
                if (now == null) {
                    secret.fill(0)
                    abort(OfflinePairingError.INVALID_FRAME)
                    return@continuation
                }
                if (expiredAt(now)) {
                    secret.fill(0)
                    abort(OfflinePairingError.EXPIRED)
                    return@continuation
                }
                val value = try {
                    OfflinePairingCommit(peer.deviceId, peer.encryptionPublicKey.copy(), peer.signingPublicKey.copy(),
                        peer.tlsSpkiSha256.copy(), secret, session.version)
                } catch (_: IllegalArgumentException) {
                    secret.fill(0)
                    abort(OfflinePairingError.INVALID_FRAME)
                    return@continuation
                }
                val committed = externalCall { committer.commit(value) }.getOrDefault(false)
                secret.fill(0)
                enqueueInternal committedContinuation@{
                    if (!current(token, setOf(OfflinePairingState.MUTUALLY_SIGNED))) return@committedContinuation
                    if (!committed) return@committedContinuation abort(OfflinePairingError.COMMIT_FAILED)
                    transition(OfflinePairingState.COMMITTED)
                    enqueueInternal { finishCommit(token) }
                }
            }
        }
    }

    private fun finishCommit(token: Long) {
        if (!current(token, setOf(OfflinePairingState.COMMITTED))) return
        clearProvisional()
        qr = null
        deadlineMillis = null
        presentedTlsPin = null
        localHello = null
        transition(OfflinePairingState.COMPLETE)
        scheduleClose()
    }

    private fun transportEffect(
        token: Long,
        expected: Set<OfflinePairingState>,
        call: () -> Unit,
        onSuccess: () -> Unit = {},
        onDiscard: () -> Unit = {},
    ) = checkedExternal(token, expected, call, onResult = { onSuccess() }, onDiscard = { onDiscard() })

    private fun <T> cryptoEffect(
        token: Long,
        expected: Set<OfflinePairingState>,
        call: () -> T,
        onResult: (T) -> Unit,
        onDiscard: (T?) -> Unit = {},
    ) = checkedExternal(token, expected, call, onResult, onDiscard)

    private fun <T> checkedExternal(
        token: Long,
        expected: Set<OfflinePairingState>,
        call: () -> T,
        onResult: (T) -> Unit,
        onDiscard: (T?) -> Unit = {},
    ) {
        enqueueInternal {
            if (!current(token, expected)) {
                onDiscard(null)
                return@enqueueInternal
            }
            val result = externalCall(call)
            val now = externalCall { port.monotonicMillis() }.getOrNull()
            enqueueInternal continuation@{
                if (!current(token, expected)) {
                    onDiscard(result.getOrNull())
                    return@continuation
                }
                if (now == null) {
                    onDiscard(result.getOrNull())
                    abort(OfflinePairingError.INVALID_FRAME)
                    return@continuation
                }
                if (expiredAt(now)) {
                    onDiscard(result.getOrNull())
                    abort(OfflinePairingError.EXPIRED)
                    return@continuation
                }
                if (result.isFailure) {
                    onDiscard(null)
                    abort(OfflinePairingError.INVALID_FRAME)
                    return@continuation
                }
                onResult(result.getOrThrow())
            }
        }
    }

    private fun transition(state: OfflinePairingState, sas: String? = status.sas) {
        val snapshot = OfflinePairingStatus(state, null, sas)
        status = snapshot
        val token = generation
        enqueueInternal {
            if (generation != token || status != snapshot) return@enqueueInternal
            externalCall { statusSink(snapshot) }
        }
    }

    private fun abort(error: OfflinePairingError) {
        generation++
        clearProvisional()
        qr = null
        deadlineMillis = null
        presentedTlsPin = null
        localHello = null
        val snapshot = OfflinePairingStatus(OfflinePairingState.IDLE, error)
        status = snapshot
        scheduleClose()
        val token = generation
        enqueueInternal {
            if (generation != token || status != snapshot) return@enqueueInternal
            externalCall { statusSink(snapshot) }
        }
    }

    private fun scheduleClose() {
        if (closeScheduled) return
        closeScheduled = true
        enqueueInternal { externalCall { port.close() } }
    }

    private fun clearProvisional() {
        pendingPeerSignature?.fill(0)
        peerSignature?.fill(0)
        ownSignature?.fill(0)
        transcript?.fill(0)
        pendingPeerSignature = null
        peerSignature = null
        ownSignature = null
        transcript = null
        pendingPeerHello = null
        peerHello = null
        signatureSent = false
        deriveScheduled = false
    }

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
            MessageDigest.isEqual(first.tlsSpkiSha256.copy(), second.tlsSpkiSha256.copy()) &&
            MessageDigest.isEqual(first.nonce.copy(), second.nonce.copy())

    private fun current(token: Long, expected: Set<OfflinePairingState>): Boolean = generation == token && status.state in expected
    private fun expiredAt(now: Long): Boolean = deadlineMillis?.let { now >= it } ?: false
    private fun isActiveTransportState(): Boolean = status.state == OfflinePairingState.ADVERTISING || status.state == OfflinePairingState.RESOLVING
    private fun safeDeadline(now: Long, duration: Long): Long? = if (now < 0 || duration <= 0 || Long.MAX_VALUE - now < duration) null else now + duration
    private fun monotonicOrNull(): Long? = externalCall { port.monotonicMillis() }.getOrNull()

    private fun <T> externalCall(block: () -> T): Result<T> {
        synchronized(actionMonitor) { inExternalEffect = true }
        return try {
            runCatching(block)
        } finally {
            synchronized(actionMonitor) { inExternalEffect = false }
        }
    }

    private fun submitPublic(action: () -> Unit) = enqueue(action, publicEvent = true)
    private fun enqueueInternal(action: () -> Unit) = enqueue(action, publicEvent = false)

    private fun enqueue(action: () -> Unit, publicEvent: Boolean) {
        val runDrain = synchronized(actionMonitor) {
            if (publicEvent && inExternalEffect) actions.addFirst(action) else actions.addLast(action)
            if (draining) false else {
                draining = true
                true
            }
        }
        if (runDrain) drain()
    }

    private fun drain() {
        while (true) {
            val action = synchronized(actionMonitor) {
                if (actions.isEmpty()) {
                    draining = false
                    null
                } else actions.removeFirst()
            } ?: return
            action()
        }
    }

    private companion object {
        const val NONCE_BYTES = 32
        const val TLS_PIN_BYTES = 32
        const val SIGNATURE_BYTES = 64
        const val SECRET_BYTES = 32
    }
}
