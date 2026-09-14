package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── managed session list and explicit import (issue #360, phase two) ───────────────────────────────────
//
// The daemon owns, per project, WHICH native Claude/Codex sessions belong to the user's Pairlet list and in WHAT
// order (one project-wide order across agents). Scanning the native history only supplies metadata for those
// members and the candidates of an explicit import; an outside terminal creating or touching a native session
// never adds, removes or reorders a member.
//
// Negotiation, per connection, deny-by-omission both ways:
//  - the daemon advertises [DaemonInfo.supportsManagedSessions] and the agent wire names it actually manages in
//    [DaemonInfo.managedAgents]; a client sends a managed.* request only when the flag is true AND every agent the
//    request names is listed. An older daemon omits both (false / empty) and would drop the unknown frame.
//  - the client declares [ClientCaps.supportsManagedSessions]; the daemon gates [ManagedSessionsState] and
//    [DiscoveredSessions] per connection AT EMISSION — replies and pushes alike — so a connection whose declaration
//    has not arrived receives neither. Rows are additionally filtered by the connection's [ClientCaps.supportsAgents].
//  - the legacy [ListSessions] / [Sessions] pair is unchanged in shape and behaviour for EVERY client, capable or
//    not. A capable client renders an agent from the managed list only once that agent is READY; agents that are
//    UNINITIALIZED or not in [DaemonInfo.managedAgents] keep coming from [Sessions].
//
// Agents are never defaulted: a request whose agent is missing or unknown to the daemon (decoded as null) is refused
// with [ManagedSessionErrors.INVALID_REQUEST]; a reply row whose agent this client cannot decode reads as null and
// the client drops that row.
//
// Every request carries a client-minted [requestId], echoed on its reply, so a reply that lands after the user
// switched project/computer/connection can be dropped. The daemon re-validates [workdir] itself and names its
// canonical identity in [ManagedSessionsState.canonicalWorkdir]. The whole surface is OWNER-only: guest, bridge and
// collaborator connections get [ManagedSessionErrors.FORBIDDEN] before any directory or title is read.
//
// After any durable change (import, remove, enable, a Pairlet-created session being registered) the daemon pushes
// the FIRST page of the project's all-agents list, WITHOUT requestId, to every other capable owner connection.
// [ManagedSessionsState.revision] is comparable only for the same [ManagedSessionsState.canonicalWorkdir] on the
// same computer; a client applies a push only when it is newer, then re-fetches remaining pages itself.
//
// Frame budget: the daemon packs every managed.state / managed.discovered frame to at most
// [MANAGED_FRAME_BUDGET_BYTES] of encoded UTF-8 (JSON escaping included) and continues on the next page via a cursor.
// Row text is cut by UTF-8 bytes, a row never repeats the project path, and groups are capped, so a single row always
// fits a page.
//
// Enum-valued fields declare defaults: every build that can declare the capability decodes an unknown future value
// to that default (PocketJson.coerceInputValues). Refusal and diagnostic vocabularies are plain strings; clients map
// unknown values to their generic "internal" / "unknown" bucket.

/** Most members one (project, agent) list may hold. An import/migration whose result would exceed it is refused
 *  (a migration refused this way leaves the agent on the legacy list). */
const val MANAGED_SESSIONS_MAX: Int = 1000

/** Encoded UTF-8 size cap of one managed.state / managed.discovered frame; further rows continue on the next page. */
const val MANAGED_FRAME_BUDGET_BYTES: Int = 3 * 1024 * 1024

/** [SessionSummary.title] / [ManagedSessionEntry.lastKnownTitle] / [DiscoveredSession.title]: UTF-8 bytes kept. */
const val MANAGED_TITLE_MAX_BYTES: Int = 512

/** First-prompt preview in managed and discovery rows: UTF-8 bytes kept. */
const val MANAGED_PROMPT_PREVIEW_MAX_BYTES: Int = 256

/** Other free-text row fields (git branch, version, model): UTF-8 bytes kept. */
const val MANAGED_META_MAX_BYTES: Int = 128

/** Most [SessionGroup]s one managed.state frame carries (the daemon's own per-project group cap). */
const val MANAGED_GROUPS_MAX: Int = 100

/** Rows one discovery page may return; larger [DiscoverSessions.limit] values are clamped. */
const val MANAGED_DISCOVER_PAGE_MAX: Int = 50

/** Page size used when [DiscoverSessions.limit] is absent or not positive. */
const val MANAGED_DISCOVER_PAGE_DEFAULT: Int = 30

/** Most pages one discovery walk may follow; past it the reply is incomplete with [ManagedScanDiagnostics.PAGE_LIMIT]. */
const val MANAGED_DISCOVER_MAX_PAGES: Int = 40

/** Longest search text accepted; longer queries are refused as [ManagedSessionErrors.INVALID_REQUEST]. */
const val MANAGED_DISCOVER_QUERY_MAX_CHARS: Int = 200

/** Bounds of the opaque [requestId] / native session id values, and of a cursor. */
const val MANAGED_ID_MAX_CHARS: Int = 128
const val MANAGED_CURSOR_MAX_CHARS: Int = 256

/** Longest project path either end accepts (same bound as project pins). */
const val MANAGED_WORKDIR_MAX_CHARS: Int = 2048

/** Structural check of an opaque identifier (requestId, native session id): 1..[MANAGED_ID_MAX_CHARS] of
 *  `[A-Za-z0-9._:-]`, never `.`/`..`. Whether the session exists and whose it is stays the daemon's call. */
fun isValidManagedId(id: String): Boolean =
    id.length in 1..MANAGED_ID_MAX_CHARS && id != "." && id != ".." &&
        id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' || it == ':' }

/** Structural check of a project path both ends share (no C0 controls, no DEL); canonical identity is the daemon's. */
fun isValidManagedWorkdir(path: String): Boolean =
    path.isNotBlank() && path.length <= MANAGED_WORKDIR_MAX_CHARS && path.none { it.code < 0x20 || it.code == 0x7F }

/** [text] cut to at most [maxBytes] of UTF-8 without splitting a code point. */
fun truncateUtf8(text: String, maxBytes: Int): String {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val cp = text[i]
        val pair = cp.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val size = when {
            pair -> 4
            cp.code < 0x80 -> 1
            cp.code < 0x800 -> 2
            else -> 3
        }
        if (bytes + size > maxBytes) break
        bytes += size
        i += if (pair) 2 else 1
    }
    return if (i == text.length) text else text.substring(0, i)
}

