package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.FileChunk
import dev.ccpocket.protocol.MAX_UPLOAD_BYTES
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

/**
 * Reassembles [FileChunk] streams into files under a session's workspace inbox:
 * `<cwd>/.ccpocket/inbox/<captureId>/<name>` (issue #90).
 *
 * The state machine mirrors [dev.ccpocket.daemon.transcribe.CaptureBuffer] (finished ring dedupes
 * late/retried chunks, idle sweep expires abandoned uploads, cancel drops the partial) with one
 * structural difference: audio buffers every chunk in memory and decodes once, but a file can be
 * 200 MB — so chunks are decoded INDIVIDUALLY (each chunk's base64 is self-contained) and the
 * contiguous prefix is streamed straight into a `.part` file, with only a small bounded
 * reorder buffer in memory. `last` + all-written → fsync + atomic rename to the final name.
 *
 * This is the daemon's only PHONE-DRIVEN write surface, so it is fenced harder than the read
 * paths: the caller passes the session cwd (NEVER the phone), captureId must match a strict
 * charset (no separators — it is interpolated into a directory name), the display name is
 * sanitized to a safe basename, and the landing directory is canonicalized and must stay inside
 * the inbox root ([DirectoryService.listPathEntries]'s toRealPath+startsWith guard). Byte/count
 * caps bound disk and memory; refusal marks the capture finished so an in-flight stream yields
 * exactly one error, not one per chunk.
 */
