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
import dev.ccpocket.app.resources.dir_picker_home
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
internal fun browseRows(listing: PathEntries?, anchor: String, subPath: String): List<PathEntry>? {
    if (listing == null || listing.subPath != subPath || listing.workdir != anchor) return null
    return listing.entries.filter { it.isDir && !it.name.startsWith(".") }
}

/** True when the daemon answered "can't read that" for the CURRENT (anchor, subPath) (unreadable/escaped dir). */
internal fun browseFailed(listing: PathEntries?, anchor: String, subPath: String): Boolean =
    listing != null && listing.workdir == anchor && listing.subPath == subPath && !listing.ok

internal fun browseJoin(subPath: String, name: String): String =
    if (subPath.isEmpty()) name else "$subPath$SEP$name"

/** One level up ("" at the root stays the root). */
internal fun browseParentOf(subPath: String): String = subPath.substringBeforeLast(SEP, "")

/** The OpenSession workdir for ([anchor], [subPath]): the raw "~" form the daemon expands, or a native
 *  absolute path under a filesystem-root anchor (#176) — same contract as the manual path sheet, which
 *  RequestRouter resolves via validateOrCreateWorkdir, answering with the real absolute path. */
internal fun browseWorkdirOf(anchor: String, subPath: String): String = joinNative(anchor, subPath)

/** Join a browse [anchor] ("~", "/", "C:\") with the picker's '/'-keyed [subPath] using the anchor's
 *  NATIVE separator, WITHOUT doubling a root's trailing one: "C:\" + "src" → "C:\src" (never "C:\/src"),
 *  "/" + "opt" → "/opt", "~" + "src" → "~/src" (the raw form the daemon expands). "" = the anchor itself. */
internal fun joinNative(anchor: String, subPath: String): String {
    if (subPath.isEmpty()) return anchor
    val sep = sepOf(anchor)
    val native = subPath.replace(SEP, sep)
    return if (anchor.endsWith(sep)) anchor + native else anchor + sep + native
}

/** Breadcrumb labels for ([anchor], [subPath]): the root segment is "~" for home or the root trimmed of
 *  its trailing separator ("C:", "/") for a filesystem-root anchor (#176), then the '/'-keyed subPath. */
internal fun browseCrumbsOf(anchor: String, subPath: String): List<String> {
    val rootLabel = if (anchor == PocketRepository.BROWSE_HOME) PocketRepository.BROWSE_HOME else trimTrailingSep(anchor)
    return listOf(rootLabel) + subPath.split(SEP).filter { it.isNotEmpty() }
}

/** The daemon host's home dir in NATIVE form, inferred from the project paths it already reported
 *  (same rule the tree root uses) — null when nothing is inferable (a fresh machine with no history:
 *  exactly the #152 case; badges and recents just stay off then). */
internal fun browseHomeAbs(dirs: List<DirectoryEntry>): String? =
    dirs.firstNotNullOfOrNull { homePrefix(it.path) }

/** ([anchor], [subPath]) as a native absolute path (for matching against DirectoryEntry.path): resolved
 *  under [homeAbs] for the "~" anchor (null when home is unknown), or directly under a filesystem-root
 *  anchor (#176, which needs no home inference). */
internal fun browseAbsOf(homeAbs: String?, anchor: String, subPath: String): String? {
    val base = if (anchor == PocketRepository.BROWSE_HOME) homeAbs ?: return null else anchor
    return joinNative(base, subPath)
}

/** The already-known project at exactly this browsed location (drives the "history" badge). */
internal fun browseProjectAt(dirs: List<DirectoryEntry>, homeAbs: String?, anchor: String, subPath: String): DirectoryEntry? {
    val abs = browseAbsOf(homeAbs, anchor, subPath) ?: return null
    return dirs.firstOrNull { it.path == abs }
}

/** A recent project paired with the ([anchor], [subPath]) that navigates the picker to it (#176). */
internal data class BrowseRecent(val entry: DirectoryEntry, val anchor: String, val subPath: String)

