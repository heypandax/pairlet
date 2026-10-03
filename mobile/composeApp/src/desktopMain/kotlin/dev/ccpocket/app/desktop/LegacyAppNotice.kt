package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.brand_dismiss
import dev.ccpocket.app.resources.legacy_app_found
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource
import java.io.File

private const val LEGACY_APP_NOTICE_KEY = "pairlet_legacy_app_notice_v1"

/**
 * The pre-rename bundle still installed next to a Pairlet-named one, or null. The release publishes both
 * macOS images; someone who had "CC Pocket.app" and then downloads the new dmg ends up with two bundles
 * that share one bundle id and one data store. Nothing breaks, but Dock/Spotlight show both — the notice
 * below says the old one can go. Only ever non-null when THIS process runs from "Pairlet.app".
 */
internal fun legacyAppBeside(root: File?, applications: File = File("/Applications")): File? {
    if (root?.name != "Pairlet.app") return null
    return listOfNotNull(root.parentFile, applications).map { File(it, "CC Pocket.app") }.firstOrNull { it.isDirectory }
}

/** In-flow, dismissible, shown once: same shape as the rename notice it sits under. */
@Composable
fun LegacyAppNotice() {
    val legacy = remember { legacyAppBeside(DesktopUpdater.packagedAppRoot()) } ?: return
    var visible by remember { mutableStateOf(SecureStore.getString(LEGACY_APP_NOTICE_KEY) == null) }
    if (!visible) return
    Surface(color = Tok.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.legacy_app_found, legacy.path), color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { SecureStore.putString(LEGACY_APP_NOTICE_KEY, "done"); visible = false }) {
                Text(stringResource(Res.string.brand_dismiss), color = Tok.accent)
            }
        }
    }
}
