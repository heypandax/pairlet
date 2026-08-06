package dev.ccpocket.daemon.disk

import dev.ccpocket.daemon.identity.Identity
import dev.ccpocket.daemon.util.logger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Sessions THIS daemon spawned, persisted so a crash can't strand them hidden (issue #70).
 *
 * claude tags `-p` transcripts `entrypoint:"sdk-cli"` and the terminal/VS Code `--resume` pickers hide
 * those; [dev.ccpocket.daemon.claude.ClaudeBackend.onProcessEnded] unhides each transcript when its
 * process ends. But a daemon that dies uncleanly never runs that hook — its sessions stayed invisible
 * to the pickers FOREVER. So every spawned (workdir, sessionId) is journaled here and [sweep] re-runs
 * the unhide for any leftovers — at boot AND periodically (issue #216 ②: a crash/orphan no longer waits
 * for "the next restart", which for an always-on daemon never came).
 *
 * A journaled transcript is only rewritten when NOTHING can still be writing it, checked three ways:
 *  - fresh mtime (within [TranscriptScanner.LIVE_WINDOW_MS]) — someone streamed into it seconds ago;
 *  - [held]: the session is one of this daemon's LIVE conversations (their claudes are our own children,
 *    invisible to the external-process probe — rewriting under them drops appends, the d8fa0da class);
 *  - [probe]: an external claude (a terminal resume, or a sibling spawner's still-running process) is
 *    attached per [LiveProcesses.externalClaudeAt]. PRESENT keeps the entry for a later pass; UNKNOWN
 *    (Windows / lsof failure) falls back to the mtime verdict alone — the pre-#216 boot behavior, else
 *    Windows would never unhide anything.
 *
 * Scoped to sessions OUR ecosystem created on purpose: unhiding other SDK tools' robo-transcripts would
 * spam the user's picker with sessions they never had. Sibling spawners (cc-connect and friends,
 * issue #216 ④) opt into the same protection by dropping their own journal — one `workdir<TAB>sessionId`
 * line per spawned session — into [defaultDropInDir] as `<tool>.tsv`; [sweep] walks those with exactly
 * the same guards and prunes settled lines. Nothing outside these journals is ever touched.
 */
object SpawnedSessions {
    private val log = logger("SpawnedSessions")
    private const val MAX_ENTRIES = 200

    /** Guards journal file access: [note] appends from conversation pumps while [sweep] rewrites. */
    private val lock = Any()

    fun defaultJournal(): File = File(Identity.defaultPath().parentFile, "spawned-sessions.tsv")

    /** Drop-in journals from SIBLING spawners in our ecosystem (issue #216 ④) — `<tool>.tsv` files in
     *  the same `workdir<TAB>sessionId` format as [defaultJournal]. */
    fun defaultDropInDir(): File = File(Identity.defaultPath().parentFile, "spawned-sessions.d")

    // claude session ids are UUIDs; anything else in the journal is tampering or corruption. Filtering
    // here (not just at sweep) keeps a hostile id from ever influencing a rewrite path (security review L1).
    private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Journal a session this daemon just brought live. Idempotent per (workdir, sessionId). */
    fun note(workdir: String, sessionId: String, journal: File = defaultJournal()) {
        if (!SESSION_ID.matches(sessionId)) return
        synchronized(lock) {
            runCatching {
                val line = "$workdir\t$sessionId"
                val existing = if (journal.exists()) journal.readLines() else emptyList()
                if (line in existing) return
                journal.parentFile?.mkdirs()
                journal.writeText((existing + line).takeLast(MAX_ENTRIES).joinToString("\n") + "\n")
                // owner-only, like identity.json — the journal maps project paths to session ids (review L4)
                runCatching { Files.setPosixFilePermissions(journal.toPath(), PosixFilePermissions.fromString("rw-------")) }
            }.onFailure { log.warn("journal write failed: ${it.message}") }
        }
    }

    /**
     * Unhide every journaled transcript that is quiet (mtime past the live window) and provably
     * writer-free ([held] + [probe], see the class doc), then drop it from its journal; anything still
     * potentially live stays journaled for the next pass. Walks [journal] and every `*.tsv` drop-in in
     * [dropInDir]. Returns how many files were rewritten.
     */
    suspend fun sweep(
        journal: File = defaultJournal(),
        dropInDir: File = defaultDropInDir(),
        dirFor: (String) -> Path = ProjectPaths::dirFor,
        now: () -> Long = System::currentTimeMillis,
        held: suspend (String) -> Boolean = { false },
        probe: (String, Path) -> LiveProcesses.ExternalClaude = LiveProcesses::externalClaudeAt,
    ): Int {
        var unhidden = sweepFile(journal, dirFor, now, held, probe)
        val dropIns = dropInDir.listFiles { f: File -> f.isFile && f.name.endsWith(".tsv") }?.sortedBy { it.name }.orEmpty()
        for (f in dropIns) unhidden += sweepFile(f, dirFor, now, held, probe)
        if (unhidden > 0) log.info("sweep unhid $unhidden stranded transcript(s) for the resume pickers")
        return unhidden
    }

    private suspend fun sweepFile(
        journal: File,
        dirFor: (String) -> Path,
        now: () -> Long,
        held: suspend (String) -> Boolean,
        probe: (String, Path) -> LiveProcesses.ExternalClaude,
    ): Int {
        val entries = synchronized(lock) {
            if (!journal.exists()) return 0
            runCatching { journal.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        }
        if (entries.isEmpty()) return 0
        var unhidden = 0
        val settled = HashSet<String>() // lines fully handled (unhidden, vanished, or malformed) — prune these
        for (line in entries) {
            val parts = line.split('\t')
            if (parts.size != 2) { settled += line; continue } // corrupt line — never useful, drop it
            val (workdir, sid) = parts
            if (!SESSION_ID.matches(sid)) { settled += line; continue } // tampered journal must not steer the rewrite (review L1)
            val root = dirFor(workdir).normalize()
            val file = root.resolve("$sid.jsonl").normalize()
            if (!file.startsWith(root)) { settled += line; continue } // belt-and-suspenders: never rewrite outside the projects tree
            if (!Files.exists(file)) { settled += line; continue } // deleted / cleaned up — nothing left to unhide
            val mtime = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: continue
            if (now() - mtime < TranscriptScanner.LIVE_WINDOW_MS) continue // maybe being written right now — retry later
            if (held(sid)) continue // one of OUR live conversations still owns it — its process end will unhide
            if (probe(workdir, file) == LiveProcesses.ExternalClaude.PRESENT) continue // a foreign claude is attached — not ours to touch yet
            if (TranscriptPatcher.unhide(file)) unhidden++
            settled += line // settled either way: already-clean files need no further passes
        }
        // prune by set subtraction against a FRESH read — note() may have appended new entries while the
        // probes above ran, and writing back our stale snapshot would silently drop them
        synchronized(lock) {
            runCatching {
                val remaining = (if (journal.exists()) journal.readLines() else emptyList())
                    .filter { it.isNotBlank() && it !in settled }
                if (remaining.isEmpty()) journal.delete() else journal.writeText(remaining.joinToString("\n") + "\n")
            }
        }
        return unhidden
    }
}
