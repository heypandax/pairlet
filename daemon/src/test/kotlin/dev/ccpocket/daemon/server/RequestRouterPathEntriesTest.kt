package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.ListPathEntries
import dev.ccpocket.protocol.PathEntries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The #176 gate on [PathEntries.roots]: filesystem roots ride ONLY an OWNER's reply to the "~"
 * home-anchor listing. An @-completion reply (real absolute workdir) must not carry them, and a guest
 * must never receive them even if a "~" frame somehow slipped past GuestGuard (defence in depth — the
 * guard denies the anchor outright, see GuestGuardTest).
 */
class RequestRouterPathEntriesTest {

    private fun router(scope: CoroutineScope): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-router-roots").toFile()
        return RequestRouter(
            registry = SessionRegistry(scope, backends = emptyMap()),
            dirs = DirectoryService(),
            transcribe = TranscribeService(scope) { null },
            inbox = FileInboxService { null },
            shell = ShellService(scope),
            exports = FileExportService(scope, { null }),
            scope = scope,
            auth = AuthService(scope, { emptyList() }, { 0 }),
            prefs = DaemonPrefs.load(tmp.resolve("prefs.json")),
            presets = PresetService(PresetStore.load(tmp.resolve("presets.json")), { emptyList() }, { 0 }),
            scheduler = dev.ccpocket.daemon.schedule.SchedulerService(
                dev.ccpocket.daemon.schedule.ScheduleStore.load(tmp.resolve("schedules.json")),
                executor = { null },
            ),
        )
    }

    /** Route one ListPathEntries and await its (launch-emitted) PathEntries reply. */
    private fun reply(frame: ListPathEntries, guestScope: GuestScope? = null): PathEntries = runBlocking {
        val got = CompletableDeferred<PathEntries>()
        router(CoroutineScope(Dispatchers.Default)).handle(
            frame,
            { f -> if (f is PathEntries) got.complete(f) },
            guestScope = guestScope,
        )
        withTimeout(5_000) { got.await() }
    }

    @Test
    fun the_owner_home_anchor_reply_carries_the_filesystem_roots() {
        val r = reply(ListPathEntries("~"))
        assertTrue(r.ok, "the home dir must list")
        assertTrue(r.roots.isNotEmpty(), "the '~' anchor reply is where the switcher learns the roots")
    }

    @Test
    fun a_real_workdir_reply_carries_no_roots() {
        // @-completion (and root-drilling itself) uses absolute workdirs — no roots on those replies
        val wd = Files.createTempDirectory("ccp-real-wd").toRealPath().toString()
        val r = reply(ListPathEntries(wd))
        assertTrue(r.ok)
        assertTrue(r.roots.isEmpty(), "roots must ride only the '~' anchor reply")
    }

    @Test
    fun a_guest_never_receives_roots_even_on_a_home_anchor_frame() {
        val scope = GuestScope(
            roots = listOf(Files.createTempDirectory("ccp-share").toRealPath().toString()),
            ownedSessions = emptySet(), label = "alex", expiresAt = null, tier = AccessTier.COLLABORATE,
        )
        val r = reply(ListPathEntries("~"), guestScope = scope)
        assertTrue(r.roots.isEmpty(), "a guest reply must never enumerate the disk layout")
    }
}
