package dev.ccpocket.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * "Attach image" composer icon — the primary direction from the claude-design handoff
 * `Attach Icon Spec.html`: a rounded picture frame (sun + mountain) whose top-right corner
 * opens for a ＋ badge, reading as "add a photo". Stroke-based, 1.5pt, round caps/joins,
 * no fill — one path set drawn in a single color so state is a tint swap at the call site
 * (default Tok.tx2 / pressed Tok.accent). Path data is verbatim from the spec's SVG.
 */
val AttachImageIcon: ImageVector by lazy {
    fun ImageVector.Builder.stroked(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )

    ImageVector.Builder(
        name = "AttachImage",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // open frame: top edge stops short of the corner, right edge resumes below the plus
        stroked {
            moveTo(13f, 4f)
            horizontalLineTo(6.5f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 4f, 6.5f)
            verticalLineTo(17.5f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 6.5f, 20f)
            horizontalLineTo(16.5f)
            arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 19f, 17.5f)
            verticalLineTo(11f)
        }
        // ＋ badge (top-right)
        stroked {
            moveTo(19f, 4.2f); verticalLineTo(9f)
            moveTo(16.6f, 6.6f); horizontalLineTo(21.4f)
        }
        // sun (stroked ring): two semicircle arcs about (8.7, 9.4) r=1.4
        stroked {
            moveTo(7.3f, 9.4f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10.1f, 9.4f)
            arcTo(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7.3f, 9.4f)
            close()
        }
        // mountain
        stroked {
            moveTo(4.4f, 17.6f)
            lineTo(9f, 12.6f)
            lineTo(12.2f, 15.8f)
            lineTo(14.2f, 13.6f)
            lineTo(18.4f, 17.8f)
        }
    }.build()
}
