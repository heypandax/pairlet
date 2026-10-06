package dev.ccpocket.app.data

import dev.ccpocket.app.data.VoiceSendFlow.Effect
import dev.ccpocket.app.data.VoiceSendFlow.Effect.CancelRefine
import dev.ccpocket.app.data.VoiceSendFlow.Effect.Discard
import dev.ccpocket.app.data.VoiceSendFlow.Effect.Finished
import dev.ccpocket.app.data.VoiceSendFlow.Effect.NoSpeech
import dev.ccpocket.app.data.VoiceSendFlow.Effect.RequestRefine
import dev.ccpocket.app.data.VoiceSendFlow.Effect.ShowPreview
import dev.ccpocket.app.data.VoiceSendFlow.Effect.ShowRefining
import dev.ccpocket.app.data.VoiceSendFlow.Effect.SubmitSend
import dev.ccpocket.app.data.VoiceSendFlow.Effect.ToComposer
import dev.ccpocket.app.data.VoiceSendFlow.Effect.TranscribeFailed
import dev.ccpocket.app.data.VoiceSendFlow.Event
import dev.ccpocket.app.data.VoiceSendFlow.Event.CapReached
import dev.ccpocket.app.data.VoiceSendFlow.Event.Downgrade
import dev.ccpocket.app.data.VoiceSendFlow.Event.HoldElapsed
import dev.ccpocket.app.data.VoiceSendFlow.Event.Partial
import dev.ccpocket.app.data.VoiceSendFlow.Event.RefineDeadline
import dev.ccpocket.app.data.VoiceSendFlow.Event.SendResult
import dev.ccpocket.app.data.VoiceSendFlow.Event.Start
import dev.ccpocket.app.data.VoiceSendFlow.Event.TapCancel
import dev.ccpocket.app.data.VoiceSendFlow.Event.TapDone
import dev.ccpocket.app.data.VoiceSendFlow.Event.TapEdit
import dev.ccpocket.app.data.VoiceSendFlow.Event.TapSend
import dev.ccpocket.app.data.VoiceSendFlow.Event.TranscriptFailed
import dev.ccpocket.app.data.VoiceSendFlow.Event.TranscriptFinal
import dev.ccpocket.app.data.VoiceComposerReason.DISCONNECTED
import dev.ccpocket.app.data.VoiceComposerReason.NOT_ADOPTED
import dev.ccpocket.app.data.VoiceComposerReason.NOT_SENT
import dev.ccpocket.app.data.VoiceComposerReason.REVIEW
import dev.ccpocket.app.data.VoiceComposerReason.TIMEOUT
import dev.ccpocket.app.data.VoiceComposerReason.UNAVAILABLE
import dev.ccpocket.protocol.TextEdit
import dev.ccpocket.protocol.TranscriptRefineError
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Voice input v2 send decision (docs/design/VOICE-INPUT-V2-REVIEW.md §11): driven only through [VoiceSendFlow.on]. */
class VoiceSendFlowTest {

    private val origin = VoiceOrigin("mac-1", "convo-A")
    private val id = "cap-1"
    private val original = "请比较 cloud 和 Claude 的输出"
    private val refinedText = "请比较 Claude 和 Claude 的输出"
    private val edit = listOf(TextEdit("cloud", "Claude"))

    private fun refined(
        ok: Boolean = true, text: String = refinedText, edits: List<TextEdit> = edit, error: String? = null,
        autoSend: Boolean = true, eligibleNow: Boolean = true, captureId: String = id,
    ) = Event.Refined(captureId, ok, text, edits, error, autoSend, eligibleNow)

    private fun flow(sendBar: Boolean = true) = VoiceSendFlow().also { it.on(Start(id, origin, sendBar)) }

    /** A send-bar capture tapped ✓ with its final transcript in: the refine is outstanding. */
    private fun refining(text: String = original): VoiceSendFlow = flow().also {
        it.on(TapSend(eligibleNow = true))
        assertEquals(listOf(RequestRefine(id, text), ShowRefining(text)), it.on(TranscriptFinal(text)))
    }

    /** The refined answer with one edit arrived: the highlight hold is running. */
    private fun holding(): VoiceSendFlow = refining().also {
        assertEquals(listOf(ShowPreview(refinedText, listOf(4 until 10))), it.on(refined()))
    }

