package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.agent.AgentBackend
import dev.ccpocket.daemon.agent.AgentBackendFactory
import dev.ccpocket.daemon.agent.AgentIo
import dev.ccpocket.daemon.agent.AgentSpec
import dev.ccpocket.protocol.AgentKind
import dev.ccpocket.protocol.HistoryMessage
import dev.ccpocket.protocol.ImageData
import dev.ccpocket.protocol.PermissionMode
import dev.ccpocket.protocol.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #360: [SessionRegistry.scanSessions] reports a scan's completeness per backend, while the legacy
 * [SessionRegistry.listSessions] keeps turning a failed backend into "no rows" exactly as before.
 */
class SessionRegistryScanSessionsTest {

    private open class StubBackend(
        override val kind: AgentKind,
        private val rows: () -> List<SessionSummary>,
    ) : AgentBackend {
        override fun listSessions(workdir: String) = rows()
        override fun processBuilder(spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun attach(io: AgentIo, spec: AgentSpec) = throw UnsupportedOperationException()
        override suspend fun parse(line: String): Nothing = throw UnsupportedOperationException()
        override suspend fun sendPrompt(text: String, images: List<ImageData>) = throw UnsupportedOperationException()
        override suspend fun interrupt() = throw UnsupportedOperationException()
        override suspend fun respondPermission(
            askId: String, allow: Boolean, remember: Boolean,
            originalInput: JsonObject?, updatedInput: String?, denyMessage: String?,
        ) = throw UnsupportedOperationException()
        override fun applySettings(mode: PermissionMode?, model: String?, effort: String?) = false
        override suspend fun onProcessEnded(sessionId: String?) {}
        override fun transcriptDir(workdir: String): Path = throw UnsupportedOperationException()
        override fun replayHistory(workdir: String, sessionId: String) = emptyList<HistoryMessage>()
        override fun resumeContextTokens(workdir: String, sessionId: String): Long? = null
    }

    private val workdir: String = Files.createTempDirectory("ccp-scan-reg").toString()

    private fun row(id: String, agent: AgentKind?) = SessionSummary(id, id, "", 1, workdir, 1, agent = agent)

    private fun registry(vararg backends: Pair<AgentKind, AgentBackend>) =
        SessionRegistry(CoroutineScope(Dispatchers.Default), backends = backends.associate { (k, b) -> k to AgentBackendFactory { b } })

    @Test
    fun a_throwing_backend_is_a_failed_scan_but_still_an_empty_legacy_list() {
        val reg = registry(AgentKind.CODEX to StubBackend(AgentKind.CODEX) { throw java.io.IOException("boom") })
        val scan = reg.scanSessions(workdir, AgentKind.CODEX)
        assertEquals(ScanCompleteness.ERROR, scan.completeness)
        assertTrue(scan.items.isEmpty())
        assertTrue(reg.listSessions(workdir).isEmpty(), "legacy list behaviour is unchanged")
    }

    @Test
    fun a_backend_that_cannot_prove_completeness_is_unverified_and_an_unregistered_one_is_an_error() {
        val reg = registry(AgentKind.KIMI to StubBackend(AgentKind.KIMI) { listOf(row("k1", AgentKind.KIMI)) })
        val kimi = reg.scanSessions(workdir, AgentKind.KIMI)
        assertEquals(ScanCompleteness.UNVERIFIED, kimi.completeness)
        assertEquals(listOf("k1"), kimi.items.map { it.sessionId })
        assertEquals(ScanCompleteness.ERROR, reg.scanSessions(workdir, AgentKind.CLAUDE).completeness)
    }

    @Test
    fun a_complete_backend_scan_passes_through_and_foreign_rows_downgrade_it() {
        val complete = object : StubBackend(AgentKind.CLAUDE, { emptyList() }) {
            override fun scanSessions(workdir: String, agent: AgentKind) =
                SessionScan(agent, workdir, listOf(row("c1", AgentKind.CLAUDE)), ScanCompleteness.COMPLETE)
        }
        val mixed = object : StubBackend(AgentKind.CODEX, { emptyList() }) {
            override fun scanSessions(workdir: String, agent: AgentKind) =
                SessionScan(agent, workdir, listOf(row("x", AgentKind.CLAUDE)), ScanCompleteness.COMPLETE)
        }
        val reg = registry(AgentKind.CLAUDE to complete, AgentKind.CODEX to mixed)
        val claude = reg.scanSessions(workdir, AgentKind.CLAUDE)
        assertEquals(ScanCompleteness.COMPLETE, claude.completeness)
        assertEquals(listOf("c1"), claude.items.map { it.sessionId })
        assertEquals(ScanCompleteness.ERROR, reg.scanSessions(workdir, AgentKind.CODEX).completeness, "never re-attribute rows")
    }
}
