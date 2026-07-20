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
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.BridgeCredential
import dev.ccpocket.protocol.BridgeRunnerSpec

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
                "Credential for \"$name\" — copy it NOW",
                color = Tok.accent, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text("Done", color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onDone))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Single-use, expires in ${ttlSec}s. Save it as bridge-credential.json next to the adapter and start it.",
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
 * Mint a bridge. The form's shape encodes the two decisions that actually matter and hides everything
 * else: WHICH projects it may touch, and WHETHER it may act without asking.
 *
 * The adapter fields are optional in one specific sense: leaving them empty means "I'll run the adapter
 * myself" (the daemon hands back a credential instead of managing a process). Filling them means the
 * daemon starts and supervises the adapter, and the ticket never leaves the machine.
 */
@Composable
internal fun NewBridgeForm(
    onCancel: () -> Unit,
    onCreate: (name: String, workdirs: List<String>, tier: AccessTier, runner: BridgeRunnerSpec?) -> Unit,
) {
    var name by remember { mutableStateOf("feishu-bot") }
    var tier by remember { mutableStateOf(AccessTier.REVIEW) }
    val picked = remember { mutableStateListOf<String>() }
    var manage by remember { mutableStateOf(true) }
    var scriptPath by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var adminId by remember { mutableStateOf("") }

    // scriptPath is NOT required: blank = the built-in Feishu adapter (the normal case)
    val canCreate = name.isNotBlank() && picked.isNotEmpty() &&
        (!manage || (appId.isNotBlank() && appSecret.isNotBlank()))

    // no inner verticalScroll: the Settings pane container already scrolls, and a nested unbounded
    // scrollable measures with infinite max height — an immediate crash, not a layout quirk
    Column(Modifier.fillMaxWidth()) {
        FieldLabel("NAME", "shown as \"via <name>\" on any session it starts")
        TextInput(name, { name = it }, "feishu-bot")

        Spacer(Modifier.height(14.dp))
        FieldLabel("PROJECTS", "the ONLY directories it may open sessions in — pick dedicated, low-sensitivity checkouts")
        PickedDirs(picked, onAdd = { pickProjectDir()?.let { if (it !in picked) picked.add(it) } }, onRemove = { picked.remove(it) })

        Spacer(Modifier.height(14.dp))
        FieldLabel("AUTONOMY", "anyone in the chat can send it prompts — this is what it may do without asking you")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TierChoice("Ask me first", "shell, writes and edits all prompt your phone", tier == AccessTier.REVIEW) { tier = AccessTier.REVIEW }
            TierChoice("Edit files silently", "edits apply unprompted; shell still asks", tier == AccessTier.COLLABORATE) { tier = AccessTier.COLLABORATE }
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { manage = !manage }) {
            Check(manage)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Let the daemon run the adapter", color = Tok.tx, fontFamily = Dk.ui, fontSize = 12.sp)
                Text(
                    if (manage) "It starts on boot, restarts on crash, and the credential never leaves this machine."
                    else "You'll get a credential to copy into an adapter you run yourself.",
                    color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
                )
            }
        }

        if (manage) {
            Spacer(Modifier.height(14.dp))
            FieldLabel("FEISHU APP", "from open.feishu.cn — the bot needs im:message + im:message:send_as_bot, and event subscription im.message.receive_v1 in long-connection mode")
            TextInput(appId, { appId = it }, "cli_xxx  (App ID)")
            Spacer(Modifier.height(6.dp))
            TextInput(appSecret, { appSecret = it }, "App Secret", secret = true)
            Spacer(Modifier.height(6.dp))
            TextInput(adminId, { adminId = it }, "your open_id — optional; leave empty and the bot tells you yours")
            Spacer(Modifier.height(12.dp))
            // custom adapter script = the advanced escape hatch. Blank (the default) runs the adapter the
            // daemon has BUILT IN — no python, no checkout, nothing else to install.
            FieldLabel("CUSTOM ADAPTER (optional)", "leave empty to use the built-in Feishu adapter — no python needed. Set a script path only to run your own.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TextInput(scriptPath, { scriptPath = it }, "built-in") }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Browse", color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp,
                    modifier = Modifier.clickable { pickAdapterScript()?.let { scriptPath = it } },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Create", color = if (canCreate) Tok.accent else Tok.muted.copy(alpha = 0.5f),
                fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background((if (canCreate) Tok.accent else Tok.muted).copy(alpha = 0.12f))
                    .clickable(enabled = canCreate) {
                        val runner = if (!manage) null else BridgeRunnerSpec(
                            scriptPath = scriptPath.trim(),
                            env = buildMap {
                                put("FEISHU_APP_ID", appId.trim())
                                put("FEISHU_APP_SECRET", appSecret.trim())
                                if (adminId.isNotBlank()) put("FEISHU_ADMIN_OPEN_ID", adminId.trim())
                            },
                        )
                        onCreate(name.trim(), picked.toList(), tier, runner)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
            Text(
                "Cancel", color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "After it connects, @mention the bot in a chat and send  /bind <project>  to point that chat at one of these projects.",
            color = Tok.muted, fontFamily = Dk.ui, fontSize = 10.sp,
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
    envKeys: List<String>,
    workdirs: List<String>,
    onCancel: () -> Unit,
    onSave: (appId: String, appSecret: String, adminId: String, workdirs: List<String>) -> Unit,
) {
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var adminId by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>().apply { addAll(workdirs) } }
    val projectsChanged = picked.toList() != workdirs
    // save is live once SOMETHING changed AND at least one project remains (a bridge with no allow-listed
    // directory can open nothing — the daemon rejects it too, this just greys the button first)
    val dirty = (appId.isNotBlank() || appSecret.isNotBlank() || adminId.isNotBlank() || projectsChanged) && picked.isNotEmpty()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        FieldLabel("PROJECTS", "the directories this bot may open sessions in — add or remove, then Save & restart")
        PickedDirs(picked, onAdd = { pickProjectDir()?.let { if (it !in picked) picked.add(it) } }, onRemove = { picked.remove(it) })
        Spacer(Modifier.height(14.dp))
        FieldLabel("ADAPTER CONFIG", "blank fields keep their current values — set: ${envKeys.joinToString(", ").ifEmpty { "(nothing yet)" }}")
        TextInput(adminId, { adminId = it }, "FEISHU_ADMIN_OPEN_ID — paste the open_id the bot echoed after /bind")
        Spacer(Modifier.height(6.dp))
        TextInput(appId, { appId = it }, "FEISHU_APP_ID (unchanged if blank)")
        Spacer(Modifier.height(6.dp))
        TextInput(appSecret, { appSecret = it }, "FEISHU_APP_SECRET (unchanged if blank)", secret = true)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Save & restart", color = if (dirty) Tok.accent else Tok.muted.copy(alpha = 0.5f),
                fontFamily = Dk.ui, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background((if (dirty) Tok.accent else Tok.muted).copy(alpha = 0.12f))
                    .clickable(enabled = dirty) { onSave(appId, appSecret, adminId, picked.toList()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                "Cancel", color = Tok.muted, fontFamily = Dk.ui, fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
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

@Composable
private fun PickedDirs(picked: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        picked.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    p.substringAfterLast('/'), color = Tok.tx, fontFamily = Dk.ui, fontSize = 11.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(p, color = Tok.muted.copy(alpha = 0.7f), fontFamily = Dk.mono, fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                Text("Remove", color = Tok.muted, fontFamily = Dk.ui, fontSize = 9.sp, modifier = Modifier.clickable { onRemove(p) })
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "+ Add project", color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp,
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
