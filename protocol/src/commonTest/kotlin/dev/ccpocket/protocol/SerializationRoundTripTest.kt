package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pre-#119 `pocket/sessions` body shape (no `groups`) — used to prove an old app skips a populated
 *  `groups` array-of-objects via ignoreUnknownKeys. A top-level @Serializable so the plugin generates its serializer. */
@kotlinx.serialization.Serializable
private data class OldSessions(val workdir: String, val items: List<SessionSummary> = emptyList())

class SerializationRoundTripTest {

    @Test
    fun openSession_discriminator_defaults_and_null_omission() {
        val env = Envelope(id = "1", ts = 7, body = OpenSession(workdir = "/x"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/session.open\"" in json, json)
        assertTrue("\"to\":\"PEER\"" in json, json)        // encodeDefaults
        assertTrue("\"mode\":\"default\"" in json, json)   // encodeDefaults
        assertFalse("resumeId" in json, json)              // explicitNulls=false
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun all_permission_modes_roundtrip() {
        val expected = mapOf(
            PermissionMode.DEFAULT to "default",
            PermissionMode.ACCEPT_EDITS to "acceptEdits",
            PermissionMode.PLAN to "plan",
            PermissionMode.BYPASS_PERMISSIONS to "bypassPermissions",
        )
        for ((mode, name) in expected) {
            assertEquals("\"$name\"", PocketJson.encodeToString(mode))
            assertEquals(mode, PocketJson.decodeFromString<PermissionMode>("\"$name\""))
        }
    }

    @Test
    fun decision_serializes_lowercase() {
        assertEquals("\"allow\"", PocketJson.encodeToString(Decision.ALLOW))
        assertEquals("\"deny\"", PocketJson.encodeToString(Decision.DENY))
    }

    @Test
    fun assistantChunk_with_text_piece_roundtrips() {
        val env = Envelope(id = "2", ts = 0, body = AssistantChunk("c1", 3, StreamPiece.Text("hi")))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/chunk\"" in json, json)
        assertTrue("\"t\":\"text\"" in json, json) // nested StreamPiece discriminator
        val back = PocketJson.decodeFromString<Envelope>(json)
        assertEquals(env, back)
        assertTrue((back.body as AssistantChunk).piece is StreamPiece.Text)
    }

    @Test
    fun unknown_keys_are_tolerated() {
        val json =
            """{"id":"9","ts":0,"to":"PEER","body":{"t":"pocket/prompt","convoId":"c","text":"hey","future":123}}"""
        val back = PocketJson.decodeFromString<Envelope>(json)
        assertEquals(SendPrompt("c", "hey"), back.body)
        // structured unknown keys too — the exact skip path an OLD peer takes over a NEW peer's
        // added array-of-objects/map fields (e.g. PermissionAsk.questions, PermissionVerdict.answers)
        val structured =
            """{"id":"10","ts":0,"to":"PEER","body":{"t":"pocket/verdict","convoId":"c","askId":"a","decision":"allow",
               "future":[{"q":"x","opts":[{"a":1}]}],"futureMap":{"k":"v"}}}"""
        assertEquals(PermissionVerdict("c", "a", Decision.ALLOW), PocketJson.decodeFromString<Envelope>(structured).body)
    }

    @Test
    fun permissionAsk_questions_roundtrip_and_are_absent_by_default() {
        // AskUserQuestion: a new daemon attaches the structured questions
        val ask = PermissionAsk(
            convoId = "c1", askId = "a1", tool = "AskUserQuestion", inputPreview = "Which color?",
            questions = listOf(
                AskQuestion("Which color?", header = "Color", options = listOf(AskOption("Red", "r"), AskOption("Blue"))),
                AskQuestion("Which sections?", multiSelect = true),
            ),
        )
        val env = Envelope(id = "q1", ts = 0, body = ask)
        val json = PocketJson.encodeToString(env)
        assertTrue("\"questions\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
        // an ordinary ask omits the field entirely (explicitNulls=false) — old daemons' frames decode as-is
        val plain = PocketJson.encodeToString(Envelope(id = "q2", ts = 0, body = PermissionAsk("c1", "a2", "Bash", "ls")))
        assertFalse("questions" in plain, plain)
        // an OLD-daemon-shaped ask (no questions key) decodes on a new phone with questions == null
        val fromOld = PocketJson.decodeFromString<Envelope>(plain)
        assertEquals(PermissionAsk("c1", "a2", "Bash", "ls"), fromOld.body)
    }

    @Test
    fun permissionAsk_neverRemember_roundtrips_and_oneOff_falls_back_for_old_daemons() {
        // a new daemon flags one-off decisions explicitly
        val flagged = PermissionAsk("c1", "a1", "ExitPlanMode", "plan text", neverRemember = true)
        val json = PocketJson.encodeToString(Envelope(id = "n1", ts = 0, body = flagged))
        assertTrue("\"neverRemember\":true" in json, json)
        val back = PocketJson.decodeFromString<Envelope>(json).body as PermissionAsk
        assertTrue(back.neverRemember)
        assertTrue(back.oneOff)
        // an OLD-daemon-shaped plan ask (no neverRemember key) still reads as one-off via the tool-name fallback
        val old = """{"id":"n2","ts":0,"to":"PEER","body":{"t":"pocket/ask","convoId":"c1","askId":"a2","tool":"ExitPlanMode","inputPreview":"plan"}}"""
        val fromOld = PocketJson.decodeFromString<Envelope>(old).body as PermissionAsk
        assertFalse(fromOld.neverRemember)
        assertTrue(fromOld.oneOff)
        // an ordinary ask is neither flagged nor one-off
        assertFalse(PermissionAsk("c1", "a3", "Bash", "ls").oneOff)
    }

    @Test
    fun askWithdrawn_roundtrips() {
        val env = Envelope(id = "w1", ts = 0, body = AskWithdrawn("c1", "a1"))
        assertEquals(env, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)))
    }

