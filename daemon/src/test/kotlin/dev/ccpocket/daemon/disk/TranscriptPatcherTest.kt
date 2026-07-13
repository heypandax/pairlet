package dev.ccpocket.daemon.disk

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptPatcherTest {

    private fun tmpFile(name: String) = Files.createTempDirectory("ccp-patch").resolve(name)

    @Test
    fun rewrites_sdk_entrypoint_to_cli_drops_queue_ops_and_is_idempotent() {
        val f = tmpFile("sess-1.jsonl")
        f.writeText(
            listOf(
                """{"type":"queue-operation","operation":"enqueue","sessionId":"sess-1"}""",
                """{"type":"user","entrypoint":"sdk-cli","promptSource":"sdk","message":{"role":"user","content":"hi"}}""",
                """{"type":"assistant","entrypoint":"sdk-cli","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        // queue-operation bookkeeping is dropped; the two real turns survive with entrypoint rewritten
        assertEquals(2, patched.size)
        assertEquals("""{"type":"user","entrypoint":"cli","promptSource":"sdk","message":{"role":"user","content":"hi"}}""", patched[0])
        assertEquals("""{"type":"assistant","entrypoint":"cli","message":{"content":[{"type":"text","text":"ok"}]}}""", patched[1])

        // second pass finds nothing to change and must not rewrite the file
        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(patched, f.readText().trimEnd().lines())
        assertFalse(Files.exists(f.resolveSibling("sess-1.jsonl.pocket-tmp")))
    }

    @Test
    fun drops_task_notification_turn_and_relinks_child_to_grandparent() {
        val f = tmpFile("sess-tn.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"deploy please"}}""",
                """{"type":"user","uuid":"n","parentUuid":"a","message":{"role":"user","content":"<task-notification>\n<task-id>x</task-id>\n</task-notification>"}}""",
                """{"type":"assistant","uuid":"b","parentUuid":"n","message":{"content":[{"type":"text","text":"on it"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(2, patched.size) // the notification turn is gone
        assertTrue(patched.none { it.contains("task-notification") })
        // the assistant turn re-points past the dropped notification, onto its grandparent 'a'
        assertTrue(patched[1].contains(""""uuid":"b""""))
        assertTrue(patched[1].contains(""""parentUuid":"a""""))
    }

    @Test
    fun drops_consecutive_notifications_and_relinks_to_surviving_ancestor() {
        val f = tmpFile("sess-cc.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"hi"}}""",
                """{"type":"user","uuid":"n1","parentUuid":"a","message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
                """{"type":"user","uuid":"n2","parentUuid":"n1","message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
                """{"type":"assistant","uuid":"b","parentUuid":"n2","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(2, patched.size)
        assertTrue(patched.none { it.contains("task-notification") })
        assertTrue(patched[1].contains(""""parentUuid":"a"""")) // re-linked across BOTH dropped turns
    }

    @Test
    fun dropping_a_root_notification_nulls_the_child_parent() {
        val f = tmpFile("sess-root.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"n","parentUuid":null,"message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
                """{"type":"user","uuid":"a","parentUuid":"n","message":{"role":"user","content":"real msg"}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(1, patched.size)
        assertTrue(patched[0].contains(""""uuid":"a""""))
        assertTrue(patched[0].contains(""""parentUuid":null""")) // root child becomes a new root
    }

    @Test
    fun drops_isMeta_skill_injection_and_relinks_child_to_grandparent() {
        // issue #126: a Skill load writes an isMeta:true user record carrying the whole SKILL.md —
        // plumbing, not the user typing. It is dropped and the chain re-stitched across it.
        val f = tmpFile("sess-skill.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"用 skill 干活"}}""",
                """{"type":"user","uuid":"m","parentUuid":"a","isMeta":true,"sourceToolUseID":"s1","message":{"role":"user","content":[{"type":"text","text":"Base directory for this skill: /x/skills/brain\n\nSKILL_MD_BODY"}]}}""",
                """{"type":"assistant","uuid":"b","parentUuid":"m","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(2, patched.size) // the injection turn is gone
        assertTrue(patched.none { it.contains("SKILL_MD_BODY") })
        assertTrue(patched[1].contains(""""uuid":"b""""))
        assertTrue(patched[1].contains(""""parentUuid":"a"""")) // relinked past the dropped injection
    }

    @Test
    fun drops_fingerprint_injection_without_isMeta() {
        // isMeta-less variant (older CLI): the "Base directory for this skill:" opening is the fallback
        val f = tmpFile("sess-skill-nometa.jsonl")
        f.writeText(
            listOf(
                """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"hi"}}""",
                """{"type":"user","uuid":"m","parentUuid":"a","message":{"role":"user","content":[{"type":"text","text":"Base directory for this skill: /x\n\nSKILL_MD_BODY"}]}}""",
                """{"type":"assistant","uuid":"b","parentUuid":"m","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(2, patched.size)
        assertTrue(patched.none { it.contains("SKILL_MD_BODY") })
        assertTrue(patched[1].contains(""""parentUuid":"a""""))
    }

    @Test
    fun keeps_an_isMeta_row_that_carries_a_tool_result() {
        // a tool_result carrier is NEVER dropped, isMeta or not — removing it would orphan the matching
        // tool_use and 400 the resumed API chain
        val f = tmpFile("sess-meta-tr.jsonl")
        val lines = listOf(
            """{"type":"assistant","uuid":"t","parentUuid":null,"message":{"content":[{"type":"tool_use","id":"s1","name":"Skill","input":{"skill":"brain"}}]}}""",
            """{"type":"user","uuid":"r","parentUuid":"t","isMeta":true,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"s1","content":"Launching skill: brain"}]}}""",
        )
        f.writeText(lines.joinToString("\n"))

        assertFalse(TranscriptPatcher.unhide(f)) // nothing safe to drop, no sdk tag -> untouched
        assertEquals(lines, f.readText().trimEnd().lines())
    }

    @Test
    fun keeps_a_user_turn_merely_quoting_the_injection_fingerprint() {
        // the prefix mid-message is a user genuinely talking about it — opening-only matching keeps it
        val f = tmpFile("sess-quote.jsonl")
        val line = """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"请解释 Base directory for this skill: 是什么意思"}}"""
        f.writeText(line)

        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(line, f.readText().trimEnd())
    }

    @Test
    fun keeps_a_notification_whose_parent_is_a_system_record_so_resume_chain_stays_valid() {
        // issue #24: after /compact the chain root is a `type:"system"` compact_boundary. If the first turn
        // under it is a pure <task-notification>, dropping it would relink the surviving assistant straight
        // onto the system record, and `claude --resume` then fails with 400 "System message must be at the
        // beginning". So that notification is KEPT — claude's own well-formed chain stays intact — while the
        // entrypoint is still rewritten so the session shows up in the desktop picker.
        val f = tmpFile("sess-sysroot.jsonl")
        f.writeText(
            listOf(
                """{"type":"system","uuid":"s","parentUuid":null,"subtype":"compact_boundary"}""",
                """{"type":"user","uuid":"n","parentUuid":"s","entrypoint":"sdk-cli","message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
                """{"type":"assistant","uuid":"b","parentUuid":"n","entrypoint":"sdk-cli","message":{"content":[{"type":"text","text":"ok"}]}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f)) // still rewrites: the sdk-cli entrypoint must become cli
        val patched = f.readText().trimEnd().lines()
        assertEquals(3, patched.size) // the notification is KEPT (not dropped) to protect the resume chain
        assertTrue(patched.any { it.contains("task-notification") })
        assertTrue(patched.none { it.contains("sdk-cli") }) // entrypoints rewritten to cli
        // the assistant is NOT relinked onto the system record — its parent is still the kept notification
        assertTrue(patched[2].contains(""""uuid":"b""""))
        assertTrue(patched[2].contains(""""parentUuid":"n""""))
    }

    @Test
    fun system_rooted_notification_with_no_sdk_tag_is_a_quiet_noop() {
        // same dangerous shape but already-cli: nothing is safe to drop, so the file must be left untouched
        val f = tmpFile("sess-sysroot-cli.jsonl")
        val lines = listOf(
            """{"type":"system","uuid":"s","parentUuid":null,"subtype":"compact_boundary"}""",
            """{"type":"user","uuid":"n","parentUuid":"s","message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
            """{"type":"assistant","uuid":"b","parentUuid":"n","message":{"content":[{"type":"text","text":"ok"}]}}""",
        )
        f.writeText(lines.joinToString("\n"))

        assertFalse(TranscriptPatcher.unhide(f)) // nothing droppable, no sdk tag -> no rewrite
        assertEquals(lines, f.readText().trimEnd().lines())
    }

    @Test
    fun keeps_a_turn_with_real_text_after_the_notification() {
        val f = tmpFile("sess-mixed.jsonl")
        val line = """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"<task-notification>\n</task-notification>\nplease deploy"}}"""
        f.writeText(line)

        // real text remains after the notification block -> not pure noise, nothing to do
        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(line, f.readText().trimEnd())
    }

    @Test
    fun ignores_tag_escaped_inside_message_content() {
        val f = tmpFile("sess-2.jsonl")
        // prompt text QUOTING the tag carries escaped quotes — must survive untouched
        val line = """{"type":"user","entrypoint":"cli","message":{"role":"user","content":"why is \"entrypoint\":\"sdk-cli\" hidden?"}}"""
        f.writeText(line)

        assertFalse(TranscriptPatcher.unhide(f))
        assertEquals(line, f.readText().trimEnd())
    }

    @Test
    fun unhide_preserves_the_transcript_mtime_so_a_reaped_session_isnt_seen_as_live() {
        // the rewrite is daemon bookkeeping — it must NOT bump mtime, else a just-reaped phone session reads as a
        // live foreign one and the phone shows a bogus take-over (issue #33 follow-up / #18 A-1)
        val f = tmpFile("sess-mtime.jsonl")
        f.writeText("""{"type":"assistant","entrypoint":"sdk-cli","message":{"content":[{"type":"text","text":"ok"}]}}""")
        val old = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L) // a fixed whole-second past instant
        Files.setLastModifiedTime(f, old)

        assertTrue(TranscriptPatcher.unhide(f)) // it DID rewrite (sdk-cli -> cli)
        assertTrue(f.readText().contains(""""entrypoint":"cli""""))
        assertEquals(old.toMillis(), Files.getLastModifiedTime(f).toMillis()) // ...but the mtime is unchanged
    }

    @Test
    fun missing_file_is_a_quiet_noop() {
        val dir = Files.createTempDirectory("ccp-patch")
        assertFalse(TranscriptPatcher.unhide(dir.resolve("nope.jsonl")))
    }

    @Test
    fun summary_leaf_uuid_pointing_at_a_dropped_turn_is_remapped_to_the_survivor() {
        // claude's summary records reference a branch leaf via leafUuid; if that leaf is a dropped
        // notification the summary would dangle — remap it up to the nearest surviving ancestor
        val f = tmpFile("sess-leaf.jsonl")
        f.writeText(
            listOf(
                """{"type":"summary","summary":"deploy work","leafUuid":"n"}""",
                """{"type":"user","uuid":"a","parentUuid":null,"message":{"role":"user","content":"deploy please"}}""",
                """{"type":"user","uuid":"n","parentUuid":"a","message":{"role":"user","content":"<task-notification>\n</task-notification>"}}""",
            ).joinToString("\n"),
        )

        assertTrue(TranscriptPatcher.unhide(f))
        val patched = f.readText().trimEnd().lines()
        assertEquals(2, patched.size) // the notification turn is gone
        assertTrue(patched[0].contains(""""leafUuid":"a"""")) // summary re-points at the surviving ancestor
    }
}
