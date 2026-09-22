package dev.ccpocket.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

@Serializable private data class PreCompactLive(val convoId: String, val workdir: String, val contextUsed: Long? = null)
@Serializable private data class PreCompactHistory(val role: ChatRole, val text: String)

class CompactWireCompatTest {
    @Test fun oldPayloadsKeepSeedAndOrdinaryTextSemantics() {
        assertFalse(PocketJson.decodeFromString<SessionLive>("""{"convoId":"c","workdir":"/w"}""").contextUsedAuthoritative)
        assertFalse(PocketJson.decodeFromString<HistoryMessage>("""{"role":"user","text":"summary quote"}""").compactSummary)
    }

    @Test fun newFieldsRoundTripAndOldShapesRetainReadableFallbacks() {
        val live = SessionLive("c", "/w", contextUsed = null, contextUsedAuthoritative = true, compactSummary = "summary")
        val liveJson = PocketJson.encodeToString(live)
        assertEquals(live, PocketJson.decodeFromString<SessionLive>(liveJson))
        assertNull(PocketJson.decodeFromString<PreCompactLive>(liveJson).contextUsed)
        val history = HistoryMessage(ChatRole.USER, "summary", compactSummary = true)
        val historyJson = PocketJson.encodeToString(history)
        assertEquals(history, PocketJson.decodeFromString<HistoryMessage>(historyJson))
        assertEquals("summary", PocketJson.decodeFromString<PreCompactHistory>(historyJson).text)
        assertFalse("contextUsed\"" in liveJson, "null occupancy is omitted, authoritative flag carries clearing semantics")
        assertFalse("compactSummary" in PocketJson.encodeToString(SessionLive("c", "/w")))
        val frame: Frame = live
        assertEquals(frame, PocketJson.decodeFromString<Frame>(PocketJson.encodeToString(frame)))
    }
}
