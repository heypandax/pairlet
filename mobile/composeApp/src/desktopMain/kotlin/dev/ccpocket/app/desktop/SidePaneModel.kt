package dev.ccpocket.app.desktop

import dev.ccpocket.app.data.ChatItem
import dev.ccpocket.app.data.SidePane
import dev.ccpocket.app.ui.ComposerState
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PermissionMode

/**
 * A [DesktopModel] view of ONE split pane's conversation (issue #311).
 *
 * The point of the delegation is that a pane renders with the real [ChatPane] — same stream, same tool
 * cards, same composer, same approval card — instead of a second, thinner chat UI that would drift from
 * it. Everything conversation-scoped is answered from [pane]; everything else (connection, sidebar,
 * machines, settings, overlays) falls through to [base], because it describes the window, not the chat.
 *
 * What is deliberately inert here, rather than delegated: the verbs that would act on the FOCUSED
 * conversation while the user is looking at this one. Changing the model, compacting, rewinding or
 * opening Changes from a side column must not quietly reach into another session, and silently doing
 * the right thing for the wrong conversation is the one failure a split view must not have. They become
 * available the moment the pane is promoted (a click), which is also when they start meaning this
 * conversation. [PANE_INERT] marks every one of them.
 */
class SidePaneModel(
    private val base: DesktopModel,
    val pane: SidePane,
) : DesktopModel by base {

    override val composerState = ComposerState()

    override var composer: String
        get() = composerState.text
        set(v) = composerState.setText(v)

    // ── this pane's conversation ──────────────────────────────────────────────────────────────────
    override val hasChat: Boolean get() = !pane.opening.value || pane.messages.isNotEmpty()
    override val opening: Boolean get() = pane.opening.value
    override val openFailed: Boolean get() = pane.openFailed.value
    override val chatTitle: String get() = pane.title.value
    override val chatAgent: AgentKind get() = pane.agent
    override val chatWorkdir: String get() = pane.workdir
    override val chatBranch: String? get() = null
    override val chatModel: String get() = ""
    override val chatMode: PermissionMode get() = PermissionMode.DEFAULT
    override val messages: List<ChatItem> get() = pane.messages
    override val streaming: Boolean get() = pane.streaming.value
    override val sessionDegraded: Boolean get() = false
    override val contextUsed: Long? get() = null
    override val contextWindow: Long? get() = null
    override val observing: Boolean get() = false
    override val selectedSessionId: String get() = pane.sessionId

    /** The approval THIS session raised. Same frame the bell popover shows, so the two never disagree. */
    override val ask: PermissionAsk? get() = pane.pendingAsk.value
    override val askTimedOut: Boolean get() = false
    override val askQueuePosition: Pair<Int, Int>? get() = null
    override val askRisk: String? get() = null

    override fun send(text: String) {
        if (base.sendSidePrompt(pane, text)) composerState.clear()
    }

    /** Allow/Deny rides the machine-wide inbox verb, which is keyed by (convoId, askId) — so a decision
     *  made here is the same decision, taken the same way, as one made from the bell or the phone. */
    override fun resolve(allow: Boolean, remember: Boolean) {
        val a = pane.pendingAsk.value ?: return
        pane.pendingAsk.value = null
        base.resolvePaneApproval(a, allow)
    }

    override fun dismissAsk() {
        pane.pendingAsk.value = null
    }

    override val paneScoped: Boolean get() = true
    override fun focusThisPane() = base.promoteSplit(pane)
    override fun closeThisPane() = base.closeSplit(pane.paneId)

    // ── PANE_INERT: focused-conversation verbs, deliberately no-ops here (see the class doc) ─────────
    override val historyHasMore: Boolean get() = false
    override val historyLoadingOlder: Boolean get() = false
    override val historyPrependGen: Int get() = 0
    override val lastHistoryPrependCount: Int get() = 0
    override fun loadOlderHistory() {}
    override val sendUndelivered: Boolean get() = false
    override val turnStalled: Boolean get() = false
    override val turnQueued: Boolean get() = false
    override fun canRewind(item: ChatItem.User): Boolean = false
    override val changedFiles: List<dev.ccpocket.protocol.ChangedFile> get() = emptyList()
    override val gitStatus: dev.ccpocket.protocol.GitStatus? get() = null
    override val slashCommands: List<dev.ccpocket.protocol.SlashCommand> get() = emptyList()
    override val pathListing: dev.ccpocket.protocol.PathEntries? get() = null
    override fun browsePath(sub: String) {}
    override fun switchMode(m: PermissionMode) {}
    override fun switchMode(m: PermissionMode, permissionMode: String?) {}
    override fun switchModel(name: String) {}
    override fun switchEffort(level: String?) {}
    override fun switchServiceTier(tier: String?) {}
    override fun compactConversation() {}
    override fun clearConversation() {}
    override fun retryOpen() = base.retrySplitOpen(pane)
    override fun takeOver() {}
    override fun answerQuestions(answers: Map<String, String>?, response: String?) {}
    override fun workflowRunFor(item: ChatItem.Tool): dev.ccpocket.protocol.WorkflowRun? = null
    override fun openWorkspaceFile(path: String) {}
    override fun tightenAutoRun(item: ChatItem.AutoRun) {}
    override var showQuickActions: Boolean
        get() = false
        set(_) {}
    override var showChanges: Boolean
        get() = false
        set(_) {}
    override var showGit: Boolean
        get() = false
        set(_) {}
    override var showModelPopover: Boolean
        get() = false
        set(_) {}
}
