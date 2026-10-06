package dev.ccpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── read-only session observation (docs/design/DOTS-SESSION-OBSERVABILITY.md, P1) ─────────────────────────
//
// A managed member can carry a Pairlet-OWN observation binding: "this native session is driven by something else
// (today: an OpenAI Dot task); Pairlet only watches it". The binding is Pairlet metadata — nothing here is an OpenAI
// field, nothing is read from the Dot, and the native transcript is never rewritten. The daemon's persisted binding is
// the ONLY authorization input: a client-sent `readOnly` or source is a request, never a fact.
//
// Negotiation, deny-by-omission both ways, exactly like the managed list:
//  - the daemon advertises [DaemonInfo.supportsSessionObservationV1]; a client sends [SetSessionObservation],
//    [ImportSession.observation] or [OpenSession.observeOnly] only when it is true. An older daemon drops the unknown
//    frame / key, so a client must NOT assume a silently-ignored `observeOnly` took effect.
//  - the client declares [ClientCaps.supportsSessionObservationV1]; the daemon fills [SessionSummary.observation],
//    [ManagedSessionEntry.observation] and [SessionLive.observation] only for such a connection. An older client never
//    sees them (and keeps today's `observing = true` + notice for a read-only open).
//
// Every enum-like value is a plain string: a value this build does not know reads as its "unknown" bucket, never as a
// permission or a terminal state.

/** Where an observed session's task comes from. Only [OPENAI_DOT] is defined today. */
object ObservationSources {
    const val OPENAI_DOT = "openai_dot"
}

/** How a binding's source was established. [VERIFIED] is reserved for a documented, machine-checked origin — no such
 *  field exists in a Codex rollout today, so every binding a client can create is [USER_ASSIGNED]. */
object ObservationAttributions {
    const val VERIFIED = "verified"
    const val USER_ASSIGNED = "user_assigned"
}

/** [ObservedProgress.state]: what the NATIVE evidence says about the session's current turn. */
object ObservedStates {
    const val UNKNOWN = "unknown"
    const val RUNNING = "running"
    const val WAITING_INPUT = "waiting_input"
    const val IDLE = "idle"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    val ALL: Set<String> = setOf(UNKNOWN, RUNNING, WAITING_INPUT, IDLE, FAILED, CANCELLED)
    fun normalize(raw: String?): String = raw?.takeIf { it in ALL } ?: UNKNOWN
}

/** [ObservedProgress.evidence]: a real turn lifecycle record, or merely "the transcript grew". */
object ObservedEvidence {
    const val NATIVE_EVENT = "native_event"
    const val TRANSCRIPT_ACTIVITY = "transcript_activity"
}

/** [ObservedProgress.freshness]: how recent the evidence is — independent of what it says. */
object ObservedFreshness {
    const val CURRENT = "current"
    const val STALE = "stale"
    const val UNAVAILABLE = "unavailable"
    const val UNKNOWN = "unknown"
    val ALL: Set<String> = setOf(CURRENT, STALE, UNAVAILABLE, UNKNOWN)
    fun normalize(raw: String?): String = raw?.takeIf { it in ALL } ?: UNKNOWN
}

/** A non-terminal observed state older than this is shown as "last recorded: …" (stale). A UI freshness threshold,
 *  not a task timeout: the state itself is kept. */
const val OBSERVATION_STALE_AFTER_MS: Long = 60_000L

/** Bounds of the free-text binding fields. */
const val OBSERVATION_SOURCE_MAX_CHARS: Int = 64
const val OBSERVATION_REF_MAX_CHARS: Int = 512
const val OBSERVATION_ACTION_MAX_CHARS: Int = 160

/**
 * Pairlet's own record that a managed member is observed on behalf of an outside task. [source] / [attribution] use
 * the vocabularies above; [parentTaskRef] is display metadata only — a task title or an `https://` link the user
 * supplied, never executed, never dereferenced by the daemon. [readOnly] true (the only value a client may set today)
 * makes every control request on the session refuse server-side.
 */
