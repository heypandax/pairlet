package dev.ccpocket.app.ui.handoff

import androidx.compose.runtime.Composable
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.*
import dev.ccpocket.protocol.HandoffBrief
import dev.ccpocket.protocol.HandoffFinding
import dev.ccpocket.protocol.HandoffResult
import dev.ccpocket.protocol.HandoffStatus
import dev.ccpocket.protocol.SessionHandoff
import dev.ccpocket.protocol.AgentKind
import org.jetbrains.compose.resources.stringResource

// Wire → display mapping. UNKNOWN maps to null: a state this build can't read renders nothing
// rather than guessing (the daemon still refuses input on its side — App display never authorizes).

/** Whether the current backend may expose the owner-side Session Handoff entry. ZCode's
 * collaborator grant is deliberately fail-closed until its approval boundary is proven. */
fun AgentKind.canInitiateSessionHandoff(): Boolean = this != AgentKind.ZCODE

fun HandoffStatus.toUi(): HandoffUiStatus? = when (this) {
    HandoffStatus.WAITING -> HandoffUiStatus.WAITING
    HandoffStatus.IN_PROGRESS -> HandoffUiStatus.IN_PROGRESS
    HandoffStatus.RETURNED -> HandoffUiStatus.RETURNED
    HandoffStatus.COMPLETED -> HandoffUiStatus.COMPLETED
    HandoffStatus.DECLINED -> HandoffUiStatus.DECLINED
    HandoffStatus.CANCELLED -> HandoffUiStatus.CANCELLED
    HandoffStatus.EXPIRED -> HandoffUiStatus.EXPIRED
    HandoffStatus.RECALLED -> HandoffUiStatus.RECALLED
    HandoffStatus.DRAFT, HandoffStatus.UNKNOWN -> null
}

fun findingSeverityOf(wire: String): FindingSeverity = when (wire) {
    HandoffFinding.SEVERITY_CRITICAL, HandoffFinding.SEVERITY_HIGH -> FindingSeverity.HIGH
    HandoffFinding.SEVERITY_MEDIUM -> FindingSeverity.MEDIUM
    else -> FindingSeverity.LOW
}

fun HandoffFinding.toUi() = HandoffFindingUi(
    severity = findingSeverityOf(severity),
    title = title,
    fileLine = file?.let { f -> if (line != null) "$f:$line" else f },
)

fun HandoffResult.toUi(returnedByLabel: String, durationLabel: String? = null) = HandoffResultUi(
    verdict = verdict,
    findings = findings.map { it.toUi() },
    verifications = verification.map { HandoffVerifyUi(it, pass = null) },
    nextSteps = recommendedNextSteps,
    returnedByLabel = returnedByLabel,
    durationLabel = durationLabel,
)

@Composable
fun HandoffBrief.toSections(): List<HandoffBriefSectionUi> = buildList {
    originalGoal?.takeIf { it.isNotBlank() }?.let { add(HandoffBriefSectionUi(stringResource(Res.string.ho_brief_goal), it)) }
    if (completedWork.isNotEmpty()) add(HandoffBriefSectionUi(stringResource(Res.string.ho_brief_done), items = completedWork))
    currentState?.takeIf { it.isNotBlank() }?.let { add(HandoffBriefSectionUi(stringResource(Res.string.ho_brief_state), it)) }
    request.takeIf { it.isNotBlank() }?.let { add(HandoffBriefSectionUi(stringResource(Res.string.ho_brief_ask), it)) }
    if (focusAreas.isNotEmpty()) add(HandoffBriefSectionUi(stringResource(Res.string.ho_brief_focus), items = focusAreas))
}

/** "H7QX-2MRD"-style short code derived from the handoff id (display + manual entry fallback). */
fun SessionHandoff.shortCode(): String {
    val cleaned = id.filter { it.isLetterOrDigit() }.uppercase().ifEmpty { "--------" }
    val eight = (cleaned + "00000000").take(8)
    return eight.take(4) + "-" + eight.drop(4)
}

/** The invite payload the QR carries (v1: an in-account claim; the HANDOFF credential leg follows). */
fun SessionHandoff.inviteBlob(): String = "ccpocket-handoff:$id"

/** "23:59:12"-style countdown to [SessionHandoff.expiresAt]. */
fun SessionHandoff.expiresCountdown(nowMs: Long = epochMillis()): String {
    val left = (expiresAt - nowMs).coerceAtLeast(0) / 1000
    val h = left / 3600; val m = (left % 3600) / 60; val s = left % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${two(h)}:${two(m)}:${two(s)}"
}

/** Compact elapsed-since label ("18m" / "3h12m") for accepted/returned stamps. */
fun elapsedLabel(sinceMs: Long?, nowMs: Long = epochMillis()): String? {
    if (sinceMs == null || sinceMs <= 0) return null
    val sec = ((nowMs - sinceMs) / 1000).coerceAtLeast(0)
    val h = sec / 3600; val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h}h${m}m"
        m > 0 -> "${m}m"
        else -> "${sec}s"
    }
}

@Composable
fun SessionHandoff.toHistoryItem(nowMs: Long = epochMillis()): HandoffHistoryItemUi? {
    val ui = status.toUi() ?: return null
    val sub = buildString {
        elapsedLabel(createdAt, nowMs)?.let { append(it) }
        result?.let { r ->
            if (r.findings.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(r.findings.size)
            }
        }
    }
    return HandoffHistoryItemUi(
        id = id,
        recipientLabel = recipientLabel ?: "?",
        status = ui,
        subLine = sub,
    )
}
