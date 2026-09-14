package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PinIssueEvent
import dev.ccpocket.app.data.PinIssueKind
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.pin_issue_alias_limited
import dev.ccpocket.app.resources.pin_issue_capacity
import dev.ccpocket.app.resources.pin_issue_dismiss
import dev.ccpocket.app.resources.pin_issue_legacy_not_migrated
import dev.ccpocket.app.resources.pin_issue_local_reset
import dev.ccpocket.app.resources.pin_issue_migration_uncertain
import dev.ccpocket.app.resources.pin_issue_outbox_full
import dev.ccpocket.app.resources.pin_issue_pairing_changed
import dev.ccpocket.app.resources.pin_issue_refused
import dev.ccpocket.app.resources.pin_issue_retained_locally
import dev.ccpocket.app.resources.pin_issue_retry
import dev.ccpocket.app.resources.pin_issue_storage_failed
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import org.jetbrains.compose.resources.stringResource

/** Phone/tablet root: the pin notice shows only in the foreground, never under App Lock's gate or privacy cover,
 *  and never over a pending approval. */
fun pinNoticeAllowedOnMobile(foreground: Boolean, locked: Boolean, covered: Boolean, approvalPending: Boolean): Boolean =
    foreground && !locked && !covered && !approvalPending

/** Desktop root: only in a visible, un-minimized main window with no modal overlay open. */
fun pinNoticeAllowedOnDesktop(windowVisible: Boolean, minimized: Boolean, overlayOpen: Boolean): Boolean =
    windowVisible && !minimized && !overlayOpen

/**
 * The one pin-sync notice for [repo]'s computer (issue #362): its current problem, unless dismissed, while the root
 * [allowed] it. Non-modal; it does not interrupt pairing, sessions or navigation, and it never names an error text
 * or a path. Dismissing hides the notice only; retry asks the computer again.
 */
@Composable
fun ProjectPinIssueNotice(repo: PocketRepository, allowed: Boolean, modifier: Modifier = Modifier) {
    val event = repo.projectPinIssueNotice.value
    if (!allowed || event == null) return
    ProjectPinIssueBar(
        event,
        onDismiss = { repo.dismissProjectPinIssue(event) },
        onRetry = if (event.retryable) repo::retryProjectPins else null,
        modifier = modifier,
    )
}

@Composable
internal fun ProjectPinIssueBar(event: PinIssueEvent, onDismiss: () -> Unit, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val label = tightCenter(13.sp) // text sits beside the action buttons: one line box on every platform
    Snackbar(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(PIN_ISSUE_NOTICE_TAG),
        containerColor = Tok.surface,
        contentColor = Tok.tx,
        action = onRetry?.let { retry ->
            { TextButton(onClick = retry) { Text(stringResource(Res.string.pin_issue_retry), color = Tok.accent, fontSize = 13.sp, style = label) } }
        },
        dismissAction = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.pin_issue_dismiss), color = Tok.tx2, fontSize = 13.sp, style = label) }
        },
    ) {
        Text(message(event), color = Tok.tx, fontSize = 13.sp, style = label)
    }
}

@Composable
private fun message(event: PinIssueEvent): String = when (event.kind) {
    PinIssueKind.RETAINED_LOCALLY -> stringResource(Res.string.pin_issue_retained_locally)
    PinIssueKind.REFUSED -> stringResource(Res.string.pin_issue_refused)
    PinIssueKind.CAPACITY -> stringResource(Res.string.pin_issue_capacity)
    PinIssueKind.STORAGE_FAILED -> stringResource(Res.string.pin_issue_storage_failed)
    PinIssueKind.LOCAL_STATE_RESET -> stringResource(Res.string.pin_issue_local_reset)
    PinIssueKind.OUTBOX_FULL -> stringResource(Res.string.pin_issue_outbox_full)
    PinIssueKind.MIGRATION_UNCERTAIN -> stringResource(Res.string.pin_issue_migration_uncertain)
    PinIssueKind.LEGACY_NOT_MIGRATED -> stringResource(Res.string.pin_issue_legacy_not_migrated, event.count)
    PinIssueKind.ALIAS_LIMITED -> stringResource(Res.string.pin_issue_alias_limited)
    PinIssueKind.PAIRING_CHANGED -> stringResource(Res.string.pin_issue_pairing_changed)
}

const val PIN_ISSUE_NOTICE_TAG = "project_pin_issue_notice"
