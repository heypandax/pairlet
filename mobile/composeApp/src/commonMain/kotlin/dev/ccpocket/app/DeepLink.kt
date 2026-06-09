package dev.ccpocket.app

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A pairing URL delivered by the OS via the `ccpocket://` scheme — e.g. the user scanned the QR
 * shown by `cc-pocket pair` with their system Camera. The platform entry point calls [handle];
 * the Compose root observes [pending] and pairs.
 */
object DeepLink {
    val pending = MutableStateFlow<String?>(null)
    fun handle(url: String) { pending.value = url }
}
