package dev.ccpocket.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.codex_preset_cautious
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentModePreset
import dev.ccpocket.protocol.PermissionMode
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Codex 权限词表的解析层（PR #296 评审跟进）。
 *
 * 词表本来硬编码在客户端四处，codex 增删一个档位就要三端同版 + 过商店审核。现在 daemon 用
 * `ModelsList.modePresets` 下发，App 只负责「把下发的行渲染成用户看得懂的样子」。这层的契约是：
 *
 *  - **没下发**（旧 daemon / 取模型失败）→ 内置 `CODEX_PRESETS` 原样，行为与今天完全一致；
 *  - **有下发** → 集合、顺序、danger/recommended 一律以 daemon 为准，文案仍走本地化；
 *  - **id 不认识**（比这版 App 新的档位）→ 直接渲染 wire 上的英文 label/detail，chips 留空——
 *    chips 说的是 approval × sandbox 两轴，对一个没听说过的档位猜轴等于凭空担保。
 */
@OptIn(ExperimentalTestApi::class)
class CodexPresetVocabularyTest {

    private fun advertised(vararg rows: AgentModePreset) = rows.toList()

    @Test
    fun `没下发就是今天的内置四行`() {
        val specs = codexPresetSpecs(emptyList())
        assertEquals(CODEX_PRESETS.map { it.mode }, specs.map { it.mode })
        assertEquals(CODEX_PRESETS, specs.map { it.builtin }, "每行都必须挂着内置行，才能出本地化文案")
        assertEquals(listOf(PermissionMode.DEFAULT), specs.filter { it.recommended }.map { it.mode })
        assertEquals(listOf(PermissionMode.BYPASS_PERMISSIONS), specs.filter { it.danger }.map { it.mode })
    }

    @Test
    fun `下发的顺序与强调压过内置表`() {
        // daemon 把顺序倒过来、把「推荐」挪到 autonomous、把 cautious 标成危险——三项都必须照办：
        // 这正是「不发版就能改词表」要买到的东西
        val specs = codexPresetSpecs(
            advertised(
                AgentModePreset(PermissionMode.BYPASS_PERMISSIONS, "full", "Full access"),
                AgentModePreset(PermissionMode.ACCEPT_EDITS, "autonomous", "Autonomous", recommended = true),
                AgentModePreset(PermissionMode.PLAN, "cautious", "Cautious", danger = true),
            ),
        )
        assertEquals(
            listOf(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN),
            specs.map { it.mode },
            "少一行就是少一行，顺序也照抄",
        )
        assertEquals(listOf(PermissionMode.ACCEPT_EDITS), specs.filter { it.recommended }.map { it.mode })
        assertEquals(listOf(PermissionMode.PLAN), specs.filter { it.danger }.map { it.mode })
        assertTrue(specs.all { it.builtin != null }, "id 认识就仍然走本地化文案，只是强调听 daemon 的")
    }

    @Test
    fun `不认识的 id 走 wire 原文且不带 chips`() {
        val specs = codexPresetSpecs(
            advertised(AgentModePreset(PermissionMode.ACCEPT_EDITS, "yolo-sandboxed", "Sandboxed auto", "New in codex 0.150")),
        )
        val row = specs.single()
        assertNull(row.builtin, "没听说过的档位不能借用别人的文案")
        assertEquals("Sandboxed auto", row.wireLabel)
        assertEquals("New in codex 0.150", row.wireDetail)
    }

    @Test
    fun `id 认识但 mode 变了也按不认识处理`() {
        // 文案和 chips 描述的是一对具体的 approval × sandbox。如果 daemon 说 "cautious" 现在跑
        // DEFAULT，再套「每步都问 + 只读」的中文文案，就是替一个不成立的保证背书。
        val row = codexPresetSpecs(
            advertised(AgentModePreset(PermissionMode.DEFAULT, "cautious", "Cautious", "now asks on request")),
        ).single()
        assertNull(row.builtin)
        assertEquals(PermissionMode.DEFAULT, row.mode, "mode 永远以 daemon 为准——它才是真正会跑的东西")
        assertEquals("Cautious", row.wireLabel)
    }

    @Test
    fun `label 为空时退到 id，行不会变成没名字的可点区域`() {
        val row = codexPresetSpecs(
            advertised(AgentModePreset(PermissionMode.DEFAULT, "brand-new", "   ", "  ")),
        ).single()
        assertEquals("brand-new", row.wireLabel)
        assertNull(row.wireDetail, "只有空白的 detail 等于没有 detail，渲染时整行省掉")
    }

    @Test
    fun `渲染层：认识的 id 出本地化文案与两枚 chips，不认识的出原文且无 chips`() = runComposeUiTest {
        var fallback: List<PresetRowUi>? = null
        var mixed: List<PresetRowUi>? = null
        var cautiousLocalized: String? = null
        setContent {
            PocketTheme {
                cautiousLocalized = stringResource(Res.string.codex_preset_cautious)
                fallback = codexPresetRows(emptyList())
                mixed = codexPresetRows(
                    listOf(
                        AgentModePreset(PermissionMode.PLAN, "cautious", "Cautious", "Ask before every step"),
                        AgentModePreset(PermissionMode.ACCEPT_EDITS, "yolo-sandboxed", "Sandboxed auto", "New in codex 0.150"),
                    ),
                )
            }
        }
        waitForIdle()

        val builtinRows = assertNotNull(fallback)
        assertEquals(CODEX_PRESETS.size, builtinRows.size)
        assertTrue(builtinRows.all { it.askChip != null && it.fsChip != null && it.desc != null })

        val rows = assertNotNull(mixed)
        assertEquals(2, rows.size)
        // 认识的 id：名字来自资源表（跟 App 别处显示的完全同一份），不是 wire 上的英文兜底
        assertEquals(cautiousLocalized, rows[0].name)
        assertNotNull(rows[0].askChip)
        assertNotNull(rows[0].fsChip)
        // 不认识的 id：原文照登，两枚 chips 留空 —— 渲染时整排 chips 一起省掉
        assertEquals("Sandboxed auto", rows[1].name)
        assertEquals("New in codex 0.150", rows[1].desc)
        assertNull(rows[1].askChip)
        assertNull(rows[1].fsChip)
    }
}
