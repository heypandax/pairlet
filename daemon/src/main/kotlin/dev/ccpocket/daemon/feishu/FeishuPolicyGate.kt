package dev.ccpocket.daemon.feishu

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Linearizes one Feishu chat's route/trust mutations with the final validation and arming of an automatic
 * bridge grant. Guardian review deliberately happens outside this gate so `/untrust` and `/bind` can land
 * while the reviewer is thinking. At the final claim there are only two possible orders:
 *
 *  1. mutation first -> [claim] revalidation fails and no grant is armed;
 *  2. claim first -> the one-turn grant is armed before the mutation, which therefore applies to later turns.
 *
 * The gate is per CHAT, not per topic: one trust record and one route cover every topic in that group.
 */
internal class FeishuPolicyGate {
    private val locks = ConcurrentHashMap<String, Mutex>()

    data class ClaimResult<T>(val valid: Boolean, val value: T? = null)

    suspend fun <T> withPolicy(chatId: String, block: suspend () -> T): T =
        lock(chatId).withLock { block() }

    /** Validate and arm under the same mutex used by every policy mutation. */
    suspend fun <T> claim(
        chatId: String,
        validate: () -> Boolean,
        arm: suspend () -> T,
    ): ClaimResult<T> = lock(chatId).withLock {
        if (!validate()) ClaimResult(valid = false) else ClaimResult(valid = true, value = arm())
    }

    private fun lock(chatId: String): Mutex = locks.computeIfAbsent(chatId) { Mutex() }
}
