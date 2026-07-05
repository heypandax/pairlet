package dev.ccpocket.daemon.claude

import dev.ccpocket.protocol.AuthState
import dev.ccpocket.protocol.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AuthService: `auth status --json` output parsing + the live-conversation switch guard. */
class AuthServiceTest {

    private fun service(
        busy: Int = 0,
        closeIdle: () -> Int = { 0 },
        exe: () -> String = { error("no CLI spawn expected in this test") },
    ) = AuthService(
        CoroutineScope(Dispatchers.Default),
        busyConversations = { busy },
        closeIdleConversations = { closeIdle() },
        claudeExe = exe,
    )

    // the real shape observed from claude 2.1.x `auth status --json`
    private val statusJson = """
        {
          "loggedIn": true,
          "authMethod": "claude.ai",
          "apiProvider": "firstParty",
          "email": "a@b.c",
          "orgId": "516dc776-2924-4848-a84c-b2c6ee824e80",
          "orgName": "A's Organization",
          "subscriptionType": "max"
        }
    """.trimIndent()

    @Test
    fun parses_clean_status_output() {
        val s = service().parseStatus(statusJson)
        assertTrue(s.loggedIn)
        assertEquals("a@b.c", s.email)
        assertEquals("A's Organization", s.orgName)
        assertEquals("max", s.subscriptionType)
        assertEquals("claude.ai", s.authMethod)
        assertNull(s.error)
    }

    @Test
    fun tolerates_wrapper_preamble_around_the_json() {
        // shell wrappers (observed locally: an ip-guard preflight) prepend banner lines to the CLI's output
        val wrapped = "[ip-guard] Preflight passed.\n  IP address : 1.2.3.4\n\n$statusJson\n"
        val s = service().parseStatus(wrapped)
        assertTrue(s.loggedIn)
        assertEquals("a@b.c", s.email)
    }

    @Test
    fun logged_out_and_garbage_outputs() {
        val out = service().parseStatus("""{"loggedIn": false}""")
        assertFalse(out.loggedIn)
        assertNull(out.email)
        assertNull(out.error)

        val garbage = service().parseStatus("command not found")
        assertFalse(garbage.loggedIn)
        assertNotNull(garbage.error)
    }

    @Test
    fun login_and_logout_are_refused_while_conversations_are_mid_task() = runBlocking {
        val emitted = mutableListOf<Frame>()
        var closed = 0
        val auth = service(busy = 2, closeIdle = { closed++; 0 })
        auth.login(console = false, emit = { emitted.add(it) })
        auth.logout(emit = { emitted.add(it) })
        assertEquals(2, emitted.size)
        assertEquals(0, closed) // a refused switch must not reap anything
        emitted.forEach { f ->
            val s = f as AuthState
            assertFalse(s.loginPending)
            assertTrue(s.error!!.contains("2 sessions are mid-task"), s.error)
        }
    }

    @Test
    fun idle_conversations_are_closed_when_nothing_is_mid_task() = runBlocking {
        // exe resolves but points nowhere runnable: the guard passes, idle close runs, then login
        // fails at spawn — which is all this test needs to observe
        val emitted = mutableListOf<Frame>()
        var closed = 0
        val auth = service(busy = 0, closeIdle = { closed++; 3 }, exe = { "/nonexistent/claude" })
        auth.login(console = false, emit = { emitted.add(it) })
        assertEquals(1, closed)
        val s = emitted.last() as AuthState
        assertNotNull(s.error) // spawn failure surfaced — not a silent success
    }

    @Test
    fun code_without_pending_login_reports_an_error() = runBlocking {
        val emitted = mutableListOf<Frame>()
        service(busy = 0).submitCode("abc", emit = { emitted.add(it) })
        val s = emitted.single() as AuthState
        assertTrue(s.error!!.contains("no login in progress"), s.error)
    }
}
