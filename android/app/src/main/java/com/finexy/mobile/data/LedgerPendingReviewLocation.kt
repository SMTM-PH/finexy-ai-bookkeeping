package com.finexy.mobile.data

internal fun LedgerPendingTarget.reviewItemId(): Long? = when (this) {
    is LedgerPendingTarget.AIItem -> itemId
    is LedgerPendingTarget.AIDraft -> itemId.takeIf { it > 0 }
    is LedgerPendingTarget.AIRequest -> itemId.takeIf { it > 0 }
    else -> null
}

/** A source or another editor can never substitute for the selected review locator. */
internal fun matchPendingReviewLocation(target: LedgerPendingTarget, ledgerId: Long, itemId: Long,
    draft: AIReviewDraftEntity?, request: AIReviewRequestEntity?): Boolean = runCatching {
    if (target.ledgerId != ledgerId || target.reviewItemId() != itemId || itemId <= 0) return@runCatching false
    if (draft != null) {
        require(draft.ledgerId == ledgerId && draft.itemId == itemId)
        pendingAIDraftRecord(draft)
    }
    if (request != null) {
        require(request.ledgerId == ledgerId && request.itemId == itemId && request.action == "confirm")
        pendingAIRequestRecord(request)
    }
    when (target) {
        is LedgerPendingTarget.AIItem -> true
        is LedgerPendingTarget.AIDraft -> draft != null && pendingAIDraftRecord(draft).target == target &&
            (request == null || request.draftId == target.draftId && request.draftRevision == target.revision)
        is LedgerPendingTarget.AIRequest -> request != null && pendingAIRequestRecord(request).target == target
        else -> false
    }
}.getOrDefault(false)

/** Receipt/error changes preserve the immutable request. A new key or payload does not. */
internal fun samePendingReviewRequest(expected: AIReviewRequestEntity?, actual: AIReviewRequestEntity?): Boolean = runCatching {
    if (expected == null || actual == null) expected == actual
    else pendingAIRequestRecord(expected).target == pendingAIRequestRecord(actual).target
}.getOrDefault(false)

/** Check the fresh Room entity before parsing or revealing any raw editor field. */
internal fun restoreExpectedAIReviewEditor(expected: AIReviewDraftEntity, actual: AIReviewDraftEntity?): AIReviewEditorForm {
    require(actual == expected) { "原编辑草稿已变化，请返回重新读取" }
    return AIReviewEditorForm.read(requireNotNull(actual))
}

/** A previously observed receipt must never downgrade to an uploadable request. */
internal fun requireOriginalAIReviewDelivery(expected: AIReviewRequestEntity, actual: AIReviewRequestEntity?): AIReviewRequestEntity {
    require(actual != null && samePendingReviewRequest(expected, actual)) { "原 AI 请求已变化或已处理，请重新核对" }
    if (expected.state == AIReviewRequestEntity.POSTED) require(actual.state == AIReviewRequestEntity.POSTED && actual.receiptJson == expected.receiptJson) {
        "原 AI 回执已变化，只能重新核对，不能再次提交"
    }
    return actual
}
