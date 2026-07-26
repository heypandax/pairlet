package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.SUPPORT_CHAT_URL
import dev.ccpocket.app.USER_MANUAL_URL
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.help_action_open_changes
import dev.ccpocket.app.resources.help_all_guides
import dev.ccpocket.app.resources.help_ask_support
import dev.ccpocket.app.resources.help_ask_support_sub
import dev.ccpocket.app.resources.help_content_note
import dev.ccpocket.app.resources.help_direct_unavailable
import dev.ccpocket.app.resources.help_location_changes
import dev.ccpocket.app.resources.help_read_guide
import dev.ccpocket.app.resources.help_section_tasks
import dev.ccpocket.app.resources.help_source
import dev.ccpocket.app.resources.help_task_agent_step_1
import dev.ccpocket.app.resources.help_task_agent_step_2
import dev.ccpocket.app.resources.help_task_agent_step_3
import dev.ccpocket.app.resources.help_task_agent_sub
import dev.ccpocket.app.resources.help_task_agent_title
import dev.ccpocket.app.resources.help_task_approval_step_1
import dev.ccpocket.app.resources.help_task_approval_step_2
import dev.ccpocket.app.resources.help_task_approval_step_3
import dev.ccpocket.app.resources.help_task_approval_sub
import dev.ccpocket.app.resources.help_task_approval_title
import dev.ccpocket.app.resources.help_task_changes_step_1
import dev.ccpocket.app.resources.help_task_changes_step_2
import dev.ccpocket.app.resources.help_task_changes_step_3
import dev.ccpocket.app.resources.help_task_changes_sub
import dev.ccpocket.app.resources.help_task_changes_title
import dev.ccpocket.app.resources.help_task_continue_step_1
import dev.ccpocket.app.resources.help_task_continue_step_2
import dev.ccpocket.app.resources.help_task_continue_step_3
import dev.ccpocket.app.resources.help_task_continue_sub
import dev.ccpocket.app.resources.help_task_continue_title
import dev.ccpocket.app.resources.help_task_schedule_step_1
import dev.ccpocket.app.resources.help_task_schedule_step_2
import dev.ccpocket.app.resources.help_task_schedule_step_3
import dev.ccpocket.app.resources.help_task_schedule_step_4
import dev.ccpocket.app.resources.help_task_schedule_sub
import dev.ccpocket.app.resources.help_task_schedule_title
import dev.ccpocket.app.resources.support_title
import dev.ccpocket.app.telemetry.TelEvent
import dev.ccpocket.app.telemetry.TelKey
import dev.ccpocket.app.telemetry.Telemetry
import dev.ccpocket.app.theme.Tok
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal const val HELP_CONTENT_VERIFIED_AT = "2026-07-25"

internal enum class HelpEntryPoint(val value: String) {
    PROJECTS("projects"),
    SESSIONS("sessions"),
    CHAT("chat"),
    SETTINGS("settings"),
}

internal enum class HelpTaskId(val value: String, val query: String) {
    CHANGES("changed_files", "changed%20files"),
    CONTINUE("continue_session", "take%20over%20terminal"),
    APPROVALS("approvals", "approve"),
    SCHEDULE("schedule", "schedule%20prompt"),
    AGENT_MODEL("agent_model", "choose%20agent%20model"),
}

private data class HelpTaskSpec(
    val id: HelpTaskId,
    val title: StringResource,
    val summary: StringResource,
    val steps: List<StringResource>,
    val icon: ImageVector,
)

private val HELP_TASKS = listOf(
    HelpTaskSpec(
        HelpTaskId.CHANGES,
        Res.string.help_task_changes_title,
        Res.string.help_task_changes_sub,
        listOf(
            Res.string.help_task_changes_step_1,
            Res.string.help_task_changes_step_2,
            Res.string.help_task_changes_step_3,
        ),
        Icons.Rounded.Visibility,
    ),
    HelpTaskSpec(
        HelpTaskId.CONTINUE,
        Res.string.help_task_continue_title,
        Res.string.help_task_continue_sub,
        listOf(
            Res.string.help_task_continue_step_1,
            Res.string.help_task_continue_step_2,
            Res.string.help_task_continue_step_3,
        ),
        Icons.Rounded.Terminal,
    ),
    HelpTaskSpec(
        HelpTaskId.APPROVALS,
        Res.string.help_task_approval_title,
        Res.string.help_task_approval_sub,
        listOf(
            Res.string.help_task_approval_step_1,
            Res.string.help_task_approval_step_2,
            Res.string.help_task_approval_step_3,
        ),
        Icons.Outlined.Shield,
    ),
    HelpTaskSpec(
        HelpTaskId.SCHEDULE,
        Res.string.help_task_schedule_title,
        Res.string.help_task_schedule_sub,
        listOf(
            Res.string.help_task_schedule_step_1,
            Res.string.help_task_schedule_step_2,
            Res.string.help_task_schedule_step_3,
            Res.string.help_task_schedule_step_4,
        ),
        Icons.Rounded.Schedule,
    ),
    HelpTaskSpec(
        HelpTaskId.AGENT_MODEL,
        Res.string.help_task_agent_title,
        Res.string.help_task_agent_sub,
        listOf(
            Res.string.help_task_agent_step_1,
            Res.string.help_task_agent_step_2,
            Res.string.help_task_agent_step_3,
        ),
        Icons.Rounded.AccountTree,
    ),
)

internal fun helpGuideUrl(task: HelpTaskId): String = "${USER_MANUAL_URL}?q=${task.query}"

