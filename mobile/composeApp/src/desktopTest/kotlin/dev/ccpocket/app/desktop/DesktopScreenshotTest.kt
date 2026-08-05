package dev.ccpocket.app.desktop

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.pairing.encode
import dev.ccpocket.app.ui.HelpCenterScreen
import dev.ccpocket.app.ui.HelpEntryPoint
import dev.ccpocket.app.ui.review.ReviewCenterScreen
import dev.ccpocket.app.ui.review.ReviewCenterState
import dev.ccpocket.app.ui.review.ReviewInviteScreen
import dev.ccpocket.app.ui.review.ReviewTab
import dev.ccpocket.protocol.ArtifactKind
import dev.ccpocket.protocol.ArtifactRef
import dev.ccpocket.protocol.CollaboratorDirection
import dev.ccpocket.protocol.CollaboratorInvite
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.ReviewBrief
import dev.ccpocket.protocol.ReviewContact
import dev.ccpocket.protocol.ReviewInboxItem
import dev.ccpocket.protocol.ReviewRequest
import dev.ccpocket.protocol.ReviewStatus
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not a behavioural test — a screenshot generator. Renders each desktop surface offscreen (Skia, the same
 * engine the app uses) into the build/screenshots folder as PNGs at 2x. Deterministic, headless, no display
 * or screen-grab needed. Run with the gradle desktopTest task filtered to this class.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopScreenshotTest {

    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val scale = 2 // pixel scale; [w]/[h] are LOGICAL dp, the scene takes pixels → multiply

    private fun shot(name: String, w: Int, h: Int, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w * scale, height = h * scale, density = Density(scale.toFloat())) {
            PocketTheme { Box(Modifier.fillMaxSize().background(Tok.base)) { content() } }
        }
        try {
            val data = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed for $name")
            File(outDir, name).writeBytes(data.bytes)
        } finally {
            scene.close()
        }
    }

    /** The full window look: a static title bar (real DkTitleBar needs a window) over the live two-pane shell. */
    @Composable
    private fun WindowFrame(model: DesktopModel) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(38.dp).background(Tok.base).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFED6A5E)))
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFF4BE4F)))
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF61C554)))
                }
                Text("cc-pocket", color = Tok.muted, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, modifier = Modifier.padding(start = 2.dp))
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, Tok.hair, RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Tok.muted, modifier = Modifier.size(13.dp))
                    Text("Search", color = Tok.muted, fontSize = 11.5.sp)
                    Key("⌘K")
                }
                Dot(Tok.ok, 7.dp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Box(Modifier.fillMaxWidth().weight(1f)) { DesktopApp(model) }
        }
    }

    private fun seed(block: SeedDesktopModel.() -> Unit = {}) = SeedDesktopModel().apply(block)

    @Test
    fun generate() {
        val W = 1180; val H = 798
        shot("01-shell.png", W, H) { WindowFrame(seed()) } // fleet: machine-grouped sidebar + split watch pane
        shot("02-codex-diff-approval.png", W, H) { WindowFrame(seed { selectSession(sessions[2]) }) }
        shot("03-attention-popover.png", W, H) { WindowFrame(seed { showAttention = true }) }
        shot("04-new-session.png", W, H) { WindowFrame(seed { showNewSession = true }) }
        shot("05-tray-quick-approve.png", W, H) { WindowFrame(seed { showTray = true }) }
        shot("06-focused-permission.png", W, H) { WindowFrame(seed { selectSession(sessions[2]); showPermissionModal = true }) }
        shot("07-command-palette.png", W, H) { WindowFrame(seed { palette = PaletteScope.ALL }) }
        shot("08-settings.png", W, H) { WindowFrame(seed { showSettings = true }) }
        shot("09-help-learning-mobile.png", 390, 844) {
            HelpCenterScreen(HelpEntryPoint.CHAT, onBack = {}, onOpenChanges = {})
        }
        shot("10-help-learning-mobile-light.png", 390, 844) {
            PocketTheme(dark = false) {
                HelpCenterScreen(HelpEntryPoint.CHAT, onBack = {}, onOpenChanges = {})
            }
        }

        // ---- Review Center (REVIEW-REQUEST.md §12) ----
        // The SHARED `ui/review` surfaces the desktop overlay hosts, rendered directly with fixed data:
        // the overlay itself needs a live daemon repository, and inventing a ledger behind it is the one
        // thing this feature must not do. These are the same composables, with the data made explicit.
        shot("11-review-center-inbox.png", W, H) { ReviewShots.center(ReviewTab.INBOX) }
        shot("12-review-center-sent.png", W, H) { ReviewShots.center(ReviewTab.SENT) }
        shot("13-review-invite-fingerprint.png", 460, 700) { ReviewShots.invite() }

        val shots = outDir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
        println("[screenshots] wrote ${shots.size} files to ${outDir.absolutePath}")
        shots.forEach { println("[screenshots]   ${it.name}  ${it.length() / 1024}KB") }
        assertTrue(shots.size >= 13, "expected at least 13 screenshots")
    }
}

