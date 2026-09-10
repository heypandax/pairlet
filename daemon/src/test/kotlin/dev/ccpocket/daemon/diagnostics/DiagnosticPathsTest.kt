package dev.ccpocket.daemon.diagnostics

import dev.ccpocket.daemon.disk.TranscriptReplay
import dev.ccpocket.observability.*
import dev.ccpocket.protocol.Envelope
import dev.ccpocket.protocol.PocketJson
import java.nio.file.Files
import kotlin.test.*

class DiagnosticPathsTest {
    private val records = mutableListOf<DiagnosticRecord>()
    @BeforeTest fun install() = Diagnostics.install(DiagnosticReporter(Component.DAEMON, Environment.STAGING,
        "test", DiagnosticSink { records.add(it) }))
    @AfterTest fun close() = Diagnostics.install(null)

    @Test fun unknownPeerVariantsAreLogsButMalformedKnownFramesAreErrors() {
        val future = """{"id":"9","ts":0,"to":"PEER","body":{"t":"pocket/from.the.future","x":1}}"""
        val unknown = assertFails { PocketJson.decodeFromString<Envelope>(future) }
        Diagnostics.protocolDecodeFailed(unknown, future.length.toLong())
        val malformed = assertFails { PocketJson.decodeFromString<Envelope>("{BROKEN_SECRET") }
        Diagnostics.protocolDecodeFailed(malformed, 14)
        assertEquals(ErrorCode.UNSUPPORTED, records[0].code, unknown.message)
        assertEquals(DiagnosticKind.LOG, records[0].kind)
        assertNull(records[0].exception)
        assertEquals(ErrorCode.DECODE_FAILED, records[1].code)
        assertEquals(DiagnosticKind.ERROR, records[1].kind)
    }

    @Test fun incompleteTailIsExpectedButEarlierCorruptionExplainsPartialHistory() {
        val file = Files.createTempFile("cc-pocket-observability", ".jsonl")
        val row = """{"type":"user","message":{"content":"PROMPT_SENTINEL"}}"""
        try {
            Files.writeString(file, row + "\n{WRITING")
            assertEquals(1, TranscriptReplay.read(file).size)
            assertEquals(ErrorCode.INCOMPLETE, records.single().code)
            assertEquals(DiagnosticKind.LOG, records.single().kind)
            records.clear()
            Files.writeString(file, "{CORRUPT\n" + row + "\n")
            assertEquals(1, TranscriptReplay.read(file).size)
            assertEquals(ErrorCode.DECODE_FAILED, records.single().code)
            assertEquals(ResultQuality.PARTIAL, records.single().metrics.resultQuality)
            assertEquals(1L, records.single().metrics.failedCount)
            records.clear()
            Files.writeString(file, row + "\n")
            assertEquals(1, TranscriptReplay.read(file).size)
            assertTrue(records.isEmpty())
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun preferenceIsExplicitAndSurvivesReloadWithoutIdentityFiles() {
        val dir = Files.createTempDirectory("cc-pocket-diagnostic-pref")
        val file = dir.resolve("diagnostics.properties")
        try {
            assertFalse(DaemonDiagnostics.enabled(file))
            DaemonDiagnostics.setEnabled(true, file)
            assertTrue(DaemonDiagnostics.enabled(file))
            DaemonDiagnostics.setEnabled(false, file)
            assertFalse(DaemonDiagnostics.enabled(file))
            Files.writeString(file, "enabled=not-a-boolean\n")
            assertFalse(DaemonDiagnostics.enabled(file))
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir) }
    }
}
