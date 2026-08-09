package dev.ccpocket.app.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.ccpocket.app.SystemBackHandler
import dev.ccpocket.app.data.PocketRepository
import dev.ccpocket.app.openWebUrl
import dev.ccpocket.app.theme.Tok
import dev.ccpocket.protocol.ReviewInboxAction
import kotlinx.coroutines.delay

/**
 * The repo-connected Review Center (REVIEW-REQUEST.md §12), in the [dev.ccpocket.app.ui.handoff]
 * *Flows.kt idiom: this file is the ONLY place in the review UI that touches a [PocketRepository], so
 * every screen beside it stays drivable from a test with plain data.
 *
 * Navigation is the repository-state + boolean-flag shape the rest of the app uses; there is no
 * navigation library to reach for, and a Review Center that invented one would be the odd surface out.
 */
@Composable
fun ReviewCenterFlow(
    repo: PocketRepository,
    modifier: Modifier = Modifier,
    /** Platform QR scanner, when the host has one (mobile). Desktop passes null and pastes. */
    scanner: (@Composable ((String) -> Unit) -> Unit)? = null,
    /** Platform QR renderer for the invite screen. */
    qr: (@Composable (String) -> Unit)? = null,
    /** Leave the Center. Called when back is pressed at the root; null = the host has no "out" (the
     *  desktop overlay dismisses via its own ✕/scrim instead). */
    onExit: (() -> Unit)? = null,
    /** Rendered above the content by the host (a mobile top bar, or the desktop overlay's header).
     *  [onBack] is always non-null when [onExit] is: at the root it exits, otherwise it pops. [atRoot]
     *  lets a host give the Center's own landing a first-hop title without repeating it on every sub-page. */
    header: (@Composable (onBack: (() -> Unit)?, atRoot: Boolean) -> Unit)? = null,
) {
    // One pull on entry. Everything after that is daemon truth arriving on its own schedule; the
    // Center never polls, because the daemon is the thing that is always running (§3.3).
    LaunchedEffect(repo) { repo.refreshReviews() }

    var tab by remember { mutableStateOf(ReviewTab.INBOX) }
    var openedReceived by remember { mutableStateOf<String?>(null) }
    var openedSent by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(false) }
    var responding by remember { mutableStateOf<String?>(null) }
    var inviting by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    fun copy(text: String) { clipboard.setText(AnnotatedString(text)); copied = true }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }

    // a sent request that just landed is the proof of the send; leaving the form on it would hide it
    val created = repo.reviewLastCreated.value
    LaunchedEffect(created?.id) {
        if (created != null && composing) { composing = false; tab = ReviewTab.SENT; openedSent = created.id }
    }

    // A `ccpocket://review-contact#` deep link is addressed to THIS surface (REVIEW-REQUEST.md §13.3),
    // so it opens the join page with the ticket already on its fingerprint step. It never redeems on
    // sight: arriving through a link is not the same as a human saying yes to these particular words.
    //
    // It closes the invite page (nothing is lost — the minted URI is still in the repo) but deliberately
    // does NOT close a compose/respond form: those hold text the user typed, and a link they may have
    // opened by accident must not discard it. The join page is still armed underneath, so backing out of
    // the form lands on it rather than losing the ticket.
    val routedInvite = repo.pendingReviewInvite.value
    LaunchedEffect(routedInvite) { if (routedInvite != null) { inviting = false; joining = true } }

    val atRoot = responding == null && !composing && !inviting && !joining &&
        openedReceived == null && openedSent == null

    fun back() {
        when {
            responding != null -> responding = null
            composing -> composing = false
            inviting -> inviting = false
            // backing out of the join page also drops the routed ticket, so leaving and re-entering the
            // Center doesn't re-open a page the user already declined. The ticket itself is untouched.
            joining -> { joining = false; repo.pendingReviewInvite.value = null }
            openedReceived != null -> { openedReceived = null; repo.clearReviewBundle() }
            openedSent != null -> openedSent = null
            else -> onExit?.invoke()
        }
    }

    // registered HERE rather than by each host: the Center owns its own sub-page stack, so it is the
    // only thing that can tell "pop a sub-page" from "leave". Android's back button and the header
    // chevron then agree by construction. The desktop actual is a no-op, which is correct there.
    if (onExit != null) SystemBackHandler(enabled = true) { back() }

    val inner = @Composable {
        Column(Modifier.fillMaxSize().background(Tok.base)) {
            val received = repo.reviewsReceived.firstOrNull { it.request.id == openedReceived }
            val sent = repo.reviewsSent.firstOrNull { it.id == openedSent }
            when {
                responding != null -> RespondScreen(
                    sending = repo.reviewActing.value == responding,
                    error = repo.reviewError.value,
                    onSubmit = { result ->
                        repo.actOnReview(responding!!, ReviewInboxAction.RESPOND, result = result)
                        responding = null
                    },
                )

                composing -> NewReviewScreen(
                    recipients = repo.reviewRecipients(),
                    sending = repo.reviewSending.value,
                    error = repo.reviewError.value,
                    onSend = { to, title, brief, artifacts -> repo.sendReview(to, title, brief, artifacts) },
                )

                inviting -> ReviewInviteScreen(
                    invite = repo.reviewInvite.value,
                    ttlSec = repo.reviewInviteTtlSec.value,
                    creating = repo.reviewInviteCreating.value,
                    error = repo.reviewError.value,
                    copied = copied,
                    qr = qr,
                    onCopy = ::copy,
                )

                joining -> ReviewJoinScreen(
                    joining = repo.reviewJoining.value,
                    error = repo.reviewError.value,
                    scanner = scanner,
                    prefill = routedInvite,
                    onJoin = { uri ->
                        repo.joinReviewContact(uri)
                        joining = false
                        repo.pendingReviewInvite.value = null
                    },
                )

                received != null -> ReceivedDetailScreen(
                    item = received,
                    bundle = repo.reviewBundle.value?.takeIf { it.requestId == received.request.id },
                    acting = repo.reviewActing.value == received.request.id,
                    preparing = repo.reviewPreparing.value == received.request.id,
                    copied = copied,
                    error = repo.reviewError.value,
                    onPrepare = { repo.prepareReview(received.request.id) },
                    onCopyPrompt = ::copy,
                    onAcknowledge = { repo.actOnReview(received.request.id, ReviewInboxAction.ACKNOWLEDGE) },
                    onStart = { repo.actOnReview(received.request.id, ReviewInboxAction.START) },
                    onDecline = { reason -> repo.actOnReview(received.request.id, ReviewInboxAction.DECLINE, reason = reason) },
                    onRespond = { responding = received.request.id },
                    // an explicit tap, with the user's own browser and their own access (§11.2)
                    onOpenUrl = { openWebUrl(it) },
                )

                sent != null -> SentDetailScreen(
                    r = sent,
                    error = repo.reviewError.value,
                    onCancel = { repo.cancelReview(sent.id) },
                    onClose = { repo.closeReview(sent.id) },
                    onOpenUrl = { openWebUrl(it) },
                )

                else -> ReviewCenterScreen(
                    state = repo.reviewCenterState(),
                    tab = tab,
                    onTab = { tab = it },
                    pendingCount = repo.reviewPendingCount,
                    onOpenReceived = { openedReceived = it.request.id },
                    onOpenSent = { openedSent = it.id },
                    onNewReview = { composing = true },
                    // minting is a daemon round trip, so it starts as the screen opens rather than
                    // behind a second tap on an empty QR frame
                    onInvite = { inviting = true; repo.createReviewInvite() },
                    onJoin = { joining = true },
                    onRemoveContact = { repo.removeReviewContact(it.id, it.direction) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // at the root the chevron only exists when the host has somewhere to go back TO
            header?.invoke(if (atRoot && onExit == null) null else ::back, atRoot)
            inner()
        }
    }
}

/** The Center's whole view, assembled in one place so a screen can never see a half-consistent mix of
 *  "loading" and "empty" — the difference between those two is the entire point of the state pane. */
fun PocketRepository.reviewCenterState(): ReviewCenterState {
    val anyLoaded = reviewsSentLoaded.value || reviewInboxLoaded.value || reviewContactsLoaded.value
    return ReviewCenterState(
        sent = reviewsSent.toList(),
        received = reviewsReceived.toList(),
        contacts = reviewContacts.toList(),
        loading = !anyLoaded && !reviewUnsupported.value,
        unsupported = reviewUnsupported.value,
        offline = !connected.value,
        error = reviewError.value,
    )
}
