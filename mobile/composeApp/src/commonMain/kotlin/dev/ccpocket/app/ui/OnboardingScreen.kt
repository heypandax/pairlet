package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.CLAUDE_CLI_SETUP_URL
import dev.ccpocket.app.GITHUB_REPO_URL
import dev.ccpocket.app.PRIVACY_POLICY_URL
import dev.ccpocket.app.SETUP_GUIDE_URL
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.supportChatUrl
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.Telemetry
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.entry.CopyableCommand
import dev.ccpocket.app.ui.entry.EntryPrimaryButton
import dev.ccpocket.app.ui.entry.EntryQuietAction
import dev.ccpocket.app.ui.entry.EntryRouteRow
import dev.ccpocket.app.ui.entry.EntryStateBlock
import dev.ccpocket.app.ui.session.Hairline
import dev.ccpocket.app.ui.session.StateMark
import dev.ccpocket.app.ui.session.StateTone
import org.jetbrains.compose.resources.stringResource

/** The desktop platforms the install command differs across. Ordered by share, not alphabetically. */
private val PLATFORMS = listOf("macOS", "Windows", "Linux")

/**
 * The REAL install command per platform, from the repository's own distribution scripts.
 *
 * The design mock carries package-manager one-liners (`brew install …`, `winget install …`) as PLACEHOLDERS —
 * its own notes say so. Substituting them would print a command that does not exist, on the one screen whose
 * entire job is a command the user must type on another machine. These are the shipped ones; the shipped
 * package-manager alternatives are [PACKAGE_COMMANDS], and they are verbatim for the same reason.
 */
private val INSTALL_COMMANDS = mapOf(
    "macOS" to "curl -fsSL https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.sh | bash",
    "Windows" to "irm https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.ps1 | iex",
    "Linux" to "curl -fsSL https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.sh | bash",
)

/**
 * The SHIPPED package-manager route per platform, from the same distribution the README documents.
 *
 * A `curl … | bash` line is the fastest install and the one a security-minded reader refuses on sight — and
 * that reader is exactly who the trust block below is written for. So the alternative sits beside it instead
 * of being buried in the web guide (issue #342). Two details here are load-bearing and must not be "tidied":
 * the Homebrew cask needs its FULL name (an unrelated cask is also called `cc-pocket`), and Scoop needs the
 * bucket added before the install resolves — hence two lines, copied as one block. Linux is absent because
 * there is no official package for it; the switch hides itself there rather than inventing one.
 */
private val PACKAGE_COMMANDS = mapOf(
    "macOS" to "brew install --cask heypandax/tap/cc-pocket",
    "Windows" to "scoop bucket add heypandax https://github.com/heypandax/scoop-bucket\nscoop install cc-pocket-daemon",
)

/** Stable `onboarding_cta` target names. Not derived from labels — a label is localised and would drift. */
private const val CTA_PAIR_NOW = "pair_now"
private const val CTA_PAIR_NOW_DOCKED = "pair_now_docked"
private const val CTA_ENTER_CODE = "enter_code"
private const val CTA_DEMO = "demo"
private const val CTA_GUIDE = "guide"
private const val CTA_SUPPORT = "support"
private const val CTA_CLOSE = "close"
private const val CTA_WHAT_IS_CLI = "what_is_cli"
private const val CTA_WIN_CLI = "win_cli_cta"
private const val CTA_COPY_INSTALL = "copy_install"
private const val CTA_OS_SEGMENT = "os_segment"
private const val CTA_INSTALL_METHOD = "install_method"
private const val CTA_GITHUB = "github"
private const val CTA_PRIVACY = "privacy"

/** One event for the whole screen, named by [target] — see [TelEvent.OnboardingCta]. [value] is the single
 *  categorical qualifier a few targets carry (the OS picked, the install method copied), never a label. */
private fun cta(target: String, value: String? = null) = Telemetry.track(
    TelEvent.OnboardingCta,
    if (value == null) mapOf(TelKey.Target to target) else mapOf(TelKey.Target to target, TelKey.Value to value),
)

