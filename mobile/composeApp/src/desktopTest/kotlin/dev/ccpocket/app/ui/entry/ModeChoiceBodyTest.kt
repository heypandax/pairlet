package dev.ccpocket.app.ui.entry

import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.cfg_mode_default_body
import dev.ccpocket.app.resources.cfg_mode_full_body
import dev.ccpocket.app.resources.cfg_mode_plan_body
import dev.ccpocket.app.resources.cfg_mode_plan_body_kimi
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionMode
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 新建会话面板里每个模式下面那句说明（[modeChoiceBodyRes]）。
 *
 * kimi 的计划模式不是只读：读文件直接放行、改文件被 kimi 自己拦下，但命令仍会先问、批准后照样执行
 * （kimi 2.1.1 实测，见 KimiBackendLiveIT）。所以 Kimi 的「计划」用单独一句，不能沿用 Claude 那句
 * 「在你退出计划模式之前什么都不改」；其他档位和其他后端的说明保持不变。
 */
class ModeChoiceBodyTest {

    private val plan = ModeChoice(PermissionMode.PLAN)

    @Test
    fun `kimi plan says it still asks before acting`() {
        assertEquals(Res.string.cfg_mode_plan_body_kimi, modeChoiceBodyRes(AgentKind.KIMI, plan))
    }

    @Test
    fun `claude plan keeps the read-only sentence`() {
        assertEquals(Res.string.cfg_mode_plan_body, modeChoiceBodyRes(AgentKind.CLAUDE, plan))
        assertNotEquals(
            runBlocking { getString(Res.string.cfg_mode_plan_body) },
            runBlocking { getString(Res.string.cfg_mode_plan_body_kimi) },
        )
    }

    @Test
    fun `dsh plan keeps the read-only sentence — its read-only sandbox refuses writes`() {
        assertEquals(Res.string.cfg_mode_plan_body, modeChoiceBodyRes(AgentKind.DSH, plan))
    }

    @Test
    fun `kimi's other modes keep the shared sentences`() {
        assertEquals(Res.string.cfg_mode_default_body, modeChoiceBodyRes(AgentKind.KIMI, ModeChoice(PermissionMode.DEFAULT)))
        assertEquals(
            Res.string.cfg_mode_full_body,
            modeChoiceBodyRes(AgentKind.KIMI, ModeChoice(PermissionMode.BYPASS_PERMISSIONS, danger = true)),
        )
    }

    @Test
    fun `the kimi mode ladder itself is unchanged`() {
        assertEquals(
            listOf(PermissionMode.DEFAULT, PermissionMode.PLAN, PermissionMode.BYPASS_PERMISSIONS),
            agentModeChoices(AgentKind.KIMI).map { it.mode },
        )
    }
}
