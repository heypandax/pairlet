package dev.ccpocket.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.ChatPresentation
import dev.ccpocket.app.data.ChatRow
import dev.ccpocket.app.data.ChatRowIdentity
import dev.ccpocket.app.data.ProcessSummary
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.tool_process_collapse
import dev.ccpocket.app.resources.tool_process_expand
import dev.ccpocket.app.resources.tool_process_group
import dev.ccpocket.app.resources.tool_process_images
import dev.ccpocket.app.resources.tool_process_images_truncated
import dev.ccpocket.app.resources.tool_process_thoughts
import dev.ccpocket.app.resources.tool_process_tools
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.theme.tightCenter
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Issue #380 — the Compose half of "collapse tool process", shared by the phone/iPad list and the desktop
 * pane. The algorithm lives in [ChatPresentation]; this file only holds per-list state and the fold row.
 */

/** Test tag on every fold row. */
const val TOOL_PROCESS_GROUP_TAG = "tool-process-group"

/** Test tag on the chat stream's LazyColumn (phone and desktop). */
const val CHAT_STREAM_TAG = "chat-stream"

/**
 * One chat list's projection state: row identity for its transcript plus which folds THIS list has opened.
 * Each list (a phone screen, a desktop pane, a split column, a second window) owns one, so expanding a fold
 * in one never opens it in another. Folding itself is unconditional for finished steps.
 *
 * Expansion is remembered by MEMBER identity, not by the fold's key: a fold whose key changes because it
 * gained a new first member (older tools paged in ahead of it, an earlier parallel call finishing after
 * the later ones) stays open, and closing it closes every member it now holds. Identities are unique per
 * process and never reused, so a replaced history cannot inherit anything.
 *
 * [source] and [collapse] must read snapshot state only: they are captured once, and [presentation]
 * re-derives whenever what they read changes — never per recomposition.
 */
@Stable
class ChatPresentationState(
    private val source: () -> List<ChatItem>,
    private val collapse: () -> Boolean,
) {
    private val identity = ChatRowIdentity()
    private val expandedMembers = mutableStateMapOf<Long, Unit>()

    val presentation: ChatPresentation by derivedStateOf {
        val items = source().toList()
        val ids = identity.assign(items)
        ChatPresentation.build(items, ids, identity.generation, collapse(), expandedMembers = expandedMembers.keys.toSet())
    }

    /** Open / close one fold. Remembered members that left the transcript are dropped on the way. */
    fun toggle(groupKey: String) {
        val p = presentation
        val group = p.rows.firstOrNull { it is ChatRow.ProcessGroup && it.groupKey == groupKey } as? ChatRow.ProcessGroup ?: return
        expandedMembers.keys.filter { p.rowOfKey(it) < 0 }.forEach { expandedMembers.remove(it) }
        val members = group.sourceIndices.map(p::sourceKey)
        if (group.expanded) members.forEach { expandedMembers.remove(it) } else members.forEach { expandedMembers[it] = Unit }
    }

    /** Is [groupKey]'s fold the end of the stream — its header while closed, its last member while open? */
    fun isLastRow(groupKey: String): Boolean = when (val last = presentation.rows.lastOrNull()) {
        is ChatRow.ProcessGroup -> last.groupKey == groupKey
        is ChatRow.Original -> last.groupKey == groupKey
        null -> false
    }
}

@Composable
fun rememberChatPresentationState(
    key: Any?,
    source: () -> List<ChatItem>,
    collapse: () -> Boolean,
): ChatPresentationState = remember(key) { ChatPresentationState(source, collapse) }

/**
 * Keep the reader's place when the projection changes UNDER them — the switch flipped, a fold opened or
 * closed, a finishing tool merged into a fold holding the row being read.
 *
 * Runs in the apply phase (before the next layout), maps the first visible row of the PREVIOUS projection
 * to its source record + pixel offset, and asks the list to put that record back where it was. Skipped while
 * [followingTail] (the list follows the stream to its end instead), on a new history generation, and when the
 * head of the transcript changed — a page of older history lands through the caller's own source-mapped
 * seam logic. [LazyListState.requestScrollToItem] never forces a remeasure, so this is safe in any phase.
 */
