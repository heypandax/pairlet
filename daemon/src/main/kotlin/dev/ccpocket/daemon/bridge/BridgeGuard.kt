package dev.ccpocket.daemon.bridge

import dev.ccpocket.protocol.CancelTurn
import dev.ccpocket.protocol.CloseSession
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.SendPrompt
import java.io.File
import java.util.ArrayDeque

/** Why a restricted request was refused — surfaced as a [dev.ccpocket.protocol.PocketError] code so the
 *  client can log/react without guessing. The BRIDGE wire code/message (#91, keyed on by adapters) and the
 *  GUEST wire code/message (#115, shown to a human) are kept SEPARATE so neither audience sees the other's
 *  terminology — [DeviceSessions] picks the pair by the credential kind. */
enum class BridgeDenyCode(
    val wire: String, val message: String,
    val guestWire: String = wire, val guestMessage: String = message,
) {
    FORBIDDEN("bridge_forbidden", "this request type is not permitted for a bridge credential",
        "share_forbidden", "that action isn't permitted for a folder-share guest"),
    BAD_WORKDIR("bridge_forbidden_workdir", "workdir is outside this bridge's allow-list",
        "share_out_of_scope", "that folder is outside your shared folder"),
    PROMPT_TOO_LARGE("bridge_prompt_too_large", "prompt exceeds the bridge size cap",
        "share_prompt_too_large", "that message is too long"),
    IMAGES_DENIED("bridge_images_denied", "image attachments are not permitted for a bridge credential"),
    NOT_OWN_SESSION("bridge_not_own_session", "resume/prompt/close references a session this bridge does not own",
        "share_not_own_session", "that session isn't one you started"),
    TOO_MANY_SESSIONS("bridge_busy", "this bridge is at its concurrent-session limit",
        "share_busy", "you're at your live-session limit for this share"),
    OPEN_RATE("bridge_rate_limited", "this bridge is opening sessions too fast",
        "share_rate_limited", "you're opening sessions too fast — slow down"),
    PROMPT_RATE("bridge_rate_limited", "this bridge is sending prompts too fast",
        "share_rate_limited", "you're sending messages too fast — slow down"),
    // ---- folder-share (issue #115): guest-first codes (the bridge fields mirror them) ----
    SHARE_EXPIRED("share_expired", "this folder share has expired"),
    OUT_OF_SCOPE("share_out_of_scope", "the target is outside this share's folder"),
}

/** The outcome of vetting one inbound bridge frame. [Allow] may carry a rewritten frame (workdir
 *  canonicalized, mode clamped, force stripped) that MUST replace the original before routing. */
sealed interface BridgeVerdict {
    data class Allow(val frame: Frame) : BridgeVerdict
    data class Deny(val code: BridgeDenyCode) : BridgeVerdict
}

/**
 * Per-credential enforcement of a bridge's CONSTRAINTS and RATE/CONCURRENCY limits (issue #91 §2/§3/§7).
 * One instance per live bridge deviceId, created by [dev.ccpocket.daemon.relay.DeviceSessions] once a
 * bridge's E2E session establishes. Not thread-safe on its own; the relay pumps one device's frames
 * sequentially, and this instance is only touched from that pump plus the convo-open callback (both on
 * the same DeviceSessions mutex path), so a plain object with @Synchronized on the mutating methods is
 * enough.
 *
 * Session OWNERSHIP: a bridge may only prompt/cancel/close/resume conversations it itself opened, tracked
 * by convoId (returned by the router's onOpened callback) and by the sessionId the daemon later mints
 * (learned via [noteSession] when a SessionLive for an owned convo is observed). This closes transcript
 * exfiltration (resume someone else's session) and session hijack.
 */
