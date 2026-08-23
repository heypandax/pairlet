package dev.ccpocket.daemon.server

import dev.ccpocket.daemon.DaemonPrefs
import dev.ccpocket.daemon.bridge.BridgeCaps
import dev.ccpocket.daemon.bridge.GuestCaps
import dev.ccpocket.daemon.bridge.GuestScope
import dev.ccpocket.daemon.claude.AuthService
import dev.ccpocket.daemon.disk.DirectoryService
import dev.ccpocket.daemon.disk.FileExportService
import dev.ccpocket.daemon.disk.FileInboxService
import dev.ccpocket.daemon.handoff.CollaboratorCaps
import dev.ccpocket.daemon.handoff.CollaboratorScope
import dev.ccpocket.daemon.presets.PresetService
import dev.ccpocket.daemon.presets.PresetStore
import dev.ccpocket.daemon.session.SessionRegistry
import dev.ccpocket.daemon.shell.ShellService
import dev.ccpocket.daemon.transcribe.TranscribeService
import dev.ccpocket.protocol.AccessTier
import dev.ccpocket.protocol.AddWorktree
import dev.ccpocket.protocol.CollaboratorPurpose
import dev.ccpocket.protocol.FetchGitStatus
import dev.ccpocket.protocol.Frame
import dev.ccpocket.protocol.GIT_OP_STAGE
import dev.ccpocket.protocol.GitAction
import dev.ccpocket.protocol.GitActionResult
import dev.ccpocket.protocol.GitDiff
import dev.ccpocket.protocol.GitStatus
import dev.ccpocket.protocol.HandoffAccess
import dev.ccpocket.protocol.ListWorktrees
import dev.ccpocket.protocol.ReadGitDiff
import dev.ccpocket.protocol.RemoveWorktree
import dev.ccpocket.protocol.ToDaemon
import dev.ccpocket.protocol.ToPhone
import dev.ccpocket.protocol.WorktreeList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Git panel's OWNER-ONLY red line (#280 §3.1, #281 §5), tested at the router — the second door.
 *
 * The first door is the ingress capability whitelist (GuestCaps / BridgeCaps / CollaboratorCaps), which
 * default-denies anything not explicitly listed; [git_frames_are_denied_by_every_capability_whitelist]
 * pins that these types stayed off those lists. But a whitelist is a list someone can edit, so the
 * router re-checks all THREE credential classes at dispatch and refuses with a frame the app can render
 * rather than with silence.
 *
 * The collaborator case is the one that matters most: a Collaborator Link arrives with `origin == null`
 * AND `guestScope == null`, so the older two-term owner test would have waved through exactly the
 * weakest credential the product hands out.
 */
class RequestRouterGitTest {

