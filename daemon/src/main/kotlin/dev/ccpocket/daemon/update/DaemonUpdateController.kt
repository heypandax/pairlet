package dev.ccpocket.daemon.update

import dev.ccpocket.protocol.DaemonUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.system.exitProcess

/** Owner-requested updates share the existing installer, verification and auto-updater exclusion. */
class DaemonUpdateController(
    private val isBusy: suspend () -> Boolean,
    private val mode: () -> String = { UpdateState.updateMode },
    private val prepare: suspend () -> PreparedUpdate? = {
        val install = UpdateService.managedInstallOf(UpdateService.selfExe())
        check(modeFor(install, System.getProperty("os.name")) == "remote")
        val latest = UpdateService.latestRelease() ?: error("release check unavailable")
        UpdateState.recordLatest(latest.version)
        if (!UpdateService.isNewer(latest.version, UpdateService.currentVersion())) null
        else PreparedUpdate(latest.version) { UpdateService.apply(latest, install!!) }
    },
    private val restart: suspend () -> Unit = { exitProcess(0) },
    private val pause: suspend () -> Unit = { delay(1_000) },
    private val tryClaim: () -> Boolean = UpdateState::tryBeginApply,
    private val releaseClaim: () -> Unit = UpdateState::finishApply,
) {
    class PreparedUpdate(val version: String, val apply: suspend () -> Unit)

    suspend fun request(requestId: String, emit: suspend (DaemonUpdateStatus) -> Unit) {
        if (requestId.isBlank() || requestId.length > 128) return
        var targetVersion: String? = null
        var ownsUpdate = false
        suspend fun publish(state: String) {
            if (ownsUpdate) UpdateState.recordPhase(state, targetVersion)
            // A disconnected requester must not interrupt a verified install already in progress.
            val blocked = UpdateState.controlsBlocked && state in setOf("checking", "downloading", "waiting", "restarting", "in_progress")
            try { emit(DaemonUpdateStatus(requestId, state, targetVersion, blocked)) }
            catch (_: Exception) { currentCoroutineContext().ensureActive() }
        }
        suspend fun busy(): Boolean = try { isBusy() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { true }

        if (mode() != "remote") { publish("manual"); return }
        if (!tryClaim()) { publish("in_progress"); return }
        ownsUpdate = true
        try {
            if (busy()) { publish("busy"); return }
            UpdateState.blockControls()
            if (busy()) { publish("busy"); return }
            publish("checking")
            val update = withContext(Dispatchers.IO) { prepare() }
            if (update == null) { publish("up_to_date"); return }
            targetVersion = update.version
            // A task can have started during the release check. Leave it untouched.
            if (busy()) { publish("busy"); return }
            publish("downloading")
            withContext(Dispatchers.IO) { update.apply() }
            var announcedWaiting = false
            while (busy()) {
                if (!announcedWaiting) { publish("waiting"); announcedWaiting = true }
                pause() // no forced timeout: a manual phone update never cuts off ongoing work
            }
            publish("restarting")
            restart() // an anchored launchd/systemd service relaunches through the updated launcher
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            publish("failed")
        } finally {
            releaseClaim()
        }
    }

    companion object {
        fun modeFor(install: UpdateService.ManagedInstall?, os: String): String =
            if (install?.serviceAnchored == true &&
                (os.contains("mac", ignoreCase = true) || os.contains("linux", ignoreCase = true))) "remote" else "manual"
    }
}
