package dev.ccpocket.app.desktop

import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #311 — every [DesktopModel] member is explicitly filed as pane-scoped, inert, or window-level.
 *
 * [SidePaneModel] is a `DesktopModel by base` delegate, and the hazard of that keyword is that it is
 * SILENT: a member added to the interface tomorrow is forwarded to the FOCUSED conversation without
 * anyone deciding it should be, and the first sign is a click in one column acting on another session.
 * That is not hypothetical — it is how seven members shipped in the first cut (the stop button, all four
 * attachment verbs, and both approval-design-M2 verdict verbs), each of them a control that renders in a
 * column, reads that column's state, and then acted one column over.
 *
 * A comment on the class could not have caught it, so this does. The three sets below ARE the contract;
 * the test only checks that they cover [DesktopModel] exactly and do not overlap. Adding a member without
 * classifying it fails here, which is the point: the classification is the review.
 *
 * Where the line falls:
 *  - [PANE_SCOPED] — answered from [dev.ccpocket.app.data.SidePane]: this column's own conversation.
 *  - [PANE_INERT] — overridden to a neutral value / no-op ON PURPOSE, because delegating would describe
 *    or act on the focused conversation. Visibly nothing beats invisibly right-for-the-wrong-session.
 *  - [WINDOW_DELEGATED] — describes the WINDOW, the machine or the app (connection, sidebar, fleet,
 *    settings, overlays), so `by base` is not a leak but the only correct answer.
 *
 * [PANE_SCOPED] + [PANE_INERT] is, by construction, exactly [SidePaneModel]'s list of `override`s —
 * a one-line check when reviewing a change here, and the reason the sets cannot quietly drift from the
 * class. What the test itself cannot verify is which of the two a member belongs in: `by` synthesises a
 * forwarder for every member, so the delegate's bytecode looks identical either way (see [members]).
 *
 * The practical litmus for the third set: a [SidePaneModel] is handed to exactly one composable tree
 * ([SplitPane] → [ChatPane]). Anything ChatPane itself reads or calls therefore has to be pane-scoped or
 * inert. The rest — the Changes/Git/workflow/rewind overlays, the sidebar, Settings — is raised from
 * window chrome holding the BASE model, and `paneScoped` strips those controls out of a column's header,
 * so delegation there can never be reached with a pane. Wire a new verb into ChatPane and this set is
 * where you must NOT put it.
 */
class SidePaneModelDelegationGuardTest {

    /** This column's own conversation: read off [dev.ccpocket.app.data.SidePane], or acted on through a
     *  pane-taking verb on the base model (`sendSidePrompt` / `stopSideTurn` / `resolvePane…`). */
    private val PANE_SCOPED = setOf(
        "hasChat", "opening", "openFailed", "retryOpen",
        "chatTitle", "chatAgent", "chatWorkdir",
        "messages", "streaming", "selectedSessionId",
        "composerState", "composer", "send", "stopTurn",
        "ask", "resolve", "resolveTaskGrant", "retrySafer", "dismissAsk",
        // W3: the column's ask has a full life cycle of its own — its queue position, its issue-#100
        // terminal timeout state, and (for an AskUserQuestion, which the bell inbox deliberately never
        // carries) the answer/skip verbs. Inert, a column drew its own question and answered the FOCUSED
        // session's, or drew the focused burst's "2 / 3" over a single card of its own.
        "askTimedOut", "askQueuePosition", "answerQuestions", "skipQuestions",
        "paneScoped", "focusThisPane", "closeThisPane",
        // the column's own model: SidePane.model (its SessionLive) + the pane-keyed switch verb + a
        // pane-local popover flag. Inert, the chip read "默认" and its click no-opped; DELEGATED, the
        // popover opened on the focused composer and a pick landed on the focused session.
        "chatModel", "chatModelId", "switchModel", "showModelPopover",
    )

