package dev.ccpocket.app.data

import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.DirectoryEntry

/**
 * The cross-project session WORKING SET (issue #165): the handful of sessions a user is actually moving
 * between right now, assembled purely client-side from data the phone already has — the daemon's project
 * list (which sessions are alive) plus a local most-recently-opened memory. No wire change, no daemon work.
 *
 * Everything here is pure so the switcher's whole behavior — MRU order, the cap, running/recent dedupe,
 * the finished-while-away signal — is unit-testable without a link, a repo, or a composition. The
 * repository holds the state and calls into these; the UI reads [SessionWorkingSet].
 */

/** How many sessions the MRU remembers. Small on purpose: this is "what am I juggling", not a history. */
const val WORKING_SET_MAX = 10

/**
 * One remembered session: durable identity ([dirKey] + [sessionId]) plus the labels needed to render a row
 * without any live data, because the whole point is showing sessions whose project isn't loaded right now.
 * [at] is when it was last opened ON THIS DEVICE (epoch ms) — the MRU sort key.
 */
data class WorkingSetEntry(
    val dirKey: String,
    val sessionId: String,
    val title: String,
    val project: String,
    val at: Long,
    val agent: AgentKind? = null,
)

/** A session the daemon reports as alive right now, lifted out of the project list. */
data class RunningSession(
    val dirKey: String,
    val sessionId: String,
    val title: String,
    val project: String,
    val agent: AgentKind?,
    /** mid-turn right now (vs merely holding background work) — lets a row say "running" vs "active". */
    val executing: Boolean,
)

/** One switcher row. [current] marks the session on screen; [running] and [unseen] drive its badges. */
data class SessionSwitcherItem(
    val dirKey: String,
    val sessionId: String,
    val title: String,
    val project: String,
    val running: Boolean,
    val executing: Boolean,
    val current: Boolean,
    val unseen: Boolean,
    val agent: AgentKind? = null,
    /** when this device last opened it (epoch ms); 0 for a running session never opened here. */
    val lastOpenedAt: Long = 0,
)

/**
 * The switcher's whole read-model. [running] and [recent] are disjoint (running wins) and BOTH exclude
 * [current], so [otherCount] is exactly "how many other sessions could I jump to" — the top-bar chip's
 * number. [attention] is the chip's badge: something happened somewhere else that you haven't seen.
 */
data class SessionWorkingSet(
    val running: List<SessionSwitcherItem>,
    val recent: List<SessionSwitcherItem>,
    val current: SessionSwitcherItem?,
    val otherCount: Int,
    val attention: Boolean,
)

val EMPTY_WORKING_SET = SessionWorkingSet(emptyList(), emptyList(), null, 0, false)

/** Last path segment of a project dir, separator-agnostic (a Windows daemon's paths use '\'). */
fun projectLabelOf(dirKey: String): String {
    val trimmed = dirKey.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return dirKey
    val cut = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return if (cut in 0 until trimmed.lastIndex) trimmed.substring(cut + 1) else trimmed
}

/**
 * Record an open: newest first, one entry per session, capped at [limit]. Re-opening a remembered session
 * MOVES it to the head (and refreshes its labels) instead of duplicating it — the previous session must be
 * findable at a stable place, which only holds if the list is a true MRU.
 */
fun mruTouch(list: List<WorkingSetEntry>, entry: WorkingSetEntry, limit: Int = WORKING_SET_MAX): List<WorkingSetEntry> =
    (listOf(entry) + list.filterNot { it.sessionId == entry.sessionId }).take(limit)

/**
 * The sessions the daemon says are alive, one row per session — the SAME rule the home screen's ACTIVE
 * section uses (`open || busy` at the project level, then one row per [DirectoryEntry.activeSessions]),
 * with the legacy single-session fields as the fallback for an older daemon that omits the array.
 */
fun runningSessions(dirs: List<DirectoryEntry>): List<RunningSession> =
    dirs.filter { it.open || it.busy }.flatMap { e ->
        val project = e.name.ifBlank { projectLabelOf(e.path) }
        if (e.activeSessions.isNotEmpty()) {
            e.activeSessions.map { s ->
                RunningSession(
                    dirKey = e.path, sessionId = s.sessionId,
                    title = s.title?.takeIf { it.isNotBlank() } ?: project,
                    project = project, agent = s.agent, executing = s.executing || s.busy,
                )
            }
        } else {
            // older daemon: only the single activeSessionId/Title pair. Its agent is unknowable here —
            // null, so a tap falls back to the resume path's own Claude default rather than inventing one.
            listOfNotNull(
                e.activeSessionId?.let { sid ->
                    RunningSession(
                        dirKey = e.path, sessionId = sid,
                        title = e.activeSessionTitle?.takeIf { it.isNotBlank() } ?: project,
                        project = project, agent = null, executing = e.executing || e.busy,
                    )
                },
            )
        }
    }.distinctBy { it.sessionId }

/**
 * Fold one project-list refresh into the finished-while-away set: a session that WAS working and no longer
 * is (finished, or its process went away entirely) becomes "unseen" until it's opened. The session on
 * screen is never marked — you watched it finish. Deliberately client-side and best-effort: it needs no
 * protocol support, and a missed edge just means one missing dot, never a wrong one.
 */
