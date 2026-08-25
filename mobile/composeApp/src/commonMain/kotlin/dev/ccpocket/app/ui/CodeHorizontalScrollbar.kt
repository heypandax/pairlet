package dev.ccpocket.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A visible drag affordance for fenced-code overflow on pointer-driven platforms (#307).
 *
 * Touch platforms already pan the same [ScrollState] directly and intentionally render no extra chrome;
 * desktop supplies the actual scrollbar below the code body.
 */
@Composable
internal expect fun CodeHorizontalScrollbar(state: ScrollState, modifier: Modifier)