/** Up to [limit] recent projects, each with the ([anchor], subPath) that reaches it — so a tap can
 *  switch the browse root when the project lives OFF home (#176; before, off-home projects had no
 *  subPath to jump to and were silently skipped). The daemon already sorts the flat list newest-first,
 *  so "recent" stays positional. A project with no inferable anchor (a relative/odd path) is skipped. */
internal fun browseRecents(dirs: List<DirectoryEntry>, homeAbs: String?, limit: Int = 5): List<BrowseRecent> =
    dirs.asSequence()
        .mapNotNull { e -> anchorOf(e.path, homeAbs)?.let { (a, s) -> BrowseRecent(e, a, s) } }
        .take(limit)
        .toList()

/** The ([anchor], '/'-keyed subPath) that reaches native absolute [path]: "~" + relative when [path]
 *  sits under [homeAbs], else the path's own filesystem root ("/", "C:\") + relative. null when neither
 *  applies (a relative/unrooted path). Home is preferred so home projects keep the friendly "~" anchor. */
internal fun anchorOf(path: String, homeAbs: String?): Pair<String, String>? {
    if (homeAbs != null && (path == homeAbs || path.startsWith(homeAbs + sepOf(homeAbs)))) {
        return PocketRepository.BROWSE_HOME to path.removePrefix(homeAbs).trimStart('/', '\\').replace('\\', SEP)
    }
    val root = fsRootOf(path) ?: return null
    return root to path.removePrefix(root).replace('\\', SEP)
}

