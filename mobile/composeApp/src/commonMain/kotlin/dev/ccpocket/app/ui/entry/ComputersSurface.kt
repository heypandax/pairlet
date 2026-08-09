package dev.ccpocket.app.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.data.ConnPhase
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.defaultDaemonUrl
import dev.ccpocket.app.pairing.BindingRole
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.computers_account
import dev.ccpocket.app.resources.computers_active_binding
import dev.ccpocket.app.resources.computers_add
import dev.ccpocket.app.resources.computers_helper
import dev.ccpocket.app.resources.computers_more
import dev.ccpocket.app.resources.computers_role_guest
import dev.ccpocket.app.resources.computers_role_owner
import dev.ccpocket.app.resources.computers_section
import dev.ccpocket.app.resources.computers_title
import dev.ccpocket.app.resources.conn_computer_offline_hint
import dev.ccpocket.app.resources.conn_invalid_body
import dev.ccpocket.app.resources.conn_invalid_title
import dev.ccpocket.app.resources.conn_offline_body
import dev.ccpocket.app.resources.conn_offline_title
import dev.ccpocket.app.resources.conn_relay_body
import dev.ccpocket.app.resources.conn_relay_title
import dev.ccpocket.app.resources.conn_repair
import dev.ccpocket.app.resources.conn_retry
import dev.ccpocket.app.resources.connect_direct
import dev.ccpocket.app.resources.daemon_ws_url
import dev.ccpocket.app.resources.device_remove
import dev.ccpocket.app.resources.device_rename
import dev.ccpocket.app.resources.exit
import dev.ccpocket.app.resources.pair_route_lan
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.RemoveDeviceDialog
import dev.ccpocket.app.ui.RenameDeviceDialog
import dev.ccpocket.app.ui.resolve
import dev.ccpocket.app.ui.session.Hairline
import org.jetbrains.compose.resources.stringResource

/**
 * "Computers" — choose a paired computer, or recover a failing connection (Entry Flow UI 2.0 · Master
 * frames 02, 09, 10, 11).
 *
 * ONE surface for both jobs, because they are the same moment: the failing phase gets the single raised
 * region on the screen and the paired list stays flat underneath, so switching machines reads as an ordinary
 * choice rather than a second alarm.
 *
 * Rows carry only what `PairedDaemon` supplies — display name, account caption, role, and which binding is
 * active. No latency, no last seen, no OS, and a `directUrl` is never treated as an online indicator.
 */
@Composable
fun ComputersSurface(
    repo: PocketRepository,
    modifier: Modifier = Modifier,
    recovery: ConnRecoveryUi? = null,
    onSwitch: (PairedDaemon) -> Unit,
    onAdd: () -> Unit,
) {
    var showLan by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(defaultDaemonUrl()) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metric.gutter),
    ) {
        Spacer(Modifier.height(28.dp))
        EntryTitle(stringResource(Res.string.computers_title), null)
        recovery?.takeIf { it.blocks }?.let {
            ConnRecoveryRegion(it, repo, Modifier.padding(top = Metric.gapL))
        }

        EntryLabel(stringResource(Res.string.computers_section), Modifier.padding(top = 26.dp, bottom = Metric.gapS))
        PairedComputerRows(repo, onSwitch)
        EntryNote(stringResource(Res.string.computers_helper), Modifier.padding(top = Metric.gap))
        Text(
            repo.status.value.resolve(), color = Tok.muted, style = TypeRole.captionMono,
            modifier = Modifier.padding(top = Metric.gapS),
        )

        Spacer(Modifier.height(20.dp))
        EntryRouteRow(stringResource(Res.string.computers_add), onClick = onAdd)
        EntryRouteRow(stringResource(Res.string.pair_route_lan), expanded = showLan) { showLan = !showLan }
        if (showLan) {
            OutlinedTextField(
                url, { url = it }, placeholder = { Text(stringResource(Res.string.daemon_ws_url)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = Metric.gapS),
            )
            EntrySecondaryButton(stringResource(Res.string.connect_direct)) { repo.startDirect(url) }
        }
        Hairline()
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The recovery region for a failing [ConnPhase] — one shape, one sentence, one canonical action and at most
 * one secondary. Each action maps straight onto the repository effect it names; nothing new is invented.
 *
 * `Pair again` deliberately KEEPS the dead binding (`beginAddDevice`, which has a real Cancel back path) and
 * `Remove` is the separate, explicit destruction — the old single "Pair again" silently did both.
 */
@Composable
fun ConnRecoveryRegion(recovery: ConnRecoveryUi, repo: PocketRepository, modifier: Modifier = Modifier) {
    val title = stringResource(
        when (recovery.phase) {
            ConnPhase.RelayUnreachable -> Res.string.conn_relay_title
            ConnPhase.ComputerOffline -> Res.string.conn_offline_title
            else -> Res.string.conn_invalid_title
        },
    )
    val body = stringResource(
        when (recovery.phase) {
            ConnPhase.RelayUnreachable -> Res.string.conn_relay_body
            ConnPhase.ComputerOffline -> Res.string.conn_offline_body
            else -> Res.string.conn_invalid_body
        },
    )
    EntryStateBlock(
        recovery, title, body, modifier,
        hint = stringResource(Res.string.conn_computer_offline_hint).takeIf { recovery.hasDaemonHint },
    ) {
        // the computer this is about, named — a recovery sentence with no subject is a guess
        repo.paired.value?.displayName()?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Tok.tx, style = TypeRole.action)
        }
        recovery.actions.forEachIndexed { i, action ->
            val label = stringResource(
                when (action) {
                    ConnActionId.RETRY -> Res.string.conn_retry
                    ConnActionId.EXIT -> Res.string.exit
                    ConnActionId.PAIR_AGAIN -> Res.string.conn_repair
                    ConnActionId.REMOVE -> Res.string.device_remove
                },
            )
            val run = {
                when (action) {
                    ConnActionId.RETRY -> repo.retryConnection()
                    ConnActionId.EXIT -> repo.disconnect()
                    ConnActionId.PAIR_AGAIN -> repo.beginAddDevice()
                    ConnActionId.REMOVE -> repo.unpairActive()
                }
                Unit
            }
            if (i == 0) EntryPrimaryButton(label, onClick = run) else EntrySecondaryButton(label, onClick = run)
        }
    }
}

