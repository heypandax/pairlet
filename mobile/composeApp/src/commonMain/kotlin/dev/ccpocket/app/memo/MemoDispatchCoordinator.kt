package dev.ccpocket.app.memo

/**
 * Batch dispatch of confirmed to-dos into one target chat (code design §7.5), as a pure part of [MemoReducer]:
 *
 *   freeze items + target → store every prompt id as a `draft` record (durable) → open the target
 *   → store the convoId, mark ONE item `sending` (durable) → submit → matching receipt → store `delivered`
 *   (durable) → next item.
 *
 * At most one item is ever `sending`; the others stay drafts until their turn, so a stop leaves them sendable.
 * Nothing is sent before its prompt id is durable; a submit that may have been written is never repeated —
 * without a receipt it becomes `unknown` ("check the chat"). A late receipt upgrades `unknown` to `delivered`
 * but never resumes the batch: only the user's next confirmation does, as a new batch.
 */
internal class MemoDispatchCoordinator(
    private val newId: () -> String,
    private val newPromptId: () -> String,
    private val receiptTimeoutMs: Long,
) {

    fun memoFor(tx: MemoTx, promptId: String): String? =
        tx.m.promptIndex[promptId]
            ?: tx.m.slots.entries.firstOrNull { (_, s) -> s.doc.dispatches.any { it.promptId == promptId } }?.key

    /**
     * The confirm sheet's primary button — the only thing that authorises sending. It authorises exactly what the
     * sheet showed: if the items (ids, trimmed text, order, count) or the target identity differ from what would
     * be sent now, nothing is created or stored and the user is told the memo changed.
     */
    fun confirm(tx: MemoTx, action: MemoAction.ConfirmDispatch) {
        val m = tx.m
        if (m.screen != MemoScreen.DETAIL) return
        val id = m.currentId ?: return
        if (id in m.deleting || m.slots[id] == null) return
        val selection = m.selection()
        val current = selection.target?.target
        val sameItems = action.shown.size == selection.shown.size &&
            action.shown.zip(selection.shown).all { (a, b) -> a.todoId == b.todoId && a.text.trim() == b.text.trim() }
        if (!sameItems || current == null || !action.target.sameAs(current)) {
            tx.m = m.copy(toast = MemoToast.CHANGED_SINCE_SHOWN)
            return
        }
        if (!selection.canDispatch) return
        val target = current
        val batchId = newId()
        val items = selection.items.map { BatchItem(it.todoId, newPromptId(), it.text.trim()) }
        tx.m = m.copy(
            batch = Batch(batchId, id, target, items, creating = target.newSession),
            promptIndex = m.promptIndex + items.map { it.promptId to id },
        )
        tx.mutate(id, Purpose.DraftsStored(batchId)) { d ->
            d.copy(
                dispatches = d.dispatches + items.map {
                    MemoDispatchRecord(
                        batchId = batchId, todoId = it.todoId, promptId = it.promptId, confirmedText = it.text,
                        target = target, state = MemoTodoState.DRAFT, updatedAtMs = tx.now.wall,
                    )
                },
            )
        }
    }

    fun onStored(tx: MemoTx, memoId: String, purpose: Purpose, ok: Boolean) {
        val b = tx.m.batch ?: return
        if (b.memoId != memoId) return
        when (purpose) {
            is Purpose.DraftsStored -> {
                if (b.batchId != purpose.batchId || b.phase != null) return
                if (!ok) {
                    // No prompt id is known to be stored: nothing may be sent. The batch never becomes visible.
                    val s = tx.slot(memoId)
                    if (s != null) tx.putSlot(memoId, s.copy(doc = s.doc.copy(dispatches = s.doc.dispatches.filter { it.batchId != b.batchId })))
                    tx.m = tx.m.copy(batch = null)
                    return
                }
                val stop = b.stopRequested
                if (stop != null) {
                    stopBatch(tx, b.copy(phase = MemoDispatchPhase.OPENING), stop)
                    return
                }
                tx.m = tx.m.copy(batch = b.copy(phase = MemoDispatchPhase.OPENING))
                tx.emit(MemoEffect.Enter(b.batchId, b.target))
            }
            is Purpose.SendingStored -> {
                if (b.batchId != purpose.batchId || b.step != BatchStep.STORING_SENDING || b.current?.promptId != purpose.promptId) return
                val lease = b.lease ?: return
                if (!ok) {
                    setRecord(tx, memoId, purpose.promptId, MemoTodoState.DRAFT)
                    stopBatch(tx, b, MemoDispatchStop.SAVE_FAILED)
                    return
                }
                val stop = b.stopRequested
                if (stop != null) {
                    setRecord(tx, memoId, purpose.promptId, MemoTodoState.DRAFT)
                    stopBatch(tx, b, stop)
                    return
                }
                val item = b.current ?: return
                tx.m = tx.m.copy(batch = b.copy(step = BatchStep.SUBMITTING))
                tx.emit(MemoEffect.Submit(b.batchId, ConfirmedMemoPrompt(memoId, item.todoId, item.promptId, item.text, lease)))
                // A submit that never returns must not hold the batch open: it gets the receipt's deadline.
                tx.emit(MemoEffect.StartTimer(submitTimer(item.promptId), receiptTimeoutMs, MemoEvent.SubmitTimeout(b.batchId, item.promptId)))
            }
            is Purpose.DeliveredStored -> {
                if (b.batchId != purpose.batchId || b.step != BatchStep.STORING_DELIVERED || b.current?.promptId != purpose.promptId) return
                if (!ok) {
                    // Delivered in memory, not on disk: pause rather than send the next item on an unsaved ledger.
                    stopBatch(tx, b, MemoDispatchStop.SAVE_FAILED)
                    return
                }
                // A session created by this batch may only now know its id.
                b.lease?.let { tx.emit(MemoEffect.Resolve(b.batchId, it, final = false)) }
                tx.m = tx.m.copy(batch = b.copy(cursor = b.cursor + 1, step = BatchStep.IDLE))
                next(tx)
            }
            else -> Unit
        }
    }

    fun onEntered(tx: MemoTx, batchId: String, result: MemoEnterResult) {
        val b = tx.m.batch ?: return
        if (b.batchId != batchId || b.phase != MemoDispatchPhase.OPENING) return
        val lease = (result as? MemoEnterResult.Entered)?.lease
        val accepted = lease != null && lease.batchId == batchId && leaseMatches(b.target, lease.target)
        if (lease == null || !accepted) {
            // Nothing was sent: every item is still a draft, and the pick stays what the user chose (a new-session
            // pick can simply be retried).
            val reason = (result as? MemoEnterResult.Failed)?.reason ?: MemoEnterFailure.NOT_THE_TARGET
            tx.m = tx.m.copy(batch = b.copy(phase = MemoDispatchPhase.OPEN_FAILED, step = BatchStep.IDLE, openFailure = reason))
            return
        }
        // From here on the memo points at the session that is open — for a new-session batch, the one just
        // created — so what is left of this batch can never create a second session.
        val opened = lease.target
        tx.mutate(b.memoId) { d ->
            d.copy(dispatches = d.dispatches.map { if (it.batchId == batchId) it.copy(convoId = lease.convoId, target = opened) else it })
        }
        repick(tx, b.target, opened)
        val entered = b.copy(lease = lease, phase = MemoDispatchPhase.SENDING, target = opened)
        val stop = b.stopRequested
        if (stop != null) {
            stopBatch(tx, entered, stop)
            return
        }
        tx.m = tx.m.copy(batch = entered)
        next(tx)
    }

    private fun next(tx: MemoTx) {
        val b = tx.m.batch ?: return
        b.stopRequested?.let {
            stopBatch(tx, b, it)
            return
        }
        val item = b.current
        if (item == null) {
            val done = b.copy(phase = MemoDispatchPhase.DONE, step = BatchStep.IDLE)
            tx.m = tx.m.copy(batch = done)
            ended(tx, done)
            return
        }
        val lease = b.lease ?: return
        tx.m = tx.m.copy(batch = b.copy(step = BatchStep.CHECKING))
        tx.emit(MemoEffect.CheckHolds(b.batchId, item.promptId, lease))
    }

    /** The lease is checked before EVERY item, not once per batch. */
    fun onHolds(tx: MemoTx, batchId: String, promptId: String, held: Boolean) {
        val b = tx.m.batch ?: return
        if (b.batchId != batchId || b.step != BatchStep.CHECKING || b.current?.promptId != promptId) return
        b.stopRequested?.let {
            stopBatch(tx, b, it)
            return
        }
        if (!held) {
            stopBatch(tx, b, MemoDispatchStop.TARGET_LOST)
            return
        }
        tx.m = tx.m.copy(batch = b.copy(step = BatchStep.STORING_SENDING))
        setRecord(tx, b.memoId, promptId, MemoTodoState.SENDING, purpose = Purpose.SendingStored(batchId, promptId))
    }

    fun onSubmitted(tx: MemoTx, batchId: String, promptId: String, result: MemoSubmitResult) {
        val b = tx.m.batch ?: return
        // A result that returns after the submit deadline is ignored: the item was already reported as unknown,
        // and even a late NotSubmitted must not turn that back into something the user might resend.
        if (b.batchId != batchId || b.step != BatchStep.SUBMITTING || b.current?.promptId != promptId) return
        tx.emit(MemoEffect.CancelTimer(submitTimer(promptId)))
        when (result) {
            MemoSubmitResult.NotSubmitted -> {
                setRecord(tx, b.memoId, promptId, MemoTodoState.FAILED, errorCode = NOT_SUBMITTED)
                stopBatch(tx, b, MemoDispatchStop.NOT_SENT)
            }
            // The matching receipt already came: whatever the writer thinks, the prompt was delivered.
            MemoSubmitResult.Unknown -> if (promptId in b.early) deliver(tx, b, promptId) else {
                setRecord(tx, b.memoId, promptId, MemoTodoState.UNKNOWN)
                stopBatch(tx, b, b.stopRequested ?: MemoDispatchStop.RECEIPT_MISSING)
            }
            MemoSubmitResult.AwaitingReceipt ->
                if (promptId in b.early) deliver(tx, b, promptId) else {
                    tx.m = tx.m.copy(batch = b.copy(step = BatchStep.AWAITING))
                    tx.emit(MemoEffect.StartTimer(receiptTimer(promptId), receiptTimeoutMs, MemoEvent.ReceiptTimeout(batchId, promptId)))
                }
        }
    }

    fun onReceipt(tx: MemoTx, receipt: MemoPromptReceipt) {
        val memoId = memoFor(tx, receipt.promptId) ?: return
        if (memoId in tx.m.deleted || memoId in tx.m.deleting) return
        val s = tx.slot(memoId) ?: return
        val record = s.doc.dispatches.lastOrNull { it.promptId == receipt.promptId } ?: return
        // All three must match: a receipt from another computer or another chat is someone else's.
        if (record.target.bindingId != receipt.bindingId || record.convoId == null || record.convoId != receipt.convoId) return
        val b = tx.m.batch
        if (b != null && b.batchId == record.batchId && b.phase == MemoDispatchPhase.SENDING && b.current?.promptId == receipt.promptId) {
            when (b.step) {
                BatchStep.AWAITING -> deliver(tx, b, receipt.promptId)
                BatchStep.SUBMITTING -> tx.m = tx.m.copy(batch = b.copy(early = b.early + receipt.promptId))
                else -> Unit
            }
            return
        }
        // Late: the item was already written off as unknown. Record the truth; do not resume anything.
        if (record.state == MemoTodoState.UNKNOWN || record.state == MemoTodoState.SENDING) {
            setRecord(tx, memoId, receipt.promptId, MemoTodoState.DELIVERED)
            b?.takeIf { it.batchId == record.batchId }?.lease?.let { tx.emit(MemoEffect.Resolve(it.batchId, it, final = false)) }
        }
    }

    /** The submit did not return within the deadline: it may have been written, so the item is unknown. */
    fun onSubmitTimeout(tx: MemoTx, batchId: String, promptId: String) {
        val b = tx.m.batch ?: return
        if (b.batchId != batchId || b.step != BatchStep.SUBMITTING || b.current?.promptId != promptId) return
        if (promptId in b.early) {
            // Its receipt did arrive: record the truth, but a submit still hanging is no basis for sending more.
            setRecord(tx, b.memoId, promptId, MemoTodoState.DELIVERED)
            stopBatch(tx, b.copy(early = b.early - promptId), b.stopRequested ?: MemoDispatchStop.RECEIPT_MISSING)
            return
        }
        setRecord(tx, b.memoId, promptId, MemoTodoState.UNKNOWN)
        stopBatch(tx, b, b.stopRequested ?: MemoDispatchStop.RECEIPT_MISSING)
    }

    fun onReceiptTimeout(tx: MemoTx, batchId: String, promptId: String) {
        val b = tx.m.batch ?: return
        if (b.batchId != batchId || b.step != BatchStep.AWAITING || b.current?.promptId != promptId) return
        setRecord(tx, b.memoId, promptId, MemoTodoState.UNKNOWN)
        stopBatch(tx, b, b.stopRequested ?: MemoDispatchStop.RECEIPT_MISSING)
    }

    /**
     * The chat says stop (left, typed a message, background, computer changed, feature off). Nothing further is
     * submitted; an item already submitted keeps waiting for its receipt or its deadline. The first reason wins.
     */
    fun onStop(tx: MemoTx, stop: MemoDispatchStop) {
        val b = tx.m.batch ?: return
        if (!b.active || b.stopRequested != null) return
        tx.m = tx.m.copy(batch = b.copy(stopRequested = stop))
    }

    fun returnToMemo(tx: MemoTx) {
        val b = tx.m.batch ?: return
        tx.emit(MemoEffect.ShowMemo(b.memoId))
        if (tx.slot(b.memoId) != null) {
            tx.m = tx.m.copy(screen = MemoScreen.DETAIL, currentId = b.memoId)
            tx.loadCatalog()
        }
    }

    /**
     * What the gateway knows now about the session [batchId] opened. An id learned later (a created session learns
     * it with its first message) or a new title is written into the batch's records and the pick. When the batch
     * has ended ([final]) and the id is still unknown, the pick is cleared: the user chooses again rather than the
     * memo sending into a session it cannot name — and it never falls back to "new session".
     */
    fun onResolved(tx: MemoTx, batchId: String, resolved: MemoTarget?, final: Boolean) {
        val b = tx.m.batch?.takeIf { it.batchId == batchId } ?: return
        val current = b.target
        if (resolved != null && resolved.sessionId.isNotEmpty() && !resolved.newSession &&
            resolved.bindingId == current.bindingId && resolved.workdir == current.workdir && resolved.agent == current.agent &&
            (current.sessionId.isEmpty() || current.sessionId == resolved.sessionId)
        ) {
            val learned = current.sessionId.isEmpty() || current.title != resolved.title
            if (learned) {
                tx.mutate(b.memoId) { d ->
                    d.copy(dispatches = d.dispatches.map { if (it.batchId == batchId) it.copy(target = resolved) else it })
                }
            }
            repick(tx, current, resolved)
            tx.m = tx.m.copy(batch = b.copy(target = resolved))
            return
        }
        if (final && current.sessionId.isEmpty()) {
            val pick = tx.m.target
            if (pick != null && pick.target.sameAs(current)) tx.m = tx.m.copy(target = null)
        }
    }

    /** The pick follows the batch's target from [from] to [to], unless the user has picked something else. */
    private fun repick(tx: MemoTx, from: MemoTarget, to: MemoTarget) {
        val pick = tx.m.target
        if (pick != null && !pick.target.sameAs(from)) return
        val row = tx.m.catalog.loadedRows().firstOrNull { it.target.sameAs(to) }
        tx.m = tx.m.copy(target = MemoTargetRow(to, row?.status ?: MemoTargetStatus.IDLE, mode = pick?.mode ?: row?.mode, lastModifiedMs = row?.lastModifiedMs ?: 0))
    }

    /** An existing session must be exactly the one confirmed; a created one must be a real session on the same
     *  computer, directory and agent. */
    private fun leaseMatches(confirmed: MemoTarget, opened: MemoTarget): Boolean =
        if (confirmed.newSession) {
            !opened.newSession && opened.bindingId == confirmed.bindingId && opened.workdir == confirmed.workdir && opened.agent == confirmed.agent
        } else {
            opened.sameAs(confirmed)
        }

    private fun ended(tx: MemoTx, b: Batch) {
        b.lease?.let { tx.emit(MemoEffect.Resolve(b.batchId, it, final = true)) }
    }

    private fun deliver(tx: MemoTx, b: Batch, promptId: String) {
        tx.emit(MemoEffect.CancelTimer(receiptTimer(promptId)))
        tx.m = tx.m.copy(batch = b.copy(step = BatchStep.STORING_DELIVERED, early = b.early - promptId))
        setRecord(tx, b.memoId, promptId, MemoTodoState.DELIVERED, purpose = Purpose.DeliveredStored(b.batchId, promptId))
    }

    private fun stopBatch(tx: MemoTx, b: Batch, stop: MemoDispatchStop) {
        val stopped = b.copy(phase = MemoDispatchPhase.STOPPED, stop = stop, step = BatchStep.IDLE)
        tx.m = tx.m.copy(batch = stopped)
        ended(tx, stopped)
    }

    private fun setRecord(tx: MemoTx, memoId: String, promptId: String, state: String, errorCode: String? = null, purpose: Purpose? = null) {
        tx.mutate(memoId, purpose) { d ->
            d.copy(
                dispatches = d.dispatches.map {
                    if (it.promptId == promptId) it.copy(state = state, updatedAtMs = tx.now.wall, errorCode = errorCode) else it
                },
            )
        }
    }

    companion object {
        /** errorCode of a FAILED record: the gateway proved the prompt never reached a writer. */
        const val NOT_SUBMITTED = "not_submitted"
    }
}

/** Restart rule: a record left `sending` by a process that is gone has lost its receipt — it becomes `unknown`
 *  (check the chat), never a draft that could be sent twice. `delivered` and `draft` stay as they are. */
internal fun recoverSending(doc: MemoDocument, nowWall: Long): MemoDocument =
    doc.copy(
        dispatches = doc.dispatches.map {
            if (it.state == MemoTodoState.SENDING) it.copy(state = MemoTodoState.UNKNOWN, updatedAtMs = nowWall) else it
        },
    )