/** The install method's wire value: which of the two shipped routes a command came from. */
private fun installMethodValue(os: String, viaPackage: Boolean): String =
    if (!viaPackage) "script" else if (os == "Windows") "scoop" else "brew"

/** [PLATFORMS] entries are what the SEGMENT SHOWS. This is what the wire carries — same rule as the target
 *  names above, so a change to how a platform is spelled on screen can never silently rename a category. */
private fun osValue(os: String): String = when (os) {
    "macOS" -> "macos"
    "Windows" -> "windows"
    else -> "linux"
}

/**
 * Scroll offset (px) at which the display title collapses. It comes back only at the very top.
 *
 * The asymmetry is load-bearing, not a preference. Collapsing REMOVES the display title from the scrollable
 * content, so the scrollable range shrinks and the offset is clamped down with it. Against any non-zero
 * expand threshold, a viewport whose total overflow sits just above [COLLAPSE_AT] would collapse, clamp
 * below the threshold, expand, and snap the user back to the top on every flick. Expanding only at a true
 * zero offset makes the collapsed state stable at every viewport size.
 */
private const val COLLAPSE_AT = 96f

/**
 * "Connect your computer" — the FIRST screen of a first run (issue #278 batch 2).
 *
 * The activation funnel's largest single drop-off was the pairing wall: a new install opened on a field
 * asking for six digits that nothing was printing yet, because the daemon was not installed on any computer.
 * Nothing on that screen said so above the fold. This screen states the precondition first and hands over
 * the command that satisfies it — the pairing code is step 2, not the front door.
 *
 * Two steps, one filled action, then quiet routes in falling weight: enter a code directly (for the user who
 * already installed it), the demo (which needs no computer at all), then the caption pair. Nothing is
 * illustrated; the copy carries it.
 *
 * [onBack] is null when this screen IS the root (a genuine first run) — a back arrow to nothing is worse
 * than no arrow. [onEnterDemo] is null wherever the demo is not offered from here.
 */
