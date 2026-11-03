package com.finexy.mobile.data

/** Only an exact original source/request may reveal its page. An unrelated capture
 * remains explicitly disposable without exposing the replacement source. */
internal enum class LedgerPendingSourceMatch { EXACT, CAPTURE_COMMITTED, CAPTURE_ONLY, UNAVAILABLE }

internal fun LedgerPendingTarget.isSourceLocation(): Boolean = when (this) {
    is LedgerPendingTarget.AIDraft -> itemId == 0L
    is LedgerPendingTarget.AIRequest -> itemId == 0L
    is LedgerPendingTarget.Capture -> true
    else -> false
}

internal fun pendingCaptureIsCommitted(capture: AIPendingCapture, saved: AIReviewDraftEntity?): Boolean = runCatching {
    saved != null && saved.ledgerId == capture.ledgerId && saved.itemId == 0L && saved.draftId == capture.draftId &&
        saved.revision > capture.revision && pendingAIDraftRecord(saved).captureOperationId == capture.operationId
}.getOrDefault(false)

/** Uses the same production projections as the overview. No raw source is returned. */
internal fun matchPendingSourceLocation(target: LedgerPendingTarget, saved: AIReviewDraftEntity?,
    request: AIReviewRequestEntity?, capture: AIPendingCapture?): LedgerPendingSourceMatch = runCatching {
    when (target) {
        is LedgerPendingTarget.AIDraft -> if (target.itemId == 0L && saved != null && pendingAIDraftRecord(saved).target == target)
            LedgerPendingSourceMatch.EXACT else LedgerPendingSourceMatch.UNAVAILABLE
        is LedgerPendingTarget.AIRequest -> if (target.itemId == 0L && request != null && pendingAIRequestRecord(request).target == target)
            LedgerPendingSourceMatch.EXACT else LedgerPendingSourceMatch.UNAVAILABLE
        is LedgerPendingTarget.Capture -> when {
            capture == null || pendingCaptureRecord(capture).target != target -> LedgerPendingSourceMatch.UNAVAILABLE
            pendingCaptureIsCommitted(capture, saved) -> LedgerPendingSourceMatch.CAPTURE_COMMITTED
            capture.matches(saved) -> LedgerPendingSourceMatch.EXACT
            else -> LedgerPendingSourceMatch.CAPTURE_ONLY
        }
        else -> LedgerPendingSourceMatch.UNAVAILABLE
    }
}.getOrDefault(LedgerPendingSourceMatch.UNAVAILABLE)

internal fun pendingSourceOwnsDraft(target: LedgerPendingTarget, saved: AIReviewDraftEntity?,
    request: AIReviewRequestEntity?, capture: AIPendingCapture?): Boolean = when (target) {
    is LedgerPendingTarget.AIRequest -> saved != null && request != null && saved.ledgerId == target.ledgerId && saved.itemId == 0L &&
        saved.draftId == request.draftId && saved.revision == request.draftRevision &&
        matchPendingSourceLocation(target, saved, request, capture) == LedgerPendingSourceMatch.EXACT
    else -> matchPendingSourceLocation(target, saved, request, capture) in
        setOf(LedgerPendingSourceMatch.EXACT, LedgerPendingSourceMatch.CAPTURE_COMMITTED)
}
