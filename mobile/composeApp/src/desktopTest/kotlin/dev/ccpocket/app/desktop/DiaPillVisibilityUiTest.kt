package dev.ccpocket.app.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ccpocket.app.assertPresent
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.dia_launch
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The composer's "Launch Dia" pill is a developer-machine affordance gated by [DesktopModel.diaCdpAvailable].
 * The seed model feeds the website's desktop screenshot and the demo, so it must never show the pill no
 * matter what the rendering host has installed; a model that reports Dia as available (the live model's
 * answer on a Mac with Dia) must still get it.
 */
@OptIn(ExperimentalTestApi::class)
class DiaPillVisibilityUiTest {

    @Test
    fun theSeedModelNeverShowsTheDiaPill() = runComposeUiTest {
        setContent { PocketTheme { ChatPane(SeedDesktopModel()) } }
        waitForIdle()
        assertTrue(!present(str(Res.string.dia_launch)), "the demo composer must not offer Launch Dia")
    }

    @Test
    fun aModelReportingDiaShowsThePill() = runComposeUiTest {
        val model = object : SeedDesktopModel() {
            override val diaCdpAvailable = true
        }
        setContent { PocketTheme { ChatPane(model) } }
        waitForIdle()
        assertPresent(str(Res.string.dia_launch))
    }
}
