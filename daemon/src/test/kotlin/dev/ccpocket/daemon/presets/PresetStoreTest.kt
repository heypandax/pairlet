package dev.ccpocket.daemon.presets

import dev.ccpocket.protocol.PresetEnv
import dev.ccpocket.protocol.SavePreset
import dev.ccpocket.protocol.Secret
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PresetStore: CRUD + validation + persistence + the secrets red line (mask-only outward). */
class PresetStoreTest {

    private fun tempPath(): File = File(Files.createTempDirectory("presets-test").toFile(), "presets.json")

    private fun save(
        id: String? = null,
        name: String = "Work proxy",
        baseUrl: String = "https://api.example-proxy.com/v1",
        tokenVar: String = PresetEnv.AUTH_TOKEN,
        token: String? = "sk-proxy-9f2a4c8e3f9a",
        model: String? = null,
        smallFastModel: String? = null,
    ) = SavePreset(id, name, baseUrl, tokenVar, token?.let(::Secret), model, smallFastModel)

    @Test
    fun create_edit_delete_roundtrip_with_persistence() {
        val path = tempPath()
        val store = PresetStore.load(path)
        assertNull(store.save(save()))
        assertNull(store.save(save(name = "Personal key", baseUrl = "https://api.anthropic.com", tokenVar = PresetEnv.API_KEY, token = "sk-ant-personal-a71c")))

        val listed = store.summaries()
        assertEquals(listOf("Work proxy", "Personal key"), listed.map { it.name })
        val work = listed.first()

        // activate + reload from disk: the whole state survives a daemon restart
        assertNull(store.activate(work.id))
        val reloaded = PresetStore.load(path)
        assertEquals(work.id, reloaded.activeId)
        assertEquals(2, reloaded.summaries().size)

        // edit with blank token keeps the stored secret (the "leave blank to keep" contract)
        assertNull(reloaded.save(save(id = work.id, name = "Work proxy 2", token = null)))
        val env = assertNotNull(reloaded.activeEnv())
        assertEquals("sk-proxy-9f2a4c8e3f9a", env[PresetEnv.AUTH_TOKEN])
        assertEquals("Work proxy 2", reloaded.summaries().first().name)

        // delete the non-active one: active pointer untouched
        val personal = reloaded.summaries().last()
        assertTrue(reloaded.delete(personal.id))
        assertEquals(work.id, reloaded.activeId)
        // delete the active one: pointer clears (the service runs the switch guard before calling this)
        assertTrue(reloaded.delete(work.id))
        assertNull(reloaded.activeId)
        assertNull(reloaded.activeEnv())
        assertFalse(reloaded.delete("nope"))
    }

    @Test
    fun summaries_and_file_never_leak_more_than_the_mask_shape() {
        val path = tempPath()
        val store = PresetStore.load(path)
        assertNull(store.save(save(token = "sk-proxy-9f2a4c8e3f9a")))

        val s = store.summaries().single()
        assertEquals("sk-…••••3f9a", s.tokenMask) // short prefix + last 4, middle elided — per the design
        // the summary object (all a frame can carry) holds no field with the full secret
        assertFalse(s.toString().contains("sk-proxy-9f2a4c8e3f9a"))

        // the plaintext DOES live in the 0600 store file — that's the documented bar (same as identity.json)
        assertTrue(path.readText().contains("sk-proxy-9f2a4c8e3f9a"))
        val perms = Files.getPosixFilePermissions(path.toPath()).map { it.toString() }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), perms)
    }

    @Test
    fun mask_flattens_short_tokens_instead_of_echoing_half_of_them() {
        assertEquals("••••", PresetStore.mask(""))
        assertEquals("••••", PresetStore.mask("sk-tiny"))
        assertEquals("••••", PresetStore.mask("123456789012345")) // 15 chars: echoing 7 would leave only 8 hidden
        assertEquals("123…••••3456", PresetStore.mask("1234567890123456"))
    }

    @Test
    fun validation_matches_the_form_contract() {
        val store = PresetStore.load(tempPath())
        assertNull(store.save(save())) // seed "Work proxy"

        assertEquals("name", store.save(save(name = "  "))?.second)
        assertEquals("name", store.save(save(name = "work PROXY", baseUrl = "https://x.y"))?.second) // dup is case-insensitive
        assertEquals("baseUrl", store.save(save(name = "B", baseUrl = "api.example.com"))?.second)   // no scheme
        assertEquals("baseUrl", store.save(save(name = "B", baseUrl = "ftp://api.example.com"))?.second)
        assertEquals("token", store.save(save(name = "B", token = null))?.second)                     // create needs a token
        assertEquals("token", store.save(save(name = "B", token = "  "))?.second)
        assertEquals("token", store.save(save(name = "B", tokenVar = "ANTHROPIC_FANCY"))?.second)     // unknown var refused
        // editing a preset that vanished under the form
        assertNull(store.save(save(id = "ghost", name = "B"))?.second)
        assertNotNull(store.save(save(id = "ghost", name = "B")))
        // http is fine (local endpoints like ollama)
        assertNull(store.save(save(name = "Local llama", baseUrl = "http://localhost:11434", token = "dummy-key-123456")))
        // renaming a preset to ITSELF is not a duplicate
        val id = store.summaries().first { it.name == "Work proxy" }.id
        assertNull(store.save(save(id = id, token = null)))
    }

    @Test
    fun activeEnv_builds_exactly_the_vars_the_preset_owns() {
        val store = PresetStore.load(tempPath())
        assertNull(store.save(save(tokenVar = PresetEnv.API_KEY, model = "gpt-4o", smallFastModel = " ")))
        val id = store.summaries().single().id
        assertNull(store.activeEnv()) // nothing active yet
        assertNull(store.activate(id))

        val env = assertNotNull(store.activeEnv())
        assertEquals("https://api.example-proxy.com/v1", env[PresetEnv.BASE_URL])
        assertEquals("sk-proxy-9f2a4c8e3f9a", env[PresetEnv.API_KEY])
        assertNull(env[PresetEnv.AUTH_TOKEN])              // the OTHER token var is not set
        assertEquals("gpt-4o", env[PresetEnv.MODEL])
        assertNull(env[PresetEnv.SMALL_FAST_MODEL])        // blank routing entries are dropped at save
        assertEquals(3, env.size)

        assertNotNull(store.activate("ghost"))             // unknown id refused, active unchanged
        assertEquals(id, store.activeId)
        assertNull(store.activate(null))                   // deactivate
        assertNull(store.activeEnv())
    }
}