/** The filesystem root of a native absolute path: the drive ("C:\") on Windows, "/" on Unix, else null. */
internal fun fsRootOf(path: String): String? {
    Regex("""^([A-Za-z]):[\\/]""").find(path)?.let { return it.groupValues[1] + ":\\" }
    return if (path.startsWith("/")) "/" else null
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
    // #176: the browse ANCHOR — "~" (the daemon home) by default, or a filesystem root ("/", "C:\") once
    // the root switcher picks one. subPath is always relative to it, '/'-keyed client-side.
    var anchor by remember { mutableStateOf(PocketRepository.BROWSE_HOME) }
    var subPath by remember { mutableStateOf("") }
    // null-then-request per level: the listing is only ever null (loading) or the LATEST reply, so a
    // reopened picker (or a root switch) can't flash the previous anchor's (or level's) stale rows
    LaunchedEffect(anchor, subPath) {
        repo.browseListing.value = null
        repo.browseDirs(anchor, subPath)
    }

    val dirs = repo.directories.toList()
    val homeAbs = remember(dirs) { browseHomeAbs(dirs) }
    val listing = repo.browseListing.value
    val rows = browseRows(listing, anchor, subPath)
    val failed = browseFailed(listing, anchor, subPath)
    val recents = remember(dirs, homeAbs) { browseRecents(dirs, homeAbs) }
    val crumbs = browseCrumbsOf(anchor, subPath)
    // the fs-root switcher's choices (#176) — owner-only: a guest never receives roots (daemon-gated),
    // and this belt-and-suspenders keeps the switcher off for a guest view client-side too. Empty against
    // an old daemon → the breadcrumb stays a plain crumb (manual path still covers off-home), so a new
    // app on an old daemon degrades cleanly.
    val roots = if (isGuestDirView(dirs)) emptyList() else repo.browseRoots.value

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

        // breadcrumb over the anchored browse; segment taps jump levels, ‹ goes one up, and (with #176
        // roots) the root segment opens the switcher. Sits OUTSIDE the 18dp block: it carries its own
        // horizontal padding.
        PickerBreadcrumb(
            crumbs, roots, anchor,
            // ‹ climbs one level; at a non-home root's top it returns to home instead of dead-ending — a
            // switcher-independent way back (matters when a new app talks to an old daemon that sends no roots)
            onUp = {
                if (subPath.isEmpty() && anchor != PocketRepository.BROWSE_HOME) anchor = PocketRepository.BROWSE_HOME
                else subPath = browseParentOf(subPath)
            },
            onSegment = { i -> subPath = if (i == 0) "" else crumbs.drop(1).take(i).joinToString(SEP.toString()) },
            onSwitchRoot = { chosen -> anchor = chosen; subPath = "" },
        )

        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            // tall enough that the BROWSE rows stay reachable below a full 5-row RECENTS block
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 400.dp)) {
                // recents pin at the home root; each carries its own anchor so an off-home one (#176)
                // switches the drive as well as the level on tap
                if (anchor == PocketRepository.BROWSE_HOME && subPath.isEmpty() && recents.isNotEmpty()) {
                    item { PickerLabel(stringResource(Res.string.dir_picker_recents)) }
                    items(recents, key = { "r:" + it.entry.path }) { r ->
                        BrowseDirRow(name = r.entry.name.ifBlank { r.entry.path }, isProject = true) { anchor = r.anchor; subPath = r.subPath }
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
                        BrowseDirRow(name = e.name, isProject = browseProjectAt(dirs, homeAbs, anchor, child) != null) { subPath = child }
                    }
                }
            }

            // ── bottom bar: the current directory + the same two actions as the manual path sheet ──
            TailPathText(browseWorkdirOf(anchor, subPath), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SessionDefaultsChip(
                    repo.defaultAgent.value, repo.defaultMode.value,
                    Modifier.height(52.dp),
                ) { onOptions(browseWorkdirOf(anchor, subPath)) }
                SheetButton(
                    stringResource(Res.string.dir_picker_use_here),
                    Modifier.weight(1f),
                    bg = Tok.accent, fg = Tok.base,
                ) { onStart(browseWorkdirOf(anchor, subPath)) }
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

/** The picker's breadcrumb with the #176 root switcher folded into its root segment: when the daemon
 *  reported filesystem [roots], the leading crumb gains a ▾ and taps open an inline panel of Home + every
 *  root; picking one calls [onSwitchRoot] (which the sheet turns into an anchor switch). With no roots
 *  (old daemon / guest) it renders as the plain breadcrumb — byte-identical to the pre-#176 behaviour, so
 *  the manual-path escape hatch stays the only off-home route there. */
@Composable
private fun PickerBreadcrumb(
    crumbs: List<String>,
    roots: List<String>,
    anchor: String,
    onUp: () -> Unit,
    onSegment: (Int) -> Unit,
    onSwitchRoot: (String) -> Unit,
) {
    var expanded by remember(roots) { mutableStateOf(false) }
    val switchable = roots.isNotEmpty()
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("‹", color = Tok.tx2, fontSize = 18.sp, modifier = Modifier.clickable(onClick = onUp).padding(end = 2.dp))
            crumbs.forEachIndexed { i, s ->
                val last = i == crumbs.lastIndex
                if (i == 0 && switchable) {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            s, color = if (last) Tok.tx else Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                        )
                        Text(if (expanded) "▴" else "▾", color = Tok.accent, fontSize = 11.sp, modifier = Modifier.padding(start = 3.dp))
                    }
                } else {
                    Text(
                        s, color = if (last) Tok.tx else Tok.tx2, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                        modifier = Modifier.clickable(enabled = !last) { onSegment(i) },
                    )
                }
                if (!last) Text("›", color = Tok.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        if (switchable && expanded) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Tok.surface).padding(vertical = 4.dp),
            ) {
                RootChoiceRow(stringResource(Res.string.dir_picker_home), selected = anchor == PocketRepository.BROWSE_HOME) {
                    expanded = false; onSwitchRoot(PocketRepository.BROWSE_HOME)
                }
                roots.forEach { r ->
                    RootChoiceRow(r, selected = anchor == r) { expanded = false; onSwitchRoot(r) }
                }
            }
        }
    }
}

/** One row in the #176 root-switch panel — a drive/root the browse can jump to (accent + ✓ when current). */
@Composable
private fun RootChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = if (selected) Tok.accent else Tok.tx2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label, color = if (selected) Tok.accent else Tok.tx, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (selected) Text("✓", color = Tok.accent, fontSize = 12.sp)
    }
}
