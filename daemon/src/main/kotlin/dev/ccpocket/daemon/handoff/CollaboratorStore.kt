package dev.ccpocket.daemon.handoff

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.protocol.Collaborator
import dev.ccpocket.protocol.PocketJson
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Persistence for Collaborator Link CONTACT rows (SESSION-HANDOFF.md §4.1): `~/.cc-pocket/collaborators.json`
 * beside handoffs.json — the same store-directory pattern as [HandoffStore]. Holds the wire
 * [Collaborator] entities (label, direction, fingerprint, handoff stats, removed flag); the E2E KEY
 * MATERIAL for the same deviceIds lives separately in collaborator-keys.json
 * ([dev.ccpocket.daemon.bridge.CollaboratorKeyStore], owned by BridgeRegistry) so the key follows the
 * one binding chain every restricted credential uses, and this file stays pure contact metadata.
 *
 * REMOVED rows are kept (flagged), never deleted: past handoffs reference the contact by label, and the
 * client renders removed contacts as a terminal group. Removing a contact kills its CREDENTIAL (the
 * BridgeRegistry entry + relay revoke), which is the actual security boundary — this ledger is display
 * truth only and never an authorization input.
 *
 * Written 0600 where the filesystem supports it (contact labels + fingerprints are the owner's private
 * address book). DELIBERATELY its own file: a downgraded daemon simply never loads it.
 *
 * Like [HandoffStore], this class only owns load/persist and the in-memory snapshot; all decisions
 * (what a remove means, when stats bump) live in [CollaboratorService], the only caller.
 */
class CollaboratorStore private constructor(private val path: File) {

    @kotlinx.serialization.Serializable
    private data class Stored(
        val v: Int = 1,
        val collaborators: List<Collaborator> = emptyList(),
    )

    private val lock = Any()
    private var state: Stored = Stored()

    fun all(): List<Collaborator> = synchronized(lock) { state.collaborators }

    fun byId(deviceId: String): Collaborator? =
        synchronized(lock) { state.collaborators.firstOrNull { it.deviceId == deviceId } }

    /** Upsert one contact by deviceId (append when new, replace in place when known) and persist. */
    fun upsert(row: Collaborator) = synchronized(lock) {
        val known = state.collaborators.any { it.deviceId == row.deviceId }
        state = state.copy(
            collaborators = if (known) state.collaborators.map { if (it.deviceId == row.deviceId) row else it }
            else state.collaborators + row,
        )
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
        fun defaultPath(): File = File(Identity.defaultPath().parentFile, "collaborators.json")

        /** Load from [path]; a missing or corrupt file yields an empty store (never a crash at boot). */
        fun load(path: File = defaultPath()): CollaboratorStore = CollaboratorStore(path).apply {
            if (path.exists()) runCatching { state = PocketJson.decodeFromString(Stored.serializer(), path.readText()) }
        }
    }
}
