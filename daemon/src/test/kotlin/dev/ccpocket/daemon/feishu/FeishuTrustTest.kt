package dev.ccpocket.daemon.feishu

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The versioned three-mode trust store: migration, fail-closed reads, distinguishable writes, snapshots. */
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
    fun legacy_chatId_to_workdir_map_migrates_to_TRUSTED_records() {
        // the old rows were the owner's explicit /trust — the upgrade must not silently change their meaning
        f.writeText("""{"oc_1":"/p/alpha","oc_2":"/p/Beta"}""")
        val t = FeishuTrust(f)
        assertTrue(t.isTrusted("oc_1", "/p/alpha"))
        assertEquals(FeishuTrustMode.TRUSTED, t.modeFor("oc_2", "/p/Beta"))
        assertEquals(1, t.recordFor("oc_1")?.contractVersion)
        assertNull(t.recordFor("oc_1")?.purpose)
        // migration is lazy: the file is not rewritten by the read itself…
        assertTrue("version" !in f.readText())
        // …but the next successful write regenerates it as v2 WITH the migrated rows intact
        assertEquals(TrustWrite.CHANGED, t.setReviewed("oc_3", "/p/gamma", null))
        val reloaded = FeishuTrust(f)
        assertTrue(reloaded.isTrusted("oc_1", "/p/alpha"))
        assertEquals(FeishuTrustMode.REVIEWED, reloaded.modeFor("oc_3", "/p/gamma"))
    }

    @Test
    fun v2_reviewed_record_survives_reload() {
        val t = FeishuTrust(f)
        assertEquals(TrustWrite.CHANGED, t.setReviewed("oc_1", "/p/alpha", "只用于代码评审"))
        val r = FeishuTrust(f).recordFor("oc_1")!!
        assertEquals(FeishuTrustMode.REVIEWED, r.mode)
        assertEquals("只用于代码评审", r.purpose)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
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
        // a future v3 may hang new safety conditions on fields this build ignores — applying its rows
        // anyway would grant more than the owner agreed to, so anything but integer 2 reads as no trust
        for (version in listOf("1", "3", "999", "\"2\"", "null", "-2")) {
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
        // /untrust deletes the record, so a same-args /review restarts at contractVersion 1 — every FIELD
        // the old snapshot saw can come back identical (ABA). The write timestamp is what must differ.
        val t = FeishuTrust(f)
        t.setReviewed("oc_1", "/p/alpha", "评审")
        val snap = t.snapshot("oc_1", "/p/alpha")
        assertTrue(t.stillMatches("oc_1", "/p/alpha", snap))
        t.untrust("oc_1")
        Thread.sleep(2) // the millisecond clock must tick between the two writes for the test to mean anything
        t.setReviewed("oc_1", "/p/alpha", "评审")
        assertEquals(snap.contractVersion, t.snapshot("oc_1", "/p/alpha").contractVersion, "ABA precondition")
        assertFalse(t.stillMatches("oc_1", "/p/alpha", snap), "a revoked-and-rebuilt policy is NOT the one reviewed")
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
