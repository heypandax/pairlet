package dev.ccpocket.daemon.disk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #253: harness-injected `<system-reminder>` turns must not read as the user talking — while a
 *  reminder PREPENDED to real input keeps the whole turn, because the CLI routinely prepends them. */
class TranscriptNoiseTest {

    @Test
    fun pure_system_reminder_is_noise() {
        val todoNudge = "<system-reminder>\nYour todo list has changed. DO NOT mention this explicitly.\n</system-reminder>"
        assertTrue(TranscriptNoise.isPureSystemReminder(todoNudge))
        assertTrue(TranscriptNoise.isNoiseUserText(todoNudge))
        assertTrue(TranscriptNoise.isNoiseUserText("  \n$todoNudge\n  ")) // surrounding whitespace only
    }

    @Test
    fun several_injected_blocks_back_to_back_are_noise() {
        val reminders = "<system-reminder>first</system-reminder>\n<system-reminder>second</system-reminder>"
        assertTrue(TranscriptNoise.isPureSystemReminder(reminders))
        // mixed injections in one turn (a notification followed by a reminder) are still all plumbing
        assertTrue(TranscriptNoise.isNoiseUserText("<task-notification>x</task-notification>\n<system-reminder>y</system-reminder>"))
    }

    @Test
    fun reminder_prepended_to_real_text_keeps_the_turn() {
        val mixed = "<system-reminder>context nudge</system-reminder>\nship the release please"
        assertFalse(TranscriptNoise.isPureSystemReminder(mixed))
        assertFalse(TranscriptNoise.isNoiseUserText(mixed))
        // …and so does a reminder that trails genuine input
        assertFalse(TranscriptNoise.isNoiseUserText("ship it\n<system-reminder>nudge</system-reminder>"))
    }

    @Test
    fun unterminated_or_quoted_reminder_keeps_the_turn() {
        // no closing tag: we cannot tell where the injection ends, so never eat the turn
        assertFalse(TranscriptNoise.isNoiseUserText("<system-reminder>truncated payload"))
        // the user genuinely talking *about* the tag mid-message
        assertFalse(TranscriptNoise.isNoiseUserText("why does <system-reminder> show up as my message?"))
    }

    @Test
    fun task_notification_and_other_shapes_still_recognized() {
        assertTrue(TranscriptNoise.isPureTaskNotification("<task-notification>\n<task-id>x</task-id>\n</task-notification>"))
        assertTrue(TranscriptNoise.isNoiseUserText("Continue from where you left off."))
        assertTrue(TranscriptNoise.isNoiseUserText("Base directory for this skill: /x/.claude/skills/brain\n\n# brain"))
        assertTrue(TranscriptNoise.isNoiseUserText("<command-name>/design</command-name>"))
        // a pure system-reminder is NOT a task-notification — the narrower predicates stay narrow,
        // since TranscriptPatcher uses isPureTaskNotification to delete lines from the real transcript
        assertFalse(TranscriptNoise.isPureTaskNotification("<system-reminder>nudge</system-reminder>"))
        assertFalse(TranscriptNoise.isPureSystemReminder("<task-notification>x</task-notification>"))
    }

    @Test
    fun empty_and_ordinary_text_are_never_noise() {
        assertFalse(TranscriptNoise.isNoiseUserText(null))
        assertFalse(TranscriptNoise.isNoiseUserText("   "))
        assertFalse(TranscriptNoise.isNoiseUserText("deploy please"))
    }
}
