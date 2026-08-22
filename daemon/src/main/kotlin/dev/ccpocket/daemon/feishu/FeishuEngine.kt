package dev.ccpocket.daemon.feishu

import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler
import com.lark.oapi.event.cardcallback.model.CallBackToast
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeDenyCode
import dev.ccpocket.daemon.bridge.BridgeGuard
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.BridgeVerdict
import dev.ccpocket.daemon.bridge.InProcessBridgeEngine
import dev.ccpocket.daemon.util.logger
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.OpenSession
import dev.ccpocket.protocol.PermissionAsk
import dev.ccpocket.protocol.PocketError
import dev.ccpocket.protocol.SendPrompt
import dev.ccpocket.protocol.SessionLive
import dev.ccpocket.protocol.TurnDone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Pure chat-facing receipt kept outside the engine so the broad TRUSTED promise has regression tests. */
internal fun trustedEnabledReply(projectName: String): String =
    "✅ 已完全信任「$projectName」：群成员的每条请求会获得整轮 full 权限，不经 Guardian，也不再逐工具等待机主批准。\n" +
        "本轮可运行未被确定性规则拦截的 Bash、MCP、网络工具和子代理，可能访问项目外数据或向外发送数据。\n" +
        "需要人类作答或确认的交互工具仍会询问；授权在每轮结束时撤销，由下一条受信请求重新取得。\n" +
        "⚠️ 受信请求可写入 .git/hooks、.claude/、CLAUDE.md、.envrc 等位置，这些内容会在你之后的会话或终端里自动执行，不随 /untrust 失效。\n" +
        "换绑到别的项目会自动失效；随时 /untrust 撤销，撤销从下一条请求生效。"

/**
 * The irreversible in-process bridge teardown sequence, isolated so its late-handler boundary can be
 * regression-tested without constructing an SDK client. Once ingress is quiesced, cancellation is not
 * enough by itself: a handler may already be inside non-cancellable router work. We therefore JOIN the
 * entire handler tree before sweeping origin conversations. The remainder is NonCancellable so cancellation
 * of the owner control request cannot strand a half-revoked authority between those two steps.
 */
internal suspend fun revokeAfterHandlerDrain(
    quiesceIngress: () -> Unit,
    handlerJob: Job,
    closeOriginConversations: suspend () -> Unit,
    releaseResources: () -> Unit,
) {
    quiesceIngress()
    withContext(NonCancellable) {
        handlerJob.cancelAndJoin()
        try {
            closeOriginConversations()
        } finally {
            releaseResources()
        }
    }
}

/**
 * The BUILT-IN Feishu bridge (issue #91 follow-up): the daemon itself holds the Feishu event
 * long-connection and drives sessions in-process — no python, no pip, no script path. The owner fills
 * three things (name, projects, app credentials) and it runs.
 *
 * Security posture is IDENTICAL to an external bridge, enforced by the same code: every open/prompt is
 * vetted by the same [BridgeGuard] (workdir allow-list, tier mode-ceiling, rate/concurrency caps), turns
 * route with `origin = name` so sessions wear the "via <name>" tag and permission asks push to the
 * OWNER's phone urgently. What an external adapter is denied by the egress whitelist, this engine simply
 * never does: it reads exactly [SessionLive] / [TurnDone] / [PocketError] off its sink and drops the
 * rest — in particular it never answers a PermissionAsk. The transport (relay E2E, redeem, tickets) is
 * gone because there is no wire to cross; the AUTHORITY model is untouched.
 *
 * One engine per managed built-in bridge, owned by BridgeRunners (start/stop/restart/state map 1:1 onto
 * the same runner surface the desktop and phone already render).
 */
