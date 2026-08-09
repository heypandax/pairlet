package dev.ccpocket.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.defaultDaemonUrl
import dev.ccpocket.app.pairing.displayName
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.entry.CopyableCommand
import dev.ccpocket.app.ui.entry.EntryLabel
import dev.ccpocket.app.ui.entry.EntryNote
import dev.ccpocket.app.ui.entry.EntryPrimaryButton
import dev.ccpocket.app.ui.entry.EntryQuietAction
import dev.ccpocket.app.ui.entry.EntryRouteRow
import dev.ccpocket.app.ui.entry.EntrySecondaryButton
import dev.ccpocket.app.ui.entry.EntryTitle
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.voice.openAppSettings
import org.jetbrains.compose.resources.stringResource
import qrscanner.CameraLens
import qrscanner.QrScanner

/** The desktop command that mints a pairing code — quoted verbatim, never paraphrased. */
private const val PAIR_COMMAND = "cc-pocket-daemon pair"

/**
 * "Pair a computer" — the first surface of the entry flow (Entry Flow UI 2.0 · Master frame 01).
 *
 * CODE-FIRST AND CAMERA-FREE BY DEFAULT. A camera is a permission request and a live picture of the user's
 * room; neither belongs in the hierarchy of a screen whose job is "type six digits". Scanning is an explicit
 * route ([PairScanRoute]) and is the only place [QrScanner] mounts, so nothing on launch asks for a
 * permission the flow does not need — and pairing stays completable if that permission is never granted.
 *
 * Every previously reachable route survives at a lower weight, below one hairline, in expected order: scan,
 * paste-link, direct LAN — then the desktop command, the install guide and Demo. Pairing validation, URI
 * parsing and every repository effect are untouched.
 */
