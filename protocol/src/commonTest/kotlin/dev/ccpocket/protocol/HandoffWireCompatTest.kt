package dev.ccpocket.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire compatibility for the Session Handoff family (SESSION-HANDOFF.md §9.1): round-trips of every
 * frame, defaults for absent fields (old JSON → new peer), UNKNOWN fallback for enum values only a
 * newer peer knows, and the drop path for the brand-new discriminators (new frame → old peer).
 */
class HandoffWireCompatTest {

    private fun handoff() = SessionHandoff(
        id = "h1",
        sourceSessionId = "sess-1",
        workdir = "/w",
        agent = AgentKind.CLAUDE,
        initiatorDeviceId = "devA",
        initiatorLabel = "Panda",
        kind = HandoffKind.REVIEW,
        status = HandoffStatus.WAITING,
        access = HandoffAccess.REVIEW_READ_ONLY,
        brief = HandoffBrief(request = "review the relay ACK path", focusAreas = listOf("ack", "revoke race")),
        createdAt = 1_000,
        expiresAt = 2_000,
    )

    // ---- round-trips ------------------------------------------------------

    @Test
    fun createHandoff_roundtrips_and_omits_null_optionals() {
        val env = Envelope(
            id = "1", ts = 7,
            body = CreateHandoff(
                workdir = "/w", sessionId = "sess-1",
                brief = HandoffBrief(request = "review this"),
            ),
        )
        val json = PocketJson.encodeToString(env)
        assertTrue("\"t\":\"pocket/handoff.create\"" in json, json)
        assertTrue("\"kind\":\"review\"" in json, json)                 // encodeDefaults: intent always on the wire
        assertTrue("\"access\":\"review_read_only\"" in json, json)
        assertFalse("recipientLabel" in json, json)                     // explicitNulls=false — absent, not null
        assertFalse("sourceConvoId" in json, json)
        assertFalse("recipientDeviceId" in json, json)                  // unbound create = byte-identical to the pre-binding shape
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    /** The collaborator-picker binding rides the tail: bound creates round-trip, and (critically) the
     *  field is a NARROWING one — it only ships in the same release as the rest of Handoff v1, so no
     *  peer exists that knows handoff.create but silently drops the binding. */
    @Test
    fun createHandoff_withRecipientBinding_roundtrips() {
        val env = Envelope(
            id = "1b", ts = 7,
            body = CreateHandoff(
                workdir = "/w", sessionId = "sess-1", brief = HandoffBrief(request = "review"),
                recipientLabel = "Frank", recipientDeviceId = "devB",
            ),
        )
        val json = PocketJson.encodeToString(env)
        assertTrue("\"recipientDeviceId\":\"devB\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun handoffCreated_and_updated_roundtrip_with_a_full_entity() {
        val created = Envelope(id = "2", ts = 0, body = HandoffCreated(ok = true, handoff = handoff()))
        assertEquals(created, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(created)))

        val returned = handoff().copy(
            status = HandoffStatus.RETURNED,
            recipientDeviceId = "devB", recipientLabel = "Frank",
            acceptedAt = 1_500, returnedAt = 1_900,
            result = HandoffResult(
                summary = "reviewed", verdict = "LGTM",
                findings = listOf(
                    HandoffFinding(
                        title = "race on revoke", severity = HandoffFinding.SEVERITY_HIGH,
                        detail = "ack can land after revoke", file = "relay/Ack.kt", line = 42,
                    ),
                ),
                returnedByDeviceId = "devB", returnedAt = 1_900,
            ),
        )
        val upd = Envelope(id = "3", ts = 0, body = HandoffUpdated(returned))
        val json = PocketJson.encodeToString(upd)
        assertTrue("\"status\":\"returned\"" in json, json)
        assertTrue("\"severity\":\"high\"" in json, json)
        assertEquals(upd, PocketJson.decodeFromString<Envelope>(json))
    }

    @Test
    fun control_frames_roundtrip() {
        listOf(
            Envelope("4", 0, body = ListHandoffs(workdir = "/w")),
            Envelope("5", 0, body = HandoffListing(listOf(handoff()))),
            Envelope("6", 0, body = AcceptHandoff("h1")),
            Envelope("7", 0, body = DeclineHandoff("h1", reason = "busy today")),
            Envelope("8", 0, body = CancelHandoff("h1")),
            Envelope("9", 0, body = RecallHandoff("h1")),
            Envelope("10", 0, body = ReturnHandoff("h1", result = HandoffResult(summary = "done"))),
            Envelope("11", 0, body = ReturnHandoff("h1")), // empty return is a valid give-back
            Envelope("12", 0, body = CompleteHandoff("h1")),
        ).forEach { env ->
            assertEquals(env, PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(env)))
        }
    }