class FeishuEngine(
    private val name: String,
    private val spec: BridgeSpec,
    env: Map<String, String>,
    private val core: DaemonCore,
    stateDir: File,
    /** log lines flow here — the runner's ring buffer, i.e. the bridge card's "adapter log". */
    private val logLine: (String) -> Unit,
) : InProcessBridgeEngine {
    private val log = logger("FeishuEngine")
    private val appId = env["FEISHU_APP_ID"].orEmpty()
    private val appSecret = env["FEISHU_APP_SECRET"].orEmpty()
    private val adminOpenId = env["FEISHU_ADMIN_OPEN_ID"]
    // OWNER BYPASS (issue #91): when ON and a message's sender == the configured owner (adminOpenId), that
    // turn runs with NO approval (full trust). Default off; meaningless without an adminOpenId set. Only the
    // BUILT-IN engine may claim this — it reads feishu's attested sender directly (an external adapter can't).
    private val ownerBypassEnabled = env["FEISHU_OWNER_BYPASS"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
    // NO-APPROVAL (issue #198): the owner's MASTER enable for letting OTHER members' requests run without the
    // per-request card. Off by default, and on its own it trusts nothing — each chat still has to be marked
    // with /trust by the machine owner. Two conditions, because a single one would make "I turned it on to try
    // it" silently apply to every group the bot happens to sit in.
    private val noApprovalEnabled = env["FEISHU_NO_APPROVAL"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
    // SHADOW rollout for the reviewed mode (design §13): reviews run and are audited, but nothing
    // auto-passes — every request still reaches the owner. Off by default; an internal calibration knob.
    private val reviewShadowOnly = env["FEISHU_REVIEW_SHADOW"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
    private val routes = FeishuRoutes(File(stateDir, "feishu-routes.json"))
    private val trust = FeishuTrust(File(stateDir, "feishu-trust.json"))
    // the durable half of the no-approval trail — see FeishuTrustLog on why the runner's log ring isn't enough
    private val trustLog = FeishuTrustLog(File(stateDir, "feishu-trust.log"))
    // structured audit for the REVIEWED flow (design §10) — hashes + outcomes, never prompt text
    private val reviewLog = FeishuReviewLog(File(stateDir, "feishu-review.log"))
    // one-shot claude -p per review, cwd'd into the bridge's own state dir so no project material is in
    // reach. Lazy: an engine on a machine that never uses /review must not pay the resolver.
    private val reviewPreflight by lazy {
        ReviewedPreflight(
            // core.claudeRuntime = the MAIN backend's binary/credentials/preset routing (§21.2 P1-1) — a
            // reviewer resolving its own claude would diverge on all three
            ClaudeFeishuPromptReviewer(File(stateDir, "reviewer"), core.claudeRuntime),
            reviewLog,
            shadowOnly = reviewShadowOnly,
            // the owner's zero-click Bash allowlist rides into the review input (§21.6 P2-1), so the
            // Guardian judges "low risk" against the ceiling this bridge ACTUALLY runs with
            allowedCommands = spec.allowedCommands,
        )
    }
    // one-shot claude -p per auto-routing decision (/bind auto), same clean-room posture as the reviewer:
    // cwd'd into the bridge's own state dir, no tools, no MCP. Lazy: a bridge with no auto chat never pays.
    private val projectRouter: FeishuProjectRouter by lazy {
        ClaudeFeishuProjectRouter(File(stateDir, "router"), core.claudeRuntime)
    }
    // bounded per-project summaries (CLAUDE.md/README head) fed to the router as candidate descriptions —
    // cached for the engine's lifetime; a restart or reconfigure re-reads them.
    private val projectSummaries = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val commands = FeishuCommands(
        routes, spec.workdirs, adminOpenId,
        chatOwnerOf = { chatOwners[it] },
        noApprovalEnabled = noApprovalEnabled,
        // the stored record, whatever project it names — FeishuCommands compares it against the CURRENT
        // binding itself, so a chat rebound to another project reads as untrusted (no silent inheriting)
        // while its stale record stays revocable and visible to /trust-status
        trustRecordOf = { trust.recordFor(it) },
    )
    private val guard = BridgeGuard(spec)

    private val handlerJob = SupervisorJob()
    private val scope = CoroutineScope(handlerJob + Dispatchers.IO)
    private var ws: com.lark.oapi.ws.Client? = null
    // One bounded SDK HTTP client for this engine's whole lifetime. A managed stop/start retains it; bridge
    // removal/reconfigure calls revokeAndShutdown(), which drains handlers and closes its connection pool.
    private var api: FeishuApiClient? = null

    // conversation state: one conversation per direct chat, or per group TOPIC (#234) — see onMessage.
    // convo -> session survives idle-reap so a later message in the same topic resumes with full context.
    private val mutex = Mutex()
    private val convoByKey = LruMap<String>(TOPIC_STATE_MAX) { key, convoId -> evictConversation(key, convoId) }
    // the workdir each key's convo was opened against. A /bind can move a chat to another project mid-life,
    // and the chat's key still maps to the OLD project's convo — reusing/resuming it would keep sending
    // prompts to the old workdir (the "rebind took no effect until restart/​/new" bug). openOrReuse compares
    // this against the now-requested workdir and, on a mismatch, opens CLEAN instead of reusing or resuming.
    private val keyWorkdir = LruMap<String>(TOPIC_STATE_MAX)
    // a DIRECT unified-inbox chat's CURRENT project (chatId -> workdir) — the continuation bias: a
    // follow-up with no routing signal stays here, an explicit/clear other-project request switches. /new
    // clears it. In-memory like keyWorkdir: a daemon restart just means the next message re-routes.
    private val activeProject = HashMap<String, String>()
    // convoId -> Claude session id. Normally reaped together with its conversation key (see
    // [evictConversation]), but an open that TIMED OUT never got recorded under any key, so this one is
    // bounded on its own too — an evicted entry just means the next message opens clean instead of resuming.
    private val sessionOf = LruMap<String>(TOPIC_STATE_MAX)
    private val turnWaiters = HashMap<String, CompletableDeferred<TurnDone>>()
    private val openWaiters = HashMap<String, CompletableDeferred<String>>() // openId -> convoId
    // A completed Feishu turn keeps its transcript/session id but must not keep a live CLI forever: bridge
    // maxSessions is a concurrent-REQUEST limit, not a "number of chats ever used" limit. A release job waits
    // through background work / continuation grace, then closes the warm process. A new message in the same
    // chat cancels the job and reuses the process if it wins the race; otherwise openOrReuse resumes by sid.
    private val releaseJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    // a turn's reply target + a one-shot guard: the final text posts to the group EXACTLY ONCE, whether
    // ask() delivers it inline (finished within the wait) or a late TurnDone does (the owner approved a
    // permission ask on their phone minutes later — the bridge's whole reason to exist). Keyed by convoId.
    /** [promptId]：这个槽位等的是哪条请求（issue #285 归属门的钥匙）。null = 老路径没铸 id 的槽位，
     *  归属门放行一切（等价旧行为）；非 null 时，只有拿到该 prompt 消费凭证之后的终态帧才允许结算它。 */
    private data class ReplySlot(val target: FeishuReplyTarget, var done: Boolean = false, val promptId: String? = null)
    private val replySlots = HashMap<String, ReplySlot>()

    /**
     * 有界 LRU（#265）。话题化之后这几张表按「话题数」而不是「群数」增长，而会话是**故意**在 idle-reap 之后
     * 仍然留着的——下一条消息还要 resume 它，所以没有一个可靠的「结束」钩子可以挂清理。用容量上限兜底：
     * 最久没被碰过的话题先掉，它的下一条消息就是开一条干净会话。这个退化可以接受，无界增长不能。
     *
     * 全部读写都在 [mutex] 里，所以 accessOrder 的非线程安全在这里不成问题。
     */
    private class LruMap<V>(
        private val max: Int,
        private val onEvict: (String, V) -> Unit = { _, _ -> },
    ) : LinkedHashMap<String, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean {
            if (size <= max || eldest == null) return false
            onEvict(eldest.key, eldest.value)
            return true
        }
    }

    /** A conversation key aged out of [convoByKey]: drop everything else that key owned. */
    private fun evictConversation(key: String, convoId: String) {
        sessionOf.remove(convoId)
        replySlots.remove(convoId)
        pendingAsk.remove(convoId)
        keyWorkdir.remove(key)
    }

    /** Forget one conversation key outright (`/new`). Returns true when a live mapping actually went away —
     *  that's what lets a top-level `/new` report how much it really cleared. Caller holds [mutex]. */
    private fun forgetConversation(key: String): Boolean {
        val convoId = convoByKey.remove(key) ?: run { keyWorkdir.remove(key); return false }
        evictConversation(key, convoId)
        return true
    }

    private sealed interface OpenResult {
        data class Opened(val convoId: String) : OpenResult
        data class Denied(val code: BridgeDenyCode) : OpenResult
        data object TimedOut : OpenResult
    }
    private data class PolicyDispatch(
        val action: ChatAction,
        val trustReply: String? = null,
        val trustAudit: String? = null,
    )
    // convoId -> the label of a permission ask currently waiting on the owner's phone, so the "still
    // working" nudge can name it ("Run command 在等你批准") instead of a bare, scary timeout.
    private val pendingAsk = HashMap<String, String>()
    // Turn serialization, keyed by [FeishuThreading.executionLockKey]: chat-wide in a group (every topic
    // there writes the SAME workdir — #265), per-conversation in a direct chat. A second prompt queues
    // instead of overwriting the first's waiter; unrelated chats stay fully parallel.
    // Bounded like the state above, with one extra rule: a HELD mutex is never evicted. Dropping one would
    // let the next message mint a fresh mutex and run in parallel with the turn still holding the old one —
    // exactly the concurrent-write this lock exists to prevent. A locked eldest simply survives the round.
    private val chatLocks = object : LinkedHashMap<String, Mutex>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mutex>?): Boolean =
            size > TOPIC_STATE_MAX && eldest?.value?.isLocked == false
    }
    // Trust/routes policy linearization, keyed by the Feishu chat (not topic). Every route/trust mutation and
    // the FINAL snapshot validation + grant hand-off takes this mutex. Guardian review deliberately does not:
    // /untrust and /bind must land immediately while it is thinking, then defeat the final claim. This closes
    // the revoke/rebind window between ReviewedPreflight's async revalidation and arming a broad turn grant.
    private val policyGate = FeishuPolicyGate()
    // the bot's own open_id (fetched at start) — the mention filter's ground truth. Null until fetched;
    // fallback then is "any mention", the pre-fix behaviour, so a slow fetch degrades soft.
    @Volatile private var botOpenId: String? = null
    // Feishu delivers events AT-LEAST-ONCE; this bounded LRU of message ids drops a redelivered duplicate so
    // one message never fires the same prompt twice. Guarded by its own monitor — onMessage runs on the lark
    // SDK's dispatcher threads (not a coroutine), so the engine's suspend mutex can't cover it.
    private val seenMessages = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SEEN_MESSAGES_MAX
    }
    // Where each /menu card this engine posted lives (card message id -> chat + topic), #247. A
    // card.action.trigger callback names ONLY the card message, so this is how a click is resolved back to a
    // chat we actually serve — and why an unknown card fails closed instead of the callback picking a chat
    // for us. In memory and bounded: after a restart an old card's buttons answer "重新发送 /menu", which is
    // honest, cheap, and keeps a long-lived chat history from being a pile of live remote controls.
    // Guarded by its own monitor — written and read on the lark SDK's dispatcher threads, like seenMessages.
    private val cardOrigins = object : LinkedHashMap<String, FeishuCardCallback.Origin>(32, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FeishuCardCallback.Origin>?): Boolean =
            size > CARD_ORIGINS_MAX
    }
    // Feishu GROUP OWNER per chat (open_id), lazily fetched — the fallback /bind authority when no admin
    // open_id is configured, so a group's own owner can bind with no env and no restart. ConcurrentHashMap:
    // written from scope coroutines, read on the lark dispatcher thread.
    // Security notes (issue #91 crypto review): (1) with no admin set this makes the group OWNER the /bind
    // authority — a deliberate trust widening; a configured admin removes the fallback entirely. (2) No TTL:
    // an ownership transfer keeps the OLD owner as bind authority until the engine restarts — bounded, since
    // binding only selects an already-allow-listed workdir and every action still hits owner approval.
    private val chatOwners = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Feishu GROUP NAME per chat (#242), for the session-stable context preamble. Holds the in-flight FETCH,
    // not the value: the preamble is baked into the agent's system prompt at open time, so the opener needs a
    // way to WAIT briefly for a first fetch rather than freeze "nameless" into the whole session. Started on
    // the lark dispatcher thread (onMessage), awaited from a scope coroutine — hence ConcurrentHashMap +
    // Deferred, never a polling loop. A completed miss is evicted (see [chatNameOrNull]) so a failed read
    // retries later; no TTL, so a rename lands after the next fetch or engine restart — nothing depends on it.
    private val chatNames = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String?>>()
    @Volatile override var running: Boolean = false
        private set
    @Volatile override var lastError: String? = null
        private set

    /**
     * The engine's slice of the router's outbound stream. EGRESS is deny-by-default and STRUCTURAL, not by
     * adapter discipline (issue #91 design review): [onFrame] acts on a fixed handful of outcome/state frames
     * and DROPS every other kind, and no branch there forwards a frame's raw content outward — the only
     * group-visible bytes are text this engine COMPUTES and passes to [reply] (which then runs SecretRedactor).
     * INGRESS is symmetric: everything this engine sends goes through [vet] → BridgeGuard, whose own
     * `else -> Deny(FORBIDDEN)` refuses any frame that isn't open/prompt/cancel/close.
     */
    private val sink = dev.ccpocket.daemon.conversation.OutboundSink { frame -> onFrame(frame) }

    override fun start(): String? {
        if (running) return null
        if (appId.isBlank() || appSecret.isBlank()) return "FEISHU_APP_ID / FEISHU_APP_SECRET are required for the built-in adapter"
        return runCatching {
            if (api == null) api = FeishuApiClient(appId, appSecret, name, logLine)
            val dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                    override fun handle(event: P2MessageReceiveV1) = onMessage(event)
                })
                // no-op: people react to the bot's replies with 👍, and every reaction otherwise lands as
                // an ERROR HandlerNotFoundException stack in the daemon log — noise dressed as failure
                .onP2MessageReactionCreatedV1(object : ImService.P2MessageReactionCreatedV1Handler() {
                    override fun handle(event: com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1) {}
                })
                .onP2MessageReactionDeletedV1(object : ImService.P2MessageReactionDeletedV1Handler() {
                    override fun handle(event: com.lark.oapi.service.im.v1.model.P2MessageReactionDeletedV1) {}
                })
                // /menu button presses (#247). The SDK carries this handler's RETURN VALUE back over the
                // same long connection as the callback's response frame, so every path must return one.
                .onP2CardActionTrigger(object : P2CardActionTriggerHandler() {
                    override fun handle(event: P2CardActionTrigger): P2CardActionTriggerResponse = onCardAction(event)
                })
                .build()
            // start() is non-blocking; the SDK reconnects on its own. Building a fresh client per start is
            // how restart works — see stop() for how the old one is put down.
            ws = com.lark.oapi.ws.Client.Builder(appId, appSecret).eventHandler(dispatcher).build()
            ws!!.start()
            running = true
            lastError = null
            // A managed-runner restart deliberately preserves live conversations for continuity. Re-arm their
            // idle release jobs so stop/start cannot turn a previously settled chat into a permanent slot leak.
            ownedConvoIds().forEach(::scheduleRelease)
            fetchBotIdentity()
            logLine("[engine] built-in feishu bridge \"$name\" connected (projects: ${spec.workdirs.joinToString { FeishuRoutes.projectName(it) }})")
            log.info("feishu engine \"$name\" started")
            null
        }.getOrElse { e ->
            lastError = "couldn't start: ${e.message}"
            logLine("[engine] start failed: ${e.message}")
            e.message ?: "start failed"
        }
    }

    override fun stop() {
        running = false
        releaseJobs.values.forEach { it.cancel() }
        releaseJobs.clear()
        // The SDK's ws.Client exposes no public stop — only protected disconnect() and an autoReconnect
        // flag. Reflection is regrettable but contained: the version is PINNED in the catalog, and the
        // failure mode of a drifted SDK is an orphaned (but harmless) reconnect loop we log about.
        ws?.let { c ->
            runCatching {
                c.javaClass.getDeclaredField("autoReconnect").apply { isAccessible = true }.set(c, false)
                c.javaClass.getDeclaredMethod("disconnect").apply { isAccessible = true }.invoke(c)
            }.onFailure { log.warn("feishu ws disconnect via reflection failed (${it.message}) — the SDK may keep a reconnect loop") }
        }
        ws = null
        api?.snapshot()?.takeIf { it.calls > 0 }?.let { s ->
            logLine(
                "[http] calls=${s.calls} failed=${s.failures} timed-out=${s.timeouts} " +
                    "peak=${s.peakActive} connections=${s.connections}/${s.idleConnections} idle",
            )
        }
        logLine("[engine] stopped")
        log.info("feishu engine \"$name\" stopped")
    }

    /** ConvoIds this engine ever opened — the caller intersects with live registry state for the
     *  "active now" pulse on the management pages, same as an external bridge's guard. */
    override fun ownedConvoIds(): Set<String> = guard.ownedConvoIds()

    /**
     * REVOKE/REMOVE/RECONFIGURE teardown: force-close every conversation this engine opened so its in-flight
     * turns END at once — revoked or replaced authority cannot survive. [stop] alone only drops the link;
     * the convos it opened keep running in the registry until the idle reaper reclaims them, which is
     * exactly the window a revoked bridge must not have. Mirrors the guest-revoke path in DeviceSessions:
     * the per-engine owned ledger covers convos this instance still tracks, and closeByOrigin ALSO reaps
     * ones opened by an EARLIER engine instance (a reconfigure builds a fresh engine and drops the old
     * ledger) — every turn routes with origin = [spec].name, so the label is the exact marker. A plain
     * stop()/restart deliberately does NOT call this: it reuses the same engine and its live convos for
     * continuity.
     */
    override suspend fun revokeAndShutdown() = revokeAfterHandlerDrain(
        quiesceIngress = ::stop,
        handlerJob = handlerJob,
        // Do not wrap this in runCatching: failure must reach BridgeRunners, which then refuses to remove
        // the old entry or construct a replacement under different authority.
        closeOriginConversations = { core.registry.closeByOrigin(spec.name) },
        releaseResources = {
            api?.close()
            api = null
        },
    )

    // ── inbound chat events ──

    /**
     * The bot's own open_id, from GET /bot/v3/info — what makes the mention filter PRECISE: without it,
     * "@some colleague check this" in a chat the bot sits in would count as addressing the bot (any-mention
     * was the reference adapter's behaviour, and it misfires whenever the app receives all group messages).
     * Best-effort async: until (or unless) it lands, the filter falls back to any-mention and says so once.
     */
    private fun fetchBotIdentity() {
        scope.launch {
            runCatching {
                val openId = api?.botOpenId() ?: error("Feishu API client unavailable")
                botOpenId = openId
                log.info("feishu bot identity: ${openId.take(12)}…")
            }.onFailure {
                logLine("[engine] couldn't fetch the bot's own open_id (${it.message}) — falling back to answering ANY @mention")
            }
        }
    }

    /** Cache the Feishu GROUP OWNER's open_id for [chatId] (GET /im/v1/chats/:id) — the fallback /bind
     *  authority when no admin is configured. Best-effort async; a miss just makes /bind say "confirming,
     *  retry". Only called when no admin open_id is set (see onMessage), so it never runs needlessly. */
    private fun fetchChatOwner(chatId: String) {
        scope.launch {
            runCatching {
                val owner = api?.chatOwnerOpenId(chatId) ?: return@launch
                // only a USER open_id (ou_…) may become a bind authority — a bot-owned group (cli_…) or any
                // other shape fails closed (never cached ⇒ /bind stays "confirming, retry"), never authorizes
                if (owner.startsWith("ou_")) chatOwners[chatId] = owner
            }
        }
    }

    /** Start (or join) the display-name fetch for [chatId] — the group half of the context preamble (#242).
     *  Deliberately independent of [fetchChatOwner]: the name is wanted in EVERY group, the owner only when
     *  no admin open_id is configured. Warmed from onMessage so the wait below is usually already over. */
    private fun fetchChatName(chatId: String): kotlinx.coroutines.Deferred<String?> =
        chatNames.computeIfAbsent(chatId) { id ->
            // Never throws: the preamble degrades to a nameless group rather than failing an open.
            scope.async { runCatching { api?.chatName(id) }.getOrNull()?.takeIf { it.isNotBlank() } }
        }

    /** The group's name for a preamble that is about to be baked into a launching session, or null. Bounded:
     *  the name is nice-to-have context, so a slow or broken Feishu read costs at most [CHAT_NAME_WAIT_MS]
     *  and then degrades to 「飞书群」 — it must never delay or fail opening the session. The wait is on the
     *  Deferred, NOT around the API call itself: [FeishuApiClient] is blocking IO that a cancellation cannot
     *  interrupt, so the fetch keeps running on its own and warms the cache for the next open. */
    private suspend fun chatNameOrNull(chatId: String): String? {
        val pending = fetchChatName(chatId)
        val name = withTimeoutOrNull(CHAT_NAME_WAIT_MS) { pending.await() }
        // A finished fetch that produced nothing (API error, empty name) is dropped so a later open retries;
        // one still in flight stays cached — it is exactly what the next open wants to join.
        if (name == null && pending.isCompleted) chatNames.remove(chatId, pending)
        return name
    }

    /** First time we've seen [messageId]? Records it and returns true; a redelivered duplicate returns
     *  false. Bounded LRU ([seenMessages]); thread-safe for the lark dispatcher threads onMessage runs on. */
    private fun firstSeen(messageId: String): Boolean =
        synchronized(seenMessages) { seenMessages.put(messageId, true) == null }

    /**
     * Requirement 2: prepend the QUOTED message (and, when distinct, the thread ROOT) ahead of the user's
     * own line, so Claude sees the original text the reply points at rather than a bare one-liner. Returns
     * [prompt] unchanged when there's nothing to quote or the fetch fails/isn't permitted — a quote is
     * additive context, never a gate: its absence must not block the turn. Full-thread history is a bounded
     * follow-up; MVP carries the direct parent plus the root anchor (two gets at most).
     */
    private suspend fun withQuotedContext(prompt: String, parentId: String?, rootId: String?): String {
        if (parentId.isNullOrBlank()) return prompt
        val quoted = fetchMessageText(parentId)
        // the root only adds signal when it's a DIFFERENT message than the direct parent (a 2-deep reply)
        val root = rootId?.takeIf { it.isNotBlank() && it != parentId }?.let { fetchMessageText(it) }
        if (quoted == null && root == null) {
            logLine("[chat] couldn't read the quoted message ($parentId) — sending without it (check im:message read scope)")
            return prompt
        }
        return buildString {
            root?.let { appendLine("[所在话题的起始消息]").appendLine(it).appendLine() }
            quoted?.let { appendLine("[用户引用的消息]").appendLine(it).appendLine() }
            appendLine("[用户本次的指令]")
            append(prompt)
        }
    }

    /** Fetch one message's plain text by id — the quoted-context reader. Null on any failure so the caller
     *  degrades to "no quote". The SDK owns tenant-token caching and the bounded HTTP path (#236). */
    private suspend fun fetchMessageText(messageId: String): String? = runCatching {
        // defense-in-depth: the id comes from Feishu's own event (om_…-shaped, not user-typed), but validate
        // its charset before it lands in the URL path so a malformed/crafted id can never alter the request.
        if (!messageId.matches(Regex("^[A-Za-z0-9_-]+$"))) return null
        val item = api?.message(messageId) ?: return null
        FeishuMessageText.plainText(item.type, item.content).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun onMessage(event: P2MessageReceiveV1) {
        // stop/revoke flips this BEFORE disconnecting the SDK or cancelling handlers. A callback already on
        // an SDK thread may race the disconnect, but it cannot admit new work after the authority boundary.
        if (!running) return
        val data = event.event ?: return
        val msg = data.message ?: return
        val isGroup = FeishuThreading.isGroup(msg.chatType)
        // a DIRECT chat needs no @ — every message there IS addressed to the bot (the unified-inbox
        // entrance). GROUPS keep the strict mention gate: without it the bot would answer every line in
        // every chat the app subscribes to.
        val mentions = msg.mentions?.toList().orEmpty()
        if (isGroup) {
            if (mentions.isEmpty()) return
            // precise when we know who we are: the BOT must be among the mentioned, or the message isn't for
            // us — "@colleague look at this" in our chat must stay none of our business
            val self = botOpenId
            if (self != null && mentions.none { it.id?.openId == self }) return
        }
        val chatId = msg.chatId ?: return
        // requirement 2: when this message REPLIES to an earlier one, parentId is the quoted message and
        // rootId the thread root — read here rather than below because a mention-only message has to know
        // it HAS a quote before it can tell "nothing was said" from "what was said is the quote".
        val parentId = msg.parentId
        val rootId = msg.rootId
        // 飞书自己的话题锚点：话题里的每条消息都带同一个 thread_id，包括不点回复的直发消息。rootId 只在
        // 回复／引用链路上才有值，单靠它会让话题内直发的每一句各自成一条会话（#262）。
        val threadId = msg.threadId
        // text AND rich-text `post` carry instructions; every other kind (image / file / card) carries none.
        // This used to read `content.text` directly, which is present only on `text` — so any message written
        // with a bullet list or a heading arrived as `post` and vanished here, with no reply and no log.
        var text = FeishuMessageText.inboundText(msg.messageType, msg.content) ?: run {
            logLine("[drop] $chatId: 不处理的消息类型 ${msg.messageType}（只认文本和富文本）")
            return
        }
        text = FeishuMessageText.withoutMentionPlaceholders(text, mentions.map { it.key })
        // "@bot" + a quoted message and nothing else is how people actually say "deal with THIS one" — the
        // instruction lives in the quote, so hand the turn a note saying exactly that and let the quoted
        // context (fetched on the Ask path) carry the content. A bare mention with no quote stays a no-op.
        val quoteOnly = text.isEmpty() && !parentId.isNullOrBlank()
        if (quoteOnly) text = QUOTE_ONLY_PROMPT
        if (text.isEmpty()) {
            logLine("[drop] $chatId: 只 @ 了机器人，既没写内容也没引用消息")
            return
        }
        val sender = data.sender?.senderId?.openId.orEmpty()
        val replyMessageId = msg.messageId ?: return
        // Feishu delivers events at-least-once — the SAME message can arrive again on a retry / reconnect,
        // and without dedup that re-runs the prompt (issue #91). Drop a message id we've already handled.
        if (!firstSeen(replyMessageId)) return

        val replyTo = FeishuThreading.replyTarget(replyMessageId, msg.chatType)
        // NOTE: the Typing receipt used to fire HERE, before the policy gate — an unbound group therefore saw
        // 「机器人在打字」 and then nothing, forever (#265). It now posts from dispatchChatLine, once we know
        // the line actually starts a turn; every other outcome already answers with real text.
        // warm the group-owner cache for the /bind fallback — groups only (a direct chat's bind authority
        // is its own user), and only when no admin is set (else it's unused)
        if (isGroup && adminOpenId.isNullOrBlank() && !chatOwners.containsKey(chatId)) fetchChatOwner(chatId)
        // warm the group-name fetch for the context preamble (#242) — groups only; a direct chat has no name
        // to state. Independent of the owner fetch above: different trigger, different consumer.
        if (isGroup) fetchChatName(chatId)

        // OWNER BYPASS (issue #91): the CONFIGURED owner's OWN messages run in a SEPARATE, dedicated session
        // (keyed apart from the topic's) that auto-allows — race-free, because a session never mixes senders.
        // Everyone else drives the approval-gated topic session. Gated on the toggle + a set admin id.
        val ownerTurn = ownerBypassEnabled && !adminOpenId.isNullOrBlank() && sender == adminOpenId
        // one anchor, one identity: the conversation key, the card origin and the 「顶层还是话题内」 judgement
        // below all read the SAME resolved topic, so a topic can never end up with two identities (#262)
        val topicAnchor = FeishuThreading.topicAnchor(replyMessageId, threadId, rootId)
        val convoKey = FeishuThreading.conversationKeyOf(chatId, msg.chatType, topicAnchor, ownerTurn)

        // Feishu's callback is synchronous, while policy ordering needs a suspendable per-chat mutex. Move the
        // command dispatch into the engine scope; commands.handle performs /bind, /unbind and single-project
        // auto-bind synchronously, so evaluating it under this mutex covers every route mutation too.
        scope.launch {
            dispatchChatLine(
                ChatLine(
                    text = text,
                    chatId = chatId,
                    chatType = msg.chatType,
                    isGroup = isGroup,
                    sender = sender,
                    ownerTurn = ownerTurn,
                    convoKey = convoKey,
                    replyTo = replyTo,
                    // the topic a card posted here would belong to: a group's own topic anchor, nothing in a
                    // direct chat (which has exactly one conversation)
                    topicRoot = if (isGroup) topicAnchor else null,
                    // 这条是在话题**里面**说的，还是在群的顶层新起的一条？只有话题锚点来自飞书（thread/root）
                    // 时才算话题内；否则这条消息自己就是话题的开头。顶层 /new 的语义靠它区分（#265）。
                    inTopic = isGroup && !(threadId.isNullOrBlank() && rootId.isNullOrBlank()),
                    parentId = parentId,
                    rootId = rootId,
                    quoteOnly = quoteOnly,
                ),
            )
        }
    }

    /**
     * A `/menu` button press (`card.action.trigger`, #247). The click becomes the EXACT command its label
     * promises and is handed to [dispatchChatLine] — the same entry point a typed line takes, under the
     * CLICKER's own open_id, through the same per-chat policy gate, the same /bind authority and the same
     * trust rules. There is no card-only execution path: a button can never be wider authority than typing,
     * only an equal one over the three commands in [FeishuCards.COMMANDS].
     *
     * Four independent things must line up before anything is dispatched: the engine is live, the value
     * names an allow-listed command, the card is one WE posted into a chat we still remember (and the
     * callback agrees about which chat that is — see [FeishuCardCallback.resolve]), and the event is new,
     * since Feishu retries callbacks like it retries messages. Feishu expects a response frame either way,
     * so every path returns a toast rather than dying under the user's finger.
     */
    private fun onCardAction(event: P2CardActionTrigger): P2CardActionTriggerResponse {
        // the same authority boundary onMessage keeps: stop/revoke flips this before the link goes down
        if (!running) return cardToast(FeishuCards.UNKNOWN_ACTION_TOAST)
        val data = event.event ?: return cardToast(FeishuCards.UNKNOWN_ACTION_TOAST)
        val line = FeishuCardCallback.resolve(
            value = data.action?.value,
            // the acting identity is Feishu's attested operator — never anything the card carries
            operatorOpenId = data.operator?.openId,
            cardMessageId = data.context?.openMessageId,
            callbackChatId = data.context?.openChatId,
            originOf = { id -> synchronized(cardOrigins) { cardOrigins[id] } },
        ) ?: return cardToast(FeishuCards.UNKNOWN_ACTION_TOAST)
        // callbacks are delivered at-least-once like messages; a retried click must not fire the command
        // twice, while two deliberate presses (distinct event ids) still both count
        val eventId = event.header?.eventId
        if (eventId != null && !firstSeen(eventId)) return cardToast(cardAckToast(line.command))

        val isGroup = FeishuThreading.isGroup(line.chatType)
        // owner bypass is re-derived from the CLICKER exactly as onMessage derives it from the sender: a
        // card the machine owner posted grants a colleague who presses it nothing they didn't already have
        val ownerTurn = ownerBypassEnabled && !adminOpenId.isNullOrBlank() && line.sender == adminOpenId
        // the same two context warm-ups a message gets, so a button is not a second-class line
        if (isGroup && adminOpenId.isNullOrBlank() && !chatOwners.containsKey(line.chatId)) fetchChatOwner(line.chatId)
        if (isGroup) fetchChatName(line.chatId)
        logLine("[card] ${line.chatId}: ${line.command}")
        scope.launch {
            dispatchChatLine(
                ChatLine(
                    text = line.command,
                    chatId = line.chatId,
                    chatType = line.chatType,
                    isGroup = isGroup,
                    sender = line.sender,
                    ownerTurn = ownerTurn,
                    // the card's remembered topic keeps a press acting on the conversation the card was
                    // posted into, not on a fresh topic rooted at the card itself
                    convoKey = FeishuThreading.conversationKeyOf(
                        line.chatId, line.chatType, line.topicRoot ?: line.cardMessageId, ownerTurn,
                    ),
                    replyTo = FeishuReplyTarget(line.cardMessageId, inThread = isGroup),
                    topicRoot = line.topicRoot,
                    // a card we posted into a group ALWAYS lives in the topic it answered, so a press on it is
                    // a line INSIDE that topic — never a top-level one (which would sweep the whole chat)
                    inTopic = isGroup && line.topicRoot != null,
                ),
            )
        }
        return cardToast(cardAckToast(line.command))
    }

    /** Post a `/menu` card and REMEMBER where it lives, so a later press resolves back to this exact chat
     *  and topic. A card Feishu won't take degrades to its plain-text twin rather than to silence. */
    private fun replyCard(line: ChatLine, card: ChatAction.Card) {
        val posted = runCatching {
            api?.replyCard(line.replyTo.messageId, card.json, inThread = line.replyTo.inThread)
        }.onFailure { logLine("[chat] card reply failed: ${it.message}") }.getOrNull()
        if (posted == null) {
            reply(line.replyTo, card.fallbackText)
            return
        }
        synchronized(cardOrigins) {
            cardOrigins[posted] = FeishuCardCallback.Origin(line.chatId, line.chatType, line.topicRoot)
        }
    }

    /** Feishu wants a response frame for every `card.action.trigger`; a toast is the least surprising one —
     *  instant feedback under the finger, and no card mutation we would then have to keep in sync. */
    private fun cardToast(text: String): P2CardActionTriggerResponse = P2CardActionTriggerResponse().apply {
        toast = CallBackToast().apply {
            type = "info"
            content = text
        }
    }

    private fun cardAckToast(command: String): String = "已收到：$command"

    /**
     * One inbound chat line, whatever produced it: a typed/@'d message ([onMessage]) or a `/menu` button
     * press ([onCardAction]). Every command, every trust check and every turn goes through HERE — a card
     * has no execution path of its own, so a button can only ever do what its clicker could type.
     */
    private data class ChatLine(
        val text: String,
        val chatId: String,
        val chatType: String?,
        val isGroup: Boolean,
        /** the Feishu-attested acting user: a message's sender, or a button's OPERATOR. */
        val sender: String,
        val ownerTurn: Boolean,
        val convoKey: String,
        val replyTo: FeishuReplyTarget,
        /** the group TOPIC this line belongs to (null in a direct chat) — carried so a card posted in
         *  answer can be resolved back to the same topic when a button on it is pressed. */
        val topicRoot: String?,
        /** 群里这条是话题**内部**的发言（true），还是在顶层新起的一条（false）。直聊恒为 false。 */
        val inTopic: Boolean = false,
        val parentId: String? = null,
        val rootId: String? = null,
        val quoteOnly: Boolean = false,
    )

    private suspend fun dispatchChatLine(line: ChatLine) {
        val text = line.text
        val chatId = line.chatId
        val isGroup = line.isGroup
        val sender = line.sender
        val ownerTurn = line.ownerTurn
        val convoKey = line.convoKey
        val replyTo = line.replyTo
        val parentId = line.parentId
        val rootId = line.rootId
        val quoteOnly = line.quoteOnly
        // a labelled block so the two "quote unreadable → answer and stop" exits stay early returns without
        // reindenting the dispatch they sit in (they used to return from onMessage's launch)
        run {
            val dispatch = policyGate.withPolicy(chatId) {
                val resolved = commands.handle(text, chatId, sender, isDirect = !isGroup)
                if (resolved is ChatAction.SetTrust) applyTrustMutation(resolved, chatId, isDirect = !isGroup)
                else PolicyDispatch(resolved)
            }
            dispatch.trustAudit?.let { what ->
                logLine("[trust] $what")
                trustLog.record("${java.time.Instant.now()} trust-change $what")
            }
            dispatch.trustReply?.let { reply(replyTo, it) }
            // #265: 「收到了」的表情回执排在策略门之后，只有真会起一轮活的行才发。别的分支都会有实打实的
            // 文字回复，再加个表情是噪音；而以前它排在门之前，未绑定项目的群会先看到表情、然后永远没下文。
            if (dispatch.action is ChatAction.Ask || dispatch.action is ChatAction.AskAuto) ackTyping(line)
            when (val action = dispatch.action) {
            is ChatAction.Ignore -> {}
            is ChatAction.Reply -> reply(replyTo, action.text)
            is ChatAction.Card -> replyCard(line, action)
            is ChatAction.Reset -> {
                // 顶层（不在任何话题里）发的 /new：这条消息刚刚生成的话题键上根本还没有会话，照原样 remove
                // 等于清了个空键，却回一句「已开新会话」——假成功。顶层的自然语义是「把这个群清一清」，
                // 所以在这里如实清掉本群所有话题的会话并报出真实条数（#265）；话题内的 /new 只清本话题。
                val sweptTopics = if (isGroup && !line.inTopic) resetAllTopics(chatId) else null
                if (sweptTopics == null) {
                    // drop the SENDER's conversation (owner and group have SEPARATE sessions per chat — see
                    // convoKey) under the same execution lock a turn holds, so /new can't race a running turn
                    executionLock(line, convoKey).withLock { mutex.withLock { forgetConversation(convoKey) } }
                }
                // a DIRECT unified inbox keeps one conversation per (chat, project): /new drops the CURRENT
                // project's conversation and clears the continuation anchor so the next message re-routes
                if (!isGroup) {
                    val active = mutex.withLock { activeProject.remove(chatId) }
                    if (active != null) {
                        val k = FeishuThreading.directProjectKey(chatId, active, ownerTurn)
                        val plock = mutex.withLock { chatLocks.getOrPut(k) { Mutex() } }
                        plock.withLock { mutex.withLock { forgetConversation(k) } }
                    }
                }
                reply(
                    replyTo,
                    when {
                        sweptTopics == null -> action.note
                        sweptTopics > 0 ->
                            "🆕 已清空本群 $sweptTopics 个话题的会话上下文，每个话题的下一条消息都会重新开始。"
                        else ->
                            "ℹ️ 本群当前没有进行中的话题会话，没有可清的上下文。\n" +
                                "在某个话题里发 /new 只会清那一个话题。"
                    },
                )
            }
            is ChatAction.SetTrust -> error("trust mutation escaped the per-chat policy gate")
            is ChatAction.Ask -> {
                // an auto-bind's feedback posts NOW — the turn behind it can take minutes
                action.note?.let { reply(replyTo, it) }
                logLine("[chat] ${FeishuRoutes.projectName(action.workdir)} ← $chatId: ${text.take(80)}")
                val slashPassThrough = text.startsWith("/")
                val prompt = buildTurnPrompt(action.prompt, slashPassThrough, parentId, rootId, quoteOnly, sender, ownerTurn)
                    ?: return@run reply(replyTo, UNREADABLE_QUOTE_REPLY)
                // ONE conversation per group topic (or per direct chat), but only one turn at a time per
                // CHAT (see chatLocks) — a second message queues instead of clobbering the first's waiter or
                // racing it inside the same workdir. ask() posts its OWN reply (inline, or late via onFrame
                // after an approval), so we only surface a hard failure to open/drive the turn.
                executionLock(line, convoKey).withLock {
                    runCatching { ask(convoKey, chatId, isGroup, action.workdir, prompt, replyTo, sender, ownerTurn) }
                        .onFailure { e ->
                            log.warn("feishu turn failed: ${e.message}")
                            reply(replyTo, "⚠️ 出错了：${e.message}")
                        }
                }
            }
            is ChatAction.AskAuto -> {
                // AUTO-routing chat: decide WHICH allow-listed project this request belongs to, then
                // proceed exactly as Ask — same guard, same trust rules, the same single owner card. The
                // quote is fetched BEFORE routing so a "deal with THIS one" reply routes on the quoted
                // content, not on a bare placeholder line.
                val slashPassThrough = text.startsWith("/")
                val prompt = buildTurnPrompt(action.prompt, slashPassThrough, parentId, rootId, quoteOnly, sender, ownerTurn)
                    ?: return@run reply(replyTo, UNREADABLE_QUOTE_REPLY)
                if (isGroup) {
                    // a GROUP topic routes once and stays put: route + run under the chat's execution lock,
                    // so two racing first messages cannot route it twice (the loser sees the sticky pick)
                    executionLock(line, convoKey).withLock {
                        when (val route = resolveAutoRoute(convoKey, chatId, prompt, slashPassThrough)) {
                            is AutoRoute.Unrouted -> reply(replyTo, route.hint)
                            is AutoRoute.Routed -> runAutoTurn(convoKey, chatId, isGroup = true, route, prompt, replyTo, sender, ownerTurn, text)
                        }
                    }
                } else {
                    // the DIRECT unified inbox: every message routes (with a continuation bias toward the
                    // chat's current project), and each project keeps its OWN conversation, so switching
                    // projects switches contexts without destroying either
                    when (val route = resolveDirectRoute(chatId, text, slashPassThrough)) {
                        is AutoRoute.Unrouted -> reply(replyTo, route.hint)
                        is AutoRoute.Routed -> {
                            val key = FeishuThreading.directProjectKey(chatId, route.workdir, ownerTurn)
                            executionLock(line, key).withLock {
                                runAutoTurn(key, chatId, isGroup = false, route, prompt, replyTo, sender, ownerTurn, text)
                            }
                        }
                    }
                }
            }
            }
        }
    }

    /** #234 的即时回执：一个 Typing 表情，让发起人知道请求收下了、活已经开始。缺 reaction scope 或飞书临时
     *  报错都只是观感问题，绝不能挡住这一轮——所以永远 best-effort、永远异步。 */
    private fun ackTyping(line: ChatLine) {
        scope.launch {
            runCatching { api?.reactTyping(line.replyTo.messageId) }
                .onFailure { logLine("[chat] typing receipt failed") }
        }
    }

    /** The mutex that serializes EXECUTION for [line] — see [FeishuThreading.executionLockKey]. [convoKey] is
     *  the conversation this turn will actually run in (a direct chat's per-project key differs from
     *  [ChatLine.convoKey]); it is only consulted for a direct chat, where there are no topics to merge. */
    private suspend fun executionLock(line: ChatLine, convoKey: String): Mutex {
        val key = FeishuThreading.executionLockKey(line.chatId, line.chatType, convoKey, line.ownerTurn)
        return mutex.withLock { chatLocks.getOrPut(key) { Mutex() } }
    }

    /**
     * 顶层 `/new`：清掉本群**所有**话题的会话，返回真正清掉了几条。
     *
     * 群里有两把执行锁（普通成员一把、owner bypass 一把），两把都要拿，否则可能把一个正在跑的话题从表里
     * 抽走。顺序固定为「普通 → owner」，而任何一轮消息都只拿其中一把，所以构不成环。
     */
    private suspend fun resetAllTopics(chatId: String): Int {
        val chatType = GROUP_CHAT_TYPE
        val shared = mutex.withLock {
            chatLocks.getOrPut(FeishuThreading.executionLockKey(chatId, chatType, chatId, ownerTurn = false)) { Mutex() }
        }
        val owner = mutex.withLock {
            chatLocks.getOrPut(FeishuThreading.executionLockKey(chatId, chatType, chatId, ownerTurn = true)) { Mutex() }
        }
        return shared.withLock {
            owner.withLock {
                mutex.withLock {
                    val prefix = FeishuThreading.topicKeyPrefix(chatId)
                    // snapshot first: forgetConversation mutates both maps underneath us
                    val doomed = (convoByKey.keys + keyWorkdir.keys).filter { it.startsWith(prefix) }.toSet()
                    doomed.count { forgetConversation(it) }
                }
            }
        }
    }

    /**
     * The turn's final prompt: quoted context (requirement 2) + the per-turn sender line (#242), or the
     * verbatim line for a slash pass-through. Null when a quote-ONLY request's quote couldn't be read —
     * there is no instruction left to run, and the caller replies [UNREADABLE_QUOTE_REPLY] instead.
     */
    private suspend fun buildTurnPrompt(
        rawPrompt: String,
        slashPassThrough: Boolean,
        parentId: String?,
        rootId: String?,
        quoteOnly: Boolean,
        sender: String,
        ownerTurn: Boolean,
    ): String? {
        // a natural-language reply carries the QUOTED message (+ thread root) into the prompt; slash
        // pass-throughs ("/clear" etc.) act on the user's own line, not the quoted one
        val quoted = if (!slashPassThrough) withQuotedContext(rawPrompt, parentId, rootId) else rawPrompt
        // "did the quote actually resolve?" is decided HERE, on the quoted value alone — the sender line
        // below always changes the string, so a later comparison would report every quote as resolved
        if (quoteOnly && quoted == rawPrompt) return null
        return if (slashPassThrough) quoted else senderLine(sender, ownerTurn) + "\n\n" + quoted
    }

    /** An auto chat's per-topic routing decision (see [resolveAutoRoute]). */
    private sealed interface AutoRoute {
        /** Run in [workdir]; non-null [note] is the routing receipt, posted before the turn starts. */
        data class Routed(val workdir: String, val note: String?) : AutoRoute
        /** Nothing to run — [hint] tells the topic how to get routed. */
        data class Unrouted(val hint: String) : AutoRoute
    }

    /**
     * Which project does this topic's request belong to? Resolution ladder, cheapest first:
     *  1. STICKY — the topic already picked a project (keyWorkdir survives idle-reap); keep it while the
     *     chat's binding still covers it. /new clears the key, so the next message re-routes.
     *  2. a bare slash pass-through carries no content to route by → teach instead of guessing;
     *  3. DETERMINISTIC — the message names exactly one allow-listed project;
     *  4. the router MODEL, honored only above the confidence floor;
     *  5. otherwise ask the user to name the project — a wrong guess costs minutes of misdirected work
     *     plus a misleading receipt, so "not sure" degrades to a question, never a pick.
     */
    /** The shared tail of an auto-routed request: routing receipt, log line, and the turn itself —
     *  identical for a group topic and a direct-inbox conversation once the workdir is decided. */
    private suspend fun runAutoTurn(
        key: String,
        chatId: String,
        isGroup: Boolean,
        route: AutoRoute.Routed,
        prompt: String,
        replyTo: FeishuReplyTarget,
        sender: String,
        ownerTurn: Boolean,
        text: String,
    ) {
        // the routing receipt posts NOW — a wrong pick must be visible before minutes of work
        route.note?.let { reply(replyTo, it) }
        logLine("[chat] ${FeishuRoutes.projectName(route.workdir)}（自动路由）← $chatId: ${text.take(80)}")
        runCatching {
            ask(key, chatId, isGroup, route.workdir, prompt, replyTo, sender, ownerTurn, autoRouted = true)
        }.onFailure { e ->
            log.warn("feishu turn failed: ${e.message}")
            reply(replyTo, "⚠️ 出错了：${e.message}")
        }
    }

    private suspend fun resolveAutoRoute(
        convoKey: String,
        chatId: String,
        prompt: String,
        slashPassThrough: Boolean,
    ): AutoRoute {
        mutex.withLock { keyWorkdir[convoKey] }
            ?.takeIf { routes.coversWorkdir(chatId, it, spec.workdirs) }
            ?.let { return AutoRoute.Routed(it, note = null) }
        if (slashPassThrough) {
            return AutoRoute.Unrouted(
                "本话题还没有路由到项目，无法直接执行斜杠命令。先 @我 说一句需求（我会自动选择项目），" +
                    "或让管理员 /bind <项目名> 固定绑定本群。",
            )
        }
        ProjectRoutePolicy.mentionedProject(prompt, spec.workdirs)?.let { wd ->
            return AutoRoute.Routed(wd, note = "📌 已路由到「${FeishuRoutes.projectName(wd)}」（本话题内延续；/new 可重新选择）")
        }
        spec.workdirs.singleOrNull()?.let { return AutoRoute.Routed(it, note = null) }
        val candidates = spec.workdirs.map { ProjectCandidate(FeishuRoutes.projectName(it), projectSummary(it)) }
        val result = projectRouter.route(
            ProjectRouteInput(prompt = prompt, candidates = candidates, chatName = chatNameOrNull(chatId)),
        )
        val picked = result?.takeIf { it.confidence >= ProjectRoutePolicy.CONFIDENCE_FLOOR }?.project
        val wd = picked?.let { FeishuRoutes.resolveProject(it, spec.workdirs) }
        if (wd != null) {
            logLine("[route] ${FeishuRoutes.projectName(wd)} ← $chatId（${"%.2f".format(result.confidence)}：${result.reason.take(60)}）")
            return AutoRoute.Routed(
                wd,
                note = "📌 已自动路由到「${FeishuRoutes.projectName(wd)}」（本话题内延续；选错了发 /new 后点名项目重试）",
            )
        }
        return AutoRoute.Unrouted(unroutableHint())
    }

    /**
     * The DIRECT unified inbox's per-message routing, biased to CONTINUE the chat's current project:
     *  1. explicit unique project name in the message → that project (switch receipt when it changes);
     *  2. a single-project bridge has nothing to choose;
     *  3. a short line with an active project is a follow-up ("继续", "对，就这样改") — no model call;
     *  4. the router model, with `current_project` as the continuation anchor;
     *  5. an unsure verdict CONTINUES the active project (the receipt named it; /new + naming corrects)
     *     — only a chat with no active project yet asks the user to name one.
     * Routes on the RAW text, not the built prompt: quoted bot replies and the sender line are noise here.
     */
    private suspend fun resolveDirectRoute(chatId: String, text: String, slashPassThrough: Boolean): AutoRoute {
        val active = mutex.withLock { activeProject[chatId] }?.takeIf { it in spec.workdirs }
        if (slashPassThrough) {
            return active?.let { AutoRoute.Routed(it, note = null) } ?: AutoRoute.Unrouted(
                "还没有正在进行的项目会话，无法直接执行斜杠命令。先说一句需求（我会自动选择项目），或 /bind <项目名> 固定。",
            )
        }
        ProjectRoutePolicy.mentionedProject(text, spec.workdirs)?.let { return directRouted(chatId, it, active) }
        spec.workdirs.singleOrNull()?.let { return directRouted(chatId, it, active) }
        if (active != null && text.length <= DIRECT_FOLLOWUP_MAX_CHARS) return AutoRoute.Routed(active, note = null)
        val candidates = spec.workdirs.map { ProjectCandidate(FeishuRoutes.projectName(it), projectSummary(it)) }
        val result = projectRouter.route(
            ProjectRouteInput(
                prompt = text,
                candidates = candidates,
                currentProject = active?.let { FeishuRoutes.projectName(it) },
            ),
        )
        val wd = result?.takeIf { it.confidence >= ProjectRoutePolicy.CONFIDENCE_FLOOR }?.project
            ?.let { FeishuRoutes.resolveProject(it, spec.workdirs) }
        if (wd != null) {
            logLine("[route] ${FeishuRoutes.projectName(wd)} ← $chatId（${"%.2f".format(result.confidence)}：${result.reason.take(60)}）")
            return directRouted(chatId, wd, active)
        }
        if (active != null) return AutoRoute.Routed(active, note = null)
        return AutoRoute.Unrouted(unroutableHint())
    }

    /** Record [wd] as the direct chat's current project and word the receipt by what changed. */
    private suspend fun directRouted(chatId: String, wd: String, active: String?): AutoRoute.Routed {
        mutex.withLock { activeProject[chatId] = wd }
        val name = FeishuRoutes.projectName(wd)
        return AutoRoute.Routed(
            wd,
            note = when (active) {
                wd -> null
                null -> "📌 已路由到「$name」（后续消息默认延续该项目；点名其他项目可切换，/new 重置）"
                else -> "📌 已切到「$name」（原「${FeishuRoutes.projectName(active)}」的会话保留，点名即可切回）"
            },
        )
    }

    private fun unroutableHint(): String =
        "🤔 没能确定这条需求属于哪个项目，请点名项目重发，例如「${
            spec.workdirs.firstOrNull()?.let { FeishuRoutes.projectName(it) } ?: "项目名"
        }：<需求>」。\n可选项目：${spec.workdirs.joinToString("、") { FeishuRoutes.projectName(it) }}"

    /** A project's bounded router summary: the head of its CLAUDE.md (else README.md), whitespace-collapsed.
     *  Local, read-only, and fed to a TOOL-LESS classifier only — never echoed to the chat. */
    private fun projectSummary(workdir: String): String = projectSummaries.computeIfAbsent(workdir) { wd ->
        val dir = File(wd)
        listOf("CLAUDE.md", "README.md")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }
            ?.let { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(ProjectRoutePolicy.MAX_SUMMARY_CHARS)
            .orEmpty()
    }

    /** Post-decision revalidation of the chat's binding for THIS turn's workdir — the auto-aware twin of
     *  the literal `routes.workdirFor(chatId) == workdir` check. Auto additionally re-checks the allow-list,
     *  the same list BridgeGuard vets, so a reconfigured spec can't be outrun by a sticky topic. A DIRECT
     *  chat is implicitly auto while unbound — the same condition FeishuCommands routes on. */
    private fun bindingStillCovers(chatId: String, workdir: String, autoRouted: Boolean, isDirect: Boolean): Boolean =
        if (autoRouted) {
            (routes.isAuto(chatId) || (isDirect && !routes.isBound(chatId))) && workdir in spec.workdirs
        } else {
            routes.workdirFor(chatId) == workdir
        }

    /** Resolve-and-persist half of a trust command. Called only inside [policyGate], in the SAME critical
     *  section as [FeishuCommands.handle], so `/untrust` cannot be resolved and then overtaken by a stale
     *  reviewed/trusted claim before its disk state changes. Replies/audit IO happen after the gate. */
    /** The chat-facing name of a trust grant's target — the AUTO sentinel must read as what it covers. */
    private fun trustProjectDisplay(wd: String): String =
        if (wd == FeishuRoutes.AUTO) "自动路由的全部项目" else FeishuRoutes.projectName(wd)

    private fun applyTrustMutation(action: ChatAction.SetTrust, chatId: String, isDirect: Boolean = false): PolicyDispatch {
        // the trust-record key: the concrete binding, or the AUTO sentinel for an auto-routing chat
        // (explicit /bind auto, or a direct chat's implicit unified inbox) — the same key FeishuCommands
        // gated the command on, so the two can't disagree about which grant is being written
        val wd = routes.workdirFor(chatId)
            ?: FeishuRoutes.AUTO.takeIf { routes.isAuto(chatId) || (isDirect && !routes.isBound(chatId)) }
        if (action.mode != FeishuTrustMode.UNTRUSTED && wd == null) {
            return PolicyDispatch(ChatAction.Ignore, trustReply = "本群还没有绑定项目，先 /bind 再设置信任模式。")
        }
        if (action.mode == FeishuTrustMode.FULL_AUTO) {
            // Legacy enum spelling is readable from old data, but it is not current consent. Even a stale
            // in-process action must fail closed instead of silently exchanging the old Guardian promise
            // for unconditional TRUSTED authority.
            return PolicyDispatch(
                ChatAction.Ignore,
                trustReply = "ℹ️ 旧 full-auto 授权不会自动升级。请先发送 /trust 阅读新版权限说明，再发送 /trust confirm。",
            )
        }
        val result = when (action.mode) {
            FeishuTrustMode.TRUSTED -> trust.trust(chatId, wd!!)
            FeishuTrustMode.REVIEWED -> trust.setReviewed(chatId, wd!!, action.purpose)
            FeishuTrustMode.FULL_AUTO -> error("handled above")
            FeishuTrustMode.UNTRUSTED -> trust.untrust(chatId)
        }
        return when (result) {
            TrustWrite.UNCHANGED -> PolicyDispatch(
                ChatAction.Ignore,
                trustReply = when (action.mode) {
                    FeishuTrustMode.TRUSTED, FeishuTrustMode.FULL_AUTO -> "本群已经是完全信任了。"
                    FeishuTrustMode.REVIEWED -> "本群已经是智能审核了。用 /trust-status 查看契约。"
                    FeishuTrustMode.UNTRUSTED -> "本群本来就是逐请求审批。"
                },
            )
            TrustWrite.WRITE_FAILED -> PolicyDispatch(
                ChatAction.Ignore,
                trustReply = if (action.mode == FeishuTrustMode.UNTRUSTED)
                    "⚠️ 收回信任没能写盘，本群可能仍处于原来的模式，请重试或在桌面端关掉总开关。"
                else "⚠️ 设置没能写盘，本群模式未变。桌面端「桥」的日志里有原因。",
            )
            TrustWrite.CHANGED -> {
                val what = when (action.mode) {
                    FeishuTrustMode.TRUSTED, FeishuTrustMode.FULL_AUTO ->
                        "机主开启了完全信任：$chatId → ${trustProjectDisplay(wd!!)}（每条请求获得整轮 full 权限）"
                    FeishuTrustMode.REVIEWED -> "机主开启了智能审核：$chatId → ${trustProjectDisplay(wd!!)}" +
                        (if (action.purpose != null) "（自定义契约）" else "（默认契约）")
                    FeishuTrustMode.UNTRUSTED -> "机主关闭了信任模式：$chatId（恢复逐请求审批）"
                }
                val confirmation = when (action.mode) {
                    FeishuTrustMode.TRUSTED, FeishuTrustMode.FULL_AUTO ->
                        trustedEnabledReply(trustProjectDisplay(wd!!))
                    FeishuTrustMode.REVIEWED ->
                        "✅ 本群已对「${trustProjectDisplay(wd!!)}」开启智能审核：每条请求先由 AI 判断是否符合群用途且低风险，" +
                            "通过的直接执行（仅限项目内的受限工具），其余仍会发机主审批。\n" +
                            "契约：${action.purpose ?: FeishuTrust.DEFAULT_CONTRACT}\n" +
                            "换绑到别的项目会自动失效；/trust-status 查看，/untrust 收回。"
                    FeishuTrustMode.UNTRUSTED ->
                        "✅ 已恢复逐请求审批：从下一次请求起，本群每次请求都会先发到机主手机。" +
                            "已经交给代理执行的当前一轮不会被中途撤销；如需停止，请在 cc-pocket 会话中点停止。"
                }
                PolicyDispatch(ChatAction.Ignore, trustReply = confirmation, trustAudit = what)
            }
        }
    }

    private fun reply(target: FeishuReplyTarget, text: String) {
        // last-ditch outbound scrub: a read of a secret file is auto-allowed (read-only, no phone prompt),
        // so its contents can ride the reply into the group — redact obvious credentials here. Applied at
        // the single reply choke point, so it also catches an error string that echoed file content.
        val (scrubbed, redacted) = SecretRedactor.redact(text)
        val out = if (redacted) "$scrubbed\n\n（⚠️ 回复中疑似密钥已自动隐去）" else scrubbed
        val body = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(out.take(MAX_REPLY_CHARS)))
        }.toString()
        runCatching {
            api?.reply(target.messageId, body, inThread = target.inThread)
        }.onFailure { logLine("[chat] reply failed: ${it.message}") }
    }

    // ── driving a session, through the SAME guard an external bridge passes ──

    /**
     * Open (or resume) the conversation mapped to [key] and run [prompt], posting the reply to [replyTo]
     * itself (via [postTurn]) so the outcome survives a slow, out-of-band owner approval:
     *  - finishes within the wait → posted inline here;
     *  - still blocked after [NUDGE_MS] with an approval pending on the phone → one "waiting on your
     *    approval" nudge naming the tool, then keep waiting (chat lock held, so later messages stay ordered);
     *  - never approved within the wait → the reply slot stays armed and [onFrame] posts the eventual
     *    TurnDone whenever the owner finally taps approve — no more "timed out, ask again".
     */
    private suspend fun ask(
        key: String,
        chatId: String,
        isGroup: Boolean,
        workdir: String,
        prompt: String,
        replyTo: FeishuReplyTarget,
        senderOpenId: String,
        ownerBypass: Boolean = false,
        /** True when [workdir] was picked by auto-routing rather than a concrete /bind — trust then keys on
         *  the AUTO sentinel (a grant for the whole allow-list) and revalidation re-checks the allow-list. */
        autoRouted: Boolean = false,
    ) {
        val convoId = when (val opened = openOrReuse(key, chatId, isGroup, workdir, ownerBypass)) {
            is OpenResult.Opened -> opened.convoId
            is OpenResult.Denied -> {
                reply(replyTo, openDeniedMessage(opened.code))
                return
            }
            OpenResult.TimedOut -> {
                reply(replyTo, "⚠️ 会话后端未能在 ${OPEN_TIMEOUT_MS / 1_000} 秒内启动，请稍后重试。")
                return
            }
        }

        // Once a prompt reaches the agent, its eventual TurnDone/PocketError schedules release. Every earlier
        // return (prompt rejected, owner denied/timed out, freshly-approved send failed) releases here instead.
        var awaitingTerminalFrame = false
        var waiterInstalled = false
        var preserveLateReply = false
        try {
            // issue #285：为这条请求铸一个 promptId。它随 SendPrompt 进入会话的 prompt 账本，回复槽记住它，
            // 归属门（onFrame 的 TurnDone 分支）凭消费凭证把「遗留轮次的终态帧」和「这条请求的结果」分开。
            val promptId = java.util.UUID.randomUUID().toString()
            // Reject an over-limit/oversized prompt before asking the owner to approve something that cannot run.
            val promptVerdict = vet(SendPrompt(convoId, prompt, promptId = promptId))
            val vetted = (promptVerdict as? BridgeVerdict.Allow)?.frame as? SendPrompt
            if (vetted == null) {
                val code = (promptVerdict as? BridgeVerdict.Deny)?.code ?: BridgeDenyCode.FORBIDDEN
                reply(replyTo, promptDeniedMessage(code))
                return
            }

            // TRUST-MODE ROUTING (issue #198 + reviewed trust). One immutable snapshot drives the whole
            // decision: both conditions are owner-held state (the master switch lives in the bridge spec, the
            // per-chat record in the engine's own state dir) and the chat id is Feishu-attested, so nothing a
            // member can type reaches this. The snapshot keys on the workdir this turn actually runs in, so a
            // chat rebound since the grant is NOT trusted for its new project (FeishuTrust keys on the pair,
            // no bind/unbind hook to keep in sync).
            // an auto-routed turn's trust grant is the chat-wide AUTO record, never the per-project one —
            // one key per binding shape, exactly what /trust wrote (see applyTrustMutation)
            val trustKey = if (autoRouted) FeishuRoutes.AUTO else workdir
            val snapshot = trust.snapshot(chatId, trustKey)
            val trusted = !ownerBypass && noApprovalEnabled && snapshot.mode == FeishuTrustMode.TRUSTED
            // REVIEWED: the Guardian classifies the vetted prompt BEFORE it reaches the agent.
            // The preflight audits itself; a non-pass (risk, low confidence, prescreen hit, timeout, CLI
            // missing, shadow mode, policy changed mid-review) simply falls through to the owner's card —
            // degraded, not broken. The revalidation closure re-reads the store AFTER the async review, so a
            // /untrust, rebind or contract edit that landed while the model was thinking voids the pass.
            val review = if (!ownerBypass && noApprovalEnabled && snapshot.mode == FeishuTrustMode.REVIEWED) {
                reviewPreflight.evaluate(
                    snapshot, vetted.text, FeishuRoutes.projectName(workdir), senderOpenId, replyTo.messageId,
                ) {
                    // §21.4: the trust record alone can't see a mid-review /bind — the routes table is the
                    // half a rebind actually mutates, so the final check reads BOTH: the policy must be
                    // unchanged AND the chat's binding must still cover the very project this review ran against.
                    trust.stillMatches(chatId, trustKey, snapshot) && bindingStillCovers(chatId, workdir, autoRouted, isDirect = !isGroup)
                }.also {
                    logLine(
                        "[review] ${if (it.autoRun) "自动通过" else "转机主审批"}" +
                            "（risk=${it.result.risk} codes=${it.result.reasonCodes.joinToString(",").ifEmpty { "-" }} id=${it.reviewId.take(8)}…）",
                    )
                }
            } else {
                null
            }
            val reviewedAuto = review?.autoRun == true

            // issue #190: approval belongs to THIS request, before requester-controlled text reaches the agent.
            // The configured owner's bypass session keeps its existing direct path; every other request waits on
            // a one-off card and gains full execution authority only for the resulting turn.
            if (!ownerBypass && !trusted && !reviewedAuto) {
                val preview = buildString {
                    appendLine("发起人：${senderOpenId.ifBlank { "未知飞书用户" }}")
                    appendLine("项目：${FeishuRoutes.projectName(workdir)}")
                    appendLine()
                    append(prompt)
                }
                reply(replyTo, "⏳ 这次请求已发送给电脑所有者审批；通过后会自动执行并把结果发在这里。")
                val approved = core.registry.approveBridgeRequest(convoId, preview)
                mutex.withLock { pendingAsk.remove(convoId) }
                // a REVIEWED chat's escalation closes its audit trail with the owner's verdict
                review?.let {
                    reviewLog.record(reviewTurnEvent(if (approved) "escalated_owner_allowed" else "escalated_owner_denied", it, snapshot, senderOpenId, replyTo.messageId, workdir))
                }
                if (!approved) {
                    reply(replyTo, "⛔ 这次请求未获批准，未执行任何操作。")
                    return
                }
            }

            val done = CompletableDeferred<TurnDone>()
            mutex.withLock { turnWaiters[convoId] = done; replySlots[convoId] = ReplySlot(replyTo, promptId = promptId) }
            // issue #285 的另一半窗口：遗留结算轮的终态帧若赶在槽位安装**之前**到达，上面的归属门看不到它
            // （expected==null 走了正常回收路径），释放任务已被武装——1 秒轮询的 closeIfIdle 会在我们的
            // prompt 尚未被消费（queuedWork=false，不算 busy）时把进程连同 CLI 队列里的真实请求一起关掉。
            // 在这里取消任何已武装的释放：这条请求接下来必然亲自走到终态（成功/失败/超时），由它重新排程。
            cancelRelease(convoId)
            waiterInstalled = true
            var policyChangedBeforeHandoff = false
            suspend fun claimStillValid(expectedMode: FeishuTrustMode, handOff: suspend () -> Boolean): Boolean {
                val claim = policyGate.claim(
                    chatId = chatId,
                    validate = {
                        noApprovalEnabled && snapshot.mode == expectedMode &&
                            trust.stillMatches(chatId, trustKey, snapshot) &&
                            bindingStillCovers(chatId, workdir, autoRouted, isDirect = !isGroup)
                    },
                ) {
                    // The gate stays held only through arming this ONE turn's grant. Any /untrust or /bind
                    // that linearizes afterwards applies to later requests; it cannot retroactively un-arm
                    // a turn that has started, and it never waits for the turn to finish.
                    handOff()
                }
                if (!claim.valid) policyChangedBeforeHandoff = true
                return claim.value == true
            }

            val sent = when {
                ownerBypass -> core.registry.sendOwnerBypassBridgePrompt(vetted)
                // TRUSTED is durable broad authority for this exact chat/project; AUTO_TRUSTED remains
                // prompt-bound and is revoked at the end of this one turn.
                trusted -> claimStillValid(snapshot.mode) {
                    core.registry.sendTrustedBridgePrompt(vetted)
                }
                reviewedAuto -> claimStillValid(FeishuTrustMode.REVIEWED) {
                    core.registry.sendReviewedBridgePrompt(vetted, review!!.reviewId)
                }
                else -> core.registry.sendApprovedBridgePrompt(vetted)
            }
            if (trusted && sent) {
                // Ring gets a short excerpt for live debugging; the DURABLE line carries no prompt text.
                logLine("[trust] 完全信任执行：发起人 ${senderOpenId.ifBlank { "?" }} · ${FeishuRoutes.projectName(workdir)} ← ${prompt.replace('\n', ' ').take(80)}")
                trustLog.record("${java.time.Instant.now()} ran chat=$chatId 完全信任执行：发起人 ${senderOpenId.ifBlank { "?" }} · ${FeishuRoutes.projectName(workdir)}")
            }
            if (reviewedAuto) {
                val event = when {
                    sent -> "turn_started"
                    policyChangedBeforeHandoff -> "policy_changed_before_handoff"
                    else -> "handoff_failed"
                }
                reviewLog.record(reviewTurnEvent(event, review!!, snapshot, senderOpenId, replyTo.messageId, workdir))
            }
            if (!sent) {
                // the grant was minted but the hand-off lost a race (the conversation went busy) — the request
                // is NOT running, and its permit/grant was consumed, so the requester must send it again.
                // A reviewed pass is single-shot by the same rule: it may not be retried onto a later prompt.
                reply(
                    replyTo,
                    when {
                        policyChangedBeforeHandoff -> "⚠️ 群的绑定或信任策略在执行前已变化，这次请求未执行，请重新发送。"
                        trusted || reviewedAuto -> "⚠️ 这次请求未能启动，请重新发送。"
                        else -> "⚠️ 已批准的请求未能启动，请重新发送。"
                    },
                )
                return
            }
            awaitingTerminalFrame = true
            preserveLateReply = true
            // fast path first: most turns finish in seconds and never nudge
            var turn = withTimeoutOrNull(NUDGE_MS) { done.await() }
            if (turn == null && !done.isCompleted) {
                // no reply yet — if an owner approval is pending on the phone, tell the group WHY (and that
                // the result will still land here); if the turn is merely long, stay quiet. Either way keep
                // waiting, holding the chat lock so later messages stay ordered behind this turn.
                mutex.withLock { pendingAsk[convoId] }?.let { label ->
                    reply(replyTo, "⏳ 有个操作（$label）在等你手机上批准，批准后我会把结果发在这里。")
                }
                turn = withTimeoutOrNull(TURN_TIMEOUT_MS) { done.await() }
            }
            if (turn != null) {
                postTurn(convoId, turnText(turn))
            }
        } finally {
            if (waiterInstalled) {
                mutex.withLock {
                    turnWaiters.remove(convoId)
                    // Once sent, preserve the slot for a late terminal frame unless it already posted. Before
                    // send, no late reply can arrive and the slot is discarded with the idle conversation.
                    if (!preserveLateReply || replySlots[convoId]?.done == true) replySlots.remove(convoId)
                }
            }
            if (!awaitingTerminalFrame) scheduleRelease(convoId)
        }
    }

    /** A turn-level audit event for a REVIEWED request, correlated by review id (design §10). */
    private fun reviewTurnEvent(
        outcome: String,
        review: ReviewedPreflight.Outcome,
        snapshot: TrustSnapshot,
        senderOpenId: String,
        messageId: String,
        workdir: String,
    ) = FeishuReviewEvent(
        timestampMs = System.currentTimeMillis(),
        eventType = "turn",
        reviewId = review.reviewId,
        chatIdHash = FeishuReviewLog.hash(snapshot.chatId),
        senderHash = FeishuReviewLog.hash(senderOpenId),
        messageIdHash = FeishuReviewLog.hash(messageId),
        projectName = FeishuRoutes.projectName(workdir),
        mode = snapshot.mode.name,
        contractVersion = snapshot.contractVersion,
        finalOutcome = outcome,
    )

    /** Post a turn's final text to its group thread AT MOST ONCE — the inline caller and a late [onFrame]
     *  both try, and the loser no-ops, so an out-of-band approval never double-posts nor drops the reply. */
    private suspend fun postTurn(convoId: String, text: String) {
        val slot = mutex.withLock { replySlots[convoId]?.takeIf { !it.done }?.also { it.done = true } } ?: return
        reply(slot.target, text)
    }

    // 空文本如实说明状态而不是含糊的「(无回复)」（issue #285 诚实分型）：归属门保证走到这里的终态帧确属
    // 本请求，空文本只剩「这轮真的没产出文字」一种含义（如纯工具轮）。
    private fun turnText(t: TurnDone): String =
        t.error?.let { "⚠️ $it" } ?: t.finalText?.takeIf { it.isNotBlank() }
            ?: "ℹ️ 这轮已执行完成，但没有产生文字回复。"

    private suspend fun openOrReuse(
        key: String,
        chatId: String,
        isGroup: Boolean,
        workdir: String,
        ownerBypass: Boolean = false,
    ): OpenResult {
        // Only a convo opened against THIS SAME workdir may be reused or resumed. After a /bind moves the
        // chat to another project, the key still points at the old project's convo; reusing (or resuming
        // its session) would land prompts in the old workdir — the rebind-doesn't-take-effect bug. A
        // workdir change ⇒ don't reuse, don't resume, open clean below (keyWorkdir gets rewritten on open).
        val sameWorkdir = mutex.withLock { keyWorkdir[key] } == workdir
        if (sameWorkdir) {
            mutex.withLock { convoByKey[key] }?.let { existing ->
                // still live? reuse. Reaped? fall through to a resume-open with its sessionId.
                if (core.registry.liveCountOf(listOf(existing)) > 0) {
                    cancelRelease(existing)
                    return OpenResult.Opened(existing)
                }
            }
        }
        val resume = if (sameWorkdir) mutex.withLock { convoByKey[key]?.let { sessionOf[it] } } else null
        // open at the bridge's GRANTED ceiling, not the wire default DEFAULT — else a COLLABORATE/AUTONOMOUS
        // bridge still prompts the owner for every file edit (the mode never got raised; the guard's clamp
        // only ever LOWERS). vet()/BridgeGuard re-clamps to the tier ceiling, so this can't exceed the grant.
        val openVerdict = vet(OpenSession(workdir = workdir, resumeId = resume, mode = AccessTier.ceiling(spec.tier)))
        val open = (openVerdict as? BridgeVerdict.Allow)?.frame as? OpenSession
            ?: return OpenResult.Denied((openVerdict as BridgeVerdict.Deny).code)
        // #242: the session-stable context, computed HERE (past every reuse/resume early-return, so a warm
        // conversation pays nothing) and baked into the launching agent's system prompt. A group name that
        // isn't cached yet gets a bounded wait; anything slower degrades to a nameless group.
        val preamble = bridgeContextPreamble(
            chatName = if (isGroup) chatNameOrNull(chatId) else null,
            isGroup = isGroup,
            projectName = FeishuRoutes.projectName(workdir),
        )
        val opened = CompletableDeferred<String>()
        val openKey = "open-${System.nanoTime()}"
        mutex.withLock { openWaiters[openKey] = opened }
        try {
            // Keep the legacy command allow-list on the conversation for wire/backward compatibility with
            // existing bridge records. Built-in Feishu requests no longer depend on it: issue #190 approves
            // the exact request before execution, then grants that turn full authority.
            // ownerBypass (issue #91): the owner's DEDICATED session opens with a trusted in-process identity
            // flag. FeishuEngine later exchanges it for one-turn OWNER_BYPASS grants; the flag itself no longer
            // auto-allows tools, so cancel can revoke the current turn. Never set for shared/group sessions or wire.
            core.router.handle(
                open, sink, spec.name,
                bridgeAllowedCommands = spec.allowedCommands,
                bridgeContextPreamble = preamble,
                ownerBypass = ownerBypass,
            ) { convoId ->
                guard.noteOpened(convoId)
                openWaiters[openKey]?.complete(convoId)
            }
            val convoId = withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() } ?: return OpenResult.TimedOut
            mutex.withLock {
                // the key may already point at an older convo (a rebind opens clean instead of reusing) —
                // that convo is now unreachable, so drop its session/slot rows with it rather than leaking
                // them forever under a convoId nothing maps to any more (#265)
                convoByKey.put(key, convoId)?.takeIf { it != convoId }?.let { stale ->
                    sessionOf.remove(stale); replySlots.remove(stale); pendingAsk.remove(stale)
                }
                keyWorkdir[key] = workdir
            }
            return OpenResult.Opened(convoId)
        } finally {
            mutex.withLock { openWaiters.remove(openKey) }
        }
    }

    /** The same verdict an external bridge's frames receive; retain its code for an honest chat reply. */
    private suspend fun vet(frame: Frame): BridgeVerdict {
        val liveOwned = if (frame is OpenSession) core.registry.liveCountOf(guard.ownedConvoIds()) else 0
        return when (val v = guard.vet(frame, System.currentTimeMillis(), liveOwned)) {
            is BridgeVerdict.Allow -> v
            is BridgeVerdict.Deny -> {
                logLine("[guard] ${frame::class.simpleName} denied: ${v.code.wire}")
                v
            }
        }
    }

    private fun openDeniedMessage(code: BridgeDenyCode): String = when (code) {
        BridgeDenyCode.BAD_WORKDIR -> "⚠️ 当前绑定目录不可用，或已不在这个 Bridge 的项目范围内。请重新 /bind。"
        BridgeDenyCode.TOO_MANY_SESSIONS ->
            "⚠️ 正在处理的请求已达并发上限（${spec.maxSessions}/${spec.maxSessions}），请等其中一个完成后重试。"
        BridgeDenyCode.OPEN_RATE -> "⚠️ 一分钟内新建会话过多，请稍后再试。"
        BridgeDenyCode.NOT_OWN_SESSION -> "⚠️ 原会话已无法安全续接，请发送 /new 后重试。"
        else -> "⚠️ 这个 Bridge 不允许打开该会话（${code.wire}）。"
    }

    private fun promptDeniedMessage(code: BridgeDenyCode): String = when (code) {
        BridgeDenyCode.PROMPT_RATE -> "⚠️ 一分钟内发送消息过多，请稍后再试。"
        BridgeDenyCode.PROMPT_TOO_LARGE -> "⚠️ 消息过长，请缩短后重试。"
        BridgeDenyCode.IMAGES_DENIED -> "⚠️ 这个 Bridge 暂不支持图片附件。"
        BridgeDenyCode.NOT_OWN_SESSION -> "⚠️ 该会话已经失效，请发送 /new 后重试。"
        else -> "⚠️ 消息未获 Bridge 接受（${code.wire}）。"
    }

    /**
     * Reclaim a settled chat's live process without losing context. Busy state is authoritative: background
     * jobs, pending cards, and continuation grace keep retrying here and continue to count against maxSessions.
     */
    private fun scheduleRelease(convoId: String) {
        val job = scope.launch {
            while (running) {
                if (core.registry.closeIfIdle(convoId)) {
                    guard.noteClosed(convoId)
                    logLine("[session] released idle conversation ${convoId.take(8)}…; context remains resumable")
                    return@launch
                }
                if (core.registry.liveCountOf(listOf(convoId)) == 0) {
                    guard.noteClosed(convoId)
                    return@launch
                }
                delay(RELEASE_RETRY_MS)
            }
        }
        releaseJobs.put(convoId, job)?.cancel()
        job.invokeOnCompletion { releaseJobs.remove(convoId, job) }
    }

    private fun cancelRelease(convoId: String) {
        releaseJobs.remove(convoId)?.cancel()
    }

    private suspend fun onFrame(frame: Frame) {
        when (frame) {
            is SessionLive -> mutex.withLock {
                frame.sessionId?.let { sid ->
                    sessionOf[frame.convoId] = sid
                    guard.noteSession(frame.convoId, sid) // lets a later resume pass the guard's own-session check
                }
            }
            // a permission ask crossed the sink → an owner decision is pending on the phone; remember the
            // tool so the nudge can name it. The bridge never ANSWERS the ask — that's the phone's job.
            is PermissionAsk -> mutex.withLock { pendingAsk[frame.convoId] = frame.title.ifBlank { frame.tool } }
            is TurnDone -> {
                // 归属门（issue #285）：恢复长会话时，CLI 先为遗留后台任务跑零轮结算（task_notification 批＋
                // 空文本 result），其终态帧与刚进来的新请求同 convoId 却不属于它。凭证=prompt 消费账本：槽位
                // 等的那条请求还没拿到消费凭证（UserReplay/一次性启动结算/本地命令回执）之前，任何终态帧都
                // 是历史轮次的——不结算 waiter、不发帖、更不释放进程（释放会连坐杀掉 CLI 队列里的真请求）。
                // 属于这条请求的终态帧稍后仍会经过这里。三种授权模式（逐请求/智能审核/完全信任）共用本门。
                val awaited = mutex.withLock { replySlots[frame.convoId]?.takeIf { !it.done }?.promptId }
                val foreign = awaited != null &&
                    core.registry.promptFate(frame.convoId, awaited) != dev.ccpocket.daemon.conversation.PromptFate.CONSUMED
                if (foreign) {
                    logLine("[turn] 忽略遗留轮次的终态帧：请求 ${awaited?.take(8)}… 尚未开始执行（convo ${frame.convoId.take(8)}…）")
                } else {
                    mutex.withLock { turnWaiters.remove(frame.convoId) }?.complete(frame)
                    mutex.withLock { pendingAsk.remove(frame.convoId) }
                    postTurn(frame.convoId, turnText(frame)) // idempotent; delivers a late reply after approval
                    scheduleRelease(frame.convoId)
                }
            }
            is PocketError -> {
                logLine("[engine] ${frame.code}: ${frame.message}")
                frame.convoId?.let { id ->
                    mutex.withLock { turnWaiters.remove(id) }?.complete(TurnDone(id, error = frame.message))
                    mutex.withLock { pendingAsk.remove(id) }
                    postTurn(id, "⚠️ ${frame.message}")
                    scheduleRelease(id)
                }
            }
            // deny-by-default egress (see [sink]): any frame NOT named above is dropped here, and nothing
            // above forwards raw frame content — so a future frame kind can't leak into the group by omission
            else -> {} // chunks, tool events, ask-withdrawn, … — rendered on the phone; the engine needs none
        }
    }

    private companion object {
        const val SEEN_MESSAGES_MAX = 512   // at-least-once dedup LRU capacity (see seenMessages)
        const val TOPIC_STATE_MAX = 512     // per-topic conversation state kept before the oldest ages out (LruMap)
        const val GROUP_CHAT_TYPE = "group" // the chat_type a topic sweep reasons about (see resetAllTopics)
        const val CARD_ORIGINS_MAX = 256    // live /menu cards we can still resolve a press for (cardOrigins)
        const val NUDGE_MS = 25_000L        // no reply yet after this + an approval pending → nudge the group
        const val TURN_TIMEOUT_MS = 300_000L
        const val OPEN_TIMEOUT_MS = 30_000L
        const val CHAT_NAME_WAIT_MS = 1_500L // bounded wait for a first group-name fetch (see chatNameOrNull)
        const val RELEASE_RETRY_MS = 1_000L
        const val MAX_REPLY_CHARS = 20_000 // feishu text-message ceiling with headroom
        // stands in as the "[用户本次的指令]" line when the user only @'d the bot on a quoted message. Worded as
        // a note ABOUT the request rather than as words the user didn't type, so the model isn't misled about
        // who said what — the actual content arrives above it as the quoted context.
        const val QUOTE_ONLY_PROMPT = "（用户只 @ 了机器人并引用了上面这条消息，没有另外写文字——请针对被引用的内容处理。）"
        const val UNREADABLE_QUOTE_REPLY = "⚠️ 读不到你引用的那条消息，请把要处理的内容直接发出来。"
        // a direct-inbox line at or under this length with an active project is a follow-up ("继续"、"对，
        // 就这样改") — sticks without a router call; longer lines get the model's read
        const val DIRECT_FOLLOWUP_MAX_CHARS = 24
    }
}
