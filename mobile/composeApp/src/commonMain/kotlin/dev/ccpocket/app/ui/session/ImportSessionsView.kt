package dev.ccpocket.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.resources.*
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import dev.ccpocket.app.ui.agentName
import dev.ccpocket.app.ui.relativeTime
import dev.ccpocket.app.ui.tilde
import dev.ccpocket.app.ui.session.ImportSessionsEvent as Ev
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Test tags — behaviour tests find the search field and rows by these, never by layout. */
object ImportSessionsTags {
    const val SEARCH = "managed_sessions_search"
    fun row(key: DiscoveredKey) = "managed_sessions_row_${key.agent.name}_${key.nativeId}"
}

/** Width at which a row puts its time in a trailing column instead of under the prompt. */
private val WIDE_ROW_MIN = 560.dp

/**
 * Controller-backed mount: phone full-screen route and desktop overlay both use this. The host owns the controller
 * (created with an explicit scope + gateway) and decides what "located in the managed list" means in [onImported].
 */
@Composable
fun ImportSessionsScreen(
    controller: ImportSessionsController,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    enable: ManagedEnableUi? = null,
    onEnable: (dev.ccpocket.protocol.AgentKind) -> Unit = {},
) {
    val state by controller.state.collectAsState()
    ImportSessionsView(state, controller::dispatch, modifier, onClose, enable, onEnable)
}

/**
 * Issue #360 stage 2: "Import from local history…". Stateless — every interaction is an [ImportSessionsEvent].
 * Importing only registers a reference: this view never sends a prompt and never takes a session over.
 */
@Composable
fun ImportSessionsView(
    state: ImportSessionsState,
    onEvent: (Ev) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    enable: ManagedEnableUi? = null,
    onEnable: (dev.ccpocket.protocol.AgentKind) -> Unit = {},
    /** The daemon's store is corrupt: nothing can be saved, so no import is offered. */
    readOnly: Boolean = false,
    /** Which agents are READY is not known yet: import stays locked until it is. */
    statusPending: Boolean = false,
    /** Asking which agents are READY failed: import stays locked; [onRetryStatus] asks again. */
    statusError: ManagedSessionsError? = null,
    onRetryStatus: () -> Unit = {},
) {
    // an agent still on the legacy list must be switched on first: importing into a list that is not the one shown
    // would look like nothing happened (the daemon rows keep being what the project displays). Until the daemon has
    // answered at all, every agent counts as possibly not switched on.
    val uninitialized = enable?.uninitialized?.contains(state.agent) == true
    val locked = statusPending || statusError != null || uninitialized
    BoxWithConstraints(modifier.fillMaxSize().background(Tok.base)) {
        val wide = maxWidth >= WIDE_ROW_MIN
        Column(Modifier.fillMaxSize()) {
            Header(state, onClose)
            if (enable != null && enable.uninitialized.isNotEmpty()) EnableNotice(enable, onEnable)
            if (statusError != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Tok.surface).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.managed_sessions_status_failed) + " · " + stringResource(statusError.messageRes()),
                        color = Tok.danger, fontSize = 12.sp, style = tightCenter(12.sp), modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    LinkButton(stringResource(Res.string.managed_sessions_retry), onRetryStatus)
                }
            } else if (statusPending) NoticeLine(stringResource(Res.string.managed_sessions_status_pending), Tok.muted)
            else if (readOnly) NoticeLine(stringResource(Res.string.managed_sessions_read_only), Tok.danger)
            else if (uninitialized) NoticeLine(stringResource(Res.string.managed_sessions_enable_required, agentName(state.agent)), Tok.warn)
            SearchField(state.queryInput) { onEvent(Ev.QueryTyped(it)) }
            if (state.agents.size > 1) AgentFilter(state) { onEvent(Ev.SelectAgent(it)) }
            if (state.list == ListPhase.Loaded && !state.complete) PartialNotice(state.diagnostic)
            Body(state, onEvent, wide, importAllowed = !locked && !readOnly)
        }
    }
}

@Composable
private fun NoticeLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = color, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(Tok.surface).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun Header(state: ImportSessionsState, onClose: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.managed_sessions_import_title), color = Tok.tx, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            state.scope?.let {
                Text(
                    tilde(it.workdir), color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (onClose != null) {
            Text(
                stringResource(Res.string.managed_sessions_close), color = Tok.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(13.sp),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
    Text(
        stringResource(Res.string.managed_sessions_import_hint), color = Tok.tx2, fontSize = 12.sp,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
    )
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val placeholder = stringResource(Res.string.managed_sessions_search_hint)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Tok.tx, fontSize = 14.sp),
        cursorBrush = SolidColor(Tok.accent),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).testTag(ImportSessionsTags.SEARCH),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.surface)
                    .border(1.dp, Tok.hair, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = Tok.muted, fontSize = 14.sp)
                inner()
            }
        },
    )
}

