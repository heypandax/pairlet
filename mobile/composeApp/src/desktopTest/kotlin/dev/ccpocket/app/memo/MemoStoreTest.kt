package dev.ccpocket.app.memo

import dev.ccpocket.protocol.VoiceMemoStage
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_NONE
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CODEX
import dev.ccpocket.protocol.VOICE_MEMO_AGENT_CLAUDE
import dev.ccpocket.protocol.VoiceMemoHash
import dev.ccpocket.protocol.VoiceMemoLimits
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memo store's cross-file rules: audio and metadata as two phases, compare-and-swap commits, tombstones that
 * outlive a crash, damaged or newer documents that are never overwritten, and a failed listing that is never an
 * empty library. The last tests run the real desktop [platformMemoFiles] in a temp directory.
 */
class MemoStoreTest {

    private val tempDir: File = Files.createTempDirectory("ccp-memo-store").toFile()

    @AfterTest
    fun tearDown() {
        System.clearProperty("ccpocket.voiceMemos.dir")
        tempDir.deleteRecursively()
    }

    private fun doc(memoId: String, createdAt: Long = 1_000, scope: MemoScope = SCOPE, revision: Long = 1, audio: AudioRef? = null) = MemoDocument(
        scope = scope, memoId = memoId, createdAtMs = createdAt, updatedAtMs = createdAt, revision = revision,
        processing = MemoAttempt(uuid(99), "audio", "0".repeat(64), 0, stage = MemoLocalStage.SAVED),
        content = MemoContent(audioDurationMs = 3_000, audio = audio),
    )

    @Test
    fun audioThenDocumentIsTwoPhasesAndAnOrphanIsNeverListed() = runTest {
        val files = InMemoryMemoFiles()
        val store = DefaultVoiceMemoStore(files)
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val ref = assertIs<MemoWrite.Durable<AudioRef>>(store.writeAudio(SCOPE, uuid(1), bytes, VoiceMemoLimits.AUDIO_MEDIA_TYPE)).value
        assertEquals(VoiceMemoHash.sha256Hex(bytes), ref.sha256)
        assertEquals(5000L, ref.byteLength)

        // Audio alone: an orphan — not listed, not counted.
        assertEquals(emptyList(), assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value)
        assertEquals(0, assertIs<MemoRead.Found<Int>>(store.count(SCOPE)).value)
        assertEquals(MemoRead.Missing, store.read(SCOPE, uuid(1)))

        assertIs<MemoWrite.Durable<Unit>>(store.commit(doc(uuid(1), audio = ref), null))
        assertEquals(listOf(uuid(1)), assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value.map { it.memoId })
        assertContentEquals(bytes, assertIs<MemoRead.Found<ByteArray>>(store.readAudio(SCOPE, uuid(1))).value)
        assertEquals(ref, assertIs<MemoRead.Found<MemoDocument>>(store.read(SCOPE, uuid(1))).value.content.audio)
    }

    @Test
    fun commitIsCompareAndSwapOnRevision() = runTest {
        val store = DefaultVoiceMemoStore(InMemoryMemoFiles())
        assertIs<MemoWrite.Durable<Unit>>(store.commit(doc(uuid(1)), null))
        assertEquals(MemoWrite.NotWritten(conflict = true), store.commit(doc(uuid(1)), null), "create over an existing memo")
        assertIs<MemoWrite.Durable<Unit>>(store.commit(doc(uuid(1), revision = 2), 1))
        assertEquals(MemoWrite.NotWritten(conflict = true), store.commit(doc(uuid(1), revision = 2), 1), "stale expected revision")
        assertEquals(MemoWrite.NotWritten(conflict = true), store.commit(doc(uuid(2), revision = 2), 1), "update of a missing memo")
        assertEquals(2, assertIs<MemoRead.Found<MemoDocument>>(store.read(SCOPE, uuid(1))).value.revision)
    }