    private fun composer(text: String, reason: VoiceComposerReason? = null) = ToComposer(origin, text, reason)
    private fun noSend(effects: List<Effect>) =
        assertTrue(effects.none { it is SubmitSend || it is RequestRefine }, "unexpected send path: $effects")

    // ── rule 4: happy path ──

    @Test fun noEditsSendsTheDaemonTextAtOnce() {
        val f = refining()
        assertEquals(listOf(SubmitSend(id, original)), f.on(refined(text = original, edits = emptyList())))
        assertEquals(VoiceSendFlow.Phase.SUBMITTED, f.phase)
        assertEquals(listOf(Finished(id)), f.on(SendResult(id, accepted = true)))
        assertNull(f.phase)
    }

    @Test fun editsArePreviewedThenSentWhenTheHoldEnds() {
        val f = holding()
        assertEquals(listOf(SubmitSend(id, refinedText)), f.on(HoldElapsed(id, eligibleNow = true)))
    }

    @Test fun tapBeforeTheFinalTranscriptWaitsForIt() {
        val f = flow()
        assertEquals(emptyList(), f.on(TapSend(eligibleNow = true)))
        assertEquals(VoiceSendFlow.Phase.AWAITING_FINAL, f.phase)
        assertEquals(listOf(RequestRefine(id, original), ShowRefining(original)), f.on(TranscriptFinal("  $original\n")))
    }

    // ── rule 1: the only authorisation is TapSend(eligible) on a send bar ──

    @Test fun finalWithoutATapLandsInTheComposer() {
        val f = flow()
        f.on(Partial("请比较"))
        assertEquals(listOf(composer(original), Finished(id)), f.on(TranscriptFinal(original)))
    }

    @Test fun tapSendOnTodaysBarOrWhileIneligibleIsTapDone() {
        for ((sendBar, eligible) in listOf(false to true, true to false, false to false)) {
            val f = flow(sendBar)
            assertEquals(emptyList(), f.on(TapSend(eligible)))
            assertEquals(listOf(composer(original), Finished(id)), f.on(TranscriptFinal(original)))
        }
    }

    @Test fun tapDoneLandsWithoutAReason() {
        val f = flow()
        f.on(TapDone)
        assertEquals(listOf(composer(original), Finished(id)), f.on(TranscriptFinal(original)))
    }

    @Test fun capReachedIsAnEditAndALaterTapSendDoesNotReauthorise() {
        val f = flow()
        assertEquals(emptyList(), f.on(CapReached))
        noSend(f.on(TapSend(eligibleNow = true)))
        assertEquals(listOf(composer(original), Finished(id)), f.on(TranscriptFinal(original)))
    }

    @Test fun retryResultAfterAFailureNeverSends() {
        val f = flow()
        f.on(TapSend(eligibleNow = true))
        assertEquals(listOf(TranscribeFailed), f.on(TranscriptFailed))
        assertEquals(listOf(composer(original), Finished(id)), f.on(TranscriptFinal(original)))
    }

    @Test fun failureThenCancelDiscards() {
        val f = flow()
        f.on(TapSend(eligibleNow = true)); f.on(TranscriptFailed)
        assertEquals(listOf(Discard, Finished(id)), f.on(TapCancel))
    }

    @Test fun downgradeWhileRecordingMakesTheCaptureEditOnly() {
        val f = flow()
        assertEquals(emptyList(), f.on(Downgrade(VoiceDowngrade.ComposerChanged)))
        noSend(f.on(TapSend(eligibleNow = true)))
        assertEquals(listOf(composer(original), Finished(id)), f.on(TranscriptFinal(original)))
    }

    // ── daemon autoSend ──

    @Test fun refinedWithoutAutoSendGoesToReviewEvenWithoutEdits() {
        assertEquals(listOf(composer(refinedText, REVIEW), Finished(id)), refining().on(refined(autoSend = false)))
        assertEquals(
            listOf(composer(original, REVIEW), Finished(id)),
            refining().on(refined(text = original, edits = emptyList(), autoSend = false, eligibleNow = false)),
        )
    }

    @Test fun unrebuildableHighlightIsNotAdoptedBeforeAutoSendIsConsidered() {
        val bad = refined(text = "完全不同的文字", autoSend = false)
        assertEquals(listOf(composer(original, NOT_ADOPTED), Finished(id)), refining().on(bad))
        assertEquals(listOf(composer(original, NOT_ADOPTED), Finished(id)), refining().on(bad.copy(autoSend = true)))
    }

