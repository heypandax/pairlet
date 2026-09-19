package dev.ccpocket.daemon.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Removes the ONE session dsh persisted for a cc-pocket model probe, and nothing else (issue #387).
 *
 * ## Why a file-level cleanup at all
 *
 * dsh 0.1.2-rc.1 offers no way to avoid the litter and no way to undo it:
 *  - `session/new` is the only method that answers with `configOptions`, and ACP's `newSession` calls
 *    `persistence.ensureMaterialized(...)` before it replies — so a promptless probe session is written
 *    to `$DSH_HOME/sessions/` and shows up in dsh's own list before we even read the answer;
 *  - its `session/new` params are `{cwd, additionalDirectories, mcpServers, _meta}` — no `ephemeral`,
 *    no `persist`, no client-chosen id, and `_meta` is never read;
 *  - `@agentclientprotocol` DOES define `session/delete`, but `dsh-acp` never registers a handler for it
 *    and its advertised `sessionCapabilities` are `{close, list, resume}` only. Its README says so:
 *    "session deletion, fork, `session/load`, modes … remain outside this automation surface";
 *  - the persistence seam has no deletion API either, at any layer ("pruning stored sessions is
 *    out-of-band backend maintenance").
 *
 * So the litter is ours to sweep, from outside. That makes this file a DELETE on a user's session store,
 * which is why every step below is a refusal looking for a reason to fire.
 *
 * ## The ownership proof
 *
 * A probe session is only removed when ALL of these hold:
 *  1. the id is the exact one this probe's own `session/new` answered with — never a name pattern, never
 *     a "looks like a temp dir" guess (the scratch cwd is only used to FIND the candidate);
 *  2. the directory sits exactly two levels under the sessions root in use — `root/<project>/<session>` —
 *     with both levels re-checked after `toRealPath`, so a symlinked or junctioned project directory
 *     cannot walk the delete out of the store, and an id that encodes to `.`/`..`/a separator cannot
 *     traverse (see [DshPaths.encodeSessionId], which escapes all three, asserted again here);
 *  3. the transcript's HEADER — read verbatim, never reconstructed from the directory name, because
 *     [DshPaths.projectKey] is lossy and colliding — carries both that same id and that same cwd;
 *  4. the transcript holds the header and NOTHING but boot settings from a verified allow-list
 *     ([BOOT_RECORDS]) — a session somebody has spoken in is never ours to drop, and an unfamiliar
 *     record is treated as one;
 *  5. the directory holds nothing but that one transcript generation and dsh's own sidecars — no
 *     sub-directories, no symlinks, no second generation, no files we cannot account for.
 *
 * Anything unproven is [Outcome.Refused], not a delete. A session that is already gone is
 * [Outcome.Absent]. Only an I/O failure on an otherwise-proven target is [Outcome.Failed]. None of the
 * three is allowed to cost the caller the catalogue it already read — cleanup failure and "no models"
 * are different answers to different questions.
 */
object DshProbeSessionCleanup {

    /** What happened to the probe's persisted session. Reasons are a fixed, enumerable vocabulary: they
     *  reach the log, and a session store must never leak transcript text or paths into one. */
    sealed interface Outcome {
        /** The directory (and, if it was left empty, its project directory) is gone. */
        data object Removed : Outcome

        /** Nothing was there — dsh never materialized it, or it was already cleaned up. */
        data object Absent : Outcome

        /** Ownership could not be proven. The session is left exactly as found. */
        data class Refused(val reason: String) : Outcome

        /** Ownership was proven but the delete did not complete; the remains are described by [reason]. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * @param sessionId the id from THIS probe's `session/new` result, verbatim.
     * @param scratchCwd the `cwd` string this probe passed to `session/new`, verbatim — both the project
     *   directory's lossy key and the header's authoritative `cwd` are checked against it.
     * @param root the sessions root the probed dsh actually wrote to. Defaults to the one this process
     *   resolves; the probe pins the child's `DSH_HOME` to that same value so the two cannot disagree.
     */
    fun remove(sessionId: String, scratchCwd: String, root: Path = DshPaths.sessionsRoot()): Outcome {
        if (sessionId.isBlank()) return Outcome.Refused("no session id in the session/new answer")
        val encoded = DshPaths.encodeSessionId(sessionId)
        // encodeSessionId escapes `/ \ : ~` and the two dot names; re-assert it rather than trust it,
        // because everything below resolves this string against the store root.
        if (encoded.isEmpty() || encoded == "." || encoded == ".." ||
            encoded.any { it == '/' || it == '\\' || it == ':' }
        ) {
            return Outcome.Refused("session id does not encode to a single directory name")
        }

        val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return Outcome.Absent
        val projectDir = realRoot.resolve(DshPaths.projectKey(scratchCwd))
        // NOFOLLOW reports false for a present symlink. Keep it for the canonical boundary check
        // below instead of incorrectly reporting that the probe's persisted session is absent.
        if (!projectDir.isDirectory(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(projectDir)) {
            return Outcome.Absent
        }
        val realProject = runCatching { projectDir.toRealPath() }.getOrNull()
            ?: return Outcome.Refused("project directory could not be resolved")
        // A junction/symlink is reported as a directory by isDirectory but resolves elsewhere; the
        // parent check below is what actually fences the delete inside the store.
        if (realProject.parent != realRoot) return Outcome.Refused("project directory leaves the session root")
        if (Files.isSymbolicLink(projectDir)) return Outcome.Refused("project directory is a link")

        val sessionDir = realProject.resolve(encoded)
        if (Files.isSymbolicLink(sessionDir)) return Outcome.Refused("session directory is a link")
        if (!sessionDir.isDirectory(LinkOption.NOFOLLOW_LINKS)) return Outcome.Absent
        val realSession = runCatching { sessionDir.toRealPath() }.getOrNull()
            ?: return Outcome.Refused("session directory could not be resolved")
        if (realSession.parent != realProject) return Outcome.Refused("session directory leaves its project")
        if (realSession.name != encoded) return Outcome.Refused("session directory name does not match the id")

        val entries = runCatching { realSession.listDirectoryEntries() }.getOrNull()
            ?: return Outcome.Refused("session directory could not be listed")
        var transcript: Path? = null
        for (entry in entries) {
            val name = entry.name
            if (Files.isSymbolicLink(entry)) return Outcome.Refused("session directory holds a link")
            if (entry.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
                return Outcome.Refused("session directory holds a sub-directory")
            }
            when {
                DshPaths.transcriptVersion(name) != null -> {
                    // Two canonical generations means a migrated, long-lived session — not a probe's.
                    if (transcript != null) return Outcome.Refused("session has more than one generation")
                    transcript = entry
                }
                DshPaths.isSidecar(name) -> Unit // dsh's own `.tmp` / `.lock` / `.dsh-mkdir-*` leftovers
                else -> return Outcome.Refused("session directory holds an unexpected file")
            }
        }
        // No header, no proof. A crashed materialization that left only a `.tmp` lands here and stays.
        val file = transcript ?: return Outcome.Refused("session has no transcript to prove ownership")
        if (!file.isRegularFile()) return Outcome.Refused("session transcript is not a regular file")

        val status = DshTranscript.ReadStatus()
        val lines = DshTranscript.lines(file, maxBytes = OWNERSHIP_READ_BYTES, status = status)
        if (status.unavailable) return Outcome.Refused("session transcript could not be read")
        // `partial` means a half-written tail or a file bigger than the budget: either the writer is not
        // done or there is far more here than a boot batch, and neither is a probe leftover.
        if (status.partial) return Outcome.Refused("session transcript is still being written")
        if (lines.isEmpty()) return Outcome.Refused("session transcript has no header")
        if (lines.size - 1 > MAX_BOOT_RECORDS) return Outcome.Refused("session has recorded activity")
        for (line in lines.drop(1)) {
            val type = runCatching { json.parseToJsonElement(line) }.getOrNull()
                ?.let { it as? JsonObject }?.str("type")
                ?: return Outcome.Refused("session holds an unreadable record")
            if (type in BOOT_RECORDS) continue
            return Outcome.Refused(
                if (type in CONVERSATION_RECORDS) "session has recorded activity"
                else "session holds an unrecognized record",
            )
        }

        val header = DshTranscript.headerOf(lines.first(), DshPaths.transcriptVersion(file.name))
            ?: return Outcome.Refused("session header could not be parsed")
        if (header.id != sessionId) return Outcome.Refused("session header carries a different id")
        val headerCwd = header.cwd ?: return Outcome.Refused("session header carries no cwd")
        if (!sameDirectory(headerCwd, scratchCwd)) return Outcome.Refused("session header carries a different cwd")

        // Proven. Everything below is I/O, and its failures are Failed, not Refused.
        for (entry in entries) {
            val deleted = runCatching { Files.deleteIfExists(entry) }
            if (deleted.isFailure) return Outcome.Failed("a file in the probe session could not be deleted")
        }
        runCatching { Files.delete(realSession) }
            .onFailure { return Outcome.Failed("the probe session directory could not be deleted") }
        // The project directory is ours too — dsh made it for this scratch cwd alone — but only prune it
        // when it is provably empty, so a key COLLISION (`/a/b` and `/a-b` share one) can never take a
        // stranger's session with it.
        runCatching {
            if (realProject.listDirectoryEntries().isEmpty()) Files.delete(realProject)
        }
        return Outcome.Removed
    }

    /** Header cwd vs the cwd we passed. Compared as paths (so `C:\x` and `C:\x\` agree, and Windows
     *  case-insensitivity is the platform's own rule), with a verbatim string fallback for anything the
     *  default provider refuses to parse. */
    private fun sameDirectory(a: String, b: String): Boolean =
        runCatching { Path.of(a).normalize() == Path.of(b).normalize() }.getOrElse { a == b }

    /** A probe session is a header plus a handful of boot settings. Anything that does not fit this is,
     *  by definition, not the thing we are allowed to delete — so the read never needs a bigger budget. */
    private const val OWNERSHIP_READ_BYTES = 256L * 1024

    /**
     * The ONLY records allowed to sit under the header of a session we will delete.
     *
     * A promptless `session/new` is not the header-only file the persistence README implies: dsh
     * 0.1.2-rc.1 also writes its boot configuration into the session's first durable batch. Verified on
     * a live probe run against the real store — the second frame of a probe session is exactly
     * `permission/preset` (seq 0), `sandbox/mode` (seq 1), `approval/policy` (seq 2), all carrying the
     * launch settings and no conversation.
     *
     * This is an ALLOW-LIST on purpose, and it is why cleanup fails CLOSED. If a future dsh adds a
     * fourth boot record, probes start refusing to sweep and say "unrecognized record" in the log; the
     * fix is to re-run the probe, confirm the new record carries no user content, and add it here.
     * The opposite arrangement — a deny-list — would delete a new kind of conversation row by default.
     */
    private val BOOT_RECORDS = setOf("permission/preset", "sandbox/mode", "approval/policy")

    /** The conversation vocabulary, so the refusal can say WHY rather than just "not allow-listed".
     *  Purely diagnostic: anything outside [BOOT_RECORDS] is refused either way. */
    private val CONVERSATION_RECORDS = setOf(
        DshTranscript.EVENT_USER, DshTranscript.EVENT_ASSISTANT, DshTranscript.EVENT_TITLE,
        DshTranscript.EVENT_TOOL_CALL, DshTranscript.EVENT_TOOL_RESULT,
        DshTranscript.EVENT_APPROVAL_ASKED, DshTranscript.EVENT_APPROVAL_DECIDED,
        DshTranscript.EVENT_REQUEST_CONTEXT, DshTranscript.EVENT_REQUEST_HEADER,
        DshTranscript.EVENT_TEXT_CHUNKS, DshTranscript.EVENT_REASONING_CHUNKS,
        DshTranscript.EVENT_TOOL_CALL_CHUNKS,
    )

    /** Room for the boot batch to grow a little without room for a conversation to hide in it. */
    private const val MAX_BOOT_RECORDS = 8

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
