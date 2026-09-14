package dev.ccpocket.app.pins

import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.isValidProjectPinToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * Where one pin scope's document lives (issue #362). Deliberately NOT the general SecureStore: that store writes
 * in place and publishes before flushing, so it cannot promise an outbox that survives a crash intact.
 */
interface ProjectPinPersistence {
    fun read(name: String): PinFileRead

    /** Crash-atomic replacement (see each platform actual for exactly what "durable" means there). */
    fun write(name: String, text: String): PinFileWrite

    /** Repeat the durability step an [PinFileWrite.Indeterminate] write could not confirm, then read what is
     *  actually stored. [PinFileRead.Failed] when that step fails again. */
    fun recover(name: String): PinFileRead

    /** Every stored document name, and whether artifacts a previous build set aside for recovery exist. */
    fun list(): PinFileListing
}

sealed interface PinFileRead {
    data object Missing : PinFileRead
    data class Found(val text: String) : PinFileRead
    data class Failed(val reason: String) : PinFileRead
}

sealed interface PinFileWrite {
    /** The whole new document is in place, through every durability step the platform supports. */
    data object Durable : PinFileWrite

    /** Failed before the replacement: the previous document is certainly still in place. */
    data object NotWritten : PinFileWrite

    /** The replacement happened but could not be made durable: either document may be what survives a crash. */
    data object Indeterminate : PinFileWrite
}

sealed interface PinFileListing {
    data class Ready(val names: List<String>, val hasRecoveryArtifacts: Boolean) : PinFileListing
    data object Failed : PinFileListing
}

/**
 * What an error-bearing existence probe (`stat`-style: [status] 0 on success, otherwise the [errno] it set) proves.
 * Only a definite "no such entry" — which also covers a missing ancestor — is [Missing]; permission, I/O and any
 * unrecognised failure are [Failed], so a store that merely cannot be inspected is never mistaken for an empty one.
 * [enoent] is the platform's value, passed in so the rule stays testable off-device.
 */
internal sealed interface PinPathProbe {
    data object Present : PinPathProbe
    data object Missing : PinPathProbe
    data class Failed(val errno: Int) : PinPathProbe

    companion object {
        fun classify(status: Int, errno: Int, enoent: Int): PinPathProbe = when {
            status == 0 -> Present
            errno == enoent -> Missing
            else -> Failed(errno)
        }
    }
}

/** A document read gated on [probe]: only a present file is read. */
internal inline fun readProbed(probe: PinPathProbe, readPresent: () -> PinFileRead): PinFileRead = when (probe) {
    PinPathProbe.Present -> readPresent()
    PinPathProbe.Missing -> PinFileRead.Missing
    is PinPathProbe.Failed -> PinFileRead.Failed("stat errno ${probe.errno}")
}

/** A directory listing gated on [probe]: only a definitely absent directory is an empty store. */
internal inline fun listProbed(probe: PinPathProbe, listPresent: () -> PinFileListing): PinFileListing = when (probe) {
    PinPathProbe.Present -> listPresent()
    PinPathProbe.Missing -> PinFileListing.Ready(emptyList(), hasRecoveryArtifacts = false)
    is PinPathProbe.Failed -> PinFileListing.Failed
}

/** Temporary bridge for callers that only understand success; not for scope internals. */
fun ProjectPinPersistence.writeLegacyBoolean(name: String, text: String): Boolean = write(name, text) == PinFileWrite.Durable

/** The platform's durable pin-document store (app-private data directory). */
expect fun platformProjectPinPersistence(): ProjectPinPersistence

/** A re-entrant mutual-exclusion lock, so every caller in this process that touches one computer's pins —
 *  primary, fleet satellite, pane — is serialized. */
expect class PinLock() {
    fun <T> withLock(block: () -> T): T
}

/** Which document a repository reads its pins from. Only [Owner] syncs; the others stay on this device. */
sealed class PinScopeKey(val storageName: String?, val synced: Boolean) {
    /** A computer this device paired as its owner: one synced document per computer. */
    data class Owner(val accountId: String) : PinScopeKey("owner-" + safePinName(accountId), synced = true)

    /** A folder-share guest binding: pins stay local and never reach, or come from, the owner's list. */
    data class Guest(val accountId: String) : PinScopeKey("guest-" + safePinName(accountId), synced = false)

