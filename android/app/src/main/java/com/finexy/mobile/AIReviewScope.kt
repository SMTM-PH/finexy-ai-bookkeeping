package com.finexy.mobile

/** The current account AI-review API has no non-default-ledger contract. */
internal const val ACCOUNT_AI_REVIEW_LEDGER_ID = 0L

internal fun requireAIReviewSaveLedger(ledgerId: Long, reviewItemId: Long?) {
    if (reviewItemId == null) return
    require(reviewItemId > 0) { "AI 待复核关联无效，请重新打开待复核队列" }
    require(ledgerId == ACCOUNT_AI_REVIEW_LEDGER_ID) {
        "AI 待复核只能写入默认个人账本；请返回账号待复核队列重新核对。"
    }
}
