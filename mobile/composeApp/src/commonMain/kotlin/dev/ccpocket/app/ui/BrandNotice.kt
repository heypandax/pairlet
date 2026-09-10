package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.brand.BrandTransition
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.brand_dismiss
import dev.ccpocket.app.resources.brand_renamed
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.stringResource

/** In-flow, dismissible notice. It does not interrupt pairing, approvals or navigation. */
@Composable
fun BrandNotice(transition: BrandTransition) {
    var visible by remember(transition) { mutableStateOf(transition.pending) }
    if (!visible) return
    Surface(color = Tok.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.brand_renamed), color = Tok.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { transition.dismiss(); visible = false }) {
                Text(stringResource(Res.string.brand_dismiss), color = Tok.accent)
            }
        }
    }
}