@Composable
fun OnboardingScreen(
    onPairNow: () -> Unit,
    onBack: (() -> Unit)? = null,
    onEnterDemo: (() -> Unit)? = null,
) {
    if (onBack != null) dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    // install-guide exposure (issue #278): how many of the users who never pair even open these steps
    LaunchedEffect(Unit) {
        dev.ccpocket.app.telemetry.Telemetry.track(dev.ccpocket.app.telemetry.TelEvent.OnboardingShown)
    }
    var os by remember { mutableStateOf("macOS") }
    // Which install route is on screen. Deliberately kept ACROSS an OS switch: a reader who rejected the
    // piped script does not want it back the moment they check what the other platform says.
    var viaPackage by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // The collapsed head is a STATE, not a scroll animation (design note 3). Once collapsed it LATCHES until
    // the user is back at the very top — see [COLLAPSE_AT] for why the expand threshold cannot be a positive
    // offset.
    var compact by remember { mutableStateOf(false) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.value.toFloat() }.collect { y ->
            compact = if (compact) y > 0f else y > COLLAPSE_AT
        }
    }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        if (compact) CollapsedHead(onBack)
        else if (onBack != null) Row(
            Modifier.fillMaxWidth().padding(horizontal = Metric.gapS).padding(top = Metric.gapXs),
            verticalAlignment = Alignment.CenterVertically,
        ) { EntryQuietAction("‹ " + stringResource(Res.string.close), color = Tok.accent) { cta(CTA_CLOSE); onBack() } }

        Column(
            Modifier.weight(1f).verticalScroll(scroll)
                .padding(horizontal = Metric.gutter).padding(top = Metric.gapS),
        ) {
            if (!compact) {
                Text(
                    WORDMARK, color = Tok.muted,
                    style = TypeRole.label.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.9.sp),
                )
                Text(
                    stringResource(Res.string.ob_title), color = Tok.tx,
                    style = TypeRole.screenTitle.copy(fontSize = 33.sp, lineHeight = 35.sp),
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    stringResource(Res.string.ob_sub), color = Tok.tx2,
                    style = TypeRole.preview.copy(fontSize = 15.5.sp, lineHeight = 23.sp),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            // ── STEP 1 · install on the computer ──
            Spacer(Modifier.height(if (compact) 18.dp else 30.dp))
            StepHead(1, stringResource(Res.string.fr_step_install))
            PlatformSegment(os) { cta(CTA_OS_SEGMENT, osValue(it)); os = it }
            // Linux ships no package, so it gets no switch — and the command shown falls back to the script
            // whatever the retained preference says.
            val pkg = PACKAGE_COMMANDS[os]
            if (pkg != null) MethodSwitch(viaPackage) {
                cta(CTA_INSTALL_METHOD, installMethodValue(os, it)); viaPackage = it
            }
            // null = the script line. On Linux that is the only route, whatever `viaPackage` still holds
            // from the platform the reader was looking at a moment ago.
            val shownPkg = pkg?.takeIf { viaPackage }
            CopyableCommand(
                shownPkg ?: INSTALL_COMMANDS.getValue(os),
                Modifier.padding(top = Metric.gap),
            ) { cta(CTA_COPY_INSTALL, installMethodValue(os, shownPkg != null)) }
            // On Windows the CLI is the step people actually miss, so the prerequisite stops being a quiet
            // line and takes a marked note. ATTENTION, not danger — nothing has failed yet.
            if (os == "Windows") EntryStateBlock(
                StateMark.DIAMOND, StateTone.ATTENTION,
                stringResource(Res.string.fr_prereq),
                stringResource(Res.string.fr_prereq_win_body),
                Modifier.padding(top = Metric.gap),
            ) {
                EntryQuietAction(
                    stringResource(Res.string.fr_prereq_win_cta), color = Tok.accent,
                ) { cta(CTA_WIN_CLI); openWebUrl(CLAUDE_CLI_SETUP_URL) }
            } else Row(
                Modifier.fillMaxWidth().padding(top = Metric.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.fr_prereq), color = Tok.muted,
                    style = TypeRole.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                    modifier = Modifier.weight(1f, fill = false),
                )
                EntryQuietAction(
                    stringResource(Res.string.fr_prereq_what), color = Tok.accent,
                ) { cta(CTA_WHAT_IS_CLI); openWebUrl(CLAUDE_CLI_SETUP_URL) }
            }

            // ── STEP 2 · pair ──
            Spacer(Modifier.height(26.dp))
            StepHead(2, stringResource(Res.string.ob_step_pair))
            Text(
                monoInline(stringResource(Res.string.fr_step_pair_body), PAIR_COMMAND),
                color = Tok.tx2, style = TypeRole.preview.copy(lineHeight = 23.sp),
                modifier = Modifier.padding(top = Metric.gap),
            )

            // the one filled action. In the collapsed state it moves to a docked footer instead (below), so
            // the primary action is never the thing that scrolled out of reach.
            if (!compact) EntryPrimaryButton(
                stringResource(Res.string.fr_cta), Modifier.padding(top = 26.dp),
            ) { cta(CTA_PAIR_NOW); onPairNow() }

            Spacer(Modifier.height(20.dp))
            EntryRouteRow(stringResource(Res.string.fr_enter_code)) { cta(CTA_ENTER_CODE); onPairNow() }
            if (onEnterDemo != null) EntryRouteRow(
                stringResource(Res.string.pair_demo),
                sub = stringResource(Res.string.fr_demo_sub), quiet = true,
            ) { cta(CTA_DEMO); onEnterDemo() }
            Hairline()

            TrustBlock()

            Row(
                Modifier.fillMaxWidth().padding(top = Metric.gapXs),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EntryQuietAction(stringResource(Res.string.ob_guide), color = Tok.muted) {
                    cta(CTA_GUIDE); openWebUrl(SETUP_GUIDE_URL)
                }
                Text("·", color = Tok.hair, style = TypeRole.caption)
                // Reachable BEFORE anything has ever paired — the state in which a user most needs it and
                // previously could not get to it at all.
                EntryQuietAction(stringResource(Res.string.support_title), color = Tok.muted) {
                    cta(CTA_SUPPORT); openWebUrl(supportChatUrl())
                }
            }
            Spacer(Modifier.height(Metric.gap))
        }

        if (compact) Column(Modifier.fillMaxWidth().background(Tok.base)) {
            Hairline()
            EntryPrimaryButton(
                stringResource(Res.string.fr_cta),
                Modifier.padding(horizontal = Metric.gutter).padding(top = Metric.gapS, bottom = Metric.gap),
            ) { cta(CTA_PAIR_NOW_DOCKED); onPairNow() }
        }
    }
}

