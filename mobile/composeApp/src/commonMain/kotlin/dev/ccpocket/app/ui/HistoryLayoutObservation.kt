package dev.ccpocket.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlinx.coroutines.launch

/** Read the token during measurement: a newly applied history invalidates measurement even if the
 * viewport size did not change. Only a real nonzero placement can produce a callback. The callback is
 * deferred to the next UI frame so it never mutates state from the layout pass. */
@Composable
fun Modifier.observeHistoryLayout(token: () -> String?, onPlaced: (String) -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    return layout { measurable, constraints ->
        val measuredToken = token()
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
            if (measuredToken != null && placeable.width > 0 && placeable.height > 0) scope.launch {
                withFrameNanos { }
                if (token() == measuredToken) onPlaced(measuredToken)
            }
        }
    }
}