    @Test
    fun shareEnded_roundtrips_with_trailing_optional_defaults() {
        // issue #115 follow-up: the guest-facing ending notice. Full form round-trips…
        val env = Envelope(id = "s1", ts = 0, body = ShareEnded(ShareEnded.REASON_EXPIRED, ownerLabel = "Pandas-MacBook"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/share.ended\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        // …the default omits ownerLabel entirely (explicitNulls=false) and carries reason=revoked (encodeDefaults)
        val plain = PocketJson.encodeToString(Envelope(id = "s2", ts = 0, body = ShareEnded()))
        assertTrue("\"reason\":\"revoked\"" in plain, plain)
        assertFalse("ownerLabel" in plain, plain)

        // a MINIMAL frame (a future daemon that omits everything) decodes with both trailing optionals defaulted
        val minimal = """{"id":"s3","ts":0,"to":"PEER","body":{"t":"pocket/share.ended"}}"""
        assertEquals(ShareEnded(ShareEnded.REASON_REVOKED, null), PocketJson.decodeFromString<Envelope>(minimal).body)

        // a NEWER daemon's extra fields are skipped, not fatal — the old-app tolerance this frame relies on
        val future = """{"id":"s4","ts":0,"to":"PEER","body":{"t":"pocket/share.ended","reason":"revoked","ownerLabel":"x","futureField":{"k":1}}}"""
        assertEquals(ShareEnded(ShareEnded.REASON_REVOKED, "x"), PocketJson.decodeFromString<Envelope>(future).body)
    }

    @Test
    fun askWithdrawn_reason_roundtrips_and_old_frames_default_withdrawn() {
        // issue #100: the retire-reason is a trailing optional. new daemon → new phone: TIMED_OUT rides along
        val timed = Envelope(id = "w2", ts = 0, body = AskWithdrawn("c1", "a1", AskWithdrawnReason.TIMED_OUT))
        val json = PocketJson.encodeToString(timed)
        assertTrue("\"reason\":\"timed_out\"" in json, json)
        assertEquals(timed, PocketJson.decodeFromString<Envelope>(json))

        // encodeDefaults puts reason on EVERY withdrawal — the plain (agent-cancel) one carries "withdrawn"
        assertTrue("\"reason\":\"withdrawn\"" in PocketJson.encodeToString(Envelope(id = "w3", ts = 0, body = AskWithdrawn("c1", "a2"))))

        // an OLD daemon's frame (no reason key) decodes to WITHDRAWN — every phone keeps today's plain dismiss
        val old = """{"id":"w4","ts":0,"to":"PEER","body":{"t":"pocket/ask.withdrawn","convoId":"c1","askId":"a3"}}"""
        assertEquals(AskWithdrawn("c1", "a3", AskWithdrawnReason.WITHDRAWN), PocketJson.decodeFromString<Envelope>(old).body)

        // a reason only a NEWER daemon knows degrades to UNKNOWN (phone just dismisses) instead of the
        // runCatching-at-decode dropping the whole frame and stranding the card
        val future = """{"id":"w5","ts":0,"to":"PEER","body":{"t":"pocket/ask.withdrawn","convoId":"c1","askId":"a4","reason":"superseded"}}"""
        assertEquals(AskWithdrawnReason.UNKNOWN, (PocketJson.decodeFromString<Envelope>(future).body as AskWithdrawn).reason)
    }

    @Test
    fun permissionAsk_timeoutSec_roundtrips_and_defaults_null_for_old_daemons() {
        // issue #100: a new daemon stamps its real approval window so the phone counts its local fallback against it
        val ask = PermissionAsk("c1", "a1", "Write", "…", timeoutSec = 600)
        val json = PocketJson.encodeToString(Envelope(id = "ts1", ts = 0, body = ask))
        assertTrue("\"timeoutSec\":600" in json, json)
        assertEquals(ask, PocketJson.decodeFromString<Envelope>(json).body)

        // a plain ask omits it (explicitNulls=false) — byte-identical to a pre-#100 daemon's frame
        assertFalse("timeoutSec" in PocketJson.encodeToString(Envelope(id = "ts2", ts = 0, body = PermissionAsk("c1", "a2", "Bash", "ls"))))
        // an OLD daemon's ask (no timeoutSec key) decodes to null → the phone falls back to its legacy 30s countdown
        val old = """{"id":"ts3","ts":0,"to":"PEER","body":{"t":"pocket/ask","convoId":"c1","askId":"a3","tool":"Bash","inputPreview":"ls"}}"""
        assertEquals(null, (PocketJson.decodeFromString<Envelope>(old).body as PermissionAsk).timeoutSec)
    }

    @Test
    fun permissionVerdict_answers_roundtrip_and_old_frames_still_decode() {
        val v = PermissionVerdict(
            "c1", "a1", Decision.ALLOW,
            answers = mapOf("Which color?" to "Red", "Which sections?" to "Intro, Conclusion"),
            response = null,
        )
        val env = Envelope(id = "v1", ts = 0, body = v)
        val json = PocketJson.encodeToString(env)
        assertTrue("\"answers\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
        // a frame from an OLD phone (no answers/response fields) decodes with null defaults
        val old = """{"id":"v2","ts":0,"to":"PEER","body":{"t":"pocket/verdict","convoId":"c1","askId":"a1","decision":"allow","remember":false}}"""
        val back = PocketJson.decodeFromString<Envelope>(old)
        assertEquals(PermissionVerdict("c1", "a1", Decision.ALLOW), back.body)
    }

    @Test
    fun usage_hours_and_date_roundtrip_and_old_frames_default() {
        // new daemon → new app: the Today range carries 24 hourly buckets (no date — today is implied)
        // and every day bucket an ISO date
        val u = Usage(
            days = listOf(UsageDay("Mon", 100, date = "2026-07-06"), UsageDay("Tue", 200, date = "2026-07-07")),
            hours = listOf(UsageDay("00:00", 0), UsageDay("14:00", 500)),
            tokensToday = 500,
        )
        val env = Envelope(id = "u1", ts = 0, body = u)
        val json = PocketJson.encodeToString(env)
        assertTrue("\"hours\"" in json, json)
        assertTrue("\"date\":\"2026-07-06\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        // a 7d/30d usage omits hours (explicitNulls=false) — byte-identical to a peer that never sets it
        val ranged = PocketJson.encodeToString(Envelope(id = "u2", ts = 0, body = Usage(days = listOf(UsageDay("Mon", 1, date = "2026-07-06")))))
        assertFalse("hours" in ranged, ranged)

        // an OLD daemon's usage (no hours key, day buckets carry no date) decodes with null defaults
        val old = """{"id":"u3","ts":0,"to":"PEER","body":{"t":"pocket/usage","days":[{"label":"Mon","tokens":100}],
            "models":[],"tokensToday":100,"requestsToday":2}}"""
        val back = PocketJson.decodeFromString<Envelope>(old).body as Usage
        assertEquals(null, back.hours)
        assertEquals(null, back.days.single().date)
        assertEquals(100, back.days.single().tokens)
    }

    @Test
    fun sessionGone_roundtrips() {
        val env = Envelope(id = "3", ts = 0, body = SessionGone("c9"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/session.gone\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun stopBackgroundJob_roundtrips() {
        // issue #80: the phone's panel "stop" — a brand-new message type. It round-trips new↔new; an old
        // daemon can't decode the unknown "t" and drops it (see unknown_frame_discriminator_throws), so
        // the stop no-ops there instead of breaking the socket.
        val env = Envelope(id = "j1", ts = 0, body = StopBackgroundJob("c1", "toolu_7"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/job.stop\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun deviceRevoked_roundtrips() {
        val env = Envelope(id = "7", ts = 0, to = Route.RELAY, body = DeviceRevoked("devX"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/device.revoked\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun lanHello_roundtrips() {
        val env = Envelope(id = "4", ts = 0, body = LanHello("devA"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/lan.hello\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun sessionFiles_pair_roundtrips_with_wire_safe_defaults() {
        // request: agent defaults to CLAUDE like OpenSession (an app predating Codex sends none)
        val req = Envelope(id = "f1", ts = 0, body = ListSessionFiles("/w", "sid"))
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/files.list\"" in reqJson, reqJson)
        assertTrue("\"agent\":\"claude\"" in reqJson, reqJson) // encodeDefaults
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))

        val resp = Envelope(id = "f2", ts = 0, body = SessionFiles("/w", "sid", listOf(ChangedFile("/w/a.md", "write", 2))))
        val respJson = PocketJson.encodeToString(resp)
        assertTrue("\"t\":\"pocket/files\"" in respJson, respJson)
        assertEquals(resp, PocketJson.decodeFromString<Envelope>(respJson))
    }

    @Test
    fun fileContent_roundtrips_and_omits_null_payloads() {
        val req = Envelope(id = "f3", ts = 0, body = ReadFile("/w", "sid", "/w/a.md"))
        assertTrue("\"t\":\"pocket/file.read\"" in PocketJson.encodeToString(req))

        val ok = Envelope(id = "f4", ts = 0, body = FileContent("/w", "sid", "/w/a.md", text = "# hi", totalBytes = 4))
        val okJson = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/file.content\"" in okJson, okJson)
        assertFalse("base64" in okJson, okJson) // explicitNulls=false — image fields absent on a text reply
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(okJson))

        val err = FileContent("/w", "sid", "/w/x", ok = false, error = "not a file this session changed")
        assertEquals(err, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope(id = "f5", ts = 0, body = err))).body)
    }

    @Test
    fun exportFile_roundtrips_carries_convoId_and_defaults_agent() {
        // issue #67 v2 / #79: the approval-gated export of a non-changed file. Distinct discriminator from
        // ReadFile so an old daemon DROPS it (can't serve past the changed-set) instead of mis-serving.
        val req = Envelope(id = "x1", ts = 0, body = ExportFile("c1", "/w", "sid", "/w/report.xlsx"))
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/file.export\"" in reqJson, reqJson)
        assertTrue("\"convoId\":\"c1\"" in reqJson, reqJson) // routes the approval to the live conversation
        assertTrue("\"agent\":\"claude\"" in reqJson, reqJson) // encodeDefaults, like ReadFile
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))
        // reply rides the SAME FileContent channel as ReadFile (served, or ok=false on refuse/deny)
        val served = FileContent("/w", "sid", "/w/report.xlsx", base64 = "UEsD", mediaType = "application/zip", totalBytes = 3)
        assertEquals(served, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope(id = "x2", ts = 0, body = served))).body)
        // a frame WITHOUT the agent key (hand-written, or a client that omits defaults) decodes to CLAUDE
        val noAgent = PocketJson.decodeFromString<Envelope>(
            """{"id":"x3","ts":0,"body":{"t":"pocket/file.export","convoId":"c1","workdir":"/w","sessionId":"sid","path":"/w/report.xlsx"}}""",
        )
        assertEquals(ExportFile("c1", "/w", "sid", "/w/report.xlsx"), noAgent.body)
    }

    @Test
    fun fileDiff_pair_roundtrips_and_stats_stay_optional() {
        val req = Envelope(id = "d1", ts = 0, body = ReadFileDiff("/w", "sid", "/w/a.kt"))
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/diff.read\"" in reqJson, reqJson)
        assertTrue("\"agent\":\"claude\"" in reqJson, reqJson) // encodeDefaults, like ListSessionFiles
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))

        val ok = Envelope(id = "d2", ts = 0, body = FileDiff("/w", "sid", "/w/a.kt", diff = "@@ -1,2 +1,3 @@\n ctx\n+new", adds = 1, dels = 0))
        val okJson = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/diff.content\"" in okJson, okJson)
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(okJson))

