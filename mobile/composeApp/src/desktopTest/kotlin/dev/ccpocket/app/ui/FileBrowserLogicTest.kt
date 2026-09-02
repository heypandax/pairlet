package dev.ccpocket.app.ui

import dev.ccpocket.protocol.ChangedFile
import dev.ccpocket.protocol.PathEntries
import dev.ccpocket.protocol.PathEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 文件浏览双视角的纯逻辑（design handoff: files-browser-dual-view）：变更集 → 树标记的映射、
 * 目录子树计数、面包屑路径切分、隐藏项过滤、以及桌面树的摊平。
 *
 * 这几条是「两个视角靠 M/A/D 贯通」这一裁决的全部承重结构——错一处，全部视角要么标不上、要么标错，
 * 而这两种错都只在真机上看得出来。
 */
class FileBrowserLogicTest {

    private fun changed(path: String, op: String = "edit") = ChangedFile(path = path, op = op)

    private fun level(subPath: String, vararg entries: PathEntry, truncated: Boolean = false, ok: Boolean = true) =
        PathEntries(workdir = "/w", subPath = subPath, entries = entries.toList(), truncated = truncated, ok = ok)

    // ── workdir 相对键 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_changed_path_maps_to_its_slash_keyed_relative_key() {
        assertEquals("src/ui/A.kt", relUnderWorkdir("/w/proj", "/w/proj/src/ui/A.kt"))
        assertEquals("A.kt", relUnderWorkdir("/w/proj", "/w/proj/A.kt"))
        // Windows daemon：原生 '\' 全部折成 '/' 键
        assertEquals("src/A.kt", relUnderWorkdir("""C:\w\proj""", """C:\w\proj\src\A.kt"""))
        // 根 workdir 自带尾分隔符，不能被 trim 成空串
        assertEquals("opt/x", relUnderWorkdir("/", "/opt/x"))
    }

    @Test
    fun a_path_outside_the_workdir_has_no_relative_key() {
        // 前缀相同但不在同一层级：截断处必须落在分隔符上，否则子树计数会虚高
        assertNull(relUnderWorkdir("/w/proj", "/w/project/A.kt"), "'/w/project' 不是 '/w/proj' 的子路径")
        assertNull(relUnderWorkdir("/w/proj", "/other/A.kt"))
        assertNull(relUnderWorkdir("/w/proj", "/w/proj"), "workdir 自己不是它自己的子项")
        assertNull(relUnderWorkdir("", "/w/proj/A.kt"))
    }

    // ── 变更集 → 树标记 ───────────────────────────────────────────────────────────────────────────

    @Test
    fun the_changed_index_keys_on_relative_paths_and_keeps_the_op() {
        val index = changedIndexOf(
            "/w/proj",
            listOf(
                changed("/w/proj/src/ui/A.kt", "write"),
                changed("/w/proj/src/ui/B.kt", "edit"),
                changed("/w/proj/README.md", "delete"),
                // #67 的导出路径可以落在 workdir 之外——树里标不上它，也不该混进任何计数
                changed("/elsewhere/report.xlsx", "write"),
            ),
        )
        assertEquals(setOf("src/ui/A.kt", "src/ui/B.kt", "README.md"), index.keys)
        assertEquals("write", index["src/ui/A.kt"])
        assertEquals("delete", index["README.md"])
        assertTrue(changedIndexOf(null, listOf(changed("/w/proj/A.kt"))).isEmpty(), "没有 workdir 就什么都标不了")
    }

    @Test
    fun the_op_to_letter_mapping_is_the_one_the_changed_list_already_uses() {
        // A / M / D 的口径只有一处（DiffView.statusLetter），两个视角共用它才不会漂
        assertEquals("A", statusLetter("write"))
        assertEquals("M", statusLetter("edit"))
        assertEquals("D", statusLetter("delete"))
        assertEquals("N", statusLetter("notebook"))
    }

    @Test
    fun a_file_row_finds_its_status_only_at_its_own_level() {
        val index = mapOf("src/ui/A.kt" to "write", "A.kt" to "edit")
        assertEquals("write", statusOpAt(index, "src/ui", "A.kt"))
        assertEquals("edit", statusOpAt(index, "", "A.kt"))
        assertNull(statusOpAt(index, "src", "A.kt"), "同名文件在别的层不能借到状态点")
    }

    @Test
    fun a_folder_counts_every_change_under_it_and_nothing_beside_it() {
        val index = mapOf(
            "src/ui/A.kt" to "write",
            "src/ui/B.kt" to "edit",
            "src/net/C.kt" to "edit",
            "docs/D.md" to "edit",
        )
        assertEquals(4, subtreeChangeCount(index, ""), "根覆盖全部")
        assertEquals(3, subtreeChangeCount(index, "src"))
        assertEquals(2, subtreeChangeCount(index, "src/ui"))
        assertEquals(0, subtreeChangeCount(index, "test"))
        // 前缀相同但不同层：'src2' 不能借走 'src' 的计数
        assertEquals(0, subtreeChangeCount(mapOf("src2/A.kt" to "edit"), "src"))
    }

    // ── 面包屑 / 路径代数 ────────────────────────────────────────────────────────────────────────

    @Test
    fun breadcrumb_segments_and_their_jump_targets_agree() {
        assertEquals(emptyList(), treeSegmentsOf(""))
        assertEquals(listOf("src", "ui"), treeSegmentsOf("src/ui"))
        assertEquals("", treeSubPathAt("src/ui", -1), "根段跳回 workdir 本身")
        assertEquals("src", treeSubPathAt("src/ui", 0))
        assertEquals("src/ui", treeSubPathAt("src/ui", 1))
        assertEquals("src/ui", treeJoin("src", "ui"))
        assertEquals("src", treeJoin("", "src"))
        assertEquals("src", treeParentOf("src/ui"))
        assertEquals("", treeParentOf("src"))
        assertEquals("", treeParentOf(""))
    }

    // ── 隐藏项过滤 ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun the_eye_toggle_filters_dot_entries_on_both_kinds() {
        val entries = listOf(PathEntry("src", true), PathEntry(".git", true), PathEntry("A.kt", false), PathEntry(".env", false))
        assertEquals(listOf("src", "A.kt"), visibleEntries(entries, showHidden = false).map { it.name })
        assertEquals(4, visibleEntries(entries, showHidden = true).size)
    }

    // ── 桌面树摊平 ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_collapsed_tree_shows_only_the_root_level() {
        val levels = mapOf(
            "" to level("", PathEntry("src", true), PathEntry("README.md", false)),
            "src" to level("src", PathEntry("A.kt", false)),
        )
        val rows = flattenFileTree(levels, expanded = emptySet(), showHidden = false)
        assertEquals(listOf("src", "README.md"), rows.map { it.name })
        assertEquals(listOf(0, 0), rows.map { it.depth })
        assertTrue(rows.none { it.expanded }, "没展开的目录不带 ▾")
    }

    @Test
    fun an_expanded_folder_splices_its_children_in_at_the_next_depth() {
        val levels = mapOf(
            "" to level("", PathEntry("src", true), PathEntry("README.md", false)),
            "src" to level("src", PathEntry("ui", true), PathEntry("Main.kt", false)),
            "src/ui" to level("src/ui", PathEntry("A.kt", false)),
        )
        val rows = flattenFileTree(levels, expanded = setOf("src", "src/ui"), showHidden = false)
        assertEquals(listOf("src", "ui", "A.kt", "Main.kt", "README.md"), rows.map { it.name })
        assertEquals(listOf(0, 1, 2, 1, 0), rows.map { it.depth })
        assertEquals(listOf("src", "src/ui", "src/ui/A.kt", "src/Main.kt", "README.md"), rows.map { it.subPath })
    }

    @Test
    fun an_expanded_folder_whose_level_has_not_arrived_marks_itself_loading() {
        val levels = mapOf("" to level("", PathEntry("src", true)))
        val rows = flattenFileTree(levels, expanded = setOf("src"), showHidden = false)
        assertEquals(1, rows.size, "缺的层直接跳过——点开的那一行自己顶着 loading")
        assertTrue(rows[0].expanded && rows[0].loading)
    }

    @Test
    fun a_truncated_level_gets_its_own_trailing_notice_row() {
        val levels = mapOf(
            "" to level("", PathEntry("src", true)),
            "src" to level("src", PathEntry("A.kt", false), truncated = true),
        )
        val rows = flattenFileTree(levels, expanded = setOf("src"), showHidden = false)
        assertEquals(listOf(FileRowKind.DIR, FileRowKind.FILE, FileRowKind.TRUNCATED), rows.map { it.kind })
        assertEquals("src", rows.last().subPath, "提示行属于被截断的那一层，不是根")
        assertEquals(1, rows.last().depth)
    }

    @Test
    fun the_eye_toggle_reaches_every_level_of_the_tree() {
        val levels = mapOf(
            "" to level("", PathEntry("src", true), PathEntry(".git", true)),
            "src" to level("src", PathEntry("A.kt", false), PathEntry(".DS_Store", false)),
        )
        assertEquals(
            listOf("src", "A.kt"),
            flattenFileTree(levels, expanded = setOf("src"), showHidden = false).map { it.name },
        )
        assertEquals(
            listOf("src", "A.kt", ".DS_Store", ".git"),
            flattenFileTree(levels, expanded = setOf("src"), showHidden = true).map { it.name },
        )
    }
}
