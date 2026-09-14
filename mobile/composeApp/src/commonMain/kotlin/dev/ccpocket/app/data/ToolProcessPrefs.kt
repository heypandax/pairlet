package dev.ccpocket.app.data

import androidx.compose.runtime.mutableStateOf
import dev.ccpocket.app.secure.SecureStore
import dev.ccpocket.protocol.AgentKind

/**
 * Whose "collapse tool process" switch this is (issue #380): THIS device (the store is local), the paired
 * computer ([accountId]), the [agent] and the stable [sessionId]. A brand-new session has no sessionId until
 * its SessionLive lands, so until then the choice is held in memory under [convoId] and moved to the durable
 * key by [ToolProcessPrefs.migrate]. Nothing is shared across computers or agents, even for equal ids.
 */
data class ToolProcessScope(
    val accountId: String?,
    val agent: AgentKind,
    val sessionId: String?,
    val convoId: String?,
)

/** The focused conversation's scope: this device × paired computer × agent × session (convoId until then). */
val PocketRepository.toolProcessScope: ToolProcessScope
    get() = ToolProcessScope(paired.value?.accountId, sessionAgent.value ?: AgentKind.CLAUDE, sessionKey.value, convoId.value)

interface ToolProcessPrefStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

private object SecureStorePrefStore : ToolProcessPrefStore {
    override fun get(key: String) = SecureStore.getString(key)
    override fun put(key: String, value: String) = SecureStore.putString(key, value)
    override fun remove(key: String) = SecureStore.remove(key)
}

/**
 * Default OFF — today's fully expanded stream. Only sessions switched ON leave a stored key; switching back
 * off removes it. Reads subscribe the caller (a snapshot state revision), so a flip recomposes every list
 * showing that session, while each list keeps its own per-fold expansion.
 */
class ToolProcessPrefs(private val store: ToolProcessPrefStore) {
    private val revision = mutableStateOf(0)
    private val temporary = HashMap<String, Boolean>()
    private val cache = HashMap<String, Boolean>()

    fun isCollapsed(scope: ToolProcessScope?): Boolean {
        revision.value
        scope ?: return false
        tempKey(scope)?.let { k -> temporary[k]?.let { return it } }
        val durable = durableKey(scope) ?: return false
        return cache.getOrPut(durable) { store.get(durable) == ON }
    }

    fun setCollapsed(scope: ToolProcessScope?, collapsed: Boolean) {
        scope ?: return
        val durable = durableKey(scope)
        if (durable != null) {
            write(durable, collapsed)
            tempKey(scope)?.let(temporary::remove)
        } else {
            temporary[tempKey(scope) ?: return] = collapsed
        }
        revision.value++
    }

    /** The session id landed: move a choice made under the convoId to the durable key. Idempotent. */
    fun migrate(scope: ToolProcessScope?) {
        scope ?: return
        val durable = durableKey(scope) ?: return
        val value = temporary.remove(tempKey(scope) ?: return) ?: return
        write(durable, value)
        revision.value++
    }

    private fun write(key: String, collapsed: Boolean) {
        if (collapsed) store.put(key, ON) else store.remove(key)
        cache[key] = collapsed
    }

    private fun durableKey(s: ToolProcessScope) =
        s.sessionId?.takeIf { it.isNotBlank() }?.let { "$PREFIX|${s.accountId ?: "-"}|${s.agent.name}|$it" }

    private fun tempKey(s: ToolProcessScope) =
        s.convoId?.takeIf { it.isNotBlank() }?.let { "${s.accountId ?: "-"}|${s.agent.name}|$it" }

    companion object {
        private const val PREFIX = "tool_process_collapse.v1"
        private const val ON = "1"

        /** The app-wide instance, backed by the platform's local key/value store. */
        val shared: ToolProcessPrefs by lazy { ToolProcessPrefs(SecureStorePrefStore) }
    }
}
