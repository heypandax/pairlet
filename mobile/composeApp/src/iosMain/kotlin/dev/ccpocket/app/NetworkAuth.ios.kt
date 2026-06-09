package dev.ccpocket.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_set_state_changed_handler
import platform.Network.nw_browser_start
import platform.Network.nw_browser_state_failed
import platform.Network.nw_browser_state_waiting
import platform.Network.nw_browser_t
import platform.Network.nw_parameters_create
import platform.Network.nw_parameters_set_include_peer_to_peer
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

// iOS 14+ gates LAN traffic behind the Local Network permission and offers no query API. The
// standard probe: publish a Bonjour service and browse for the same type. The probe itself
// triggers the system dialog on first run; `netServiceDidPublish` (or any browse result) means
// granted, a browser stuck in `waiting` (kDNSServiceErr_PolicyDenied) means denied. Nothing
// resolves until the user answers the dialog, so suspending here is exactly the wait we want.

/** Must be listed under NSBonjourServices in Info.plist or the browse is denied outright. */
private const val BONJOUR_TYPE = "_ccpocket._tcp"

private var granted = false
private val activeProbes = mutableSetOf<LocalNetworkProbe>() // NSNetService.delegate is weak; keep probes alive

actual suspend fun ensureLocalNetworkAccess(url: String): Boolean {
    if (granted || !needsLocalNetworkPermission(hostOf(url))) return true
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            lateinit var probe: LocalNetworkProbe
            probe = LocalNetworkProbe { ok ->
                activeProbes.remove(probe)
                if (ok) granted = true
                if (cont.isActive) cont.resume(ok)
            }
            activeProbes.add(probe)
            probe.start()
            cont.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) {
                    probe.cancel()
                    activeProbes.remove(probe)
                }
            }
        }
    }
}

private fun hostOf(url: String): String {
    val authority = url.substringAfter("://", url).substringBefore('/')
    return if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
    else authority.substringBefore(':')
}

// Loopback never trips the permission (the simulator default); public hosts (a relay) don't either.
private fun needsLocalNetworkPermission(host: String): Boolean {
    if (host.isEmpty() || host == "localhost" || host == "127.0.0.1" || host == "::1") return false
    if (host.endsWith(".local") || !host.contains('.')) return true // mDNS or bare LAN hostname
    val octets = host.split('.')
    if (octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }) {
        val a = octets[0].toInt()
        val b = octets[1].toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
    return false // domain name → assume public
}

private class LocalNetworkProbe(private val onResult: (Boolean) -> Unit) : NSObject(), NSNetServiceDelegateProtocol {
    private var browser: nw_browser_t? = null
    private var service: NSNetService? = null
    private var done = false

    fun start() {
        val parameters = nw_parameters_create()
        nw_parameters_set_include_peer_to_peer(parameters, true)
        val b = nw_browser_create(nw_browse_descriptor_create_bonjour_service(BONJOUR_TYPE, null), parameters)
        browser = b
        nw_browser_set_queue(b, dispatch_get_main_queue())
        nw_browser_set_state_changed_handler(b) { state, _ ->
            if (state == nw_browser_state_waiting || state == nw_browser_state_failed) finish(false)
        }
        nw_browser_set_browse_results_changed_handler(b) { _, _, _ -> finish(true) }
        nw_browser_start(b)

        service = NSNetService(domain = "local.", type = "$BONJOUR_TYPE.", name = "cc-pocket-preflight", port = 9776).also {
            it.delegate = this
            it.publish()
        }
    }

    override fun netServiceDidPublish(sender: NSNetService) = finish(true)

    fun cancel() = tearDown()

    private fun finish(ok: Boolean) {
        if (done) return
        tearDown()
        onResult(ok)
    }

    private fun tearDown() {
        done = true
        browser?.let { nw_browser_cancel(it) }
        browser = null
        service?.let { it.stop(); it.delegate = null }
        service = null
    }
}
