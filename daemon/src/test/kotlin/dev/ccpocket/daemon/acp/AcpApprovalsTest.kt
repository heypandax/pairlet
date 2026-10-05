package dev.ccpocket.daemon.acp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approval bookkeeping and option choice, plus the namespaced synthetic frames. */
class AcpApprovalsTest {
    private val full = Json.parseToJsonElement(
        """[{"optionId":"a1","kind":"allow_once"},{"optionId":"aa","kind":"allow_always"},
           {"optionId":"r1","kind":"reject_once"},{"optionId":"ra","kind":"reject_always"}]""",
    ).jsonArray
    private val onceOnly = Json.parseToJsonElement(
        """[{"optionId":"allow-once","kind":"allow_once"},{"optionId":"reject-once","kind":"reject_once"}]""",
    ).jsonArray

    @Test
    fun `remember prefers the always option and falls back to once`() {
        assertEquals("aa", AcpApprovals.pickOption(full, allow = true, remember = true))
        assertEquals("a1", AcpApprovals.pickOption(full, allow = true, remember = false))
        assertEquals("r1", AcpApprovals.pickOption(full, allow = false, remember = true))
        assertEquals("allow-once", AcpApprovals.pickOption(onceOnly, allow = true, remember = true))
        assertNull(AcpApprovals.pickOption(JsonArray(emptyList()), allow = true, remember = false))
    }

    @Test
    fun `a request is answered once with the chosen option, or cancelled when none matches`() = runBlocking {
        val w = mutableListOf<String>()
        val approvals = AcpApprovals(AcpRpc { w += it })
        val params = Json.parseToJsonElement("""{"options":$onceOnly}""").jsonObject
        val ask = approvals.register(JsonPrimitive(7), params)
        assertEquals("7", ask)
        assertTrue(approvals.respond(ask, allow = false, remember = false))
        assertEquals(
            """{"jsonrpc":"2.0","id":7,"result":{"outcome":{"outcome":"selected","optionId":"reject-once"}}}""",
            w.single(),
        )
        assertTrue(!approvals.respond(ask, allow = true, remember = false), "a stale answer is never written twice")
        assertEquals(1, w.size)

        val bare = approvals.register(JsonPrimitive("x"), null)
        approvals.respond(bare, allow = true, remember = false)
        assertEquals("""{"jsonrpc":"2.0","id":"x","result":{"outcome":{"outcome":"cancelled"}}}""", w.last())
    }

    @Test
    fun `clear forgets the previous process's requests`() = runBlocking {
        val w = mutableListOf<String>()
        val approvals = AcpApprovals(AcpRpc { w += it })
        val ask = approvals.register(JsonPrimitive(1), null)
        approvals.clear()
        assertTrue(!approvals.respond(ask, allow = true, remember = false))
        assertTrue(w.isEmpty())
    }

    @Test
    fun `synthetic frames are namespaced per backend`() {
        val s = AcpSynthetic("kimi")
        assertEquals("cc-pocket/kimi-prompt-refused", s.refusalType)
        assertEquals("""{"type":"cc-pocket/kimi-prompt-refused","id":4,"message":"no"}""", s.refusal(4, "no"))
        assertEquals("""{"type":"cc-pocket/dsh-error","message":"boom"}""", AcpSynthetic("dsh").error("boom"))
        assertEquals("""{"type":"cc-pocket/dsh-notice","message":"fyi"}""", AcpSynthetic("dsh").notice("fyi"))
    }
}
