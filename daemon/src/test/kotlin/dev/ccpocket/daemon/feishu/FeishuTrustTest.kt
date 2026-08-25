package dev.ccpocket.daemon.feishu

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The versioned trust store: migration, fail-closed reads, distinguishable writes, snapshots. */
class FeishuTrustTest {
    private val tmp: File = Files.createTempDirectory("ccp-trust").toFile()
    private val f = File(tmp, "trust.json")

    @AfterTest fun cleanup() {
        // some tests drop the dir's write bit — restore it so deleteRecursively can actually clean up
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rwxr-xr-x")) }
        tmp.deleteRecursively()
    }

    // ── migration ──

    @Test
    fun legacy_chatId_to_workdir_map_is_retained_but_requires_new_full_confirmation() {
        // the old /trust promised a restricted ceiling; keep the row visible but execute fail-closed
        f.writeText("""{"oc_1":"/p/alpha","oc_2":"/p/Beta"}""")
        val t = FeishuTrust(f)
        assertFalse(t.isTrusted("oc_1", "/p/alpha"))
        assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_2", "/p/Beta"))
        assertEquals(FeishuTrustMode.TRUSTED, t.recordFor("oc_1")?.mode)
        assertFalse(t.recordFor("oc_1")?.fullAuthorityConfirmed ?: true)
        assertEquals(1, t.recordFor("oc_1")?.contractVersion)
        assertNull(t.recordFor("oc_1")?.purpose)
        // migration is lazy: the file is not rewritten by the read itself…
        assertTrue("version" !in f.readText())
        // …but the next successful explicit write regenerates it as v3 WITH the migrated rows intact
        assertEquals(TrustWrite.CHANGED, t.setReviewed("oc_3", "/p/gamma", null))
        val reloaded = FeishuTrust(f)
        assertFalse(reloaded.isTrusted("oc_1", "/p/alpha"))
        assertEquals(FeishuTrustMode.REVIEWED, reloaded.modeFor("oc_3", "/p/gamma"))
        assertTrue("\"version\":3" in f.readText())
    }

    @Test
    fun old_v2_trusted_requires_new_full_confirmation_while_reviewed_keeps_its_mode() {
        f.writeText(
            """{"version":2,"chats":{"oc_trusted":{"workdir":"/p/alpha","mode":"TRUSTED","fullAuthorityConfirmed":true,"contractVersion":4},"oc_reviewed":{"workdir":"/p/beta","mode":"REVIEWED","purpose":"只做评审","contractVersion":7}}}""",
        )
        val t = FeishuTrust(f)
        assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_trusted", "/p/alpha"))
        assertEquals(FeishuTrustMode.TRUSTED, t.recordFor("oc_trusted")?.mode)
        assertFalse(t.recordFor("oc_trusted")?.fullAuthorityConfirmed ?: true)
        assertEquals(FeishuTrustMode.REVIEWED, t.modeFor("oc_reviewed", "/p/beta"))
        assertEquals("只做评审", t.recordFor("oc_reviewed")?.purpose)
        assertFalse(t.recordFor("oc_trusted")?.mode == FeishuTrustMode.FULL_AUTO)
        assertFalse(t.recordFor("oc_reviewed")?.mode == FeishuTrustMode.FULL_AUTO)
        assertTrue("\"version\":2" in f.readText(), "a read must not rewrite or migrate the file")
    }

    @Test
    fun v3_legacy_full_auto_normalizes_to_trusted_without_rewriting_the_file() {
        val body =
            """{"version":3,"chats":{"oc_1":{"workdir":"/p/alpha","mode":"FULL_AUTO","purpose":"只用于代码评审","contractVersion":4,"policyRevision":"11111111-1111-4111-8111-111111111111"}}}"""
        f.writeText(body)
        val r = FeishuTrust(f).recordFor("oc_1")!!
        assertEquals(FeishuTrustMode.TRUSTED, r.mode)
        assertFalse(r.fullAuthorityConfirmed)
        assertNull(r.purpose, "TRUSTED has no Guardian contract")
        assertEquals(4, r.contractVersion)
        assertEquals("11111111-1111-4111-8111-111111111111", r.policyRevision)
        assertEquals(body, f.readText(), "compatibility reads must not rewrite owner state")
    }

