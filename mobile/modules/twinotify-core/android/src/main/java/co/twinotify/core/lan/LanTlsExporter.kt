package co.twinotify.core.lan

/** Portable TLS channel binding, selected only through authenticated TLS ALPN. */
internal object LanTlsExporter {
    const val ALPN = "twinotify-lan/2"
    const val LABEL = "EXPORTER-twinotify-lan-v1"

    fun context(export: () -> ByteArray?): ByteArray {
        val material = export()
        if (material == null || material.size != 32) {
            throw LanConnectionException(LanConnectionFailure.TLS_FAILED)
        }
        return material.copyOf()
    }
}