class BridgeGuard(val spec: BridgeSpec) {
    // insertion-ordered + bounded: a long-lived bridge opens thousands of convos over its life, and the
    // ownership ledger must not grow without limit. Concurrency is enforced against LIVE convos
    // (liveOwned), so evicting the oldest id here only affects whether a very old, long-closed convo is
    // still recognized as "own" — which it never legitimately is (it was reaped ages ago).
    private val ownedConvos = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (added && size > MAX_TRACKED_CONVOS) iterator().let { it.next(); it.remove() }
            return added
        }
    }
    private val ownedSessions = HashSet<String>()     // sessionIds the daemon minted for this bridge's convos
    private val openTimes = ArrayDeque<Long>()         // session.open timestamps in the last minute
    private val promptTimes = ArrayDeque<Long>()       // prompt timestamps in the last minute
    private val roots: List<String> = spec.workdirs.mapNotNull { canonical(File(it)) }

    /** Vet an inbound frame. Returns [BridgeVerdict.Allow] (possibly rewritten) or a [BridgeVerdict.Deny].
     *  The caller enforces the top-level type whitelist via [BridgeCaps.ingressAllowed] BEFORE calling
     *  this — so only the four admitted request types ever reach here. [liveOwned] is how many of this
     *  bridge's conversations are STILL LIVE in the registry (the caller computes it for opens):
     *  concurrency budgets live sessions, not the historical ledger — an idle-reaped session must not
     *  eat a slot forever. */
    @Synchronized
    fun vet(frame: Frame, now: Long, liveOwned: Int = 0): BridgeVerdict = when (frame) {
        is OpenSession -> vetOpen(frame, now, liveOwned)
        is SendPrompt -> vetPrompt(frame, now)
        is CancelTurn -> if (owns(frame.convoId)) BridgeVerdict.Allow(frame) else BridgeVerdict.Deny(BridgeDenyCode.NOT_OWN_SESSION)
        // force=false always: a bridge may leave its own view, never force-kill a busy session
        is CloseSession -> if (owns(frame.convoId)) BridgeVerdict.Allow(frame.copy(force = false)) else BridgeVerdict.Deny(BridgeDenyCode.NOT_OWN_SESSION)
        else -> BridgeVerdict.Deny(BridgeDenyCode.FORBIDDEN)
    }

    /** Snapshot of the convoIds this bridge ever opened — the caller intersects it with live registry
     *  state to compute [vet]'s liveOwned. */
    @Synchronized
    fun ownedConvoIds(): Set<String> = ownedConvos.toSet()

    private fun vetOpen(f: OpenSession, now: Long, liveOwned: Int): BridgeVerdict {
        val wd = canonical(File(f.workdir)) ?: return BridgeVerdict.Deny(BridgeDenyCode.BAD_WORKDIR)
        if (roots.none { underRoot(wd, it) }) return BridgeVerdict.Deny(BridgeDenyCode.BAD_WORKDIR)
        // resume only OWN sessions: a bridge cannot resume (and thus read the transcript of) another
        // client's session, nor take one over
        val resume = f.resumeId
        if (resume != null && resume !in ownedSessions) return BridgeVerdict.Deny(BridgeDenyCode.NOT_OWN_SESSION)
        if (liveOwned >= spec.maxSessions) return BridgeVerdict.Deny(BridgeDenyCode.TOO_MANY_SESSIONS)
        if (!admit(openTimes, spec.opensPerMin, now)) return BridgeVerdict.Deny(BridgeDenyCode.OPEN_RATE)
        // clamp: mode capped (never bypass), take-over forbidden, workdir canonicalized. The daemon —
        // not the bridge — is the authority on these; the rewritten frame is what gets routed.
        return BridgeVerdict.Allow(
            f.copy(workdir = wd, mode = BridgeCaps.clampMode(f.mode, spec.tier), takeOver = false),
        )
    }

    private fun vetPrompt(f: SendPrompt, now: Long): BridgeVerdict {
        if (!owns(f.convoId)) return BridgeVerdict.Deny(BridgeDenyCode.NOT_OWN_SESSION)
        if (f.text.length > MAX_PROMPT_CHARS) return BridgeVerdict.Deny(BridgeDenyCode.PROMPT_TOO_LARGE)
        if (f.images.isNotEmpty()) return BridgeVerdict.Deny(BridgeDenyCode.IMAGES_DENIED)
        if (!admit(promptTimes, spec.promptsPerMin, now)) return BridgeVerdict.Deny(BridgeDenyCode.PROMPT_RATE)
        return BridgeVerdict.Allow(f)
    }

    /** Called by DeviceSessions when the router reports this frame opened [convoId] for this bridge. */
    @Synchronized
    fun noteOpened(convoId: String) { ownedConvos.add(convoId) }

    /** Called when a SessionLive for an owned convo carries the minted sessionId — lets a later
     *  `open(resumeId=that)` be recognized as own. Bounded so a long-lived bridge can't grow unbounded. */
    @Synchronized
    fun noteSession(convoId: String, sessionId: String) {
        if (convoId in ownedConvos) {
            ownedSessions.add(sessionId)
            if (ownedSessions.size > MAX_TRACKED_SESSIONS) ownedSessions.iterator().let { it.next(); it.remove() }
        }
    }

    /** A convo this bridge opened closed — free a concurrency slot. */
    @Synchronized
    fun noteClosed(convoId: String) { ownedConvos.remove(convoId) }

    private fun owns(convoId: String) = convoId in ownedConvos

    /** Sliding-window admission: keep only timestamps within the last minute; admit if under [perMin]. */
    private fun admit(window: ArrayDeque<Long>, perMin: Int, now: Long): Boolean {
        while (window.isNotEmpty() && now - window.peekFirst() >= 60_000) window.pollFirst()
        if (window.size >= perMin) return false
        window.addLast(now)
        return true
    }

    private companion object {
        const val MAX_PROMPT_CHARS = 32 * 1024 // §3: prompt ≤ 32KiB — bounds a single trigger's blast radius
        const val MAX_TRACKED_SESSIONS = 256
        const val MAX_TRACKED_CONVOS = 256

        fun canonical(f: File): String? = runCatching { f.canonicalFile.path }.getOrNull()

        /** True if [child] is [root] itself or a descendant — path-segment aware so `/a/bees` is NOT
         *  under `/a/be` (a raw startsWith would wrongly admit it). */
        fun underRoot(child: String, root: String): Boolean =
            child == root || child.startsWith(root + File.separator)
    }
}
