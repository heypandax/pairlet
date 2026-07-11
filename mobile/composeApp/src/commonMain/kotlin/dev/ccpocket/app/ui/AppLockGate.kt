package dev.ccpocket.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.lock.AppLockController
import dev.ccpocket.app.lock.BiometryKind
import dev.ccpocket.app.lock.GateVisual
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The App Lock gate (issue #109) — a biometric front door shown at launch and on every qualifying resume,
 * above ALL content (including the permission sheet). Restraint over chrome: mostly monochrome, the terracotta
 * accent spent on exactly one focal point per frame. The OS draws its own Face ID sheet on top; this is the
 * branded backdrop + the fallbacks for when biometrics don't pass.
 */
@Composable
fun AppLockGate(c: AppLockController) {
    val kindName = biometryName(c.biometryKind)
    val reason = stringResource(Res.string.app_lock_reason)
    // while the gate is up, back must not drive the hidden content underneath — consume it
    dev.ccpocket.app.SystemBackHandler(enabled = true) { }
    // the OS may auto-present its sheet; kick it once when the gate appears (button re-triggers if dismissed)
    LaunchedEffect(Unit) { c.authenticate(reason) }

    val visual = c.visual.value
    val dim = visual == GateVisual.AUTHENTICATING
    val disabled = dim
    // accent lands on exactly one focal point per frame: the glyph in 1–3, the passcode button in 4–5
    val faceColor = when (visual) {
        GateVisual.IDLE, GateVisual.AUTHENTICATING, GateVisual.FAILED -> Tok.accent
        GateVisual.FALLBACK, GateVisual.LOCKED_OUT -> Tok.muted
    }
    val faceDisabled = visual == GateVisual.LOCKED_OUT

    val status: String
    val statusColor: Color
    val sub: String
    when (visual) {
        GateVisual.IDLE, GateVisual.AUTHENTICATING -> {
            status = stringResource(Res.string.app_lock_status_locked)
            statusColor = if (dim) Tok.muted else Tok.tx
            sub = stringResource(Res.string.app_lock_sub_locked)
        }
        GateVisual.FAILED -> {
            status = stringResource(Res.string.app_lock_status_failed)
            statusColor = Tok.danger
            sub = stringResource(Res.string.app_lock_sub_locked)
        }
        GateVisual.FALLBACK -> {
            status = stringResource(Res.string.app_lock_status_fallback, kindName)
            statusColor = Tok.danger
            sub = stringResource(Res.string.app_lock_sub_fallback)
        }
        GateVisual.LOCKED_OUT -> {
            status = stringResource(Res.string.app_lock_status_lockedout, kindName)
            statusColor = Tok.tx
            sub = stringResource(Res.string.app_lock_sub_lockedout)
        }
    }

    // primary action (filled) + optional secondary (quiet link), per frame
    val primaryLabel: String
    val primaryBiometric: Boolean
    val secondaryLabel: String?
    val secondaryBiometric: Boolean
    when (visual) {
        GateVisual.IDLE, GateVisual.AUTHENTICATING -> {
            primaryLabel = stringResource(Res.string.app_lock_unlock, kindName); primaryBiometric = true
            secondaryLabel = stringResource(Res.string.app_lock_passcode); secondaryBiometric = false
        }
        GateVisual.FAILED -> {
            primaryLabel = stringResource(Res.string.app_lock_try_again, kindName); primaryBiometric = true
            secondaryLabel = stringResource(Res.string.app_lock_passcode); secondaryBiometric = false
        }
        GateVisual.FALLBACK -> {
            primaryLabel = stringResource(Res.string.app_lock_passcode); primaryBiometric = false
            secondaryLabel = stringResource(Res.string.app_lock_try_again, kindName); secondaryBiometric = true
        }
        GateVisual.LOCKED_OUT -> {
            primaryLabel = stringResource(Res.string.app_lock_passcode); primaryBiometric = false
            secondaryLabel = null; secondaryBiometric = false
        }
    }

    // slow pending pulse on the glyph while the OS sheet is up (no spinner — the OS sheet is the activity)
    val pulse = if (dim) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse)).value
    } else 1f

    Box(Modifier.fillMaxSize().background(Tok.base)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── centered lockup — the calm brand moment ──
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(top = 72.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppMarkGlyph(color = if (dim) Tok.muted else Tok.tx2, width = 30.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "cc-pocket",
                    color = if (dim) Tok.muted else Tok.tx,
                    fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp,
                )
                Spacer(Modifier.height(64.dp))
                FaceIdGlyph(color = faceColor, size = 92.dp, disabled = faceDisabled, alpha = pulse)
                Spacer(Modifier.height(40.dp))
                Text(status, color = statusColor, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 27.sp)
                Spacer(Modifier.height(8.dp))
                Text(sub, color = if (dim) Tok.muted else Tok.tx2, fontSize = 14.5.sp, textAlign = TextAlign.Center, lineHeight = 21.sp, modifier = Modifier.widthIn(max = 280.dp))
            }
            // ── lower third — actions ──
            FilledAction(primaryLabel, enabled = !disabled) {
                if (primaryBiometric) c.authenticate(reason) else c.useDevicePasscode(reason)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                if (secondaryLabel != null) LinkAction(secondaryLabel, enabled = !disabled) {
                    if (secondaryBiometric) c.authenticate(reason) else c.useDevicePasscode(reason)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * The opaque privacy cover shown in the OS app-switcher while unlocked-but-backgrounded (design frame D):
 * theme base + the same lockup as the gate, never a blur — so cc-pocket's thumbnail leaks only the brand.
 */
@Composable
fun AppLockCover() {
    Box(Modifier.fillMaxSize().background(Tok.base), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppMarkGlyph(color = Tok.tx2, width = 28.dp)
            Spacer(Modifier.height(15.dp))
            Text("cc-pocket", color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(42.dp))
            FaceIdGlyph(color = Tok.accent, size = 76.dp)
        }
    }
}

/** Localized biometric name for the kind-aware copy ("Face ID" / "Touch ID" / "Fingerprint" / generic). */
@Composable
internal fun biometryName(kind: BiometryKind): String = stringResource(
    when (kind) {
        BiometryKind.FACE -> Res.string.biometry_face
        BiometryKind.TOUCH -> Res.string.biometry_touch
        BiometryKind.FINGERPRINT -> Res.string.biometry_fingerprint
        BiometryKind.GENERIC, BiometryKind.NONE -> Res.string.biometry_generic
    }
)

@Composable
private fun FilledAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(13.dp))
            .background(if (enabled) Tok.accent else Tok.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Tok.base, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LinkAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Tok.tx2, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** The cc-pocket app mark — chevron + bar, kept monochrome (design app-lock-app.jsx AppMark). */
@Composable
internal fun AppMarkGlyph(color: Color, width: Dp) {
    Canvas(Modifier.size(width = width, height = width * (37f / 56f))) {
        val sx = size.width / 56f
        val sy = size.height / 37f
        val chevron = Path().apply {
            moveTo(7f * sx, 4f * sy); lineTo(23f * sx, 18.5f * sy); lineTo(7f * sx, 33f * sy)
        }
        drawPath(chevron, color, style = Stroke(width = 7.5f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawRoundRect(color, topLeft = Offset(33f * sx, 3f * sy), size = Size(15f * sx, 31f * sy), cornerRadius = CornerRadius(4f * sx, 4f * sy))
    }
}

/** The Face ID line glyph — 1.5pt line icon, the single security signifier (design app-lock-app.jsx FaceID).
 *  Drawn from the same 0..64 viewBox coordinates; rounded corners via short quadratics. */
@Composable
internal fun FaceIdGlyph(color: Color, size: Dp, disabled: Boolean = false, alpha: Float = 1f) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 64f
        val c = color.copy(alpha = alpha)
        val stroke = Stroke(width = 2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun x(v: Float) = v * s
        fun y(v: Float) = v * s
        val path = Path().apply {
            // corner brackets (rounded L at radius 5)
            moveTo(x(6f), y(20f)); lineTo(x(6f), y(11f)); quadraticTo(x(6f), y(6f), x(11f), y(6f)); lineTo(x(20f), y(6f))
            moveTo(x(44f), y(6f)); lineTo(x(53f), y(6f)); quadraticTo(x(58f), y(6f), x(58f), y(11f)); lineTo(x(58f), y(20f))
            moveTo(x(6f), y(44f)); lineTo(x(6f), y(53f)); quadraticTo(x(6f), y(58f), x(11f), y(58f)); lineTo(x(20f), y(58f))
            moveTo(x(44f), y(58f)); lineTo(x(53f), y(58f)); quadraticTo(x(58f), y(58f), x(58f), y(53f)); lineTo(x(58f), y(44f))
            // eyes
            moveTo(x(24f), y(25f)); lineTo(x(24f), y(30f))
            moveTo(x(40f), y(25f)); lineTo(x(40f), y(30f))
            // nose
            moveTo(x(32f), y(26f)); lineTo(x(32f), y(36f)); lineTo(x(28.5f), y(36f))
            // mouth
            moveTo(x(25f), y(41f)); quadraticTo(x(32f), y(46.5f), x(39f), y(41f))
            // disabled slash
            if (disabled) { moveTo(x(12f), y(12f)); lineTo(x(52f), y(52f)) }
        }
        drawPath(path, c, style = stroke)
    }
}