@Composable
fun PairingScreen(repo: PocketRepository) {
    var showOnboarding by remember { mutableStateOf(false) }
    // The six digits and the paste disclosure are hoisted ABOVE the scan route on purpose: walking into the
    // scanner and back must not silently erase what was already typed (acceptance path 02).
    var code by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }

    if (showOnboarding) { OnboardingScreen(onBack = { showOnboarding = false }, onPairNow = { showOnboarding = false }); return }
    if (scanning) {
        PairScanRoute(
            digitsEntered = code.length,
            onBack = { scanning = false },
            onScanned = { scanning = false; repo.handlePairUrl(it) },
            onUseCode = { scanning = false },
            onPasteLink = { scanning = false; showPaste = true },
        )
        return
    }

    var link by remember { mutableStateOf("") }
    var showLan by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(defaultDaemonUrl()) }
    val complete = code.length == 6
    // "Add a computer" entered from an existing binding — let the user back out to the device picker.
    val adding = repo.addingDevice.value
    if (adding) dev.ccpocket.app.SystemBackHandler(enabled = true) { repo.cancelAddDevice() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metric.gutter),
    ) {
        // added from an existing binding: a real way back, and the current computer stays connected
        if (adding) Row(
            Modifier.fillMaxWidth().padding(top = Metric.gapXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.pair_add_title), color = Tok.tx2, style = TypeRole.action,
                modifier = Modifier.weight(1f),
            )
            EntryQuietAction(stringResource(Res.string.cancel), color = Tok.accent) { repo.cancelAddDevice() }
        }
        Spacer(Modifier.height(if (adding) Metric.gapS else 40.dp))
        EntryTitle(stringResource(Res.string.pair_title), stringResource(Res.string.pair_sub))

        // A pure RECIPIENT (SESSION-HANDOFF.md §10: "接收方要求 cc-pocket App；不要求 daemon") has
        // collaborator links but no owner binding, so this pairing screen is their whole app — it would
        // otherwise read as "you haven't started yet" while their contact link is live and offers are
        // already routed here. One line, stated once; the offer overlay above still does the real work.
        val collabLinks = remember { dev.ccpocket.app.pairing.Pairing.collaboratorLinks() }
        if (!adding && collabLinks.isNotEmpty()) {
            val shape = RoundedCornerShape(Metric.radius)
            Column(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(shape)
                    .background(Tok.warn.copy(alpha = 0.08f)).border(Metric.hairline, Tok.warn.copy(alpha = 0.38f), shape)
                    .padding(Metric.gapL),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
                    Box(Modifier.size(8.dp).rotate(45f).background(Tok.warn)) // attention, not danger
                    Text(stringResource(Res.string.co_pairing_linked), color = Tok.tx, style = TypeRole.action)
                }
                Text(
                    stringResource(Res.string.co_pairing_linked_sub, collabLinks.joinToString("、") { it.displayName() }),
                    color = Tok.tx2, style = TypeRole.caption, modifier = Modifier.padding(top = Metric.gapXs),
                )
            }
        }

        // ── the hierarchy: six digits, then the one canonical action ──
        EntryLabel(stringResource(Res.string.pair_code_label), Modifier.padding(top = 26.dp, bottom = Metric.gap))
        CodeInput(code) { v -> code = v }
        EntryPrimaryButton(
            stringResource(Res.string.pair_cta),
            Modifier.padding(top = Metric.gapL),
            enabled = complete,
        ) { repo.pairWithCode(code) }
        EntryNote(
            stringResource(if (complete) Res.string.pair_helper_complete else Res.string.pair_helper_incomplete),
            Modifier.padding(top = Metric.gapS),
        )
        Text(
            repo.status.value.resolve(), color = Tok.muted, style = TypeRole.captionMono,
            modifier = Modifier.padding(top = Metric.gapS),
        )

        // ── alternatives: peers below one hairline, in the order they are actually reached for ──
        Spacer(Modifier.height(22.dp))
        EntryRouteRow(stringResource(Res.string.pair_route_scan)) { scanning = true }
        EntryRouteRow(stringResource(Res.string.pair_route_paste), expanded = showPaste) { showPaste = !showPaste }
        if (showPaste) {
            OutlinedTextField(
                link, { link = it }, placeholder = { Text(stringResource(Res.string.paste_pair_link)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = Metric.gapS),
            )
            EntrySecondaryButton(
                stringResource(Res.string.pair_from_link), Modifier.padding(bottom = Metric.gap),
                enabled = link.isNotBlank(),
            ) { repo.pair(link) }
        }
        EntryRouteRow(stringResource(Res.string.pair_route_lan), expanded = showLan) { showLan = !showLan }
        if (showLan) {
            OutlinedTextField(
                url, { url = it }, placeholder = { Text(stringResource(Res.string.daemon_ws_url)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = Metric.gapS),
            )
            EntrySecondaryButton(stringResource(Res.string.connect_direct)) { repo.startDirect(url) }
            EntryNote(stringResource(Res.string.pair_lan_note), Modifier.padding(top = Metric.gapS, bottom = Metric.gap))
        }
        Hairline()

        // ── technical help: the exact command, copyable ──
        EntryLabel(stringResource(Res.string.pair_on_computer), Modifier.padding(top = 22.dp, bottom = Metric.gapS))
        CopyableCommand(PAIR_COMMAND)
        EntryQuietAction(stringResource(Res.string.ob_open), Modifier.padding(top = Metric.gapXs)) { showOnboarding = true }

        // No computer? Explore the whole app with sample data — no pairing or account needed.
        Hairline(Modifier.padding(top = Metric.gap))
        EntryQuietAction(stringResource(Res.string.pair_demo), Modifier.padding(top = Metric.gapXs)) { repo.enterDemo() }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The explicit scan route (Master frame 07) — the ONLY place the camera is requested.
 *
 * It has a real back path, and an unusable camera is not a dead end: the failure is explained in words and
 * both the code and paste routes are repeated HERE rather than linked away, so the user never has to
 * reconstruct where they came from. [digitsEntered] lets the screen state, in words, that what was already
 * typed survived the detour.
 */
@Composable
internal fun PairScanRoute(
    digitsEntered: Int,
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
    onUseCode: () -> Unit,
    onPasteLink: () -> Unit,
) {
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    // qr-kit reports "no camera on this device" and "permission refused" through the same channel, and the
    // platforms word them differently — so this is ONE honest state ("we can't use the camera") rather than
    // a guess between two, with the platform's own message shown underneath when it gave one.
    var failure by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Metric.gutter),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = Metric.gapXs), verticalAlignment = Alignment.CenterVertically) {
            EntryQuietAction("‹ " + stringResource(Res.string.close), color = Tok.accent) { onBack() }
        }
        EntryTitle(stringResource(Res.string.scan_title), null, Modifier.padding(top = Metric.gapS))

        Box(Modifier.padding(top = 20.dp).align(Alignment.CenterHorizontally)) {
            if (failure == null) {
                Viewfinder(
                    onScanned = { v -> if (!handled) { handled = true; onScanned(v) } },
                    onFailure = { failure = it.ifBlank { " " } },
                )
            } else {
                CameraUnavailable(failure)
            }
        }

        // The camera-free routes are always present — before the camera fails, not only after.
        EntryLabel(stringResource(Res.string.scan_without_camera), Modifier.padding(top = 24.dp, bottom = Metric.gapS))
        EntryNote(stringResource(Res.string.scan_without_camera_body), Modifier.padding(bottom = Metric.gap))
        EntryPrimaryButton(stringResource(Res.string.scan_use_code)) { onUseCode() }
        EntryNote(
            when (digitsEntered) {
                0 -> stringResource(Res.string.scan_kept_none)
                1 -> stringResource(Res.string.scan_kept_one)
                else -> stringResource(Res.string.scan_kept_many, digitsEntered)
            },
            Modifier.padding(top = Metric.gapS),
        )
        EntryRouteRow(stringResource(Res.string.pair_route_paste), Modifier.padding(top = Metric.gap)) { onPasteLink() }

        EntryLabel(stringResource(Res.string.pair_on_computer), Modifier.padding(top = 22.dp, bottom = Metric.gapS))
        CopyableCommand(PAIR_COMMAND)
        Spacer(Modifier.height(28.dp))
    }
}

