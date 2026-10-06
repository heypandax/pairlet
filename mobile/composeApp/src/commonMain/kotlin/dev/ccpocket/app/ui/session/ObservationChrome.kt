package dev.ccpocket.app.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.relativeTime
import dev.ccpocket.protocol.ObservationAttributions
import dev.ccpocket.protocol.ObservationBinding
import dev.ccpocket.protocol.ObservedFreshness
import dev.ccpocket.protocol.ObservedProgress
import dev.ccpocket.protocol.ObservedStates
import dev.ccpocket.protocol.SessionObservation
import org.jetbrains.compose.resources.stringResource

/*
 * Read-only session observation (docs/design/DOTS-SESSION-OBSERVABILITY.md §4.3 / §4.4): the written form of an
 * observed member's binding and progress, shared by the phone's chat bar, the desktop's observe bar, the session
 * list's meta line and the session-info sheet. One vocabulary, one place — a stale "running" is never "complete",
 * a finished turn is never "task done", an unknown value is "unknown".
 */

/** "Dot (linked by you)" — how a binding's source is written. Only user-assigned bindings exist today. */
@Composable
fun observationSourceText(binding: ObservationBinding): String = when (binding.attribution) {
    ObservationAttributions.USER_ASSIGNED -> stringResource(Res.string.obs_source_user_assigned)
    else -> binding.source
}

/** The one-line progress reading: state word, "last recorded" when stale, and the running tool when known. */
@Composable
fun observedProgressText(progress: ObservedProgress?): String {
    if (progress == null) return stringResource(Res.string.obs_no_snapshot)
    val freshness = ObservedFreshness.normalize(progress.freshness)
    if (freshness == ObservedFreshness.UNAVAILABLE) return stringResource(Res.string.obs_unavailable)
    val state = ObservedStates.normalize(progress.state)
    val word = stringResource(
        when (state) {
            ObservedStates.RUNNING -> Res.string.obs_state_running
            ObservedStates.WAITING_INPUT -> Res.string.obs_state_waiting
            ObservedStates.IDLE -> Res.string.obs_state_idle
            ObservedStates.FAILED -> Res.string.obs_state_failed
            ObservedStates.CANCELLED -> Res.string.obs_state_cancelled
            else -> if (progress.lastActivityAt != null) Res.string.obs_state_unknown_activity else Res.string.obs_state_unknown
        },
    )
    val nonTerminal = state == ObservedStates.RUNNING || state == ObservedStates.WAITING_INPUT || state == ObservedStates.UNKNOWN
    val parts = buildList {
        add(if (nonTerminal && freshness == ObservedFreshness.STALE) "${stringResource(Res.string.obs_last_recorded)}: $word" else word)
        progress.currentAction?.takeIf { it.isNotBlank() && state == ObservedStates.RUNNING }?.let { add(stringResource(Res.string.obs_current_action, it)) }
        progress.lastActivityAt?.takeIf { it > 0 }?.let { add(stringResource(Res.string.obs_updated_at, relativeTime(it))) }
    }
    return parts.joinToString(" · ")
}

/**
 * Replaces the composer (and the "Continue here" button) for a read-only observe view: says who drives the session,
 * that input belongs in that app, and what the record proves right now. No control of any kind lives here.
 */
@Composable
fun ObservedReadOnlyBar(observation: SessionObservation, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (observation.binding != null) Res.string.obs_readonly_bar else Res.string.obs_readonly_bar_unbound),
                color = Tok.tx2, fontSize = 13.sp, style = tightCenter(13.sp), modifier = Modifier.weight(1f),
            )
            observation.binding?.let {
                Spacer(Modifier.width(8.dp))
                Text(observationSourceText(it), color = Tok.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(11.5.sp))
            }
        }
        Text(observedProgressText(observation.progress), color = Tok.tx, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