    @Test
    fun aTombstoneRejectsCommitsHidesTheMemoAndSurvivesAnInterruptedPurge() = runTest {
        val files = InMemoryMemoFiles()
        val store = DefaultVoiceMemoStore(files)
        store.writeAudio(SCOPE, uuid(1), ByteArray(10), VoiceMemoLimits.AUDIO_MEDIA_TYPE)
        store.commit(doc(uuid(1)), null)

        assertIs<MemoWrite.Durable<Unit>>(store.markDeleted(SCOPE, uuid(1)))
        assertEquals(MemoWrite.NotWritten(conflict = true), store.commit(doc(uuid(1), revision = 2), 1), "a late write cannot resurrect it")
        assertEquals(MemoRead.Missing, store.read(SCOPE, uuid(1)))
        assertEquals(emptyList(), assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value)
        assertEquals(0, assertIs<MemoRead.Found<Int>>(store.count(SCOPE)).value)
        assertEquals(listOf(uuid(1)), store.pendingPurges(SCOPE))

        // The purge fails part-way: the tombstone stays for the next start.
        files.deleteResult = { _, name -> if (name == DefaultVoiceMemoStore.DOCUMENT) MemoWrite.NotWritten() else MemoWrite.Durable(Unit) }
        assertFalse(store.purgeDeleted(SCOPE, uuid(1)) is MemoWrite.Durable)
        assertEquals(listOf(uuid(1)), store.pendingPurges(SCOPE))
        assertEquals(MemoRead.Missing, store.read(SCOPE, uuid(1)))

        files.deleteResult = { _, _ -> MemoWrite.Durable(Unit) }
        assertIs<MemoWrite.Durable<Unit>>(store.purgeDeleted(SCOPE, uuid(1)))
        assertEquals(emptyList(), store.pendingPurges(SCOPE))
        assertTrue(files.files.isEmpty(), "every file of the memo is gone: ${files.files.keys}")
    }

    @Test
    fun aDamagedDocumentIsIsolatedAndNeverOverwritten() = runTest {
        val files = InMemoryMemoFiles()
        val store = DefaultVoiceMemoStore(files)
        store.commit(doc(uuid(1), createdAt = 1), null)
        store.commit(doc(uuid(2), createdAt = 2), null)
        val key = DefaultVoiceMemoStore.memoDir(SCOPE, uuid(1)) + "/" + DefaultVoiceMemoStore.DOCUMENT
        files.files[key] = "{ not json".encodeToByteArray()

        val rows = assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value
        assertEquals(listOf(uuid(2)), rows.map { it.memoId })
        assertEquals(2, assertIs<MemoRead.Found<Int>>(store.count(SCOPE)).value, "the damaged memo still holds a slot")
        assertIs<MemoRead.Unreadable>(store.read(SCOPE, uuid(1)))
        assertEquals(MemoWrite.NotWritten(conflict = true), store.commit(doc(uuid(1), revision = 2), 1))
        assertEquals("{ not json", files.files[key]!!.decodeToString())
    }

    @Test
    fun anUnknownSchemaVersionIsReportedAndKeptByteForByte() = runTest {
        val files = InMemoryMemoFiles()
        val store = DefaultVoiceMemoStore(files)
        store.commit(doc(uuid(1)), null)
        val key = DefaultVoiceMemoStore.memoDir(SCOPE, uuid(1)) + "/" + DefaultVoiceMemoStore.DOCUMENT
        val next = MEMO_SCHEMA_VERSION + 1
        val newer = files.files[key]!!.decodeToString()
            .replace("\"schemaVersion\":$MEMO_SCHEMA_VERSION", "\"schemaVersion\":$next")
        assertTrue(newer.contains("\"schemaVersion\":$next"))
        files.files[key] = newer.encodeToByteArray()

        assertEquals(MemoRead.UnsupportedVersion(next), store.read(SCOPE, uuid(1)))
        assertEquals(MemoWrite.NotWritten(conflict = true), store.commit(doc(uuid(1), revision = 2), 1))
        assertEquals(newer, files.files[key]!!.decodeToString())
        assertEquals(emptyList(), assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value)
    }

    @Test
    fun aListingThatCannotBeReadIsNotAnEmptyLibrary() = runTest {
        val files = InMemoryMemoFiles()
        val store = DefaultVoiceMemoStore(files)
        store.commit(doc(uuid(1)), null)
        files.failListDirs = true
        assertIs<MemoRead.Unreadable>(store.list(SCOPE, 0, 10))
        assertIs<MemoRead.Unreadable>(store.count(SCOPE))
        files.failListDirs = false
        files.failListFiles = true
        assertIs<MemoRead.Unreadable>(store.count(SCOPE))
        files.failListFiles = false
        files.failRead = { _, name -> name == DefaultVoiceMemoStore.DOCUMENT }
        assertIs<MemoRead.Unreadable>(store.read(SCOPE, uuid(1)))
    }

