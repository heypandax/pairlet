package dev.ccpocket.app.desktop

import java.awt.FileDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.action_browse
import dev.ccpocket.app.resources.action_create
import dev.ccpocket.app.resources.bridge_adapter_config
import dev.ccpocket.app.resources.bridge_add_project
import dev.ccpocket.app.resources.bridge_allow_cmds
import dev.ccpocket.app.resources.bridge_allow_cmds_hint
import dev.ccpocket.app.resources.bridge_allow_cmds_hint_create
import dev.ccpocket.app.resources.bridge_autonomy
import dev.ccpocket.app.resources.bridge_autonomy_hint
import dev.ccpocket.app.resources.bridge_bind_hint
import dev.ccpocket.app.resources.bridge_cred_note
import dev.ccpocket.app.resources.bridge_cred_title
import dev.ccpocket.app.resources.bridge_custom_adapter
import dev.ccpocket.app.resources.bridge_custom_hint
import dev.ccpocket.app.resources.bridge_edit_appid_ph
import dev.ccpocket.app.resources.bridge_edit_hint
import dev.ccpocket.app.resources.bridge_edit_projects_hint
import dev.ccpocket.app.resources.bridge_edit_save
import dev.ccpocket.app.resources.bridge_edit_secret_ph
import dev.ccpocket.app.resources.bridge_feishu_app
import dev.ccpocket.app.resources.bridge_feishu_hint
import dev.ccpocket.app.resources.bridge_manage_off
import dev.ccpocket.app.resources.bridge_manage_on
import dev.ccpocket.app.resources.bridge_manage_toggle
import dev.ccpocket.app.resources.bridge_name_hint
import dev.ccpocket.app.resources.bridge_none_yet
import dev.ccpocket.app.resources.bridge_no_approval
import dev.ccpocket.app.resources.bridge_no_approval_hint
import dev.ccpocket.app.resources.bridge_owner_bypass
import dev.ccpocket.app.resources.bridge_owner_bypass_hint_create
import dev.ccpocket.app.resources.bridge_owner_bypass_hint_edit
import dev.ccpocket.app.resources.bridge_ph_admin
import dev.ccpocket.app.resources.bridge_ph_admin_edit
import dev.ccpocket.app.resources.bridge_ph_appid
import dev.ccpocket.app.resources.bridge_projects
import dev.ccpocket.app.resources.bridge_projects_hint
import dev.ccpocket.app.resources.bridge_request_approval
import dev.ccpocket.app.resources.bridge_request_approval_hint
import dev.ccpocket.app.resources.bridge_tier_ask_sub
import dev.ccpocket.app.resources.bridge_tier_ask_title
import dev.ccpocket.app.resources.bridge_tier_edit_sub
import dev.ccpocket.app.resources.bridge_tier_edit_title
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.cmd_source_builtin
import dev.ccpocket.app.resources.device_remove
import dev.ccpocket.app.resources.dir_picker_choose_here
import dev.ccpocket.app.resources.dir_picker_remote_only
import dev.ccpocket.app.resources.dir_picker_remote_title
import dev.ccpocket.app.resources.done
import dev.ccpocket.app.resources.form_name
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.BridgeCredential
import dev.ccpocket.protocol.BridgeRunnerSpec
import org.jetbrains.compose.resources.stringResource

/**
 * Pick a project root. macOS's AWT dialog only offers DIRECTORIES under this property — without it the
 * owner gets a file picker and cannot select a folder at all. Restored right after, since the same
 * dialog class serves [pickAdapterScript]. BLOCKS the caller, like the other pickers here.
 */
private fun pickProjectDir(): String? {
    val key = "apple.awt.fileDialogForDirectories"
    System.setProperty(key, "true")
    return try {
        FileDialog(null as java.awt.Frame?, "Pick a project this bot may work in", FileDialog.LOAD)
            .apply { isVisible = true }.files?.firstOrNull()?.absolutePath
    } finally {
        System.setProperty(key, "false")
    }
}

private fun pickAdapterScript(): String? =
    FileDialog(null as java.awt.Frame?, "Pick the adapter script", FileDialog.LOAD)
        .apply { isVisible = true }.files?.firstOrNull()?.absolutePath

/** Encode a credential the way the adapter's own `Credential.load` reads it — the owner may need to paste
 *  it into a file by hand when they run the adapter themselves. */