@Composable
private fun AgentFilter(state: ImportSessionsState, onSelect: (dev.ccpocket.protocol.AgentKind) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.agents.forEach { agent ->
            val selected = agent == state.agent
            val shape = RoundedCornerShape(999.dp)
            Text(
                agentName(agent),
                color = if (selected) Tok.base else Tok.tx2,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                style = tightCenter(12.sp),
                modifier = Modifier.clip(shape)
                    .background(if (selected) Tok.accent else Tok.surface)
                    .border(1.dp, if (selected) Tok.accent else Tok.hair, shape)
                    .clickable { onSelect(agent) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun PartialNotice(diagnostic: DiscoverDiagnostic?) {
    Text(
        stringResource(diagnostic.messageRes()), color = Tok.warn, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(Tok.surface).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun Body(state: ImportSessionsState, onEvent: (Ev) -> Unit, wide: Boolean, importAllowed: Boolean = true) {
    when (val phase = state.list) {
        ListPhase.Idle -> Unit
        ListPhase.Loading -> CenterMessage(stringResource(Res.string.managed_sessions_loading), null)
        is ListPhase.Failed -> CenterMessage(
            stringResource(Res.string.managed_sessions_failed),
            stringResource(phase.error.messageRes()),
            action = if (phase.error == ManagedSessionsError.UNSUPPORTED) null else stringResource(Res.string.managed_sessions_retry),
            onAction = { onEvent(Ev.Retry) },
        )
        ListPhase.Loaded -> if (state.items.isEmpty()) {
            CenterMessage(
                if (state.appliedQuery.isEmpty()) stringResource(Res.string.managed_sessions_empty)
                else stringResource(Res.string.managed_sessions_empty_query, state.appliedQuery),
                null,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { "${it.agent.name}/${it.nativeId}" }) { row ->
                    ImportRow(row, state.isImported(row), state.imports[row.key], wide, importAllowed) { onEvent(Ev.ImportClicked(row.key)) }
                }
                item(key = "footer") { Footer(state, onEvent) }
            }
        }
    }
}

@Composable
private fun Footer(state: ImportSessionsState, onEvent: (Ev) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        when {
            state.loadingMore -> Text(stringResource(Res.string.managed_sessions_loading), color = Tok.muted, fontSize = 12.sp)
            state.loadMoreError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.managed_sessions_load_more_failed), color = Tok.danger, fontSize = 12.sp, style = tightCenter(12.sp))
                Spacer(Modifier.width(8.dp))
                LinkButton(stringResource(Res.string.managed_sessions_retry)) { onEvent(Ev.Retry) }
            }
            state.canLoadMore -> LinkButton(stringResource(Res.string.managed_sessions_load_more)) { onEvent(Ev.LoadMore) }
        }
    }
}

@Composable
private fun ImportRow(row: DiscoveredSession, imported: Boolean, phase: ImportPhase?, wide: Boolean, importAllowed: Boolean, onImport: () -> Unit) {
    val preview = row.firstPrompt?.trim()?.takeIf { it.isNotEmpty() && it != row.title.trim() }
    val time = relativeTime(row.lastModified)
    Row(
        Modifier.fillMaxWidth().testTag(ImportSessionsTags.row(row.key))
            .clip(RoundedCornerShape(12.dp)).background(Tok.surface)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, color = Tok.tx, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = if (wide) 1 else 2, overflow = TextOverflow.Ellipsis)
            preview?.let {
                Text(it, color = Tok.tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (!wide) Text(time, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            if (phase is ImportPhase.Failed) {
                // an unanswered import may have landed: say "unconfirmed" (the list is being re-read), never "failed"
                val unconfirmed = phase.error == ManagedSessionsError.UNCONFIRMED
                Text(
                    if (unconfirmed) stringResource(phase.error.messageRes())
                    else stringResource(Res.string.managed_sessions_import_failed) + " · " + stringResource(phase.error.messageRes()),
                    color = if (unconfirmed) Tok.warn else Tok.danger, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (wide) {
            Spacer(Modifier.width(12.dp))
            Text(time, color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, style = tightCenter(11.sp), modifier = Modifier.widthIn(min = 64.dp))
        }
        Spacer(Modifier.width(10.dp))
        when {
            imported -> Pill(stringResource(Res.string.managed_sessions_imported), Tok.ok)
            phase == ImportPhase.InFlight -> Pill(stringResource(Res.string.managed_sessions_importing), Tok.muted)
            phase == ImportPhase.Checking -> Pill(stringResource(Res.string.managed_sessions_checking), Tok.muted)
            // not yet importable (agent still on the legacy list, or a read-only store): the notice above says why
            !importAllowed -> Unit
            else -> LinkButton(
                stringResource(if (phase is ImportPhase.Failed) Res.string.managed_sessions_retry else Res.string.managed_sessions_import),
                onClick = onImport,
            )
        }
    }
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(12.sp),
        modifier = Modifier.clip(shape).border(1.dp, Tok.hair, shape).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text, color = Tok.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = tightCenter(12.sp),
        modifier = Modifier.clip(shape).border(1.dp, Tok.accent, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun CenterMessage(title: String, detail: String?, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
        Text(title, color = Tok.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, color = Tok.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp)) }
        action?.let { Box(Modifier.padding(top = 12.dp)) { LinkButton(it, onAction) } }
    }
}

internal fun ManagedSessionsError.messageRes(): StringResource = when (this) {
    ManagedSessionsError.UNSUPPORTED -> Res.string.managed_sessions_error_unsupported
    ManagedSessionsError.DENIED -> Res.string.managed_sessions_error_denied
    ManagedSessionsError.INVALID_WORKDIR -> Res.string.managed_sessions_error_invalid_workdir
    ManagedSessionsError.NOT_FOUND -> Res.string.managed_sessions_error_not_found
    ManagedSessionsError.DISCONNECTED -> Res.string.managed_sessions_error_disconnected
    ManagedSessionsError.TIMEOUT -> Res.string.managed_sessions_error_timeout
    ManagedSessionsError.INTERNAL -> Res.string.managed_sessions_error_internal
    ManagedSessionsError.SCAN_INCOMPLETE -> Res.string.managed_sessions_error_scan_incomplete
    ManagedSessionsError.CAPACITY -> Res.string.managed_sessions_error_capacity
    ManagedSessionsError.STORE_UNAVAILABLE -> Res.string.managed_sessions_error_store
    ManagedSessionsError.STORE_CORRUPT -> Res.string.managed_sessions_error_store_corrupt
    ManagedSessionsError.CURSOR_EXPIRED -> Res.string.managed_sessions_error_cursor_expired
    ManagedSessionsError.UNCONFIRMED -> Res.string.managed_sessions_error_unconfirmed
}

/** Test tag of the first-use notice card. */
const val MANAGED_ENABLE_NOTICE_TAG = "managed_sessions_enable_notice"

/**
 * The one-time switch (issue #360): says what stays (current sessions), what changes (outside sessions stop joining),
 * and the older-app limit, then offers one button per agent still on the legacy list.
 */
@Composable
private fun EnableNotice(enable: ManagedEnableUi, onEnable: (dev.ccpocket.protocol.AgentKind) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).testTag(MANAGED_ENABLE_NOTICE_TAG)
            .clip(RoundedCornerShape(12.dp)).background(Tok.surface).border(1.dp, Tok.hair, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(stringResource(Res.string.managed_sessions_enable_title), color = Tok.tx, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(Res.string.managed_sessions_enable_body), color = Tok.tx2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        enable.error?.let { e ->
            val reason = stringResource(e.messageRes())
            val detail = enable.diagnostic?.let { " " + stringResource(it.messageRes()) }.orEmpty()
            Text(reason + detail, color = Tok.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            enable.uninitialized.forEach { agent ->
                if (enable.busy == agent) Pill(stringResource(Res.string.managed_sessions_enabling), Tok.muted)
                else LinkButton(stringResource(Res.string.managed_sessions_enable_for, agentName(agent))) { if (enable.busy == null) onEnable(agent) }
            }
        }
    }
}

private fun DiscoverDiagnostic?.messageRes(): StringResource = when (this) {
    DiscoverDiagnostic.SCAN_ERROR -> Res.string.managed_sessions_partial_scan_error
    DiscoverDiagnostic.TRUNCATED -> Res.string.managed_sessions_partial_truncated
    DiscoverDiagnostic.PERMISSION -> Res.string.managed_sessions_partial_permission
    DiscoverDiagnostic.UNKNOWN, null -> Res.string.managed_sessions_partial_unknown
}
