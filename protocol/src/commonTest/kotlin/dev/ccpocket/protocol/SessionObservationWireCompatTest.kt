package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-observation `pocket/session.open` reader an already-shipped daemon decodes with. */
@Serializable
private data class PreObservationOpenSession(
    val workdir: String,
    val resumeId: String? = null,
    val takeOver: Boolean = false,
    val agent: AgentKind = AgentKind.CLAUDE,
)

/** The pre-observation `ManagedSessionEntry` reader an already-shipped App decodes rows with (nested unknown objects). */
@Serializable
private data class PreObservationManagedEntry(
    val sessionId: String,
    val agent: AgentKind? = null,
    val summary: PreObservationSummary? = null,
    val group: String? = null,
)

@Serializable
private data class PreObservationSummary(val sessionId: String, val title: String, val firstPrompt: String, val messageCount: Int, val cwd: String, val lastModified: Long)

@Serializable
private data class PreObservationManagedState(val requestId: String? = null, val workdir: String, val items: List<PreObservationManagedEntry>? = null)

/** The pre-observation `pocket/session.live` reader an already-shipped App decodes with. */
@Serializable
private data class PreObservationSessionLive(
    val convoId: String,
    val workdir: String,
    val sessionId: String? = null,
    val observing: Boolean = false,
    val notice: String? = null,
)

/**
 * Wire compatibility of the read-only observation surface (docs/design/DOTS-SESSION-OBSERVABILITY.md §4.6): every new
 * field is a trailing optional in BOTH directions, the new request round-trips under its discriminator, unknown
 * vocabulary values read as "unknown" (never as a permission or a terminal state), and a binding a client could use to
 * grant itself more than read-only observation is structurally invalid.
 */
class SessionObservationWireCompatTest {

