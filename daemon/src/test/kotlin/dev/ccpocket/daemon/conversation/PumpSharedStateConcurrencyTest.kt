package dev.ccpocket.daemon.conversation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lifecycle design S6: [BackgroundJobRegistry] and [WorkflowTracker] were documented "pump-only" but are
 * touched from other threads too — the panel's stop (markKilled), the reaper (reapStale), a reattach's
 * snapshots, a process stop's clear / killRunning. Unsynchronized, an iteration racing a mutation threw
 * ConcurrentModificationException (and an exception on the pump used to wedge the session, audit M6).
 * These hammer every entry point from several threads at once; any exception fails the test.
 */
class PumpSharedStateConcurrencyTest {

    private fun hammer(seconds: Double = 1.5, vararg workers: (Int) -> Unit): List<Throwable> {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val stop = AtomicBoolean(false)
        val start = CountDownLatch(1)
        val threads = workers.map { work ->
            thread(isDaemon = true) {
                start.await()
                var i = 0
                try {
                    while (!stop.get()) work(i++)
                } catch (t: Throwable) {
                    errors += t
                }
            }
        }
        start.countDown()
        Thread.sleep((seconds * 1000).toLong())
        stop.set(true)
        threads.forEach { it.join(5_000) }
        return errors.toList()
    }

    @Test
    fun background_job_registry_tolerates_concurrent_access() {
        val jobs = BackgroundJobRegistry()
        val bg = buildJsonObject { put("command", "make"); put("run_in_background", true) }
        val errors = hammer(
            1.5,
            { i -> // the pump: jobs born, linked, finished, plus foreground traffic
                val now = System.currentTimeMillis()
                jobs.onToolUse("t$i", "Bash", bg, now)
                jobs.onTaskStarted("k$i", "t$i", "make", "local_bash", now)
                jobs.onToolUse("f$i", "Bash", buildJsonObject { put("command", "ls") }, now)
                jobs.onToolUse("a$i", "Agent", buildJsonObject { put("description", "x") }, now)
                jobs.onToolResult("a$i", "done", false, now)
                if (i % 3 == 0) jobs.onTaskUpdated("k$i", "completed", now)
            },
            { i -> // the phone's stop + the reaper
                jobs.markKilled("t${i % 64}", System.currentTimeMillis())
                jobs.reapStale(System.currentTimeMillis(), 0)
            },
            { _ -> // reattach / directory / push readers
                jobs.snapshot()
                jobs.hasRunning()
                jobs.hasRunningBackgroundAgents()
            },
            { i -> if (i % 50 == 0) jobs.clear() else jobs.snapshot() }, // a process stop
        )
        assertTrue(errors.isEmpty(), "concurrent access threw: ${errors.take(3)}")
    }

    @Test
    fun workflow_tracker_tolerates_concurrent_access() {
        val runs = WorkflowTracker()
        val items = Json.parseToJsonElement(
            """[{"type":"workflow_phase","index":1,"title":"A"},
                {"type":"workflow_agent","index":1,"label":"x","phaseIndex":1,"agentId":"a","state":"start","startedAt":1,"queuedAt":1}]""",
        ) as JsonArray
        val errors = hammer(
            1.5,
            { i -> // the pump
                val now = System.currentTimeMillis()
                runs.onTaskStarted("w$i", "u$i", "flow", now)
                runs.onLaunched("u$i", "wf_$i", "w$i", "flow", now)
                runs.onProgress("w$i", "u$i", items, now)
                if (i % 2 == 0) runs.onTaskSettled("w$i", "completed", now)
            },
            { _ -> runs.killRunning(System.currentTimeMillis()) }, // a process stop / death
            { i -> // reattach replay + the manifest patch
                runs.snapshots()
                runs.snapshotFor("w${i % 16}")
                runs.onManifest("w${i % 16}", "wf_$i", "ok", 10, null)
                runs.snapshotWithFinal("w${i % 16}", "ok")
                runs.hasRunning()
            },
            { i -> if (i % 50 == 0) runs.clear() else runs.snapshots() },
        )
        assertTrue(errors.isEmpty(), "concurrent access threw: ${errors.take(3)}")
    }

}
