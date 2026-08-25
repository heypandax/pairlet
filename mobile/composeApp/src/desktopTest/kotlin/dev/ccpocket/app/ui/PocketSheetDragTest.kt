package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import dev.ccpocket.app.present
import dev.ccpocket.app.theme.PocketTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PocketSheet] 的下拉关闭手势：**手势不能因为「回调换了个身份」而被打断**。
 *
 * 背景（PR #296 评审）：拖拽把手原本写成 `pointerInput(onDismiss, dismissThresholdPx)`。调用方给的
 * 是内联 lambda，它的身份会随无关状态翻转（实例：重连 sheet 的 `timedOut`）。key 一变，pointerInput
 * 协程就在手势中途被取消——而 `detectVerticalDragGestures` 在这条路径上**不会**回调 `onDragCancel`，
 * 于是 remember 的 `dragY` 保留着非零位移：sheet 卡在半下移的位置，且这一次下拉不会关闭它。
 *
 * 修法是标准套路：key 只留 density 派生的稳定 `dismissThresholdPx`，回调走 `rememberUpdatedState`。
 * 下面两个测试分别钉「中途重组不打断手势」和「关闭时调到的是最新回调」——前者是回归闸，
 * 后者防止有人把 key 加回去时顺手把 rememberUpdatedState 也删了（那会调到过期的 lambda）。
 *
 * 手势用 `moveBy` 而非 `moveTo`：sheet 跟手时 `graphicsLayer.translationY` 会挪动节点自身，
 * 节点相对坐标因此不可靠，而 `moveBy` 是相对上一个指针位置的增量，与节点是否移动无关。
 */
@OptIn(ExperimentalTestApi::class)
class PocketSheetDragTest {

    private val body = "sheet-body-marker"

    @Test
    fun `拖拽中途回调身份翻转不打断手势`() = runComposeUiTest {
        var open by mutableStateOf(true)
        var flip by mutableStateOf(false)
        var dismissedWith: Boolean? = null
        setContent {
            PocketTheme {
                // 复刻真实调用点：一个无关的状态被读进局部 val，再被 lambda 捕获——Compose 的 lambda
                // 记忆化以捕获值为 key，所以 flip 一翻转 onDismiss 就是一个新实例。
                val timedOut = flip
                if (open) {
                    PocketSheet(onDismiss = { open = false; dismissedWith = timedOut }) {
                        Text(body, Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()
        assertTrue(present(body), "sheet 应先是打开的")

        // 手指按下并拉出一段（超过 touch slop，但先不松手）
        onNodeWithTag(POCKET_SHEET_DRAG_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 90f))
        }
        // 手势进行中翻转无关状态 → onDismiss 换身份 → 旧写法在这里取消 pointerInput
        flip = true
        waitForIdle()
        assertTrue(present(body), "中途重组本身不该关闭 sheet")

        // 继续拉过 64dp 阈值再松手
        onNodeWithTag(POCKET_SHEET_DRAG_HANDLE_TAG).performTouchInput {
            moveBy(Offset(0f, 140f))
            up()
        }
        waitForIdle()

        assertFalse(
            present(body),
            "手势中途的重组把 pointerInput 重启了：detectVerticalDragGestures 丢了这次拖拽，" +
                "sheet 关不掉且 dragY 残留非零位移（PR #296 评审项）",
        )
        assertEquals(true, dismissedWith, "关闭走的应是翻转后的最新回调")
    }

    @Test
    fun `关闭时调用的是最新的回调而不是首次组合的那个`() = runComposeUiTest {
        var open by mutableStateOf(true)
        var generation by mutableStateOf(1)
        var calledWith = 0
        setContent {
            PocketTheme {
                val gen = generation
                if (open) {
                    PocketSheet(onDismiss = { calledWith = gen; open = false }) {
                        Text(body, Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()
        generation = 2
        waitForIdle()

        onNodeWithTag(POCKET_SHEET_DRAG_HANDLE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 90f))
            moveBy(Offset(0f, 140f))
            up()
        }
        waitForIdle()

        assertFalse(present(body), "拉过阈值必须关闭 sheet")
        assertEquals(2, calledWith, "rememberUpdatedState 必须让手势调到最新的 onDismiss，而不是首帧那个")
    }
}
