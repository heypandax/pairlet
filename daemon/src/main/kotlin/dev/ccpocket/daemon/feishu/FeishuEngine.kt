package dev.ccpocket.daemon.feishu

import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

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
    private val routes = FeishuRoutes(File(stateDir, "feishu-routes.json"))
    private val commands = FeishuCommands(routes, spec.workdirs, adminOpenId, chatOwnerOf = { chatOwners[it] })
    private val guard = BridgeGuard(spec)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: com.lark.oapi.ws.Client? = null
    private var api: com.lark.oapi.Client? = null

    // conversation state: ONE conversation per chat (key = chatId) — see onMessage. convo -> session
    // survives idle-reap so a later message resumes with full context.
    private val mutex = Mutex()
    private val convoByKey = HashMap<String, String>()
    // the workdir each key's convo was opened against. A /bind can move a chat to another project mid-life,
    // and the chat's key still maps to the OLD project's convo — reusing/resuming it would keep sending
    // prompts to the old workdir (the "rebind took no effect until restart/​/new" bug). openOrReuse compares
    // this against the now-requested workdir and, on a mismatch, opens CLEAN instead of reusing or resuming.
    private val keyWorkdir = HashMap<String, String>()
    private val sessionOf = HashMap<String, String>()
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
    private data class ReplySlot(val replyTo: String, var done: Boolean = false)
    private val replySlots = HashMap<String, ReplySlot>()

    private sealed interface OpenResult {
        data class Opened(val convoId: String) : OpenResult
        data class Denied(val code: BridgeDenyCode) : OpenResult
        data object TimedOut : OpenResult
    }
    // convoId -> the label of a permission ask currently waiting on the owner's phone, so the "still
    // working" nudge can name it ("Run command 在等你批准") instead of a bare, scary timeout.
    private val pendingAsk = HashMap<String, String>()
    // per-chat turn serialization: two messages in one chat share a conversation, and a second prompt
    // arriving mid-turn would OVERWRITE the first's turn waiter — the first reply then never posts and
    // its sender sees a phantom timeout. One lock per chat queues them in arrival order instead; other
    // chats stay fully parallel.
    private val chatLocks = HashMap<String, Mutex>()
    // the bot's own open_id (fetched at start) — the mention filter's ground truth. Null until fetched;
    // fallback then is "any mention", the pre-fix behaviour, so a slow fetch degrades soft.
    @Volatile private var botOpenId: String? = null
    // Feishu delivers events AT-LEAST-ONCE; this bounded LRU of message ids drops a redelivered duplicate so
    // one message never fires the same prompt twice. Guarded by its own monitor — onMessage runs on the lark
    // SDK's dispatcher threads (not a coroutine), so the engine's suspend mutex can't cover it.
    private val seenMessages = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SEEN_MESSAGES_MAX
    }
    // Feishu GROUP OWNER per chat (open_id), lazily fetched — the fallback /bind authority when no admin
    // open_id is configured, so a group's own owner can bind with no env and no restart. ConcurrentHashMap:
    // written from scope coroutines, read on the lark dispatcher thread.
    // Security notes (issue #91 crypto review): (1) with no admin set this makes the group OWNER the /bind
    // authority — a deliberate trust widening; a configured admin removes the fallback entirely. (2) No TTL:
    // an ownership transfer keeps the OLD owner as bind authority until the engine restarts — bounded, since
    // binding only selects an already-allow-listed workdir and every action still hits owner approval.
    private val chatOwners = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val http = java.net.http.HttpClient.newHttpClient()

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
            api = com.lark.oapi.Client.newBuilder(appId, appSecret).build()
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
        logLine("[engine] stopped")
        log.info("feishu engine \"$name\" stopped")
    }

    override fun shutdown() { stop(); scope.cancel() }

    /** ConvoIds this engine ever opened — the caller intersects with live registry state for the
     *  "active now" pulse on the management pages, same as an external bridge's guard. */
    override fun ownedConvoIds(): Set<String> = guard.ownedConvoIds()

    /**
     * REVOKE/REMOVE teardown ONLY: force-close every conversation this engine opened so its in-flight
     * turns END at once — the "revoke = sessions end" promise. [stop] alone only drops the feishu link;
     * the convos it opened keep running in the registry until the idle reaper reclaims them, which is
     * exactly the window a revoked bridge must not have. Mirrors the guest-revoke path in DeviceSessions:
     * the per-engine owned ledger covers convos this instance still tracks, and closeByOrigin ALSO reaps
     * ones opened by an EARLIER engine instance (a reconfigure builds a fresh engine and drops the old
     * ledger) — every turn routes with origin = [spec].name, so the label is the exact marker. A plain
     * stop()/restart deliberately does NOT call this: it reuses the same engine and its live convos for
     * continuity.
     */
    override suspend fun closeOwnedConvos() {
        ownedConvoIds().forEach { runCatching { core.registry.close(it, force = true) } }
        runCatching { core.registry.closeByOrigin(spec.name) }
    }

    // ── inbound chat events ──

    /**
     * The bot's own open_id, from GET /bot/v3/info — what makes the mention filter PRECISE: without it,
     * "@some colleague check this" in a chat the bot sits in would count as addressing the bot (any-mention
     * was the reference adapter's behaviour, and it misfires whenever the app receives all group messages).
     * Best-effort async: until (or unless) it lands, the filter falls back to any-mention and says so once.
     */
    /** A tenant_access_token for app-authenticated Feishu REST calls (bot info, chat owner), or null. */
    private fun tenantToken(): String? = runCatching {
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("""{"app_id":"$appId","app_secret":"$appSecret"}"""))
            .build()
        Json.parseToJsonElement(http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body())
            .jsonObject["tenant_access_token"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun fetchBotIdentity() {
        scope.launch {
            runCatching {
                val token = tenantToken() ?: error("no tenant_access_token in reply")
                val infoReq = java.net.http.HttpRequest.newBuilder(java.net.URI("https://open.feishu.cn/open-apis/bot/v3/info"))
                    .header("Authorization", "Bearer $token").GET().build()
                val openId = Json.parseToJsonElement(http.send(infoReq, java.net.http.HttpResponse.BodyHandlers.ofString()).body())
                    .jsonObject["bot"]?.jsonObject?.get("open_id")?.jsonPrimitive?.content ?: error("no bot.open_id in reply")
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
                val token = tenantToken() ?: return@launch
                val req = java.net.http.HttpRequest.newBuilder(java.net.URI("https://open.feishu.cn/open-apis/im/v1/chats/$chatId?user_id_type=open_id"))
                    .header("Authorization", "Bearer $token").GET().build()
                val owner = Json.parseToJsonElement(http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body())
                    .jsonObject["data"]?.jsonObject?.get("owner_id")?.jsonPrimitive?.content
                // only a USER open_id (ou_…) may become a bind authority — a bot-owned group (cli_…) or any
                // other shape fails closed (never cached ⇒ /bind stays "confirming, retry"), never authorizes
                if (owner != null && owner.startsWith("ou_")) chatOwners[chatId] = owner
            }
        }
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

    /** Fetch one message's plain text by id (GET /im/v1/messages/:id → data.items[0]) — the quoted-context
     *  reader. Null on any failure (no token, HTTP error, empty items, parse miss) so the caller degrades to
     *  "no quote". Reuses the engine's tenant-token + raw-HTTP path; runs on Dispatchers.IO via the caller. */
    private suspend fun fetchMessageText(messageId: String): String? = runCatching {
        // defense-in-depth: the id comes from Feishu's own event (om_…-shaped, not user-typed), but validate
        // its charset before it lands in the URL path so a malformed/crafted id can never alter the request.
        if (!messageId.matches(Regex("^[A-Za-z0-9_-]+$"))) return null
        val token = tenantToken() ?: return null
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI("https://open.feishu.cn/open-apis/im/v1/messages/$messageId"))
            .header("Authorization", "Bearer $token").GET().build()
        val item = Json.parseToJsonElement(http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body())
            .jsonObject["data"]?.jsonObject?.get("items")?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull()?.jsonObject ?: return null
        val msgType = item["msg_type"]?.jsonPrimitive?.content
        val content = item["body"]?.jsonObject?.get("content")?.jsonPrimitive?.content
        FeishuMessageText.plainText(msgType, content).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun onMessage(event: P2MessageReceiveV1) {
        val data = event.event ?: return
        val msg = data.message ?: return
        val mentions = msg.mentions ?: return
        if (mentions.isEmpty()) return
        // precise when we know who we are: the BOT must be among the mentioned, or the message isn't for
        // us — "@colleague look at this" in our chat must stay none of our business
        val self = botOpenId
        if (self != null && mentions.none { it.id?.openId == self }) return
        var text = runCatching {
            Json.parseToJsonElement(msg.content ?: return).jsonObject["text"]?.jsonPrimitive?.content
        }.getOrNull()?.trim() ?: return
        for (m in mentions) m.key?.let { text = text.replace(it, "").trim() }
        if (text.isEmpty()) return
        val chatId = msg.chatId ?: return
        // warm the group-owner cache for the /bind fallback — only when no admin is set (else it's unused)
        if (adminOpenId.isNullOrBlank() && !chatOwners.containsKey(chatId)) fetchChatOwner(chatId)
        val sender = data.sender?.senderId?.openId.orEmpty()
        val replyTo = msg.messageId ?: return
        // requirement 2: when this message REPLIES to an earlier one, parentId is the quoted message and
        // rootId the thread root — pulled below so Claude sees the original the user is pointing at, not
        // just their new one-liner. Absent on a plain new message. Only used for natural-language turns.
        val parentId = msg.parentId
        val rootId = msg.rootId
        // Feishu delivers events at-least-once — the SAME message can arrive again on a retry / reconnect,
        // and without dedup that re-runs the prompt (issue #91). Drop a message id we've already handled.
        if (!firstSeen(replyTo)) return

        // OWNER BYPASS (issue #91): the CONFIGURED owner's OWN messages run in a SEPARATE, dedicated session
        // (keyed apart from the group's) that auto-allows — race-free, because a session never mixes senders.
        // Everyone else drives the shared, approval-gated session. Gated on the toggle + a set admin id.
        val ownerTurn = ownerBypassEnabled && !adminOpenId.isNullOrBlank() && sender == adminOpenId
        val convoKey = if (ownerTurn) "$chatId owner" else chatId

        when (val action = commands.handle(text, chatId, sender)) {
            is ChatAction.Ignore -> {}
            is ChatAction.Reply -> reply(replyTo, action.text)
            is ChatAction.Reset -> scope.launch {
                // drop the SENDER's conversation (owner and group have SEPARATE sessions per chat — see
                // convoKey) under the same per-session lock a turn holds, so /new can't race a running turn
                val lock = mutex.withLock { chatLocks.getOrPut(convoKey) { Mutex() } }
                lock.withLock { mutex.withLock {
                    convoByKey.remove(convoKey)?.let { sessionOf.remove(it); replySlots.remove(it); pendingAsk.remove(it) }
                    keyWorkdir.remove(convoKey)
                } }
                reply(replyTo, action.note)
            }
            is ChatAction.Ask -> scope.launch {
                // an auto-bind's feedback posts NOW — the turn behind it can take minutes
                action.note?.let { reply(replyTo, it) }
                logLine("[chat] ${FeishuRoutes.projectName(action.workdir)} ← $chatId: ${text.take(80)}")
                // requirement 2: a natural-language reply carries the QUOTED message (+ thread root) into the
                // prompt. Skipped for slash pass-throughs ("/clear" etc.) — those act on the user's own line,
                // not the quoted one. Network fetch here, safely inside the coroutine (never the lark thread).
                val prompt = if (!text.startsWith("/")) withQuotedContext(action.prompt, parentId, rootId) else action.prompt
                // ONE conversation per chat (context carries across messages), one turn at a time per
                // chat (see chatLocks) — a second message queues instead of clobbering the first's waiter.
                // ask() posts its OWN reply (inline, or late via onFrame after an approval) so the result
                // survives an out-of-band owner tap; we only surface a hard failure to open/drive the turn.
                val lock = mutex.withLock { chatLocks.getOrPut(convoKey) { Mutex() } }
                lock.withLock {
                    runCatching { ask(convoKey, action.workdir, prompt, replyTo, sender, ownerTurn) }
                        .onFailure { e ->
                            log.warn("feishu turn failed: ${e.message}")
                            reply(replyTo, "⚠️ 出错了：${e.message}")
                        }
                }
            }
        }
    }

    private fun reply(messageId: String, text: String) {
        // last-ditch outbound scrub: a read of a secret file is auto-allowed (read-only, no phone prompt),
        // so its contents can ride the reply into the group — redact obvious credentials here. Applied at
        // the single reply choke point, so it also catches an error string that echoed file content.
        val (scrubbed, redacted) = SecretRedactor.redact(text)
        val out = if (redacted) "$scrubbed\n\n（⚠️ 回复中疑似密钥已自动隐去）" else scrubbed
        val body = kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(out.take(MAX_REPLY_CHARS)))
        }.toString()
        runCatching {
            api?.im()?.v1()?.message()?.reply(
                ReplyMessageReq.newBuilder().messageId(messageId)
                    .replyMessageReqBody(ReplyMessageReqBody.newBuilder().content(body).msgType("text").build())
                    .build(),
            )
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
        workdir: String,
        prompt: String,
        replyTo: String,
        senderOpenId: String,
        ownerBypass: Boolean = false,
    ) {
        val convoId = when (val opened = openOrReuse(key, workdir, ownerBypass)) {
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
            // Reject an over-limit/oversized prompt before asking the owner to approve something that cannot run.
            val promptVerdict = vet(SendPrompt(convoId, prompt))
            val vetted = (promptVerdict as? BridgeVerdict.Allow)?.frame as? SendPrompt
            if (vetted == null) {
                val code = (promptVerdict as? BridgeVerdict.Deny)?.code ?: BridgeDenyCode.FORBIDDEN
                reply(replyTo, promptDeniedMessage(code))
                return
            }

            // issue #190: approval belongs to THIS request, before requester-controlled text reaches the agent.
            // The configured owner's bypass session keeps its existing direct path; every other request waits on
            // a one-off card and gains full execution authority only for the resulting turn.
            if (!ownerBypass) {
                val preview = buildString {
                    appendLine("发起人：${senderOpenId.ifBlank { "未知飞书用户" }}")
                    appendLine("项目：${FeishuRoutes.projectName(workdir)}")
                    appendLine()
                    append(prompt)
                }
                reply(replyTo, "⏳ 这次请求已发送给电脑所有者审批；通过后会自动执行并把结果发在这里。")
                val approved = core.registry.approveBridgeRequest(convoId, preview)
                mutex.withLock { pendingAsk.remove(convoId) }
                if (!approved) {
                    reply(replyTo, "⛔ 这次请求未获批准，未执行任何操作。")
                    return
                }
            }

            val done = CompletableDeferred<TurnDone>()
            mutex.withLock { turnWaiters[convoId] = done; replySlots[convoId] = ReplySlot(replyTo) }
            waiterInstalled = true
            val sent =
                if (ownerBypass) {
                    core.router.handle(vetted, sink, spec.name)
                    true
                } else {
                    core.registry.sendApprovedBridgePrompt(vetted)
                }
            if (!sent) {
                reply(replyTo, "⚠️ 已批准的请求未能启动，请重新发送。")
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

    /** Post a turn's final text to its group thread AT MOST ONCE — the inline caller and a late [onFrame]
     *  both try, and the loser no-ops, so an out-of-band approval never double-posts nor drops the reply. */
    private suspend fun postTurn(convoId: String, text: String) {
        val slot = mutex.withLock { replySlots[convoId]?.takeIf { !it.done }?.also { it.done = true } } ?: return
        reply(slot.replyTo, text)
    }

    private fun turnText(t: TurnDone): String =
        t.error?.let { "⚠️ $it" } ?: t.finalText?.takeIf { it.isNotBlank() } ?: "(无回复)"

    private suspend fun openOrReuse(key: String, workdir: String, ownerBypass: Boolean = false): OpenResult {
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
        val opened = CompletableDeferred<String>()
        val openKey = "open-${System.nanoTime()}"
        mutex.withLock { openWaiters[openKey] = opened }
        try {
            // Keep the legacy command allow-list on the conversation for wire/backward compatibility with
            // existing bridge records. Built-in Feishu requests no longer depend on it: issue #190 approves
            // the exact request before execution, then grants that turn full authority.
            // ownerBypass (issue #91): the owner's DEDICATED session opens with the trusted in-process
            // full-trust flag → its PermissionBridge auto-allows. Never set for the shared / group session,
            // and impossible for an external adapter to set (it never reaches this in-process handle call).
            core.router.handle(open, sink, spec.name, bridgeAllowedCommands = spec.allowedCommands, ownerBypass = ownerBypass) { convoId ->
                guard.noteOpened(convoId)
                openWaiters[openKey]?.complete(convoId)
            }
            val convoId = withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() } ?: return OpenResult.TimedOut
            mutex.withLock { convoByKey[key] = convoId; keyWorkdir[key] = workdir }
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
                mutex.withLock { turnWaiters.remove(frame.convoId) }?.complete(frame)
                mutex.withLock { pendingAsk.remove(frame.convoId) }
                postTurn(frame.convoId, turnText(frame)) // idempotent; delivers a late reply after approval
                scheduleRelease(frame.convoId)
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
        const val NUDGE_MS = 25_000L        // no reply yet after this + an approval pending → nudge the group
        const val TURN_TIMEOUT_MS = 300_000L
        const val OPEN_TIMEOUT_MS = 30_000L
        const val RELEASE_RETRY_MS = 1_000L
        const val MAX_REPLY_CHARS = 20_000 // feishu text-message ceiling with headroom
    }
}
