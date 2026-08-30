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
 * conversation. `PANE_INERT` marks every one of them.
 *
 * The hazard of `by base` is that it is SILENT: a member added to [DesktopModel] tomorrow is delegated
 * without anyone deciding it should be, and the first sign is a click in one column landing in another.
 * That is what took seven members through review here — stop, the four attachment verbs, and both M2
 * verdict verbs — so the contract is no longer a comment: `SidePaneModelDelegationGuardTest` enumerates
 * [DesktopModel]'s methods by reflection and fails until each one is filed under pane-scoped, inert, or
 * window-level. Add a member, classify it there.
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

    /** The approval — or question — THIS session raised. Same frame the bell popover shows for approvals,
     *  so the two never disagree; for questions this column is the only surface there is. */
    override val ask: PermissionAsk? get() = pane.pendingAsk.value

    /** issue #100 in a column: matched against the card actually on screen, exactly as the focused model
     *  matches its own id, so a stale timeout can never grey out the NEXT ask. */
    override val askTimedOut: Boolean
        get() = pane.timedOutAskId.value?.let { it == pane.pendingAsk.value?.askId } ?: false

    /**
     * "n / m" for this column's own burst.
     *
     * The focused path counts a burst UP (1/3 → 2/3) from counters it keeps across resolutions; a column
     * keeps no such history, so it reports what it can prove: this card, plus everything still waiting
     * behind it. Both answer the one question the chip exists for — "is there more after this one" — and
     * neither can outlive its queue.
     */
    override val askQueuePosition: Pair<Int, Int>?
        get() = pane.askQueue.size.takeIf { it > 0 }?.let { 1 to (1 + it) }

    override val askRisk: String? get() = null

    override fun send(text: String) {
        if (base.sendSidePrompt(pane, text)) composerState.clear()
    }

    /** ■ / Esc stops THIS column's turn. The bare `stopTurn` it used to inherit cancelled the focused
     *  conversation, i.e. it reliably interrupted the one turn the user had not asked it to. */
    override fun stopTurn() = base.stopSideTurn(pane)

    /** Every verdict names the ask THIS column is holding. Deciding by "whatever is pending" — which is
     *  what delegation did for the two M2 verbs below — showed this pane's card and answered another
     *  session's question, silently and with the user's own click. [remember] rides along: dropping it
     *  (as the first cut did) demoted 始终允许 to a one-off allow with no sign anything was lost. */
    override fun resolve(allow: Boolean, remember: Boolean) {
        val a = pane.pendingAsk.value ?: return
        base.resolvePaneApproval(a, allow, remember)
    }

    override fun resolveTaskGrant() {
        val a = pane.pendingAsk.value ?: return
        base.resolvePaneTaskGrant(a)
    }

    override fun retrySafer(constraints: List<String>) {
        val a = pane.pendingAsk.value ?: return
        base.retryPaneSafer(a, constraints)
    }

    /** Answering is a verdict like any other, addressed to THIS column's ask. Delegated, it read the
     *  repository's own pendingAsk: the column drew its question and answered the focused session's. */
    override fun answerQuestions(answers: Map<String, String>?, response: String?) {
        val a = pane.pendingAsk.value ?: return
        base.answerPaneQuestions(a, answers, response)
    }

    override fun skipQuestions(message: String) {
        val a = pane.pendingAsk.value ?: return
        base.skipPaneQuestions(a, message)
    }

    /** The timed-out card's Dismiss: nothing goes on the wire (the daemon already auto-denied), the card
     *  is simply retired and whatever queued behind it comes up. */
    override fun dismissAsk() {
        val a = pane.pendingAsk.value ?: return
        base.dismissPaneAsk(a)
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
    override fun resendStalled() {}
    // rewind/fork is a focused-pane affordance ([canRewind] false already hides the menu); these are its
    // BANNERS, which delegation would have filled with the focused conversation's lineage and refusals —
    // a "branched from …" line over a column that was never branched.
    override val rewindBlockedByTurn: Boolean get() = false
    override fun startRewind(item: ChatItem.User, mode: String) {}
    override val rewindError: String? get() = null
    override fun dismissRewindError() {}
    override val sessionLineage: dev.ccpocket.app.data.PocketRepository.SessionLineage? get() = null
    // Same story for the handoff banner ChatPane renders above the stream: delegated, a column showed the
    // FOCUSED session's handoff and its Recall/Cancel/Reviewed buttons acted on it. Nulling the handoff
    // itself is what closes that — the verbs are unreachable from a column once no banner is drawn.
    override val activeHandoff: dev.ccpocket.protocol.SessionHandoff? get() = null
    override fun handoffIsRecipient(): Boolean = false
    // ── attachments: the focused pane's, by construction (see SidePane's KDoc — "attachments … stay on
    //    the focused pane"). Delegated, the focused session's staged images/files rendered into EVERY
    //    column's composer, a paste or a drop inside a column hung its file on the focused session's next
    //    prompt, and an upload running over there disabled this column's send button ([uploadsBusy] gates
    //    submit). Doing visibly nothing here beats doing the right thing to the wrong conversation; the
    //    affordance comes back the moment the column is promoted, which is when it starts meaning this
    //    conversation.
    override val pendingImages: List<dev.ccpocket.app.data.PendingImage> get() = emptyList()
    override fun attachImages(raw: List<ByteArray>) {}
    override fun removePendingImage(id: Long) {}
    override fun hasReadyImages(): Boolean = false
    override val pendingFiles: List<dev.ccpocket.app.data.PendingFile> get() = emptyList()
    override fun attachFiles(files: List<dev.ccpocket.app.media.PickedFile>) {}
    override fun removePendingFile(id: Long) {}
    override fun retryPendingFile(id: Long) {}
    override fun uploadsBusy(): Boolean = false
    override fun hasLandedFiles(): Boolean = false
    // The AttentionLease heartbeat is keyed to the repository's OWN pendingAsk, so a column's card would
    // have extended the focused ask's reading budget instead of its own. Inert until a pane-scoped
    // heartbeat exists; the cost is only that a pane's card gets no lease extension (the daemon's
    // absolute deadline was always the real one), never a lease bought for the wrong ask.
    override fun askHeartbeat() {}
    override fun askHeartbeatRelease() {}
    override fun canRewind(item: ChatItem.User): Boolean = false
    override val chatModelId: String get() = ""
    override val chatPermissionMode: String? get() = null
    override val chatEffort: String? get() = null
    override val chatServiceTier: String? get() = null
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