    /** No binding at all (the plaintext dev connection): local only. */
    data object Unpaired : PinScopeKey("local", synced = false)

    /** The no-pairing demo: in memory only, never written anywhere. */
    data object Demo : PinScopeKey(null, synced = false)

    /** A Collaborator Link inbox: it has no projects, so it has no pins. */
    data object Inbox : PinScopeKey(null, synced = false)

    companion object {
        fun of(binding: PairedDaemon?, demo: Boolean): PinScopeKey = when {
            demo -> Demo
            binding == null -> Unpaired
            binding.role == BindingRole.OWNER -> Owner(binding.accountId)
            binding.role == BindingRole.GUEST -> Guest(binding.accountId)
            else -> Inbox
        }
    }
}

/**
 * Proof that a controller acts for the binding currently installed for [accountId] in this process. [epoch]
 * changes whenever that binding's device or credential is replaced or removed, so an old controller's lease stops
 * being current at that moment. It holds no credential.
 */
data class PinBindingLease(val accountId: String, val deviceId: String, val epoch: Long)

/** The result of one scope transition. */
sealed interface PinUpdate {
    /** The resulting document is in place ([changed] false: nothing needed saving). When the update asked to defer
     *  publication, call [publish] once the caller's own state is latched; otherwise listeners already heard. */
    class Committed internal constructor(val doc: PinStateDoc, val changed: Boolean, private var pending: (() -> Unit)?) : PinUpdate {
        fun publish() {
            val deliver = pending ?: return
            pending = null
            deliver()
        }
    }

    /** The lease is no longer current for this scope: nothing was read, changed or saved. */
    data object StaleLease : PinUpdate

    /** Storage cannot accept changes (unreadable document, or an earlier save's outcome is still unknown). */
    data class Blocked(val issue: PinSyncIssue) : PinUpdate

    /** This save failed before replacing anything: nothing changed. */
    data class NotSaved(val issue: PinSyncIssue) : PinUpdate

    /** This save's outcome is unknown: the previous document stays visible and the scope is blocked until what is
     *  stored has been recovered and read back. */
    data class Indeterminate(val issue: PinSyncIssue) : PinUpdate
}

/**
 * One scope's shared document. Every change is persisted BEFORE it becomes visible in memory; a failed write
 * leaves both the stored and the in-memory state exactly as they were, and a write whose outcome is unknown blocks
 * the scope instead of letting the cached copy overwrite whatever actually survived. Listeners are told the new
 * visible list after the change is in place.
 */
