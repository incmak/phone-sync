package co.twinotify.core.pairing

internal object PairConfirmation {
    private const val RESPONDER_DOMAIN = "twinotify-pair-confirm-b-v1\n"

    fun responderMessage(
        token: String,
        aEncPub: ByteArray,
        aSignPub: ByteArray,
        bEncPub: ByteArray,
        bSignPub: ByteArray,
        initiatorSignature: ByteArray,
    ): ByteArray = RESPONDER_DOMAIN.toByteArray() + token.toByteArray() +
        aEncPub + aSignPub + bEncPub + bSignPub + initiatorSignature
}
