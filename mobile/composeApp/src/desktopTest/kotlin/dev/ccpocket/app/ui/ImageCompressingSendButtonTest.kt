package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.advanceFrameAndWait
import dev.ccpocket.app.data.ImgState
import dev.ccpocket.app.data.PendingImage
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.desktop.DesktopApp
import dev.ccpocket.app.desktop.DesktopModel
import dev.ccpocket.app.desktop.SEND_TAG
import dev.ccpocket.app.desktop.SEND_WAITS_TAG
import dev.ccpocket.app.desktop.SeedDesktopModel
import dev.ccpocket.app.pairing.PairedDaemon
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.composer_compressing
import dev.ccpocket.app.resources.send
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionLive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A photo still compressing holds the send back (`sendPrompt` returns false and keeps text and photo — see
 * ImageCompressingSendTest). The button has to SAY so: it used to look ready, and a click did nothing at all.
 * Each platform shows the wait the way it already shows an upload in flight — the phone's status slot in Send's
 * place, the desktop's spinner circle — and Send comes back the moment compression finishes.
 */
@OptIn(ExperimentalTestApi::class)
class ImageCompressingSendButtonTest {

    private val photo = byteArrayOf(1, 2, 3)

    @Test
    fun phoneSendWaitsWhileAPhotoCompresses() = runDesktopComposeUiTest(402, 874) {
        mainClock.autoAdvance = false
        var repo: PocketRepository? = null
        setContent {
            val scope = rememberCoroutineScope()
            val r = remember {
                PocketRepository(
                    scope,
                    PairedDaemon(relay = "wss://test.invalid", accountId = "acct", daemonPub = "pub", deviceId = "dev", credential = "cred"),
                ).apply {
                    receiveForTest(SessionLive(convoId = "c1", workdir = "/w", sessionId = "s1", mode = PermissionMode.DEFAULT))
                    pendingImages.add(PendingImage(7, photo, ImgState.Compressing))
                }
            }
            repo = r
            PocketTheme { Box(Modifier.fillMaxSize()) { ChatScreen(r) } }
        }
        advanceFrameAndWait()
        onAllNodes(hasSetTextAction()).onFirst().performTextInput("look at this")
        advanceFrameAndWait()

        val send = hasContentDescription(str(Res.string.send)) and hasClickAction()
        assertEquals(0, onAllNodes(send).fetchSemanticsNodes().size, "no Send while the photo is still compressing")
        val waits = str(Res.string.composer_compressing)
        assertEquals(1, onAllNodes(hasContentDescription(waits)).fetchSemanticsNodes().size, "the slot names the wait")
        assertEquals(0, onAllNodes(hasContentDescription(waits) and hasClickAction()).fetchSemanticsNodes().size, "…and is not a control")

        repo!!.pendingImages[0] = PendingImage(7, photo, ImgState.Ready) // compression finished
        advanceFrameAndWait()
        assertEquals(1, onAllNodes(send).fetchSemanticsNodes().size, "Send is back once the photo is ready")
    }

    @Test
    fun desktopSendWaitsWhileAPhotoCompresses() = runComposeUiTest {
        val images = mutableStateListOf(PendingImage(7, photo, ImgState.Compressing))
        val model = object : DesktopModel by SeedDesktopModel() {
            override val pendingImages: List<PendingImage> get() = images
        }
        setContent { PocketTheme { DesktopApp(model) } }
        waitForIdle()

        assertEquals(0, onAllNodes(hasTestTag(SEND_TAG)).fetchSemanticsNodes().size, "no clickable send while compressing")
        assertEquals(1, onAllNodes(hasTestTag(SEND_WAITS_TAG)).fetchSemanticsNodes().size, "the waiting circle instead")
        assertEquals(0, onAllNodes(hasTestTag(SEND_WAITS_TAG) and hasClickAction()).fetchSemanticsNodes().size)

        images[0] = PendingImage(7, photo, ImgState.Ready)
        waitForIdle()
        assertEquals(1, onAllNodes(hasTestTag(SEND_TAG) and hasClickAction()).fetchSemanticsNodes().size, "send is back")
        assertEquals(0, onAllNodes(hasTestTag(SEND_WAITS_TAG)).fetchSemanticsNodes().size)
    }
}