@Composable
internal fun HelpCenterScreen(
    entryPoint: HelpEntryPoint,
    onBack: () -> Unit,
    onOpenChanges: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf<HelpTaskId?>(null) }
    dev.ccpocket.app.SystemBackHandler(enabled = true) { onBack() }
    LaunchedEffect(Unit) {
        Telemetry.track(TelEvent.HelpOpened, mapOf(TelKey.EntryPoint to entryPoint.value))
    }

    Column(Modifier.fillMaxSize().background(Tok.base)) {
        Row(
            Modifier.fillMaxWidth().background(Tok.surface).padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onBack) { Text("←", color = Tok.tx2, fontSize = 18.sp) }
            Text(
                stringResource(Res.string.support_title),
                color = Tok.tx,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SmartSupportCard(entryPoint)
            }
            item {
                HelpSectionLabel(stringResource(Res.string.help_section_tasks))
            }
            items(HELP_TASKS, key = { it.id.value }) { task ->
                HelpTaskCard(
                    task = task,
                    expanded = expanded == task.id,
                    onToggle = {
                        val opening = expanded != task.id
                        expanded = if (opening) task.id else null
                        if (opening) {
                            Telemetry.track(
                                TelEvent.HelpTaskOpened,
                                mapOf(TelKey.HelpTask to task.id.value, TelKey.EntryPoint to entryPoint.value),
                            )
                        }
                    },
                    onOpenChanges = onOpenChanges.takeIf { task.id == HelpTaskId.CHANGES },
                    entryPoint = entryPoint,
                )
            }
            item {
                HelpAction(
                    label = stringResource(Res.string.help_all_guides),
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    primary = false,
                ) {
                    Telemetry.track(TelEvent.HelpGuideOpened, mapOf(TelKey.EntryPoint to entryPoint.value))
                    openWebUrl(USER_MANUAL_URL)
                }
            }
            item {
                Text(
                    stringResource(Res.string.help_content_note, HELP_CONTENT_VERIFIED_AT),
                    color = Tok.muted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HelpSectionLabel(text: String) {
    Text(
        text,
        color = Tok.muted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 1.dp),
    )
}

@Composable
private fun SmartSupportCard(entryPoint: HelpEntryPoint) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Tok.accent.copy(alpha = 0.12f))
            .border(1.dp, Tok.accent.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
            .clickable {
                Telemetry.track(TelEvent.HelpSupportOpened, mapOf(TelKey.EntryPoint to entryPoint.value))
                openWebUrl(SUPPORT_CHAT_URL)
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                .background(Tok.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.SmartToy, null, tint = Tok.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text(
                stringResource(Res.string.help_ask_support),
                color = Tok.tx,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.help_ask_support_sub),
                color = Tok.tx2,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = Tok.accent,
            modifier = Modifier.padding(start = 8.dp).size(18.dp),
        )
    }
}

@Composable
private fun HelpTaskCard(
    task: HelpTaskSpec,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenChanges: (() -> Unit)?,
    entryPoint: HelpEntryPoint,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(Tok.surface)
            .border(1.dp, if (expanded) Tok.accent.copy(alpha = 0.42f) else Tok.hair, RoundedCornerShape(13.dp))
            .clickable(onClick = onToggle)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Tok.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(task.icon, null, tint = if (expanded) Tok.accent else Tok.tx2, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.padding(start = 11.dp).weight(1f)) {
                Text(
                    stringResource(task.title),
                    color = Tok.tx,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(task.summary),
                    color = Tok.tx2,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                tint = Tok.muted,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tok.hair))
            Column(
                Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                task.steps.forEachIndexed { index, step ->
                    HelpStep(index + 1, stringResource(step))
                }
                if (task.id == HelpTaskId.CHANGES) {
                    LocationHint()
                    if (onOpenChanges == null) {
                        Text(
                            stringResource(Res.string.help_direct_unavailable),
                            color = Tok.muted,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOpenChanges != null) {
                        Box(Modifier.weight(1f)) {
                            HelpAction(
                                label = stringResource(Res.string.help_action_open_changes),
                                icon = Icons.Rounded.Visibility,
                                primary = true,
                            ) {
                                Telemetry.track(
                                    TelEvent.HelpDirectAction,
                                    mapOf(
                                        TelKey.HelpTask to HelpTaskId.CHANGES.value,
                                        TelKey.EntryPoint to entryPoint.value,
                                    ),
                                )
                                onOpenChanges()
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        HelpAction(
                            label = stringResource(Res.string.help_read_guide),
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            primary = false,
                        ) {
                            Telemetry.track(
                                TelEvent.HelpGuideOpened,
                                mapOf(TelKey.HelpTask to task.id.value, TelKey.EntryPoint to entryPoint.value),
                            )
                            openWebUrl(helpGuideUrl(task.id))
                        }
                    }
                }
                Text(
                    stringResource(Res.string.help_source, HELP_CONTENT_VERIFIED_AT),
                    color = Tok.muted,
                    fontSize = 10.5.sp,
                )
            }
        }
    }
}

@Composable
private fun HelpStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(Tok.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = Tok.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text,
            color = Tok.tx2,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
    }
}

@Composable
private fun LocationHint() {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tok.raised)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Visibility, null, tint = Tok.accent, modifier = Modifier.size(15.dp))
        Text(
            stringResource(Res.string.help_location_changes),
            color = Tok.tx2,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
    }
}

@Composable
private fun HelpAction(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit) {
    val bg = if (primary) Tok.accent else Tok.raised
    val fg = if (primary) Color.White else Tok.tx2
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, Tok.hair, RoundedCornerShape(10.dp)))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(
            label,
            color = fg,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}
