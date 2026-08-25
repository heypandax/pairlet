package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.ccpocket.app.present
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.codex_preset_autonomous
import dev.ccpocket.app.resources.codex_preset_cautious
import dev.ccpocket.app.resources.mode_plan_label
import dev.ccpocket.app.resources.mode_switching
import dev.ccpocket.app.str
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mode-switch sheet while a switch is already in flight (PR #296 评审跟进).
 *
 * 这里管的是**真实文件系统权限的开关**：一次 `switchMode` 还在路上时，行只有 0.55 alpha 的视觉暗示，
 * 但如果它仍然吃点击，双击或者「点错了改点另一行」就会连发两次切换——两个请求的完成顺序不受控，会话最后
 * 落在哪个权限档位是掷骰子。Claude 那一支的 `ModeRow` 一直有 `enabled = !switching` 这道门，Codex 分支
 * 漏了，本测试把两支拉齐并钉死。
 *
 * 每个用例都先证明「不切换时这一点是点得动的」，否则「没触发」可能只是点空了。
 */
@OptIn(ExperimentalTestApi::class)
class ModeSheetSwitchingUiTest {

    /** The sheet at the release baseline viewport, with every selection captured. */
    private fun sheet(
        agent: AgentKind,
        switching: Boolean,
        current: PermissionMode = PermissionMode.DEFAULT,
        assertions: SkikoComposeUiTest.(MutableList<PermissionMode>) -> Unit,
    ) = runDesktopComposeUiTest(W, H) {
        val picked = mutableListOf<PermissionMode>()
        setContent {
            PocketTheme {
                Box(Modifier.fillMaxSize()) {
                    ModeSheet(
                        current = current, rules = emptyList(), switching = switching,
                        workdir = "/Users/alex/code/cc-pocket", agent = agent,
                        onSelect = { m, _ -> picked += m },
                        onClearRule = {}, onClearAll = {}, onDismiss = {},
                    )
                }
            }
        }
        waitForIdle()
        assertions(picked)
    }

    @Test
    fun `切换进行中 Codex 预设行不接受点击`() {
        sheet(AgentKind.CODEX, switching = true) { picked ->
            assertTrue(present(str(Res.string.mode_switching)), "切换中的说明必须在场——0.55 alpha 不是唯一说明")
            // 「谨慎」不是当前档位，正常情况下点它一定会发一次切换
            onAllNodes(hasText(str(Res.string.codex_preset_cautious))).onFirst().performClick()
            waitForIdle()
            // 换一行再点——真实的误操作是「点了没反应，就改点别的」
            onAllNodes(hasText(str(Res.string.codex_preset_autonomous))).onFirst().performClick()
            waitForIdle()
            assertEquals(emptyList(), picked, "切换在路上时，任何一行都不得再发第二次 switchMode")
        }
    }

    @Test
    fun `没在切换时同一行照常可点`() {
        // 反证：上面的「没触发」来自这道门，而不是来自点空了或者行根本不可点
        sheet(AgentKind.CODEX, switching = false) { picked ->
            onAllNodes(hasText(str(Res.string.codex_preset_cautious))).onFirst().performClick()
            waitForIdle()
            assertEquals(listOf(PermissionMode.PLAN), picked, "同一次点击在非切换态下必须真的选中「谨慎」")
        }
    }

    @Test
    fun `Claude 那一支的同一道门保持原样`() {
        // 两支从来就该同规矩；这条防的是「修 Codex 的时候把 Claude 的门碰掉了」
        sheet(AgentKind.CLAUDE, switching = true) { picked ->
            onAllNodes(hasText(str(Res.string.mode_plan_label))).onFirst().performClick()
            waitForIdle()
            assertEquals(emptyList(), picked)
        }
        sheet(AgentKind.CLAUDE, switching = false) { picked ->
            onAllNodes(hasText(str(Res.string.mode_plan_label))).onFirst().performClick()
            waitForIdle()
            assertEquals(listOf(PermissionMode.PLAN), picked)
        }
    }

    private companion object {
        const val W = 402
        const val H = 874
    }
}
