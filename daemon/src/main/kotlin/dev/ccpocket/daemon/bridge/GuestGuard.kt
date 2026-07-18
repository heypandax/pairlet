package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.AudioCancel
import dev.ccpocket.protocol.AudioChunk
import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.ClearAllowRule
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.FetchHistoryPage
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.ListDirectories
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.ListSessionFiles
import dev.ccpocket.protocol.ListSessions
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionVerdict
import dev.ccpocket.protocol.ReadFile
import dev.ccpocket.protocol.ReadFileDiff
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.StopBackgroundJob
import dev.ccpocket.protocol.SwitchMode
import java.util.ArrayDeque

/**
 * Per-credential enforcement of a GUEST folder-share's CONSTRAINTS + RATE/CONCURRENCY limits (issue #115),
 * the guest analogue of [BridgeGuard]. One instance per live guest deviceId, created by
 * [BridgeRegistry.startGuard] once the guest's E2E session establishes and seeded with the guest's
 * PERSISTED owned-session set (so the "is this the guest's own session" checks survive a daemon restart).
 * Not thread-safe on its own; the relay pumps one device's frames sequentially through [DeviceSessions],
 * so a plain object with @Synchronized on the mutating methods is enough.
 *
 * The scope boundary (the shared root) is enforced HERE for every frame that names a path/dir/session:
 *  - OpenSession workdir MUST canonicalize inside the root ([PathScope.contains]) — `../`/symlink safe.
 *  - resume / prompt / verdict / mode.switch / close / cancel / stop / audio act only on the guest's OWN
 *    conversations (transcript exfiltration + session hijack are the two things this closes).
 *  - list / read frames must target a dir under the root AND (for session reads) a session the guest owns.
 *  - the requested permission mode is clamped to the share TIER's ceiling (never bypassPermissions).
 *  - once the share [expiresAt] passes, EVERY frame is denied (defence in depth behind the registry purge).
 */