    @Test
    fun scopesAreIsolatedAndTheirTokensArePathSafe() = runTest {
        val store = DefaultVoiceMemoStore(InMemoryMemoFiles())
        store.commit(doc(uuid(1)), null)
        store.commit(doc(uuid(2), scope = OTHER_SCOPE), null)
        assertEquals(listOf(uuid(1)), assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value.map { it.memoId })
        assertEquals(listOf(uuid(2)), assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(OTHER_SCOPE, 0, 10)).value.map { it.memoId })
        assertEquals(MemoRead.Missing, store.read(OTHER_SCOPE, uuid(1)))
        val token = DefaultVoiceMemoStore.scopeToken(SCOPE)
        assertEquals(32, token.length)
        assertTrue(MemoPaths.isToken(token))
        assertNotEquals(token, DefaultVoiceMemoStore.scopeToken(OTHER_SCOPE))
        assertNotEquals(token, DefaultVoiceMemoStore.scopeToken(MemoScope("binding-a", "device-2")))
    }

    @Test
    fun listIsNewestFirstPagedAndCountsStatesFromTheDocument() = runTest {
        val store = DefaultVoiceMemoStore(InMemoryMemoFiles())
        (1..5).forEach { store.commit(doc(uuid(it), createdAt = it * 10L), null) }
        val page = assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 1, 2)).value
        assertEquals(listOf(uuid(4), uuid(3)), page.map { it.memoId })

        val record = { todo: String, state: String -> MemoDispatchRecord("b", todo, "p-$todo", "x", TARGET, "c", state, 1) }
        val withResult = seededDoc(uuid(9), "t1" to "a", "t2" to "b", "t3" to "c", "t4" to "d",
            dispatches = listOf(record("t1", MemoTodoState.DELIVERED), record("t2", MemoTodoState.UNKNOWN), record("t3", MemoTodoState.FAILED)))
        store.commit(withResult.copy(revision = 1), null)
        val header = assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value.first { it.memoId == uuid(9) }
        assertEquals(listOf(1, 1, 1, 1, 0), listOf(header.drafts, header.delivered, header.unknown, header.failed, header.sending))
        assertNull(header.stage, "a memo with content shows no processing stage")
        assertEquals(MemoLocalStage.SAVED, assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value.first { it.memoId == uuid(1) }.stage)
    }

    @Test
    fun pathsAcceptOnlyInternalTokens() {
        assertTrue(MemoPaths.isName("document.json"))
        assertTrue(MemoPaths.isDir("abc/" + uuid(1)))
        listOf("", ".", "..", "a/b", "/abs", "a\\b", "a b", "ü").forEach { assertFalse(MemoPaths.isName(it), it) }
        listOf("", "..", "a/../b", "/a", "a//b", "a/", "a\\b").forEach { assertFalse(MemoPaths.isDir(it), it) }
        assertTrue(MemoPaths.isDir("", allowRoot = true))
    }

    @Test
    fun theRealDesktopFilesRoundTripInATempDirectory() = runTest {
        System.setProperty("ccpocket.voiceMemos.dir", tempDir.absolutePath)
        val files = platformMemoFiles()
        val store = defaultVoiceMemoStore(files)
        val bytes = ByteArray(300_000) { (it * 7).toByte() }
        val ref = assertIs<MemoWrite.Durable<AudioRef>>(store.writeAudio(SCOPE, uuid(1), bytes, VoiceMemoLimits.AUDIO_MEDIA_TYPE)).value
        assertIs<MemoWrite.Durable<Unit>>(store.commit(doc(uuid(1), audio = ref), null))
        assertIs<MemoWrite.Durable<Unit>>(store.commit(doc(uuid(1), revision = 2, audio = ref).copy(content = MemoContent(title = "T", audio = ref)), 1))

        val dir = File(tempDir, DefaultVoiceMemoStore.memoDir(SCOPE, uuid(1)))
        assertEquals(setOf("audio.m4a", "document.json"), dir.list()!!.toSet(), "no temp files left behind")
        if (Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, "audio.m4a").toPath())))
        }
        assertEquals("T", assertIs<MemoRead.Found<MemoDocument>>(store.read(SCOPE, uuid(1))).value.content.title)
        assertContentEquals(bytes, assertIs<MemoRead.Found<ByteArray>>(store.readAudio(SCOPE, uuid(1))).value)
        assertEquals(1, assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value.size)

        assertIs<MemoWrite.Durable<Unit>>(store.deleteAudio(SCOPE, uuid(1)))
        assertIs<MemoWrite.Durable<Unit>>(store.deleteAudio(SCOPE, uuid(1)), "deleting a missing file is idempotent")
        assertEquals(MemoRead.Missing, store.readAudio(SCOPE, uuid(1)))

        assertIs<MemoWrite.Durable<Unit>>(store.markDeleted(SCOPE, uuid(1)))
        assertEquals(listOf(uuid(1)), store.pendingPurges(SCOPE))
        assertIs<MemoWrite.Durable<Unit>>(store.purgeDeleted(SCOPE, uuid(1)))
        assertFalse(dir.exists())
        assertEquals(emptyList(), store.pendingPurges(SCOPE))

        // Nothing a caller passes can leave the memo root.
        assertEquals(MemoWrite.NotWritten(), files.write("..", "x", ByteArray(1)))
        assertEquals(MemoWrite.NotWritten(), files.write("a", "../x", ByteArray(1)))
        assertIs<MemoRead.Unreadable>(files.read("a/..", "x"))
        assertNull(files.listDirs("../.."))
        assertFalse(File(tempDir.parentFile, "x").exists())
        assertEquals(emptyList(), files.listDirs("missing"))
    }

    @Test
    fun organiserFieldsRoundTripAndAnOlderDocumentStillReads() = runTest {
        val files = InMemoryMemoFiles()
        val store = DefaultVoiceMemoStore(files)
        val doc = doc(uuid(1)).copy(
            processing = MemoAttempt(uuid(2), "audio", "0".repeat(64), 0, stage = VoiceMemoStage.TRANSCRIBED, accepted = true, agent = VOICE_MEMO_AGENT_CODEX),
            content = MemoContent(transcript = "t", organizedBy = VOICE_MEMO_AGENT_CODEX),
            todos = listOf(MemoTodo("t1", "whole", wholeTranscript = true)),
        )
        assertIs<MemoWrite.Durable<Unit>>(store.commit(doc, null))
        assertEquals(doc, assertIs<MemoRead.Found<MemoDocument>>(store.read(SCOPE, uuid(1))).value)
        val json = files.files[DefaultVoiceMemoStore.memoDir(SCOPE, uuid(1)) + "/" + DefaultVoiceMemoStore.DOCUMENT]!!.decodeToString()
        assertTrue("\"agent\":\"codex\"" in json && "\"organizedBy\":\"codex\"" in json && "\"wholeTranscript\":true" in json, json)

        // A document written before these fields existed reads with their defaults, and can still be updated.
        val old = """{"schemaVersion":$MEMO_SCHEMA_VERSION,"scope":{"bindingId":"${SCOPE.bindingId}","deviceId":"${SCOPE.deviceId}"},""" +
            """"memoId":"${uuid(3)}","createdAtMs":1,"updatedAtMs":1,"revision":1,"editRevision":0,""" +
            """"processing":{"attemptId":"${uuid(4)}","inputKind":"audio","inputHash":"${"0".repeat(64)}","baseEditRevision":0,""" +
            """"remoteRevision":2,"stage":"ready","accepted":true},""" +
            """"content":{"title":"Old","summary":"S","transcript":"t"},"todos":[{"todoId":"t1","text":"a","selected":true}],""" +
            """"dispatches":[],"deleted":false}"""
        files.files[DefaultVoiceMemoStore.memoDir(SCOPE, uuid(3)) + "/" + DefaultVoiceMemoStore.DOCUMENT] = old.encodeToByteArray()
        val legacy = assertIs<MemoRead.Found<MemoDocument>>(store.read(SCOPE, uuid(3))).value
        assertEquals(VOICE_MEMO_AGENT_CLAUDE, legacy.processing!!.agent, "v1 organised with Claude only")
        assertNull(legacy.content.organizedBy)
        assertFalse(legacy.content.degraded)
        assertFalse(legacy.todos.single().wholeTranscript)
        assertIs<MemoWrite.Durable<Unit>>(store.commit(legacy.copy(revision = 2), 1))

        val degraded = doc(uuid(5)).copy(content = MemoContent(transcript = "raw", degraded = true))
        assertIs<MemoWrite.Durable<Unit>>(store.commit(degraded, null))
        assertEquals(degraded, assertIs<MemoRead.Found<MemoDocument>>(store.read(SCOPE, uuid(5))).value)
    }

    @Test
    fun anUntitledMemoIsListedByItsTranscriptsFirstSentence() = runTest {
        val store = DefaultVoiceMemoStore(InMemoryMemoFiles())
        val transcribed = MemoAttempt(uuid(9), "audio", "0".repeat(64), 0, stage = VoiceMemoStage.TRANSCRIBED, accepted = true, agent = VOICE_MEMO_AGENT_NONE)
        store.commit(doc(uuid(1), createdAt = 2).copy(processing = transcribed, content = MemoContent(transcript = "Plan the release。Then rest")), null)
        store.commit(doc(uuid(2), createdAt = 1).copy(processing = transcribed, content = MemoContent(title = "Mine", transcript = "Other words.")), null)
        store.commit(doc(uuid(3), createdAt = 0), null)
        val rows = assertIs<MemoRead.Found<List<MemoHeader>>>(store.list(SCOPE, 0, 10)).value
        assertEquals(listOf("Plan the release", "Mine", ""), rows.map { it.title })
        assertEquals(listOf(null, null, MemoLocalStage.SAVED), rows.map { it.stage }, "a transcribed memo has its result")
    }
}
