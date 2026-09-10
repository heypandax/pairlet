package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

@Serializable private data class BeforeDiagnosticOpen(val workdir: String, val resumeId: String? = null)
@Serializable private data class BeforeDiagnosticAttached(val role: Role, val accountId: String, val relayProtoV: Int = 0)

class DiagnosticWireCompatTest {
    private val context = DiagnosticContext("1234567890abcdef1234567890abcdef", 2, "1234567890abcdef")

    @Test fun omissionPreservesOldBusinessDefaultsAndCapabilitiesFailClosed() {
        val open = PocketJson.decodeFromString<Frame>("""{"t":"pocket/session.open","workdir":"/fixture"}""") as OpenSession
        assertNull(open.diagnostic)
        assertEquals(AgentKind.CLAUDE, open.agent)
        assertFalse((PocketJson.decodeFromString<Frame>("""{"t":"pocket/client.caps"}""") as ClientCaps).supportsDiagnostics)
        assertFalse((PocketJson.decodeFromString<Frame>("""{"t":"pocket/daemon.info"}""") as DaemonInfo).supportsDiagnostics)
        assertNull(PocketJson.decodeFromString<Attached>("""{"role":"daemon","accountId":"account"}""").connectionId)
    }

    @Test fun oldPeersIgnoreOptionalOperationAndConnectionFields() {
        val open = OpenSession("/fixture", resumeId = "session", diagnostic = context)
        val old = PocketJson.decodeFromString<BeforeDiagnosticOpen>(PocketJson.encodeToString<Frame>(open))
        assertEquals("/fixture", old.workdir)
        assertEquals("session", old.resumeId)
        val attached = Attached(Role.DAEMON, "account", connectionId = DiagnosticId(context.traceId))
        val oldAttached = PocketJson.decodeFromString<BeforeDiagnosticAttached>(PocketJson.encodeToString<Frame>(attached))
        assertEquals("account", oldAttached.accountId)
        assertEquals(Role.DAEMON, oldAttached.role)
    }

    @Test fun malformedMetadataCannotDiscardALegitimateOpen() {
        val bad = listOf("42", "[]", "true", "\"private text\"", "{}",
            """{"traceId":"${context.traceId}","attempt":"2"}""",
            """{"traceId":"${context.traceId}","attempt":1001}""",
            """{"traceId":"${context.traceId}","spanId":{}}""",
            """{"traceId":"${"0".repeat(32)}"}""")
        for (metadata in bad) {
            val frame = PocketJson.decodeFromString<Frame>("""{"t":"pocket/session.open","workdir":"/fixture","diagnostic":$metadata}""") as OpenSession
            assertEquals("/fixture", frame.workdir)
            assertNull(frame.diagnostic?.validated(), metadata)
        }
    }

    @Test fun malformedSocketIdsDoNotBreakAttachOrPresence() {
        for (id in listOf("[]", "42", "{}", "\"bad\"", "\"${"0".repeat(32)}\"")) {
            val frame = PocketJson.decodeFromString<Attached>("""{"role":"daemon","accountId":"account","connectionId":$id}""")
            assertEquals("account", frame.accountId)
            assertNull(frame.connectionId?.validated())
            assertTrue(PocketJson.decodeFromString<PeerPresence>("""{"online":true,"connectionId":$id}""").online)
        }
    }

    @Test fun negotiatedMilestonesRoundTripWithoutPromptOrFileContents() {
        val frames = listOf<Frame>(HistoryComplete("c", context, 0, replaySent = false),
            HistoryApplied("c", context), PromptProgress("c", context, "queued"),
            PromptProgress("c", context, "complete", "success", true),
            ApprovalProgress("c", context, "adapter_returned"))
        for (frame in frames) assertEquals(frame, PocketJson.decodeFromString<Frame>(PocketJson.encodeToString<Frame>(frame)))
    }
}