    private fun roundTrip(frame: Frame): Frame =
        PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope("1", 0, body = frame))).body

    private fun bodyJson(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(Envelope("1", 0, body = frame))).jsonObject
            .getValue("body").toString()

    private fun body(json: String): Frame = PocketJson.decodeFromString<Envelope>("""{"id":"1","ts":0,"body":$json}""").body

    private val binding = ObservationBinding(parentTaskRef = "Dot: refactor the parser")
    private val progress = ObservedProgress(state = ObservedStates.RUNNING, evidence = ObservedEvidence.NATIVE_EVENT, sourceEventAt = 5, lastActivityAt = 6, observedAt = 7, freshness = ObservedFreshness.CURRENT, turnId = "turn-1")

    @Test
    fun set_observation_roundtrips_under_its_discriminator_and_clears_with_null() {
        val set = SetSessionObservation("r1", "/p", AgentKind.CODEX, "sid", binding)
        assertEquals(set, roundTrip(set))
        assertTrue(bodyJson(set).contains("\"t\":\"pocket/managed.observe\""))
        val clear = SetSessionObservation("r2", "/p", AgentKind.CODEX, "sid", binding = null)
        assertNull((roundTrip(clear) as SetSessionObservation).binding)
    }

    @Test
    fun open_session_observe_only_is_a_trailing_optional_both_ways() {
        val open = OpenSession("/p", "sid", agent = AgentKind.CODEX, observeOnly = true)
        assertEquals(true, (roundTrip(open) as OpenSession).observeOnly)
        // an old daemon's reader skips the key
        val old = PocketJson.decodeFromString<PreObservationOpenSession>(bodyJson(open))
        assertEquals("sid", old.resumeId)
        assertFalse(old.takeOver)
        // an old App never sends it: absent reads as false
        val fromOld = body("""{"t":"pocket/session.open","workdir":"/p","resumeId":"sid"}""") as OpenSession
        assertFalse(fromOld.observeOnly)
    }

    @Test
    fun session_live_observation_is_ignored_by_an_old_app_and_absent_from_an_old_daemon() {
        val live = SessionLive("c", "/p", "sid", observing = true, notice = "read-only", observation = SessionObservation(binding, readOnly = true, progress))
        assertEquals(live, roundTrip(live))
        val old = PocketJson.decodeFromString<PreObservationSessionLive>(bodyJson(live))
        assertTrue(old.observing)
        assertEquals("read-only", old.notice)
        val fromOldDaemon = body("""{"t":"pocket/session.live","convoId":"c","workdir":"/p","observing":true}""") as SessionLive
        assertNull(fromOldDaemon.observation)
    }

    @Test
    fun summary_and_managed_entry_carry_the_snapshot_as_trailing_optionals() {
        val summary = SessionSummary("sid", "t", "p", 1, "", 1L, agent = AgentKind.CODEX, observation = SessionObservation(binding, true, progress))
        val decoded = PocketJson.decodeFromString<SessionSummary>(PocketJson.encodeToString(summary))
        assertEquals(summary, decoded)
        val plain = PocketJson.decodeFromString<SessionSummary>("""{"sessionId":"sid","title":"t","firstPrompt":"p","messageCount":1,"cwd":"","lastModified":1}""")
        assertNull(plain.observation)
        val entry = ManagedSessionEntry("sid", AgentKind.CODEX, observation = SessionObservation(binding, true))
        val state = ManagedSessionsState("r", "/p", items = listOf(entry), entry = entry)
        assertEquals(state, roundTrip(state))
        val import = ImportSession("r", "/p", AgentKind.CODEX, "sid", observation = binding)
        assertEquals(import, roundTrip(import))
        assertNull((body("""{"t":"pocket/managed.import","requestId":"r","workdir":"/p","agent":"codex","sessionId":"sid"}""") as ImportSession).observation)
    }

    @Test
    fun capabilities_default_to_false_in_both_directions() {
        assertFalse((body("""{"t":"pocket/daemon.info"}""") as DaemonInfo).supportsSessionObservationV1)
        assertFalse((body("""{"t":"pocket/client.caps"}""") as ClientCaps).supportsSessionObservationV1)
        assertTrue((roundTrip(DaemonInfo(supportsSessionObservationV1 = true)) as DaemonInfo).supportsSessionObservationV1)
        assertTrue((roundTrip(ClientCaps(supportsSessionObservationV1 = true)) as ClientCaps).supportsSessionObservationV1)
    }

    @Test
    fun unknown_vocabulary_reads_as_unknown_never_as_a_terminal_or_running_state() {
        val future = PocketJson.decodeFromString<ObservedProgress>("""{"state":"paused_by_cloud","freshness":"fresh","evidence":"telepathy"}""")
        assertEquals("paused_by_cloud", future.state) // the raw value is kept on the wire…
        assertEquals(ObservedStates.UNKNOWN, ObservedStates.normalize(future.state)) // …and normalized by every reader
        assertEquals(ObservedFreshness.UNKNOWN, ObservedFreshness.normalize(future.freshness))
        assertEquals(ObservedStates.RUNNING, ObservedStates.normalize("running"))
    }

    @Test
    fun a_client_cannot_mint_a_verified_or_writable_binding() {
        assertTrue(isValidObservationBinding(binding))
        assertTrue(isValidObservationBinding(ObservationBinding(parentTaskRef = null)))
        assertFalse(isValidObservationBinding(binding.copy(attribution = ObservationAttributions.VERIFIED)))
        assertFalse(isValidObservationBinding(binding.copy(readOnly = false)))
        assertFalse(isValidObservationBinding(binding.copy(source = "")))
        assertFalse(isValidObservationBinding(binding.copy(source = "dot/../x")))
        assertFalse(isValidObservationBinding(binding.copy(parentTaskRef = "x".repeat(OBSERVATION_REF_MAX_CHARS + 1))))
        assertFalse(isValidObservationBinding(binding.copy(parentTaskRef = "bad\u0000ref")))
        assertFalse(isValidObservationBinding(binding.copy(parentTaskRef = "   ")))
    }

    @Test
    fun ordinary_frames_carry_no_observation_key_at_all() {
        // explicitNulls = false: a plain frame's bytes are exactly what an old peer always received
        assertFalse("observation" in bodyJson(SessionLive("c", "/p")))
        assertFalse("observation" in PocketJson.encodeToString(SessionSummary("sid", "t", "p", 1, "", 1L)))
        assertFalse("observation" in PocketJson.encodeToString(ManagedSessionEntry("sid", AgentKind.CODEX)))
        assertFalse("observation" in bodyJson(ImportSession("r", "/p", AgentKind.CODEX, "sid")))
    }

    @Test
    fun an_old_row_reader_skips_the_nested_snapshot_inside_a_managed_page() {
        val entry = ManagedSessionEntry(
            "sid", AgentKind.CODEX, summary = SessionSummary("sid", "t", "p", 1, "", 1L, agent = AgentKind.CODEX, observation = SessionObservation(binding, true, progress)),
            observation = SessionObservation(binding, true, progress),
        )
        val old = PocketJson.decodeFromString<PreObservationManagedState>(bodyJson(ManagedSessionsState("r", "/p", items = listOf(entry))))
        assertEquals("sid", old.items!!.single().sessionId)
        assertEquals("t", old.items!!.single().summary!!.title)
    }

    @Test
    fun bare_json_defaults_are_fail_safe() {
        val b = PocketJson.decodeFromString<ObservationBinding>("{}")
        assertTrue(b.readOnly)
        assertEquals(ObservationAttributions.USER_ASSIGNED, b.attribution)
        assertEquals(ObservationSources.OPENAI_DOT, b.source)
        assertNull(b.parentTaskRef)
        val o = PocketJson.decodeFromString<SessionObservation>("{}")
        assertNull(o.binding); assertFalse(o.readOnly); assertNull(o.progress)
        val p = PocketJson.decodeFromString<ObservedProgress>("{}")
        assertEquals(ObservedStates.UNKNOWN, p.state); assertEquals(ObservedFreshness.UNKNOWN, p.freshness); assertEquals("turn", p.scope)
    }

    @Test
    fun an_unknown_agent_on_the_new_request_reads_as_null_never_as_claude() {
        val f = body("""{"t":"pocket/managed.observe","requestId":"r","workdir":"/p","agent":"future_agent","sessionId":"sid"}""") as SetSessionObservation
        assertNull(f.agent)
    }

    @Test
    fun only_an_https_ref_is_a_link() {
        assertTrue(isObservationLink("https://chatgpt.com/dots/x/activity/1"))
        assertFalse(isObservationLink("http://chatgpt.com/x"))
        assertFalse(isObservationLink("chatgpt://task/1"))
        assertFalse(isObservationLink("https://x.y z"))
        assertFalse(isObservationLink("Dot: refactor the parser"))
        assertFalse(isObservationLink(null))
    }
}
