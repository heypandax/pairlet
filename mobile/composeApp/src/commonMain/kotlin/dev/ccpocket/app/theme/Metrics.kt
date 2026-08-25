package dev.ccpocket.app.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mobile UI 2.0 core metrics — the non-color half of the foundations strip (design brief §"Locked visual
 * principles"). Colors already have an owner in [Tok]; spacing, radii and the 48 dp touch floor did not, so
 * every surface re-invented them inline.
 *
 * Deliberately introduced with ONE consumer (the Secure Approval sheet). Existing call sites keep their
 * literals until their own surface is migrated — a mass rewrite would bury the visual slice it belongs to.
 */
object Metric {
    /** Screen-edge gutter for a full-width surface. */
    val gutter = 20.dp
    val gapXs = 4.dp
    val gapS = 8.dp
    val gap = 12.dp
    val gapL = 16.dp
    val radiusS = 10.dp
    val radius = 14.dp
    /** Top corners of a bottom-anchored surface. */
    val radiusSheet = 20.dp
    /** Minimum interactive target for the new core surfaces (design brief). */
    val touch = 48.dp
    val hairline = 1.dp
}

/**
 * The type roles the new core surfaces read. Size/weight/leading only — color stays a [Tok] decision at the
 * call site, so a role works unchanged in both palettes. Monospace is reserved for code, command, path,
 * branch, IDs and counts (design brief).
 */
object TypeRole {
    /** The screen's own name ("Sessions"). One per screen; nothing else competes with it. */
    val screenTitle = TextStyle(fontSize = 31.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
    /** A list row's / chat header's subject line. Wraps to three lines before it may ellipsize. */
    val rowTitle = TextStyle(fontSize = 17.5.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    /** Supporting copy under a row title — the first prompt, a state's detail. */
    val preview = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    /** The mono metadata line: agent, branch, relative time, counts. */
    val metaMono = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val bodyMono = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
    val caption = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp)
    val captionMono = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
    /** Uppercase section/field label. */
    val label = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
    val action = TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    val actionSub = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace)
}