    // ── rule 5: ineligible at answer or hold end ──

    @Test fun ineligibleAtAnswerLandsTheRefinedText() {
        assertEquals(listOf(composer(refinedText), Finished(id)), refining().on(refined(eligibleNow = false)))
        assertEquals(
            listOf(composer(original), Finished(id)),
            refining().on(refined(text = original, edits = emptyList(), eligibleNow = false)),
        )
    }

    @Test fun ineligibleAtHoldEndLandsTheRefinedText() {
        assertEquals(listOf(composer(refinedText), Finished(id)), holding().on(HoldElapsed(id, eligibleNow = false)))
    }

    // ── rule 2: at most one SubmitSend ──

    @Test fun duplicatesAfterSubmitChangeNothing() {
        val f = holding()
        assertEquals(listOf(SubmitSend(id, refinedText)), f.on(HoldElapsed(id, true)))
        for (e in listOf(HoldElapsed(id, true), refined(), TapEdit, TapSend(true), CapReached, TapCancel, RefineDeadline(id))) {
            assertEquals(emptyList(), f.on(e), "$e")
        }
    }

    @Test fun editRacingTheHoldWinsWhenFirst() {
        val f = holding()
        assertEquals(listOf(composer(refinedText), Finished(id)), f.on(TapEdit))
        assertEquals(emptyList(), f.on(HoldElapsed(id, true)))
    }

    @Test fun replayedStartCannotReArmASentCapture() {
        val f = refining()
        f.on(refined(text = original, edits = emptyList()))
        f.on(SendResult(id, true))
        assertEquals(emptyList(), f.on(Start(id, origin, sendBar = true)))
        noSend(f.on(TapSend(true)) + f.on(TranscriptFinal(original)) + f.on(refined(text = original, edits = emptyList())))
    }

    // ── rules 3, 6, 7: edit / deadline make the capture edit-only for good ──

    @Test fun editWhileRefiningLandsTheOriginalAndCancels() {
        val f = refining()
        assertEquals(listOf(CancelRefine(id), composer(original), Finished(id)), f.on(TapEdit))
        assertEquals(emptyList(), f.on(refined()))
    }

    @Test fun editBeforeTheFinalLandsItWhenItArrives() {
        val f = flow()
        f.on(TapSend(true))
        assertEquals(emptyList(), f.on(TapEdit))
        assertEquals(listOf(composer(original), Finished(id)), f.on(TranscriptFinal(original)))
    }

    @Test fun deadlineLandsTheOriginalWithTimeoutAndIgnoresTheLateAnswer() {
        val f = refining()
        assertEquals(listOf(CancelRefine(id), composer(original, TIMEOUT), Finished(id)), f.on(RefineDeadline(id)))
        assertEquals(emptyList(), f.on(refined()))
        assertEquals(emptyList(), f.on(RefineDeadline(id)))
    }

    @Test fun capReachedWhileRefiningIsAnEdit() {
        assertEquals(listOf(CancelRefine(id), composer(original), Finished(id)), refining().on(CapReached))
    }

    // ── rule 8: refine failures ──

    @Test fun refineFailureCodesMapToReasons() {
        val cases = mapOf(
            TranscriptRefineError.INVALID to NOT_ADOPTED,
            TranscriptRefineError.TIMEOUT to TIMEOUT,
            TranscriptRefineError.UNAVAILABLE to UNAVAILABLE,
            TranscriptRefineError.FAILED to UNAVAILABLE,
            TranscriptRefineError.SUPERSEDED to UNAVAILABLE,
            "some-future-code" to UNAVAILABLE,
            null to UNAVAILABLE,
        )
        for ((code, reason) in cases) {
            assertEquals(
                listOf(composer(original, reason), Finished(id)),
                refining().on(refined(ok = false, text = "", edits = emptyList(), error = code)), "$code",
            )
        }
    }

    // ── rules 9–11: downgrades ──

    @Test fun disconnectWhileRefiningKeepsTheOriginal() {
        assertEquals(
            listOf(CancelRefine(id), composer(original, DISCONNECTED), Finished(id)),
            refining().on(Downgrade(VoiceDowngrade.Disconnected)),
        )
    }

    @Test fun disconnectDuringHoldKeepsTheOriginalWithoutCancel() {
        assertEquals(listOf(composer(original, DISCONNECTED), Finished(id)), holding().on(Downgrade(VoiceDowngrade.Disconnected)))
    }