/** Whether an agent's migration to the managed list has happened for a project. */
@Serializable
enum class ManagedMigrationState {
    /** The legacy scan-driven [Sessions] list still applies for this agent; nothing is filtered. */
    @SerialName("uninitialized") UNINITIALIZED,

    /** Membership and order are the managed list; new outside sessions only appear in discovery. */
    @SerialName("ready") READY,
}

/** How a member entered the managed list. Informational only — never an authorization input. */
@Serializable
enum class ManagedSessionOrigin {
    /** Created through Pairlet; registered once the backend reported its native id. */
    @SerialName("created_here") CREATED_HERE,

    /** Added by an explicit [ImportSession]. */
    @SerialName("explicit_import") EXPLICIT_IMPORT,

    /** Carried over from the visible legacy list when the agent was migrated. */
    @SerialName("legacy_adopted") LEGACY_ADOPTED,
}

/** Whether a member's native record was found by the daemon's latest scan. */
@Serializable
enum class ManagedAvailability {
    /** Found: [ManagedSessionEntry.summary] carries its live metadata. */
    @SerialName("available") AVAILABLE,

    /** A COMPLETE scan did not find it (deleted/moved). The member is kept; show "original record unavailable". */
    @SerialName("missing") MISSING,

    /** The scan was incomplete (error, truncation, permission), so absence proves nothing. Show last-known data. */
    @SerialName("unknown") UNKNOWN,
}

/** One managed agent's state within a project. [agent] null = an agent this client cannot decode: drop the row.
 *  [scanComplete] false = member availability is UNKNOWN for this agent; [diagnostic] says why. */
@Serializable
data class ManagedAgentStatus(
    val agent: AgentKind? = null,
    val migration: ManagedMigrationState = ManagedMigrationState.UNINITIALIZED,
    val scanComplete: Boolean = false,
    val diagnostic: String? = null,
)

