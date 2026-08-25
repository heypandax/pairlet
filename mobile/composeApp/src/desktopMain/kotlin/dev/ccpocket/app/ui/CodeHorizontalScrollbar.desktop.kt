package dev.ccpocket.app.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal const val CODE_SCROLLBAR_TAG = "code-horizontal-scrollbar"

@Composable
internal actual fun CodeHorizontalScrollbar(state: ScrollState, modifier: Modifier) {
    if (state.maxValue > 0) {
        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = modifier.testTag(CODE_SCROLLBAR_TAG),
        )
    }
}
