package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.bridge_runner_stop
import dev.ccpocket.app.resources.share_revoke
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.BridgeInfo
import dev.ccpocket.protocol.BridgeRunnerState
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BridgesPaneUiTest {

    @Test
    fun bridgeActionsStayInsideSettingsPane() = runComposeUiTest {
        val model = object : DesktopModel by SeedDesktopModel() {
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
        }

        // 475dp is the usable Bridges pane width inside the 700dp Settings modal.
        setContent { PocketTheme { Box(Modifier.width(475.dp)) { BridgesPane(model) } } }
        waitForIdle()

        val revokeBounds = onNodeWithText(str(Res.string.share_revoke)).getUnclippedBoundsInRoot()
        assertTrue(
            revokeBounds.right <= 475.dp,
            "Revoke must be fully inside the pane, but its right edge was ${revokeBounds.right}",
        )
        val stopBounds = onNodeWithText(str(Res.string.bridge_runner_stop)).getUnclippedBoundsInRoot()
        assertTrue(
            stopBounds.left <= 20.dp,
            "The second-row actions must start at the pane's left padding, but Stop began at ${stopBounds.left}",
        )
    }
}