    @Test fun leavingKeepsTheFinalTheThenPartialElseDiscards() {
        assertEquals(
            listOf(CancelRefine(id), composer(original), Finished(id)),
            refining().on(Downgrade(VoiceDowngrade.LeftConversation)),
        )
        val withPartial = flow().also { it.on(Partial("  请比较 ")); it.on(TapSend(true)) }
        assertEquals(listOf(composer("请比较"), Finished(id)), withPartial.on(Downgrade(VoiceDowngrade.LeftConversation)))
        val blankPartial = flow().also { it.on(Partial("  ")); it.on(TapSend(true)) }
        assertEquals(listOf(Discard, Finished(id)), blankPartial.on(Downgrade(VoiceDowngrade.LeftConversation)))
        val audioOnly = flow().also { it.on(TapSend(true)) }
        assertEquals(listOf(Discard, Finished(id)), audioOnly.on(Downgrade(VoiceDowngrade.Disconnected)))
    }

    @Test fun otherDowngradesLandTheOriginalOnceTextExists() {
        for (cause in listOf(VoiceDowngrade.Backgrounded, VoiceDowngrade.ComposerChanged, VoiceDowngrade.EligibilityLost)) {
            assertEquals(listOf(CancelRefine(id), composer(original), Finished(id)), refining().on(Downgrade(cause)), "$cause")
            assertEquals(listOf(composer(original), Finished(id)), holding().on(Downgrade(cause)), "$cause")
            val waiting = flow().also { it.on(TapSend(true)) }
            assertEquals(emptyList(), waiting.on(Downgrade(cause)), "$cause")
            assertEquals(listOf(composer(original), Finished(id)), waiting.on(TranscriptFinal(original)), "$cause")
        }
    }

    // ── rule 12: after submit ──

    @Test fun refusedSubmitLandsTheTextThatWasToBeSent() {
        val f = holding()
        f.on(HoldElapsed(id, true))
        assertEquals(listOf(composer(refinedText, NOT_SENT), Finished(id)), f.on(SendResult(id, accepted = false)))
    }

    @Test fun downgradesAfterSubmitNeverLandText() {
        for (cause in VoiceDowngrade.entries) {
            val f = refining()
            f.on(refined(text = original, edits = emptyList()))
            assertEquals(emptyList(), f.on(Downgrade(cause)), "$cause")
            assertEquals(listOf(Finished(id)), f.on(SendResult(id, true)))
        }
    }

    // ── rule 13: foreign and duplicate answers ──

    @Test fun answerForAnotherCaptureOrASecondAnswerIsIgnored() {
        val f = refining()
        assertEquals(emptyList(), f.on(refined(captureId = "cap-other")))
        assertEquals(emptyList(), f.on(RefineDeadline("cap-other")))
        assertEquals(listOf(ShowPreview(refinedText, listOf(4 until 10))), f.on(refined()))
        assertEquals(emptyList(), f.on(refined(text = original, edits = emptyList())))
    }

    // ── rules 14, 15 ──

    @Test fun blankFinalIsNoSpeechWhateverTheIntent() {
        for (tap in listOf<Event?>(null, TapSend(true), TapDone, TapEdit)) {
            val f = flow()
            tap?.let { f.on(it) }
            assertEquals(listOf(NoSpeech, Finished(id)), f.on(TranscriptFinal("  \n")), "$tap")
            assertNull(f.phase)
        }
    }

    @Test fun cancelBeforeSubmitDiscards() {
        assertEquals(listOf(Discard, Finished(id)), flow().on(TapCancel))
        assertEquals(listOf(CancelRefine(id), Discard, Finished(id)), refining().on(TapCancel))
        assertEquals(listOf(Discard, Finished(id)), holding().on(TapCancel))
    }

    // ── rule 16: frozen origin ──

    @Test fun composerTargetIsTheFrozenOrigin() {
        val f = VoiceSendFlow()
        val first = VoiceOrigin("mac-1", "convo-A")
        f.on(Start("c1", first, sendBar = true))
        f.on(TapSend(true)); f.on(TranscriptFinal(original))
        val effects = f.on(Downgrade(VoiceDowngrade.LeftConversation))
        assertEquals(first, effects.filterIsInstance<ToComposer>().single().origin)
        // A new capture in another conversation gets its own origin; the old one's events no longer apply.
        val second = VoiceOrigin("mac-2", "convo-B")
        f.on(Start("c2", second, sendBar = false))
        assertEquals(listOf(ToComposer(second, original, null), Finished("c2")), f.on(TranscriptFinal(original)))
    }