class UploadReassembler(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxUploadBytes: Long = MAX_UPLOAD_BYTES, // injectable so tests can exercise the write-side cap
) {

    sealed interface Result {
        /** More chunks expected — nothing to tell the phone yet. */
        data object Incomplete : Result

        /** Chunk of a capture that already finished / was cancelled / was refused — drop silently. */
        data object Stale : Result

        /** All chunks written and the file landed. [relPath] is relative to the session cwd
         *  captured at upload start (daemon-host separators) — the `@`-referencable path. */
        class Complete(val captureId: String, val name: String, val relPath: String, val size: Long) : Result

        /** Upload refused/aborted; any partial was deleted and later chunks of it will read Stale. */
        data class Refused(val captureId: String, val error: String) : Result
    }

    private class Pending(
        val convoId: String,
        val captureId: String,
        val name: String,
        val workdir: Path,
        val dir: Path,        // <cwd>/.ccpocket/inbox/<captureId>
        val part: Path,       // <dir>/<name>.part
        val out: OutputStream,
        val createdMs: Long,
    ) {
        var lastMs: Long = createdMs
        var nextIdx = 0                              // next contiguous idx to stream to disk
        var lastIdx: Int? = null                     // set when the last-flagged chunk arrives
        var written = 0L
        val reorder = HashMap<Int, ByteArray>()      // bounded out-of-order stash
        var reorderBytes = 0L
    }

    private val pending = HashMap<String, Pending>()  // captureId -> upload in flight
    private val finished = ArrayDeque<String>()       // recent completed/cancelled/refused captureIds

    /** Feed one chunk. [workdir] is the conversation's cwd as the DAEMON knows it (never phone input). */
    @Synchronized
    fun add(c: FileChunk, workdir: Path): Result {
        sweep()
        if (c.captureId in finished) return Result.Stale

        var p = pending[c.captureId]
        if (p == null) {
            val opened = openUpload(c, workdir)
            when (opened) {
                is Opened.Refuse -> {
                    markFinished(c.captureId)
                    return Result.Refused(c.captureId, opened.error)
                }
                is Opened.Ok -> {
                    p = opened.pending
                    pending[c.captureId] = p
                }
            }
        } else if (p.convoId != c.convoId) {
            // a different conversation may not write into someone else's in-flight capture
            return Result.Stale
        }
        p.lastMs = nowMs()

        val bytes = try {
            Base64.getDecoder().decode(c.base64)
        } catch (_: IllegalArgumentException) {
            return abort(p, "file data arrived corrupted — try again")
        }
        if (bytes.size > MAX_CHUNK_BYTES) return abort(p, "upload chunk too large")
        if (c.idx < 0 || c.idx > MAX_CHUNK_COUNT) return abort(p, "too many chunks")
        if (c.last) p.lastIdx = c.idx

        when {
            c.idx < p.nextIdx -> {} // duplicate of an already-written chunk — ignore
            c.idx == p.nextIdx -> {
                if (!write(p, bytes)) return abort(p, ERR_TOO_LARGE)
                // flush any buffered successors that are now contiguous
                while (true) {
                    val next = p.reorder.remove(p.nextIdx) ?: break
                    p.reorderBytes -= next.size
                    if (!write(p, next)) return abort(p, ERR_TOO_LARGE)
                }
            }
            else -> { // ahead of the contiguous prefix — stash, bounded
                if (p.reorder.size >= MAX_REORDER_CHUNKS || p.reorderBytes + bytes.size > MAX_REORDER_BYTES) {
                    return abort(p, "upload chunks arrived too far out of order — try again")
                }
                if (p.reorder.put(c.idx, bytes) == null) p.reorderBytes += bytes.size
            }
        }

        val last = p.lastIdx ?: return Result.Incomplete
        if (p.nextIdx <= last) return Result.Incomplete

        // all of 0..last streamed to the .part — land it
        pending.remove(p.captureId)
        markFinished(p.captureId)
        return runCatching {
            p.out.flush()
            p.out.close()
            val final = p.dir.resolve(p.name)
            try {
                Files.move(p.part, final, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(p.part, final, StandardCopyOption.REPLACE_EXISTING)
            }
            val rel = p.workdir.relativize(p.dir).resolve(p.name).toString()
            Result.Complete(p.captureId, p.name, rel, p.written) as Result
        }.getOrElse {
            cleanup(p)
            Result.Refused(p.captureId, "could not write the file to the workspace")
        }
    }

    /** User cancelled (or is retrying under a fresh id): drop buffered state + the on-disk partial. */
    @Synchronized
    fun cancel(convoId: String, captureId: String) {
        val p = pending[captureId] ?: return
        if (p.convoId != convoId) return
        pending.remove(captureId)
        markFinished(captureId)
        cleanup(p)
    }

    /** Uploads currently in flight for [convoId] — lets callers cap per-conversation concurrency. */
    @Synchronized
    fun inFlight(convoId: String): Int = pending.values.count { it.convoId == convoId }

    // ---- internals -------------------------------------------------------------------------

    private sealed interface Opened {
        class Ok(val pending: Pending) : Opened
        class Refuse(val error: String) : Opened
    }

    /** Validate identifiers, fence the landing dir inside the inbox, open the .part stream. */
    private fun openUpload(c: FileChunk, workdir: Path): Opened {
        if (!CAPTURE_ID_RX.matches(c.captureId)) return Opened.Refuse("malformed upload id")
        if (pending.size >= MAX_PENDING) return Opened.Refuse("too many uploads in flight — wait for one to finish")
        if (pending.values.count { it.convoId == c.convoId } >= MAX_PER_CONVO) {
            return Opened.Refuse("too many uploads in flight for this session")
        }
        if (c.totalBytes > maxUploadBytes) return Opened.Refuse(ERR_TOO_LARGE)
        val name = sanitizeName(c.name) ?: return Opened.Refuse("unusable file name")

        return runCatching {
            val realWorkdir = workdir.toRealPath()
            // Canonicalize the inbox root and verify IT stays inside the workspace BEFORE resolving the
            // captureId under it: a pre-planted `.ccpocket/inbox` symlink can't then redirect writes out
            // of the tree, and anchoring the containment check on the (canonical) inbox root — not merely
            // the workdir — keeps the fence tight even if [CAPTURE_ID_RX] is ever loosened to allow
            // separators. (DirectoryService.listPathEntries uses the same toRealPath+startsWith belt.)
            val inboxRoot = realWorkdir.resolve(INBOX_DIR)
            Files.createDirectories(inboxRoot)
            val realInbox = inboxRoot.toRealPath()
            if (!realInbox.startsWith(realWorkdir)) return Opened.Refuse("workspace inbox escaped the workspace")
            val dir = realInbox.resolve(c.captureId)
            Files.createDirectories(dir)
            val real = dir.toRealPath()
            if (!real.startsWith(realInbox)) {
                runCatching { Files.deleteIfExists(dir) }
                return Opened.Refuse("landing directory escaped the workspace")
            }
            ensureSelfIgnored(realWorkdir)
            val part = real.resolve("$name.part")
            // Drop any stale partial or a pre-planted symlink first — deleteIfExists removes the LINK
            // itself, not its target — then CREATE_NEW so the write can never follow a symlink out of
            // the (already contained) capture dir.
            Files.deleteIfExists(part)
            val out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Opened.Ok(Pending(c.convoId, c.captureId, name, realWorkdir, real, part, out, nowMs())) as Opened
        }.getOrElse { Opened.Refuse("could not open the workspace inbox for writing") }
    }

    private fun write(p: Pending, bytes: ByteArray): Boolean {
        if (p.written + bytes.size > maxUploadBytes) return false
        p.out.write(bytes)
        p.written += bytes.size
        p.nextIdx++
        return true
    }

    private fun abort(p: Pending, error: String): Result {
        pending.remove(p.captureId)
        markFinished(p.captureId)
        cleanup(p)
        return Result.Refused(p.captureId, error)
    }

    /** Close the stream and remove the capture's directory (partial + anything else we created). */
    private fun cleanup(p: Pending) {
        runCatching { p.out.close() }
        runCatching {
            if (Files.exists(p.dir)) {
                Files.walk(p.dir).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /** Drop uploads that stopped receiving chunks (phone died mid-stream) — state AND partial file. */
    private fun sweep() {
        val cutoff = nowMs() - IDLE_EXPIRY_MS
        val stale = pending.values.filter { it.lastMs < cutoff }
        for (p in stale) {
            pending.remove(p.captureId)
            markFinished(p.captureId)
            cleanup(p)
        }
    }

    private fun markFinished(id: String) {
        finished.addLast(id)
        while (finished.size > FINISHED_KEEP) finished.removeFirst()
    }

    /** `.ccpocket/.gitignore` containing `*` — the inbox must never dirty the user's repo status. */
    private fun ensureSelfIgnored(workdir: Path) {
        runCatching {
            val gi = workdir.resolve(".ccpocket").resolve(".gitignore")
            if (!Files.exists(gi)) Files.write(gi, listOf("*"))
        }
    }

    companion object {
        /** Landing area relative to the session cwd. */
        val INBOX_DIR: Path = Path.of(".ccpocket", "inbox")

        /** captureId is interpolated into a directory name → strict charset, no separators/dots. */
        private val CAPTURE_ID_RX = Regex("[A-Za-z0-9_-]{4,64}")

        const val IDLE_EXPIRY_MS = 120_000L
        private const val FINISHED_KEEP = 64
        private const val MAX_PENDING = 8       // across all conversations
        private const val MAX_PER_CONVO = 4
        private const val MAX_CHUNK_COUNT = 4_096
        private const val MAX_CHUNK_BYTES = 3 * 1024 * 1024   // relay frames cap at 4 MiB anyway
        private const val MAX_REORDER_CHUNKS = 32
        private const val MAX_REORDER_BYTES = 16L * 1024 * 1024
        const val ERR_TOO_LARGE = "file too large — the limit is 200 MB"

        /**
         * Reduce a phone-supplied display name to a safe basename that is also a clean `@`-token:
         * strip any directory part, replace separators/control chars/whitespace with `_`, refuse
         * empty/dot names, sidestep Windows reserved device names, and cap length keeping the
         * extension end. Returns null when nothing usable remains.
         */
        fun sanitizeName(raw: String): String? {
            val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            if (base.isEmpty() || base == "." || base == "..") return null
            val cleaned = buildString(base.length) {
                for (ch in base) append(if (ch.isISOControl() || ch.isWhitespace() || ch in "\\/:*?\"<>|@") '_' else ch)
            }.trim('.').ifEmpty { return null } // ".." / "..." collapse to empty → refused
            val stem = cleaned.substringBefore('.')
            val safe = if (stem.uppercase() in WINDOWS_RESERVED) "_$cleaned" else cleaned
            return if (safe.length <= MAX_NAME_LEN) safe else safe.takeLast(MAX_NAME_LEN)
        }

        private const val MAX_NAME_LEN = 120
        private val WINDOWS_RESERVED = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
}
