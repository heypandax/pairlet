package dev.ccpocket.daemon.dsh

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LIVE, against the REAL `dsh` binary and the REAL `$DSH_HOME` store: opening the model picker must
 * leave the user's session list exactly as it found it (issue #387).
 *
 * This is the one check the fixture tests cannot make. They prove the sweep refuses everything it cannot
 * own; only a real dsh proves that the thing it CAN own is the thing dsh actually wrote — the id it
 * answers with, the cwd it records, the directory name it chooses, and the moment its write is final.
 *
 * It costs no inference: `initialize` → `session/new` → `session/close` and nothing else. It does write
 * to the real store, which is the point — the assertion is that the store is byte-identical afterwards.
 *
 * OFF BY DEFAULT. Run it deliberately, after every dsh upgrade, next to `scripts/probe-dsh-acp.py`:
 *
 * ```
 *   CC_POCKET_DSH_LIVE=1 ./gradlew --no-daemon :daemon:test \
 *     --tests 'dev.ccpocket.daemon.dsh.DshProbeSessionLiveIT'
 * ```
 *
 * `--no-daemon` is not decoration: a Test task inherits the BUILD JVM's environment, so a long-lived
 * Gradle daemon would hand the test a stale `CC_POCKET_DSH_BIN` / `DSH_HOME`.
 */
@EnabledIfEnvironmentVariable(named = "CC_POCKET_DSH_LIVE", matches = "1")
class DshProbeSessionLiveIT {

    /** Every file under the sessions root, by relative path, with its bytes — the only comparison that
     *  can catch a rewrite as well as a delete. */
    private fun snapshot(root: Path): Map<String, List<Long>> {
        if (!root.isDirectory()) return emptyMap()
        return Files.walk(root).use { walk ->
            walk.filter { it.isRegularFile() }.toList()
        }.associate { file ->
            root.relativize(file).toString() to listOf(Files.size(file), checksum(file))
        }
    }

    private fun checksum(file: Path): Long {
        val crc = java.util.zip.CRC32()
        crc.update(Files.readAllBytes(file))
        return crc.value
    }

    private fun dirs(root: Path): Set<String> {
        if (!root.isDirectory()) return emptySet()
        return Files.walk(root).use { walk ->
            walk.filter { it.isDirectory() }.toList()
        }.map { root.relativize(it).toString() }.toSet()
    }

    @Test
    fun a_real_model_probe_leaves_the_real_session_store_exactly_as_it_found_it() {
        val root = DshPaths.sessionsRoot()
        val filesBefore = snapshot(root)
        val dirsBefore = dirs(root)

        val exe = DshLauncher.resolveExecutable(null)
        val result = DshProbeSession(launch = { scratch -> DshProbeSession.processBuilder(exe, scratch).start() }).run()

        // The catalogue is the point of the probe; without it the rest proves nothing.
        val options = assertNotNull(result.options, "the live dsh answered no configOptions")
        assertTrue(options.models.isNotEmpty(), "a live dsh with no models makes this test vacuous")
        assertTrue(result.processExited, "the probe left a dsh running")
        assertTrue(result.scratchRemoved, "the probe left its scratch directory behind")
        assertEquals(DshProbeSessionCleanup.Outcome.Removed, result.session)

        assertEquals(dirsBefore, dirs(root), "the probe added or removed a directory in the session store")
        assertEquals(filesBefore, snapshot(root), "the probe added, removed or rewrote a file in the session store")
    }
}
