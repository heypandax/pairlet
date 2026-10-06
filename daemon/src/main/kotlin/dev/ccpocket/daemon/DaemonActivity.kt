package dev.ccpocket.daemon

import dev.ccpocket.daemon.execution.ExecutionRunPlane
import dev.ccpocket.daemon.memo.VoiceMemoService
import dev.ccpocket.daemon.schedule.SchedulerService
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.daemon.transcribe.TranscriptRefineService

/**
 * The auto-update idle gate: is this daemon doing anything a process exit would destroy?
 *
 * Sessions alone are not enough. Work that runs beside them, or before one exists:
 *  - remote execution: a run queued behind its grant's concurrency ceiling has no session yet and its
 *    prompt only in memory, so an exit loses it. [executionPlane] is read as given; null (not loaded) means
 *    no run can exist in this process, and the gate must never load it;
 *  - scheduled tasks: a fire that is due or about to be (the session it opens is covered once it is busy);
 *  - chat dictation and voice memos: whisper / organiser jobs owned by no session;
 *  - a dictation being proofread (voice input v2): the phone is waiting to send its result.
 */
internal object DaemonActivity {
    suspend fun busy(
        registry: SessionRegistry,
        executionPlane: ExecutionRunPlane?,
        scheduler: SchedulerService,
        transcribe: TranscribeService,
        voiceMemo: VoiceMemoService,
        transcriptRefine: TranscriptRefineService,
    ): Boolean =
        registry.hasActiveWork() ||
            executionPlane?.hasLiveRuns() == true ||
            scheduler.hasImminentWork() ||
            transcribe.isTranscribing() ||
            voiceMemo.activeJobs() > 0 ||
            transcriptRefine.isRefining()
}