/** The empty viewfinder that says WHY it is empty — same geometry as the live one, so nothing jumps. */
@Composable
private fun CameraUnavailable(detail: String?) {
    Column(
        Modifier.size(width = 260.dp, height = 240.dp).clip(RoundedCornerShape(16.dp))
            .background(Tok.warn.copy(alpha = 0.07f))
            .border(Metric.hairline, Tok.warn.copy(alpha = 0.38f), RoundedCornerShape(16.dp))
            .padding(Metric.gapL),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gapS)) {
            Box(Modifier.size(8.dp).rotate(45f).background(Tok.warn)) // attention, not danger
            Text(stringResource(Res.string.scan_unavailable_title), color = Tok.tx, style = TypeRole.rowTitle)
        }
        Text(
            stringResource(Res.string.scan_unavailable_body), color = Tok.tx2, style = TypeRole.preview,
            modifier = Modifier.padding(top = Metric.gapS),
        )
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Tok.muted, style = TypeRole.captionMono, modifier = Modifier.padding(top = Metric.gapS))
        }
        EntrySecondaryButton(
            stringResource(Res.string.scan_open_settings), Modifier.padding(top = Metric.gap),
        ) { openAppSettings() }
    }
}

/** The camera frame. Mounted ONLY inside [PairScanRoute] — composing it is what starts the camera. */
@Composable
private fun Viewfinder(onScanned: (String) -> Unit, onFailure: (String) -> Unit) {
    val anim = rememberInfiniteTransition()
    val scanY by anim.animateFloat(6f, 210f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse))
    Box(
        Modifier.size(width = 260.dp, height = 240.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF15171A), Color(0xFF0B0C0D))))
            .border(Metric.hairline, Tok.hair, RoundedCornerShape(16.dp)),
    ) {
        QrScanner(
            modifier = Modifier.fillMaxSize(),
            flashlightOn = false,
            cameraLens = CameraLens.Back,
            openImagePicker = false,
            onCompletion = onScanned,
            imagePickerHandler = {},
            onFailure = onFailure,
            overlayColor = Color.Transparent,      // suppress qr-kit's own dimming; we draw the frame
            overlayBorderColor = Color.Transparent,
        )
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            val len = 30.dp.toPx(); val th = 3.dp.toPx(); val w = size.width; val h = size.height
            fun l(a: Offset, b: Offset) = drawLine(Tok.accent, a, b, th, StrokeCap.Round)
            l(Offset(0f, 0f), Offset(len, 0f)); l(Offset(0f, 0f), Offset(0f, len))             // TL
            l(Offset(w, 0f), Offset(w - len, 0f)); l(Offset(w, 0f), Offset(w, len))             // TR
            l(Offset(0f, h), Offset(len, h)); l(Offset(0f, h), Offset(0f, h - len))             // BL
            l(Offset(w, h), Offset(w - len, h)); l(Offset(w, h), Offset(w, h - len))            // BR
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = scanY.dp).height(2.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Tok.accent, Color.Transparent))),
        )
        Text(
            stringResource(Res.string.scanning),
            color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

/**
 * The six-digit field.
 *
 * Deliberately does NOT auto-submit on the sixth digit: `Pair computer` is the canonical action, and a field
 * that fires by itself leaves the primary button reading as decoration (and re-fires on every correction).
 */
@Composable
private fun CodeInput(code: String, onCode: (String) -> Unit) {
    val label = stringResource(Res.string.pair_code_label)
    Box(Modifier.fillMaxWidth()) {
        // visible boxes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            for (i in 0 until 6) {
                val ch = code.getOrNull(i)
                val active = i == code.length.coerceAtMost(5)
                Box(
                    Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(Metric.gap)).background(Tok.surface)
                        .border(
                            if (active) 1.5.dp else Metric.hairline,
                            if (active) Tok.accent else Tok.hair,
                            RoundedCornerShape(Metric.gap),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        ch != null -> Text(ch.toString(), color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        active -> Box(Modifier.width(2.dp).height(26.dp).background(Tok.accent))
                        else -> Box(Modifier.width(8.dp).height(2.dp).background(Tok.hair))
                    }
                }
            }
        }
        // transparent input on top, capturing taps + the numeric keyboard. It carries the field's name, so
        // the invisible target is announced by a screen reader instead of being a silent rectangle.
        BasicTextField(
            value = code,
            onValueChange = { onCode(it.filter(Char::isDigit).take(6)) },
            modifier = Modifier.fillMaxWidth().height(58.dp).alpha(0f).semantics { contentDescription = label },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}