internal object PrettyJson {
    fun of(c: BridgeCredential): String = buildString {
        appendLine("{")
        appendLine("""  "name": "${c.name}",""")
        appendLine("""  "accountId": "${c.accountId}",""")
        appendLine("""  "daemonPub": "${c.daemonPub}",""")
        appendLine("""  "ticket": "${c.ticket}",""")
        appendLine("""  "relay": "${c.relay}",""")
        appendLine("""  "workdirs": [${c.workdirs.joinToString(", ") { "\"$it\"" }}]""")
        append("}")
    }
}

/**
 * The one moment an UNMANAGED bridge's ticket is visible. It is single-use and expires in ~2 minutes, so
 * the card says so plainly rather than letting the owner discover it by the adapter failing later.
 */
@Composable
internal fun OneShotCredentialCard(name: String, ttlSec: Int, json: String, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Tok.accent.copy(alpha = 0.08f))
            .border(1.dp, Tok.accent.copy(alpha = 0.35f)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.bridge_cred_title, name),
                color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(12.sp),
            )
            Spacer(Modifier.weight(1f))
            Text(stringResource(Res.string.done), color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp, style = tightCenter(10.sp), modifier = Modifier.clickable(onClick = onDone))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.bridge_cred_note, ttlSec),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
        )
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Box(Modifier.fillMaxWidth().heightIn(max = 150.dp).clip(RoundedCornerShape(6.dp)).background(Tok.base).padding(8.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(json, color = Tok.tx.copy(alpha = 0.85f), fontFamily = Dk.mono, fontSize = 10.sp)
                }
            }
        }
    }
}

/**
 * Mint a bridge. Built-in Feishu approves an outsider's exact request, then executes that one request
 * without piecemeal tool prompts. The legacy autonomy/command controls remain only for external adapters.
 *
 * The adapter fields are optional in one specific sense: leaving them empty means "I'll run the adapter
 * myself" (the daemon hands back a credential instead of managing a process). Filling them means the
 * daemon starts and supervises the adapter, and the ticket never leaves the machine.
 */