    @Test
    fun a_v2_file_cannot_smuggle_the_new_full_auto_mode() {
        val body = """{"version":2,"chats":{"oc_1":{"workdir":"/p/alpha","mode":"FULL_AUTO"}}}"""
        f.writeText(body)
        val t = FeishuTrust(f)
        assertEquals(0, t.size())
        assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_1", "/p/alpha"))
        assertEquals(body, f.readText())
    }

    @Test
    fun corrupt_file_fails_closed_and_is_not_overwritten_by_the_read() {
        f.writeText("[ broken")
        val t = FeishuTrust(f)
        assertEquals(0, t.size())
        assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_1", "/p/alpha"))
        assertEquals("[ broken", f.readText(), "a failed read must never destroy the evidence")
    }

    @Test
    fun unsupported_schema_versions_fail_closed_without_touching_the_file() {
        // a future schema may hang new safety conditions on fields this build ignores — applying its rows
        // anyway would grant more than the owner agreed to, so anything but supported integers 2/3 is empty
        for (version in listOf("1", "4", "999", "\"2\"", "\"3\"", "null", "-2")) {
            val body = """{"version":$version,"chats":{"oc_1":{"workdir":"/p/alpha","mode":"TRUSTED"}}}"""
            f.writeText(body)
            val t = FeishuTrust(f)
            assertEquals(0, t.size(), "version=$version must read as empty trust")
            assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_1", "/p/alpha"))
            assertEquals(body, f.readText(), "the read must never rewrite an unsupported file (version=$version)")
        }
    }

    // ── distinguishable writes ──

    @Test
    fun repeat_of_the_same_state_is_UNCHANGED_not_a_failure() {
        val t = FeishuTrust(f)
        assertEquals(TrustWrite.CHANGED, t.trust("oc_1", "/p/alpha"))
        assertTrue(t.recordFor("oc_1")?.fullAuthorityConfirmed == true)
        assertTrue(FeishuTrust(f).isTrusted("oc_1", "/p/alpha"), "current v3 confirmation must survive restart")
        assertEquals(TrustWrite.UNCHANGED, t.trust("oc_1", "/p/alpha"))
        assertEquals(TrustWrite.CHANGED, t.setReviewed("oc_1", "/p/alpha", "评审"))
        assertEquals(TrustWrite.UNCHANGED, t.setReviewed("oc_1", "/p/alpha", "评审"))
        assertEquals(TrustWrite.CHANGED, t.untrust("oc_1"))
        assertEquals(TrustWrite.UNCHANGED, t.untrust("oc_1"))
    }