        val err = FileDiff("/w", "sid", "/w/x", ok = false, error = "not a file this session changed")
        assertEquals(err, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope(id = "d3", ts = 0, body = err))).body)

        // ChangedFile from an OLD daemon (no adds/dels on the wire) must decode with null stats,
        // and a stats-bearing row must round-trip — the list UI keys "show counts" off null.
        val legacy = PocketJson.decodeFromString<ChangedFile>("""{"path":"/w/a.md","op":"edit","edits":3}""")
        assertEquals(ChangedFile("/w/a.md", "edit", 3, adds = null, dels = null), legacy)
        val statted = ChangedFile("/w/a.md", "edit", 3, adds = 12, dels = 4)
        assertEquals(statted, PocketJson.decodeFromString<ChangedFile>(PocketJson.encodeToString(statted)))
    }

    @Test
    fun unknown_frame_discriminator_throws() {
        // The invariant the whole forward-compat story rests on: an unknown "t" must THROW (each decode
        // site wraps in runCatching and drops the frame). A default polymorphic deserializer added later
        // would silently break that contract — this test pins it.
        val json = """{"id":"9","ts":0,"to":"PEER","body":{"t":"pocket/from.the.future","x":1}}"""
        assertTrue(runCatching { PocketJson.decodeFromString<Envelope>(json) }.isFailure)
    }

    @Test
    fun daemonInfo_roundtrips_and_omits_null_lanUrl() {
        val with = Envelope(id = "5", ts = 0, body = DaemonInfo("ws://192.168.1.2:8765/v1/ws"))
        val json = PocketJson.encodeToString(with)
        assertTrue("\"t\":\"pocket/daemon.info\"" in json, json)
        assertEquals(with, PocketJson.decodeFromString<Envelope>(json))
        // null lanUrl (listener disabled) is omitted on the wire and decodes back to null
        val without = PocketJson.encodeToString(Envelope(id = "6", ts = 0, body = DaemonInfo(null)))
        assertFalse("lanUrl" in without, without)
        assertEquals(DaemonInfo(null), PocketJson.decodeFromString<Envelope>(without).body)
    }

    @Test
    fun daemonInfo_hostname_is_optional_and_back_compatible() {
        // hostname (issue #62) is a trailing optional field: round-trips when set, omitted when null, and an
        // old daemon's frame that predates it decodes to a null hostname (no throw). The four-direction
        // wire-compat contract for adding a field to a daemon -> phone frame.
        val named = Envelope(id = "7", ts = 0, body = DaemonInfo("ws://192.168.1.2:8765/v1/ws", "Pandas-MacBook-Pro"))
        val json = PocketJson.encodeToString(named)
        assertTrue("\"hostname\":\"Pandas-MacBook-Pro\"" in json, json)
        assertEquals(named, PocketJson.decodeFromString<Envelope>(json))
        // null hostname is omitted on the wire (explicitNulls=false) — byte-identical to the pre-#62 shape
        assertFalse("hostname" in PocketJson.encodeToString(Envelope(id = "8", ts = 0, body = DaemonInfo("ws://x/v1/ws"))))
        // an old daemon's frame (no hostname key) decodes to a null hostname — the accountId-hash name stands
        val legacy = """{"id":"9","ts":0,"to":"PEER","body":{"t":"pocket/daemon.info","lanUrl":"ws://x/v1/ws"}}"""
        assertEquals(DaemonInfo("ws://x/v1/ws", null), PocketJson.decodeFromString<Envelope>(legacy).body)
    }

    @Test
    fun sessionSummary_omits_null_gitBranch() {
        val s = SessionSummary(
            sessionId = "s", title = "t", firstPrompt = "p",
            messageCount = 1, cwd = "/x", lastModified = 0,
        )
        val json = PocketJson.encodeToString(s)
        assertFalse("gitBranch" in json, json)
        assertEquals(s, PocketJson.decodeFromString<SessionSummary>(json))
    }

    @Test
    fun sessionSummary_model_roundtrips_and_defaults_for_old_daemons() {
        val s = SessionSummary(
            sessionId = "s", title = "t", firstPrompt = "p",
            messageCount = 1, cwd = "/x", lastModified = 0, model = "claude-opus-4-8",
        )
        assertEquals(s, PocketJson.decodeFromString<SessionSummary>(PocketJson.encodeToString(s)))
        // an old daemon's summary has no `model` key → decodes to null, not an error
        val old = """{"sessionId":"s","title":"t","firstPrompt":"p","messageCount":1,"cwd":"/x","lastModified":0}"""
        assertEquals(null, PocketJson.decodeFromString<SessionSummary>(old).model)
    }

    @Test
    fun tokenUsage_contextTokens_is_computed_not_serialized() {
        // occupancy = prompt (fresh + cached) + the reply just written; a getter, so it must never
        // appear on the wire (older peers would reject or double-count a baked field)
        val u = TokenUsage(inputTokens = 10, outputTokens = 5, cacheCreationInputTokens = 100, cacheReadInputTokens = 1000)
        assertEquals(1115L, u.contextTokens)
        assertFalse("contextTokens" in PocketJson.encodeToString(u))
    }

    @Test
    fun audioChunk_roundtrips() {
        val env = Envelope(id = "4", ts = 0, body = AudioChunk("c1", "cap-1", 0, last = true, mediaType = "audio/mp4", base64 = "QUFB"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/audio.chunk\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun audioCancel_roundtrips() {
        val env = Envelope(id = "5", ts = 0, body = AudioCancel("c1", "cap-1"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/audio.cancel\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun fileChunk_roundtrips_and_totalBytes_defaults_for_hand_rolled_frames() {
        // issue #90: the chunked file-upload stream. idx 0 carries the full size for early over-cap refusal
        val first = Envelope(
            id = "fc1", ts = 0,
            body = FileChunk("c1", "cap-1", 0, last = false, name = "report.pdf", mediaType = "application/pdf", base64 = "QUFB", totalBytes = 2_400_000),
        )
        val json = PocketJson.encodeToString(first)
        assertTrue("\"t\":\"pocket/file.chunk\"" in json, json)
        assertTrue("\"totalBytes\":2400000" in json, json)
        assertEquals(first, PocketJson.decodeFromString<Envelope>(json))

        // later chunks ride the default 0 (encodeDefaults puts it on the wire; a differently-defaulting
        // peer still reads intent), and a frame WITHOUT the key decodes to 0 = unknown
        val tail = FileChunk("c1", "cap-1", 3, last = true, name = "report.pdf", mediaType = "application/pdf", base64 = "QUJD")
        assertEquals(tail, PocketJson.decodeFromString<FileChunk>(PocketJson.encodeToString(tail)))
        val minimal = """{"convoId":"c1","captureId":"cap-1","idx":1,"last":false,"name":"a.csv","mediaType":"text/csv","base64":"QQ=="}"""
        assertEquals(0L, PocketJson.decodeFromString<FileChunk>(minimal).totalBytes)
    }

    @Test
    fun fileUploadCancel_roundtrips() {
        val env = Envelope(id = "fc2", ts = 0, body = FileUploadCancel("c1", "cap-1"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/file.cancel\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun fileUploaded_success_and_error_variants_roundtrip() {
        // success: the landing path rides along; error is omitted (explicitNulls=false)
        val ok = Envelope(
            id = "fu1", ts = 0,
            body = FileUploaded("c1", "cap-1", path = ".ccpocket/inbox/cap-1/report.pdf", name = "report.pdf", size = 2_400_000),
        )
        val okJson = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/file.uploaded\"" in okJson, okJson)
        assertFalse("error" in okJson, okJson)
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(okJson))

        // failure: nothing landed — path/name absent, error present
        val err = FileUploaded("c1", "cap-2", ok = false, error = "file too large — the limit is 200 MB")
        val errJson = PocketJson.encodeToString<FileUploaded>(err)
        assertTrue("\"ok\":false" in errJson, errJson)
        assertFalse("path" in errJson, errJson)
        assertEquals(err, PocketJson.decodeFromString<FileUploaded>(errJson))

        // a minimal frame (only the required keys) reads as a pathless success — pins default semantics
        val minimal = """{"convoId":"c1","captureId":"cap-3"}"""
        val back = PocketJson.decodeFromString<FileUploaded>(minimal)
        assertTrue(back.ok)
        assertEquals(null, back.path)
        assertEquals(0L, back.size)
    }

    @Test
    fun fileChunk_is_undecodable_to_old_peers_by_design() {
        // The #90 downgrade story: an OLD daemon can't decode "pocket/file.chunk" (unknown discriminator
        // throws → runCatching at its decode site drops the frame silently), so a NEW app must arm an
        // upload timeout instead of waiting on a FileUploaded that will never come. Same contract the
        // generic unknown_frame_discriminator_throws pins, restated on this exact frame so a future
        // default-deserializer change can't quietly break the timeout-based degradation.
        val json = PocketJson.encodeToString(Envelope(id = "fc3", ts = 0, body = FileChunk("c", "cap", 0, true, "a.txt", "text/plain", "QQ==")))
        assertTrue("\"t\":\"pocket/file.chunk\"" in json, json)
        // simulate the old peer: a codec whose sealed hierarchy doesn't know the type ≈ unknown "t" here
        val unknownT = json.replace("pocket/file.chunk", "pocket/file.chunk-from-the-future")
        assertTrue(runCatching { PocketJson.decodeFromString<Envelope>(unknownT) }.isFailure)
    }

    @Test
    fun transcript_ok_defaults_and_error_variant() {
        val ok = Envelope(id = "6", ts = 0, body = Transcript("c1", "cap-1", text = "hello world"))
        val okJson = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/transcript\"" in okJson, okJson)
        assertFalse("error" in okJson, okJson) // explicitNulls=false
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(okJson))

        val err = Transcript("c1", "cap-2", ok = false, error = "whisper-cli not found")
        val errJson = PocketJson.encodeToString<Transcript>(err)
        assertTrue("\"ok\":false" in errJson, errJson)
        assertEquals(err, PocketJson.decodeFromString<Transcript>(errJson))
    }

    @Test
    fun sessionLive_carries_mode_and_omits_it_when_unknown() {
        val controlled = Envelope(id = "7", ts = 0, body = SessionLive("c1", "/x", "sid", mode = PermissionMode.ACCEPT_EDITS, executing = true))
        val json = PocketJson.encodeToString(controlled)
        assertTrue("\"mode\":\"acceptEdits\"" in json, json)
        assertTrue("\"executing\":true" in json, json)
        assertEquals(controlled, PocketJson.decodeFromString<Envelope>(json))

        val observed = SessionLive("c2", "/y", "sid2", observing = true) // terminal session: mode unknown
        val obsJson = PocketJson.encodeToString<SessionLive>(observed)
        assertFalse("mode" in obsJson, obsJson) // explicitNulls=false
        assertFalse("executing" in obsJson, obsJson) // absent = unknown -> the phone keeps local state
        assertEquals(observed, PocketJson.decodeFromString<SessionLive>(obsJson))

        // a frame from a pre-executing peer still decodes (field defaults to null)
        val legacy = """{"convoId":"c3","workdir":"/z","sessionId":"sid3"}"""
        assertEquals(SessionLive("c3", "/z", "sid3"), PocketJson.decodeFromString<SessionLive>(legacy))
    }

    @Test
    fun runShellCommand_roundtrips_with_default_timeout() {
        val env = Envelope(id = "8", ts = 0, body = RunShellCommand("c1", "git status", "/repo"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/shell.run\"" in json, json)
        assertTrue("\"timeoutMs\":30000" in json, json) // encodeDefaults
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun shellResult_roundtrips_and_omits_null_error() {
        val ok = Envelope(id = "9", ts = 0, body = ShellResult("c1", "node -v", exitCode = 0, stdout = "v22.0.0\n"))
        val json = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/shell.result\"" in json, json)
        assertFalse("error" in json, json) // explicitNulls=false
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(json))

        val denied = ShellResult("c1", "rm -rf /", exitCode = -1, denied = true)
        val deniedJson = PocketJson.encodeToString<ShellResult>(denied)
        assertTrue("\"denied\":true" in deniedJson, deniedJson)
        assertEquals(denied, PocketJson.decodeFromString<ShellResult>(deniedJson))
    }

    @Test
    fun pairBegin_roundtrips() {
        val env = Envelope(id = "3", ts = 0, to = Route.RELAY, body = PairBegin("daemon-e2e-pub"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/pair.begin\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun pairBegin_headless_roundtrips_and_old_frames_default_false() {
        // issue #91: the authoritative bridge marker rides PairBegin from the minting daemon
        val bridge = Envelope(id = "pb1", ts = 0, to = Route.RELAY, body = PairBegin("pub", headless = true))
        val json = PocketJson.encodeToString(bridge)
        assertTrue("\"headless\":true" in json, json)
        assertEquals(bridge, PocketJson.decodeFromString<Envelope>(json))
        // an OLD daemon's PairBegin (no headless key) decodes to false — the relay mints a phone ticket
        val old = """{"id":"pb2","ts":0,"to":"RELAY","body":{"t":"pocket/pair.begin","e2ePub":"pub"}}"""
        assertEquals(PairBegin("pub"), PocketJson.decodeFromString<Envelope>(old).body as PairBegin)
    }

    @Test
    fun auth_objects_and_state_roundtrip() {
        // argless requests are data objects — encode as bare {"t": …} and decode back to the singleton
        for ((body, tag) in listOf<Pair<Frame, String>>(
            FetchAuthStatus to "pocket/auth.fetch",
            AuthLoginCancel to "pocket/auth.login.cancel",
            AuthLogout to "pocket/auth.logout",
        )) {
            val json = PocketJson.encodeToString(Envelope(id = "a", ts = 0, body = body))
            assertTrue("\"t\":\"$tag\"" in json, json)
            assertEquals(body, PocketJson.decodeFromString<Envelope>(json).body)
        }

        val login = Envelope(id = "a1", ts = 0, body = AuthLogin())
        val loginJson = PocketJson.encodeToString(login)
        assertTrue("\"console\":false" in loginJson, loginJson) // encodeDefaults
        assertEquals(login, PocketJson.decodeFromString<Envelope>(loginJson))

        val state = Envelope(
            id = "a2", ts = 0,
            body = AuthState(loggedIn = true, email = "a@b.c", orgName = "Org", subscriptionType = "max", authMethod = "claude.ai"),
        )
        val stateJson = PocketJson.encodeToString(state)
        assertTrue("\"t\":\"pocket/auth.state\"" in stateJson, stateJson)
        assertFalse("loginUrl" in stateJson, stateJson) // explicitNulls=false
        assertFalse("error" in stateJson, stateJson)
        assertEquals(state, PocketJson.decodeFromString<Envelope>(stateJson))
    }

    @Test
    fun authLogin_and_closeSession_force_roundtrip_and_old_frames_default_false() {
        // new client → new daemon: the flag rides along
        val forced = Envelope(id = "a3", ts = 0, body = AuthLogin(force = true))
        val forcedJson = PocketJson.encodeToString(forced)
        assertTrue("\"force\":true" in forcedJson, forcedJson)
        assertEquals(forced, PocketJson.decodeFromString<Envelope>(forcedJson))
        // an OLD client's frame (no force key) decodes with force=false — the refusal semantics stand
        val oldLogin = """{"id":"a4","ts":0,"to":"PEER","body":{"t":"pocket/auth.login","console":false}}"""
        assertEquals(AuthLogin(), PocketJson.decodeFromString<Envelope>(oldLogin).body)

        val close = Envelope(id = "a5", ts = 0, body = CloseSession("c1", force = true))
        val closeJson = PocketJson.encodeToString(close)
        assertTrue("\"force\":true" in closeJson, closeJson)
        assertEquals(close, PocketJson.decodeFromString<Envelope>(closeJson))
        // an OLD client's close (no force key) keeps the busy keep-alive shield (force=false)
        val oldClose = """{"id":"a6","ts":0,"to":"PEER","body":{"t":"pocket/session.close","convoId":"c1"}}"""
        assertEquals(CloseSession("c1"), PocketJson.decodeFromString<Envelope>(oldClose).body)
    }

    @Test
    fun authState_blockers_roundtrip_old_frames_default_and_future_reason_degrades() {
        // new daemon → new app: the structured blocker list rides along and round-trips
        val state = Envelope(
            id = "b1", ts = 0,
            body = AuthState(
                loggedIn = true, error = "busy",
                blockers = listOf(
                    AuthBlocker("c1", "s1", cwd = "/w/api-server", reason = AuthBlockReason.EXECUTING),
                    AuthBlocker("c2", cwd = "/w/web", reason = AuthBlockReason.BACKGROUND_JOBS, jobLabels = listOf("npm run dev")),
                ),
            ),
        )
        val json = PocketJson.encodeToString(state)
        assertTrue("\"reason\":\"executing\"" in json, json)
        assertTrue("\"reason\":\"background_jobs\"" in json, json)
        assertEquals(state, PocketJson.decodeFromString<Envelope>(json))

        // an OLD daemon's state (no blockers key) decodes to an empty list — the error string stands alone
        val old = """{"id":"b2","ts":0,"to":"PEER","body":{"t":"pocket/auth.state","loggedIn":true,"error":"busy"}}"""
        assertEquals(emptyList(), (PocketJson.decodeFromString<Envelope>(old).body as AuthState).blockers)

        // an OLD app's skip path over the new array-of-objects field: a same-shaped unknown key must not throw
        val skipped = """{"id":"b3","ts":0,"to":"PEER","body":{"t":"pocket/auth.state","loggedIn":true,
            "futureBlockers":[{"convoId":"c","cwd":"/x","reason":"executing","jobLabels":["a"]}]}}"""
        assertTrue((PocketJson.decodeFromString<Envelope>(skipped).body as AuthState).loggedIn)

        // a reason value only a NEWER daemon knows degrades to UNKNOWN instead of failing the whole frame
        // (which runCatching at the decode sites would silently drop — a login button that goes dead)
        val future = """{"id":"b4","ts":0,"to":"PEER","body":{"t":"pocket/auth.state","loggedIn":true,
            "blockers":[{"convoId":"c","cwd":"/x","reason":"pending_ask"}]}}"""
        val degraded = PocketJson.decodeFromString<Envelope>(future).body as AuthState
        assertEquals(AuthBlockReason.UNKNOWN, degraded.blockers.single().reason)
    }

    @Test
    fun authState_apiKeySource_roundtrips_and_defaults_null_on_old_frames() {
        // new daemon → new app: the API-key source rides along and round-trips (issue #73)
        val keyed = Envelope(
            id = "k1", ts = 0,
            body = AuthState(loggedIn = true, authMethod = "claude.ai", apiKeySource = "ANTHROPIC_API_KEY"),
        )
        val keyedJson = PocketJson.encodeToString(keyed)
        assertTrue("\"apiKeySource\":\"ANTHROPIC_API_KEY\"" in keyedJson, keyedJson)
        assertEquals(keyed, PocketJson.decodeFromString<Envelope>(keyedJson))

        // an OAuth login (null apiKeySource) omits the key — a plain login frame stays byte-identical to the old shape
        val oauthJson = PocketJson.encodeToString(Envelope(id = "k2", ts = 0, body = AuthState(loggedIn = true, authMethod = "claude.ai")))
        assertFalse("apiKeySource" in oauthJson, oauthJson)

        // an OLD daemon's state (no apiKeySource key) decodes to null → the new client falls back to the normal account pane
        val old = """{"id":"k3","ts":0,"to":"PEER","body":{"t":"pocket/auth.state","loggedIn":true,"authMethod":"claude.ai"}}"""
        assertEquals(null, (PocketJson.decodeFromString<Envelope>(old).body as AuthState).apiKeySource)
    }

    @Test
    fun pushPrefs_set_and_state_roundtrip() {
        // query form: enabled stays null and is omitted on the wire (explicitNulls=false)
        val query = Envelope(id = "p1", ts = 0, body = SetPushPrefs())
        val queryJson = PocketJson.encodeToString(query)
        assertTrue("\"t\":\"pocket/push.prefs.set\"" in queryJson, queryJson)
        assertFalse("enabled" in queryJson, queryJson)
        assertEquals(query, PocketJson.decodeFromString<Envelope>(queryJson))

        val set = Envelope(id = "p2", ts = 0, body = SetPushPrefs(enabled = false))
        assertEquals(set, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(set)))

        val state = Envelope(id = "p3", ts = 0, body = PushPrefs(enabled = true))
        val stateJson = PocketJson.encodeToString(state)
        assertTrue("\"t\":\"pocket/push.prefs\"" in stateJson, stateJson)
        assertEquals(state, PocketJson.decodeFromString<Envelope>(stateJson))
    }

    @Test
    fun directoryEntry_activeSessions_roundtrip_and_old_daemon_defaults() {
        // new daemon → new app: the per-session list rides along and round-trips
        val entry = DirectoryEntry(
            path = "/p", name = "p", isDir = true, open = true, executing = true,
            activeSessionId = "s1", activeSessionTitle = "fix bug",
            activeSessions = listOf(
                ActiveSession("s1", "fix bug", executing = true, gitBranch = "main"),
                ActiveSession("s2", "write docs", busy = true, agent = AgentKind.CODEX),
            ),
        )
        val json = PocketJson.encodeToString(entry)
        assertEquals(entry, PocketJson.decodeFromString<DirectoryEntry>(json))

        // old daemon → new app: no activeSessions key at all → empty list, legacy single fields intact
        val old = """{"path":"/p","name":"p","isDir":true,"open":true,"activeSessionId":"s1","activeSessionTitle":"fix bug"}"""
        val back = PocketJson.decodeFromString<DirectoryEntry>(old)
        assertEquals(emptyList(), back.activeSessions)
        assertEquals("s1", back.activeSessionId)
    }

    @Test
    fun promptAck_and_promptId_roundtrip_and_old_peers_default() {
        // issue #66: new app ↔ new daemon
        val env = Envelope(id = "9", ts = 0, body = SendPrompt("c1", "hi", promptId = "ab12"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"promptId\":\"ab12\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        val ack = Envelope(id = "10", ts = 0, body = PromptAck("c1", "ab12"))
        assertEquals(ack, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(ack)))

        // old app → new daemon: no promptId key → null (no ack expected)
        val legacySend = """{"convoId":"c1","text":"hi"}"""
        assertEquals(SendPrompt("c1", "hi"), PocketJson.decodeFromString<SendPrompt>(legacySend))
        // new app (no promptId minted) → frame omits the key entirely (explicitNulls=false)
        assertFalse("promptId" in PocketJson.encodeToString<SendPrompt>(SendPrompt("c1", "hi")))
    }

    @Test
    fun turnDone_error_and_sessionLive_degraded_roundtrip_and_old_frames_default() {
        // issue #65: a failed turn carries its error; a healthy one omits the key
        val failed = TurnDone("c1", finalText = "No response requested.", error = "API request failed")
        val fj = PocketJson.encodeToString<TurnDone>(failed)
        assertTrue("\"error\":\"API request failed\"" in fj, fj)
        assertEquals(failed, PocketJson.decodeFromString<TurnDone>(fj))
        assertFalse("error" in PocketJson.encodeToString<TurnDone>(TurnDone("c1", "ok")))

        // old daemon → new app: no error/degraded keys → null/false, nothing renders differently
        assertEquals(TurnDone("c1", "ok"), PocketJson.decodeFromString<TurnDone>("""{"convoId":"c1","finalText":"ok"}"""))
        val legacyLive = """{"convoId":"c3","workdir":"/z","sessionId":"sid3"}"""
        assertFalse(PocketJson.decodeFromString<SessionLive>(legacyLive).degraded)

        val degraded = SessionLive("c1", "/x", "sid", degraded = true)
        val dj = PocketJson.encodeToString<SessionLive>(degraded)
        assertTrue("\"degraded\":true" in dj, dj)
        assertEquals(degraded, PocketJson.decodeFromString<SessionLive>(dj))
        // encodeDefaults=true puts "degraded":false on EVERY healthy announce — old apps must (and do,
        // via ignoreUnknownKeys) skip it; pin that on-wire shape here
        assertTrue("\"degraded\":false" in PocketJson.encodeToString<SessionLive>(SessionLive("c1", "/x", "sid")))

        // history: a synthetic placeholder carries error=true; old records default to false
        val hist = HistoryMessage(ChatRole.ASSISTANT, "No response requested.", error = true)
        assertEquals(hist, PocketJson.decodeFromString<HistoryMessage>(PocketJson.encodeToString(hist)))
        val legacyHist = """{"role":"assistant","text":"hi"}"""
        assertFalse(PocketJson.decodeFromString<HistoryMessage>(legacyHist).error)
    }

    @Test
    fun pathEntries_pair_roundtrips() {
        // composer @-file completion (issue #75): request + a populated reply survive the wire
        val req = Envelope(id = "pe1", ts = 0, body = ListPathEntries("/w", subPath = "src/app"))
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/path.list\"" in reqJson, reqJson)
        assertTrue("\"limit\":500" in reqJson, reqJson) // encodeDefaults — a differently-defaulting peer reads intent
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))

        val resp = Envelope(
            id = "pe2", ts = 0,
            body = PathEntries("/w", "src/app", entries = listOf(PathEntry("main", true), PathEntry("Main.kt", false)), truncated = true),
        )
        val respJson = PocketJson.encodeToString(resp)
        assertTrue("\"t\":\"pocket/path.entries\"" in respJson, respJson)
        assertEquals(resp, PocketJson.decodeFromString<Envelope>(respJson))
    }

    @Test
    fun pathEntries_minimal_frame_decodes_with_default_semantics() {
        // a minimal frame (only t/workdir/subPath) must read as a SUCCESSFUL EMPTY listing — pins the
        // default semantics a hand-rolled or field-dropping peer relies on
        val minimal = """{"id":"pe3","ts":0,"to":"PEER","body":{"t":"pocket/path.entries","workdir":"/w","subPath":""}}"""
        val back = PocketJson.decodeFromString<Envelope>(minimal).body as PathEntries
        assertEquals(emptyList(), back.entries)
        assertTrue(back.ok)
        assertFalse(back.truncated)
        assertEquals(null, back.error)
    }

    @Test
    fun pathEntries_success_frame_omits_null_error() {
        // explicitNulls=false: a successful reply never carries an "error" key on the wire
        val ok = Envelope(id = "pe4", ts = 0, body = PathEntries("/w", "", entries = listOf(PathEntry("src", true))))
        val json = PocketJson.encodeToString(ok)
        assertFalse("\"error\"" in json, json)
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun subagent_tool_fields_roundtrip_and_old_frames_default() {
        // issue #77: the sub-agent extras are OPTIONAL on ToolEvent/HistoryMessage — a new daemon and
        // an old app (or the reverse) keep talking
        val start = ToolEvent("c1", 1, ToolPhase.START, "Agent", "general-purpose: add", toolUseId = "t1", parentToolUseId = null)
        assertEquals(start, PocketJson.decodeFromString<ToolEvent>(PocketJson.encodeToString(start)))
        val child = ToolEvent("c1", 2, ToolPhase.START, "Bash", "expr 2 + 3", toolUseId = "t2", parentToolUseId = "t1")
        assertEquals(child, PocketJson.decodeFromString<ToolEvent>(PocketJson.encodeToString(child)))
        val result = ToolEvent("c1", 3, ToolPhase.RESULT, "Agent", ok = true, toolUseId = "t1", output = "5")
        val rj = PocketJson.encodeToString(result)
        assertTrue("\"output\":\"5\"" in rj, rj)
        assertEquals(result, PocketJson.decodeFromString<ToolEvent>(rj))

        // a plain (non-subagent) tool frame stays byte-identical to the pre-#77 wire: the new keys
        // are null and explicitNulls=false omits them
        val plainJson = PocketJson.encodeToString(ToolEvent("c1", 4, ToolPhase.START, "Bash", "ls"))
        assertFalse("toolUseId" in plainJson, plainJson)
        assertFalse("parentToolUseId" in plainJson, plainJson)
        assertFalse("output" in plainJson, plainJson)

        // old daemon → new app: no ids/output → nulls, the flat card of today
        val legacyTool = """{"convoId":"c1","seq":4,"phase":"start","tool":"Bash","inputPreview":"ls"}"""
        val decoded = PocketJson.decodeFromString<ToolEvent>(legacyTool)
        assertEquals(null, decoded.toolUseId)
        assertEquals(null, decoded.parentToolUseId)
        assertEquals(null, decoded.output)

        // history: a completed sub-agent card keeps ok/output; old records default to null
        val card = HistoryMessage(ChatRole.TOOL, "general-purpose: add", tool = "Agent", ok = true, output = "5")
        assertEquals(card, PocketJson.decodeFromString<HistoryMessage>(PocketJson.encodeToString(card)))
        val legacyCard = PocketJson.decodeFromString<HistoryMessage>("""{"role":"tool","text":"x","tool":"Task"}""")
        assertEquals(null, legacyCard.ok)
        assertEquals(null, legacyCard.output)

        // the shared alias predicate both sides key card rendering/tracking on
        assertTrue(isSubagentTool("Task"))
        assertTrue(isSubagentTool("Agent"))
        assertFalse(isSubagentTool("Bash"))
    }

    @Test
    fun history_question_answers_roundtrip_and_old_frames_default() {
        // issue #110: `answers` is OPTIONAL on HistoryMessage — a new daemon and an old app (or the
        // reverse) keep talking; only an AskUserQuestion row a replay resolved ever carries it
        val answered = HistoryMessage(
            ChatRole.TOOL, "Which color do you prefer?", tool = "AskUserQuestion",
            answers = listOf(QuestionAnswer("Which color do you prefer?", "Red"), QuestionAnswer("", "surprise me")),
        )
        val aj = PocketJson.encodeToString(answered)
        assertTrue("\"answers\"" in aj, aj)
        assertEquals(answered, PocketJson.decodeFromString<HistoryMessage>(aj))

        // old daemon → new app: no answers key → null, the plain tool card of today
        val legacy = PocketJson.decodeFromString<HistoryMessage>("""{"role":"tool","text":"{\"questions\":[…]}","tool":"AskUserQuestion"}""")
        assertEquals(null, legacy.answers)

        // any plain TOOL row stays byte-identical to the pre-#110 wire: answers is null and
        // explicitNulls=false omits the key
        val plainJson = PocketJson.encodeToString(HistoryMessage(ChatRole.TOOL, "ls", tool = "Bash"))
        assertFalse("answers" in plainJson, plainJson)
    }

    @Test
    fun workflowUpdate_roundtrips_and_null_previews_are_omitted() {
        val env = Envelope(
            id = "w1", ts = 0,
            body = WorkflowUpdate(
                convoId = "c1",
                run = WorkflowRun(
                    runId = "wf_8f3a21c0-abc",
                    name = "release-pipeline",
                    status = WorkflowRunStatus.RUNNING,
                    toolUseId = "toolu_1",
                    phases = listOf(WorkflowPhaseInfo(1, "resolve"), WorkflowPhaseInfo(2, "analyze")),
                    agents = listOf(
                        WorkflowAgentSnap(1, "scan module-auth", WorkflowAgentState.RUNNING, phaseIndex = 2, startedAt = 5, lastToolName = "Grep"),
                        WorkflowAgentSnap(2, "scan module-core", WorkflowAgentState.DONE, phaseIndex = 2, durationMs = 72_000, resultPreview = "no issues"),
                        WorkflowAgentSnap(3, "test auth.expiry", WorkflowAgentState.FAILED, phaseIndex = 2, error = "AssertionError: expected refresh"),
                        WorkflowAgentSnap(4, "package dist", WorkflowAgentState.QUEUED, phaseIndex = 2, queuedAt = 9),
                    ),
                    startedAt = 1,
                ),
            ),
        )
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/workflow\"" in json, json)
        assertTrue("\"status\":\"running\"" in json, json)
        assertTrue("\"state\":\"queued\"" in json, json)
        assertTrue("\"state\":\"failed\"" in json, json)
        assertFalse("finalResult" in json, json)  // explicitNulls=false — terminal-only fields stay off the live frame
        assertFalse("\"durationMs\":null" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun workflow_future_enum_values_degrade_to_unknown_not_a_dropped_frame() {
        // a state/status only a NEWER daemon knows must not fail the decode — runCatching at the
        // client's decode site would silently drop the frame and the card would freeze mid-run
        val future = """{"id":"w2","ts":0,"to":"PEER","body":{"t":"pocket/workflow","convoId":"c1","run":{
            "runId":"wf_1","name":"n","status":"paused","agents":[
            {"index":1,"label":"a","state":"skipped"}]}}}"""
        val run = (PocketJson.decodeFromString<Envelope>(future).body as WorkflowUpdate).run
        assertEquals(WorkflowRunStatus.UNKNOWN, run.status)
        assertEquals(WorkflowAgentState.UNKNOWN, run.agents.single().state)
    }

    @Test
    fun workflowAgentDetail_request_response_roundtrip_and_old_frames_default() {
        val req = Envelope(id = "w3", ts = 0, body = GetWorkflowAgentDetail("c1", "wf_1", 7, agentId = "a1"))
        val reqJson = PocketJson.encodeToString(req)
        assertTrue("\"t\":\"pocket/workflow.agent.fetch\"" in reqJson, reqJson)
        assertEquals(req, PocketJson.decodeFromString<Envelope>(reqJson))

        val resp = Envelope(id = "w4", ts = 0, body = WorkflowAgentDetail("c1", "wf_1", 7, prompt = "Investigate…", result = "patched"))
        assertEquals(resp, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(resp)))

        // a daemon that predates optional agentId still decodes our request minus the hint
        val oldReq = """{"id":"w5","ts":0,"to":"PEER","body":{"t":"pocket/workflow.agent.fetch","convoId":"c1","runId":"wf_1","agentIndex":7}}"""
        assertEquals(null, (PocketJson.decodeFromString<Envelope>(oldReq).body as GetWorkflowAgentDetail).agentId)
    }

    @Test
    fun historyMessage_workflowRunId_trails_and_workflow_tool_predicate() {
        // new daemon → new app: the Workflow tool row carries its run id for card binding
        val row = HistoryMessage(ChatRole.TOOL, "probe", tool = "Workflow", ok = true, workflowRunId = "wf_1")
        assertEquals(row, PocketJson.decodeFromString<HistoryMessage>(PocketJson.encodeToString(row)))

        // old daemon's row (no key) → null; the card renders as a plain tool row
        val legacy = PocketJson.decodeFromString<HistoryMessage>("""{"role":"tool","text":"x","tool":"Workflow"}""")
        assertEquals(null, legacy.workflowRunId)

        // old client skips the unknown key without throwing
        val skipped = PocketJson.decodeFromString<HistoryMessage>(
            """{"role":"tool","text":"x","tool":"Workflow","workflowRunId":"wf_9","futureKey":1}""",
        )
        assertEquals("wf_9", skipped.workflowRunId)

        assertTrue(isWorkflowTool("Workflow"))
        assertFalse(isWorkflowTool("Task"))
        assertFalse(isWorkflowTool("Agent"))
    }

    // ── API presets (issue #113) ─────────────────────────────────────────

    @Test
    fun presets_requests_roundtrip_and_token_is_write_only_plus_redacted() {
        // create: the plaintext token rides client → daemon as a plain JSON string (E2E protects transport)
        val save = Envelope(
            id = "s1", ts = 0,
            body = SavePreset(
                name = "Work proxy", baseUrl = "https://api.example-proxy.com/v1",
                tokenVar = PresetEnv.API_KEY, token = Secret("sk-live-9f2a4c8e3f9a"),
                model = "gpt-4o", smallFastModel = "gpt-4o-mini",
            ),
        )
        val saveJson = PocketJson.encodeToString(save)
        assertTrue("\"t\":\"pocket/presets.save\"" in saveJson, saveJson)
        assertTrue("\"token\":\"sk-live-9f2a4c8e3f9a\"" in saveJson, saveJson) // inline value class = bare string
        assertFalse("\"id\":\"" in saveJson.substringAfter("body"), saveJson)  // null id (create) omitted
        assertEquals(save, PocketJson.decodeFromString<Envelope>(saveJson))

        // …but the frame's toString (accidental logging) redacts the secret
        val printed = save.toString()
        assertFalse("sk-live-9f2a4c8e3f9a" in printed, printed)
        assertTrue("«redacted»" in printed, printed)

        // edit keeping the stored token: null token omitted on the wire ("leave blank to keep")
        val keep = PocketJson.encodeToString(Envelope(id = "s2", ts = 0, body = SavePreset(id = "p1", name = "n", baseUrl = "https://x")))
        assertFalse("\"token\":" in keep, keep)

        // activate / deactivate / delete round-trip; activate-null omits id
        val act = Envelope(id = "a1", ts = 0, body = ActivatePreset(id = "p1", force = true))
        assertEquals(act, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(act)))
        val deact = PocketJson.encodeToString(Envelope(id = "a2", ts = 0, body = ActivatePreset()))
        assertTrue("\"t\":\"pocket/presets.activate\"" in deact, deact)
        assertFalse("\"id\":" in deact.substringAfter("body"), deact)
        val del = Envelope(id = "d1", ts = 0, body = DeletePreset("p1"))
        assertEquals(del, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(del)))
    }

    @Test
    fun presetsState_masks_only_and_decodes_tolerantly() {
        val state = Envelope(
            id = "ps1", ts = 0,
            body = PresetsState(
                presets = listOf(
                    PresetSummary("p1", "Work proxy", "https://api.example-proxy.com/v1", PresetEnv.AUTH_TOKEN, "sk-…••••3f9a", model = "gpt-4o"),
                    PresetSummary("p2", "Personal key", "https://api.anthropic.com", PresetEnv.API_KEY, "sk-…••••a71c"),
                ),
                activeId = "p1",
            ),
        )
        val json = PocketJson.encodeToString(state)
        assertTrue("\"t\":\"pocket/presets.state\"" in json, json)
        assertTrue("sk-…••••3f9a" in json, json) // the mask IS the only token-shaped thing on this wire
        assertEquals(state, PocketJson.decodeFromString<Envelope>(json))

        // refusal carries blockers in the same shape as AuthState (shared client card)
        val blocked = PresetsState(
            presets = emptyList(), activeId = null,
            error = "Sessions on this computer are still working",
            blockers = listOf(AuthBlocker(convoId = "c1", cwd = "/w/acme-web", reason = AuthBlockReason.EXECUTING)),
        )
        val bj = PocketJson.encodeToString(Envelope(id = "ps2", ts = 0, body = blocked))
        assertEquals(blocked, PocketJson.decodeFromString<Envelope>(bj).body as PresetsState)

        // a NEWER daemon's summary (extra key + unknown tokenVar) still decodes: ignoreUnknownKeys +
        // tokenVar-as-string keep a future var name from failing the whole pane
        val future = """{"id":"f1","ts":0,"to":"PEER","body":{"t":"pocket/presets.state","presets":[
            {"id":"p9","name":"n","baseUrl":"https://x","tokenVar":"ANTHROPIC_FANCY_TOKEN","tokenMask":"••••","shiny":true}],"activeId":"p9"}}"""
        val dec = PocketJson.decodeFromString<Envelope>(future).body as PresetsState
        assertEquals("ANTHROPIC_FANCY_TOKEN", dec.presets.single().tokenVar)

        // a minimal old-shape state (empty object) decodes to safe defaults — tail-append compatibility
        val minimal = PocketJson.decodeFromString<Envelope>("""{"id":"f2","ts":0,"to":"PEER","body":{"t":"pocket/presets.state"}}""").body as PresetsState
        assertEquals(emptyList(), minimal.presets)
        assertEquals(null, minimal.activeId)
        assertEquals(null, minimal.fieldError)
    }

    @Test
    fun bridge_origin_fields_roundtrip_and_old_frames_default_null() {
        // issue #91: SessionLive.origin — a bridge-opened conversation names its trigger source
        val bridged = SessionLive("c1", "/x", "sid", origin = "feishu-bot")
        val bj = PocketJson.encodeToString<SessionLive>(bridged)
        assertTrue("\"origin\":\"feishu-bot\"" in bj, bj)
        assertEquals(bridged, PocketJson.decodeFromString<SessionLive>(bj))
        // an interactive session's frame stays byte-identical to the pre-#91 wire (explicitNulls=false)
        assertFalse("origin" in PocketJson.encodeToString<SessionLive>(SessionLive("c1", "/x", "sid")))
        // an OLD daemon's frame (no origin key) decodes to null — no label rendered
        val legacy = """{"convoId":"c3","workdir":"/z","sessionId":"sid3"}"""
        assertEquals(null, PocketJson.decodeFromString<SessionLive>(legacy).origin)

        // ActiveSession.origin — the project list's live row label, same four-direction contract
        val row = ActiveSession("s1", "review MR", executing = true, origin = "feishu-bot")
        assertEquals(row, PocketJson.decodeFromString<ActiveSession>(PocketJson.encodeToString(row)))
        assertFalse("origin" in PocketJson.encodeToString(ActiveSession("s1")))
        assertEquals(null, PocketJson.decodeFromString<ActiveSession>("""{"sessionId":"s1"}""").origin)
    }

    @Test
    fun pairRedeem_headless_roundtrips_and_old_shapes_default_false() {
        // issue #91: the REST redeem body — a bridge declares itself headless
        val headless = PairRedeem("tkt", "pub", headless = true)
        val hj = PocketJson.encodeToString(headless)
        assertTrue("\"headless\":true" in hj, hj)
        assertEquals(headless, PocketJson.decodeFromString<PairRedeem>(hj))
        // an OLD app's redeem (no headless key) decodes as an interactive device on a NEW relay
        assertEquals(PairRedeem("tkt", "pub"), PocketJson.decodeFromString<PairRedeem>("""{"ticket":"tkt","devicePubKey":"pub"}"""))
        // an OLD relay skips the unknown key the usual ignoreUnknownKeys way — pin the skip shape
        // (kotlin has no "old decoder" here; the unknown-key tolerance test above covers the mechanism)
        assertTrue("\"headless\":false" in PocketJson.encodeToString(PairRedeem("tkt", "pub")), "encodeDefaults keeps the flag explicit")

        // DaemonHello.protoV: a NEW daemon announces PROTO_V_HEADLESS; an old relay ignores the value
        val hello = DaemonHello("acct", "edpub", protoV = PROTO_V_HEADLESS)
        val hjson = PocketJson.encodeToString(Envelope(id = "h1", ts = 0, to = Route.RELAY, body = hello))
        assertTrue("\"protoV\":2" in hjson, hjson)
        assertEquals(hello, PocketJson.decodeFromString<Envelope>(hjson).body)
        // an OLD daemon's hello (no protoV key) decodes as 1 — the relay must treat it as pre-headless
        val oldHello = """{"id":"h2","ts":0,"to":"RELAY","body":{"t":"pocket/daemon.hello","accountId":"a","ed25519Pub":"p"}}"""
        assertEquals(1, (PocketJson.decodeFromString<Envelope>(oldHello).body as DaemonHello).protoV)
    }

    @Test
    fun notifyPush_urgent_roundtrips_and_old_frames_default_false() {
        // issue #91: a bridge-approval push rides the urgent flag so the relay delivers it even with a
        // phone attached elsewhere
        val urgent = Envelope(id = "n1", ts = 0, to = Route.RELAY, body = NotifyPush("Approval needed", "Run command waiting", workdir = "/w", sessionId = "s", urgent = true))
        val json = PocketJson.encodeToString(urgent)
        assertTrue("\"urgent\":true" in json, json)
        assertEquals(urgent, PocketJson.decodeFromString<Envelope>(json))
        // an OLD daemon's push (no urgent key) decodes to false — the ordinary deviceCount==0 gate applies
        val old = """{"id":"n2","ts":0,"to":"RELAY","body":{"t":"pocket/push.notify","title":"t","body":"b"}}"""
        assertEquals(NotifyPush("t", "b"), PocketJson.decodeFromString<Envelope>(old).body as NotifyPush)
    }

    // ---- folder-share (issue #115) ----

    @Test
    fun accessTier_roundtrips_and_future_tier_degrades_to_safest() {
        // every known tier round-trips by its wire name
        val expected = mapOf(
            AccessTier.REVIEW to "review",
            AccessTier.COLLABORATE to "collaborate",
            AccessTier.AUTONOMOUS to "autonomous",
        )
        for ((tier, name) in expected) {
            assertEquals("\"$name\"", PocketJson.encodeToString(tier))
            assertEquals(tier, PocketJson.decodeFromString<AccessTier>("\"$name\""))
        }
        // a tier only a NEWER peer knows must NOT fail the frame — it degrades to the SAFEST (REVIEW-like)
        // so a scoped credential never falls open on a version skew
        assertEquals(AccessTier.UNKNOWN, PocketJson.decodeFromString<AccessTier>("\"read_only_v2\""))
        // and the ceiling of an unknown tier is the most cautious mode (never bypass)
        assertEquals(PermissionMode.DEFAULT, AccessTier.ceiling(AccessTier.UNKNOWN))
        assertEquals(PermissionMode.DEFAULT, AccessTier.ceiling(AccessTier.REVIEW))
        assertEquals(PermissionMode.ACCEPT_EDITS, AccessTier.ceiling(AccessTier.COLLABORATE))
        assertEquals(PermissionMode.ACCEPT_EDITS, AccessTier.ceiling(AccessTier.AUTONOMOUS))
    }

    @Test
    fun createShare_roundtrips_with_defaults_and_old_frames_default() {
        val env = Envelope(id = "sh1", ts = 0, body = CreateShare("/Users/panda/repo"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/share.create\"" in json, json)
        assertTrue("\"tier\":\"collaborate\"" in json, json)          // encodeDefaults
        assertTrue("\"expiresInSec\":604800" in json, json)           // 7d default, encodeDefaults
        assertFalse("label" in json, json)                            // explicitNulls=false
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
        // a minimal owner frame (only path) reads with the collaborate/7d defaults
        val minimal = """{"id":"sh2","ts":0,"to":"PEER","body":{"t":"pocket/share.create","path":"/x"}}"""
        assertEquals(CreateShare("/x"), PocketJson.decodeFromString<Envelope>(minimal).body)
    }

    @Test
    fun listShares_and_revokeShare_roundtrip() {
        val list = Envelope(id = "sh3", ts = 0, body = ListShares)
        val listJson = PocketJson.encodeToString(list)
        assertTrue("\"t\":\"pocket/share.list\"" in listJson, listJson)
        assertEquals(ListShares, PocketJson.decodeFromString<Envelope>(listJson).body)

        val rev = Envelope(id = "sh4", ts = 0, body = RevokeShare("dev-guest-1"))
        val revJson = PocketJson.encodeToString(rev)
        assertTrue("\"t\":\"pocket/share.revoke\"" in revJson, revJson)
        assertEquals(rev, PocketJson.decodeFromString<Envelope>(revJson))
    }

    @Test
    fun shareInvite_and_shareCreated_roundtrip_and_omit_null_error() {
        val invite = ShareInvite(
            relay = "wss://pocket.ark-nexus.cc", accountId = "acct", daemonPub = "pub", ticket = "tkt",
            folderName = "repo", tier = AccessTier.COLLABORATE, expiresAt = 1_800_000_000_000, ttlSec = 120,
            ownerLabel = "Pandas-MacBook-Pro",
        )
        val ok = Envelope(id = "sc1", ts = 0, body = ShareCreated(ok = true, invite = invite))
        val okJson = PocketJson.encodeToString(ok)
        assertTrue("\"t\":\"pocket/share.created\"" in okJson, okJson)
        assertTrue("\"folderName\":\"repo\"" in okJson, okJson)
        assertFalse("\"error\"" in okJson, okJson) // explicitNulls=false — a success carries no error
        assertEquals(ok, PocketJson.decodeFromString<Envelope>(okJson))

        val err = ShareCreated(ok = false, error = "a phone pairing is in progress — retry shortly")
        val errJson = PocketJson.encodeToString(Envelope(id = "sc2", ts = 0, body = err))
        assertFalse("invite" in errJson, errJson) // null invite omitted
        assertEquals(err, PocketJson.decodeFromString<Envelope>(errJson).body)
    }

    @Test
    fun shareListing_and_shareRevoked_roundtrip() {
        val listing = ShareListing(
            items = listOf(
                ShareInfo("dev1", "/Users/panda/repo", AccessTier.COLLABORATE, createdAt = 1, expiresAt = 2, online = true, activeSessions = 1),
                ShareInfo("dev2", "/Users/panda/web", AccessTier.REVIEW, createdAt = 3, expiresAt = 4, guestLabel = "alex", lastActiveAt = 5, revoked = true),
            ),
        )
        val env = Envelope(id = "sl1", ts = 0, body = listing)
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/share.listing\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        val revoked = Envelope(id = "sr1", ts = 0, body = ShareRevoked("dev1", ok = true))
        val rJson = PocketJson.encodeToString(revoked)
        assertTrue("\"t\":\"pocket/share.revoked\"" in rJson, rJson)
        assertFalse("error" in rJson, rJson) // explicitNulls=false
        assertEquals(revoked, PocketJson.decodeFromString<Envelope>(rJson))
    }

    @Test
    fun directoryEntry_share_fields_roundtrip_and_old_frames_default_null() {
        // a GUEST's shared row carries the origin label + expiry + tier; an ordinary local row omits them
        val shared = DirectoryEntry(
            path = "/Users/panda/repo", name = "repo", isDir = true, hasSessions = true,
            sharedBy = "Pandas-MacBook-Pro", shareExpiresAt = 1_800_000_000_000, shareTier = AccessTier.COLLABORATE,
        )
        val json = PocketJson.encodeToString(shared)
        assertTrue("\"sharedBy\":\"Pandas-MacBook-Pro\"" in json, json)
        assertTrue("\"shareTier\":\"collaborate\"" in json, json)
        assertEquals(shared, PocketJson.decodeFromString<DirectoryEntry>(json))
        // a plain local dir stays byte-identical to the pre-#115 wire (explicitNulls=false)
        val local = PocketJson.encodeToString(DirectoryEntry(path = "/p", name = "p", isDir = true))
        assertFalse("sharedBy" in local, local)
        assertFalse("shareExpiresAt" in local, local)
        assertFalse("shareTier" in local, local)
        // an old daemon's entry (no share keys) decodes with null share fields — a plain row
        val old = """{"path":"/p","name":"p","isDir":true}"""
        val back = PocketJson.decodeFromString<DirectoryEntry>(old)
        assertEquals(null, back.sharedBy)
        assertEquals(null, back.shareExpiresAt)
        assertEquals(null, back.shareTier)
        // a FUTURE peer's unknown shareTier degrades to UNKNOWN in-frame and the whole entry still decodes
        // (not dropped) — the fail-closed contract in structured context, not just a bare string
        val future = """{"path":"/p","name":"p","isDir":true,"sharedBy":"panda","shareTier":"read_only_v2"}"""
        val futureBack = PocketJson.decodeFromString<DirectoryEntry>(future)
        assertEquals(AccessTier.UNKNOWN, futureBack.shareTier)
        assertEquals("panda", futureBack.sharedBy)
    }

    @Test
    fun share_shapes_tolerate_an_unknown_future_key() {
        // the new-daemon → OLD-phone direction for the share shapes: a NEWER daemon adds a field the old
        // phone's schema lacks, and the old phone must SKIP it (ignoreUnknownKeys) rather than drop the whole
        // frame. Pin it directly on the #115 shapes (the mechanism is shared, but the four-direction contract
        // is per-shape) — the known fields must survive the skip.
        val entry = PocketJson.decodeFromString<DirectoryEntry>(
            """{"path":"/p","name":"p","isDir":true,"sharedBy":"panda","shareTier":"collaborate","futureFlag":true,"futureObj":{"x":1}}""",
        )
        assertEquals("panda", entry.sharedBy)
        assertEquals(AccessTier.COLLABORATE, entry.shareTier)

        val info = PocketJson.decodeFromString<ShareInfo>(
            """{"deviceId":"d1","path":"/p","tier":"review","createdAt":1,"expiresAt":2,"futureField":[{"k":"v"}]}""",
        )
        assertEquals("d1", info.deviceId)
        assertEquals(AccessTier.REVIEW, info.tier)

        // and the enclosing ShareListing frame still decodes with the unknown key nested in an item
        val listing = PocketJson.decodeFromString<Envelope>(
            """{"id":"z1","ts":0,"to":"PEER","body":{"t":"pocket/share.listing","items":[
               {"deviceId":"d2","path":"/q","tier":"collaborate","createdAt":3,"expiresAt":4,"unknownFuture":9}]}}""",
        )
        assertEquals("d2", (listing.body as ShareListing).items.single().deviceId)
    }

    // ── session groups (issue #119) ──────────────────────────────────────────

    @Test
    fun sessionGroup_roundtrips() {
        val g = SessionGroup(id = "g1", name = "Feature work", order = 2)
        assertEquals(g, PocketJson.decodeFromString<SessionGroup>(PocketJson.encodeToString(g)))
    }

    @Test
    fun sessionSummary_group_roundtrips_and_defaults_for_old_daemons() {
        val s = SessionSummary(
            sessionId = "s", title = "t", firstPrompt = "p",
            messageCount = 1, cwd = "/x", lastModified = 0, group = "g1",
        )
        assertEquals(s, PocketJson.decodeFromString<SessionSummary>(PocketJson.encodeToString(s)))
        // an old daemon's summary has no `group` key → decodes to null (ungrouped), not an error
        val old = """{"sessionId":"s","title":"t","firstPrompt":"p","messageCount":1,"cwd":"/x","lastModified":0}"""
        assertEquals(null, PocketJson.decodeFromString<SessionSummary>(old).group)
    }

    @Test
    fun sessions_frame_groups_roundtrip_and_omit_when_null() {
        val withGroups = Envelope(
            id = "sg1", ts = 0,
            body = Sessions(
                workdir = "/x",
                items = listOf(SessionSummary("s", "t", "p", 1, "/x", 0, group = "g1")),
                groups = listOf(SessionGroup("g1", "Docs", 0), SessionGroup("g2", "Bugs", 1)),
            ),
        )
        val json = PocketJson.encodeToString(withGroups)
        assertTrue("\"t\":\"pocket/sessions\"" in json, json)
        assertTrue("\"groups\"" in json, json)
        assertEquals(withGroups, PocketJson.decodeFromString<Envelope>(json))

        // an old daemon omits groups entirely → null, and an old app that never sends them still decodes
        val noGroups = Sessions(workdir = "/x", items = emptyList())
        val j2 = PocketJson.encodeToString(noGroups)
        assertFalse("groups" in j2, j2)
        assertEquals(noGroups, PocketJson.decodeFromString<Sessions>(j2))
        // a legacy daemon's frame (no groups key at all) decodes with groups == null
        val legacy = """{"workdir":"/x","items":[]}"""
        assertEquals(null, PocketJson.decodeFromString<Sessions>(legacy).groups)
    }

    @Test
    fun old_peer_skips_a_populated_groups_array() {
        // the new-daemon → OLD-app direction, exercised as a STRUCTURED unknown-key skip: an old app whose
        // schema lacks `groups` decodes a NEW pocket/sessions frame that DOES carry a populated array-of-objects
        // and must skip it (ignoreUnknownKeys) — the exact skip path over structured data that has bitten before.
        // [OldSessions] (top-level) simulates the pre-#119 schema.
        val newFrame = """{"workdir":"/x","items":[],"groups":[{"id":"g1","name":"Docs","order":0}]}"""
        val back = PocketJson.decodeFromString<OldSessions>(newFrame)
        assertEquals("/x", back.workdir)
        assertTrue(back.items.isEmpty())
    }

    @Test
    fun group_mutation_frames_roundtrip() {
        val create = Envelope(id = "1", ts = 0, body = GroupCreate("/x", "Feature"))
        assertTrue("\"t\":\"pocket/group.create\"" in PocketJson.encodeToString(create))
        assertEquals(create, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(create)))

        val rename = Envelope(id = "2", ts = 0, body = GroupRename("/x", "g1", "Renamed"))
        assertTrue("\"t\":\"pocket/group.rename\"" in PocketJson.encodeToString(rename))
        assertEquals(rename, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(rename)))

        val delete = Envelope(id = "3", ts = 0, body = GroupDelete("/x", "g1"))
        assertTrue("\"t\":\"pocket/group.delete\"" in PocketJson.encodeToString(delete))
        assertEquals(delete, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(delete)))

        // assign with a group, and assign-out (groupId null is omitted by explicitNulls=false)
        val assign = Envelope(id = "4", ts = 0, body = GroupAssign("/x", "s1", "g1"))
        assertTrue("\"t\":\"pocket/group.assign\"" in PocketJson.encodeToString(assign))
        assertEquals(assign, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(assign)))

        val unassign = GroupAssign("/x", "s1", null)
        val uj = PocketJson.encodeToString(unassign)
        assertFalse("groupId" in uj, uj)
        assertEquals(unassign, PocketJson.decodeFromString<GroupAssign>(uj))
    }
}