@Composable
internal fun NewBridgeForm(
    model: DesktopModel,
    onCancel: () -> Unit,
    onCreate: (name: String, workdirs: List<String>, tier: AccessTier, allowedCommands: List<String>, runner: BridgeRunnerSpec?) -> Unit,
) {
    var name by remember { mutableStateOf("feishu-bot") }
    var tier by remember { mutableStateOf(AccessTier.REVIEW) }
    val picked = remember { mutableStateListOf<String>() }
    var allowCmds by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf(true) }
    var scriptPath by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var adminId by remember { mutableStateOf("") }
    var ownerBypass by remember { mutableStateOf(false) }
    var noApproval by remember { mutableStateOf(false) }
    // #218: on a remote daemon the local FileDialog browses the WRONG machine — swap in the daemon-side
    // folder browser (RemoteDirPickerPopup, rendered at the tail of this form).
    var remotePick by remember { mutableStateOf(false) }
    val requestScopedApproval = manage && scriptPath.isBlank()

    // scriptPath is NOT required: blank = the built-in Feishu adapter (the normal case)
    val canCreate = name.isNotBlank() && picked.isNotEmpty() &&
        (!manage || (appId.isNotBlank() && appSecret.isNotBlank()))

    // no inner verticalScroll: the Settings pane container already scrolls, and a nested unbounded
    // scrollable measures with infinite max height — an immediate crash, not a layout quirk
    Column(Modifier.fillMaxWidth()) {
        FieldLabel(stringResource(Res.string.form_name).uppercase(), stringResource(Res.string.bridge_name_hint))
        TextInput(name, { name = it }, "feishu-bot")

        Spacer(Modifier.height(14.dp))
        FieldLabel(stringResource(Res.string.bridge_projects).uppercase(), stringResource(Res.string.bridge_projects_hint))
        PickedDirs(
            picked,
            onAdd = {
                if (model.activeIsThisMachine) pickProjectDir()?.let { if (it !in picked) picked.add(it) }
                else remotePick = true
            },
            onRemove = { picked.remove(it) },
        )

        Spacer(Modifier.height(14.dp))
        if (requestScopedApproval) {
            FieldLabel(
                stringResource(Res.string.bridge_request_approval).uppercase(),
                stringResource(Res.string.bridge_request_approval_hint),
            )
        } else {
            FieldLabel(stringResource(Res.string.bridge_autonomy).uppercase(), stringResource(Res.string.bridge_autonomy_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TierChoice(stringResource(Res.string.bridge_tier_ask_title), stringResource(Res.string.bridge_tier_ask_sub), tier == AccessTier.REVIEW) { tier = AccessTier.REVIEW }
                TierChoice(stringResource(Res.string.bridge_tier_edit_title), stringResource(Res.string.bridge_tier_edit_sub), tier == AccessTier.COLLABORATE) { tier = AccessTier.COLLABORATE }
            }
            Spacer(Modifier.height(14.dp))
            FieldLabel(stringResource(Res.string.bridge_allow_cmds).uppercase(), stringResource(Res.string.bridge_allow_cmds_hint_create))
            MultilineInput(allowCmds, { allowCmds = it }, "npm test\n./gradlew build\npytest")
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { manage = !manage }) {
            Check(manage)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(Res.string.bridge_manage_toggle), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp)
                Text(
                    stringResource(if (manage) Res.string.bridge_manage_on else Res.string.bridge_manage_off),
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                )
            }
        }

        if (manage) {
            Spacer(Modifier.height(14.dp))
            FieldLabel(stringResource(Res.string.bridge_feishu_app).uppercase(), stringResource(Res.string.bridge_feishu_hint))
            TextInput(appId, { appId = it }, stringResource(Res.string.bridge_ph_appid))
            Spacer(Modifier.height(6.dp))
            TextInput(appSecret, { appSecret = it }, "App Secret", secret = true)
            Spacer(Modifier.height(6.dp))
            TextInput(adminId, { adminId = it }, stringResource(Res.string.bridge_ph_admin))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ownerBypass = !ownerBypass }) {
                Check(ownerBypass)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(Res.string.bridge_owner_bypass), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp)
                    Text(
                        stringResource(Res.string.bridge_owner_bypass_hint_create),
                        color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // issue #198: the MASTER enable only. Which groups actually go card-free is decided in the group
            // itself with /trust, so the hint has to say that — a checkbox that reads like "all my groups are
            // now free" would be the one misunderstanding that matters here.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { noApproval = !noApproval }) {
                Check(noApproval)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(Res.string.bridge_no_approval), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp)
                    Text(
                        stringResource(Res.string.bridge_no_approval_hint),
                        color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // custom adapter script = the advanced escape hatch. Blank (the default) runs the adapter the
            // daemon has BUILT IN — no python, no checkout, nothing else to install.
            FieldLabel(stringResource(Res.string.bridge_custom_adapter).uppercase(), stringResource(Res.string.bridge_custom_hint))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TextInput(scriptPath, { scriptPath = it }, stringResource(Res.string.cmd_source_builtin)) }
                Spacer(Modifier.width(8.dp))
                // #218: the adapter-script FILE chooser is local-only. On a remote daemon it would point at
                // the wrong filesystem, so drop the browse affordance and tell the owner to type the path.
                if (model.activeIsThisMachine) {
                    Text(
                        stringResource(Res.string.action_browse), color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp,
                        modifier = Modifier.clickable { pickAdapterScript()?.let { scriptPath = it } },
                    )
                } else {
                    Text(
                        stringResource(Res.string.dir_picker_remote_only), color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Res.string.action_create), color = if (canCreate) Tok.accent else Tok.muted.copy(alpha = 0.5f),
                fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(12.sp),
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background((if (canCreate) Tok.accent else Tok.muted).copy(alpha = 0.12f))
                    .clickable(enabled = canCreate) {
                        val runner = if (!manage) null else BridgeRunnerSpec(
                            scriptPath = scriptPath.trim(),
                            env = buildMap {
                                put("FEISHU_APP_ID", appId.trim())
                                put("FEISHU_APP_SECRET", appSecret.trim())
                                if (adminId.isNotBlank()) put("FEISHU_ADMIN_OPEN_ID", adminId.trim())
                                if (ownerBypass) put("FEISHU_OWNER_BYPASS", "1")
                                if (noApproval) put("FEISHU_NO_APPROVAL", "1")
                            },
                        )
                        onCreate(name.trim(), picked.toList(), tier, parseCommandLines(allowCmds), runner)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
            Text(
                stringResource(Res.string.cancel), color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                style = tightCenter(12.sp),
                modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.bridge_bind_hint),
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
        )
    }
    if (remotePick) {
        RemoteDirPickerPopup(
            model,
            title = stringResource(Res.string.dir_picker_remote_title),
            confirmLabel = stringResource(Res.string.dir_picker_choose_here),
            onDismiss = { remotePick = false },
            onPick = { p -> if (p !in picked) picked.add(p); remotePick = false },
        )
    }
}

