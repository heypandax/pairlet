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

    /** One assistant turn of [tokens] input tokens at noon, [daysAgo] local days back. */
    private fun turn(daysAgo: Long, tokens: Long, id: String): String {
        val ts = LocalDate.now(ZoneId.systemDefault()).minusDays(daysAgo).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        return """{"type":"assistant","timestamp":"$ts","requestId":"r-$id","message":{"id":"m-$id","model":"claude-opus-4-8","usage":{"input_tokens":$tokens,"output_tokens":0}}}"""
    }

    @Test
    fun today_range_prev_window_is_yesterday_only() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(
                listOf(
                    turn(0, 300, "a"),  // today → the window
                    turn(1, 500, "b"),  // yesterday → the prev window
                    turn(2, 700, "c"),  // the day before → outside both, ignored
                ).joinToString("\n") + "\n",
            )
            val u = UsageService.aggregate(1, projectsRoot = root, codexFiles = emptyList())
            assertEquals(300L, u.tokensToday)
            assertEquals(500L, u.prevWindowTokens, "span 1 compares against yesterday only")
            // prev-window turns must NOT leak into the trend or the by-model bars
            assertEquals(300L, u.days.sumOf { it.tokens })
            assertEquals(300L, u.models.single().tokens)
        }
    }

    @Test
    fun week_range_prev_window_sums_the_7_days_before() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(
                listOf(
                    turn(0, 100, "a"),   // in the 7d window
                    turn(6, 200, "b"),   // oldest day of the 7d window
                    turn(7, 400, "c"),   // newest day of the prev window
                    turn(13, 800, "d"),  // oldest day of the prev window
                    turn(14, 1600, "e"), // outside both windows, ignored
                ).joinToString("\n") + "\n",
            )
            val u = UsageService.aggregate(7, projectsRoot = root, codexFiles = emptyList())
            assertEquals(300L, u.days.sumOf { it.tokens })
            assertEquals(1200L, u.prevWindowTokens, "prev window = the 7 days right before the visible 7")
            assertEquals(300L, u.models.single().tokens, "prev-window turns never feed the model bars")
        }
    }

    /** One assistant turn [daysAgo] days back carrying [input] input, [cacheRead] cache-read tokens and a
     *  top-level [costUsd] — enough to exercise the requests/cache-hit/cost sub-metrics (issue #174). */
    private fun richTurn(daysAgo: Long, input: Long, cacheRead: Long, costUsd: Double, id: String): String {
        val ts = LocalDate.now(ZoneId.systemDefault()).minusDays(daysAgo).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        return """{"type":"assistant","timestamp":"$ts","requestId":"r-$id","costUSD":$costUsd,""" +
            """"message":{"id":"m-$id","model":"claude-opus-4-8","usage":{"input_tokens":$input,"output_tokens":0,"cache_read_input_tokens":$cacheRead}}}"""
    }

    @Test
    fun window_sub_metrics_span_the_whole_window_not_just_today() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(
                listOf(
                    richTurn(0, 100, 100, 0.10, "a"),  // today
                    richTurn(3, 300, 100, 0.30, "b"),  // mid-window
                    richTurn(6, 200, 200, 0.20, "c"),  // oldest in-window day
                    richTurn(8, 999, 999, 9.90, "d"),  // prev window — must NOT feed either window/today set
                ).joinToString("\n") + "\n",
            )
            val u = UsageService.aggregate(7, projectsRoot = root, codexFiles = emptyList())
            // the today sub-metrics stay today-only (unchanged behaviour)
            assertEquals(1L, u.requestsToday)
            assertEquals(50, u.cacheHitPct, "today cache-hit = 100 / (100 + 100)")
            assertEquals(0.10, u.costUsdToday!!, 1e-9)
            // the window sub-metrics accumulate across ALL three in-window days, never the prev-window turn
            assertEquals(3L, u.requestsWindow, "requests span the whole 7d window, not just today")
            assertEquals(40, u.cacheHitPctWindow, "window cache-hit = 400 / (600 + 400)")
            assertEquals(0.60, u.costUsdWindow!!, 1e-9, "window cost sums every in-window transcript costUSD")
        }
    }

    @Test
    fun span_1_window_sub_metrics_mirror_the_today_values() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(
                listOf(
                    richTurn(0, 100, 100, 0.10, "a"),  // today
                    richTurn(1, 999, 999, 9.90, "b"),  // yesterday → prev window for span 1, excluded
                ).joinToString("\n") + "\n",
            )
            val u = UsageService.aggregate(1, projectsRoot = root, codexFiles = emptyList())
            // for the Today range the window IS today, so the window fields are filled and equal the today ones
            assertEquals(1L, u.requestsWindow)
            assertEquals(u.requestsToday, u.requestsWindow)
            assertEquals(u.cacheHitPct, u.cacheHitPctWindow)
            assertEquals(u.costUsdToday, u.costUsdWindow)
        }
    }

    @Test
    fun window_cost_is_null_when_no_transcript_records_a_cost() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            // a subscription-shaped transcript: usage tokens but NO top-level costUSD on any turn
            proj.resolve("s1.jsonl").writeText(
                listOf(turn(0, 200, "a"), turn(2, 400, "b")).joinToString("\n") + "\n",
            )
            val u = UsageService.aggregate(7, projectsRoot = root, codexFiles = emptyList())
            assertNull(u.costUsdToday, "no costUSD recorded → today cost stays null")
            assertNull(u.costUsdWindow, "no costUSD recorded → window cost stays null too")
            assertEquals(2L, u.requestsWindow, "requests still count even when cost is unknown")
        }
    }

    // issue #217: OpenCode turns feed the same aggregation, and their model gets the OPENCODE badge
    // (not the codex/gpt string heuristic — an OpenCode "openai/…" model must stay OPENCODE).
    @Test
    fun opencode_turns_count_and_classify_as_opencode() {
        withProjects { root ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            fun ms(hour: Int) = today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            val turns = listOf(
                dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.UsageTurn(
                    id = "oc1", whenEpochMs = ms(4), model = "zhipuai/glm-4.6",
                    input = 100, output = 40, cacheRead = 60,
                ),
                // an OpenCode session on an openai model — must NOT be mis-badged CODEX
                dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.UsageTurn(
                    id = "oc2", whenEpochMs = ms(4), model = "openai/gpt-5.1",
                    input = 10, output = 10, cacheRead = 0,
                ),
            )
            val u = UsageService.aggregate(1, projectsRoot = root, codexFiles = emptyList(), openCodeTurns = { turns })

            assertEquals(220L, u.tokensToday, "100+40+60 + 10+10")
            assertEquals(2L, u.requestsToday)
            val glm = u.models.single { it.model == "zhipuai/glm-4.6" }
            assertEquals(200L, glm.tokens)
            assertEquals(AgentKind.OPENCODE, glm.agent)
            val gpt = u.models.single { it.model == "openai/gpt-5.1" }
            assertEquals(AgentKind.OPENCODE, gpt.agent, "OpenCode's model stays OPENCODE, not CODEX")
            // cache-read splits out of input just like the other backends
            assertEquals(220L, u.hours!![4].tokens)
        }
    }

    // dedup by message id: the same OpenCode turn seen twice contributes once.
    @Test
    fun opencode_turns_dedup_by_message_id() {
        withProjects { root ->
            val zone = ZoneId.systemDefault()
            val ms = LocalDate.now(zone).atTime(5, 0).atZone(zone).toInstant().toEpochMilli()
            val one = dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.UsageTurn(
                id = "dup", whenEpochMs = ms, model = "anthropic/claude-sonnet-4-5",
                input = 50, output = 50, cacheRead = 0,
            )
            val u = UsageService.aggregate(1, projectsRoot = root, codexFiles = emptyList(), openCodeTurns = { listOf(one, one) })
            assertEquals(100L, u.tokensToday, "duplicate id counted once")
            assertEquals(1L, u.requestsToday)
        }
    }
}
