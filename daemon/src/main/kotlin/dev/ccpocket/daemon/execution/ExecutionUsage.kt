package dev.ccpocket.daemon.execution

import dev.ccpocket.daemon.bridge.BridgeRegistry
import dev.ccpocket.daemon.execution.client.ExecutionClient
import dev.ccpocket.daemon.execution.client.ExecutionClientStore
import java.io.File

/**
 * #367: "has this machine ever used remote execution?" — answered from evidence that ALREADY exists on
 * disk / in the credential registry, so no new state is introduced to remember it.
 *
 * The relay wiring loads the execution planes (grant store, owner plane, run plane, source client, the
 * 15-second maintenance ticker) at attach time ONLY when [reason] finds something. Every other machine
 * loads them on first use instead (the local control API, see [dev.ccpocket.daemon.DaemonCore.ensureExecution]).
 *
 * Each piece of evidence is a file that only the execution code itself ever writes — loading the planes on
 * an unused machine creates none of them (an empty grant store does not persist, an absent journal root is
 * not created, an empty link store is not written) — so "unused" stays unused until real use.
 *
 * Deliberately NOT evidence: the existence of `execution-credentials.json`. [BridgeRegistry] rewrites that
 * file (as `{}`) on every bridge/guest change, so its existence proves nothing; what counts is
 * whether the registry actually holds an EXECUTION credential.
 */
class ExecutionUsage(
    /** Target side: `execution-grants.json` (its tombstone log is derived beside it). */
    private val grantStore: File,
    /** Target side: the run journal root (`execution-runs/`). */
    private val runRoot: File,
    /** Source side: `execution-links.json` + `execution-link-secrets.json`. */
    private val clientLinks: File,
    private val clientLinkSecrets: File,
    /** Source side: `execution-client-runs.json`. */
    private val clientRuns: File,
) {

    /** Why this machine counts as "in use", or null when there is no evidence at all. Stable short codes
     *  (logged at attach) — never a path's content. */
    fun reason(bridges: BridgeRegistry): String? = when {
        grantStore.exists() -> "grants"
        ExecutionGrantStore.tombstonePathFor(grantStore).exists() -> "grant_tombstones"
        bridges.ids().any { bridges.isExecution(it) } -> "execution_credential"
        runRoot.exists() -> "run_journal"
        clientLinks.exists() -> "client_links"
        clientLinkSecrets.exists() -> "client_link_secrets"
        clientRuns.exists() -> "client_runs"
        else -> null
    }

    companion object {
        /** The production paths — the SAME ones the relay wiring loads the planes from. */
        fun defaults(runRoot: File = RunJournal.defaultRoot()): ExecutionUsage {
            val (links, secrets) = ExecutionClient.defaultLinkPaths()
            return ExecutionUsage(
                grantStore = ExecutionGrantStore.defaultPath(),
                runRoot = runRoot,
                clientLinks = links,
                clientLinkSecrets = secrets,
                clientRuns = ExecutionClientStore.defaultPath(),
            )
        }
    }
}
