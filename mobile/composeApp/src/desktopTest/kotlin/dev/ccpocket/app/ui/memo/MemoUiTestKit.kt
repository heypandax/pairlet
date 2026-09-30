package dev.ccpocket.app.ui.memo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.memo.MemoBlock
import dev.ccpocket.app.memo.MemoDispatchBlock
import dev.ccpocket.app.memo.MemoDocumentState
import dev.ccpocket.app.memo.MemoListState
import dev.ccpocket.app.memo.MemoListStatus
import dev.ccpocket.app.memo.MemoNewSessionOptions
import dev.ccpocket.app.memo.MemoProjectRow
import dev.ccpocket.app.memo.MemoProjectSessions
import dev.ccpocket.app.memo.MemoTargetCatalog
import dev.ccpocket.app.memo.MemoProcessingState
import dev.ccpocket.app.memo.MemoReadiness
import dev.ccpocket.app.memo.MemoScope
import dev.ccpocket.app.memo.MemoScreen
import dev.ccpocket.app.memo.MemoSelectionState
import dev.ccpocket.app.memo.MemoStep
import dev.ccpocket.app.memo.MemoStepRow
import dev.ccpocket.app.memo.MemoStepStatus
import dev.ccpocket.app.memo.MemoTarget
import dev.ccpocket.app.memo.MemoTargetRow
import dev.ccpocket.app.memo.MemoTargetStatus
import dev.ccpocket.app.memo.MemoTimings
import dev.ccpocket.app.memo.MemoTodoRow
import dev.ccpocket.app.memo.MemoTodoState
import dev.ccpocket.app.memo.MemoUiState
import dev.ccpocket.app.theme.PocketTheme
import dev.ccpocket.app.ui.LocalReduceMotion
import dev.ccpocket.protocol.AgentKind
import kotlin.test.assertTrue

// Shared scene + fixtures for the voice-memo UI tests. `Density(1f, fontScale)` makes one scene pixel one dp,
// so the viewport assertions double as the overflow proof at 100 % and at 200 % type. Reduced motion is on
// so no infinite spinner / pulse keeps the clock busy — the settled frame is what is asserted.

internal const val PHONE_W = 390
internal const val PHONE_H = 844
internal const val SMALL_W = 320
internal const val SMALL_H = 693

