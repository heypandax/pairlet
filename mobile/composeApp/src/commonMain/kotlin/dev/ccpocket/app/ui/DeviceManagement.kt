package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * The paired-computers list + add / rename / remove, shared by the disconnected picker (ConnectScreen)
 * and the Settings sheet. [onSwitch] fires when a row is tapped — the caller decides what "switch" means
 * (connect from the picker, or dismiss-then-switch from Settings). Rename/remove are handled in place.
 */
@Composable
fun DeviceList(repo: PocketRepository, onSwitch: (PairedDaemon) -> Unit, onAdd: () -> Unit) {
    var renaming by remember { mutableStateOf<PairedDaemon?>(null) }
    var removing by remember { mutableStateOf<PairedDaemon?>(null) }
    val activeId = repo.paired.value?.accountId
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(12.dp)),
    ) {
        repo.pairedList.forEachIndexed { i, d ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            DeviceRow(
                d, active = d.accountId == activeId,
                onTap = { onSwitch(d) },
                onRename = { renaming = d },
                onRemove = { removing = d },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, null, tint = Tok.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(Res.string.add_device), color = Tok.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    // Dialogs render in their own overlay window — safe to host here even though DeviceList itself sits
    // inside the Settings sheet / a scroll column (a nested PocketSheet would get unbounded constraints).
    renaming?.let { d ->
        RenameDeviceDialog(d, onSave = { repo.renameDaemon(d, it); renaming = null }, onDismiss = { renaming = null })
    }
    removing?.let { d ->
        RemoveDeviceDialog(d, onConfirm = { repo.unpair(d); removing = null }, onDismiss = { removing = null })
    }
}

/** One bound computer: status dot + name + account id, with rename/remove actions. */
@Composable
private fun DeviceRow(d: PairedDaemon, active: Boolean, onTap: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onTap).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Tok.ok else Tok.hair))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.displayName(), color = Tok.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.device_active), color = Tok.ok, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                d.accountCaption(), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
            )
        }
        IconButton(onRename, Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Edit, stringResource(Res.string.device_rename), tint = Tok.tx2, modifier = Modifier.size(17.dp))
        }
        IconButton(onRemove, Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Delete, stringResource(Res.string.device_remove), tint = Tok.danger, modifier = Modifier.size(17.dp))
        }
    }
}

/** The account-id caption shown under a device name (mono, truncated). */
private fun PairedDaemon.accountCaption() = "${accountId.take(16)}…"

/** Give a computer a local nickname (blank clears it back to the account id). */
@Composable
private fun RenameDeviceDialog(d: PairedDaemon, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(d.label ?: "") }
    DeviceDialog(
        title = stringResource(Res.string.rename_device_title),
        confirmLabel = stringResource(Res.string.device_save), confirmColor = Tok.accent,
        onConfirm = { onSave(text.trim()) }, onDismiss = onDismiss,
    ) {
        Text(d.accountCaption(), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
        OutlinedTextField(
            text, { text = it }, singleLine = true,
            label = { Text(stringResource(Res.string.rename_device_hint)) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

/** Confirm removing a binding — it requires re-pairing afterwards, so guard it. */
@Composable
private fun RemoveDeviceDialog(d: PairedDaemon, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DeviceDialog(
        title = stringResource(Res.string.remove_device_title),
        confirmLabel = stringResource(Res.string.device_remove), confirmColor = Tok.danger,
        onConfirm = onConfirm, onDismiss = onDismiss,
    ) {
        Text(d.displayName(), color = Tok.tx2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.remove_device_confirm), color = Tok.tx2, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Shared themed confirm dialog: title + a body column + a colored confirm and a muted Cancel. */
@Composable
private fun DeviceDialog(
    title: String, confirmLabel: String, confirmColor: Color,
    onConfirm: () -> Unit, onDismiss: () -> Unit, body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tok.raised,
        titleContentColor = Tok.tx,
        textContentColor = Tok.tx2,
        title = { Text(title) },
        text = { Column { body() } },
        confirmButton = { TextButton(onConfirm) { Text(confirmLabel, color = confirmColor) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(Res.string.cancel), color = Tok.muted) } },
    )
}
