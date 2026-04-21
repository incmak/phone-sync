package co.twinotify.core.listener

object CanonIdBuilder {
    /**
     * canon_id = "{originDevice}:{package}:{id}:{tag_or_empty}"
     * Spec §3.1: stable cross-device identity. Last-write-wins semantics for id=0 collisions.
     */
    fun build(originDevice: String, pkg: String, id: Int, tag: String?): String =
        "$originDevice:$pkg:$id:${tag ?: ""}"
}
