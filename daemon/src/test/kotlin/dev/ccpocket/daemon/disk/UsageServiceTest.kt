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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the aggregation against a temp projects tree via the injectable roots (never the real ~/.claude|~/.codex). */
class UsageServiceTest {

    /**
     * [UsageService.aggregate] with EVERY disk seam defaulted to empty.
     *
     * The seams' production defaults read the developer's real stores (~/.zcode, ~/.kimi-code, the
     * OpenCode db), so calling `aggregate` directly makes a test's numbers depend on whoever runs it —
     * exactly how the local ZCode store started inflating the request counts here (issue #258). Tests
     * opt IN to a backend by passing its fixture; everything else stays silent.
     */
    private fun hermetic(
        days: Int,
        projectsRoot: Path,
        codexFiles: List<Path> = emptyList(),
        openCodeTurns: (Long) -> List<dev.ccpocket.daemon.opencode.OpenCodeTranscriptScanner.UsageTurn> = { emptyList() },
        zcodeTurns: (Long) -> List<dev.ccpocket.daemon.zcode.ZCodeTranscriptScanner.UsageTurn> = { emptyList() },
        kimiRecords: (Long) -> List<dev.ccpocket.daemon.kimi.KimiUsageScanner.UsageRecord> = { emptyList() },
        dshRecords: (Long) -> List<dev.ccpocket.daemon.dsh.DshUsageScanner.UsageRecord> = { emptyList() },
        agent: AgentKind? = null,
    ) = UsageService.aggregate(days, projectsRoot, codexFiles, openCodeTurns, zcodeTurns, kimiRecords, dshRecords, agent)

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

            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList())

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
            val u = hermetic(7, projectsRoot = root, codexFiles = emptyList())
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
            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList())
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
            val u = hermetic(7, projectsRoot = root, codexFiles = emptyList())
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
            val u = hermetic(7, projectsRoot = root, codexFiles = emptyList())
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
            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList())
            // for the Today range the window IS today, so the window fields are filled and equal the today ones
            assertEquals(1L, u.requestsWindow)
            assertEquals(u.requestsToday, u.requestsWindow)
            assertEquals(u.cacheHitPct, u.cacheHitPctWindow)
            assertEquals(u.costUsdToday, u.costUsdWindow)
        }
    }

    /**
     * issue #323: the window cache-hit percentage now ships the two accumulators it was computed from.
     * The whole value of that is MACHINE-CHECKABLE traceability, so this pins the ratio against the
     * printed percentage — if the three could ever disagree, the "here is where the number came from"
     * line would itself be something the user has to take on faith, which is the bug.
     */
    @Test
    fun window_cache_hit_ships_the_numerator_and_denominator_behind_it() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(
                listOf(
                    richTurn(0, 100, 100, 0.10, "a"),  // today
                    richTurn(3, 300, 100, 0.30, "b"),  // mid-window
                    richTurn(6, 200, 200, 0.20, "c"),  // oldest in-window day
                    richTurn(8, 999, 999, 9.90, "d"),  // prev window — must feed neither accumulator
                ).joinToString("\n") + "\n",
            )
            val u = hermetic(7, projectsRoot = root)
            // the accumulators themselves: cache-read 400, and its base input(600) + cache-read(400)
            assertEquals(400L, u.cacheReadTokensWindow, "numerator = the window's cache-read tokens")
            assertEquals(1000L, u.cacheBaseTokensWindow, "denominator = input + cache-read over the same window")
            // …and they reproduce the percentage the card prints, under the page's own formula
            assertEquals(40, u.cacheHitPctWindow)
            assertEquals(
                u.cacheHitPctWindow,
                ((u.cacheReadTokensWindow!! * 100) / u.cacheBaseTokensWindow!!).toInt(),
                "the shipped ratio must reproduce the shipped percentage exactly",
            )
            // the denominator EXCLUDES cache-creation on purpose (writing the cache was never a hit
            // opportunity) — it is input + cache-read and nothing else
            assertEquals(600L + u.cacheReadTokensWindow!!, u.cacheBaseTokensWindow)
        }
    }

    /** No sample at all → both null, never a tidy-looking 0. Same "unknown vs measured zero" distinction
     *  cacheHitPctWindow has always drawn: 0/0 rendered as "0 / 0 tokens" would read as a real measurement
     *  of a cache that never got asked anything. */
    @Test
    fun window_cache_raw_tokens_are_null_without_a_sample() {
        withProjects { root ->
            val u = hermetic(7, projectsRoot = root)
            assertNull(u.cacheHitPctWindow, "an empty window is unknown, not 0%")
            assertNull(u.cacheReadTokensWindow)
            assertNull(u.cacheBaseTokensWindow)
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
            val u = hermetic(7, projectsRoot = root, codexFiles = emptyList())
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
            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList(), openCodeTurns = { turns })

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
            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList(), openCodeTurns = { listOf(one, one) })
            assertEquals(100L, u.tokensToday, "duplicate id counted once")
            assertEquals(1L, u.requestsToday)
        }
    }

    // ── issue #323: sub-agent transcripts live two levels deeper and were never scanned ─────────

    /** A sidechain assistant turn as the CLI writes it into `<sessionId>/subagents/agent-*.jsonl`:
     *  same shape as a top-level turn plus the sidechain markers, and no `costUSD`. */
    private fun subagentTurn(daysAgo: Long, tokens: Long, id: String): String {
        val ts = LocalDate.now(ZoneId.systemDefault()).minusDays(daysAgo).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        return """{"parentUuid":"p-$id","isSidechain":true,"agentId":"a1","type":"assistant","timestamp":"$ts",""" +
            """"requestId":"r-$id","message":{"id":"m-$id","model":"claude-opus-5","type":"message","role":"assistant",""" +
            """"usage":{"input_tokens":$tokens,"output_tokens":0}}}"""
    }

    /**
     * The whole bug: `~/.claude/projects/<dirKey>/<sessionId>/subagents/agent-*.jsonl` is two levels below
     * the top-level transcript, and the old two-level enumeration stopped one level short — so on a
     * Task-heavy machine a quarter of the tokens never reached this aggregation, which is why the total
     * never reconciled with the recursive glob ccusage walks.
     */
    @Test
    fun subagent_transcripts_two_levels_deep_are_counted_too() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(turn(0, 300, "top") + "\n")
            val subagents = proj.resolve("s1").resolve("subagents").also { it.createDirectories() }
            subagents.resolve("agent-aaa111.jsonl").writeText(subagentTurn(0, 500, "sub1") + "\n")
            subagents.resolve("agent-bbb222.jsonl").writeText(subagentTurn(0, 200, "sub2") + "\n")

            val u = hermetic(1, projectsRoot = root)
            assertEquals(1000L, u.tokensToday, "the session's own 300 plus both sub-agents' 500 + 200")
            assertEquals(3L, u.requestsToday, "each sub-agent turn is a request too")
            assertEquals(1000L, u.days.last().tokens)
            // the sub-agents ran on a different model, so they surface as their own by-model bar
            assertEquals(700L, u.models.single { it.model == "claude-opus-5" }.tokens)
            assertEquals(AgentKind.CLAUDE, u.models.single { it.model == "claude-opus-5" }.agent)
        }
    }

    /**
     * Double-count guard. Nothing stops a CLI version from writing the same sidechain turn into BOTH the
     * session transcript and the sub-agent file — adding a second enumeration depth would then inflate the
     * total instead of fixing it. The existing `message.id:requestId` dedup has to absorb that, and the
     * PARENT copy has to be the one kept, since it is the copy that may carry `costUSD`.
     */
    @Test
    fun a_turn_present_in_both_the_session_and_its_subagent_file_counts_once() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            // the parent carries the cost stamp; the sub-agent copy of the very same turn does not
            proj.resolve("s1.jsonl").writeText(richTurn(0, 400, 0, 0.25, "shared") + "\n")
            val subagents = proj.resolve("s1").resolve("subagents").also { it.createDirectories() }
            subagents.resolve("agent-aaa111.jsonl").writeText(
                listOf(
                    subagentTurn(0, 400, "shared"), // same message id + requestId as the parent's turn
                    subagentTurn(0, 100, "own"),    // genuinely new, must still land
                ).joinToString("\n") + "\n",
            )

            val u = hermetic(1, projectsRoot = root)
            assertEquals(500L, u.tokensToday, "the duplicated turn counts once (400), plus the sub-agent's own 100")
            assertEquals(2L, u.requestsToday)
            assertEquals(0.25, u.costUsdToday!!, 1e-9, "first-wins dedup keeps the parent copy, so its costUSD survives")
        }
    }

    /** The added depth must be inert where it doesn't apply: no `subagents/` dir, an empty one, a session
     *  dir holding something else entirely, and non-jsonl noise inside `subagents/` all read as before. */
    @Test
    fun missing_empty_and_non_jsonl_subagent_dirs_change_nothing() {
        withProjects { root ->
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(turn(0, 300, "top") + "\n")
            // s1 has no subagents dir at all; s2 has an empty one
            proj.resolve("s2").resolve("subagents").createDirectories()
            // s3's subagents dir holds only non-jsonl noise, and s3 itself holds a stray unrelated dir
            val s3sub = proj.resolve("s3").resolve("subagents").also { it.createDirectories() }
            s3sub.resolve("agent-aaa111.jsonl.tmp").writeText(subagentTurn(0, 999, "tmp") + "\n")
            s3sub.resolve("notes.txt").writeText(subagentTurn(0, 999, "txt") + "\n")
            s3sub.resolve("nested").createDirectories()
            proj.resolve("s3").resolve("checkpoints").createDirectories()
            // a .jsonl one level too shallow (directly under the session dir) is NOT a transcript location
            proj.resolve("s3").resolve("stray.jsonl").writeText(subagentTurn(0, 999, "stray") + "\n")

            val u = hermetic(1, projectsRoot = root)
            assertEquals(300L, u.tokensToday, "only the session's own transcript counts — none of the noise does")
            assertEquals(1L, u.requestsToday)
            assertEquals(1, u.models.size)
        }
    }

    // ── issue #258: ZCode + Kimi join the total, and one agent can be viewed alone ──────────────

    private fun msToday(hour: Int): Long =
        LocalDate.now(ZoneId.systemDefault()).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun zTurn(id: String, hour: Int, model: String, input: Long, output: Long, cacheCreation: Long = 0, cacheRead: Long = 0) =
        dev.ccpocket.daemon.zcode.ZCodeTranscriptScanner.UsageTurn(id, msToday(hour), model, input, output, cacheCreation, cacheRead)

    private fun kRecord(id: String, hour: Int, model: String, input: Long, output: Long, cacheCreation: Long = 0, cacheRead: Long = 0) =
        dev.ccpocket.daemon.kimi.KimiUsageScanner.UsageRecord(id, msToday(hour), model, input, output, cacheCreation, cacheRead)

    /** issue #279: dsh's records arrive with cache read already split out of input (DshUsageScanner does the
     *  OpenAI→Claude normalization), so they feed this aggregation on the same footing as every other backend. */
    private fun dRecord(id: String, hour: Int, model: String, input: Long, output: Long, cacheRead: Long = 0) =
        dev.ccpocket.daemon.dsh.DshUsageScanner.UsageRecord(id, msToday(hour), model, input, output, cacheCreation = 0, cacheRead = cacheRead)

    @Test
    fun zcode_requests_count_and_classify_as_zcode() {
        withProjects { root ->
            val turns = listOf(
                zTurn("u1", 6, "anthropic/glm-5", input = 100, output = 40, cacheCreation = 10, cacheRead = 50),
                // a ZCode session on an anthropic-hosted model must NOT fall into the claude/codex heuristic
                zTurn("u2", 6, "anthropic/claude-sonnet-4-5", input = 10, output = 10),
            )
            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList(), zcodeTurns = { turns })

            assertEquals(220L, u.tokensToday, "100+40+10+50 + 10+10 — cache CREATION counts in the total")
            assertEquals(2L, u.requestsToday)
            assertEquals(220L, u.hours!![6].tokens)
            val glm = u.models.single { it.model == "anthropic/glm-5" }
            assertEquals(200L, glm.tokens)
            assertEquals(AgentKind.ZCODE, glm.agent)
            assertEquals(AgentKind.ZCODE, u.models.single { it.model == "anthropic/claude-sonnet-4-5" }.agent)
            // cache read splits out of input, so the shared cache-hit formula sees 50 / (110 + 50)
            assertEquals(31, u.cacheHitPct)
        }
    }

    @Test
    fun kimi_records_count_and_classify_as_kimi() {
        withProjects { root ->
            val records = listOf(
                kRecord("k1", 8, "kimi-code/k3", input = 0, output = 76),
                kRecord("k2", 8, "kimi-code/k3", input = 100, output = 20, cacheRead = 30),
            )
            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList(), kimiRecords = { records })

            assertEquals(226L, u.tokensToday, "76 + (100+20+30)")
            assertEquals(2L, u.requestsToday)
            val m = u.models.single()
            assertEquals("kimi-code/k3", m.model)
            assertEquals(AgentKind.KIMI, m.agent)
            assertEquals(226L, m.tokens)
        }
    }

    // issue #279: dsh joins the total. Its chip existed from #255 but nothing fed it, so every DeepSeek bar
    // read 0 while the spend sat on disk.
    @Test
    fun dsh_records_count_and_classify_as_dsh() {
        withProjects { root ->
            val records = listOf(
                dRecord("d1", 7, "deepseek-v4-flash", input = 100, output = 40, cacheRead = 60),
                dRecord("d2", 7, "deepseek-v4-reasoner", input = 10, output = 10),
            )
            val u = hermetic(1, projectsRoot = root, codexFiles = emptyList(), dshRecords = { records })

            assertEquals(220L, u.tokensToday, "100+40+60 + 10+10")
            assertEquals(2L, u.requestsToday)
            assertEquals(220L, u.hours!![7].tokens)
            val flash = u.models.single { it.model == "deepseek-v4-flash" }
            assertEquals(200L, flash.tokens)
            // the legacy string heuristic would have mis-badged a "deepseek-…" key as CLAUDE; the badge must
            // come from the backend recorded at accumulation
            assertEquals(AgentKind.DSH, flash.agent)
            assertEquals(AgentKind.DSH, u.models.single { it.model == "deepseek-v4-reasoner" }.agent)
            // cache read is already split out of input, so the shared formula sees 60 / (110 + 60)
            assertEquals(35, u.cacheHitPct)
            assertNull(u.costUsdToday, "dsh stamps no cost — the column stays blank rather than estimated")
        }
    }

    @Test
    fun zcode_and_kimi_dedup_by_their_own_ids() {
        withProjects { root ->
            val z = zTurn("dup", 9, "anthropic/glm-5", input = 50, output = 50)
            val k = kRecord("dup", 9, "kimi-code/k3", input = 5, output = 5)
            val u = hermetic(
                1, projectsRoot = root, codexFiles = emptyList(),
                zcodeTurns = { listOf(z, z) }, kimiRecords = { listOf(k, k) },
            )
            // each duplicate is collapsed, and the two backends' identical ids do NOT collide with each other
            assertEquals(110L, u.tokensToday)
            assertEquals(2L, u.requestsToday)
        }
    }

    // The by-agent view (issue #258): a filtered aggregate reports ONLY that backend — and never even
    // touches the others' seams, so the scan cost drops with the scope.
    @Test
    fun agent_filter_scopes_the_whole_aggregation_to_one_backend() {
        withProjects { root ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val proj = root.resolve("-Users-x-proj").also { it.createDirectories() }
            proj.resolve("s1.jsonl").writeText(
                """{"type":"assistant","timestamp":"${today.atTime(7, 0).atZone(zone).toInstant()}","requestId":"r1","message":{"id":"m1","model":"claude-opus-4-8","usage":{"input_tokens":500,"output_tokens":0}}}""" + "\n",
            )
            var openCodeScanned = false
            fun aggregate(agent: AgentKind?) = hermetic(
                1, projectsRoot = root, codexFiles = emptyList(),
                openCodeTurns = { openCodeScanned = true; emptyList() },
                zcodeTurns = { listOf(zTurn("u1", 6, "anthropic/glm-5", input = 100, output = 0)) },
                kimiRecords = { listOf(kRecord("k1", 8, "kimi-code/k3", input = 0, output = 76)) },
                dshRecords = { listOf(dRecord("d1", 10, "deepseek-v4-flash", input = 200, output = 0)) },
                agent = agent,
            )

            val all = aggregate(null)
            assertEquals(876L, all.tokensToday, "unfiltered stays the sum of every backend")
            assertEquals(4, all.models.size)
            assertTrue(openCodeScanned, "the unfiltered pass reads every backend")

            openCodeScanned = false
            val zcodeOnly = aggregate(AgentKind.ZCODE)
            assertEquals(100L, zcodeOnly.tokensToday)
            assertEquals(1L, zcodeOnly.requestsToday)
            assertEquals(AgentKind.ZCODE, zcodeOnly.models.single().agent)
            assertEquals(100L, zcodeOnly.days.last().tokens, "the trend follows the filter too")
            assertFalse(openCodeScanned, "a narrowed request must not scan the other backends' stores")

            assertEquals(76L, aggregate(AgentKind.KIMI).tokensToday, "the Kimi view must not pick up dsh's 200")
            assertEquals(500L, aggregate(AgentKind.CLAUDE).tokensToday)
            val dshOnly = aggregate(AgentKind.DSH)
            assertEquals(200L, dshOnly.tokensToday)
            assertEquals(1L, dshOnly.requestsToday)
            assertEquals(AgentKind.DSH, dshOnly.models.single().agent)
            assertEquals(200L, dshOnly.days.last().tokens, "the trend follows the dsh filter too")
            assertEquals(0L, aggregate(AgentKind.CODEX).tokensToday, "a backend with no records reads as empty, not as everything")
        }
    }
}
