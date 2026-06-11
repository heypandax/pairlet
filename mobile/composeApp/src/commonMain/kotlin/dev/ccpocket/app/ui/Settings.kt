package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

// keep in sync with versionName in mobile/composeApp/build.gradle.kts (no BuildConfig in commonMain)
private const val APP_VERSION = "0.1.0"

/**
 * Minimal v1 settings sheet (design: Settings.html, reduced per the v1 audit): the About rows plus
 * the Unpair escape hatch. Default-mode / device management / appearance are P2.
 */
@Composable
fun SettingsSheet(paired: PairedDaemon?, onUnpair: () -> Unit, onDismiss: () -> Unit) {
    PocketSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 4.dp)) {
            Text(stringResource(Res.string.settings_title), color = Tok.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Text(
                stringResource(Res.string.about_section), color = Tok.muted, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))) {
                AboutRow(stringResource(Res.string.about_version), APP_VERSION)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                AboutRow(stringResource(Res.string.about_license), "MIT")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
                AboutRow(
                    stringResource(Res.string.about_connection),
                    paired?.let { "relay · ${it.accountId.take(12)}…" } ?: "direct LAN",
                )
            }

            Box(
                Modifier.padding(top = 18.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Tok.danger.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onUnpair),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.unpair), color = Tok.danger, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Tok.tx2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, maxLines = 1)
    }
}