class GuestGuard(
    val spec: BridgeSpec,
    /** The guest's owned session ids restored from the persisted ledger — so a reconnect after a daemon
     *  restart still recognises the guest's own historical sessions (visibility "by initiator"). */
    seedSessions: Set<String> = emptySet(),
    /** Persist a newly-minted owned sessionId (the ledger the seed is read back from). */
    private val persistSession: (String) -> Unit = {},
) {
    private val roots: List<String> = spec.workdirs.mapNotNull { PathScope.canonical(it) }

    // insertion-ordered + bounded, like BridgeGuard: a long-lived guest opens many convos; the ownership
    // ledger must not grow without limit. Concurrency budgets LIVE convos (computed by the caller), so
    // evicting a very old, long-closed id here is harmless (it was reaped ages ago).
    private val ownedConvos = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (added && size > MAX_TRACKED) iterator().let { it.next(); it.remove() }
            return added
        }
    }
    private val ownedSessions = LinkedHashSet<String>().apply { addAll(seedSessions) }
    private val openTimes = ArrayDeque<Long>()
    private val promptTimes = ArrayDeque<Long>()

    /**
     * Vet an inbound frame from this guest. The caller enforces the top-level type whitelist via
     * [GuestCaps.ingressAllowed] BEFORE calling this, so only admitted request types reach here.
     * [liveOwned] is how many of this guest's conversations are STILL LIVE (the caller computes it for
     * opens; concurrency budgets live sessions, not the historical ledger). A [BridgeVerdict.Allow] may
     * carry a REWRITTEN frame (workdir canonicalized, mode clamped, force/takeOver stripped) that MUST
     * replace the original before routing.
     */
    @Synchronized
    fun vet(frame: Frame, now: Long, liveOwned: Int = 0): BridgeVerdict {
        if (spec.expired(now)) return BridgeVerdict.Deny(BridgeDenyCode.SHARE_EXPIRED)
        return when (frame) {
            is OpenSession -> vetOpen(frame, now, liveOwned)
            is SendPrompt -> vetPrompt(frame, now)
            // turn/session controls on the guest's OWN conversation only
            is CancelTurn -> ownedOr(frame.convoId, frame)
            is CloseSession -> if (owns(frame.convoId)) BridgeVerdict.Allow(frame.copy(force = false)) else notOwn()
            is PermissionVerdict -> ownedOr(frame.convoId, frame) // a guest answers ONLY its own asks
            is SwitchMode -> if (owns(frame.convoId)) BridgeVerdict.Allow(frame.copy(mode = GuestCaps.clampMode(frame.mode, spec.tier))) else notOwn()
            is ClearAllowRule -> ownedOr(frame.convoId, frame)
            is StopBackgroundJob -> ownedOr(frame.convoId, frame)
            is AudioChunk -> ownedOr(frame.convoId, frame)
            is AudioCancel -> ownedOr(frame.convoId, frame)
            // older-history paging (issue #147): reads only the guest's OWN conversation's transcript
            is FetchHistoryPage -> ownedOr(frame.convoId, frame)
            // scoped browse/read surfaces — the response is additionally filtered by the router (own sessions)
            is ListDirectories -> BridgeVerdict.Allow(frame) // router returns ONLY the shared root
            is ListSessions -> if (underScope(frame.workdir)) BridgeVerdict.Allow(frame) else badWorkdir()
            is ListPathEntries -> if (underScope(frame.workdir)) BridgeVerdict.Allow(frame) else badWorkdir()
            is ListSessionFiles -> vetSessionRead(frame.workdir, frame.sessionId, frame)
            is ReadFile -> vetSessionRead(frame.workdir, frame.sessionId, frame)
            is ReadFileDiff -> vetSessionRead(frame.workdir, frame.sessionId, frame)
            else -> BridgeVerdict.Deny(BridgeDenyCode.FORBIDDEN)
        }
    }

    private fun vetOpen(f: OpenSession, now: Long, liveOwned: Int): BridgeVerdict {
        if (!underScope(f.workdir)) return badWorkdir()
        // resume only OWN sessions: a guest must not resume (and thus read the transcript of) an owner's
        // session that merely happens to sit under the shared root
        val resume = f.resumeId
        if (resume != null && resume !in ownedSessions) return notOwn()
        if (liveOwned >= spec.maxSessions) return BridgeVerdict.Deny(BridgeDenyCode.TOO_MANY_SESSIONS)
        if (!admit(openTimes, spec.opensPerMin, now)) return BridgeVerdict.Deny(BridgeDenyCode.OPEN_RATE)
        val wd = PathScope.canonical(f.workdir) ?: return badWorkdir()
        // the daemon — not the guest — is the authority: canonicalize the workdir, clamp the mode to the
        // tier ceiling, strip take-over (a guest never seizes a session live in another client), and force
        // the CLAUDE backend. The clean-room (no MCP / no owner context) is a Claude-launch concern; Codex
        // has its own ~/.codex MCP + config the daemon does not strip in v1, so a guest driving Codex would
        // bypass the clean-room and reach the owner's Codex integrations. Force Claude until Codex gets a
        // clean-room too (v2).
        return BridgeVerdict.Allow(
            f.copy(workdir = wd, mode = GuestCaps.clampMode(f.mode, spec.tier), takeOver = false, agent = AgentKind.CLAUDE),
        )
    }

    private fun vetPrompt(f: SendPrompt, now: Long): BridgeVerdict {
        if (!owns(f.convoId)) return notOwn()
        if (f.text.length > MAX_PROMPT_CHARS) return BridgeVerdict.Deny(BridgeDenyCode.PROMPT_TOO_LARGE)
        // images ARE allowed for a guest (unlike a bridge — a guest is a human who may send a screenshot);
        // the relay's frame cap + the client's downscale bound the size.
        if (!admit(promptTimes, spec.promptsPerMin, now)) return BridgeVerdict.Deny(BridgeDenyCode.PROMPT_RATE)
        return BridgeVerdict.Allow(f)
    }

    /** A (workdir, sessionId) read must target the shared root AND a session the guest itself owns —
     *  otherwise a guest could read an owner session's changed files by guessing (workdir, sessionId). */
    private fun vetSessionRead(workdir: String, sessionId: String, frame: Frame): BridgeVerdict = when {
        !underScope(workdir) -> badWorkdir()
        sessionId !in ownedSessions -> notOwn()
        else -> BridgeVerdict.Allow(frame)
    }

    private fun ownedOr(convoId: String, frame: Frame): BridgeVerdict =
        if (owns(convoId)) BridgeVerdict.Allow(frame) else notOwn()

    /** Tilde forms are refused OUTRIGHT, before containment: "~" is an OWNER-side convention (the #152
     *  home-browse anchor). [PathScope.canonical] does NOT expand it — it resolves against the JVM cwd —
     *  while the EXECUTION side expands it to the real home dir. So if the daemon were (against the
     *  rules) started from inside a shared root, "<cwd>/~" would pass containment and a guest could walk
     *  the owner's whole home tree. A guest's scope is always issued as an absolute root, so no
     *  legitimate guest frame ever needs a tilde — the hard reject makes the gate unconditional. */
    private fun underScope(workdir: String): Boolean {
        if (workdir.startsWith("~")) return false
        return PathScope.contains(roots, workdir)
    }
    private fun owns(convoId: String) = convoId in ownedConvos
    private fun notOwn() = BridgeVerdict.Deny(BridgeDenyCode.NOT_OWN_SESSION)
    private fun badWorkdir() = BridgeVerdict.Deny(BridgeDenyCode.BAD_WORKDIR)

    @Synchronized
    fun ownedConvoIds(): Set<String> = ownedConvos.toSet()

    /** The guest's owned session ids (in-memory ledger, seeded from the persisted set) — the router
     *  intersects [ListSessions] results with this so a guest sees only sessions it started, never the
     *  owner's other sessions that happen to live under the shared root (issue #115 comment §3). */
    @Synchronized
    fun ownedSessionIds(): Set<String> = ownedSessions.toSet()

    @Synchronized
    fun noteOpened(convoId: String) { ownedConvos.add(convoId) }

    /** A SessionLive for an owned convo carried the minted sessionId — record it (and persist) so a later
     *  resume/read is recognised as the guest's own even across a daemon restart. */
    @Synchronized
    fun noteSession(convoId: String, sessionId: String) {
        if (convoId in ownedConvos && ownedSessions.add(sessionId)) {
            if (ownedSessions.size > MAX_TRACKED) ownedSessions.iterator().let { it.next(); it.remove() }
            persistSession(sessionId)
        }
    }

    @Synchronized
    fun noteClosed(convoId: String) { ownedConvos.remove(convoId) }

    /** Sliding-window admission (identical rule to BridgeGuard): keep only the last minute; admit if under [perMin]. */
    private fun admit(window: ArrayDeque<Long>, perMin: Int, now: Long): Boolean {
        while (window.isNotEmpty() && now - window.peekFirst() >= 60_000) window.pollFirst()
        if (window.size >= perMin) return false
        window.addLast(now)
        return true
    }

    private companion object {
        const val MAX_PROMPT_CHARS = 64 * 1024 // a guest is interactive — a roomier bound than a bridge's 32KiB
        const val MAX_TRACKED = 256
    }
}
