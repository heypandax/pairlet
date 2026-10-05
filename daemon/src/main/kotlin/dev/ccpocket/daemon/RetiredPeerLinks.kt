package dev.ccpocket.daemon

import dev.ccpocket.daemon.peer.PeerLinkStore
import dev.ccpocket.daemon.util.logger
import java.io.File

/**
 * The peer links review requests dialled out with (retired 2026-10). Each one holds a relay credential in a
 * colleague's account plus this link's private key, in `peer-links.json` / `peer-link-secrets.json`.
 * Nothing dials them any more, so the key material goes: every link is removed through [PeerLinkStore.remove]
 * (secret first, then the public row is kept as a `removed` record), and the files themselves stay.
 *
 * The protocol has no way for a device to revoke ITSELF at someone else's relay, so the credential stays valid
 * there until that colleague's daemon drops it — which it does once it runs a version that retires
 * collaborator credentials too.
 *
 * Opened ONLY by these two literal names. Execution links live in `execution-links.json` /
 * `execution-link-secrets.json`, files this code never names, never lists and never opens.
 */
internal object RetiredPeerLinks {
    const val PUBLIC_FILE = "peer-links.json"
    const val SECRET_FILE = "peer-link-secrets.json"

    private val log = logger("RetiredPeerLinks")

    /** Remove the key material of every review peer link under [dir]. Returns how many links lost it. Never
     *  throws: a store that cannot be read or written is logged and retried on the next start. */
    fun clear(dir: File): Int {
        val public = File(dir, PUBLIC_FILE)
        val secret = File(dir, SECRET_FILE)
        if (!public.exists() && !secret.exists()) return 0
        val store = runCatching { PeerLinkStore.load(public, secret) }.getOrElse {
            log.warn("review peer links unreadable (${it.message}) — left as they are")
            return 0
        }
        var cleared = 0
        for (link in store.all()) {
            if (link.removed && store.secretOf(link.id) == null) continue
            when (store.remove(link.id)) {
                is PeerLinkStore.RemoveResult.Ok -> cleared++
                PeerLinkStore.RemoveResult.PersistFailed -> log.warn("review peer link ${link.id} could not be cleared — retrying next start")
                PeerLinkStore.RemoveResult.NotFound -> Unit
            }
        }
        if (cleared > 0) log.info("cleared the key material of $cleared retired review peer link(s)")
        return cleared
    }
}
