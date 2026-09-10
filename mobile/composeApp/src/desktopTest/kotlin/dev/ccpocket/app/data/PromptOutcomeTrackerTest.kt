package dev.ccpocket.app.data

import dev.ccpocket.app.telemetry.*
import dev.ccpocket.protocol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PromptOutcomeTrackerTest {
    @Test fun futureTerminalVocabularyStaysUnknownAndPartiallyCovered() = runTest {
        val events = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        val tracker = PromptOutcomeTracker(backgroundScope, { true }, { 20 }, { e, p -> events += e to p }, { { true } })
        val context = tracker.request(SendPrompt("c", "fixture", promptId = "p"), true, emptyMap()).diagnostic!!
        tracker.progress(PromptProgress("c", context, "complete", "future_terminal"))
        val result = events.single { it.first == TelEvent.TurnResult }.second
        assertEquals("unknown", result[TelKey.Result])
        assertEquals("partial", result[TelKey.Coverage])
        assertEquals("incomplete", result[TelKey.Reason])
        tracker.reset()
    }

    @Test fun consumedResponseHasItsOwnDeadlineAndOneLateRecovery() = runTest {
        val events = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        val tracker = PromptOutcomeTracker(backgroundScope, { true }, { 50 }, { e, p -> events += e to p }, { { true } })
        val request = tracker.request(SendPrompt("c", "fixture", promptId = "p"), true, emptyMap())
        val context = request.diagnostic!!
        tracker.progress(PromptProgress("c", context, "consumed"))
        runCurrent(); advanceTimeBy(51); runCurrent()
        assertEquals("timeout", events.single { it.first == TelEvent.PromptResponseResult }.second[TelKey.Result])
        assertTrue(events.none { it.first == TelEvent.TurnResult }) // deadline never settles Agent work
        repeat(2) { tracker.progress(PromptProgress("c", context, "first_output")) }
        assertEquals(1, events.count { it.first == TelEvent.PromptResponseRecovered })
        tracker.progress(PromptProgress("c", context, "complete", "success", true))
        assertEquals("success", events.single { it.first == TelEvent.TurnResult }.second[TelKey.Result])
        assertTrue(events.none { it.first == TelEvent.ValueReached }) // network output is not a visible result
        tracker.reset()
    }

    @Test fun queuedOrAnotherAttemptsProgressCannotStartTheResponseDeadline() = runTest {
        val events = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        val tracker = PromptOutcomeTracker(backgroundScope, { true }, { 20 }, { e, p -> events += e to p }, { { true } })
        val context = tracker.request(SendPrompt("c", "fixture", promptId = "p"), true, emptyMap()).diagnostic!!
        tracker.progress(PromptProgress("c", context, "queued"))
        tracker.progress(PromptProgress("c", context.copy(attempt = 1), "consumed"))
        tracker.progress(PromptProgress("other", context, "consumed"))
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertTrue(events.none { it.first == TelEvent.PromptResponseResult })
        tracker.reset()
        assertEquals("unknown", events.single { it.first == TelEvent.PromptResponseResult }.second[TelKey.Result])
    }

    @Test fun backgroundWaitAndConsentChangesNeverBecomeForegroundFailure() = runTest {
        var foreground = true
        var allowed = true
        val events = mutableListOf<Pair<TelEvent, Map<TelKey, Any>>>()
        val tracker = PromptOutcomeTracker(backgroundScope, { foreground }, { 20 }, { e, p -> events += e to p }, { { allowed } })
        val context = tracker.request(SendPrompt("c", "fixture", promptId = "p"), true, emptyMap()).diagnostic!!
        tracker.progress(PromptProgress("c", context, "consumed"))
        runCurrent()
        foreground = false; tracker.background()
        advanceTimeBy(100); runCurrent()
        assertEquals("waiting", events.single { it.first == TelEvent.PromptResponseResult }.second[TelKey.Result])
        allowed = false
        tracker.progress(PromptProgress("c", context, "complete", "failure"))
        assertTrue(events.none { it.first == TelEvent.TurnResult })
        tracker.reset()
    }
}
