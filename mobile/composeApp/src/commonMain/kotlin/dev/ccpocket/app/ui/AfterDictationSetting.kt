package dev.ccpocket.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.AppUpdateRoute
import dev.ccpocket.app.appUpdateRoute
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.VoiceAfterDictation
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.settings_after_dictation
import dev.ccpocket.app.resources.settings_after_dictation_compose
import dev.ccpocket.app.resources.settings_after_dictation_compose_sub
import dev.ccpocket.app.resources.settings_after_dictation_send
import dev.ccpocket.app.resources.settings_after_dictation_send_sub
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.session.Hairline
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Voice input v2's setting (README "后续决定", review §11 "已确认"): what ✓ does after dictation. A value row in
 * Settings › General › Voice input opens a two-choice sheet; choosing "Correct and send" before the one-time
 * disclosure was accepted on this device opens [VoiceRefineDisclosureSheet] instead, and only its "Turn on" gets
 * there. The repository refuses SEND without the acknowledgement ([PocketRepository.setVoiceAfterDictation]), so that
 * refusal is what decides when the disclosure is due.
 */
internal enum class AfterDictationSheet { CHOICE, DISCLOSURE }

/** The phone apps host the voice input v2 send bar; the desktop app's chat does not (review §11 A6), so nothing there
 *  offers "Correct and send". The desktop app has its own settings — [SettingsScreen] renders on the desktop JVM only
 *  in tests, where this keeps the row off like on any desktop. */
internal fun hostsVoiceSendBar(): Boolean = appUpdateRoute() != AppUpdateRoute.DESKTOP_IN_APP

/** [hostsVoiceSendBar] as Settings reads it — a local, so the desktop JVM tests can render a phone's General page. */
internal val LocalHostsVoiceSendBar = staticCompositionLocalOf { hostsVoiceSendBar() }

/** What ✓ actually does here: SEND counts only once the disclosure was accepted on this device — until then the bar
 *  is today's — so that is the value the setting shows and the sheet ticks. */
internal fun PocketRepository.effectiveAfterDictation(): VoiceAfterDictation =
    if (voiceRefineAcked.value) voiceAfterDictation.value else VoiceAfterDictation.COMPOSE

private fun afterDictationTitle(value: VoiceAfterDictation): StringResource = when (value) {
    VoiceAfterDictation.COMPOSE -> Res.string.settings_after_dictation_compose
    VoiceAfterDictation.SEND -> Res.string.settings_after_dictation_send
}

private fun afterDictationSub(value: VoiceAfterDictation): StringResource = when (value) {
    VoiceAfterDictation.COMPOSE -> Res.string.settings_after_dictation_compose_sub
    VoiceAfterDictation.SEND -> Res.string.settings_after_dictation_send_sub
}

/**
 * "After dictation", its current value inline and a chevron — the auto-lock row's grammar, flat between hairlines
 * like the Whisper switch above it ([topHairline] false when that switch already drew the line). Label and value
 * share the width, so 200% type wraps both instead of squeezing the label away.
 */
@Composable
internal fun AfterDictationRow(value: VoiceAfterDictation, topHairline: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (topHairline) Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // tightCenter on every text in the row: three sizes side by side (AGENTS.md)
            Text(
                stringResource(Res.string.settings_after_dictation), color = Tok.tx, fontSize = 14.sp, style = tightCenter(14.sp),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                stringResource(afterDictationTitle(value)), color = Tok.tx2, fontSize = 13.sp, style = tightCenter(13.sp),
                textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Text("›", color = Tok.muted, fontSize = 16.sp, style = tightCenter(16.sp))
        }
        Hairline()
    }
}

/** Host for the setting's sheets, over the whole Settings screen. [sheet] null = none; [onSheet] moves between them. */
@Composable
internal fun AfterDictationSheets(repo: PocketRepository, sheet: AfterDictationSheet?, onSheet: (AfterDictationSheet?) -> Unit) {
    when (sheet) {
        null -> {}
        AfterDictationSheet.CHOICE -> AfterDictationChoiceSheet(
            current = repo.effectiveAfterDictation(),
            onPick = { onSheet(chooseAfterDictation(repo, it)) },
            onDismiss = { onSheet(null) },
        )
        AfterDictationSheet.DISCLOSURE -> VoiceRefineDisclosureSheet(
            onTurnOn = {
                repo.acknowledgeVoiceRefineDisclosure()
                repo.setVoiceAfterDictation(VoiceAfterDictation.SEND)
                onSheet(null)
            },
            // "Not now", the scrim, back and a drag all leave the setting where it was
            onNotNow = { onSheet(null) },
        )
    }
}

/** Apply a pick; the sheet to show next — the disclosure when "Correct and send" still needs it, else none. */
internal fun chooseAfterDictation(repo: PocketRepository, choice: VoiceAfterDictation): AfterDictationSheet? =
    if (repo.setVoiceAfterDictation(choice)) null else AfterDictationSheet.DISCLOSURE

/** The two choices, each with what ✓ will do — the auto-lock sheet's single-choice grammar. */
@Composable
private fun AfterDictationChoiceSheet(current: VoiceAfterDictation, onPick: (VoiceAfterDictation) -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                stringResource(Res.string.settings_after_dictation), color = Tok.muted, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 18.dp, top = 4.dp, bottom = 6.dp),
            )
            listOf(VoiceAfterDictation.COMPOSE, VoiceAfterDictation.SEND).forEach { option ->
                val sel = current == option
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) { selected = sel }
                        .clickable(role = Role.RadioButton) { onPick(option) }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(afterDictationTitle(option)), color = if (sel) Tok.tx else Tok.tx2, fontSize = 15.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            stringResource(afterDictationSub(option)), color = Tok.muted, fontSize = 12.sp, lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (sel) Text("✓", color = Tok.accent, fontSize = 14.sp, style = tightCenter(14.sp), modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}
