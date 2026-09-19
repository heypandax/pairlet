package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.protocol.AskOption
import dev.ccpocket.protocol.AskQuestion
import dev.ccpocket.protocol.PermissionAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import dev.ccpocket.app.resources.Res
import dev.ccpocket.app.resources.question_answer
import dev.ccpocket.app.resources.question_freeform_link
import dev.ccpocket.app.resources.question_other
import dev.ccpocket.app.resources.questions_unanswered_collapse
import dev.ccpocket.app.resources.questions_unanswered_expand

/** The AskUserQuestion card: selection → answers map shapes (the wire contract with the daemon). */
@OptIn(ExperimentalTestApi::class)
class QuestionCardUiTest {

    private fun ask(vararg qs: AskQuestion) = PermissionAsk(
        convoId = "c1", askId = "a1", tool = "AskUserQuestion", inputPreview = "q",
        questions = qs.toList(),
    )

    private val color = AskQuestion(
        "Which color?", header = "Color",
        options = listOf(AskOption("Red", "warm"), AskOption("Blue", "cool")),
    )
    private val sections = AskQuestion(
        "Which sections?", header = "Sections", multiSelect = true,
        options = listOf(AskOption("Intro", "start"), AskOption("Body", "middle"), AskOption("End", "close")),
    )

    @Test
    fun singleSelect_pick_then_answer_sends_label_keyed_by_question() = runComposeUiTest {
        var got: Map<String, String>? = null
        var gotResponse: String? = "unset"
        setContent { PocketTheme { QuestionCard(ask(color), onAnswer = { a, r -> got = a; gotResponse = r }, onSkip = {}) } }
        onAllNodes(hasText("Red")).onFirst().performClick()
        val answerLabel = runBlocking { getString(Res.string.question_answer) }
        onAllNodes(hasText(answerLabel)).onFirst().performClick()
        assertEquals(mapOf("Which color?" to "Red"), got)
        assertNull(gotResponse)
    }

    @Test
    fun multiSelect_joins_labels_with_comma_and_includes_other_text() = runComposeUiTest {
        var got: Map<String, String>? = null
        setContent { PocketTheme { QuestionCard(ask(sections), onAnswer = { a, _ -> got = a }, onSkip = {}) } }
        onAllNodes(hasText("Intro")).onFirst().performClick()
        onAllNodes(hasText("End")).onFirst().performClick()
        val other = runBlocking { getString(Res.string.question_other) }
        onAllNodes(hasText(other)).onFirst().performClick()
        // the expanded inline field is the only text-input on screen
        onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performTextInput("附录")
        val answerLabel = runBlocking { getString(Res.string.question_answer) }
        onAllNodes(hasText(answerLabel)).onFirst().performClick()
        assertEquals(mapOf("Which sections?" to "Intro, End, 附录"), got)
    }

    @Test
    fun freeform_reply_sends_response_instead_of_answers() = runComposeUiTest {
        var gotAnswers: Map<String, String>? = mapOf("x" to "y")
        var gotResponse: String? = null
        setContent { PocketTheme { QuestionCard(ask(color, sections), onAnswer = { a, r -> gotAnswers = a; gotResponse = r }, onSkip = {}) } }
        val link = runBlocking { getString(Res.string.question_freeform_link) }
        onAllNodes(hasText(link)).onFirst().performClick()
        onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performTextInput("随便选个快的")
        val answerLabel = runBlocking { getString(Res.string.question_answer) }
        onAllNodes(hasText(answerLabel)).onFirst().performClick()
        assertNull(gotAnswers)
        assertEquals("随便选个快的", gotResponse)
    }