    /** Deliberately inert. Each one is a control ChatPane renders (or a value it renders FROM) whose
     *  delegated answer would have come from — or landed on — the focused conversation instead. */
    private val PANE_INERT = setOf(
        // conversation identity a column does not track (mode/effort/tier switching stays with the
        // focused pane; the MODEL graduated to pane-scoped above once SidePane learned to carry it)
        "chatBranch", "chatMode",
        "chatPermissionMode", "chatEffort", "chatServiceTier",
        "sessionDegraded", "contextUsed", "contextWindow", "observing", "takeOver",
        // transcript paging + delivery watchdogs: a column has neither, and the focused pane's answers
        // would put another session's loader/warning/resend cue over this stream
        "historyHasMore", "historyLoadingOlder", "historyPrependGen", "lastHistoryPrependCount",
        "loadOlderHistory", "sendUndelivered", "turnStalled", "turnQueued", "resendStalled",
        // rewind/fork: the menu is already hidden by canRewind=false; these are its BANNERS
        "canRewind", "rewindBlockedByTurn", "startRewind", "rewindError", "dismissRewindError",
        "sessionLineage",
        // the handoff banner ChatPane draws above the stream, with verbs that acted on the focused session
        "activeHandoff", "handoffIsRecipient",
        // header/composer surfaces that belong to the focused conversation
        "changedFiles", "gitStatus", "slashCommands", "pathListing", "browsePath",
        "switchMode", "switchEffort", "switchServiceTier",
        "compactConversation", "clearConversation",
        "workflowRunFor", "openWorkspaceFile", "tightenAutoRun",
        "showQuickActions", "showChanges", "showGit",
        // attachments are the focused pane's by construction (SidePane's KDoc); uploadsBusy() even gated
        // this column's send button on the OTHER session's uploads
        "pendingImages", "attachImages", "removePendingImage", "hasReadyImages",
        "pendingFiles", "attachFiles", "removePendingFile", "retryPendingFile",
        "uploadsBusy", "hasLandedFiles",
        // the approval card: the ask is this column's, but these three reach the repository's own
        // pendingAsk, i.e. the FOCUSED one — an advisory risk badge read off another session's assessment,
        // and a lease heartbeat that bought reading time for another session's ask.
        "askRisk", "askHeartbeat", "askHeartbeatRelease",
    )