/**
 * Fixed data for the Review Center shots. Deterministic on purpose — including the daemon key, which is
 * a REAL P-256 public key (the invite codec refuses anything else, so a placeholder would render the
 * empty state) but a CONSTANT one, so the fingerprint in the image does not change between runs.
 */
private object ReviewShots {

    /**
     * A real, throwaway P-256 public key — 65 uncompressed bytes, base64url. Generated once and pinned
     * here so the fingerprint in the image is the same on every run; it is a PUBLIC key whose private
     * half was discarded, so there is nothing to leak.
     *
     * It has to be a REAL point: the invite codec validates it ([E2ECrypto.isValidPublicKey]), and a
     * placeholder would render the screen's empty state instead of the fingerprint this shot exists for.
     */
    const val DAEMON_PUB =
        "BIG2-aFCrdPw2-eBeNRZxqxPvkWGfFEWSxcQmzi1cIhXn8VkUEnqKyL8nswpSr8zkIrqy2z7ydwb_GxBukEfjG8"

    private fun request(
        id: String,
        status: ReviewStatus,
        title: String,
        request: String,
        createdAt: Long,
    ) = ReviewRequest(
        id = id, senderDeviceId = "dev-frank", senderLabel = "Frank",
        recipientDeviceId = "dev-me", recipientLabel = "Panda",
        title = title, brief = ReviewBrief(request = request),
        artifacts = listOf(ArtifactRef(ArtifactKind.MERGE_REQUEST, url = "https://git.example.com/relay/-/merge_requests/482")),
        status = status, revision = 2, createdAt = createdAt, updatedAt = createdAt,
    )

    private val state = ReviewCenterState(
        received = listOf(
            ReviewInboxItem(
                linkId = "pl_frank", peerLabel = "Frank", peerFingerprint = "amber-basil-coral-dune · echo-fjord-grove-haven",
                request = request("rr_KQ7t", ReviewStatus.DELIVERED, "Relay ACK fence", "Check the retry race in the delivery ACK path", 1_000),
            ),
            ReviewInboxItem(
                linkId = "pl_aiko", peerLabel = "Aiko", peerFingerprint = "iris-jade-krill-lunar · mango-orbit-pixel-quartz",
                request = request("rr_9fLm", ReviewStatus.IN_PROGRESS, "Purpose separation", "Does the contact split hold for old peers?", 900),
                pending = listOf("acknowledge"),
            ),
            ReviewInboxItem(
                linkId = "pl_frank", peerLabel = "Frank", peerFingerprint = "amber-basil-coral-dune · echo-fjord-grove-haven",
                request = request("rr_2Bd1", ReviewStatus.CLOSED, "Windows launcher", "Confirm the console subsystem fix", 800),
            ),
        ),
        sent = listOf(
            request("rr_Xp4w", ReviewStatus.QUEUED, "First-contact PSK", "Does the migration survive a lost response?", 1_100)
                .copy(recipientLabel = "Aiko", senderLabel = "Panda"),
            request("rr_Tm8k", ReviewStatus.RESPONDED, "Prompt fence", "Is any peer text reachable as trusted prose?", 1_050)
                .copy(recipientLabel = "Frank", senderLabel = "Panda"),
        ),
        contacts = listOf(
            ReviewContact(
                "dev-frank", "Frank", CollaboratorDirection.OUTBOUND,
                fingerprint = "amber-basil-coral-dune · echo-fjord-grove-haven", connectedAt = 500, canSend = true,
            ),
            ReviewContact(
                "pl_aiko", "Aiko", CollaboratorDirection.INBOUND,
                fingerprint = "iris-jade-krill-lunar · mango-orbit-pixel-quartz", connectedAt = 400, canSend = false,
            ),
        ),
    )

    @Composable
    fun center(tab: ReviewTab) {
        ReviewCenterScreen(
            state = state, tab = tab, onTab = {}, pendingCount = 2,
            onOpenReceived = {}, onOpenSent = {}, onNewReview = {},
            onInvite = {}, onJoin = {}, onRemoveContact = {},
        )
    }

    @Composable
    fun invite() {
        val uri = CollaboratorInvite(
            relay = "wss://pocket.ark-nexus.cc", accountId = "acct-panda", daemonPub = DAEMON_PUB,
            ticket = "one-time-connect-ticket", ownerLabel = "Panda · MacBook", ttlSec = 600,
            purpose = CollaboratorPurpose.REVIEW,
        ).encode()
        ReviewInviteScreen(invite = uri, ttlSec = 600, creating = false, error = null, copied = false, onCopy = {})
    }
}
