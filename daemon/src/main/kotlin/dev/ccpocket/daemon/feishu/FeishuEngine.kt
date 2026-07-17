package dev.ccpocket.daemon.feishu

import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import dev.ccpocket.daemon.DaemonCore
import dev.ccpocket.daemon.bridge.BridgeGuard
import dev.ccpocket.daemon.bridge.BridgeSpec
import dev.ccpocket.daemon.bridge.BridgeVerdict
import dev.ccpocket.daemon.bridge.InProcessBridgeEngine
import dev.ccpocket.daemon.util.logger
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val sessionOf = HashMap<String, String>()
    private val turnWaiters = HashMap<String, CompletableDeferred<TurnDone>>()
    private val openWaiters = HashMap<String, CompletableDeferred<String>>() // openId -> convoId
    // a turn's reply target + a one-shot guard: the final text posts to the group EXACTLY ONCE, whether
    // ask() delivers it inline (finished within the wait) or a late TurnDone does (the owner approved a
    // permission ask on their phone minutes later — the bridge's whole reason to exist). Keyed by convoId.
    private data class ReplySlot(val replyTo: String, var done: Boolean = false)
    private val replySlots = HashMap<String, ReplySlot>()
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
        // Feishu delivers events at-least-once — the SAME message can arrive again on a retry / reconnect,
        // and without dedup that re-runs the prompt (issue #91). Drop a message id we've already handled.
        if (!firstSeen(replyTo)) return

        when (val action = commands.handle(text, chatId, sender)) {
            is ChatAction.Ignore -> {}
            is ChatAction.Reply -> reply(replyTo, action.text)
            is ChatAction.Reset -> scope.launch {
                // drop the chat's conversation under the same per-chat lock a turn holds, so /new can't
                // race a running turn and leave a half-cleared mapping
                val lock = mutex.withLock { chatLocks.getOrPut(chatId) { Mutex() } }
                lock.withLock { mutex.withLock {
                    convoByKey.remove(chatId)?.let { sessionOf.remove(it); replySlots.remove(it); pendingAsk.remove(it) }
                } }
                reply(replyTo, action.note)
            }
            is ChatAction.Ask -> scope.launch {
                // an auto-bind's feedback posts NOW — the turn behind it can take minutes
                action.note?.let { reply(replyTo, it) }
                logLine("[chat] ${FeishuRoutes.projectName(action.workdir)} ← $chatId: ${text.take(80)}")
                // ONE conversation per chat (context carries across messages), one turn at a time per
                // chat (see chatLocks) — a second message queues instead of clobbering the first's waiter.
                // ask() posts its OWN reply (inline, or late via onFrame after an approval) so the result
                // survives an out-of-band owner tap; we only surface a hard failure to open/drive the turn.
                val lock = mutex.withLock { chatLocks.getOrPut(chatId) { Mutex() } }
                lock.withLock {
                    runCatching { ask(chatId, action.workdir, action.prompt, replyTo) }
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
    private suspend fun ask(key: String, workdir: String, prompt: String, replyTo: String) {
        val convoId = openOrReuse(key, workdir)
            ?: run { reply(replyTo, "⚠️ 无法打开会话（超出并发/频率限制或目录不可用）"); return }
        val done = CompletableDeferred<TurnDone>()
        mutex.withLock { turnWaiters[convoId] = done; replySlots[convoId] = ReplySlot(replyTo) }
        var leaveArmed = false
        try {
            val vetted = vet(SendPrompt(convoId, prompt))
                ?: run { reply(replyTo, "⚠️ 消息被限流或过长，请稍后再试"); return }
            core.router.handle(vetted, sink, spec.name)
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
            } else {
                // gave up waiting, but the owner may still approve minutes later — leave the slot armed so
                // onFrame delivers the eventual TurnDone to the group instead of dropping it.
                leaveArmed = true
            }
        } finally {
            mutex.withLock {
                turnWaiters.remove(convoId)
                // release the slot unless we're deliberately holding it for a late TurnDone (and a racing
                // onFrame post hasn't already consumed it)
                if (!leaveArmed || replySlots[convoId]?.done == true) replySlots.remove(convoId)
            }
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

    private suspend fun openOrReuse(key: String, workdir: String): String? {
        mutex.withLock { convoByKey[key] }?.let { existing ->
            // still live? reuse. Reaped? fall through to a resume-open with its sessionId.
            if (core.registry.liveCountOf(listOf(existing)) > 0) return existing
        }
        val resume = mutex.withLock { convoByKey[key]?.let { sessionOf[it] } }
        val open = vet(OpenSession(workdir = workdir, resumeId = resume)) as? OpenSession ?: return null
        val opened = CompletableDeferred<String>()
        val openKey = "open-${System.nanoTime()}"
        mutex.withLock { openWaiters[openKey] = opened }
        try {
            core.router.handle(open, sink, spec.name) { convoId ->
                guard.noteOpened(convoId)
                openWaiters[openKey]?.complete(convoId)
            }
            val convoId = withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() } ?: return null
            mutex.withLock { convoByKey[key] = convoId }
            return convoId
        } finally {
            mutex.withLock { openWaiters.remove(openKey) }
        }
    }

    /** The same vet an external bridge's frames pass in DeviceSessions — deny → null (with a log line). */
    private suspend fun vet(frame: Frame): Frame? {
        val liveOwned = if (frame is OpenSession) core.registry.liveCountOf(guard.ownedConvoIds()) else 0
        return when (val v = guard.vet(frame, System.currentTimeMillis(), liveOwned)) {
            is BridgeVerdict.Allow -> v.frame
            is BridgeVerdict.Deny -> {
                logLine("[guard] ${frame::class.simpleName} denied: ${v.code.wire}")
                null
            }
        }
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
            }
            is PocketError -> {
                logLine("[engine] ${frame.code}: ${frame.message}")
                frame.convoId?.let { id ->
                    mutex.withLock { turnWaiters.remove(id) }?.complete(TurnDone(id, error = frame.message))
                    mutex.withLock { pendingAsk.remove(id) }
                    postTurn(id, "⚠️ ${frame.message}")
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
        const val MAX_REPLY_CHARS = 20_000 // feishu text-message ceiling with headroom
    }
}