@Serializable
data class ObservationBinding(
    val source: String = ObservationSources.OPENAI_DOT,
    val attribution: String = ObservationAttributions.USER_ASSIGNED,
    val parentTaskRef: String? = null,
    val readOnly: Boolean = true,
)

private fun cleanText(s: String, max: Int): Boolean = s.length <= max && s.none { it.code < 0x20 || it.code == 0x7F }

/** Structural check of a client-supplied binding. [attribution] must be [ObservationAttributions.USER_ASSIGNED] — a
 *  client cannot declare a source verified — and [readOnly] must be true: a writable binding is meaningless today. */
fun isValidObservationBinding(b: ObservationBinding): Boolean =
    isValidManagedId(b.source) && b.source.length <= OBSERVATION_SOURCE_MAX_CHARS &&
        b.attribution == ObservationAttributions.USER_ASSIGNED &&
        b.readOnly &&
        (b.parentTaskRef == null || (b.parentTaskRef.isNotBlank() && cleanText(b.parentTaskRef, OBSERVATION_REF_MAX_CHARS)))

/** Whether [ref] may be offered as a tappable link: only an absolute `https://` URL, nothing else. */
fun isObservationLink(ref: String?): Boolean =
    ref != null && ref.startsWith("https://") && ref.length <= OBSERVATION_REF_MAX_CHARS && ref.none { it.isWhitespace() || it.code < 0x20 }

/**
 * The current-turn progress the daemon can PROVE from the native record. [scope] is always `turn` in v1: this is one
 * sub-session's latest turn, never the parent task. [state] / [evidence] / [freshness] are the string vocabularies
 * above. [sourceEventAt] is the native record's own timestamp for the deciding event; [lastActivityAt] the newest
 * business activity (a new record, not a re-read); [observedAt] when the daemon last successfully looked.
 * [currentAction] is a short evidence-backed summary of the running tool/step, or null. [turnId] only when the record
 * carries one.
 */
@Serializable
data class ObservedProgress(
    val scope: String = "turn",
    val state: String = ObservedStates.UNKNOWN,
    val evidence: String? = null,
    val sourceEventAt: Long? = null,
    val lastActivityAt: Long? = null,
    val observedAt: Long = 0,
    val freshness: String = ObservedFreshness.UNKNOWN,
    val currentAction: String? = null,
    val turnId: String? = null,
)

/** What a session row / live announce carries for an observed member: the binding (null = not bound), whether this
 *  open is read-only (binding or a one-shot `observeOnly`), and the latest progress snapshot (null = none derivable). */
@Serializable
data class SessionObservation(
    val binding: ObservationBinding? = null,
    val readOnly: Boolean = false,
    val progress: ObservedProgress? = null,
)

/**
 * Owner-only: set or clear the observation binding of managed member [sessionId] of [agent] under [workdir]. A null
 * [binding] clears it. Setting one is refused with [ManagedSessionErrors.OBSERVATION_CONFLICT] while this daemon holds
 * a controllable conversation on the session — the running driver is never interrupted or demoted — and with
 * [ManagedSessionErrors.OBSERVATION_INVALID] for a binding that fails [isValidObservationBinding]. Reply: one
 * [ManagedSessionsState] (first page) whose [ManagedSessionsState.entry] carries the member with its new binding; other
 * capable owner connections get the usual push.
 */
@Serializable
@SerialName("pocket/managed.observe")
data class SetSessionObservation(
    val requestId: String,
    val workdir: String,
    val agent: AgentKind? = null,
    val sessionId: String,
    val binding: ObservationBinding? = null,
) : ToDaemon

/** [PocketError.code] values of a read-only open / control refusal. */
object ObservationErrors {
    /** `observeOnly` / a bound session was opened but no readable native record exists for it: nothing was created. */
    const val OBSERVE_UNAVAILABLE = "observe_unavailable"
    /** A control request (take-over, rename, rewind, …) targeted a session whose persisted binding is read-only. */
    const val READ_ONLY = "session_read_only"
}
