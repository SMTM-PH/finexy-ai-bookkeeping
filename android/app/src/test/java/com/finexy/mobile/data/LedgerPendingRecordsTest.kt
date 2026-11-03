package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LedgerPendingRecordsTest {
    private val secret = "synthetic-private-input-728164"
    private fun transaction() = TransactionEntity("original-local-id", type = 3, categoryName = "private-category",
        sourceAmountMinor = 728164, comment = secret, time = 1790000000000, updatedAt = 100)
    private fun aiPayload() = buildJsonObject {
        put("ledgerId", "9"); put("clientSessionId", "original-request"); put("sourceType", 1)
        put("sourceText", secret); put("failureReason", "")
    }.toString()

    @Test fun localDeletionIntentIsIncludedAndLocatorChangesWithEveryRelevantEdit() {
        val row = transaction().copy(deleted = true)
        val original = pendingTransactionRecord(row)!!
        assertEquals(LedgerPendingKind.LOCAL_SYNC, original.kind)
        assertEquals("original-local-id", (original.target as LedgerPendingTarget.Transaction).localId)
        assertNotEquals(original.target, pendingTransactionRecord(row.copy(comment = "$secret-edited"))!!.target)
        assertNotEquals(original.target, pendingTransactionRecord(row.copy(sourceAmountMinor = 1))!!.target)
        assertNotEquals(original.target, pendingTransactionRecord(row.copy(deleted = false))!!.target)
        assertNotEquals(original.target, pendingTransactionRecord(row.copy(updatedAt = 101))!!.target)
        assertNull(pendingTransactionRecord(row.copy(syncState = SyncState.SYNCED)))
        assertFalse(original.toString().contains(secret)); assertFalse(original.toString().contains("728164"))
    }

    @Test fun legacyUnknownWriteNeverBecomesOrdinarySyncAndResolvedHistoryIsNotPending() {
        val unknown = transaction().copy(reviewItemId = 81)
        assertEquals(LedgerPendingKind.LEGACY_AI, pendingTransactionRecord(unknown)!!.kind)
        val waitingResolve = unknown.copy(serverId = 9007199254740993L, syncState = SyncState.SYNCED)
        assertEquals(LedgerPendingKind.LEGACY_AI, pendingTransactionRecord(waitingResolve)!!.kind)
        assertEquals(81L, (pendingTransactionRecord(waitingResolve)!!.target as LedgerPendingTarget.Transaction).relatedAIItemId)
        assertNull(pendingTransactionRecord(waitingResolve.copy(reviewItemId = null)))
        // Only an explicitly cleared association is resolved. Local deletion
        // or a SYNCED marker cannot prove an old unknown upload never committed.
        assertEquals(LedgerPendingKind.LEGACY_AI, pendingTransactionRecord(waitingResolve.copy(deleted = true))!!.kind)
        assertEquals(LedgerPendingKind.LEGACY_AI, pendingTransactionRecord(unknown.copy(syncState = SyncState.SYNCED))!!.kind)
        assertEquals(LedgerPendingKind.CONFLICT, pendingTransactionRecord(transaction().copy(syncState = SyncState.CONFLICT))!!.kind)
    }

    @Test fun rawAIAndTemplateInputRemainOnlyHashedMetadata() {
        val ai = pendingAIDraftRecord(AIReviewDraftEntity(9, 0, "draft", 1, "{\"sourceText\":\"$secret\",\"amount\":\"1.+\"}", 1))
        val template = pendingTemplateDraftRecord(TemplateFormDraftEntity(9, 1, "draft", 1, 71, "{\"name\":\"$secret\"}", 1))
        assertFalse(ai.toString().contains(secret)); assertFalse(template.toString().contains(secret))
        assertEquals(LedgerPendingKind.DRAFT, ai.kind)
        val editedTemplate = pendingTemplateDraftRecord(TemplateFormDraftEntity(9, 1, "draft", 1, 72, "{\"name\":\"$secret\"}", 1))
        assertNotEquals(template.target, editedTemplate.target)
        assertThrows(IllegalArgumentException::class.java) {
            pendingAIDraftRecord(AIReviewDraftEntity(9, 0, "draft", 1, "{\"amount\":123}", 1))
        }
    }

    @Test fun captureLinkMarkerMustBeCanonicalAndBoundToSourceDraftWithValidImageReference() {
        val operation = "b8a11e38-6b52-4c77-93c2-4e256a05d901"
        val imageId = "83edc6aa-30d5-4c5b-bf6d-45a5f1012764"
        fun draft(marker: String, image: String = imageId, item: Long = 0) = AIReviewDraftEntity(9, item, "source", 2,
            "{\"captureOperationId\":\"$marker\",\"sourceImageId\":\"$image\",\"sourceText\":\"$secret\"}", 2)
        val record = pendingAIDraftRecord(draft(operation))
        assertEquals(operation, record.captureOperationId)
        assertFalse(record.toString().contains(imageId)); assertFalse(record.toString().contains(secret))
        for (invalid in listOf(draft("not-an-operation"), draft(operation.uppercase()), draft("1-1-1-1-1"),
            draft(operation, image = ""), draft(operation, image = "invalid"), draft(operation, item = 81))) {
            assertThrows(IllegalArgumentException::class.java) { pendingAIDraftRecord(invalid) }
        }
        assertNull(pendingAIDraftRecord(draft("", image = "")).captureOperationId)
    }

    @Test fun postedAICreateKeepsOriginalTargetAndSuppliesRemoteDedupBridge() {
        val pending = AIReviewRequestEntity(9, 0, "original-request", "draft", 1, "create", aiPayload(), createdAt = 1)
        val item = LedgerAIReviewItem(9, 9007199254740993L, 11, 0, 0, true, false, 1, 1, secret, null, "", 100)
        val before = pendingAIRequestRecord(pending)
        val after = pendingAIRequestRecord(pending.copy(state = AIReviewRequestEntity.POSTED, receiptJson = item.toStoredJson()))
        assertEquals(before.target, after.target)
        assertEquals(LedgerPendingKind.RESULT_UNKNOWN, before.kind)
        assertEquals(LedgerPendingKind.RECEIPT, after.kind)
        assertEquals(item.id, after.resultingAIItemId)
        assertFalse(after.toString().contains(secret))
        assertThrows(IllegalArgumentException::class.java) {
            pendingAIRequestRecord(pending.copy(state = AIReviewRequestEntity.POSTED, receiptJson = item.copy(ledgerId = 10).toStoredJson()))
        }
    }

    @Test fun sharedWriteReceiptKeepsImmutableLocatorAndDoesNotPretendToBeRawDraft() {
        val payload = buildJsonObject {
            put("ledgerId", "9"); put("type", 3); put("categoryId", "20"); put("time", 1790000000)
            put("utcOffset", 480); put("sourceAccountId", "10"); put("destinationAccountId", "0")
            put("sourceAmount", 728164); put("destinationAmount", 0); put("tagIds", JsonArray(emptyList()))
            put("pictureIds", JsonArray(emptyList())); put("hideAmount", false); put("geoLocation", JsonNull); put("comment", secret)
        }.toString()
        val row = LedgerWriteEntity(9, "original-local-id", payload, createdAt = 100)
        val before = pendingLedgerWriteRecord(row)
        val after = pendingLedgerWriteRecord(row.copy(state = LedgerWriteEntity.POSTED, serverId = 91))
        assertEquals(before.target, after.target)
        assertEquals(LedgerPendingKind.RESULT_UNKNOWN, before.kind)
        assertEquals(LedgerPendingKind.RECEIPT, after.kind)
        assertFalse(after.toString().contains(secret)); assertFalse(after.toString().contains("728164"))
        assertThrows(IllegalArgumentException::class.java) { pendingLedgerWriteRecord(row.copy(ledgerId = 10)) }
        assertThrows(IllegalArgumentException::class.java) { pendingLedgerWriteRecord(row.copy(state = LedgerWriteEntity.POSTED)) }
    }

    @Test fun digestCannotConfuseFieldBoundariesOrNullWithLiteralText() {
        assertNotEquals(ledgerPendingDigest("a;b", "c"), ledgerPendingDigest("a", "b;c"))
        assertNotEquals(ledgerPendingDigest(null), ledgerPendingDigest("null"))
    }
}