@Composable
fun KeepChatReadingPosition(
    listState: LazyListState,
    presentation: ChatPresentation,
    leadingRows: Int,
    followingTail: Boolean,
) {
    val last = remember { LastLaidOut() }
    SideEffect {
        val prev = last.presentation
        val prevLeading = last.leadingRows
        last.presentation = presentation
        last.leadingRows = leadingRows
        if (prev == null || prev === presentation || followingTail) return@SideEffect
        if (prev.generation != presentation.generation) return@SideEffect
        if (prev.sourceSize == 0 || presentation.sourceSize == 0) return@SideEffect
        if (prev.sourceKey(0) != presentation.sourceKey(0)) return@SideEffect
        val row = listState.firstVisibleItemIndex - prevLeading
        val anchor = prev.anchorAt(row, listState.firstVisibleItemScrollOffset) ?: return@SideEffect
        val target = presentation.rowFor(anchor)
        if (target < 0) return@SideEffect
        if (target == row && leadingRows == prevLeading && prev.rows[row].key == presentation.rows[target].key) return@SideEffect
        listState.requestScrollToItem(target + leadingRows, anchor.offset)
    }
}

/** Same, reading [state]'s projection inside its OWN recompose scope, so a streamed chunk re-runs only this
 *  leaf and not the whole chat screen that hosts it. */
@Composable
fun KeepChatReadingPosition(
    listState: LazyListState,
    state: ChatPresentationState,
    leadingRows: Int,
    followingTail: Boolean,
) = KeepChatReadingPosition(listState, state.presentation, leadingRows, followingTail)

private class LastLaidOut {
    var presentation: ChatPresentation? = null
    var leadingRows: Int = 0
}

/** "Process · 8 tools · 1 thought · 3 images" — counts only (plural-correct per locale), never a summary. */
@Composable
fun processSummaryLabel(summary: ProcessSummary): String = buildList {
    add(stringResource(Res.string.tool_process_group))
    if (summary.tools > 0) add(pluralStringResource(Res.plurals.tool_process_tools, summary.tools, summary.tools))
    if (summary.thoughts > 0) add(pluralStringResource(Res.plurals.tool_process_thoughts, summary.thoughts, summary.thoughts))
    if (summary.images > 0) add(pluralStringResource(Res.plurals.tool_process_images, summary.images, summary.images))
    if (summary.imagesTruncated) add(stringResource(Res.string.tool_process_images_truncated))
}.joinToString(" · ")

/**
 * The fold's own row. Expanded, the members render beneath it as their ordinary rows (with their existing
 * tool/thinking renderers, image viewers included); this row stays as the way to close them again.
 */
@Composable
fun ProcessGroupRow(
    summary: ProcessSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
    fontSize: Float = 12.5f,
) {
    val action = stringResource(if (expanded) Res.string.tool_process_collapse else Res.string.tool_process_expand)
    val size = fontSize.sp
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().testTag(TOOL_PROCESS_GROUP_TAG)
            .clip(shape).background(Tok.surface).border(1.dp, Tok.hair, shape)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = action; onClick(label = action) { onToggle(); true } }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (expanded) "▾" else "▸", color = Tok.muted, fontSize = size, fontFamily = fontFamily,
            style = tightCenter(size),
        )
        Text(
            processSummaryLabel(summary), color = Tok.tx2, fontSize = size, fontFamily = fontFamily,
            fontWeight = FontWeight.Medium, style = tightCenter(size),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(action, color = Tok.muted, fontSize = (fontSize - 1.5f).sp, fontFamily = fontFamily, style = tightCenter((fontSize - 1.5f).sp))
    }
}
