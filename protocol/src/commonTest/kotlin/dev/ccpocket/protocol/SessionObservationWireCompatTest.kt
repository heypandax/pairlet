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
    fun only_an_https_ref_is_a_link() {
        assertTrue(isObservationLink("https://chatgpt.com/dots/x/activity/1"))
        assertFalse(isObservationLink("http://chatgpt.com/x"))
        assertFalse(isObservationLink("chatgpt://task/1"))
        assertFalse(isObservationLink("https://x.y z"))
        assertFalse(isObservationLink("Dot: refactor the parser"))
        assertFalse(isObservationLink(null))
    }
}
