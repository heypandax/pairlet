package dev.ccpocket.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cancel
import dev.ccpocket.app.resources.dir_picker_empty
import dev.ccpocket.app.resources.dir_picker_error
import dev.ccpocket.app.resources.dir_picker_home
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.app.ui.browseCrumbsOf
import dev.ccpocket.app.ui.browseFailed
import dev.ccpocket.app.ui.browseHomeAbs
import dev.ccpocket.app.ui.browseJoin
import dev.ccpocket.app.ui.browseParentOf
import dev.ccpocket.app.ui.browseProjectAt
import dev.ccpocket.app.ui.browseRows
import dev.ccpocket.app.ui.browseWorkdirOf
import dev.ccpocket.app.ui.isGuestDirView
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop remote-directory picker (issues #218/#214). When the ACTIVE daemon is another machine, a
 * native chooser browses the wrong filesystem — so this drives the daemon-side #152 browse wire instead
 * (the same anchored [dev.ccpocket.protocol.ListPathEntries] the @-file completer and mobile's
 * DirectoryPickerSheet use). Pure browse logic (rows/crumbs/joins) is REUSED from ui/DirectoryPicker.kt so
 * the two shells can't drift on path composition (Windows "\" hosts included). [onPick] receives the raw
 * "~/…" / native-root form the daemon expands + canonicalizes; it never touches the local disk.
 */
@Composable
internal fun RemoteDirPickerCard(
    model: DesktopModel,
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    // #176: the browse ANCHOR — "~" (the daemon home) by default, or a reported fs root once switched.
    var anchor by remember { mutableStateOf(PocketRepository.BROWSE_HOME) }
    var subPath by remember { mutableStateOf("") }
    LaunchedEffect(anchor, subPath) { model.requestBrowse(anchor, subPath) }

    val dirs = model.browseDirectories
    val homeAbs = remember(dirs) { browseHomeAbs(dirs) }
    val listing = model.browseListing
    val rows = browseRows(listing, anchor, subPath)
    val failed = browseFailed(listing, anchor, subPath)
    val crumbs = browseCrumbsOf(anchor, subPath)
    // owner-only + belt-and-suspenders: a guest never receives roots (daemon-gated), so keep the switcher off
    val roots = if (isGuestDirView(dirs)) emptyList() else model.browseRoots

    Column(
        Modifier.width(440.dp).clip(RoundedCornerShape(14.dp)).background(Tok.raised)
            .border(1.dp, Tok.hair, RoundedCornerShape(14.dp)).padding(16.dp),
    ) {
        Text(title, color = Tok.tx, fontFamily = Dk.ui, fontSize = 14.sp, fontWeight = FontWeight.Bold)

        // ── breadcrumb + #176 root switcher ──
        RemoteCrumbBar(
            crumbs = crumbs, roots = roots, anchor = anchor,
            onUp = {
                if (subPath.isEmpty() && anchor != PocketRepository.BROWSE_HOME) anchor = PocketRepository.BROWSE_HOME
                else subPath = browseParentOf(subPath)
            },
            onSegment = { i -> subPath = if (i == 0) "" else crumbs.drop(1).take(i).joinToString("/") },
            onSwitchRoot = { chosen -> anchor = chosen; subPath = "" },
        )

        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 320.dp)) {
            LazyColumn(Modifier.fillMaxWidth()) {
                when {
                    failed -> item {
                        Text(
                            stringResource(Res.string.dir_picker_error), color = Tok.warn, fontFamily = Dk.ui,
                            fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp),
                        )
                    }
                    rows == null -> items(3) { SkeletonDirRow() }
                    rows.isEmpty() -> item {
                        Text(
                            stringResource(Res.string.dir_picker_empty), color = Tok.muted, fontFamily = Dk.ui,
                            fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp),
                        )
                    }
                    else -> items(rows, key = { "d:" + it.name }) { e ->
                        val child = browseJoin(subPath, e.name)
                        RemoteDirRow(
                            name = e.name,
                            isProject = browseProjectAt(dirs, homeAbs, anchor, child) != null,
                        ) { subPath = child }
                    }
                }
            }
        }

        // the folder the confirm button will use, in the daemon's own form
        Text(
            browseWorkdirOf(anchor, subPath), color = Tok.muted, fontFamily = Dk.mono, fontSize = 10.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                confirmLabel, color = Tok.base, fontFamily = Dk.ui, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Tok.accent)
                    .clickable { onPick(browseWorkdirOf(anchor, subPath)) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                stringResource(Res.string.cancel), color = Tok.muted, fontFamily = Dk.ui, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

/** Center the [RemoteDirPickerCard] over a full-window scrim via a [Popup] — so it floats above whatever
 *  hosts it (the Settings modal's Bridges pane included). Used where there is no top-level Overlay to ride. */
@Composable
internal fun RemoteDirPickerPopup(
    model: DesktopModel,
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(Dk.backdrop.copy(alpha = 0.5f)).noRippleClickLocal(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.noRippleClickLocal {}) {
                RemoteDirPickerCard(model, title, confirmLabel, onDismiss, onPick)
            }
        }
    }
}

@Composable
private fun RemoteCrumbBar(
    crumbs: List<String>,
    roots: List<String>,
    anchor: String,
    onUp: () -> Unit,
    onSegment: (Int) -> Unit,
    onSwitchRoot: (String) -> Unit,
) {
    var expanded by remember(roots) { mutableStateOf(false) }
    val switchable = roots.isNotEmpty()
    Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("‹", color = Tok.tx2, fontFamily = Dk.ui, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onUp).padding(end = 2.dp))
            crumbs.forEachIndexed { i, s ->
                val last = i == crumbs.lastIndex
                if (i == 0 && switchable) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(horizontal = 3.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s, color = if (last) Tok.tx else Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal)
                        Text(if (expanded) "▴" else "▾", color = Tok.accent, fontFamily = Dk.ui, fontSize = 10.sp, modifier = Modifier.padding(start = 3.dp))
                    }
                } else {
                    Text(
                        s, color = if (last) Tok.tx else Tok.tx2, fontFamily = Dk.mono, fontSize = 11.sp,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable(enabled = !last) { onSegment(i) },
                    )
                }
                if (!last) Text("›", color = Tok.muted, fontFamily = Dk.mono, fontSize = 11.sp)
            }
        }
        if (switchable && expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Tok.surface).padding(vertical = 4.dp)) {
                RemoteRootRow(stringResource(Res.string.dir_picker_home), anchor == PocketRepository.BROWSE_HOME) { expanded = false; onSwitchRoot(PocketRepository.BROWSE_HOME) }
                roots.forEach { r -> RemoteRootRow(r, anchor == r) { expanded = false; onSwitchRoot(r) } }
            }
        }
    }
}

@Composable
private fun RemoteRootRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = if (selected) Tok.accent else Tok.tx2, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            label, color = if (selected) Tok.accent else Tok.tx, fontFamily = Dk.mono, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", color = Tok.accent, fontFamily = Dk.ui, fontSize = 11.sp)
    }
}

@Composable
private fun RemoteDirRow(name: String, isProject: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(name, color = Tok.tx, fontFamily = Dk.mono, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (isProject) { Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(Tok.accent)); Spacer(Modifier.width(8.dp)) }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SkeletonDirRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(Tok.surface))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.height(12.dp).fillMaxWidth(0.45f).clip(RoundedCornerShape(4.dp)).background(Tok.surface))
    }
}

@Composable
private fun Modifier.noRippleClickLocal(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    return clickable(interactionSource = src, indication = null, onClick = onClick)
}
