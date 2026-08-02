package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateMapOf
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.Pairing
import dev.ccpocket.protocol.SessionHandoff
import kotlinx.coroutines.CoroutineScope

/**
 * Keeps one live link per Collaborator Link (SESSION-HANDOFF.md §4.1) — the offer inbox.
 *
 * A Collaborator Link is NOT a computer, so it must not go through [FleetCoordinator]: the fleet exists to
 * keep every machine you control live, its satellites are spawned from the pairing list, and its whole
 * read-model (directories, running sessions, per-machine approvals) assumes an owner credential. Threading
 * contacts through it would put colleagues in the machine switcher and hang each of them on the directory
 * list they are refused.
 *
 * What it DOES reuse is the connection stack: each link is an ordinary [PocketRepository] pinned to its
 * binding, so relay dialing, the Noise handshake, direct-LAN first, the backoff ladder, the deaf-link
 * self-heal and the foreground reconnect all come for free. The repository recognises the binding's
 * COLLABORATOR role and switches itself into inbox mode (§3.2.3): unfiltered `ListHandoffs()` on connect,
 * `HandoffListing` as the readiness proof, no directory wait, no "computer offline".
 *
 * The inbox is deliberately always-on while the app is: an offer is a "someone is waiting on you" event,
 * and the design's offline story (a content-free push, §3.4) only wakes the app — the app still has to be
 * connected to see what it was woken for.
 */
class CollaboratorInbox(private val scope: CoroutineScope) {

    /** Live inbox links, keyed by the colleague's accountId. Compose-observable. */
    val links = mutableStateMapOf<String, PocketRepository>()

    /** The repo speaking for [accountId], if its link is up. */
    fun repoFor(accountId: String): PocketRepository? = links[accountId]

    /** Every inbox link, in no particular order. */
    fun repos(): List<PocketRepository> = links.values.toList()

    /**
     * Every WAITING offer addressed to this device, across every contact, newest first. Pure daemon truth:
     * an offer that was declined, cancelled or expired while the app was away simply isn't in the next
     * listing, so nothing has to be locally invalidated (§3.2.8).
     */
    fun offers(): List<IncomingOffer> =
        links.entries
            .flatMap { (id, repo) -> repo.incomingOffers().map { IncomingOffer(id, it, repo) } }
            .sortedByDescending { it.handoff.createdAt }

    /** Re-derive the link set from the stored Collaborator Links. Idempotent. */
    fun sync() {
        val want = Pairing.collaboratorLinks().associateBy { it.accountId }
        links.keys.toList().forEach { id -> if (id !in want) links.remove(id)?.disconnect() }
        want.forEach { (id, binding) -> if (id !in links) links[id] = connect(binding, ticket = null) }
    }

    /** Bring a JUST-redeemed link up without waiting for the next [sync] — the offer that prompted the QR
     *  is usually already waiting on the daemon. [ticket] is the one-time pairing ticket, which doubles as
     *  the PSK on a binding's very first relay connect. */
    fun add(binding: PairedDaemon, ticket: String?) {
        links.remove(binding.accountId)?.disconnect() // a re-scan supersedes the old credential
        links[binding.accountId] = connect(binding, ticket)
    }

    /** Drop a contact's link (and its stored credential). */
    fun remove(accountId: String) {
        links.remove(accountId)?.disconnect()
        Pairing.removeCollaborator(accountId)
    }

    private fun connect(binding: PairedDaemon, ticket: String?): PocketRepository =
        PocketRepository(scope, pinnedTo = binding).also {
            it.armFirstTicket(ticket)
            it.startRelay()
        }

    /**
     * Bring the stored links up. Safe to call once at app root; idempotent afterwards.
     *
     * No collector watches the store: the only two writers are [add] (a fresh redeem, which the repository
     * routes here through its `onCollaboratorLinkAdded` hook) and [remove], and both keep [links] in step
     * synchronously. That is deliberately unlike [FleetCoordinator], whose satellite set has to be re-derived
     * from an observable primary that switches machines under it.
     */
    fun start() {
        if (started) return
        started = true
        sync()
    }

    private var started = false

    /** iOS suspends every socket in the background — fan the foreground reconnect out to the inbox too. */
    fun onAppForeground() = links.values.forEach { it.onAppForeground() }
}

/**
 * One incoming offer plus the link that can answer it. Carrying the repo matters: an offer that arrived on
 * a Collaborator Link must be accepted OVER THAT LINK — the primary computer's credential has no standing
 * in someone else's daemon at all.
 */
data class IncomingOffer(
    val accountId: String,
    val handoff: SessionHandoff,
    val repo: PocketRepository,
)
