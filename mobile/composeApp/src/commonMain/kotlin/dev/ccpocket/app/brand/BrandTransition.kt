package dev.ccpocket.app.brand

/** Capture BEFORE a repository can create device keys/defaults. Never migrates or removes old data. */
class BrandTransition(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    var pending: Boolean
        private set

    init {
        val saved = read(KEY)
        pending = if (saved != null) saved == PENDING else {
            val existing = LEGACY_KEYS.any { !read(it).isNullOrBlank() }
            write(KEY, if (existing) PENDING else DONE)
            existing
        }
    }

    fun dismiss() {
        write(KEY, DONE)
        pending = false
    }

    companion object {
        private const val KEY = "pairlet_brand_notice_v1"
        private const val PENDING = "pending"
        private const val DONE = "done"
        // Device identity exists even for an unpaired old install. Additional keys cover settings-only
        // and older installs. A new Pairlet install records DONE before it writes any of these keys.
        private val LEGACY_KEYS = listOf(
            "device_priv", "device_pub", "paired_daemon", "paired_daemons", "collab_links",
            "privacy_disclosure_accepted", "appearance_theme_mode", "notify_on_complete",
        )
    }
}