    @Test fun newStartSupersedesAnUnfinishedCapture() {
        val f = refining()
        assertEquals(listOf(CancelRefine(id), Finished(id)), f.on(Start("cap-2", origin, sendBar = true)))
        assertEquals(emptyList(), f.on(refined()))
    }

    // ── property: random sequences ──

    @Test fun randomSequencesNeverSendTwiceOrWithoutAuthorisation() {
        val ids = listOf("a", "b", "c")
        val texts = listOf(original, refinedText, "", "  ", "看一下 cloud 日志")
        val edits = listOf(emptyList(), edit, listOf(TextEdit("日志", "log")), listOf(TextEdit("missing", "x")))
        val codes = listOf(null, TranscriptRefineError.INVALID, TranscriptRefineError.TIMEOUT, "zzz")
        repeat(20_000) { round ->
            val rnd = Random(round)
            val flow = VoiceSendFlow()
            val sent = HashMap<String, Int>()
            val authorised = HashSet<String>() // TapSend(true) on a send bar, before any downgrade/edit
            val autoSendSeen = HashSet<String>() // a Refined(ok, autoSend) for that capture
            val sendBars = HashMap<String, Boolean>()
            var current: String? = null
            fun pick() = ids[rnd.nextInt(ids.size)]
            repeat(rnd.nextInt(1, 25)) {
                val event: Event = when (rnd.nextInt(16)) {
                    0 -> pick().let { Start(it, VoiceOrigin("m", it), rnd.nextBoolean()) }
                    1 -> TapSend(rnd.nextBoolean())
                    2 -> TapDone
                    3 -> TapEdit
                    4 -> TapCancel
                    5 -> Partial(texts.random(rnd))
                    6 -> TranscriptFinal(texts.random(rnd))
                    7 -> TranscriptFailed
                    8 -> CapReached
                    9, 10 -> Event.Refined(
                        pick(), rnd.nextBoolean(), texts.random(rnd), edits.random(rnd), codes.random(rnd),
                        rnd.nextBoolean(), rnd.nextBoolean(),
                    )
                    11 -> RefineDeadline(pick())
                    12, 13 -> HoldElapsed(pick(), rnd.nextBoolean())
                    14 -> Downgrade(VoiceDowngrade.entries.random(rnd))
                    else -> SendResult(pick(), rnd.nextBoolean())
                }
                // Model of what the flow may treat as authorised — deliberately a superset of the flow's rules.
                if (event is Start && event.captureId !in sendBars) { sendBars[event.captureId] = event.sendBar; current = event.captureId }
                if (event is TapSend && event.eligibleNow && current?.let { sendBars[it] } == true) authorised += current!!
                if (event is Event.Refined && event.ok && event.autoSend) autoSendSeen += event.captureId
                for (effect in flow.on(event)) {
                    val cid = when (effect) { is SubmitSend -> effect.captureId; is RequestRefine -> effect.captureId; else -> null }
                    if (cid != null && cid !in authorised) fail("round $round: $effect without TapSend(true) on a send bar")
                    if (effect is SubmitSend) {
                        if (cid !in autoSendSeen) fail("round $round: $effect without a Refined(autoSend = true)")
                        if ((sent.merge(effect.captureId, 1, Int::plus) ?: 0) > 1) fail("round $round: second SubmitSend for $cid")
                    }
                }
            }
        }
    }

    @Test fun randomSequencesNeverSendAfterADowngradeOrEdit() {
        val demoting = listOf(TapEdit, CapReached) + VoiceDowngrade.entries.map { Downgrade(it) }
        repeat(5_000) { round ->
            val rnd = Random(round)
            val f = flow()
            val prefix = listOf(TapSend(true), TranscriptFinal(original)).take(rnd.nextInt(3))
            prefix.forEach { f.on(it) }
            // The host starts the deadline only on RequestRefine; an earlier one is stale and ignored.
            f.on((if (prefix.size == 2) demoting + RefineDeadline(id) else demoting).random(rnd))
            val tail = listOf(
                TapSend(true), TranscriptFinal(original), refined(), refined(text = original, edits = emptyList()),
                HoldElapsed(id, true), Partial(original),
            )
            repeat(8) { noSend(f.on(tail.random(rnd))) }
        }
    }
}