    // ---- old JSON (missing new fields) → defaults -------------------------

    @Test
    fun unknown_structured_keys_are_skipped_losslessly() {
        // checklist 6(c): when a FUTURE peer tail-appends a structured field (array of objects),
        // THIS version must skip it precisely and keep every field it does know intact — this is
        // the exact path today's build walks once SessionHandoff grows.
        val json = """{"id":"14","ts":0,"to":"PEER","body":{"t":"pocket/handoff.updated",
            "handoff":{"id":"h10","sourceSessionId":"s10","status":"waiting",
            "attachments":[{"path":"a/b.txt","bytes":12},{"path":"c.png","bytes":34}],
            "recipientLabel":"Frank"}}}""".replace("\n", "")
        val h = (PocketJson.decodeFromString<Envelope>(json).body as HandoffUpdated).handoff
        assertEquals("h10", h.id)
        assertEquals("s10", h.sourceSessionId)
        assertEquals(HandoffStatus.WAITING, h.status)
        assertEquals("Frank", h.recipientLabel, "fields AFTER the unknown key must survive the skip")
    }

    @Test
    fun a_minimal_handoff_decodes_via_defaults() {
        // a future slimmer peer (or an older one that predates trailing fields) sends only the ids
        val json = """{"id":"13","ts":0,"to":"PEER","body":{"t":"pocket/handoff.updated",
            "handoff":{"id":"h9","sourceSessionId":"s9"}}}""".replace("\n", "")
        val h = (PocketJson.decodeFromString<Envelope>(json).body as HandoffUpdated).handoff
        assertEquals("h9", h.id)
        assertEquals("s9", h.sourceSessionId)
        assertEquals(AgentKind.CLAUDE, h.agent)
        assertEquals(HandoffStatus.UNKNOWN, h.status, "absent status must read as the locked fallback")
        assertEquals(HandoffAccess.UNKNOWN, h.access)
        assertTrue(h.allowedRoots.isEmpty())
        assertNull(h.result)
        assertEquals("", h.brief.request)
    }

    @Test
    fun a_minimal_result_and_finding_decode_via_defaults() {
        val json = """{"id":"14","ts":0,"to":"PEER","body":{"t":"pocket/handoff.return","handoffId":"h1",
            "result":{"summary":"ok","findings":[{"title":"nit"}]}}}""".replace("\n", "")
        val r = (PocketJson.decodeFromString<Envelope>(json).body as ReturnHandoff).result!!
        assertEquals("ok", r.summary)
        assertEquals("", r.returnedByDeviceId, "daemon stamps identity — a blank draft must decode")
        assertEquals(0, r.returnedAt)
        val f = r.findings.single()
        assertEquals("nit", f.title)
        assertEquals(HandoffFinding.SEVERITY_INFO, f.severity)
        assertNull(f.file); assertNull(f.line)
    }

    @Test
    fun createHandoff_without_trailing_optionals_decodes_via_defaults() {
        val json = """{"id":"15","ts":0,"to":"PEER","body":{"t":"pocket/handoff.create",
            "workdir":"/w","sessionId":"s1","brief":{"request":"look"}}}""".replace("\n", "")
        val c = PocketJson.decodeFromString<Envelope>(json).body as CreateHandoff
        assertEquals(HandoffKind.REVIEW, c.kind)
        assertEquals(HandoffAccess.REVIEW_READ_ONLY, c.access)
        assertEquals(DEFAULT_HANDOFF_EXPIRES_SEC, c.expiresInSec)
        assertNull(c.recipientLabel)
        assertNull(c.recipientDeviceId, "absent binding must read as the open-invite semantics")
    }

    // ---- unknown enum values → UNKNOWN (never a failed decode) ------------

    @Test
    fun unknown_enum_values_degrade_to_UNKNOWN() {
        val json = """{"id":"16","ts":0,"to":"PEER","body":{"t":"pocket/handoff.updated",
            "handoff":{"id":"h1","sourceSessionId":"s1","kind":"pair_program","status":"suspended",
            "access":"continue_yolo"}}}""".replace("\n", "")
        val h = (PocketJson.decodeFromString<Envelope>(json).body as HandoffUpdated).handoff
        assertEquals(HandoffKind.UNKNOWN, h.kind)
        assertEquals(HandoffStatus.UNKNOWN, h.status)
        assertEquals(HandoffAccess.UNKNOWN, h.access)
        assertFalse(h.status.isTerminal, "an unreadable state must stay conservatively locked, not release")
    }

