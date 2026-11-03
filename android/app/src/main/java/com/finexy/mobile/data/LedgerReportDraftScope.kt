package com.finexy.mobile.data

/** Memory-only ownership metadata. Identity is a partition hash, never a session token. */
internal data class LedgerReportDraftOwner(
    val namespace: String,
    val serverUrl: String,
    val identity: String,
    val ledgerId: Long,
    val localMode: Boolean
)

/** Revision also changes on A -> B -> A, even when an observer only receives the last A. */
internal data class LedgerReportDraftScope(val owner: LedgerReportDraftOwner?, val revision: Long)

/** Only raw editable choices; no report, resource names, confirmation, delivery or URI. */
internal data class LedgerReportDraftChoices(
    val scope: LedgerReportDraftScope,
    val form: LedgerReportForm,
    val amountsVisible: Boolean,
    val pendingTag: LedgerReportTagChoice = LedgerReportTagChoice("any")
)

internal fun rebindLedgerReportDraft(
    current: LedgerReportDraftChoices?,
    next: LedgerReportDraftScope,
    showAmountsInitially: Boolean,
    initialForm: () -> LedgerReportForm = { LedgerReportForm.currentMonth() }
): LedgerReportDraftChoices {
    if (current?.scope == next) return current
    // A preference supplied by an earlier owner cannot reveal a later owner's values.
    val firstKnownOwner = current == null && next.owner != null && next.revision == 1L
    return LedgerReportDraftChoices(next, initialForm(), firstKnownOwner && showAmountsInitially)
}
