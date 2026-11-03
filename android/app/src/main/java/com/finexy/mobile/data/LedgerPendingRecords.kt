package com.finexy.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Length-delimited fields prevent a raw comment from changing the locator's field boundaries. */
internal fun ledgerPendingDigest(vararg fields: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { value ->
        val bytes = value?.toString()?.toByteArray(Charsets.UTF_8)
        digest.update((bytes?.size?.toString() ?: "null").toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte()); if (bytes != null) digest.update(bytes)
        digest.update(';'.code.toByte())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun pendingAIRequestRecord(row: AIReviewRequestEntity): LedgerPendingRecord {
    validateAIReviewRequest(row)
    return LedgerPendingRecord(LedgerPendingTarget.AIRequest(row.ledgerId, row.itemId, row.requestId,
        row.draftId, row.draftRevision, ledgerPendingDigest(row.action, row.payloadJson, row.createdAt, row.sourceImageId)),
        if (row.state == AIReviewRequestEntity.POSTED) LedgerPendingKind.RECEIPT else LedgerPendingKind.RESULT_UNKNOWN,
        if (row.action == "create" && row.state == AIReviewRequestEntity.POSTED) validateAIReviewReceipt(row, row.receiptJson) else null)
}

internal fun pendingAIDraftRecord(row: AIReviewDraftEntity): LedgerPendingRecord {
    validateAIReviewDraft(row)
    val fields = Json.parseToJsonElement(row.formJson) as JsonObject
    val captureOperationId = (fields["captureOperationId"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
    if (captureOperationId != null) require(row.itemId == 0L && aiDraftImageId(row).isNotEmpty()) {
        "拍照提交标记缺少原图引用"
    }
    return LedgerPendingRecord(LedgerPendingTarget.AIDraft(row.ledgerId, row.itemId, row.draftId, row.revision,
        ledgerPendingDigest(row.formJson)), LedgerPendingKind.DRAFT, captureOperationId = captureOperationId)
}

internal fun pendingLedgerWriteRecord(row: LedgerWriteEntity): LedgerPendingRecord {
    require(row.ledgerId > 0 && row.createdAt >= 0)
    require(row.state in setOf(LedgerWriteEntity.PENDING, LedgerWriteEntity.POSTED))
    require(if (row.state == LedgerWriteEntity.POSTED) (row.serverId ?: 0) > 0 else row.serverId == null)
    require(row.payloadJson.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
    val payload = Json.parseToJsonElement(row.payloadJson) as? JsonObject ?: error("持久写入格式不完整")
    require(payload["ledgerId"] == JsonPrimitive(row.ledgerId.toString()))
    require(payload.keys == setOf("ledgerId", "type", "categoryId", "time", "utcOffset", "sourceAccountId",
        "destinationAccountId", "sourceAmount", "destinationAmount", "tagIds", "pictureIds", "hideAmount", "geoLocation", "comment"))
    return LedgerPendingRecord(LedgerPendingTarget.Transaction(row.ledgerId, row.localId, row.createdAt,
        ledgerPendingDigest(row.payloadJson)), if (row.state == LedgerWriteEntity.POSTED) LedgerPendingKind.RECEIPT else LedgerPendingKind.RESULT_UNKNOWN)
}

internal fun pendingTransactionRecord(row: TransactionEntity): LedgerPendingRecord? {
    require(row.syncState in setOf(SyncState.PENDING, SyncState.FAILED, SyncState.CONFLICT, SyncState.SYNCED))
    require(row.reviewItemId == null || row.reviewItemId > 0)
    val needsSync = row.syncState != SyncState.SYNCED
    // Retain the safety boundary even for a deleted, locally synced tombstone.
    // Neither local deletion nor a missing server ID proves the old add never happened.
    val legacyResolution = row.reviewItemId != null
    if (!needsSync && !legacyResolution) return null
    val hash = ledgerPendingDigest(row.serverId, row.timeSequenceId, row.type, row.sourceAccountId,
        row.destinationAccountId, row.categoryId, row.categoryName, row.sourceAmountMinor, row.destinationAmountMinor,
        row.currency, row.comment, row.time, row.utcOffset, row.tagIdsJson, row.syncState, row.deleted, row.updatedAt,
        row.syncedSnapshotJson, row.pictureIdsJson, row.geoLocationJson, row.hideAmount, row.reviewItemId)
    val kind = when {
        row.reviewItemId != null -> LedgerPendingKind.LEGACY_AI
        row.syncState == SyncState.CONFLICT -> LedgerPendingKind.CONFLICT
        else -> LedgerPendingKind.LOCAL_SYNC
    }
    return LedgerPendingRecord(LedgerPendingTarget.Transaction(0, row.localId, row.updatedAt, hash, row.reviewItemId), kind)
}

internal fun pendingLegacyAIRecord(row: AIReviewItemEntity): LedgerPendingRecord {
    require(row.status == AIReviewItemEntity.STATUS_PENDING && row.createdUnixTime >= 0)
    val hash = ledgerPendingDigest(row.sourceType, row.status, row.sourceText, row.recognizedDataJson, row.failureReason)
    return LedgerPendingRecord(LedgerPendingTarget.LegacyAIItem(0, row.id, row.createdUnixTime, hash), LedgerPendingKind.LEGACY_AI)
}

internal fun pendingTemplateRequestRecord(row: TemplateCreationEntity): LedgerPendingRecord {
    validateTemplateCreation(row)
    return LedgerPendingRecord(LedgerPendingTarget.Template(row.ledgerId, row.templateType, row.clientSessionId,
        row.draftId, row.draftRevision, ledgerPendingDigest(row.payloadJson, row.createdAt)),
        if (row.state == TemplateCreationEntity.POSTED) LedgerPendingKind.RECEIPT else LedgerPendingKind.RESULT_UNKNOWN)
}

internal fun pendingTemplateDraftRecord(row: TemplateFormDraftEntity): LedgerPendingRecord {
    validateTemplateDraft(row)
    return LedgerPendingRecord(LedgerPendingTarget.Template(row.ledgerId, row.templateType, null, row.draftId, row.revision,
        ledgerPendingDigest(row.editingId, row.formJson)), LedgerPendingKind.DRAFT)
}

internal fun pendingPeriodicActionRecord(row: PeriodicAction): LedgerPendingRecord = LedgerPendingRecord(
    LedgerPendingTarget.Occurrence(row.ledgerId, row.templateId, row.scheduledUnixTime, row.action, row.queuedAt),
    LedgerPendingKind.RESULT_UNKNOWN)

internal fun pendingCaptureRecord(row: AIPendingCapture): LedgerPendingRecord = LedgerPendingRecord(
    LedgerPendingTarget.Capture(row.ledgerId, row.operationId, row.draftId, row.revision, row.formHash), LedgerPendingKind.CAPTURE)
