package dev.ccpocket.daemon.feishu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Security boundary for permanent built-in-engine teardown; no Feishu SDK/network is involved. */
class FeishuEngineLifecycleTest {
    @Test
    fun a_handler_that_opens_late_is_joined_before_the_origin_close_sweep() = runBlocking {
        val events = mutableListOf<String>()
        val handlerJob = SupervisorJob()
        val handlerScope = CoroutineScope(coroutineContext + handlerJob)
        val handlerEntered = CompletableDeferred<Unit>()
        val allowLateOpen = CompletableDeferred<Unit>()
        val quiesced = CompletableDeferred<Unit>()
        var activeOriginConversations = 0

        handlerScope.launch {
            // Models router/open work that has already crossed a cancellation boundary when revoke starts.
            withContext(NonCancellable) {
                handlerEntered.complete(Unit)
                allowLateOpen.await()
                activeOriginConversations++
                events += "handler-late-open"
            }
        }
        handlerEntered.await()

        val revoke = async {
            revokeAfterHandlerDrain(
                quiesceIngress = {
                    events += "quiesce"
                    quiesced.complete(Unit)
                },
                handlerJob = handlerJob,
                closeOriginConversations = {
                    events += "close-origin:$activeOriginConversations"
                    activeOriginConversations = 0
                },
                releaseResources = { events += "release" },
            )
        }
        quiesced.await()

        assertFalse(revoke.isCompleted, "teardown must join the already-admitted handler")
        assertEquals(listOf("quiesce"), events, "origin close ran before the late handler drained")

        allowLateOpen.complete(Unit)
        revoke.await()

        assertEquals(listOf("quiesce", "handler-late-open", "close-origin:1", "release"), events)
        assertEquals(0, activeOriginConversations)
    }

    @Test
    fun an_origin_close_failure_is_rethrown_after_resources_are_released() = runBlocking {
        val handlerJob = SupervisorJob()
        var released = false

        val failure = runCatching {
            revokeAfterHandlerDrain(
                quiesceIngress = {},
                handlerJob = handlerJob,
                closeOriginConversations = { error("registry close failed") },
                releaseResources = { released = true },
            )
        }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertTrue(failure.message.orEmpty().contains("registry close failed"))
        assertTrue(released, "resource release must still run when registry close fails")
    }
}
