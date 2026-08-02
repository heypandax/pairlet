package dev.ccpocket.app.ui.handoff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.epochMillis
import dev.ccpocket.app.pairing.encode
import kotlinx.coroutines.delay

/**
 * The repo-connected "Connect a colleague" flow (design Frames 3/3b): mints a one-time ticket on
 * entry, renders the QR + short code + honest note, and flips to the Connected sub-state when the
 * daemon reports the redeem ([PocketRepository.lastCollaboratorConnected]).
 * [fromDraft] true = reached from the recipient picker; the success primary returns to the draft.
 */
@Composable
fun ConnectColleagueFlow(
    repo: PocketRepository,
    fromDraft: Boolean,
    onBackToHandoff: () -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) {
        repo.lastCollaboratorConnected.value = null
        repo.createCollaboratorTicket()
    }
    val invite = repo.collaboratorTicket.value
    // ticket TTL countdown, approximated from when the invite landed on this device
    val mintedAt = remember(invite) { epochMillis() }
    var now by remember { mutableStateOf(epochMillis()) }
    LaunchedEffect(invite) { while (true) { delay(1000); now = epochMillis() } }
    val countdown = invite?.let {
        val left = ((mintedAt + it.ttlSec * 1000L - now) / 1000).coerceAtLeast(0)
        "${(left / 60).toString().padStart(2, '0')}:${(left % 60).toString().padStart(2, '0')}"
    }
    ConnectColleagueScreen(
        invite = invite,
        inviteBlob = invite?.encode(),
        ticketCountdown = countdown,
        connected = repo.lastCollaboratorConnected.value,
        error = repo.collaboratorError.value,
        fromDraft = fromDraft,
        onBackToHandoff = onBackToHandoff,
        onClose = onClose,
    )
}

/** Repo-connected Collaborators management screen (design Frames 5/5b/6). */
@Composable
fun CollaboratorsFlow(repo: PocketRepository, onConnectNew: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { repo.listCollaborators(); repo.listHandoffs() }
    CollaboratorsScreen(
        contacts = repo.collaborators.toList(),
        handoffsWith = { c -> repo.handoffs.filter { it.recipientDeviceId == c.deviceId }.mapNotNull { it.toHistoryItem() } },
        onRemove = { repo.removeCollaborator(it.deviceId) },
        onConnectNew = onConnectNew,
        onBack = onBack,
    )
}
