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
 * to the pickers FOREVER. So every spawned (workdir, sessionId) is journaled here, and [sweepAtBoot]
 * re-runs the unhide for any leftovers on the next start (at boot none of our processes are alive, so
 * the files are safe to rewrite — except a file with a FRESH mtime, which may belong to a terminal
 * claude the user resumed meanwhile; those are kept for the next sweep).
 *
 * Scoped to sessions WE created on purpose: unhiding other SDK tools' robo-transcripts would spam the
 * user's picker with sessions they never had.
 */
object SpawnedSessions {
    private val log = logger("SpawnedSessions")
    private const val MAX_ENTRIES = 200

    fun defaultJournal(): File = File(Identity.defaultPath().parentFile, "spawned-sessions.tsv")

    // claude session ids are UUIDs; anything else in the journal is tampering or corruption. Filtering
    // here (not just at sweep) keeps a hostile id from ever influencing a rewrite path (security review L1).
    private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Journal a session this daemon just brought live. Idempotent per (workdir, sessionId). */
    @Synchronized
    fun note(workdir: String, sessionId: String, journal: File = defaultJournal()) {
        if (!SESSION_ID.matches(sessionId)) return
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

    /**
     * Unhide every journaled transcript that is quiet (mtime past the live window) and drop it from the
     * journal; fresh files stay journaled for the next boot. Returns how many files were rewritten.
     */
    @Synchronized
    fun sweepAtBoot(
        journal: File = defaultJournal(),
        dirFor: (String) -> Path = ProjectPaths::dirFor,
        now: () -> Long = System::currentTimeMillis,
    ): Int {
        if (!journal.exists()) return 0
        val entries = runCatching { journal.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (entries.isEmpty()) return 0
        var unhidden = 0
        val keep = ArrayList<String>()
        for (line in entries) {
            val (workdir, sid) = line.split('\t').takeIf { it.size == 2 } ?: continue
            if (!SESSION_ID.matches(sid)) continue // tampered journal must not steer the rewrite (review L1)
            val root = dirFor(workdir).normalize()
            val file = root.resolve("$sid.jsonl").normalize()
            if (!file.startsWith(root)) continue // belt-and-suspenders: never rewrite outside the projects tree
            if (!Files.exists(file)) continue // deleted / cleaned up — nothing left to unhide
            val mtime = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull() ?: continue
            if (now() - mtime < TranscriptScanner.LIVE_WINDOW_MS) {
                keep += line // maybe a terminal claude took it over — not safe to rewrite, retry next boot
                continue
            }
            if (TranscriptPatcher.unhide(file)) unhidden++
        }
        runCatching {
            if (keep.isEmpty()) journal.delete() else journal.writeText(keep.joinToString("\n") + "\n")
        }
        if (unhidden > 0) log.info("boot sweep unhid $unhidden crash-stranded transcript(s) for the resume pickers")
        return unhidden
    }
}
