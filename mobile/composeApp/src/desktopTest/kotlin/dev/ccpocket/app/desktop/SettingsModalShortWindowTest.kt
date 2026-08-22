package dev.ccpocket.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.settings_tab_about
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test

/**
 * Issue #209 re-report: on a short / high-DPI-scaled window the Overlay clamps the settings modal below
 * its natural 500dp, and the nav rail's bottom items (About) were cropped with no way to reach them —
 * the v1.7.0 fix gave only the CONTENT pane a scroll, not the rail. This pins the rail's own scroll by
 * rendering the modal under a 300dp ceiling (what the Overlay does on such windows) and requiring the
 * last rail item to be scroll-reachable and clickable.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsModalShortWindowTest {

    @Test
    fun about_stays_reachable_when_the_modal_is_clamped_short() = runComposeUiTest {
        setContent { PocketTheme { Box(Modifier.height(300.dp)) { SettingsModal(SeedDesktopModel()) {} } } }
        onAllNodes(hasText(str(Res.string.settings_tab_about))).onFirst().performScrollTo().performClick()
        waitForIdle()
    }
}
