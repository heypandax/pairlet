package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pre-#360 `pocket/client.caps` body — an already-shipped daemon skips the new flag. */
@Serializable
private data class PreManagedClientCaps(
    val supportsAgents: List<String> = emptyList(),
    val supportsDiagnostics: Boolean = false,
    val supportsProjectPins: Boolean = false,
)

/** A pre-#360 `pocket/daemon.info` reader — an already-shipped client skips the new fields. */
@Serializable
private data class PreManagedDaemonInfo(
    val hostname: String? = null,
    val supportedAgents: List<String> = emptyList(),
    val supportsProjectPins: Boolean = false,
)

/** The pre-#360 `pocket/sessions` reader shape an old client decodes with. */
@Serializable
private data class PreManagedSessions(
    val workdir: String,
    val items: List<SessionSummary>,
    val groups: List<SessionGroup>? = null,
    val renameSupported: Boolean = false,
    val archiveSupported: Boolean = false,
)

/**
 * Wire compatibility for the managed session list (issue #360): every new frame round-trips under its discriminator,
 * absent fields read as the fail-safe meaning (a missing/unknown agent is NEVER read as Claude), both capability
 * fields are trailing optionals in BOTH directions, legacy list frames keep their exact shape, unknown discriminators
 * take the drop path, and unknown keys anywhere inside a row are skipped. The encoded frame BYTE budget is enforced by
 * the daemon's packer and pinned by its adversarial tests (ManagedSessionServiceTest).
 */
class ManagedSessionsWireCompatTest {

    private fun roundTrip(frame: Frame): Frame =
        PocketJson.decodeFromString<Envelope>(PocketJson.encodeToString(Envelope("1", 0, body = frame))).body

    private fun bodyJson(frame: Frame): String =
        PocketJson.parseToJsonElement(PocketJson.encodeToString(Envelope("1", 0, body = frame))).jsonObject
            .getValue("body").toString()

    private fun body(json: String): Frame = PocketJson.decodeFromString<Envelope>("""{"id":"1","ts":0,"body":$json}""").body

    private fun summary(id: String) = SessionSummary(id, "t-$id", "p", 2, "", 1_000, agent = AgentKind.CODEX, group = "g1")

    @Test
    fun every_request_roundtrips_under_its_discriminator() {
        val frames = listOf(
            ListManagedSessions("r0", "/Users/me/app", allAgents = true) to "pocket/managed.list",
            ListManagedSessions("r1", "/Users/me/app", AgentKind.CODEX, cursor = "m1.40.3.0123456789abcdef") to "pocket/managed.list",
            EnableManagedSessions("r2", "~/app", AgentKind.CLAUDE) to "pocket/managed.enable",
            DiscoverSessions("r3", "/Users/me/app", AgentKind.CODEX, query = "fix", cursor = "c1", limit = 20) to "pocket/managed.discover",
            ImportSession("r4", "/Users/me/app", AgentKind.CLAUDE, "0b9a-uuid") to "pocket/managed.import",
            RemoveManagedSession("r5", "/Users/me/app", AgentKind.CODEX, "019a-uuid") to "pocket/managed.remove",
        )
        for ((frame, name) in frames) {
            assertEquals(frame, roundTrip(frame))
            assertTrue("\"t\":\"$name\"" in bodyJson(frame), bodyJson(frame))
        }
    }

