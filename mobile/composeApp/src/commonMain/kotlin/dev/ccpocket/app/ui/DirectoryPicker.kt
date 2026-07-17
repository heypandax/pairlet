package dev.ccpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.dir_picker_browse
import dev.ccpocket.app.resources.dir_picker_empty
import dev.ccpocket.app.resources.dir_picker_error
import dev.ccpocket.app.resources.dir_picker_recents
import dev.ccpocket.app.resources.dir_picker_sub
import dev.ccpocket.app.resources.dir_picker_title
import dev.ccpocket.app.resources.dir_picker_type_path
import dev.ccpocket.app.resources.dir_picker_use_here
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.DirectoryEntry
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PathEntry
import org.jetbrains.compose.resources.stringResource

// ── "Open a project folder" picker (issue #152) ────────────────────────────────────────────────────
// The positive-direction entry the project list lacked: browse the DAEMON machine's real filesystem
// (anchored at its home, "~") and start a brand-new session in ANY existing directory — no prior
// Claude/Codex history required. Built entirely on existing wire surfaces: listing rides the #75
// ListPathEntries frame with the literal "~" workdir (only the daemon knows the remote home; its
// resolve accepts '/' on a Windows host too), and opening rides the ordinary OpenSession(resumeId =
// null) → validateOrCreateWorkdir path the manual "+ path" sheet (#7) already uses. No new protocol,
// no new daemon read surface — an owner could already list any readable directory through #75.
// Guests never see this UI (owner-only entry, see isGuestDirView) and the daemon independently denies
// their "~" anchor (GuestGuard clamps ListPathEntries to the shared root).

/** The subpath segments are '/'-joined CLIENT-side keys; the daemon resolves them under its home. */
private const val SEP = '/'

/** Rows to render for the current [subPath], or null while loading (no listing yet, or the listing is
 *  for another subPath — replies can arrive out of order when drilling fast). Directories only, dot
 *  folders hidden: this picker chooses a PROJECT dir, not a dotfile browser (the daemon's dirs-first
 *  sort means a truncated listing loses files, not folders). */
internal fun browseRows(listing: PathEntries?, subPath: String): List<PathEntry>? {
    if (listing == null || listing.subPath != subPath || listing.workdir != PocketRepository.BROWSE_HOME) return null
    return listing.entries.filter { it.isDir && !it.name.startsWith(".") }
}

/** True when the daemon answered "can't read that" for the CURRENT subPath (unreadable/escaped dir). */
internal fun browseFailed(listing: PathEntries?, subPath: String): Boolean =
    listing != null && listing.workdir == PocketRepository.BROWSE_HOME && listing.subPath == subPath && !listing.ok

internal fun browseJoin(subPath: String, name: String): String =
    if (subPath.isEmpty()) name else "$subPath$SEP$name"

/** One level up ("" at the root stays the root). */
internal fun browseParentOf(subPath: String): String = subPath.substringBeforeLast(SEP, "")

/** The OpenSession workdir for [subPath]: the raw "~" form the daemon expands (same contract as the
 *  manual path sheet — RequestRouter resolves it via validateOrCreateWorkdir and SessionLive answers
 *  with the real absolute path). */
internal fun browseWorkdirOf(subPath: String): String =
    if (subPath.isEmpty()) PocketRepository.BROWSE_HOME else PocketRepository.BROWSE_HOME + SEP + subPath

/** Breadcrumb labels for [subPath], home collapsed to "~" — mirrors crumbs()/crumbTargets for the
 *  project tree, but over the picker's relative key instead of an absolute path. */
internal fun browseCrumbsOf(subPath: String): List<String> =
    listOf(PocketRepository.BROWSE_HOME) + subPath.split(SEP).filter { it.isNotEmpty() }

/** The daemon host's home dir in NATIVE form, inferred from the project paths it already reported
 *  (same rule the tree root uses) — null when nothing is inferable (a fresh machine with no history:
 *  exactly the #152 case; badges and recents just stay off then). */
internal fun browseHomeAbs(dirs: List<DirectoryEntry>): String? =
    dirs.firstNotNullOfOrNull { homePrefix(it.path) }

/** [subPath] as a native absolute path under [homeAbs] (for matching against DirectoryEntry.path),
 *  or null when home is unknown. */
internal fun browseAbsOf(homeAbs: String?, subPath: String): String? {
    if (homeAbs == null) return null
    if (subPath.isEmpty()) return homeAbs
    val sep = sepOf(homeAbs)
    return homeAbs + sep + subPath.replace(SEP, sep)
}

/** The already-known project at exactly this browsed location (drives the "history" badge). */
internal fun browseProjectAt(dirs: List<DirectoryEntry>, homeAbs: String?, subPath: String): DirectoryEntry? {
    val abs = browseAbsOf(homeAbs, subPath) ?: return null
    return dirs.firstOrNull { it.path == abs }
}

/** Up to [limit] recent projects UNDER the home root, paired with their picker subPath — the daemon
 *  already sorts the flat list newest-first, so "recent" is positional. Off-home projects (other
 *  drives) are skipped: they have no subPath to jump to, and the main list still shows them. */
internal fun browseRecents(dirs: List<DirectoryEntry>, homeAbs: String?, limit: Int = 5): List<Pair<DirectoryEntry, String>> {
    if (homeAbs == null) return emptyList()
    val sep = sepOf(homeAbs)
    return dirs.asSequence()
        .filter { it.path.startsWith(homeAbs + sep) }
        .map { it to it.path.removePrefix(homeAbs + sep).replace(sep, SEP) }
        .take(limit)
        .toList()
}