/**
 * One member of a managed list, in list order. [agent] null = an agent this client cannot decode: drop the row.
 *
 * [summary] is the live scan row when [availability] is AVAILABLE. Its [SessionSummary.cwd] is EMPTY — the row
 * belongs to [ManagedSessionsState.canonicalWorkdir] — its text fields are cut by UTF-8 bytes, and its
 * [SessionSummary.group] is the daemon's group assignment. Only when [summary] is absent do [lastKnownTitle] /
 * [lastKnownModified] carry a display fallback; they are never a path or a permission. [group] repeats the group
 * assignment so an unavailable member still files under its group.
 *
 * [groupAmbiguous] is true when the same native id is also a session of ANOTHER agent in this project: group
 * assignments are keyed by bare session id, so which agent a legacy group membership belongs to cannot be proven —
 * the client shows the known placement with an "attribution unclear" marker and never re-files it.
 */
@Serializable
data class ManagedSessionEntry(
    val sessionId: String,
    val agent: AgentKind? = null,
    val origin: ManagedSessionOrigin = ManagedSessionOrigin.LEGACY_ADOPTED,
    val createdAt: Long = 0,
    val availability: ManagedAvailability = ManagedAvailability.UNKNOWN,
    val summary: SessionSummary? = null,
    val lastKnownTitle: String? = null,
    val lastKnownModified: Long? = null,
    val group: String? = null,
    val groupAmbiguous: Boolean = false,
)

// ── requests (client -> daemon, owner only, only after capability negotiation) ─────────────────────────

/**
 * Fetch the managed list of [workdir]: exactly one of [agent] (that agent only) or [allAgents] = true (every agent in
 * [DaemonInfo.managedAgents], in ONE project-wide order) must be set; anything else is refused with
 * [ManagedSessionErrors.INVALID_REQUEST]. [cursor] is the previous page's [ManagedSessionsState.nextCursor] (null =
 * first page); a cursor from another project / scope / store revision is refused with
 * [ManagedSessionErrors.CURSOR_INVALID] and the client restarts from the first page. Reply: one [ManagedSessionsState].
 */
@Serializable
@SerialName("pocket/managed.list")
data class ListManagedSessions(
    val requestId: String,
    val workdir: String,
    val agent: AgentKind? = null,
    val allAgents: Boolean = false,
    val cursor: String? = null,
) : ToDaemon

/**
 * Switch [agent] under [workdir] to the managed list — the one-time "keep my current sessions, import later ones"
 * consent the client explains before sending. The daemon takes a COMPLETE scan, adopts the currently visible sessions
 * in the legacy list's order as [ManagedSessionOrigin.LEGACY_ADOPTED], and commits members + order + READY in one
 * durable write. Idempotent: an already READY agent is answered with its list and absorbs nothing. An incomplete scan
 * is refused with [ManagedSessionErrors.SCAN_INCOMPLETE] (+ [ManagedSessionsState.diagnostic]); a result over
 * [MANAGED_SESSIONS_MAX] with [ManagedSessionErrors.CAPACITY]; either way the agent stays UNINITIALIZED.
 * [agent] is required. Reply: one [ManagedSessionsState] (first page) for [agent].
 */
@Serializable
@SerialName("pocket/managed.enable")
data class EnableManagedSessions(
    val requestId: String,
    val workdir: String,
    val agent: AgentKind? = null,
) : ToDaemon

/**
 * Search the native history of ONE [agent] (required) under [workdir] for import candidates. Read-only metadata:
 * nothing is opened, resumed or registered. Matching is a case-insensitive substring of title, first prompt or
 * session id; a blank [query] matches everything. Rows are newest first (lastModified descending), members included
 * and flagged [DiscoveredSession.alreadyManaged]. [cursor] is the previous page's opaque
 * [DiscoveredSessions.nextCursor] (null = first page), valid only for the same workdir / agent / query; [limit] is
 * clamped to 1..[MANAGED_DISCOVER_PAGE_MAX] rows and a page also ends at the frame budget. Reply: one [DiscoveredSessions].
 */