    private fun router(scope: CoroutineScope): RequestRouter {
        val tmp = Files.createTempDirectory("ccp-router-git").toFile()
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

    private val workdir = Files.createTempDirectory("ccp-git-wd").toRealPath().toString()

    private fun reply(
        frame: Frame,
        origin: String? = null,
        guestScope: GuestScope? = null,
        collabScope: CollaboratorScope? = null,
    ): Frame = runBlocking {
        val got = CompletableDeferred<Frame>()
        router(CoroutineScope(Dispatchers.Default)).handle(
            frame,
            { f -> if (f is GitStatus || f is GitDiff || f is GitActionResult || f is WorktreeList) got.complete(f) },
            origin = origin, guestScope = guestScope, collabScope = collabScope,
        )
        withTimeout(10_000) { got.await() }
    }

    private fun guest() = GuestScope(
        roots = listOf(workdir), ownedSessions = emptySet(),
        label = "alex", expiresAt = null, tier = AccessTier.COLLABORATE,
    )

    private fun collaborator() = CollaboratorScope(
        deviceId = "dev-1", pathScope = listOf(workdir), access = HandoffAccess.REVIEW_READ_ONLY,
    )

    private fun refusalOf(f: Frame): String? = when (f) {
        is GitStatus -> f.error.takeIf { !f.ok }
        is GitDiff -> f.error.takeIf { !f.ok }
        is GitActionResult -> f.error.takeIf { !f.ok }
        is WorktreeList -> f.error.takeIf { !f.ok }
        else -> null
    }

    /** Every git/worktree request, in the shape the phone sends it. */
    private fun everyGitRequest(): List<Frame> = listOf(
        FetchGitStatus("c1", workdir),
        ReadGitDiff("c1", workdir, "src/a.kt"),
        GitAction("c1", workdir, GIT_OP_STAGE, paths = listOf("src/a.kt")),
        ListWorktrees("c1", workdir),
        AddWorktree("c1", workdir, "feat/x", createBranch = true),
        RemoveWorktree("c1", workdir, "$workdir-worktrees/feat-x"),
    )

    @Test
    fun a_bridge_credential_is_refused_on_every_git_frame_reads_included() {
        for (req in everyGitRequest()) {
            val r = reply(req, origin = "feishu:group-1")
            assertEquals(RequestRouter.GIT_OWNER_ONLY, refusalOf(r), "${req::class.simpleName} must be refused for a bridge")
        }
    }

    @Test
    fun a_scoped_guest_is_refused_on_every_git_frame_reads_included() {
        // deliberately scoped to the very workdir it is asking about: the refusal is about the SURFACE
        // (whole-repository state) being wider than a share, not about the path.
        for (req in everyGitRequest()) {
            val r = reply(req, origin = "alex", guestScope = guest())
            assertEquals(RequestRouter.GIT_OWNER_ONLY, refusalOf(r), "${req::class.simpleName} must be refused for a guest")
        }
    }

    @Test
    fun a_collaborator_link_is_refused_even_though_its_origin_and_guestScope_are_both_null() {
        for (req in everyGitRequest()) {
            val r = reply(req, origin = null, guestScope = null, collabScope = collaborator())
            assertEquals(RequestRouter.GIT_OWNER_ONLY, refusalOf(r), "${req::class.simpleName} must be refused for a collaborator")
        }
    }

    @Test
    fun the_refusal_never_reveals_whether_the_path_exists() {
        // a directory oracle would be a real leak: a guest could probe the owner's disk by watching which
        // refusal came back. Both a real and an imaginary path must answer identically.
        val real = reply(FetchGitStatus("c1", workdir), guestScope = guest())
        val fake = reply(FetchGitStatus("c1", "/no/such/place/at/all"), guestScope = guest())
        assertEquals(refusalOf(real), refusalOf(fake))
    }

    @Test
    fun the_owner_gets_through_and_a_bad_workdir_is_a_different_refusal() {
        // owner + real dir: reaches GitService, which answers notARepo for a plain temp directory
        val ok = reply(FetchGitStatus("c1", workdir)) as GitStatus
        assertTrue(ok.ok)
        assertTrue(ok.notARepo)

        // owner + nonexistent dir: refused, but by the workdir validator, not the credential gate
        val bad = reply(FetchGitStatus("c1", "/no/such/place/at/all")) as GitStatus
        assertFalse(bad.ok)
        assertTrue(bad.error.orEmpty().startsWith("not a readable directory"), bad.error.orEmpty())
    }

    @Test
    fun the_owner_test_is_all_three_credential_classes() {
        assertTrue(RequestRouter.gitOwnerOnly(null, null, null))
        assertFalse(RequestRouter.gitOwnerOnly("feishu:g", null, null))
        assertFalse(RequestRouter.gitOwnerOnly(null, guest(), null))
        // the vacuous case the two-term test used to miss
        assertFalse(RequestRouter.gitOwnerOnly(null, null, collaborator()))
    }

    @Test
    fun git_frames_are_denied_by_every_capability_whitelist() {
        val requests = everyGitRequest().map { it as ToDaemon }
        for (f in requests) {
            assertFalse(GuestCaps.ingressAllowed(f), "guest ingress must not admit ${f::class.simpleName}")
            assertFalse(BridgeCaps.ingressAllowed(f), "bridge ingress must not admit ${f::class.simpleName}")
            for (purpose in CollaboratorPurpose.entries) {
                assertFalse(
                    CollaboratorCaps.ingressAllowed(f, purpose),
                    "collaborator ($purpose) ingress must not admit ${f::class.simpleName}",
                )
            }
        }
        // and the replies never leave for a restricted credential either — a refusal frame the router
        // built for an owner must not become a side channel if routing ever changes.
        val replies: List<ToPhone> = listOf(
            GitStatus("c1", workdir),
            GitDiff("c1", workdir, "a.kt"),
            GitActionResult("c1", GIT_OP_STAGE),
            WorktreeList("c1", workdir),
            dev.ccpocket.protocol.GitActionPreview("c1", GIT_OP_STAGE, "tok"),
        )
        for (f in replies) {
            assertFalse(GuestCaps.egressAllowed(f), "guest egress must not admit ${f::class.simpleName}")
            assertFalse(BridgeCaps.egressAllowed(f), "bridge egress must not admit ${f::class.simpleName}")
        }
    }
}
