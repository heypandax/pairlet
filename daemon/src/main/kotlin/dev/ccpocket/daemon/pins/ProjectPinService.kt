package dev.ccpocket.daemon.pins

import dev.ccpocket.daemon.disk.ProjectPaths
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.PROJECT_PINS_MAX
import dev.ccpocket.protocol.PROJECT_PINS_MAX_OPS
import dev.ccpocket.protocol.ProjectPin
import dev.ccpocket.protocol.ProjectPinErrors
import dev.ccpocket.protocol.ProjectPinOp
import dev.ccpocket.protocol.ProjectPinResolution
import dev.ccpocket.protocol.ProjectPinsSnapshot
import dev.ccpocket.protocol.ProjectPinsState
import dev.ccpocket.protocol.SyncProjectPins
import dev.ccpocket.protocol.isValidProjectPinPath
import dev.ccpocket.protocol.isValidProjectPinToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The authoritative project-pin list of THIS computer (issue #362), shared by every owner client.
 *
 * Commit semantics: each [SyncProjectPins] carries contiguous SET operations of one client stream, queued against
 * one store incarnation. Sequence numbers at or below the durable cursor of (transport-authenticated deviceId,
 * streamId) are deduplicated; new ones must start exactly one past it, are validated as a whole, applied in
 * order, and persisted together with the advanced cursor BEFORE anything is acknowledged or broadcast. A refused
 * batch commits nothing. For the same canonical project, the later commit wins — daemon order, never client clocks.
 *
 * Read/modify/write is serialized by [mutex]; fan-out runs after leaving it, one conflating drain per
 * subscriber with a bounded delivery, so a slow or dead sibling can delay only itself.
 */
class ProjectPinService(
    private val store: ProjectPinStore,
    private val scope: CoroutineScope,
    /** Canonical identity of a path on this machine, resolved afresh by every request. Injectable so tests can
     *  change aliases on purpose. */
    private val keyOf: (String) -> String = ::canonicalPinKey,
    /** Whether a device may still send frames. Only consulted to reclaim cursors at [maxCursors]: a cursor of
     *  a device that can never authenticate again is the one kind of dedup state that is safe to drop. */
    private val deviceStillPaired: (String) -> Boolean = { true },
    private val maxCursors: Int = MAX_CURSORS,
    private val deliveryTimeoutMs: Long = DELIVERY_TIMEOUT_MS,
    private val newIncarnation: () -> String = ::randomToken,
) {
    private val log = logger("ProjectPins")

    /** The reply to the requester, plus the snapshot to broadcast when the list itself changed. */
    data class Outcome(val reply: ProjectPinsState, val changed: ProjectPinsSnapshot?)

    private val mutex = Mutex()

    /** Last state known to be on disk. Only this process writes the file (the daemon is a singleton). */
    private var cached: PinStoreState? = null

    /** Set for the rest of this instance once a write's stored outcome became unknown: no state held here can be
     *  trusted any more, and replacing the file again could overwrite a commit this instance cannot see. Only a
     *  restart — a fresh instance reading the whole file — recovers. Guarded by [mutex]. */
    private var persistenceBlocked = false

    /**
     * Commit [request] for the transport-authenticated [deviceId]. [isAdmitted] is the transport's word that the
     * connection the request arrived on may still sync (for a batch: under the subscription it names). It is
     * asked under the store lock, before the store is read, so a request that waited for the lock cannot
     * outlive a retire or a revoke that happened meanwhile. It may take a transport lock; no transport may call
     * this while holding one.
     */
    suspend fun sync(deviceId: String, request: SyncProjectPins, isAdmitted: suspend () -> Boolean = { true }): Outcome {
        val subscription = request.subscriptionId.takeIf { isValidProjectPinToken(it) }.orEmpty()
        val requestId = request.requestId.takeIf { isValidProjectPinToken(it, minChars = 1) }
        val streamId = request.streamId.takeIf { isValidProjectPinToken(it) }
        fun refuse(code: String, message: String, readable: PinStoreState? = null, ack: Long? = null) = Outcome(
            ProjectPinsState(
                subscriptionId = subscription, requestId = requestId, streamId = streamId,
                ackSeq = ack, snapshot = readable?.let(::snapshotOf), error = code, message = message,
            ),
            changed = null,
        )

        // ---- structural validation: nothing below depends on the store ----
        if (deviceId.isBlank() || subscription.isEmpty() || requestId == null || streamId == null) {
            return refuse(ProjectPinErrors.INVALID_REQUEST, "malformed pin sync identifiers")
        }
        val ops = request.ops
        if (ops.size > PROJECT_PINS_MAX_OPS) {
            return refuse(ProjectPinErrors.INVALID_REQUEST, "at most $PROJECT_PINS_MAX_OPS operations per request")
        }
        if (ops.any { !isValidProjectPinPath(it.path) }) {
            return refuse(ProjectPinErrors.INVALID_PATH, "a pinned path is empty, too long or contains control characters")
        }
        // positive and contiguous — without letting Long.MAX_VALUE + 1 wrap around into a "successor"
        if (ops.isNotEmpty() && (ops.first().seq < 1 || ops.zipWithNext().any { (a, b) -> a.seq == Long.MAX_VALUE || b.seq != a.seq + 1 })) {
            return refuse(ProjectPinErrors.INVALID_REQUEST, "operation sequence numbers must be contiguous and start at 1 or later")
        }

        mutex.withLock {
            if (!isAdmitted()) {
                return refuse(ProjectPinErrors.SUBSCRIPTION_STALE, "this connection is not the current pin subscription of its device")
            }
            if (persistenceBlocked) return refuse(ProjectPinErrors.STORE_UNAVAILABLE, BLOCKED_MESSAGE)
            val current = when (val loaded = load()) {
                is Load.Ok -> loaded.state
                is Load.Failed -> return refuse(loaded.code, loaded.message)
            }
            // a batch belongs to the incarnation it was queued against: under any other, its sequence numbers would
            // be judged by cursors that never saw them. Refused before any reconcile, cursor or write.
            if (ops.isNotEmpty() && request.expectedIncarnation != current.incarnation) {
                return refuse(
                    ProjectPinErrors.INCARNATION_MISMATCH,
                    "the project pin list on this computer was reset; fetch it before sending changes",
                    current,
                )
            }

            val cursorIndex = current.cursors.indexOfFirst { it.deviceId == deviceId && it.streamId == streamId }
            val high = if (cursorIndex >= 0) current.cursors[cursorIndex].seq else 0L
            val fresh = ops.filter { it.seq > high }
            var cursors = current.cursors
            if (fresh.isNotEmpty()) {
                if (fresh.first().seq != high + 1) {
                    return refuse(
                        ProjectPinErrors.SEQUENCE_GAP,
                        "operation ${fresh.first().seq} does not follow committed operation $high of this stream",
                        current, high,
                    )
                }
                val last = fresh.last().seq
                if (cursorIndex >= 0) {
                    cursors = cursors.toMutableList().also { it[cursorIndex] = it[cursorIndex].copy(seq = last) }
                } else {
                    if (cursors.size >= maxCursors) {
                        cursors = cursors.filter { it.deviceId == deviceId || deviceStillPaired(it.deviceId) }
                    }
                    if (cursors.size >= maxCursors) {
                        return refuse(ProjectPinErrors.STREAM_CAPACITY, "this computer tracks too many pin streams", current, high)
                    }
                    cursors = cursors + StoredCursor(deviceId, streamId, last)
                }
            }

            // Every path this transaction touches is resolved ONCE: the reconcile, the applied operations and the
            // reported resolutions all describe the same view of the filesystem. At most one row per operation.
            val resolved = HashMap<String, String>()
            val keyNow = { path: String -> resolved.getOrPut(path) { keyFor(path) } }
            val rows = ops.map { ResolvedOp(it, keyNow(it.path)) }

            // an alias this machine now resolves differently collapses into one row BEFORE applying anything
            var pins = reconcile(current.pins, keyNow)
            var listChanged = pins != current.pins
            if (fresh.isNotEmpty()) {
                val applied = apply(pins, rows.filter { it.op.seq > high })
                if (applied.size > PROJECT_PINS_MAX) {
                    return refuse(ProjectPinErrors.CAPACITY, "at most $PROJECT_PINS_MAX projects can be pinned", current, high)
                }
                if (applied != pins) { pins = applied; listChanged = true }
            }
            if (listChanged && current.revision == Long.MAX_VALUE) {
                return refuse(ProjectPinErrors.STORE_UNAVAILABLE, "the project pin list on this computer cannot change any more", current, high)
            }

            // list, revision and cursors land as one document, or not at all
            val next = current.copy(
                revision = if (listChanged) current.revision + 1 else current.revision,
                pins = pins,
                cursors = cursors,
            )
            if (next != current) {
                when (store.write(next)) {
                    PinStoreWrite.Durable -> cached = next
                    // nothing acknowledged, nothing broadcast, memory still the last durable state: a retry is safe
                    PinStoreWrite.UnchangedFailure ->
                        return refuse(ProjectPinErrors.STORE_UNAVAILABLE, "could not save project pins on this computer")
                    PinStoreWrite.IndeterminateFailure ->
                        return refuse(ProjectPinErrors.STORE_UNAVAILABLE, blockPersistence())
                }
            }
            val snapshot = snapshotOf(next)
            return Outcome(
                ProjectPinsState(
                    subscriptionId = subscription, requestId = requestId, streamId = streamId,
                    ackSeq = fresh.lastOrNull()?.seq ?: high, snapshot = snapshot,
                    // how each requested spelling resolves NOW — a deduplicated operation's row included
                    resolutions = rows.takeIf { it.isNotEmpty() }?.map { ProjectPinResolution(it.op.seq, it.op.path, wireKey(it.key)) },
                ),
                changed = snapshot.takeIf { listChanged },
            )
        }
    }

    // ---- fan-out ----

    private class Subscriber(val deliver: suspend (ProjectPinsSnapshot) -> Unit) {
        val pending = AtomicReference<ProjectPinsSnapshot?>(null)
        val draining = AtomicBoolean(false)
        @Volatile var delivered: ProjectPinsSnapshot? = null
    }

    private val subscribers = ConcurrentHashMap<Any, Subscriber>()

    /**
     * Register a push target. Idempotent per [key] — the relay re-attaches on every owner frame under its stable
     * per-device key, and the FIRST registration's [deliver] stays, so it must resolve the connection, its
     * capability and subscription, and the device's membership at emission time rather than capture them.
     */
    fun attach(key: Any, deliver: suspend (ProjectPinsSnapshot) -> Unit) {
        subscribers.putIfAbsent(key, Subscriber(deliver))
    }

    fun detach(key: Any) {
        subscribers.remove(key)
    }

    internal fun subscriberCount(): Int = subscribers.size

    /** Offer [snapshot] to every subscriber except [exceptKey] (the requester, which already has it). */
    fun broadcast(snapshot: ProjectPinsSnapshot, exceptKey: Any? = null) {
        for ((key, subscriber) in subscribers) {
            if (key == exceptKey) continue
            subscriber.pending.getAndUpdate { queued -> if (queued == null || newerOrOther(snapshot, queued)) snapshot else queued }
            drain(key, subscriber)
        }
    }

    private fun drain(key: Any, subscriber: Subscriber) {
        if (!subscriber.draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (subscribers[key] === subscriber) {
                    val next = subscriber.pending.getAndSet(null) ?: break
                    val last = subscriber.delivered
                    if (last != null && !newerOrOther(next, last)) continue
                    try {
                        withTimeout(deliveryTimeoutMs) { subscriber.deliver(next) }
                        subscriber.delivered = next
                    } catch (e: TimeoutCancellationException) {
                        log.info("pin push to a slow subscriber timed out after ${deliveryTimeoutMs}ms")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.info("pin push failed: ${e::class.simpleName}")
                    }
                }
            } finally {
                subscriber.draining.set(false)
                // an offer that raced the loop's exit must not wait for the next commit
                if (subscriber.pending.get() != null && subscribers[key] === subscriber) drain(key, subscriber)
            }
        }
    }

    // ---- state helpers ----

    private sealed interface Load {
        data class Ok(val state: PinStoreState) : Load
        data class Failed(val code: String, val message: String) : Load
    }

    /** One submitted operation with the identity its path resolved to in this transaction. */
    private data class ResolvedOp(val op: ProjectPinOp, val key: String)

    private fun load(): Load {
        cached?.let { return Load.Ok(it) }
        return when (val read = store.read()) {
            is PinStoreRead.Loaded -> Load.Ok(read.state).also { cached = read.state }
            PinStoreRead.Missing -> {
                // persisted before first use: an incarnation must never change just because nobody pinned yet
                val fresh = PinStoreState(incarnation = newIncarnation())
                when (store.write(fresh)) {
                    PinStoreWrite.Durable -> Load.Ok(fresh).also { cached = fresh }
                    PinStoreWrite.UnchangedFailure ->
                        Load.Failed(ProjectPinErrors.STORE_UNAVAILABLE, "could not create the project pin store on this computer")
                    PinStoreWrite.IndeterminateFailure -> Load.Failed(ProjectPinErrors.STORE_UNAVAILABLE, blockPersistence())
                }
            }
            is PinStoreRead.Corrupt -> {
                log.warn("project pin store is corrupt (${read.reason}) — left untouched; move it aside to start over")
                Load.Failed(ProjectPinErrors.STORE_CORRUPT, "the project pin store on this computer is damaged and was left untouched")
            }
            is PinStoreRead.Unreadable -> {
                log.warn("project pin store unreadable: ${read.error::class.simpleName}")
                Load.Failed(ProjectPinErrors.STORE_UNAVAILABLE, "could not read project pins on this computer")
            }
        }
    }

    /** A write's stored outcome is unknown: forget the cached state and refuse every later request of this
     *  instance, rather than replace a file that may already hold a commit it cannot see. */
    private fun blockPersistence(): String {
        persistenceBlocked = true
        cached = null
        log.warn("project pin store: a save's outcome is unknown — refusing pin sync until the daemon restarts and re-reads it")
        return BLOCKED_MESSAGE
    }

    private fun keyFor(path: String): String = runCatching { keyOf(path) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "raw:$path"

    /** Recompute every stored identity and keep the first (newest) row of each; later alias rows are dropped
     *  while the surviving row keeps the display path it was pinned with. */
    private fun reconcile(pins: List<StoredPin>, keyNow: (String) -> String): List<StoredPin> {
        val seen = HashSet<String>()
        val out = ArrayList<StoredPin>(pins.size)
        for (pin in pins) {
            val key = keyNow(pin.path)
            if (!seen.add(key)) continue
            out += if (key == pin.key) pin else pin.copy(key = key)
        }
        return out
    }

    private fun apply(pins: List<StoredPin>, fresh: List<ResolvedOp>): List<StoredPin> {
        val list = pins.toMutableList()
        for ((op, key) in fresh) {
            if (op.pinned) {
                // already pinned: idempotent, and deliberately NOT a reorder
                if (list.none { it.key == key }) list.add(0, StoredPin(op.path, key))
            } else {
                list.removeAll { it.key == key }
            }
        }
        return list
    }

    private fun snapshotOf(state: PinStoreState) = ProjectPinsSnapshot(
        incarnation = state.incarnation,
        revision = state.revision,
        pins = state.pins.map { ProjectPin(it.path, wireKey(it.key)) },
    )

    companion object {
        /** Stream cursors kept before new streams are refused (after reclaiming unpaired devices' cursors). */
        const val MAX_CURSORS = 512
        const val DELIVERY_TIMEOUT_MS = 10_000L

        private const val BLOCKED_MESSAGE =
            "saving project pins on this computer failed midway; they need recovery — restart the daemon on this computer"

        private val random = SecureRandom()

        internal fun randomToken(): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Fixed-size opaque identity on the wire: equal canonical keys stay equal, and a long realpath never
         *  inflates the frame. */
        internal fun wireKey(canonical: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()),
            )

        private fun newerOrOther(candidate: ProjectPinsSnapshot, than: ProjectPinsSnapshot): Boolean =
            candidate.incarnation != than.incarnation || candidate.revision > than.revision
    }
}

/**
 * The canonical identity of a pinned path on this machine, resolved NOW: tilde expanded HERE (only the daemon
 * knows its home); an existing absolute path is its real path, anything else gets the same string normalization
 * [ProjectPaths.canonicalKey] gives a path that does not exist. Deliberately not that function itself: its memo
 * keeps a path's first real path forever, while a pin must follow a symlink retargeted since the last request. A
 * path that is not absolute after expansion is never resolved against the daemon's own working directory; it
 * keeps a string identity of its own so it can neither alias a real project nor be refused into a stuck outbox.
 * Inputs are already bounded by [isValidProjectPinPath]; only [ProjectPinService.wireKey] of the result leaves
 * the daemon.
 */
internal fun canonicalPinKey(path: String): String {
    val expanded = ProjectPaths.expandTilde(path)
    val parsed = runCatching { Path.of(expanded) }.getOrNull()
    if (parsed == null || !parsed.isAbsolute) return "rel:" + ProjectPaths.normCwd(expanded)
    val real = runCatching { parsed.toRealPath() }.getOrNull()
        ?: return ProjectPaths.normCwd(parsed.normalize().toString())
    return ProjectPaths.normCwd(real.toString())
}
