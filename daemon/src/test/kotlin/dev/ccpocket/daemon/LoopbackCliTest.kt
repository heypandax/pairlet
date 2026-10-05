package dev.ccpocket.daemon

import dev.ccpocket.daemon.relay.LegacyLoopbackGuard
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The CLI's reading of the loopback gate's refusals (pairing security phase 0) — what the user is shown. */
class LoopbackCliTest {

    private fun refusalOf(status: HttpStatusCode, code: String, message: String) =
        LoopbackCli.refusal(LoopbackCli.Reply(status, """{"error":"$code","message":"$message"}"""))

    @Test
    fun gate_refusals_become_sentences() {
        val wrong = LegacyLoopbackGuard.tokenRejection("a", "b")!!
        val shown = refusalOf(wrong.status, wrong.code, wrong.message)!!
        assertTrue(shown.startsWith("the running daemon rejected this CLI's local control token"), shown)
        assertTrue("same OS user" in shown, shown)

        val off = LegacyLoopbackGuard.tokenRejection(null, "b")!!
        assertTrue("daemon log" in refusalOf(off.status, off.code, off.message)!!)
        assertTrue("forbidden_origin" in refusalOf(HttpStatusCode.Forbidden, "forbidden_origin", "no")!!)
    }

    @Test
    fun everything_else_is_left_to_the_command() {
        // the relay link being down is a 503 too, but it is the pair command's retry case, not a refusal
        assertNull(refusalOf(HttpStatusCode.ServiceUnavailable, "relay_offline", "x"))
        assertNull(LoopbackCli.refusal(LoopbackCli.Reply(HttpStatusCode.OK, """{"ticket":"t"}""")))
        assertNull(LoopbackCli.refusal(LoopbackCli.Reply(HttpStatusCode.Unauthorized, "not json")))
        assertNull(refusalOf(HttpStatusCode.Conflict, "headless_pairing_pending", "x"))
    }

    @Test
    fun the_token_comes_from_the_daemons_file() {
        val dir = Files.createTempDirectory("ccp-loopback-cli").toFile()
        val path = dir.resolve("local-control-token")
        assertNull(LoopbackCli.token(path), "no daemon has run here yet")
        assertTrue(LoopbackCli.missingTokenMessage(path, "start it").contains(path.path))
        path.writeText("tok-123\n")
        assertEquals("tok-123", LoopbackCli.token(path))
    }
}
