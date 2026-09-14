package dev.ccpocket.app.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.data.RepoManagedSessionsGateway
import dev.ccpocket.protocol.AgentKind
import kotlinx.coroutines.launch

/**
 * The import screen with its controller and first-use notice, over any [gateway] (issue #360). Both ends mount this:
 * the phone as a full-screen route, the desktop inside its sidebar popup. [scope] is always explicit — the project the
 * entry was raised on, never "whatever the repository has listed". [onLocate] gets each successful import; it may
 * navigate and reveal, and must never send a prompt or take a session over.
 *
 * Import stays locked until the daemon has said which agents are READY: while that answer is on its way, when it failed
 * (an error with retry — never "unlocked by default"), for an agent still UNINITIALIZED (switch it on first) and for a
 * corrupt, read-only store. An enable that got no answer in time is re-checked against the daemon before anything is
 * said: it may well have been committed.
 */
@Composable
fun ManagedImportHost(
    gateway: ManagedSessionsGateway,
    scope: ManagedScope,
    agents: List<AgentKind>,
    onLocate: (ImportSessionsEffect.Imported) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutines = rememberCoroutineScope()
    val locate by rememberUpdatedState(onLocate)
    val controller = remember(gateway) { ImportSessionsController(gateway, coroutines, onImported = { locate(it) }) }
    var enable by remember(scope) { mutableStateOf<ManagedEnableUi?>(null) }
    var readOnly by remember(scope) { mutableStateOf(false) }
    /** The daemon's last status answer; null = not answered yet (import locked). */
    var status by remember(scope) { mutableStateOf<ManagedStatusResult?>(null) }

    /** Re-read which agents still need switching on; null when the daemon could not say. */
    suspend fun loadStatus(): ManagedStatusResult.Statuses? {
        val r = gateway.status(scope)
        status = r
        val s = r as? ManagedStatusResult.Statuses ?: return null
        readOnly = s.readOnly
        enable = s.uninitialized.filter { it in agents }.takeIf { it.isNotEmpty() }?.let { ManagedEnableUi(it) }
        return s
    }

    LaunchedEffect(scope, agents) {
        controller.dispatch(ImportSessionsEvent.Open(scope, agents))
        loadStatus()
    }
    DisposableEffect(controller) { onDispose { controller.dispatch(ImportSessionsEvent.Close) } }

    val state by controller.state.collectAsState()
    ImportSessionsView(
        state, controller::dispatch, modifier, onClose,
        enable = enable,
        readOnly = readOnly,
        statusPending = status == null,
        statusError = (status as? ManagedStatusResult.Failure)?.error,
        onRetryStatus = {
            status = null
            coroutines.launch { loadStatus() }
        },
        onEnable = { agent ->
            val current = enable
            if (current != null && current.busy == null) {
                enable = current.copy(busy = agent, error = null, diagnostic = null)
                coroutines.launch {
                    when (val r = gateway.enable(scope, agent)) {
                        EnableResult.Enabled -> {
                            loadStatus()
                            // the discovery rows' "already imported" marks came from before the switch: page one again
                            controller.dispatch(ImportSessionsEvent.Open(scope, agents, controller.state.value.agent))
                        }
                        is EnableResult.Failure -> {
                            if (r.error == ManagedSessionsError.UNCONFIRMED) {
                                // no answer is not "no": the daemon may have committed READY — ask it before saying anything
                                val s = loadStatus()
                                if (s != null && agent !in s.uninitialized) {
                                    controller.dispatch(ImportSessionsEvent.Open(scope, agents, controller.state.value.agent))
                                    return@launch
                                }
                            }
                            enable = enable?.copy(busy = null, error = r.error, diagnostic = r.diagnostic)
                        }
                    }
                }
            }
        },
    )
}

/** [ManagedImportHost] for a repository-backed screen (the phone): the listed computer, [workdir] as given. */
@Composable
fun ManagedImportRoute(
    repo: PocketRepository,
    workdir: String,
    onLocate: (ImportSessionsEffect.Imported) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gateway = remember(repo) { RepoManagedSessionsGateway(repo) }
    val computer = repo.paired.value?.accountId.orEmpty()
    val scope = remember(computer, workdir) { ManagedScope(computer, workdir) }
    val managed = repo.daemonManagedAgents.value
    val agents = remember(managed) { IMPORTABLE_AGENTS.filter { it in managed } }
    ManagedImportHost(gateway, scope, agents, onLocate, onClose, modifier)
}
