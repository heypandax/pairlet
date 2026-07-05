package dev.ccpocket.daemon.session

import dev.ccpocket.daemon.disk.LiveProcesses
import dev.ccpocket.protocol.SendPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

/**
 * Decision matrix for [SessionRegistry.externallyActive] — the single detector behind BOTH the take-over
 * fork and the read-only observe split. mtime freshness is only the cheap gate; the injected process
 * probe delivers the verdict, and a probe failure falls back to the old mtime-only behavior (fork rather
 * than risk two writers clobbering one transcript).
 *
 *   mtime stale / file missing / pre-boot → false, probe never runs (no lsof on the ordinary open path)
 *   mtime fresh + probe ABSENT           → false (terminal already quit — resume in place, no fork)
 *   mtime fresh + probe PRESENT          → true  (live external claude — fork / observe as before)
 *   mtime fresh + probe UNKNOWN/throws   → true  (conservative: keep the mtime verdict)
 */
class SessionRegistryExternallyActiveTest {

    private fun registry(probe: (String, Path) -> LiveProcesses.ExternalClaude) =
        SessionRegistry(CoroutineScope(Dispatchers.Default), backends = emptyMap(), processProbe = probe)

    /** A transcript in a fresh temp workdir with its mtime pinned to [mtimeMs]. */
    private fun transcript(dir: Path, mtimeMs: Long): Path {
        val f = dir.resolve("sid.jsonl")
        Files.writeString(f, "{}")
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtimeMs))
        return f
    }

    @Test
    fun stale_mtime_is_false_and_skips_the_probe() = runBlocking {
        var probed = false
        val reg = registry { _, _ -> probed = true; LiveProcesses.ExternalClaude.PRESENT }
        val dir = Files.createTempDirectory("sr-ext")
        val f = transcript(dir, System.currentTimeMillis() - 60_000) // well past the 20s live window
        assertFalse(reg.externallyActive("sid", dir.toString(), f))
        assertFalse(probed, "a stale transcript must not pay for an lsof probe")
    }

    @Test
    fun missing_file_is_false_and_skips_the_probe() = runBlocking {
        var probed = false
        val reg = registry { _, _ -> probed = true; LiveProcesses.ExternalClaude.PRESENT }
        val dir = Files.createTempDirectory("sr-ext")
        assertFalse(reg.externallyActive("sid", dir.toString(), dir.resolve("nope.jsonl")))
        assertFalse(probed)
    }

    @Test
    fun pre_boot_mtime_is_false_and_skips_the_probe() = runBlocking {
        // restart amnesia: inside the 20s window but BEFORE this registry booted → our previous instance's write
        var probed = false
        val reg = registry { _, _ -> probed = true; LiveProcesses.ExternalClaude.PRESENT }
        val dir = Files.createTempDirectory("sr-ext")
        val f = transcript(dir, System.currentTimeMillis() - 5_000) // fresh, but predates registry construction
        assertFalse(reg.externallyActive("sid", dir.toString(), f))
        assertFalse(probed)
    }

    @Test
    fun fresh_mtime_but_no_external_process_is_false() = runBlocking {
        // the fixed misfork: user quit the terminal claude seconds ago — mtime is fresh, nobody is writing
        val reg = registry { _, _ -> LiveProcesses.ExternalClaude.ABSENT }
        val dir = Files.createTempDirectory("sr-ext")
        val f = transcript(dir, System.currentTimeMillis())
        assertFalse(reg.externallyActive("sid", dir.toString(), f))
    }

    @Test
    fun fresh_mtime_with_live_external_process_is_true() = runBlocking {
        val reg = registry { _, _ -> LiveProcesses.ExternalClaude.PRESENT }
        val dir = Files.createTempDirectory("sr-ext")
        val f = transcript(dir, System.currentTimeMillis())
        assertTrue(reg.externallyActive("sid", dir.toString(), f))
    }

    @Test
    fun probe_unknown_falls_back_to_the_mtime_verdict() = runBlocking {
        // Windows / lsof missing / timeout: keep the old behavior — a spurious fork beats a two-writer clobber
        val reg = registry { _, _ -> LiveProcesses.ExternalClaude.UNKNOWN }
        val dir = Files.createTempDirectory("sr-ext")
        val f = transcript(dir, System.currentTimeMillis())
        assertTrue(reg.externallyActive("sid", dir.toString(), f))
    }

    @Test
    fun probe_crash_falls_back_to_the_mtime_verdict() = runBlocking {
        val reg = registry { _, _ -> error("lsof exploded") }
        val dir = Files.createTempDirectory("sr-ext")
        val f = transcript(dir, System.currentTimeMillis())
        assertTrue(reg.externallyActive("sid", dir.toString(), f))
    }
}