/** True when this connection is a folder-share GUEST view (issue #115): every project row the daemon
 *  sent is a stamped shared root. Guests don't get the home browser — their ListDirectories reply
 *  always contains the shared root(s) and nothing else, so "non-empty and all stamped" is precise.
 *  Cosmetic only: the daemon independently denies a guest's "~" listing and out-of-scope opens. */
internal fun isGuestDirView(dirs: List<DirectoryEntry>): Boolean =
    dirs.isNotEmpty() && dirs.all { it.sharedBy != null }

/**
 * The picker sheet (UI-DESIGN §5.3 / §10.2④): Recents pinned at the root, a breadcrumb + subfolder
 * browse below, and a bottom "use this directory" bar that starts the session under the persisted
 * defaults ([onStart]) or routes through the full agent/mode picker ([onOptions]) — the same two-action
 * bottom row as the manual path sheet, which stays reachable via [onTypePath] for off-home paths.
 */
@Composable
internal fun DirectoryPickerSheet(
    repo: PocketRepository,
    onDismiss: () -> Unit,
    onTypePath: () -> Unit,
    onOptions: (String) -> Unit,
    onStart: (String) -> Unit,
) {
    var subPath by remember { mutableStateOf("") }
    // null-then-request per level: the listing is only ever null (loading) or the LATEST reply, so a
    // reopened picker can't flash the previous machine's (or level's) stale rows
    LaunchedEffect(subPath) {
        repo.browseListing.value = null
        repo.browseHomeDirs(subPath)
    }

    val dirs = repo.directories.toList()
    val homeAbs = remember(dirs) { browseHomeAbs(dirs) }
    val listing = repo.browseListing.value
    val rows = browseRows(listing, subPath)
    val failed = browseFailed(listing, subPath)
    val recents = remember(dirs, homeAbs) { browseRecents(dirs, homeAbs) }
    val crumbs = browseCrumbsOf(subPath)

    PocketSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.dir_picker_title), color = Tok.tx, fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                // escape hatch to the manual sheet: off-home paths (other drives, /opt) stay reachable
                Text(
                    stringResource(Res.string.dir_picker_type_path), color = Tok.accent, fontSize = 12.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTypePath).padding(6.dp),
                )
            }
            Text(
                stringResource(Res.string.dir_picker_sub), color = Tok.muted, fontSize = 12.5.sp,
                lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp),
            )
        }

        // breadcrumb over the home-anchored browse; segment taps jump levels, ‹ goes one up. Sits
        // OUTSIDE the 18dp block: the shared Breadcrumb carries its own horizontal padding.
        Breadcrumb(
            crumbs,
            onUp = { subPath = browseParentOf(subPath) },
            onSegment = { i -> subPath = if (i == 0) "" else crumbs.drop(1).take(i).joinToString(SEP.toString()) },
        )

        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            // tall enough that the BROWSE rows stay reachable below a full 5-row RECENTS block
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp)) {
                if (subPath.isEmpty() && recents.isNotEmpty()) {
                    item { PickerLabel(stringResource(Res.string.dir_picker_recents)) }
                    items(recents, key = { (e, _) -> "r:" + e.path }) { (e, sub) ->
                        BrowseDirRow(name = e.name.ifBlank { e.path }, isProject = true) { subPath = sub }
                    }
                    item { PickerLabel(stringResource(Res.string.dir_picker_browse)) }
                }
                when {
                    failed -> item {
                        Text(
                            stringResource(Res.string.dir_picker_error), color = Tok.warn, fontSize = 12.5.sp,
                            modifier = Modifier.padding(vertical = 14.dp),
                        )
                    }
                    rows == null -> items(3) { SkeletonRow() }
                    rows.isEmpty() -> item {
                        Text(
                            stringResource(Res.string.dir_picker_empty), color = Tok.muted, fontSize = 12.5.sp,
                            modifier = Modifier.padding(vertical = 14.dp),
                        )
                    }
                    else -> items(rows, key = { "d:" + it.name }) { e ->
                        val child = browseJoin(subPath, e.name)
                        BrowseDirRow(name = e.name, isProject = browseProjectAt(dirs, homeAbs, child) != null) { subPath = child }
                    }
                }
            }

            // ── bottom bar: the current directory + the same two actions as the manual path sheet ──
            TailPathText(browseWorkdirOf(subPath), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SessionDefaultsChip(
                    repo.defaultAgent.value, repo.defaultMode.value,
                    Modifier.height(52.dp),
                ) { onOptions(browseWorkdirOf(subPath)) }
                SheetButton(
                    stringResource(Res.string.dir_picker_use_here),
                    Modifier.weight(1f),
                    bg = Tok.accent, fg = Tok.base,
                ) { onStart(browseWorkdirOf(subPath)) }
            }
        }
    }
}

@Composable
private fun PickerLabel(text: String) = Text(
    text, color = Tok.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
)

/** One browsable folder row: icon + mono name (+ history badge when it's already a project) + chevron. */
@Composable
private fun BrowseDirRow(name: String, isProject: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Tok.tx2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            name, color = Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (isProject) { HistoryBadge(); Spacer(Modifier.width(8.dp)) }
        Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Tok.muted, modifier = Modifier.size(18.dp))
    }
}

/** Loading placeholder row (the design's skeleton state). */
@Composable
private fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(Tok.surface))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.height(13.dp).fillMaxWidth(0.45f).clip(RoundedCornerShape(5.dp)).background(Tok.surface))
    }
}
