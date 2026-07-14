package dev.ccpocket.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
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