/** The paired list: low-container entity rows, hairline separated, no card stack. */
@Composable
private fun PairedComputerRows(repo: PocketRepository, onSwitch: (PairedDaemon) -> Unit) {
    var renaming by remember { mutableStateOf<PairedDaemon?>(null) }
    var removing by remember { mutableStateOf<PairedDaemon?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val activeId = repo.paired.value?.accountId
    Column(Modifier.fillMaxWidth()) {
        repo.pairedList.forEach { d ->
            ComputerRow(
                d,
                active = d.accountId == activeId,
                menuOpen = menuFor == d.accountId,
                onTap = { onSwitch(d) },
                onMenu = { menuFor = if (menuFor == d.accountId) null else d.accountId },
                onRename = { menuFor = null; renaming = d },
                onRemove = { menuFor = null; removing = d },
            )
        }
        Hairline()
    }
    // Dialogs render in their own overlay window — safe to host inside a scrolling column.
    renaming?.let { d ->
        RenameDeviceDialog(d, onSave = { repo.renameDaemon(d, it); renaming = null }, onDismiss = { renaming = null })
    }
    removing?.let { d ->
        RemoveDeviceDialog(d, onConfirm = { repo.unpair(d); removing = null }, onDismiss = { removing = null })
    }
}

@Composable
private fun ComputerRow(
    d: PairedDaemon,
    active: Boolean,
    menuOpen: Boolean,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val role = stringResource(
        if (d.role == BindingRole.GUEST) Res.string.computers_role_guest else Res.string.computers_role_owner,
    )
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .clickable(role = Role.Button, onClick = onTap)
                .padding(vertical = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    d.displayName(), color = Tok.tx, style = TypeRole.rowTitle,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.computers_account, d.accountId.take(10) + "…", role),
                    color = Tok.tx2, style = TypeRole.metaMono,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                // absence of this line is not a state — only the active binding claims anything
                if (active) Text(
                    stringResource(Res.string.computers_active_binding), color = Tok.ok,
                    style = TypeRole.caption, modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box(
                Modifier.size(Metric.touch).clip(RoundedCornerShape(Metric.radiusS))
                    .clickable(role = Role.Button, onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.MoreHoriz, stringResource(Res.string.computers_more),
                    tint = Tok.tx2, modifier = Modifier.size(20.dp),
                )
            }
        }
        if (menuOpen) Row(
            Modifier.fillMaxWidth().padding(bottom = Metric.gap),
            horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
        ) {
            EntrySecondaryButton(stringResource(Res.string.device_rename), Modifier.weight(1f), onClick = onRename)
            EntrySecondaryButton(
                stringResource(Res.string.device_remove), Modifier.weight(1f), tint = Tok.danger, onClick = onRemove,
            )
        }
    }
}