class ProjectPinScope internal constructor(
    val key: PinScopeKey,
    private val lock: PinLock,
    private val persistence: ProjectPinPersistence,
    private val newToken: () -> String,
    private val leaseIsCurrent: (PinBindingLease) -> Boolean,
) {
    private enum class Storage { READY, READ_BLOCKED, INDETERMINATE }

    private var doc: PinStateDoc = ProjectPinReducer.fresh(newToken())
    private var storage = Storage.READY
    private val listeners = mutableListOf<(List<String>) -> Unit>()

    /** The lease whose transition created [PinStateDoc.resolutionPending]; lookup work is nobody else's. */
    private var lookupOwner: PinBindingLease? = null

    /** A refused reply's reason, shared by every controller of this scope until an explicit trigger. */
    private var flushBlocked: PinSyncIssue? = null

    /** Why loading the stored document could not produce a writable scope; reported by the first repository that
     *  binds. Null once storage is healthy. */
    var loadIssue: PinSyncIssue? = null
        private set

    val synced: Boolean get() = key.synced

    fun document(): PinStateDoc = lock.withLock { doc }

    fun visible(): List<String> = lock.withLock { ProjectPinReducer.visible(doc, key.synced) }

    /** Null while this scope can take changes; otherwise why it cannot. */
    fun storageIssue(): PinSyncIssue? = lock.withLock {
        when (storage) {
            Storage.READY -> null
            Storage.READ_BLOCKED -> loadIssue ?: PinSyncIssue.StorageFailed
            Storage.INDETERMINATE -> PinSyncIssue.StorageFailed
        }
    }

    /**
     * A controller's change, checked against [lease] under the same lock as the storage transition itself, so a
     * controller whose binding was replaced can never slip an update in after the replacement. With
     * [deferPublish] listeners hear about the change only when the caller calls [PinUpdate.Committed.publish].
     */
    fun updateFor(lease: PinBindingLease, deferPublish: Boolean = false, change: (PinStateDoc) -> PinStateDoc): PinUpdate =
        commit(lease, gated = true, deferPublish, change)

    /** Ungated change for trusted callers (migration, local-only scopes, and the pre-lease link until it moves to
     *  [updateFor]). True when the resulting document is in place, including "nothing changed". */
    fun update(change: (PinStateDoc) -> PinStateDoc): Boolean =
        commit(lease = null, gated = false, deferPublish = false, change) is PinUpdate.Committed

    internal fun updateTrusted(change: (PinStateDoc) -> PinStateDoc): PinUpdate =
        commit(lease = null, gated = false, deferPublish = false, change)

    // ---- the shared flush latch ----

    /** Stop every controller of this scope from sending until an explicit trigger. Set it BEFORE publishing the
     *  transition that caused it (see [updateFor]'s deferPublish). */
    fun setFlushBlocked(reason: PinSyncIssue) = lock.withLock { flushBlocked = reason }

    /** A new explicit user edit, an accepted current fetch, or a new connection — never mere listener churn. */
    fun clearFlushBlockedOnExplicitTrigger() = lock.withLock { flushBlocked = null }

    fun flushBlockedReason(): PinSyncIssue? = lock.withLock { flushBlocked }

    /** True when [lease] may send this scope's pending operations now. Storage trouble overrides everything. */
    fun canFlush(lease: PinBindingLease): Boolean = lock.withLock {
        leaseMatches(lease) && storage == Storage.READY && flushBlocked == null &&
            doc.stream.deviceId == lease.deviceId && doc.stream.incarnation != null
    }

    /** The lookup-only batch [lease] may send now — empty for anyone but the binding that created that work. */
    fun lookupBatchFor(lease: PinBindingLease): List<ProjectPinOp> = lock.withLock {
        if (!canFlush(lease) || lookupOwner != lease) emptyList() else ProjectPinReducer.lookupBatch(doc)
    }

    /** Observe document changes (with the resulting visible list); returns the unsubscribe action. */
    fun addListener(listener: (List<String>) -> Unit): () -> Unit {
        lock.withLock { listeners += listener }
        return { removeListener(listener) }
    }

    fun removeListener(listener: (List<String>) -> Unit) {
        lock.withLock { listeners.remove(listener) }
    }

    internal fun listenerCount(): Int = lock.withLock { listeners.size }

    private fun leaseMatches(lease: PinBindingLease) = key == PinScopeKey.Owner(lease.accountId) && leaseIsCurrent(lease)

    private fun commit(
        lease: PinBindingLease?,
        gated: Boolean,
        deferPublish: Boolean,
        change: (PinStateDoc) -> PinStateDoc,
    ): PinUpdate {
        var reloaded: Pair<List<(List<String>) -> Unit>, List<String>>? = null
        val result = lock.withLock<PinUpdate> {
            if (gated && !leaseMatches(lease!!)) return@withLock PinUpdate.StaleLease
            val before = doc
            when (storage) {
                Storage.READ_BLOCKED -> load()
                Storage.INDETERMINATE -> recoverIndeterminate()
                Storage.READY -> {}
            }
            if (doc != before) reloaded = listeners.toList() to ProjectPinReducer.visible(doc, key.synced)
            if (storage != Storage.READY) return@withLock PinUpdate.Blocked(storageIssue() ?: PinSyncIssue.StorageFailed)

            // lookup work belongs to the lease that created it; anyone else starts without it
            val base = if (doc.resolutionPending.isEmpty() || lookupOwner == lease) doc else doc.copy(resolutionPending = emptyList())
            var next = change(base)
            if (lease == null && next.resolutionPending.isNotEmpty() && next.resolutionPending != base.resolutionPending) {
                next = next.copy(resolutionPending = emptyList()) // no provable originating binding
            }
            if (next == doc) return@withLock PinUpdate.Committed(doc, changed = false, pending = null)
            val name = key.storageName
            val written = if (name == null) PinFileWrite.Durable else persistence.write(name, ProjectPinCodec.encode(next))
            when (written) {
                PinFileWrite.NotWritten -> PinUpdate.NotSaved(PinSyncIssue.StorageFailed)
                PinFileWrite.Indeterminate -> {
                    storage = Storage.INDETERMINATE
                    PinUpdate.Indeterminate(PinSyncIssue.StorageFailed)
                }
                PinFileWrite.Durable -> {
                    doc = next
                    lookupOwner = if (next.resolutionPending.isEmpty()) null else lease
                    // every persisted change, not only visible ones: another holder may have work to flush
                    val targets = listeners.toList()
                    val visible = ProjectPinReducer.visible(next, key.synced)
                    reloaded = null // superseded by this publication
                    PinUpdate.Committed(next, changed = true, pending = { targets.forEach { it(visible) } })
                }
            }
        }
        reloaded?.let { (targets, visible) -> targets.forEach { it(visible) } }
        if (result is PinUpdate.Committed && !deferPublish) result.publish()
        return result
    }

    /** Read (after the durability step) what is stored. Anything this build cannot fully read blocks the scope and
     *  is left exactly as it is — never moved aside, never overwritten. */
    internal fun load() {
        val name = key.storageName ?: return
        when (val read = persistence.recover(name)) {
            PinFileRead.Missing -> adopt(ProjectPinReducer.fresh(newToken()))
            is PinFileRead.Failed -> block(PinSyncIssue.StorageFailed)
            is PinFileRead.Found -> when (val decoded = ProjectPinCodec.decode(read.text)) {
                is ProjectPinCodec.Decoded.Ok -> adopt(decoded.doc)
                ProjectPinCodec.Decoded.Newer -> block(PinSyncIssue.StorageFailed)
                ProjectPinCodec.Decoded.Corrupt -> block(PinSyncIssue.LocalStateReset)
            }
        }
    }

    /** After an indeterminate save: adopt what storage really holds, or stay blocked showing the previous doc. */
    private fun recoverIndeterminate() {
        val name = key.storageName ?: return adopt(doc)
        when (val read = persistence.recover(name)) {
            PinFileRead.Missing -> adopt(ProjectPinReducer.fresh(newToken()))
            is PinFileRead.Found -> (ProjectPinCodec.decode(read.text) as? ProjectPinCodec.Decoded.Ok)?.let { adopt(it.doc) }
            is PinFileRead.Failed -> {}
        }
    }

    private fun adopt(loaded: PinStateDoc) {
        // a loaded lookup queue has no provable originating credential: it is dropped as knowledge, never as intent
        doc = if (loaded.resolutionPending.isEmpty()) loaded else loaded.copy(resolutionPending = emptyList())
        lookupOwner = null
        storage = Storage.READY
        loadIssue = null
    }

    private fun block(issue: PinSyncIssue) {
        if (storage == Storage.READY && loadIssue == null) doc = ProjectPinReducer.fresh(newToken())
        storage = Storage.READ_BLOCKED
        loadIssue = issue
    }
}

