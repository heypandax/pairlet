package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.bridge_revoke_c1
import dev.ccpocket.app.resources.bridge_revoke_c2
import dev.ccpocket.app.resources.bridge_revoke_confirm
import dev.ccpocket.app.resources.bridge_revoke_title
import dev.ccpocket.app.resources.bridge_runner_stop
import dev.ccpocket.app.resources.bridge_unbind
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeRunnerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BridgesPaneUiTest {

    /** Records what the pane actually asked the daemon to do — a revoke is not a rendering detail. */
    private class RecordingModel(val revoked: MutableList<String> = mutableListOf()) :
        DesktopModel by SeedDesktopModel() {
        override val bridgesLoaded = true
        override val bridges = listOf(
            BridgeInfo(
                name = "feishu-bot",
                workdirs = listOf("/Users/test/app-test", "/Users/test/cc-pocket"),
                online = true,
                maxSessions = 2,
                runner = BridgeRunnerState(kind = "feishu", scriptPath = "", running = true),
            ),
        )

        override fun revokeBridge(name: String) { revoked += name }
    }

    /** 475dp is the usable Bridges pane width inside the 700dp Settings modal. */
    private fun pane(model: DesktopModel): @androidx.compose.runtime.Composable () -> Unit = {
        PocketTheme { Box(Modifier.width(475.dp)) { BridgesPane(model) } }
    }

    /**
     * The two tiers survive the pane's real width (issue #259 keeps the old containment proof).
     *
     * The process chips still start at the row's left padding, and the destructive action — no longer one
     * of them — sits in the footer below, right-aligned and fully inside the pane.
     */
    @Test
    fun bridgeActionsStayInsideSettingsPaneInTwoTiers() = runComposeUiTest {
        setContent(pane(RecordingModel()))
        waitForIdle()

        val stop = onAllNodes(hasClickAction() and hasText(str(Res.string.bridge_runner_stop))).onFirst()
            .getUnclippedBoundsInRoot()
        assertTrue(
            stop.left <= 20.dp,
            "The process chips must start at the pane's left padding, but Stop began at ${stop.left}",
        )
        val unbind = onAllNodes(hasClickAction() and hasText(str(Res.string.bridge_unbind))).onFirst()
            .getUnclippedBoundsInRoot()
        assertTrue(
            unbind.right <= 475.dp,
            "The destructive action must be fully inside the pane, but its right edge was ${unbind.right}",
        )
        assertTrue(unbind.top >= stop.bottom, "…and below the chip row, not beside it")
        assertTrue(unbind.left > stop.right, "…right-aligned in its own footer")
    }

    /**
     * The desktop asks first, like the phone always did.
     *
     * Before this the pointer platform revoked on the naked click: one stray click on a 「撤销」 pill and the
     * credential was gone. The 「…」 in the new label is a promise, and these three moves are the promise
     * being kept — nothing happens on the click, nothing happens on Cancel, and only the dialog's own
     * filled-danger button reaches `revokeBridge`.
     */
    @Test
    fun unbindOpensAConfirmDialogAndOnlyTheConfirmButtonRevokes() = runComposeUiTest {
        val model = RecordingModel()
        setContent(pane(model))
        waitForIdle()

        // 1 · the click opens the dialog and does NOT revoke
        onAllNodes(hasClickAction() and hasText(str(Res.string.bridge_unbind))).onFirst().performClick()
        waitForIdle()
        assertTrue(present(str(Res.string.bridge_revoke_title)), "the confirm dialog must appear")
        assertTrue(present("feishu-bot"), "…naming WHICH bridge is at stake")
        assertTrue(present(str(Res.string.bridge_revoke_c1)), "…and both consequences")
        assertTrue(present(str(Res.string.bridge_revoke_c2)))
        assertEquals(emptyList(), model.revoked, "opening the question must not answer it")

        // 2 · Cancel backs out, still without revoking
        onNodeWithText(str(Res.string.cancel)).performClick()
        waitForIdle()
        assertFalse(present(str(Res.string.bridge_revoke_title)), "Cancel closes the dialog")
        assertEquals(emptyList(), model.revoked, "Cancel must not revoke")

        // 3 · only the deliberate second move does it
        onAllNodes(hasClickAction() and hasText(str(Res.string.bridge_unbind))).onFirst().performClick()
        waitForIdle()
        onNodeWithText(str(Res.string.bridge_revoke_confirm)).performClick()
        waitForIdle()
        assertEquals(listOf("feishu-bot"), model.revoked, "the confirm button is the only path to revoke")
        assertFalse(present(str(Res.string.bridge_revoke_title)), "…and it closes the dialog behind it")
    }
}
