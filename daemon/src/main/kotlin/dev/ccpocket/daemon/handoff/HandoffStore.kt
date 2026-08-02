package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.PocketJson
import dev.ccpocket.protocol.SessionControllerLease
import dev.ccpocket.protocol.SessionHandoff
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Persistence for Session Handoffs (SESSION-HANDOFF.md §9.2): `~/.cc-pocket/handoffs.json` beside
 * identity.json — the same store-directory pattern as [dev.ccpocket.daemon.schedule.ScheduleStore] /
 * [dev.ccpocket.daemon.bridge.BridgeStore] — holding both the [SessionHandoff] entities AND their
 * [SessionControllerLease]s, so a daemon restart recovers non-terminal handoffs and the exclusive
 * controller truth together (§5.4). Written 0600 where the filesystem supports it (briefs/results are
 * collaboration content the credential holder must not be able to rewrite, and the lease is an
 * authorization fact — same sensitivity class as bridges.json).
 *
 * DELIBERATELY its own file (never devices.json/bridges.json): a downgraded daemon that predates
 * handoffs simply never loads it — the handoff state fails closed instead of being misread.
 *
 * This class only owns load/persist and the in-memory snapshot; ALL state-machine decisions (legal
 * transitions, CAS accept, expiry) live in [HandoffRegistry], which serializes its mutations and is
 * the only caller. The wire types are reused as the on-disk shape on purpose: the daemon is the
 * single writer, the shape is already additive-with-defaults (its wire-compat tests double as file
 * forward-compat), and PocketJson's ignoreUnknownKeys makes older files load under newer builds.
 */
class HandoffStore private constructor(private val path: File) {

    @kotlinx.serialization.Serializable
    private data class Stored(
        val v: Int = 1,
        val handoffs: List<SessionHandoff> = emptyList(),
        val leases: List<SessionControllerLease> = emptyList(),
    )

    private val lock = Any()
    private var state: Stored = Stored()

    fun handoffs(): List<SessionHandoff> = synchronized(lock) { state.handoffs }

    fun leases(): List<SessionControllerLease> = synchronized(lock) { state.leases }

    fun handoffById(id: String): SessionHandoff? = synchronized(lock) { state.handoffs.firstOrNull { it.id == id } }

    fun leaseOf(sessionId: String): SessionControllerLease? =
        synchronized(lock) { state.leases.firstOrNull { it.sessionId == sessionId } }

    /** Upsert one handoff by id (append when new, replace in place when known) and persist. */
    fun putHandoff(handoff: SessionHandoff) = synchronized(lock) {
        val known = state.handoffs.any { it.id == handoff.id }
        state = state.copy(
            handoffs = if (known) state.handoffs.map { if (it.id == handoff.id) handoff else it }
            else state.handoffs + handoff,
        )
        persist()
    }

    /** Upsert one lease by sessionId and persist. The one-lease-per-session invariant (§5.3 item 1)
     *  is DECIDED in [HandoffRegistry]; the keyed upsert here merely makes the file unable to hold two. */
    fun putLease(lease: SessionControllerLease) = synchronized(lock) {
        state = state.copy(leases = state.leases.filterNot { it.sessionId == lease.sessionId } + lease)
        persist()
    }

    /** Drop the lease for [sessionId] (leaving IN_PROGRESS deletes the lease at once). Returns false
     *  when none was held. */
    fun removeLease(sessionId: String): Boolean = synchronized(lock) {
        if (state.leases.none { it.sessionId == sessionId }) return false
        state = state.copy(leases = state.leases.filterNot { it.sessionId == sessionId })
        persist()
        true
    }

    /** Atomically (one persist) replace BOTH lists — the restart-recovery normalization path, where
     *  several handoffs may settle and orphan leases drop in a single sweep. */
    fun replaceAll(handoffs: List<SessionHandoff>, leases: List<SessionControllerLease>) = synchronized(lock) {
        state = state.copy(handoffs = handoffs, leases = leases)
        persist()
    }

    private fun persist() {
        runCatching {
            path.parentFile?.mkdirs()
            path.writeText(PocketJson.encodeToString(Stored.serializer(), state))
            runCatching { // best-effort 0600 (POSIX only; Windows ACLs inherit the profile dir)
                Files.setPosixFilePermissions(path.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }
    }

    companion object {
        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "handoffs.json")

        /** Load from [path]; a missing or corrupt file yields an empty store (never a crash at boot). */
        fun load(path: File = defaultPath()): HandoffStore = HandoffStore(path).apply {
            if (path.exists()) runCatching { state = PocketJson.decodeFromString(Stored.serializer(), path.readText()) }
        }
    }
}
