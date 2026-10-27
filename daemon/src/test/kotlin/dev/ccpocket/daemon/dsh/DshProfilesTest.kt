package dev.ccpocket.daemon.dsh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The redacted dsh profile view: dsh installs plugins PER PROFILE and Pairlet always starts `acp`, so a provider
 * plugin added to `web` is invisible to every catalogue Pairlet reads. The layout is the real one
 * (`$DSH_HOME/profiles/<name>/package.json` + `cordis.patch.yml`, dsh 0.2.0-rc.2).
 */
class DshProfilesTest {

    private fun home(): Path {
        val home = Files.createTempDirectory("dsh-profiles")
        fun profile(name: String, deps: String, patch: String) {
            val dir = home.resolve("profiles").resolve(name).createDirectories()
            dir.resolve("package.json").writeText(
                """{"name":"dsh-profile-$name","private":true,"dependencies":{$deps},
                   "dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-$name-app"]}}}""",
            )
            dir.resolve("cordis.patch.yml").writeText(patch)
        }
        profile("acp", "", "# comment\n[]\n")
        profile(
            "web",
            """"dsh-plugin-qoder":"^0.3.1","dsh-plugin-workbuddy":"file:/Users/someone/src/workbuddy"""",
            "- id: qoder\n  config:\n    apiKey: sk-SECRET\n- id: workbuddy\n  config:\n    token: TOKEN\n",
        )
        return home
    }

    @Test
    fun `plugins installed elsewhere but not in acp are named, with where they are`() {
        val profiles = DshProfiles.read(home())
        assertEquals(listOf("acp", "web"), profiles.map { it.name })
        assertEquals(
            mapOf("dsh-plugin-qoder" to listOf("web"), "dsh-plugin-workbuddy" to listOf("web")),
            DshProfiles.missingFrom(profiles),
        )
        val hint = assertNotNull(DshProfiles.missingPluginHint(profiles))
        assertTrue("dsh-plugin-qoder (in web)" in hint && "dsh plugin --profile acp add" in hint, hint)
    }

    @Test
    fun `nothing to say when acp already has every plugin`() {
        val profiles = listOf(
            DshProfiles.Profile("acp", emptyList(), mapOf("p" to "1.0.0"), 0),
            DshProfiles.Profile("web", emptyList(), mapOf("p" to "1.0.0"), 0),
        )
        assertNull(DshProfiles.missingPluginHint(profiles))
    }

    /** The report is what a user pastes into a public thread: names and versions, never settings, keys or paths. */
    @Test
    fun `the report carries package names and versions but no settings, secrets or paths`() {
        val report = DshProfiles.report(home(), dshVersion = "0.2.1-rc.3", homeOverridden = false)
        assertTrue("dsh-plugin-qoder@^0.3.1" in report, report)
        assertTrue("dsh-plugin-workbuddy@<local>" in report, report)
        assertTrue("patch entries: 2" in report, report)
        assertTrue("profile Pairlet starts: acp" in report, report)
        assertTrue("plugins missing from acp: dsh-plugin-qoder (in web), dsh-plugin-workbuddy (in web)" in report, report)
        listOf("sk-SECRET", "TOKEN", "apiKey", "/Users/someone").forEach { assertFalse(it in report, "leaked $it: $report") }
    }

    @Test
    fun `version specs that can carry a path or url are reduced`() {
        assertEquals("^1.2.3", DshProfiles.redactSpec("^1.2.3"))
        assertEquals("0.2.1-rc.3", DshProfiles.redactSpec("0.2.1-rc.3"))
        assertEquals("latest", DshProfiles.redactSpec("latest"))
        assertEquals("npm:@scope/pkg@1.0.0", DshProfiles.redactSpec("npm:@scope/pkg@1.0.0"))
        listOf("file:../x", "link:/a", "git+https://h/x.git", "https://h/x.tgz", "~/x", "./x", "github-user/repo")
            .forEach { assertEquals("<local>", DshProfiles.redactSpec(it), it) }
    }

    @Test
    fun `a missing profiles directory is no profiles`() {
        assertTrue(DshProfiles.read(Files.createTempDirectory("dsh-empty")).isEmpty())
    }
}