    @Test
    fun a_missing_or_unknown_request_agent_decodes_to_null_never_to_claude() {
        for (agentJson in listOf("", ""","agent":"future-agent"""")) {
            val enable = body("""{"t":"pocket/managed.enable","requestId":"r","workdir":"/w"$agentJson}""") as EnableManagedSessions
            assertNull(enable.agent, "enable <$agentJson>")
            assertNull((body("""{"t":"pocket/managed.import","requestId":"r","workdir":"/w","sessionId":"s"$agentJson}""") as ImportSession).agent)
            assertNull((body("""{"t":"pocket/managed.remove","requestId":"r","workdir":"/w","sessionId":"s"$agentJson}""") as RemoveManagedSession).agent)
            assertNull((body("""{"t":"pocket/managed.discover","requestId":"r","workdir":"/w"$agentJson}""") as DiscoverSessions).agent)
            val list = body("""{"t":"pocket/managed.list","requestId":"r","workdir":"/w"$agentJson}""") as ListManagedSessions
            assertNull(list.agent)
            assertFalse(list.allAgents, "an unknown agent must not widen into an all-agents list")
        }
        val discover = body("""{"t":"pocket/managed.discover","requestId":"r","workdir":"/w","agent":"codex"}""") as DiscoverSessions
        assertEquals("", discover.query)
        assertNull(discover.cursor)
        assertEquals(MANAGED_DISCOVER_PAGE_DEFAULT, discover.limit)
    }

    @Test
    fun replies_pages_and_pushes_roundtrip_and_a_refusal_never_synthesizes_an_empty_list() {
        val imported = ManagedSessionEntry("a", AgentKind.CODEX, ManagedSessionOrigin.EXPLICIT_IMPORT, 10, ManagedAvailability.AVAILABLE, summary("a"), group = "g1")
        val page = ManagedSessionsState(
            requestId = "r4", workdir = "~/app", agent = AgentKind.CODEX, canonicalWorkdir = "/Users/me/app", revision = 4,
            agents = listOf(
                ManagedAgentStatus(AgentKind.CLAUDE, ManagedMigrationState.READY, scanComplete = true, diagnostic = ManagedScanDiagnostics.COMPLETE),
                ManagedAgentStatus(AgentKind.CODEX, ManagedMigrationState.UNINITIALIZED, scanComplete = false, diagnostic = ManagedScanDiagnostics.TRUNCATED),
            ),
            items = listOf(
                imported,
                ManagedSessionEntry("b", AgentKind.CLAUDE, ManagedSessionOrigin.LEGACY_ADOPTED, 11, ManagedAvailability.MISSING,
                    lastKnownTitle = "old", lastKnownModified = 5, groupAmbiguous = true),
            ),
            nextCursor = "m1.2.4.0123456789abcdef", complete = false,
            groups = listOf(SessionGroup("g1", "G", 0)), sessionId = "a", changed = true, entry = imported,
        )
        val push = ManagedSessionsState(workdir = "/Users/me/app", allAgents = true, canonicalWorkdir = "/Users/me/app", revision = 5,
            agents = emptyList(), items = emptyList(), complete = true)
        val discovered = DiscoveredSessions(
            "r3", "/Users/me/app", AgentKind.CLAUDE,
            items = listOf(DiscoveredSession("x", AgentKind.CLAUDE, "title", "prompt", 9, 3, live = true, alreadyManaged = true)),
            nextCursor = "c2", complete = false, diagnostic = ManagedScanDiagnostics.PARTIAL,
        )
        listOf(page, push, discovered).forEach { assertEquals(it, roundTrip(it)) }
        assertTrue("\"t\":\"pocket/managed.state\"" in bodyJson(page))
        assertTrue("\"t\":\"pocket/managed.discovered\"" in bodyJson(discovered))
        val pushJson = bodyJson(push)
        listOf("\"requestId\"", "\"agent\"", "\"error\"", "\"entry\"", "\"sessionId\"", "\"nextCursor\"").forEach {
            assertFalse(it in pushJson, "a push must not carry $it: $pushJson")
        }

        val refusal = body("""{"t":"pocket/managed.state","requestId":"r","workdir":"/w","error":"managed_store_corrupt","readOnly":true}""") as ManagedSessionsState
        assertNull(refusal.items, "an unreadable store must stay null, never an empty list")
        assertNull(refusal.agents)
        assertFalse(refusal.complete, "an absent complete flag never claims the whole list was delivered")
        assertEquals(ManagedSessionErrors.STORE_CORRUPT, refusal.error)
        assertTrue(refusal.readOnly)
        val forbidden = bodyJson(ManagedSessionsState("r", "/w", error = ManagedSessionErrors.FORBIDDEN))
        listOf("items", "agents", "canonicalWorkdir", "revision", "nextCursor").forEach { assertFalse("\"$it\"" in forbidden, forbidden) }

        val emptyPage = body("""{"t":"pocket/managed.discovered","requestId":"r","workdir":"/w"}""") as DiscoveredSessions
        assertFalse(emptyPage.complete, "an absent complete flag must never read as 'no sessions exist'")
    }

    @Test
    fun reply_rows_with_a_missing_or_unknown_agent_decode_to_null_for_the_client_to_drop() {
        val state = body(
            """{"t":"pocket/managed.state","requestId":"r","workdir":"/w","agent":"future-agent",""" +
                """"agents":[{"agent":"future-agent","migration":"ready"},{"migration":"ready"},{"agent":"claude","migration":"ready"}],""" +
                """"items":[{"sessionId":"a","agent":"future-agent"},{"sessionId":"b"},{"sessionId":"c","agent":"codex"}],""" +
                """"entry":{"sessionId":"a","agent":"future-agent"}}""",
        ) as ManagedSessionsState
        assertNull(state.agent)
        assertEquals(listOf(null, null, AgentKind.CLAUDE), state.agents!!.map { it.agent })
        assertEquals(listOf(null, null, AgentKind.CODEX), state.items!!.map { it.agent })
        assertNull(state.entry!!.agent)
        val page = body(
            """{"t":"pocket/managed.discovered","requestId":"r","workdir":"/w","agent":"future-agent",""" +
                """"items":[{"sessionId":"a","agent":"future-agent"},{"sessionId":"b"},{"sessionId":"c","agent":"claude"}]}""",
        ) as DiscoveredSessions
        assertNull(page.agent)
        assertEquals(listOf(null, null, AgentKind.CLAUDE), page.items.map { it.agent })
        assertEquals(ManagedAgentStatus(), PocketJson.decodeFromString<ManagedAgentStatus>("{}"))
        assertEquals(ManagedMigrationState.UNINITIALIZED, ManagedAgentStatus().migration)
    }

    @Test
    fun unknown_enum_values_and_keys_anywhere_in_a_row_degrade_instead_of_failing() {
        val json = """{"t":"pocket/managed.state","requestId":"r","workdir":"/w","future":{"a":1},""" +
            """"agents":[{"agent":"claude","migration":"frozen","later":1}],""" +
            """"items":[{"sessionId":"s","agent":"claude","origin":"teleported","availability":"quantum","later":true,""" +
            """"summary":{"sessionId":"s","title":"t","firstPrompt":"","messageCount":1,"cwd":"","lastModified":1,""" +
            """"futureArray":[1,{"x":[2]}],"futureObject":{"nested":{"deep":true}},"agent":"future-agent"}}]}"""
        val state = body(json) as ManagedSessionsState
        assertEquals(ManagedMigrationState.UNINITIALIZED, state.agents!!.single().migration)
        val row = state.items!!.single()
        assertEquals(ManagedSessionOrigin.LEGACY_ADOPTED, row.origin)
        assertEquals(ManagedAvailability.UNKNOWN, row.availability)
        assertEquals("t", row.summary!!.title)
        assertNull(row.summary!!.agent)

        val entry = PocketJson.decodeFromString<ManagedSessionEntry>("""{"sessionId":"s"}""")
        assertEquals(ManagedAvailability.UNKNOWN, entry.availability, "absent availability must not claim the record exists")
        assertNull(entry.summary)
        assertNull(entry.agent)
    }

    @Test
    fun clientCaps_supportsManagedSessions_is_a_trailing_optional_both_ways() {
        val legacy = body("""{"t":"pocket/client.caps","supportsAgents":["kimi"],"supportsProjectPins":true}""") as ClientCaps
        assertFalse(legacy.supportsManagedSessions)
        assertTrue(legacy.supportsProjectPins)
        val modern = ClientCaps(supportsAgents = listOf("kimi"), supportsProjectPins = true, supportsManagedSessions = true)
        assertEquals(modern, roundTrip(modern))
        val old = PocketJson.decodeFromString<PreManagedClientCaps>(bodyJson(modern))
        assertEquals(listOf("kimi"), old.supportsAgents)
        assertTrue(old.supportsProjectPins)
    }

    @Test
    fun daemonInfo_managed_fields_are_trailing_optionals_both_ways() {
        val legacy = PocketJson.decodeFromString<DaemonInfo>("""{"daemonVersion":"2.0.0","supportsProjectPins":true}""")
        assertFalse(legacy.supportsManagedSessions)
        assertTrue(legacy.managedAgents.isEmpty())
        val modern = DaemonInfo(hostname = "mac", supportedAgents = listOf("claude", "codex"), supportsProjectPins = true,
            supportsManagedSessions = true, managedAgents = listOf("claude", "codex"))
        assertEquals(modern, roundTrip(modern))
        val old = PocketJson.decodeFromString<PreManagedDaemonInfo>(bodyJson(modern))
        assertEquals("mac", old.hostname)
        assertTrue(old.supportsProjectPins)
    }

    @Test
    fun legacy_session_list_frames_keep_their_exact_shape() {
        assertEquals("""{"t":"pocket/sessions.list","workdir":"/Users/me/app"}""", bodyJson(ListSessions("/Users/me/app")))
        val sessions = Sessions("/Users/me/app", listOf(summary("a").copy(cwd = "/Users/me/app")), groups = listOf(SessionGroup("g1", "G", 0)), renameSupported = true)
        val json = bodyJson(sessions)
        listOf("managed", "migration", "availability", "requestId", "nextCursor").forEach { assertFalse(it in json, "legacy Sessions must not grow $it: $json") }
        val old = PocketJson.decodeFromString<PreManagedSessions>(json)
        assertEquals(sessions.items, old.items)
        assertEquals(sessions.groups, old.groups)
    }

    @Test
    fun unknown_managed_frames_fail_the_envelope_decode_the_drop_path() {
        assertTrue(runCatching { body("""{"t":"pocket/managed.reorder","requestId":"r"}""") }.isFailure)
    }

    @Test
    fun the_refusal_and_diagnostic_spellings_are_wire_contract() {
        assertEquals(
            listOf("managed_forbidden", "managed_unsupported", "managed_invalid_request", "managed_invalid_workdir", "managed_not_found",
                "managed_scan_incomplete", "managed_capacity", "managed_store_corrupt", "managed_store_unavailable", "managed_cursor_invalid"),
            listOf(ManagedSessionErrors.FORBIDDEN, ManagedSessionErrors.UNSUPPORTED, ManagedSessionErrors.INVALID_REQUEST,
                ManagedSessionErrors.INVALID_WORKDIR, ManagedSessionErrors.NOT_FOUND, ManagedSessionErrors.SCAN_INCOMPLETE,
                ManagedSessionErrors.CAPACITY, ManagedSessionErrors.STORE_CORRUPT, ManagedSessionErrors.STORE_UNAVAILABLE,
                ManagedSessionErrors.CURSOR_INVALID),
        )
        assertEquals(
            listOf("complete", "partial", "truncated", "permission_denied", "error", "page_limit"),
            listOf(ManagedScanDiagnostics.COMPLETE, ManagedScanDiagnostics.PARTIAL, ManagedScanDiagnostics.TRUNCATED,
                ManagedScanDiagnostics.PERMISSION_DENIED, ManagedScanDiagnostics.ERROR, ManagedScanDiagnostics.PAGE_LIMIT),
        )
    }

    @Test
    fun validators_bound_ids_and_workdirs() {
        assertTrue(isValidManagedId("0b9a1c2d-1111-2222-3333-444455556666"))
        assertTrue(isValidManagedId("rollout:019a.x_y"))
        assertFalse(isValidManagedId(""))
        assertFalse(isValidManagedId(".."))
        assertFalse(isValidManagedId("a/b"))
        assertFalse(isValidManagedId("a\\b"))
        assertFalse(isValidManagedId("x".repeat(MANAGED_ID_MAX_CHARS + 1)))
        assertTrue(isValidManagedWorkdir("~/中文 项目"))
        assertFalse(isValidManagedWorkdir(" "))
        assertFalse(isValidManagedWorkdir("/a\nb"))
        assertFalse(isValidManagedWorkdir("/a" + 0x7F.toChar() + "b"), "DEL is refused")
        assertFalse(isValidManagedWorkdir("/" + "x".repeat(MANAGED_WORKDIR_MAX_CHARS)))
    }

    @Test
    fun utf8_truncation_counts_bytes_and_never_splits_a_code_point() {
        assertEquals("abc", truncateUtf8("abc", 3))
        assertEquals("ab", truncateUtf8("abc", 2))
        assertEquals("中", truncateUtf8("中文", 5), "a 3-byte char that does not fit whole is dropped")
        assertEquals("中文", truncateUtf8("中文", 6))
        val emoji = "😀😀" // 4 UTF-8 bytes each, a surrogate pair in UTF-16
        assertEquals("😀", truncateUtf8(emoji, 7))
        assertEquals("", truncateUtf8(emoji, 3))
        for (max in 0..40) {
            val cut = truncateUtf8("a中😀é" .repeat(5), max)
            assertTrue(cut.encodeToByteArray().size <= max, "cut to $max bytes: ${cut.encodeToByteArray().size}")
            assertFalse(cut.isNotEmpty() && cut.last().isHighSurrogate(), "no dangling surrogate")
        }
    }
}