    @Test
    fun a_write_that_cannot_persist_is_WRITE_FAILED_and_memory_stays_on_disk_truth() {
        val t = FeishuTrust(f)
        t.trust("oc_1", "/p/alpha")
        Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertEquals(TrustWrite.WRITE_FAILED, t.untrust("oc_1"), "an unpersistable revoke must fail loudly")
            assertTrue(t.isTrusted("oc_1", "/p/alpha"), "…and the in-memory state must still match the disk")
            assertEquals(TrustWrite.WRITE_FAILED, t.setReviewed("oc_2", "/p/Beta", null))
            assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_2", "/p/Beta"))
        } finally {
            Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    // ── (chat, project) pairing ──

    @Test
    fun a_rebind_voids_the_mode_instead_of_inheriting() {
        // the /bind authority may be the Feishu GROUP OWNER, not the machine owner — so a chat re-pointed at
        // another allow-listed project must NOT carry reduced approval onto a project nobody trusted it with
        val t = FeishuTrust(f)
        t.setReviewed("oc_1", "/p/alpha", "评审")
        assertEquals(FeishuTrustMode.REVIEWED, t.modeFor("oc_1", "/p/alpha"))
        assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_1", "/p/Beta"))
        // ...and the stale record is still visible, so /untrust and /trust-status can see it
        assertEquals("/p/alpha", t.recordFor("oc_1")?.workdir)
        // re-granting for the new project is an explicit act that replaces the old pair
        assertEquals(TrustWrite.CHANGED, t.trust("oc_1", "/p/Beta"))
        assertEquals(FeishuTrustMode.UNTRUSTED, t.modeFor("oc_1", "/p/alpha"))
        assertEquals(1, t.size())
    }

    @Test
    fun contract_version_grows_on_every_mode_or_purpose_change() {
        val t = FeishuTrust(f)
        t.setReviewed("oc_1", "/p/alpha", null)
        assertEquals(1, t.recordFor("oc_1")?.contractVersion)
        t.setReviewed("oc_1", "/p/alpha", "只做评审")
        assertEquals(2, t.recordFor("oc_1")?.contractVersion)
        t.trust("oc_1", "/p/alpha")
        assertEquals(3, t.recordFor("oc_1")?.contractVersion)
    }

    // ── snapshots for the async-review revoke race ──

    @Test
    fun snapshot_revalidation_catches_untrust_rebind_and_contract_edits() {
        val t = FeishuTrust(f)
        t.setReviewed("oc_1", "/p/alpha", "评审")
        val snap = t.snapshot("oc_1", "/p/alpha")
        assertEquals(FeishuTrustMode.REVIEWED, snap.mode)
        assertTrue(t.stillMatches("oc_1", "/p/alpha", snap))

        // contract edit mid-review → stale
        t.setReviewed("oc_1", "/p/alpha", "评审和测试")
        assertFalse(t.stillMatches("oc_1", "/p/alpha", snap))

        // untrust mid-review → stale
        val snap2 = t.snapshot("oc_1", "/p/alpha")
        t.untrust("oc_1")
        assertFalse(t.stillMatches("oc_1", "/p/alpha", snap2))

        // an UNTRUSTED snapshot of an absent record is stable
        val none = t.snapshot("oc_9", "/p/alpha")
        assertEquals(FeishuTrustMode.UNTRUSTED, none.mode)
        assertTrue(t.stillMatches("oc_9", "/p/alpha", none))
    }

    @Test
    fun revoke_and_regrant_with_identical_args_still_voids_an_old_snapshot() {
        // Pin the wall clock so timestamp-based identity DEFINITELY collides. The persisted UUID must be the
        // only changing identity; no sleeps or scheduler timing make this regression probabilistic.
        val revisions = listOf(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        ).iterator()
        val t = FeishuTrust(
            path = f,
            nowEpochMs = { 1234L },
            newPolicyRevision = { revisions.next() },
        )
        t.setReviewed("oc_1", "/p/alpha", "评审")
        val snap = t.snapshot("oc_1", "/p/alpha")
        assertTrue(t.stillMatches("oc_1", "/p/alpha", snap))
        t.untrust("oc_1")
        t.setReviewed("oc_1", "/p/alpha", "评审")

        // Reload to prove the differentiator is on disk rather than process-local state.
        val reloaded = FeishuTrust(f)
        val rebuilt = reloaded.snapshot("oc_1", "/p/alpha")
        assertEquals(snap.contractVersion, rebuilt.contractVersion, "ABA precondition: version repeats")
        assertEquals(snap.updatedAtEpochMs, rebuilt.updatedAtEpochMs, "ABA precondition: clock is constant")
        assertEquals(snap.mode, rebuilt.mode)
        assertEquals(snap.purpose, rebuilt.purpose)
        assertNotEquals(snap.policyRevision, rebuilt.policyRevision, "each grant must carry a unique revision")
        assertFalse(reloaded.stillMatches("oc_1", "/p/alpha", snap), "a revoked-and-rebuilt policy is NOT the one reviewed")
    }

    @Test
    fun purpose_is_trimmed_bounded_and_blank_means_default_contract() {
        val t = FeishuTrust(f)
        t.setReviewed("oc_1", "/p/alpha", "   ")
        assertNull(t.recordFor("oc_1")?.purpose)
        t.setReviewed("oc_1", "/p/alpha", "x".repeat(FeishuTrust.MAX_PURPOSE_CHARS + 100))
        assertEquals(FeishuTrust.MAX_PURPOSE_CHARS, t.recordFor("oc_1")?.purpose?.length)
    }
}