@Serializable
@SerialName("pocket/managed.discover")
data class DiscoverSessions(
    val requestId: String,
    val workdir: String,
    val agent: AgentKind? = null,
    val query: String = "",
    val cursor: String? = null,
    val limit: Int = MANAGED_DISCOVER_PAGE_DEFAULT,
) : ToDaemon

/**
 * Add native session [sessionId] of [agent] (required) under [workdir] to the managed list. The daemon re-verifies
 * `(agent, workdir, sessionId)` against its own scan and trusts no client path or title; the member lands at the top
 * of the ungrouped part of the list. Importing an existing member succeeds with [ManagedSessionsState.alreadyManaged].
 * Nothing is copied, rewritten, opened or resumed; no prompt is sent. Reply: one [ManagedSessionsState] (first page)
 * with [ManagedSessionsState.sessionId] and [ManagedSessionsState.entry], sent only after the store write is durable.
 */
@Serializable
@SerialName("pocket/managed.import")
data class ImportSession(
    val requestId: String,
    val workdir: String,
    val agent: AgentKind? = null,
    val sessionId: String,
) : ToDaemon

/**
 * Take [sessionId] of [agent] (required) out of the managed list under [workdir]. Only the registration is dropped —
 * the native transcript, its group assignment and archive state are untouched, and it can be imported again. Removing
 * a non-member succeeds with `changed = false`. Distinct from archiving. Reply: one [ManagedSessionsState] (first
 * page), sent only after the store write is durable; other capable owner connections get the push described above.
 */
@Serializable
@SerialName("pocket/managed.remove")
data class RemoveManagedSession(
    val requestId: String,
    val workdir: String,
    val agent: AgentKind? = null,
    val sessionId: String,
) : ToDaemon

// ── replies (daemon -> client, only to a connection that declared ClientCaps.supportsManagedSessions) ──

/**
 * One page of a project's managed list — the reply to list / enable / import / remove, and the push after a change.
 *
 * [requestId] echoes the request; null marks a PUSH. [workdir] echoes the request's spelling (a push names the
 * canonical path). [agent] / [allAgents] echo the request's scope (a push is `allAgents = true`). [canonicalWorkdir]
 * is the daemon's identity of the project (null when the path was refused); [revision] grows with every durable
 * change to the project and is only comparable for the same [canonicalWorkdir] on the same computer.
 *
 * Paging: [items] is one page in list order. [nextCursor] non-null means more members follow — send
 * [ListManagedSessions] with it; [complete] is true only on the last page. [agents] and [groups] ride every page.
 *
 * [agents] and [items] are NULL — never empty lists — whenever the store could not be read (corrupt, unknown schema,
 * IO failure) or the request was refused before reading it; a client then keeps its previous display rather than
 * showing "no sessions". For an agent that is UNINITIALIZED, [items] contains only members registered so far, and the
 * client keeps rendering that agent from [Sessions].
 *
 * [readOnly] is true when the stored file exists but this build cannot vouch for it: the file is preserved as is and
 * every mutation is refused with [ManagedSessionErrors.STORE_CORRUPT].
 *
 * On a refusal [error] is one of [ManagedSessionErrors], [diagnostic] one of [ManagedScanDiagnostics] when a scan was
 * the reason, and nothing was committed. For import/remove, [sessionId] names the session; [changed] is false for an
 * idempotent repeat; [alreadyManaged] is true when an import found an existing member; [entry] is the imported
 * member's row so the client can locate it directly even when it is not on this page. [groups] are the project's
 * session groups (at most [MANAGED_GROUPS_MAX]).
 */
@Serializable
@SerialName("pocket/managed.state")
data class ManagedSessionsState(
    val requestId: String? = null,
    val workdir: String,
    val agent: AgentKind? = null,
    val allAgents: Boolean = false,
    val canonicalWorkdir: String? = null,
    val revision: Long? = null,
    val agents: List<ManagedAgentStatus>? = null,
    val items: List<ManagedSessionEntry>? = null,
    val nextCursor: String? = null,
    val complete: Boolean = false,
    val groups: List<SessionGroup>? = null,
    val readOnly: Boolean = false,
    val sessionId: String? = null,
    val changed: Boolean = false,
    val alreadyManaged: Boolean = false,
    val entry: ManagedSessionEntry? = null,
    val error: String? = null,
    val diagnostic: String? = null,
    val message: String? = null,
) : ToPhone