@OptIn(ExperimentalTestApi::class)
internal fun memoScene(
    width: Int = PHONE_W,
    height: Int = PHONE_H,
    fontScale: Float = 1f,
    autoAdvance: Boolean = true,
    content: @Composable () -> Unit,
    assertions: SkikoComposeUiTest.() -> Unit,
) = runDesktopComposeUiTest(width, height) {
    mainClock.autoAdvance = autoAdvance
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale), LocalReduceMotion provides true) {
            PocketTheme { content() }
        }
    }
    waitForIdle()
    assertions()
}

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.count(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.has(text: String, substring: Boolean = false): Boolean = count(text, substring) > 0

internal val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

/** The button nodes carrying exactly [text]. */
@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.buttons(text: String) = onAllNodes(isButton and hasText(text))

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.buttonCount(text: String): Int = buttons(text).fetchSemanticsNodes().size

/** Invoke the node's own onClick (house style: independent of hit-testing / scroll position). */
internal fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.tapText(text: String, last: Boolean = false) {
    val nodes = onAllNodes(hasText(text))
    (if (last) nodes.onLast() else nodes.onFirst()).tap()
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.tapDescription(description: String) {
    onAllNodes(hasContentDescription(description)).onFirst().tap()
    waitForIdle()
}

/** Every text and content description on screen — for "no raw %" style sweeps. */
@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.allScreenText(): List<String> =
    onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

/** The node's full box lies inside the [w] × [h] viewport. */
@OptIn(ExperimentalTestApi::class)
internal fun SkikoComposeUiTest.assertInViewport(node: SemanticsNodeInteraction, what: String, w: Int, h: Int) {
    val b = node.getUnclippedBoundsInRoot()
    assertTrue(b.left.value >= -0.5f && b.right.value <= w + 0.5f, "$what spills sideways: ${b.left}..${b.right} in $w")
    assertTrue(b.top.value >= -0.5f && b.bottom.value <= h + 0.5f, "$what is outside the visible height: ${b.top}..${b.bottom} in $h")
}

internal object MemoFx {
    val scope = MemoScope("bind-1", "dev-1")

    fun readiness(
        block: MemoBlock = MemoBlock.NONE,
        online: Boolean = true,
        name: String = "alex-mac",
        organizer: String? = "claude",
        defaultAgent: String = "claude",
    ) = MemoReadiness(
        featureOn = true, scope = scope, computerName = name, online = online, block = block,
        organizer = organizer, organizers = listOfNotNull(organizer), defaultAgent = defaultAgent,
    )

    val target = MemoTarget("bind-1", "s1", "/Users/alex/code/pairlet", AgentKind.CLAUDE, project = "Pairlet", title = "移动端构建排查")
    val targetRow = MemoTargetRow(target, MemoTargetStatus.IDLE)

    fun todo(id: String, text: String, state: String = MemoTodoState.DRAFT, selected: Boolean = true) =
        MemoTodoRow(todoId = id, text = text, selected = selected && state == MemoTodoState.DRAFT, state = state)

    val threeDrafts = listOf(
        todo("t1", "检查 mobile build 失败的原因，先给出诊断和修复建议。"),
        todo("t2", "补充 README 中的本地启动步骤，保留现有配置示例。"),
        todo("t3", "检查发布前清单，只报告遗漏项，暂时不要发布。"),
    )

    fun doc(
        todos: List<MemoTodoRow> = threeDrafts,
        degraded: Boolean = false,
        summary: String? = "整理了构建排查、文档补充和发布检查三件事。",
        organizerAvailable: String? = "claude",
    ) =
        MemoDocumentState(
            memoId = "m1",
            title = "出门想到的三件事",
            titleFallback = "嗯，出门前想到三件事",
            createdAtMs = epochMillis() - 60_000,
            audioDurationMs = 75_000,
            summary = summary,
            summaryUnstructured = degraded && summary != null,
            degraded = degraded,
            // an organised memo names its organiser; a degraded one does not (contract)
            organizedBy = if (!degraded && summary != null) "claude" else null,
            organizerAvailable = organizerAvailable,
            wholeTranscriptFits = true,
            todos = todos,
            transcript = "嗯，出门前想到三件事。",
            timings = MemoTimings(audioDurationMs = 75_000, uploadMs = 1_200, transcribeMs = 8_400, summarizeMs = 4_700, totalMs = null),
        )

    const val RAW_TRANSCRIPT = "嗯，出门前想到三件事。第一，mobile 的 build 昨天晚上又失败了，先帮我看一下是什么原因。"

    /** A transcribe-only memo (v3.1 "未整理"): no title of its own, no summary, no organiser on record. */
    fun rawDoc(
        todos: List<MemoTodoRow> = emptyList(),
        organizerAvailable: String? = null,
        organizerLost: Boolean = false,
        wholeTranscriptFits: Boolean = true,
    ) = MemoDocumentState(
        memoId = "m1",
        title = "",
        titleFallback = "嗯，出门前想到三件事",
        createdAtMs = epochMillis() - 60_000,
        audioDurationMs = 75_000,
        summary = null,
        summaryUnstructured = false,
        degraded = false,
        organizedBy = null,
        organizerLost = organizerLost,
        organizerAvailable = organizerAvailable,
        wholeTranscriptFits = wholeTranscriptFits,
        todos = todos,
        transcript = RAW_TRANSCRIPT,
        timings = MemoTimings(audioDurationMs = 75_000, uploadMs = 1_200, transcribeMs = 8_400, summarizeMs = null, totalMs = 9_600),
    )

    /** The result page of a transcribe-only memo, with the readiness organiser matching [organizerAvailable]. */
    fun rawDetail(doc: MemoDocumentState = rawDoc()) = MemoUiState(
        readiness = readiness(organizer = doc.organizerAvailable),
        screen = MemoScreen.DETAIL,
        list = MemoListState(loaded = true),
        document = doc,
        selection = MemoSelectionState(block = MemoDispatchBlock.NO_SELECTION),
        catalog = catalog(),
    )

    /** A ready-to-dispatch selection of every draft with text. */
    fun readySelection(todos: List<MemoTodoRow>) = MemoSelectionState(
        target = targetRow,
        items = todos.filter { it.state == MemoTodoState.DRAFT && it.selected && it.text.isNotBlank() },
        block = MemoDispatchBlock.NONE,
    )

    // ── target catalog (brief sample: Pairlet / docs-site / scratch) ──
    const val PAIRLET = "/Users/alex/code/pairlet"
    const val DOCS = "/Users/alex/code/docs-site"
    const val SCRATCH = "/Users/alex/code/scratch"

    fun session(id: String, title: String, status: MemoTargetStatus, workdir: String = PAIRLET, project: String = "Pairlet", agent: AgentKind = AgentKind.CLAUDE, mode: String? = null, modified: Long = 0) =
        MemoTargetRow(MemoTarget("bind-1", id, workdir, agent, project = project, title = title), status, mode = mode, lastModifiedMs = modified)

    val running = session("s2", "文档整理", MemoTargetStatus.RUNNING)
    val observing = session("s4", "依赖升级", MemoTargetStatus.OBSERVING)
    val docsSession = session("s5", "站点迁移", MemoTargetStatus.IDLE, DOCS, "docs-site", AgentKind.CODEX)

    val projects = listOf(
        MemoProjectRow(PAIRLET, "Pairlet", sessionCount = 3, running = true),
        MemoProjectRow(DOCS, "docs-site", sessionCount = 1),
        MemoProjectRow(SCRATCH, "scratch", sessionCount = 0),
        MemoProjectRow("/Users/alex/code/uncounted", "uncounted", sessionCount = null),
    )

    val newOptions = MemoNewSessionOptions(agents = listOf(AgentKind.CLAUDE, AgentKind.CODEX), defaultAgent = AgentKind.CLAUDE, mode = "DEFAULT")

    fun catalog(
        status: MemoListStatus = MemoListStatus.READY,
        recent: List<MemoTargetRow> = listOf(running, targetRow, docsSession),
        projects: List<MemoProjectRow> = this.projects,
        project: MemoProjectSessions? = null,
        newSession: MemoNewSessionOptions = newOptions,
    ) = MemoTargetCatalog(status, recent, projects, project, newSession)

    fun pairletLevel(status: MemoListStatus = MemoListStatus.READY, sessions: List<MemoTargetRow> = listOf(running, targetRow.copy(lastModifiedMs = epochMillis() - 3_600_000), observing)) =
        MemoProjectSessions(PAIRLET, "Pairlet", status, sessions)

    /** "New session in scratch on Codex" as the selected target. */
    val newTarget = MemoTargetRow(MemoTarget("bind-1", "", SCRATCH, AgentKind.CODEX, project = "scratch", newSession = true), MemoTargetStatus.IDLE)

    fun detail(
        todos: List<MemoTodoRow> = threeDrafts,
        selection: MemoSelectionState = readySelection(todos),
        readiness: MemoReadiness = readiness(),
        catalog: MemoTargetCatalog = catalog(),
    ) = MemoUiState(
        readiness = readiness,
        screen = MemoScreen.DETAIL,
        list = MemoListState(loaded = true),
        document = doc(todos),
        selection = selection,
        catalog = catalog,
    )

    fun steps(upload: MemoStepStatus, transcribe: MemoStepStatus, organize: MemoStepStatus) = listOf(
        MemoStepRow(MemoStep.UPLOAD, upload, if (upload == MemoStepStatus.DONE) 1_200 else null),
        MemoStepRow(MemoStep.TRANSCRIBE, transcribe, if (transcribe == MemoStepStatus.DONE) 8_400 else null),
        MemoStepRow(MemoStep.ORGANIZE, organize, if (organize == MemoStepStatus.DONE) 4_700 else null),
    )

    fun processing(p: MemoProcessingState) = MemoUiState(
        readiness = readiness(), screen = MemoScreen.PROCESSING, list = MemoListState(loaded = true), processing = p,
    )
}
