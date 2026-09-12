package dev.ccpocket.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi

/** A frozen Compose clock needs a frame to apply state changes before checking the new UI. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.advanceFrameAndWait() {
    mainClock.advanceTimeByFrame()
    waitForIdle()
}
