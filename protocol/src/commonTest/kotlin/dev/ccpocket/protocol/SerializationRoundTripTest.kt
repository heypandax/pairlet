package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun sessionGone_roundtrips() {
        val env = Envelope(id = "3", ts = 0, body = SessionGone("c9"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/session.gone\"" in json, json)
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
}