    /** The window, the machine, the app. None of these is reachable holding a [SidePaneModel] — see the
     *  class doc's litmus — and every one of them means the same thing in a column as anywhere else. */
    private val WINDOW_DELEGATED = setOf(
        // window chrome (desktop chrome v2): whether the sidebar is collapsed, and the session back/forward
        // history behind ⌘[ / ⌘]. Both describe the WINDOW — one trail per window, not per column — and both
        // are read by the chrome cluster the leftmost column's sub-header adopts while the sidebar is hidden.
        // They are the exception to "PANE_SCOPED + PANE_INERT == SidePaneModel's overrides": the class
        // re-delegates them EXPLICITLY because they are the only members here with an interface default,
        // i.e. the one shape where a silent `by` hand-off could answer with the inert default instead.
        "sidebarCollapsed", "canGoBack", "canGoForward", "goBack", "goForward",
        // connection + computer switcher
        "connected", "connGen", "activeComputer", "computers", "selectComputer", "addComputer",
        "renameComputer", "removeComputer", "activeIsThisMachine",
        // window-level overlay flags
        "switcherOpen", "showNewSession", "showTray", "palette", "showSettings", "showAddComputer",
        "showPermissionModal", "showAttention", "showWorktrees", "showSkills", "showHandoff",
        "showReviewCenter", "showFolderPicker", "showQuotaPopover",
        "anyOverlayOpen", "dismissOverlays",
        // session handoff + collaborator links (raised from window chrome; the pane's banner is inert)
        "handoffInvite", "handoffCreating", "handoffError", "handoffIsInitiator", "handoffCreate",
        "handoffCancel", "handoffRecall", "handoffComplete", "handoffReturn", "dismissHandoffInvite",
        "collaborators", "collaboratorTicket", "lastCollaboratorConnected", "collaboratorError",
        "listCollaborators", "createCollaboratorTicket", "removeCollaborator",
        // Review Center + usage dashboard (the live repository, handed over whole)
        "reviewRepo", "usageRepo", "reviewPending", "openReviewCenter", "refreshReviews",
        // sidebar: pins, projects, sessions, groups, archive, rename, RECENT
        "pins", "pin", "unpin", "movePin", "openPin", "jumpPin", "isPinned", "pinsFull",
        "projectPins", "pinProject", "unpinProject", "isProjectPinned", "projectListReveal",
        "openProjectPin", "browseProjects",
        "projects", "sessions", "openProject", "selectSession", "hideSession",
        "sessionGroups", "clearRecent", "sessionsRefreshing", "refresh", "liveSession",
        "customGroups", "canEditGroups", "createGroup", "renameGroup", "deleteGroup", "assignGroup",
        "groupCollapsed",
        "archivedSessions", "canArchiveSessions", "archiveSession", "unarchiveSession",
        "refreshArchived", "browseArchived",
        "canRenameSessions", "renameSession", "renameError", "dismissRenameError",
        // the rewind CONFIRMATION lives in window chrome (the sheet), unlike the banners above
        "rewindSheet", "confirmRewind", "cancelRewind",
        // new-session flows (they create a session; they do not act on one)
        "newSessionDir", "newSessionSeed", "openNewSession", "newSession", "openFolderPath",
        "newSessionPrompt", "startingSession", "newSessionPromptError", "startSessionWithPrompt",
        "dismissNewSessionPromptError",
        // remote directory browser (issues #218/#214)
        "browseListing", "browseRoots", "browseDirectories", "requestBrowse",
        // fleet
        "machines", "attention", "watch", "resolveAttention", "jumpMachine",
        "running", "runningVisible", "openRunning", "browseRunning",
        // split-pane plumbing itself: these take the pane as an argument, so they are already explicit
        // (splitFocusedSlot is window layout — WHERE the focused chat renders — not conversation state)
        "sidePanes", "canSplit", "splitFocusedSlot", "openInSplit", "closeSplit", "promoteSplit", "retrySplitOpen",
        "sendSidePrompt", "stopSideTurn", "switchSideModel", "resolvePaneApproval", "resolvePaneTaskGrant", "retryPaneSafer",
        "answerPaneQuestions", "skipPaneQuestions", "dismissPaneAsk",
        // workflow orchestration panel (docked at window level)
        "workflowRuns", "dockedWorkflowRunId", "openWorkflowPanel", "closeWorkflowPanel",
        "workflowAgentDetails", "fetchWorkflowAgentDetail",
        // capability tables — machine/daemon facts, not conversation state
        "effortOptions", "serviceTierOptions", "effortOptionsFor", "serviceTierOptionsFor",
        "permissionModeAvailable", "modelsForAgent", "fetchModels", "pathSep",
        "gatewayBaseUrl", "gatewayModels", "availableAgents",
        // Changes browser / Git panel / worktrees: overlays, raised from the focused header only
        "changedFilesLoading", "changedFilesStale", "fetchChangedFiles", "selectedChangedPath",
        "selectedDiff", "selectedContent", "selectedContentProgress", "selectChangedFile", "openChanges",
        "gitStatusLoading", "gitStatusStale", "gitDiff", "gitDiffPath", "gitDiffStaged", "gitBusyOp",
        "gitError", "gitPendingConfirm", "fetchGitStatus", "openGitDiff", "gitAct", "confirmPendingGit",
        "dismissGitConfirm", "dismissGitError", "openGit",
        "worktrees", "worktreesLoading", "worktreesStale", "fetchWorktrees", "addWorktree",
        "worktreeCreated", "dismissWorktreeCreated", "openWorktreeSession", "removeWorktree",
        "openWorktrees",
        // machine catalogs
        "skillCatalog", "skillCatalogLoading", "skillCatalogStale", "fetchSkillCatalog", "openSkills",
        "bridges", "bridgesLoaded", "bridgesStale", "bridgeBusy", "bridgeError", "bridgeMergeLost",
        "bridgeCredential", "fetchBridges", "createBridge", "revokeBridge", "controlBridgeRunner",
        "configureBridgeRunner", "clearBridgeCredential",
        // app + self-update
        "appVersion", "relayUrl", "updateState", "daemonVersion", "daemonUpdateCommand",
        "checkForUpdates", "applyUpdate", "updateCommand", "updateReleasesUrl",
        // settings / preferences
        "defaultAgent", "defaultMode", "defaultPermissionMode", "defaultEffort", "defaultServiceTier",
        "defaultModelFor", "contextWindowOverride", "terminalApp", "terminalDefaultEmbedded",
        "terminalPanel", "menuBarEnabled", "themeMode", "accentTheme", "chatAlignment",
        "phonePush", "refreshPushPrefs",
        "approvalNoAutoDeny", "refreshApprovalPrefs", "approvalFullControlExpiryMs",
        "fullControlExpiryMs",
        // folder-share + schedules
        "shares", "sharesLoaded", "lastShareInvite", "refreshShares", "createShare", "revokeShare",
        "clearLastShare", "redeemShareInvite",
        "schedules", "schedulesLoaded", "schedulesStale", "refreshSchedules", "cancelSchedule",
        // account + API presets
        "authState", "refreshAuth", "switchAccount", "stopAuthBlocker", "submitAuthCode",
        "cancelAuthLogin", "logoutAccount",
        "presetsState", "presetsRev", "refreshPresets", "savePreset", "deletePreset", "activatePreset",
        "stopPresetBlocker", "stopPresetDeleteBlocker",
    )