private const val WORDMARK = "cc-pocket"

/** The collapsed head: wordmark plus the screen's name on one hairline-terminated line. */
@Composable
private fun CollapsedHead(onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Metric.gutter).heightIn(min = 40.dp)
                .padding(bottom = Metric.gap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metric.gap),
        ) {
            if (onBack != null) Text(
                "‹", color = Tok.accent, style = TypeRole.title,
                modifier = Modifier.clickable(role = Role.Button) { cta(CTA_CLOSE); onBack() },
            )
            Text(
                WORDMARK, color = Tok.muted,
                style = TypeRole.label.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.7.sp),
            )
            Text(
                stringResource(Res.string.ob_title), color = Tok.tx,
                style = TypeRole.rowTitle.copy(fontSize = 16.sp), modifier = Modifier.weight(1f),
            )
        }
        Hairline()
    }
}

/** A numbered step head: the accent-subtle circle and the step's own sentence. */
@Composable
private fun StepHead(n: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metric.gap)) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(Tok.accent.copy(alpha = 0.14f))
                .border(Metric.hairline, Tok.accent.copy(alpha = 0.52f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                n.toString(), color = Tok.accent,
                style = TypeRole.captionMono.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        Text(title, color = Tok.tx, style = TypeRole.rowTitle, modifier = Modifier.weight(1f))
    }
}

/** The platform switch. A selection, so the chosen segment is raised rather than merely tinted. */
@Composable
private fun PlatformSegment(selected: String, onPick: (String) -> Unit) {
    val outer = RoundedCornerShape(Metric.gap)
    val inner = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(outer).background(Tok.surface)
            .border(Metric.hairline, Tok.hair, outer).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(Metric.gapXs),
    ) {
        for (p in PLATFORMS) {
            val on = p == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(inner)
                    .then(if (on) Modifier.background(Tok.raised).border(Metric.hairline, Tok.hair, inner) else Modifier)
                    .clickable(role = Role.Tab) { onPick(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p, color = if (on) Tok.tx else Tok.tx2,
                    style = if (on) TypeRole.action.copy(fontSize = 14.sp) else TypeRole.body,
                )
            }
        }
    }
}

/**
 * The install-route switch: the same one-liner, or the platform's package manager.
 *
 * Deliberately narrower and quieter than [PlatformSegment] above it. The platform is a fact about the
 * reader's machine; this is a preference about how they want to install. Two selectors of equal weight
 * stacked on each other would read as one four-way control instead of two independent choices.
 */