/**
 * Edit a managed bridge in place: its PROJECT allow-list (add / remove projects) and its adapter config.
 * The project editor is pre-filled with the bridge's current [workdirs]. The adapter fields keep merge
 * semantics — blank keeps the stored value, since the app secret is never echoed back out ([envKeys] shows
 * what's set) — and double as the /bind bootstrap slot for pasting the owner's open_id. The daemon
 * re-validates the projects and restarts the adapter with the new config.
 */
@Composable
internal fun EditRunnerForm(
    model: DesktopModel,
    envKeys: List<String>,
    workdirs: List<String>,
    allowedCommands: List<String>,
    ownerBypass: Boolean,
    noApproval: Boolean,
    requestScopedApproval: Boolean,
    onCancel: () -> Unit,
    onSave: (appId: String, appSecret: String, adminId: String, workdirs: List<String>, allowedCommands: List<String>, ownerBypass: Boolean, noApproval: Boolean) -> Unit,
) {
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var adminId by remember { mutableStateOf("") }
    var ownerBypassOn by remember { mutableStateOf(ownerBypass) }
    var noApprovalOn by remember { mutableStateOf(noApproval) }
    var remotePick by remember { mutableStateOf(false) } // #218: remote-daemon folder browser (see NewBridgeForm)
    val picked = remember { mutableStateListOf<String>().apply { addAll(workdirs) } }
    val projectsChanged = picked.toList() != workdirs
    var allowCmds by remember { mutableStateOf(allowedCommands.joinToString("\n")) }
    val commandsChanged = !requestScopedApproval && parseCommandLines(allowCmds) != allowedCommands
    // save is live once SOMETHING changed AND at least one project remains (a bridge with no allow-listed
    // directory can open nothing — the daemon rejects it too, this just greys the button first)
    val dirty = (
        appId.isNotBlank() || appSecret.isNotBlank() || adminId.isNotBlank() || projectsChanged ||
            commandsChanged || ownerBypassOn != ownerBypass || noApprovalOn != noApproval
        ) && picked.isNotEmpty()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        FieldLabel(stringResource(Res.string.bridge_projects).uppercase(), stringResource(Res.string.bridge_edit_projects_hint))
        PickedDirs(
            picked,
            onAdd = {
                if (model.activeIsThisMachine) pickProjectDir()?.let { if (it !in picked) picked.add(it) }
                else remotePick = true
            },
            onRemove = { picked.remove(it) },
        )
        Spacer(Modifier.height(14.dp))
        if (requestScopedApproval) {
            FieldLabel(
                stringResource(Res.string.bridge_request_approval).uppercase(),
                stringResource(Res.string.bridge_request_approval_hint),
            )
        } else {
            FieldLabel(stringResource(Res.string.bridge_allow_cmds).uppercase(), stringResource(Res.string.bridge_allow_cmds_hint))
            MultilineInput(allowCmds, { allowCmds = it }, "npm test\n./gradlew build\npytest")
        }
        Spacer(Modifier.height(14.dp))
        FieldLabel(
            stringResource(Res.string.bridge_adapter_config).uppercase(),
            stringResource(Res.string.bridge_edit_hint, envKeys.joinToString(", ").ifEmpty { stringResource(Res.string.bridge_none_yet) }),
        )
        TextInput(adminId, { adminId = it }, stringResource(Res.string.bridge_ph_admin_edit))
        Spacer(Modifier.height(6.dp))
        TextInput(appId, { appId = it }, stringResource(Res.string.bridge_edit_appid_ph))
        Spacer(Modifier.height(6.dp))
        TextInput(appSecret, { appSecret = it }, stringResource(Res.string.bridge_edit_secret_ph), secret = true)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { ownerBypassOn = !ownerBypassOn }) {
            Check(ownerBypassOn)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(Res.string.bridge_owner_bypass), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp)
                Text(
                    stringResource(Res.string.bridge_owner_bypass_hint_edit),
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { noApprovalOn = !noApprovalOn }) {
            Check(noApprovalOn)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(Res.string.bridge_no_approval), color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp)
                Text(
                    stringResource(Res.string.bridge_no_approval_hint),
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Res.string.bridge_edit_save), color = if (dirty) Tok.accent else Tok.muted.copy(alpha = 0.5f),
                fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(11.sp),
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background((if (dirty) Tok.accent else Tok.muted).copy(alpha = 0.12f))
                    .clickable(enabled = dirty) {
                        onSave(appId, appSecret, adminId, picked.toList(), parseCommandLines(allowCmds), ownerBypassOn, noApprovalOn)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                stringResource(Res.string.cancel), color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
                style = tightCenter(11.sp),
                modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
    if (remotePick) {
        RemoteDirPickerPopup(
            model,
            title = stringResource(Res.string.dir_picker_remote_title),
            confirmLabel = stringResource(Res.string.dir_picker_choose_here),
            onDismiss = { remotePick = false },
            onPick = { p -> if (p !in picked) picked.add(p); remotePick = false },
        )
    }
}