    @Test
    fun everyDesktopModelMemberIsClassified() {
        val classified = PANE_SCOPED + PANE_INERT + WINDOW_DELEGATED
        val unclassified = (members() - classified).sorted()
        assertTrue(
            unclassified.isEmpty(),
            "New DesktopModel member(s) with no split-pane decision: $unclassified\n" +
                "A `by base` delegate forwards them to the FOCUSED conversation silently. Decide what " +
                "each one means inside a split column, override it in SidePaneModel if that answer is " +
                "not `base`, and add it to PANE_SCOPED / PANE_INERT / WINDOW_DELEGATED here.",
        )
    }

    @Test
    fun theThreeSetsDoNotOverlapAndDescribeNothingThatIsGone() {
        assertTrue(
            (PANE_SCOPED intersect PANE_INERT).isEmpty() &&
                (PANE_SCOPED intersect WINDOW_DELEGATED).isEmpty() &&
                (PANE_INERT intersect WINDOW_DELEGATED).isEmpty(),
            "a member is filed twice: " + listOf(
                PANE_SCOPED intersect PANE_INERT,
                PANE_SCOPED intersect WINDOW_DELEGATED,
                PANE_INERT intersect WINDOW_DELEGATED,
            ).flatten().sorted(),
        )
        // A renamed or deleted member must not leave its old classification behind as reassuring dead
        // text — the sets are only a contract while they still name the interface.
        val stale = ((PANE_SCOPED + PANE_INERT + WINDOW_DELEGATED) - members()).sorted()
        assertTrue(stale.isEmpty(), "classified name(s) that DesktopModel no longer has: $stale")
    }

    /**
     * Every public method of the interface, property accessors included, as Kotlin member names.
     *
     * Reflection is over [DesktopModel], never over [SidePaneModel]: `by` synthesises a forwarder for
     * EVERY member, so the delegate's own bytecode cannot tell an explicit override from an implicit
     * hand-off. The sets above are the contract precisely because the compiler has no way to be.
     */
    private fun members(): Set<String> =
        DesktopModel::class.java.methods
            .filterNot { it.isSynthetic || '$' in it.name }
            .map(::memberName)
            .toSet() - setOf("equals", "hashCode", "toString")

    /** `getFoo`/`setFoo` → `foo`, so the sets read as the Kotlin source does. A `setX(…)` FUNCTION folds
     *  into the `x` property's entry, which is the right granularity: both are the same member family
     *  and must get the same answer. `isFoo` is left alone — Kotlin only emits it for a property already
     *  spelled that way, and this interface's `is…` names are plain functions. */
    private fun memberName(m: Method): String {
        val n = m.name
        val head = when {
            n.length > 3 && n[3].isUpperCase() && (n.startsWith("get") || n.startsWith("set")) -> n.substring(3)
            else -> return n
        }
        return head.replaceFirstChar { it.lowercase() }
    }
}
