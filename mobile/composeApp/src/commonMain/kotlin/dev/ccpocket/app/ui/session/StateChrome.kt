package dev.ccpocket.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.ap_required
import dev.ccpocket.app.resources.st_act_answer
import dev.ccpocket.app.resources.st_act_review
import dev.ccpocket.app.resources.st_answer
import dev.ccpocket.app.resources.st_complete
import dev.ccpocket.app.resources.st_failure
import dev.ccpocket.app.resources.st_new_result
import dev.ccpocket.app.resources.st_running
import dev.ccpocket.app.theme.Metric
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/**
 * The rendering half of the state vocabulary (Mobile UI 2.0 · foundations "Status marks").
 *
 * Shape and a written label carry every state; colour only confirms it. Both Sessions rows and the Chat
 * state block draw through here, which is what keeps their grammar identical in the same frame.
 */

/** The live `Tok` slot behind a [StateTone]. A getter, so a theme/accent switch recomposes every reader. */
@Composable
fun stateColor(tone: StateTone): Color = when (tone) {
    StateTone.ATTENTION -> Tok.warn
    StateTone.DANGER -> Tok.danger
    StateTone.RUNNING -> Tok.ok
    StateTone.NEUTRAL -> Tok.muted
}

/** The written state. Never optional: a mark without a label is colour-only signalling. */
@Composable
fun stateLabel(state: SurfaceState): String = stringResource(
    when (state) {
        // one owner for "Approval required" — the same words the Secure Approval header uses
        SurfaceState.APPROVAL -> Res.string.ap_required
        SurfaceState.ANSWER -> Res.string.st_answer
        SurfaceState.FAILURE -> Res.string.st_failure
        SurfaceState.RUNNING -> Res.string.st_running
        SurfaceState.NEW_RESULT -> Res.string.st_new_result
        SurfaceState.COMPLETE -> Res.string.st_complete
    },
)

/** The verb on the state's action. The tap only OPENS the session — the decision stays with its own surface. */
@Composable
fun stateActionLabel(action: StateAction): String = stringResource(
    when (action) {
        StateAction.REVIEW -> Res.string.st_act_review
        StateAction.ANSWER -> Res.string.st_act_answer
    },
)

/**
 * The state's mark. Diamond = intervention, square = failure, filled dot = running, ring = settled —
 * distinguishable with no colour at all, which is the point (Proofs · "legible in greyscale").
 */
@Composable
fun StateMarkGlyph(mark: StateMark, color: Color, size: Dp = 8.dp) {
    when (mark) {
        // rotate() only turns the drawn square; the 8dp box keeps its layout footprint, so a diamond and a
        // dot sit on the same optical baseline instead of nudging the title beside them
        StateMark.DIAMOND -> Box(Modifier.size(size).rotate(45f).background(color))
        StateMark.SQUARE -> Box(Modifier.size(size).background(color))
        StateMark.DOT -> Box(Modifier.size(size).clip(CircleShape).background(color))
        StateMark.RING -> Box(Modifier.size(size).clip(CircleShape).border(1.5.dp, color, CircleShape))
    }
}

/** A hairline rule — the only separator the low-container layout uses between list rows and turns. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Tok.hair) =
    Box(modifier.fillMaxWidth().height(Metric.hairline).background(color))