@Composable
private fun FieldLabel(label: String, hint: String) {
    Text(label, color = Tok.muted, fontFamily = Dk.ui, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
    Text(hint, color = Tok.muted.copy(alpha = 0.75f), fontFamily = Dk.ui, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Tok.muted.copy(alpha = 0.6f), fontFamily = Dk.ui, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Tok.tx, fontFamily = if (secret) Dk.mono else Dk.ui, fontSize = 11.sp),
            cursorBrush = SolidColor(Tok.accent),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Split a textarea into command-allow-list entries: one per line, trimmed, blanks dropped. The daemon
 *  normalizes again (dedupe + cap) and BridgeCommandPolicy still gates each against the danger/metachar
 *  walls, so this is only display-side hygiene. */
private fun parseCommandLines(text: String): List<String> =
    text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun MultilineInput(value: String, onChange: (String) -> Unit, placeholder: String) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(6.dp)).background(Tok.surface)
            .border(1.dp, Tok.hair, RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Tok.muted.copy(alpha = 0.6f), fontFamily = Dk.mono, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(color = Tok.tx, fontFamily = Dk.mono, fontSize = 11.sp),
            cursorBrush = SolidColor(Tok.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PickedDirs(picked: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        picked.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    p.substringAfterLast('/'), color = Tok.tx, fontFamily = Dk.ui, fontSize = 11.sp, style = tightCenter(11.sp),
                )
                Spacer(Modifier.width(8.dp))
                Text(p, color = Tok.muted.copy(alpha = 0.7f), fontFamily = Dk.mono, fontSize = 9.sp, style = tightCenter(9.sp))
                Spacer(Modifier.weight(1f))
                Text(stringResource(Res.string.device_remove), color = Tok.muted, fontFamily = Dk.ui, fontSize = 9.sp, style = tightCenter(9.sp), modifier = Modifier.clickable { onRemove(p) })
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(Res.string.bridge_add_project), color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp,
            style = tightCenter(10.sp),
            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Tok.accent.copy(alpha = 0.12f))
                .clickable(onClick = onAdd).padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun TierChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(210.dp).clip(RoundedCornerShape(7.dp))
            .background(if (selected) Tok.accent.copy(alpha = 0.12f) else Tok.surface)
            .border(1.dp, if (selected) Tok.accent.copy(alpha = 0.5f) else Tok.hair, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick).padding(10.dp),
    ) {
        Text(title, color = if (selected) Tok.accent else Tok.tx, fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = Tok.muted, fontFamily = Dk.ui, fontSize = 9.sp)
    }
}

@Composable
private fun Check(on: Boolean) {
    Box(
        Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
            .background(if (on) Tok.accent else Color_Transparent)
            .border(1.dp, if (on) Tok.accent else Tok.muted, RoundedCornerShape(3.dp)),
    )
}

private val Color_Transparent = androidx.compose.ui.graphics.Color.Transparent