/**
 * Process-wide owner of every pin scope: one [ProjectPinScope] per key, so two repositories bound to the same
 * computer share one document and one sequence counter instead of racing two copies. Also the in-process
 * authority on which binding currently speaks for each computer, and the one-time migration of the pre-#362
 * device-global pin list.
 */
class ProjectPinRegistry(
    private val persistence: ProjectPinPersistence,
    internal val newToken: () -> String = ::randomPinToken,
) {
    private val lock = PinLock()
    private val scopes = HashMap<PinScopeKey, ProjectPinScope>()
    private val migratedLegacy = HashSet<String>()

    /** Installed binding identity per account. The credential is compared here only — never logged or persisted. */
    private class Authority(val deviceId: String, val credential: String, val epoch: Long) {
        fun sameIdentity(binding: PairedDaemon) = binding.deviceId == deviceId && binding.credential == credential
        override fun toString() = "Authority(epoch=$epoch)"
    }

    private val authorities = HashMap<String, Authority>()
    private var authorityInitialized = false
    private var lastEpoch = 0L

    /** Why the last legacy migration attempt could not decide, if it could not. */
    var migrationIssue: PinSyncIssue? = null
        private set

    fun scope(key: PinScopeKey): ProjectPinScope = lock.withLock {
        scopes.getOrPut(key) { ProjectPinScope(key, lock, persistence, newToken, ::isCurrent).also { it.load() } }
    }

    /** Remove a torn-down holder's [listener] from every scope — safe from any thread. */
    fun removeListener(listener: (List<String>) -> Unit) {
        lock.withLock { scopes.values.forEach { it.removeListener(listener) } }
    }

    // ---- binding authority ----

    /** Install authority from the canonical pairing store, once per registry. Later calls do nothing. */
    fun initializeAuthorityOnce(readCurrentBindings: () -> List<PairedDaemon>) = lock.withLock {
        if (authorityInitialized) return@withLock
        install(readCurrentBindings())
        authorityInitialized = true
    }

    /**
     * Only for the code path that has just replaced or removed a pairing credential: re-read CURRENT pairing (call
     * `Pairing.loadAll()` in [readCurrentBindings], never a mirror or an earlier result) and install it. A replaced
     * or removed identity gets a new epoch, which retires every lease issued for the old one before this returns.
     */
    fun refreshAfterPairingChange(readCurrentBindings: () -> List<PairedDaemon>) = lock.withLock {
        install(readCurrentBindings())
        authorityInitialized = true
    }

    /** A lease for [binding] when it is exactly the installed identity; null for anything else. Never installs. */
    fun acquireLease(binding: PairedDaemon): PinBindingLease? = lock.withLock {
        if (binding.role != BindingRole.OWNER) return@withLock null
        val authority = authorities[binding.accountId] ?: return@withLock null
        if (!authority.sameIdentity(binding)) null else PinBindingLease(binding.accountId, binding.deviceId, authority.epoch)
    }

    fun isCurrent(lease: PinBindingLease): Boolean = lock.withLock {
        authorities[lease.accountId]?.let { it.epoch == lease.epoch && it.deviceId == lease.deviceId } == true
    }

    private fun install(bindings: List<PairedDaemon>) {
        // only an OWNER binding syncs, so only it holds authority: a guest sibling on the same account keeps its pins
        // local and never disturbs the owner. Two OWNER identities for one account speak for nothing: no lease
        // rather than a guess.
        val current = bindings.filter { it.role == BindingRole.OWNER }.groupBy { it.accountId }
            .filterValues { same -> same.all { it.deviceId == same[0].deviceId && it.credential == same[0].credential } }
            .mapValues { it.value[0] }
        authorities.keys.retainAll(current.keys)
        current.forEach { (account, binding) ->
            val existing = authorities[account]
            if (existing == null || !existing.sameIdentity(binding)) {
                authorities[account] = Authority(binding.deviceId, binding.credential, ++lastEpoch)
            }
        }
    }

    // ---- legacy migration ----

    /**
     * Land the legacy device-global list ([legacyText], newline-joined, newest first) once. The legacy key itself
     * is never modified — it stays as the recoverable backup — and the marker is written in the SAME document
     * change as the complete list, so neither a replay nor a crash can lose or duplicate a migrated pin:
     *  - exactly one OWNER computer: claimed there; as much as its outbox holds becomes operations now, the rest
     *    follows as acknowledgements free room;
     *  - several: kept as a local fallback on [active] (the computer active right now) only, to be claimed path
     *    by path when that computer's own listing proves a path is its;
     *  - otherwise (no owner, or the active binding is not an owner): not yet — nothing is guessed;
     *  - and never while any stored pin state could hold the marker but cannot be read ([migrationIssue]).
     */
    fun migrateLegacyIfNeeded(legacyText: String?, bindings: List<PairedDaemon>, active: PairedDaemon?) {
        val paths = legacyText?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        if (paths.isEmpty()) return
        val digest = legacyDigest(legacyText!!)
        lock.withLock { migrateLocked(paths, digest, bindings, active) }
    }

    private enum class Evidence { LANDED, NONE, UNCERTAIN }

    private fun migrateLocked(paths: List<String>, digest: String, bindings: List<PairedDaemon>, active: PairedDaemon?) {
        if (digest in migratedLegacy) return
        when (legacyEvidence(digest)) {
            Evidence.LANDED -> { migratedLegacy += digest; migrationIssue = null; return }
            Evidence.UNCERTAIN -> { migrationIssue = PinSyncIssue.MigrationUncertain; return }
            Evidence.NONE -> migrationIssue = null
        }
        val owners = bindings.filter { it.role == BindingRole.OWNER }.map { it.accountId }.distinct()
        val (target, claim) = when {
            owners.size == 1 -> PinScopeKey.Owner(owners.single()) to true
            owners.size > 1 && active?.role == BindingRole.OWNER -> PinScopeKey.Owner(active.accountId) to false
            else -> return
        }
        val landed = scope(target).updateTrusted { doc ->
            if (claim) ProjectPinReducer.claimLegacy(doc, paths, digest)
            else ProjectPinReducer.holdLegacyFallback(doc, paths, digest)
        }
        if (landed is PinUpdate.Committed) migratedLegacy += digest
    }

    /** Whether some scope already holds [digest], none certainly does, or stored state that might hold it cannot be
     *  read (a failed listing, an undecodable or unreadable document, or an artifact set aside for recovery). */
    private fun legacyEvidence(digest: String): Evidence {
        if (scopes.values.any { it.storageIssue() == null && it.document().legacy?.digest == digest }) return Evidence.LANDED
        val listing = persistence.list() as? PinFileListing.Ready ?: return Evidence.UNCERTAIN
        var uncertain = listing.hasRecoveryArtifacts
        for (name in listing.names) {
            when (val read = persistence.read(name)) {
                is PinFileRead.Found -> when (val decoded = ProjectPinCodec.decode(read.text)) {
                    is ProjectPinCodec.Decoded.Ok -> if (decoded.doc.legacy?.digest == digest) return Evidence.LANDED
                    else -> uncertain = true
                }
                is PinFileRead.Failed -> uncertain = true
                PinFileRead.Missing -> {}
            }
        }
        return if (uncertain) Evidence.UNCERTAIN else Evidence.NONE
    }

    companion object {
        /** The app's registry over platform storage; tests construct their own over a temp directory. */
        val shared: ProjectPinRegistry by lazy { ProjectPinRegistry(platformProjectPinPersistence()) }
    }
}