fun markFinishedAway(
    prevWorking: Set<String>,
    nowWorking: Set<String>,
    currentSessionId: String?,
    unseen: Set<String>,
): Set<String> {
    val finished = prevWorking - nowWorking - setOfNotNull(currentSessionId)
    return if (finished.isEmpty()) unseen else unseen + finished
}

/**
 * Assemble the switcher's read-model. [running] keeps the daemon's order (executing-first per project,
 * projects newest-first); [recent] is MRU order MINUS anything already shown as running — so the previous
 * session sits at the top of `recent` unless it's still running, in which case it's at the top of `running`.
 * Either way it's the first row of the sheet, which is the one guarantee the "jump back" gesture needs.
 *
 * [approvals] is the seam for "another session is waiting on you": today the daemon binds an ask to the
 * connection that opened its conversation, so the phone only ever holds the CURRENT session's ask and this
 * arrives empty — [attention] then means finished-while-away only. When asks go account-wide, passing their
 * session ids here lights the same badge with no other change.
 */
fun buildWorkingSet(
    running: List<RunningSession>,
    mru: List<WorkingSetEntry>,
    currentSessionId: String?,
    currentDirKey: String? = null,
    currentTitle: String? = null,
    unseen: Set<String> = emptySet(),
    approvals: Set<String> = emptySet(),
): SessionWorkingSet {
    val runningById = running.associateBy { it.sessionId }
    val mruById = mru.associateBy { it.sessionId }

    val current = currentSessionId?.let { sid ->
        val r = runningById[sid]
        val m = mruById[sid]
        SessionSwitcherItem(
            dirKey = r?.dirKey ?: m?.dirKey ?: currentDirKey ?: "",
            sessionId = sid,
            title = currentTitle?.takeIf { it.isNotBlank() }
                ?: m?.title ?: r?.title ?: projectLabelOf(currentDirKey ?: ""),
            project = r?.project ?: m?.project ?: projectLabelOf(currentDirKey ?: ""),
            running = r != null, executing = r?.executing == true,
            current = true, unseen = false, // the session you're looking at is seen by definition
            agent = r?.agent ?: m?.agent, lastOpenedAt = m?.at ?: 0,
        )
    }

    val runningItems = running.filterNot { it.sessionId == currentSessionId }.map { r ->
        val m = mruById[r.sessionId]
        SessionSwitcherItem(
            dirKey = r.dirKey, sessionId = r.sessionId,
            // a title the user actually saw beats the daemon's derived one for a session opened here
            title = m?.title?.takeIf { it.isNotBlank() } ?: r.title,
            project = r.project, running = true, executing = r.executing,
            current = false, unseen = r.sessionId in unseen,
            agent = r.agent ?: m?.agent, lastOpenedAt = m?.at ?: 0,
        )
    }
        // Most-recently-visited first, same rule `recent` follows — so "the session I just came from" is
        // the top row whether or not it happens to be running. Sorting only `recent` left the ordering
        // conditional on something the user can't see (whether the daemon still calls it alive): step out
        // of a session that is still working and it jumped into RUNNING at a position decided by the
        // project list. Sessions this device never opened have at = 0 and settle at the bottom in the
        // daemon's own order (a stable sort keeps it) — there is no visit to rank them by.
        .sortedByDescending { it.lastOpenedAt }

    val shown = runningItems.map { it.sessionId }.toSet() + setOfNotNull(currentSessionId)
    val recentItems = mru.filterNot { it.sessionId in shown }.map { m ->
        SessionSwitcherItem(
            dirKey = m.dirKey, sessionId = m.sessionId, title = m.title, project = m.project,
            running = false, executing = false, current = false, unseen = m.sessionId in unseen,
            agent = m.agent, lastOpenedAt = m.at,
        )
    }

    val others = runningItems + recentItems
    return SessionWorkingSet(
        running = runningItems,
        recent = recentItems,
        current = current,
        otherCount = others.size,
        attention = others.any { it.unseen } || approvals.any { it != currentSessionId },
    )
}

// ── persistence (SecureStore, same dependency-free encoding the pinned/session-params stores use) ──────
// TSV per line, labels last and sanitized: titles come from prompts and may contain anything, so tabs and
// newlines are flattened to spaces on write rather than escaped — the labels are display-only.

private fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

fun encodeWorkingSet(list: List<WorkingSetEntry>): String =
    list.joinToString("\n") { e ->
        listOf(e.dirKey, e.sessionId, e.at.toString(), e.agent?.name ?: "", flat(e.project), flat(e.title)).joinToString("\t")
    }

fun decodeWorkingSet(raw: String?): List<WorkingSetEntry> {
    raw ?: return emptyList()
    return raw.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val t = line.split('\t')
        if (t.size < 6) return@mapNotNull null
        val at = t[2].toLongOrNull() ?: return@mapNotNull null
        WorkingSetEntry(
            dirKey = t[0], sessionId = t[1], at = at,
            agent = t[3].takeIf { it.isNotEmpty() }?.let { n -> AgentKind.entries.firstOrNull { it.name == n } },
            project = t[4], title = t[5],
        )
    }.filter { it.dirKey.isNotEmpty() && it.sessionId.isNotEmpty() }.take(WORKING_SET_MAX).toList()
}