    /**
     * §5.4's graceful-recall markers ride the TAIL of SessionHandoff: an in-flight recall
     * ([SessionHandoff.recallPending]) and an unclean settle ([SessionHandoff.recallIncomplete]).
     * A peer that predates them omits both keys and must read the pre-§5.4 semantics — false, i.e.
     * "no recall in flight / nothing to warn about" — never a decode failure.
     */
    @Test
    fun recall_markers_roundtrip_and_default_false_for_an_older_peer() {
        val pending = handoff().copy(status = HandoffStatus.IN_PROGRESS, recipientDeviceId = "devB", recallPending = true)
        val env = Envelope("17", 0, body = HandoffUpdated(pending))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"recallPending\":true" in json, json)
        assertTrue("\"recallIncomplete\":false" in json, json) // encodeDefaults: intent always on the wire
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        val recalled = handoff().copy(status = HandoffStatus.RECALLED, recallIncomplete = true)
        assertEquals(
            recalled,
            (PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope("18", 0, body = HandoffUpdated(recalled)))).body as HandoffUpdated).handoff,
        )

        val old = """{"id":"19","ts":0,"to":"PEER","body":{"t":"pocket/handoff.updated",
            "handoff":{"id":"h1","sourceSessionId":"s1","status":"in_progress"}}}""".replace("\n", "")
        val h = (PocketJson.decodeFromString<Envelope>(old).body as HandoffUpdated).handoff
        assertFalse(h.recallPending, "an older peer's row must not read as a recall in flight")
        assertFalse(h.recallIncomplete)
    }

    /** The create refusal's machine-readable code is a trailing optional too: an older daemon answers
     *  with error text only, and the field decodes as null (clients fall back to [HandoffCreated.error]). */
    @Test
    fun handoffCreated_code_roundtrips_and_is_absent_for_an_older_daemon() {
        val env = Envelope("20", 0, body = HandoffCreated(ok = false, error = "not implemented yet", code = "handoff_not_supported"))
        val json = PocketJson.encodeToString(env)
        assertTrue("\"code\":\"handoff_not_supported\"" in json, json)
        assertEquals(env, PocketJson.decodeFromString<Envelope>(json))

        val old = """{"id":"21","ts":0,"to":"PEER","body":{"t":"pocket/handoff.created","ok":false,"error":"busy"}}"""
        val created = PocketJson.decodeFromString<Envelope>(old).body as HandoffCreated
        assertFalse(created.ok)
        assertEquals("busy", created.error)
        assertNull(created.code)
    }

    @Test
    fun lease_roundtrips_and_recallRequested_defaults_false() {
        val lease = SessionControllerLease(
            sessionId = "s1", handoffId = "h1", controllerDeviceId = "devB",
            acquiredAt = 5, leaseExpiresAt = 9, recallRequested = true,
        )
        assertEquals(lease, PocketJson.decodeFromString<SessionControllerLease>(PocketJson.encodeToString(lease)))
        val old = """{"sessionId":"s1","handoffId":"h1","controllerDeviceId":"devB","acquiredAt":5,"leaseExpiresAt":9}"""
        assertFalse(PocketJson.decodeFromString<SessionControllerLease>(old).recallRequested)
    }

    @Test
    fun terminal_set_matches_the_state_machine() {
        assertTrue(HandoffStatus.COMPLETED.isTerminal)
        assertTrue(HandoffStatus.DECLINED.isTerminal)
        assertTrue(HandoffStatus.CANCELLED.isTerminal)
        assertTrue(HandoffStatus.EXPIRED.isTerminal)
        assertTrue(HandoffStatus.RECALLED.isTerminal)
        assertFalse(HandoffStatus.DRAFT.isTerminal)
        assertFalse(HandoffStatus.WAITING.isTerminal)
        assertFalse(HandoffStatus.IN_PROGRESS.isTerminal)
        assertFalse(HandoffStatus.RETURNED.isTerminal)
        assertFalse(HandoffStatus.UNKNOWN.isTerminal)
    }

    // ---- new frame → old peer: the drop path ------------------------------

    @Test
    fun an_unknown_handoff_frame_fails_the_envelope_decode_the_drop_path() {
        // what an OLD daemon does with pocket/handoff.create: unknown discriminator → decode throws →
        // the runCatching-wrapped transport drops the frame (no reply — clients arm a deadline).
        val json = """{"id":"17","ts":0,"to":"PEER","body":{"t":"pocket/handoff.frobnicate","x":1}}"""
        val r = runCatching { PocketJson.decodeFromString<Envelope>(json) }
        assertTrue(r.isFailure, "unknown discriminator must throw (the transport's drop path), not decode to garbage")
    }
}