    // #150: 桌面端把卡片放在 LazyColumn 的 tail item 里（无限高约束）。#125 的 weighted 中段在
    // 无界约束下按 minHeight=0 测量，被压成 0 高 → 只剩标题和按钮的空壳。这里在无限高宿主中
    // 渲染，断言问题文本与选项真实可见、且整条回答链路仍然可走通。
    @Test
    fun unboundedHeight_lazyColumn_item_still_shows_question_and_options() = runComposeUiTest {
        var got: Map<String, String>? = null
        setContent {
            PocketTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "tail") { QuestionCard(ask(color), onAnswer = { a, _ -> got = a }, onSkip = {}) }
                }
            }
        }
        onAllNodes(hasText("Which color?")).onFirst().assertIsDisplayed()
        onAllNodes(hasText("Red")).onFirst().assertIsDisplayed()
        onAllNodes(hasText("Blue")).onFirst().assertIsDisplayed()
        onAllNodes(hasText("Red")).onFirst().performClick()
        val answerLabel = runBlocking { getString(Res.string.question_answer) }
        onAllNodes(hasText(answerLabel)).onFirst().performClick()
        assertEquals(mapOf("Which color?" to "Red"), got)
    }

    @Test
    fun unboundedHeight_verticalScroll_host_still_shows_question_and_options() = runComposeUiTest {
        setContent {
            PocketTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    QuestionCard(ask(color), onAnswer = { _, _ -> }, onSkip = {})
                }
            }
        }
        onAllNodes(hasText("Which color?")).onFirst().assertIsDisplayed()
        onAllNodes(hasText("Red")).onFirst().assertIsDisplayed()
    }

    // ── #384：回放的未作答提问必须能完整阅读 ────────────────────────────────────────────────
    // 断言的是行为——正文能读到的高度、展开／收起入口该不该在、状态有没有落到别的那一条——
    // 而不是实现里的 maxLines 数值；换一种折叠写法这些用例应当照样成立。

    /** 一条铁定超过三行摘要的回放提问；[tag] 让两条长文在同一个列表里互不相同。 */
    private fun longQuestion(tag: String, segments: Int = 7) =
        (1..segments).joinToString("") { "第 $it 段：这条回放提问默认只显示三行摘要，必须能展开读到结尾。" } + "尾巴-$tag"

    @Test
    fun unansweredRow_longQuestion_expands_to_full_text_then_collapses_back() = runComposeUiTest {
        val text = longQuestion("A", segments = 14)
        // 无限高宿主（聊天流就是这么装它的）：展开内容参与外层滚动，卡片自己不许再开一层纵向滚动
        setContent {
            PocketTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { QuestionsUnansweredRow(text) }
            }
        }
        fun bodyHeight() = onAllNodes(hasText(text)).onFirst().fetchSemanticsNode().size.height
        val expand = runBlocking { getString(Res.string.questions_unanswered_expand) }
        val collapse = runBlocking { getString(Res.string.questions_unanswered_collapse) }

        val collapsedHeight = bodyHeight()
        onAllNodes(hasText(expand)).onFirst().assertIsDisplayed()
        onAllNodes(hasText(collapse)).assertCountEquals(0)

        onAllNodes(hasText(expand)).onFirst().performClick()
        waitForIdle()
        val expandedHeight = bodyHeight()
        assertTrue(
            expandedHeight > collapsedHeight * 2,
            "展开后正文应显著变高（读得到结尾）：collapsed=$collapsedHeight expanded=$expandedHeight",
        )
        onAllNodes(hasText(expand)).assertCountEquals(0)
        onAllNodes(hasText(collapse)).onFirst().assertIsDisplayed()

        onAllNodes(hasText(collapse)).onFirst().performClick()
        waitForIdle()
        assertEquals(collapsedHeight, bodyHeight(), "收起应回到原来的摘要高度")
    }

    @Test
    fun unansweredRow_shortQuestion_shows_no_expand_affordance() = runComposeUiTest {
        val text = "要不要继续？"
        setContent { PocketTheme { Column(Modifier.width(320.dp)) { QuestionsUnansweredRow(text) } } }
        onAllNodes(hasText(text)).onFirst().assertIsDisplayed()
        val expand = runBlocking { getString(Res.string.questions_unanswered_expand) }
        val collapse = runBlocking { getString(Res.string.questions_unanswered_collapse) }
        onAllNodes(hasText(expand)).assertCountEquals(0)
        onAllNodes(hasText(collapse)).assertCountEquals(0)
    }

    @Test
    fun unansweredRow_expansion_stays_on_its_own_message_in_a_lazy_list() = runComposeUiTest {
        val first = longQuestion("一")
        val second = longQuestion("二")
        setContent {
            PocketTheme {
                LazyColumn(Modifier.width(320.dp).height(600.dp)) {
                    item(key = "m:1") { QuestionsUnansweredRow(first) }
                    item(key = "m:2") { QuestionsUnansweredRow(second) }
                }
            }
        }
        fun heightOf(t: String) = onAllNodes(hasText(t)).onFirst().fetchSemanticsNode().size.height
        val expand = runBlocking { getString(Res.string.questions_unanswered_expand) }
        val collapse = runBlocking { getString(Res.string.questions_unanswered_collapse) }
        onAllNodes(hasText(expand)).assertCountEquals(2)
        val secondCollapsed = heightOf(second)

        onAllNodes(hasText(expand)).onFirst().performClick()
        waitForIdle()
        // 只有被点的那条展开：另一条仍是摘要，也仍带着自己的「展开」入口
        onAllNodes(hasText(collapse)).assertCountEquals(1)
        onAllNodes(hasText(expand)).assertCountEquals(1)
        assertEquals(secondCollapsed, heightOf(second), "另一条提问不该跟着展开")
        assertTrue(heightOf(first) > secondCollapsed, "被点开的那条才变高")
    }

    @Test
    fun answer_stays_disabled_until_every_question_is_answered() = runComposeUiTest {
        var fired = false
        setContent { PocketTheme { QuestionCard(ask(color, sections), onAnswer = { _, _ -> fired = true }, onSkip = {}) } }
        // answer only Q1 of 2 — the Answer button must not fire
        onAllNodes(hasText("Red")).onFirst().performClick()
        val answerLabel = runBlocking { getString(Res.string.question_answer) }
        onAllNodes(hasText(answerLabel)).onFirst().performClick()
        assertTrue(!fired, "Answer must be inert while a question is unanswered")
    }
}
