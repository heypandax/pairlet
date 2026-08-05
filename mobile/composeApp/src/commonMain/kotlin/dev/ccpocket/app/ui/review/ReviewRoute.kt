package dev.ccpocket.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.rv_title
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource
import qrgenerator.QRCodeImage
import qrscanner.CameraLens
import qrscanner.QrScanner

/**
 * The mobile Review Center route (REVIEW-REQUEST.md §12): the shared [ReviewCenterFlow] with this
 * platform's chrome — a top bar, the camera scanner and a QR renderer.
 *
 * Back handling is the app's LIFO [SystemBackHandler] convention. Registered HERE, above the content
 * stack, so Android's back button leaves the Center rather than falling through to the session
 * navigation underneath it; iOS has no system gesture to catch, hence the always-present ‹.
 */
@Composable
fun ReviewCenterRoute(repo: PocketRepository, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tok.base)) {
        ReviewCenterFlow(
            repo = repo,
            scanner = { onScanned -> ReviewScanBox(onScanned) },
            qr = { blob -> ReviewQr(blob) },
            // the flow owns the sub-page stack, so it also owns back: pop a sub-page, or leave
            onExit = onBack,
            header = { back ->
                Row(
                    Modifier.fillMaxWidth().background(Tok.base).padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "‹", color = Tok.tx2, fontSize = 22.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { back?.invoke() ?: onBack() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(Res.string.rv_title), color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(34.dp))
                }
            },
        )
    }
}

/** The same qr-kit viewfinder pairing and Join-folder use. A scan only DECODES here — the join screen
 *  still asks the human to compare the fingerprint before anything is sent to the daemon. */
@Composable
private fun ReviewScanBox(onScanned: (String) -> Unit) {
    var handled by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().size(220.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)
            .border(1.dp, Tok.hair, RoundedCornerShape(18.dp)),
    ) {
        QrScanner(
            modifier = Modifier.fillMaxSize(),
            flashlightOn = false,
            cameraLens = CameraLens.Back,
            openImagePicker = false,
            onCompletion = { v -> if (!handled) { handled = true; onScanned(v) } },
            imagePickerHandler = {},
            onFailure = {},
            overlayColor = Color.Transparent,
            overlayBorderColor = Color.Transparent,
        )
    }
}

@Composable
private fun ReviewQr(blob: String) {
    Box(
        Modifier.size(188.dp).clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        QRCodeImage(url = blob, contentDescription = "review invite QR", modifier = Modifier.size(168.dp))
    }
}

/** The header dot: a colleague is waiting, without spelling out a count in a 36dp icon button. */
@Composable
fun ReviewPendingDot(count: Int) {
    if (count <= 0) return
    Column(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Tok.accent)) {}
}