@Composable
private fun MethodSwitch(viaPackage: Boolean, onPick: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.padding(top = Metric.gapS),
        horizontalArrangement = Arrangement.spacedBy(Metric.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (isPkg in listOf(false, true)) {
            val on = isPkg == viaPackage
            Box(
                Modifier.clip(shape)
                    .then(if (on) Modifier.background(Tok.raised).border(Metric.hairline, Tok.hair, shape) else Modifier)
                    .clickable(role = Role.Tab) { onPick(isPkg) }
                    .padding(horizontal = Metric.gapS, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                // text inside a background box: the line box must come from the FONT SIZE, not the font's own
                // metrics, or the label sits off-centre on whichever platform's fallback face differs
                Text(
                    stringResource(if (isPkg) Res.string.fr_install_pkg else Res.string.fr_install_script),
                    color = if (on) Tok.tx else Tok.muted,
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    style = tightCenter(12.5.sp),
                )
            }
        }
    }
}

/**
 * "How it stays private" — the three claims a first run has to be able to CHECK, on the screen that asks the
 * user to paste a command into their own shell (issue #342).
 *
 * It replaces a single reassuring sentence. A sentence is an assertion; these three lines each hand over the
 * artefact behind the assertion — the source, the encryption boundary, and the policy — so the reader can
 * verify rather than believe. The wording tracks the consent gate's, which is the same promise stated once.
 */
@Composable
private fun TrustBlock() {
    val shape = RoundedCornerShape(Metric.radiusS)
    Column(
        Modifier.fillMaxWidth().padding(top = Metric.gapS).clip(shape).background(Tok.surface)
            .border(Metric.hairline, Tok.hair, shape)
            .padding(horizontal = Metric.gapL, vertical = Metric.gap),
    ) {
        Text(stringResource(Res.string.fr_trust_title), color = Tok.muted, style = TypeRole.label)
        TrustLine(stringResource(Res.string.fr_trust_open), stringResource(Res.string.fr_trust_github)) {
            cta(CTA_GITHUB); openWebUrl(GITHUB_REPO_URL)
        }
        TrustLine(stringResource(Res.string.fr_trust_e2e), null, null)
        TrustLine(stringResource(Res.string.fr_trust_policy), stringResource(Res.string.privacy_gate_policy)) {
            cta(CTA_PRIVACY); openWebUrl(PRIVACY_POLICY_URL)
        }
    }
}

/**
 * One trust line: a hairline mark, the claim, and (where there is one) the link that proves it.
 *
 * The mark is a glyph sitting beside prose at a different size — CLAUDE.md's alignment rule, and adding
 * [tightCenter] to only one side of such a pair still leaves them off. Both take the SAME line box here, so
 * the mark lands on the claim's first line whatever fallback face each of them resolves to. That box is
 * [tightCenter]'s (centred, `Trim.None`) with the height raised off its 1.15× default, because the claim is
 * prose that wraps and 1.15× would set it solid. [Alignment.Top] keeps the mark on that first line.
 */
@Composable
private fun TrustLine(body: String, link: String?, onLink: (() -> Unit)?) {
    val lineBox = tightCenter(12.5.sp).copy(lineHeight = 18.sp)
    Row(
        Modifier.fillMaxWidth().padding(top = Metric.gapS),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Metric.gapS),
    ) {
        Text("◇", color = Tok.hair, fontSize = 10.sp, style = lineBox)
        Column(Modifier.weight(1f)) {
            Text(body, color = Tok.tx2, fontSize = 12.5.sp, style = lineBox)
            if (link != null && onLink != null) Text(
                link, color = Tok.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(12.5.sp),
                modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(Metric.radiusS))
                    .clickable(role = Role.Button, onClick = onLink)
                    .padding(top = 10.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * [text] with every occurrence of [command] set in monospace.
 *
 * A command quoted inside prose has to LOOK like a command — a user retyping "cc-pocket-daemon pair" from a
 * proportional sentence has no way to see where it starts and stops. Localisations keep the command literal,
 * so this works unchanged in both languages; if a translation drops it, the sentence simply renders plain
 * rather than throwing.
 */
internal fun monoInline(text: String, command: String): AnnotatedString = buildAnnotatedString {
    val mono = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    if (command.isEmpty()) { append(text); return@buildAnnotatedString }  // indexOf("") never advances
    var i = 0
    while (true) {
        val at = text.indexOf(command, i)
        if (at < 0) { append(text.substring(i)); break }
        append(text.substring(i, at))
        withStyle(mono) { append(command) }
        i = at + command.length
    }
}