/** One import candidate. [agent] null = undecodable: drop the row. Text is cut by UTF-8 bytes; [alreadyManaged] is
 *  true when it is already a member of this agent's managed list (show "imported" instead of an import button). */
@Serializable
data class DiscoveredSession(
    val sessionId: String,
    val agent: AgentKind? = null,
    val title: String = "",
    val firstPrompt: String = "",
    val lastModified: Long = 0,
    val messageCount: Int = 0,
    val live: Boolean = false,
    val alreadyManaged: Boolean = false,
)

/**
 * One discovery page, newest first. [nextCursor] is non-null when more rows match; pass it back unchanged. [complete]
 * is true only when the underlying scan finished successfully AND this is the last page — an empty [items] with
 * [complete] false never means "no sessions". [diagnostic] (one of [ManagedScanDiagnostics]) says why a page is
 * incomplete; rows of a PARTIAL / TRUNCATED scan are still real and importable. A refusal carries [error], no rows.
 */
@Serializable
@SerialName("pocket/managed.discovered")
data class DiscoveredSessions(
    val requestId: String,
    val workdir: String,
    val agent: AgentKind? = null,
    val items: List<DiscoveredSession> = emptyList(),
    val nextCursor: String? = null,
    val complete: Boolean = false,
    val diagnostic: String? = null,
    val error: String? = null,
    val message: String? = null,
) : ToPhone

/** The bounded vocabulary of scan diagnostics. Unknown values mean "incomplete, reason unknown". */
object ManagedScanDiagnostics {
    /** The scan succeeded and every row was read. */
    const val COMPLETE = "complete"
    /** Some native records could not be read (or cannot be addressed); the rest are listed. */
    const val PARTIAL = "partial"
    /** The backend caps how many records it walks and the history reached that cap. */
    const val TRUNCATED = "truncated"
    /** The native history directory could not be read for lack of permission. */
    const val PERMISSION_DENIED = "permission_denied"
    /** The scan failed outright. */
    const val ERROR = "error"
    /** The discovery walk hit [MANAGED_DISCOVER_MAX_PAGES]. */
    const val PAGE_LIMIT = "page_limit"
}

/** The bounded refusal vocabulary of [ManagedSessionsState.error] and [DiscoveredSessions.error]. */
object ManagedSessionErrors {
    /** The connection is not the owner (guest, bridge, collaborator). Nothing was read. */
    const val FORBIDDEN = "managed_forbidden"
    /** This daemon does not manage the agent (see [DaemonInfo.managedAgents]) or has no store wired. */
    const val UNSUPPORTED = "managed_unsupported"
    /** Malformed requestId / session id / cursor / query, a field over its bound, a missing or unknown agent, or a
     *  list request naming neither or both of agent / allAgents. */
    const val INVALID_REQUEST = "managed_invalid_request"
    /** The workdir fails validation: not an existing readable absolute directory, or outside what this connection may use. */
    const val INVALID_WORKDIR = "managed_invalid_workdir"
    /** Import: a COMPLETE scan of (agent, workdir) does not contain the session. */
    const val NOT_FOUND = "managed_not_found"
    /** Enable / import could not be proven because the scan was incomplete; see [ManagedSessionsState.diagnostic]. */
    const val SCAN_INCOMPLETE = "managed_scan_incomplete"
    /** The result would exceed [MANAGED_SESSIONS_MAX]. */
    const val CAPACITY = "managed_capacity"
    /** The stored file exists but cannot be decoded or has an unknown schema; it is left untouched (read-only). */
    const val STORE_CORRUPT = "managed_store_corrupt"
    /** The store could not be read or durably written; nothing was acknowledged. After a write whose outcome is
     *  unknown the project is refused until the daemon restarts and re-reads it. */
    const val STORE_UNAVAILABLE = "managed_store_unavailable"
    /** The cursor belongs to another workdir / scope / query / store revision, or has expired; restart from page one. */
    const val CURSOR_INVALID = "managed_cursor_invalid"
}
