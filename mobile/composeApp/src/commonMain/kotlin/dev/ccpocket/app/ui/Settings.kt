package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.APP_VERSION
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

// new-session default effort: the canonical levels (shared with the live /effort picker) + a leading
// null = "model default". Hoisted so it isn't rebuilt on every Settings recomposition.
private val EFFORT_DEFAULT_OPTS: List<String?> = listOf(null) + EFFORT_OPTIONS

/**
 * Settings as a full screen (not a sheet): per-session preferences — notifications, the new-session default
 * mode + reasoning effort, About, and Exit. Paired computers are managed on the disconnected picker
 * (ConnectScreen), reached via Exit — not here. [onBack] returns to the screen that opened it.
 */
@Composable
fun SettingsScreen(repo: PocketRepository, onBack: () -> Unit) {
    // back closes Settings — register a handler so it doesn't fall through to the app-level navigation
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton({ onBack() }) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Text(stringResource(Res.string.settings_title), color = Tok.tx, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 20.dp),
        ) {

            // Paired computers are NOT managed here — switching/adding happens on the disconnected picker
            // (ConnectScreen) reached via Exit below. Keeps Settings to per-session preferences.

            SectionLabel(stringResource(Res.string.notifications_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                ToggleRow(
                    label = stringResource(Res.string.notify_on_complete),
                    sub = stringResource(Res.string.notify_on_complete_sub),
                    checked = repo.notificationsOn.value,
                    onChange = { repo.setNotificationsEnabled(it) },
                )
            }

            SectionLabel(stringResource(Res.string.default_mode_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                MODES.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                    val sel = repo.defaultMode.value == m.key
                    Row(
                        Modifier.fillMaxWidth().clickable { repo.setDefaultMode(m.key) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("●", color = m.color, fontSize = 9.sp, modifier = Modifier.padding(end = 10.dp))
                        Text(stringResource(m.label), color = if (sel) Tok.accent else Tok.tx, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (sel) Text("✓", color = Tok.accent, fontSize = 13.5.sp)
                    }
                }
            }

            SectionLabel(stringResource(Res.string.default_effort_section))
            val effortDefaultLabel = stringResource(Res.string.value_default)
            // horizontal segmented control: a surface track with equal-width segments; the selected one
            // fills with accent (thumb), the rest stay flush with the track
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EFFORT_DEFAULT_OPTS.forEach { opt ->
                    val sel = repo.defaultEffort.value == opt
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                            .then(if (sel) Modifier.background(Tok.accent) else Modifier)
                            .clickable { repo.setDefaultEffort(opt) }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            opt ?: effortDefaultLabel,
                            color = if (sel) Tok.base else Tok.tx2,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                        )
                    }
                }
            }

            SectionLabel(stringResource(Res.string.about_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                AboutRow(stringResource(Res.string.about_version), APP_VERSION)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                AboutRow(stringResource(Res.string.about_license), "MIT")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                AboutRow(
                    stringResource(Res.string.about_connection),
                    repo.paired.value?.displayName() ?: "direct LAN",
                )
            }

            // Exit -> disconnect to the computer picker (ConnectScreen), where paired computers are managed.
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(12.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
                    .clickable { onBack(); repo.disconnect() }.padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.exit), color = Tok.danger, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A small uppercase group heading, shared by the settings groups. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = Tok.muted, fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** A settings row with a title + subtitle on the left and a Switch on the right. */
@Composable
private fun ToggleRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = Tok.tx, fontSize = 14.sp)
            Text(sub, color = Tok.muted, fontSize = 11.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A label/value row used by the settings + session-info sheets (label left, mono value right). */
@Composable
fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 1)
    }
}