/** Versioned JSON for [PinStateDoc]. A document that breaks the outbox invariants is corrupt, not "mostly fine":
 *  resending from it could open a gap or reuse a sequence number. */
object ProjectPinCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    sealed interface Decoded {
        data class Ok(val doc: PinStateDoc) : Decoded
        data object Newer : Decoded
        data object Corrupt : Decoded
    }

    fun encode(doc: PinStateDoc): String = json.encodeToString(PinStateDoc.serializer(), doc)

    fun decode(text: String): Decoded {
        val version = runCatching { json.parseToJsonElement(text).jsonObject["v"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (version != null && version > PinStateDoc.VERSION) return Decoded.Newer
        val doc = runCatching { json.decodeFromString(PinStateDoc.serializer(), text) }.getOrNull() ?: return Decoded.Corrupt
        return if (consistent(doc)) Decoded.Ok(doc) else Decoded.Corrupt
    }

    private fun consistent(doc: PinStateDoc): Boolean {
        val s = doc.stream
        if (doc.v != PinStateDoc.VERSION || !isValidProjectPinToken(s.id)) return false
        if (s.ackedSeq < 0 || s.nextSeq < 1 || s.ackedSeq > s.nextSeq - 1 || s.sentSeq < s.ackedSeq || s.sentSeq > s.nextSeq - 1) return false
        if (doc.pending.size.toLong() != s.nextSeq - 1 - s.ackedSeq) return false
        if (!doc.pending.withIndex().all { (i, op) -> op.seq == s.ackedSeq + 1 + i }) return false
        val lookup = doc.resolutionPending
        if (lookup.size > ProjectPinReducer.MAX_LOOKUP || lookup.any { it.seq < 1 || it.seq > s.ackedSeq }) return false
        if (lookup.distinctBy { it.seq }.size != lookup.size) return false
        return doc.legacy?.let { l -> l.eligible.all { it in l.fallback } } ?: true
    }
}

/** 128 random bits as hex: an idempotency / subscription identifier, never a credential. */
fun randomPinToken(): String = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** A file-name-safe rendering of an account id; anything unusual (or case-sensitive) gets a hash suffix so two
 *  ids can never share a document on a case-insensitive filesystem. */
internal fun safePinName(raw: String): String {
    val cleaned = raw.filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }.take(64)
    return if (cleaned == raw && cleaned.isNotEmpty()) cleaned else "${cleaned.take(40)}-${fnv64Hex(raw)}"
}

internal fun legacyDigest(text: String): String = fnv64Hex(text)

private fun fnv64Hex(text: String): String {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
    for (b in text.encodeToByteArray()) {
        hash = hash xor (b.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
