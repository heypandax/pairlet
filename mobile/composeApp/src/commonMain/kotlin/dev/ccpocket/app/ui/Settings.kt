package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

// keep in sync with versionName in mobile/composeApp/build.gradle.kts (no BuildConfig in commonMain)
private const val APP_VERSION = "0.1.0"

/**
 * Settings sheet: the paired-computers list (switch / add / rename / remove — see [DeviceList]) plus the
 * About rows. The single-device "Unpair" button is gone — removal is now per-device inside the list.
 */
@Composable
fun SettingsSheet(repo: PocketRepository, onAddDevice: () -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 4.dp)) {
            Text(stringResource(Res.string.settings_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SectionLabel(stringResource(Res.string.devices_section))
            // tapping a row in Settings switches to that computer; close the sheet so the new link is in view
            DeviceList(repo, onSwitch = { onDismiss(); repo.switchDaemon(it) }, onAdd = onAddDevice)

            SectionLabel(stringResource(Res.string.notifications_section))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                ToggleRow(
                    label = stringResource(Res.string.notify_on_complete),
                    sub = stringResource(Res.string.notify_on_complete_sub),
                    checked = repo.notificationsOn.value,
                    onChange = { repo.setNotificationsEnabled(it) },
                )
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
