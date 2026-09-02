package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.PRIVACY_POLICY_URL
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.TypeRole
import dev.ccpocket.app.ui.entry.EntryPrimaryButton
import dev.ccpocket.app.ui.entry.EntryQuietAction
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One-time data disclosure shown before ANYTHING else renders — pairing, Demo mode, and every
 * content screen sit behind it (App Review guideline 5.1.2(i): disclose what is sent and to whom,
 * and obtain permission, BEFORE any personal data can leave the device). Acceptance is persisted
 * via [dev.ccpocket.app.data.PocketRepository.acceptPrivacyConsent]; the linked policy stays
 * reachable afterwards from Settings.
 */
@Composable
fun PrivacyConsentScreen(onAgree: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Tok.base) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Spacer(Modifier.height(48.dp))
                Text(
                    stringResource(Res.string.privacy_gate_title),
                    color = Tok.tx,
                    style = TypeRole.screenTitle.copy(fontSize = 30.sp, lineHeight = 34.sp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.privacy_gate_intro),
                    color = Tok.tx2,
                    style = TypeRole.preview.copy(fontSize = 15.sp, lineHeight = 22.sp),
                )
                Spacer(Modifier.height(24.dp))
                ConsentPoint(Res.string.privacy_gate_what_title, Res.string.privacy_gate_what_body)
                ConsentPoint(Res.string.privacy_gate_where_title, Res.string.privacy_gate_where_body)
                ConsentPoint(Res.string.privacy_gate_who_title, Res.string.privacy_gate_who_body)
                ConsentPoint(Res.string.privacy_gate_voice_title, Res.string.privacy_gate_voice_body)
                Text(
                    stringResource(Res.string.privacy_gate_demo),
                    color = Tok.muted,
                    style = TypeRole.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                )
                Spacer(Modifier.height(16.dp))
            }
            // Docked action bar: per the full-bleed convention the bottom-most surface consumes the
            // nav-bar band itself instead of the root padding the whole tree.
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
                EntryQuietAction(stringResource(Res.string.privacy_gate_policy)) { openWebUrl(PRIVACY_POLICY_URL) }
                Spacer(Modifier.height(10.dp))
                EntryPrimaryButton(stringResource(Res.string.privacy_gate_agree), onClick = onAgree)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ConsentPoint(title: StringResource, body: StringResource) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(title),
                color = Tok.tx,
                style = TypeRole.rowTitle.copy(fontSize = 15.sp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(body),
                color = Tok.tx2,
                style = TypeRole.preview.copy(fontSize = 13.5.sp, lineHeight = 20.sp),
            )
        }
    }
}
