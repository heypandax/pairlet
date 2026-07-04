package dev.ccpocket.daemon.session

import dev.ccpocket.protocol.SendPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse

class SessionRegistryPromptTest {

    /** A prompt for a convo the registry no longer holds (idle-reaped / daemon restarted) must report the
     *  miss — the router turns that into SessionGone so the phone can re-open + resend, instead of the old
     *  `?: Unit` where the message silently vanished ("sent into a ghost session, nothing happened"). */
    @Test
    fun prompt_for_unknown_convo_reports_miss() = runBlocking {
        val registry = SessionRegistry(CoroutineScope(Dispatchers.Default), backends = emptyMap())
        assertFalse(registry.sendPrompt(SendPrompt(convoId = "reaped-long-ago", text = "hello?")))
    }
}
