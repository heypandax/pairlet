package dev.ccpocket.daemon.disk

import dev.ccpocket.protocol.AgentKind
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the aggregation against a temp projects tree via the injectable roots (never the real ~/.claude|~/.codex). */
class UsageServiceTest {

    private fun withProjects(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("ccp-usage-projects")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun today_buckets_by_hour_and_filters_zero_models() {
        withProjects { root ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            fun at(hour: Int, min: Int) = today.atTime(hour, min).atZone(zone).toInstant().toString()

            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(
                listOf(
                    // real model, two turns in hour 3 → 150 + 150
                    """{"type":"assistant","timestamp":"${at(3, 10)}","requestId":"r1","message":{"id":"m1","model":"claude-opus-4-8","usage":{"input_tokens":100,"output_tokens":50}}}""",
                    """{"type":"assistant","timestamp":"${at(3, 40)}","requestId":"r2","message":{"id":"m2","model":"claude-opus-4-8","usage":{"input_tokens":100,"output_tokens":50}}}""",
                    // real model, one turn in hour 14 → 200
                    """{"type":"assistant","timestamp":"${at(14, 5)}","requestId":"r3","message":{"id":"m3","model":"claude-opus-4-8","usage":{"input_tokens":200,"output_tokens":0}}}""",
                    // a <synthetic> zero-token turn — must NOT surface as a by-model row
                    """{"type":"assistant","timestamp":"${at(9, 0)}","requestId":"r4","message":{"id":"m4","model":"<synthetic>","usage":{"input_tokens":0,"output_tokens":0}}}""",
                ).joinToString("\n") + "\n",
            )

            val u = UsageService.aggregate(1, projectsRoot = root, codexFiles = emptyList())

            val hours = assertNotNull(u.hours, "Today range must carry 24 hourly buckets")
            assertEquals(24, hours.size)
            assertEquals(300L, hours[3].tokens)
            assertEquals(200L, hours[14].tokens)
            assertEquals(0L, hours[0].tokens)
            assertEquals("03:00", hours[3].label)
            assertEquals(500L, u.tokensToday)
            // the zero-token <synthetic> entry is filtered at the source, so exactly one model remains
            val m = u.models.single()
            assertEquals("claude-opus-4-8", m.model)
            assertEquals(500L, m.tokens)
            assertEquals(AgentKind.CLAUDE, m.agent)
        }
    }

    @Test
    fun week_range_has_no_hours_and_dates_every_day_bucket() {
        withProjects { root ->
            val today = LocalDate.now(ZoneId.systemDefault())
            val u = UsageService.aggregate(7, projectsRoot = root, codexFiles = emptyList())
            assertNull(u.hours, "only the Today range fills hours")
            assertEquals(7, u.days.size)
            assertTrue(u.days.all { it.date != null }, "every day bucket carries an ISO date")
            assertEquals(today.toString(), u.days.last().date)
        }
    }
}
