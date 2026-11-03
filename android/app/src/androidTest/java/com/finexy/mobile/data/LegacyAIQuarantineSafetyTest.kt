package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real Room regression; no HTTP, sync, WorkManager or main application data. */
@RunWith(AndroidJUnit4::class)
class LegacyAIQuarantineSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
    private fun legacy(localId: String = "legacy-original") = TransactionEntity(localId, type = 3, sourceAccountId = 10,
        categoryId = 20, categoryName = "Synthetic category", sourceAmountMinor = 123, comment = "Synthetic original",
        time = 1790000000000, updatedAt = 10, reviewItemId = 71)
    private fun confirmation() = buildJsonObject {
        put("ledgerId", "0"); put("id", "71")
        put("transaction", buildJsonObject {
            put("ledgerId", "0"); put("clientSessionId", "original-confirmation"); put("type", 3)
            put("time", 1790000000); put("utcOffset", 480); put("categoryId", "20")
            put("sourceAccountId", "10"); put("destinationAccountId", "0")
            put("sourceAmount", 123); put("destinationAmount", 0); put("hideAmount", false)
            put("tagIds", JsonArray(emptyList())); put("pictureIds", JsonArray(emptyList())); put("comment", "Synthetic original")
        })
    }.toString()
    private suspend fun draft(persistence: AIReviewPersistence) = persistence.saveDraft(0, 71, "{\"sourceAmount\":\"1.+\"}")

    @Test fun localDeleteOfUnknownLegacyWriteRetainsBarrierAndPendingLocator() = runBlocking<Unit> {
        val db = database()
        try {
            val repo = TransactionRepository(context, db, false); val original = legacy()
            db.dao().upsertTransaction(original)
            repo.markDeleted(original.localId)
            val tombstone = requireNotNull(db.dao().findTransaction(original.localId))
            assertTrue(tombstone.deleted); assertEquals(SyncState.SYNCED, tombstone.syncState)
            assertEquals(original.sourceAmountMinor, tombstone.sourceAmountMinor)
            assertEquals(original.comment, tombstone.comment)
            assertEquals(original.reviewItemId, tombstone.reviewItemId)
            assertNull(tombstone.serverId)
            assertTrue(db.dao().hasLegacyAIReviewTransaction(71))
            val record = requireNotNull(pendingTransactionRecord(tombstone))
            assertEquals(LedgerPendingKind.LEGACY_AI, record.kind)
            assertEquals(71L, (record.target as LedgerPendingTarget.Transaction).relatedAIItemId)
        } finally { db.close() }
    }

    @Test fun deletedUnknownLegacyWriteStillBlocksNewAtomicConfirmationEnqueue() = runBlocking<Unit> {
        val db = database()
        try {
            val repo = TransactionRepository(context, db, false)
            val original = legacy().copy(deleted = true, syncState = SyncState.SYNCED)
            db.dao().upsertTransaction(original)
            val saved = draft(repo.aiReview)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.aiReview.enqueue(saved, "confirm", confirmation()) } }
            assertTrue(repo.aiReview.requests().isEmpty())
            assertEquals(saved, repo.aiReview.draft(0, 71))
            assertEquals(original, db.dao().findTransaction(original.localId))
        } finally { db.close() }
    }

    @Test fun restoringDeletedLegacyTombstoneAfterEnqueueBlocksUploadWithoutReplacingOriginalRequest() = runBlocking<Unit> {
        val db = database()
        try {
            val repo = TransactionRepository(context, db, false)
            val request = repo.aiReview.enqueue(draft(repo.aiReview), "confirm", confirmation())
            val original = legacy().copy(deleted = true, syncState = SyncState.SYNCED)
            db.dao().upsertTransaction(original)
            var uploads = 0; var reads = 0
            val result = AIReviewDelivery(repo.aiReview, { uploads++; error("Unknown legacy write must block posting") },
                { _, _ -> reads++; error("There is no confirmed receipt") }).push()
            assertEquals(0, uploads); assertEquals(0, reads); assertEquals(1, result.errors.size)
            val remaining = repo.aiReview.requests().single()
            assertEquals(request.requestId, remaining.requestId)
            assertEquals(request.payloadJson, remaining.payloadJson)
            assertEquals(AIReviewRequestEntity.PENDING, remaining.state)
            assertEquals(original, db.dao().findTransaction(original.localId))
        } finally { db.close() }
    }

    @Test fun pendingListAbsenceNeverClearsKnownUnknownOrDeletedLegacyTransactionAssociations() = runBlocking<Unit> {
        val db = database()
        try {
            val repo = TransactionRepository(context, db, false)
            val rows = listOf(legacy("unknown"), legacy("known").copy(serverId = 9007199254740993L, syncState = SyncState.SYNCED),
                legacy("deleted-unknown").copy(deleted = true, syncState = SyncState.SYNCED),
                legacy("deleted-known").copy(serverId = 9007199254740995L, deleted = true, syncState = SyncState.SYNCED))
            rows.forEach { db.dao().upsertTransaction(it) }
            repo.cacheAIReviewItem(RemoteAIReviewItem(71, 1, 1, "Synthetic old source", "", "", 1))
            repo.replaceAIReviewItems(emptyList())
            // Preserve the existing cache contract, without treating it as transaction proof.
            assertTrue(repo.observeAIReviewItems().first().isEmpty())
            for (original in rows) assertEquals(original, db.dao().findTransaction(original.localId))
            assertTrue(db.dao().hasLegacyAIReviewTransaction(71))
            assertEquals(4, rows.mapNotNull(::pendingTransactionRecord).size)
        } finally { db.close() }
    }

    @Test fun knownAtomicReceiptCanBeReadWithoutErasingUncertainLegacyHistory() = runBlocking<Unit> {
        val db = database()
        try {
            val repo = TransactionRepository(context, db, false)
            val request = repo.aiReview.enqueue(draft(repo.aiReview), "confirm", confirmation())
            val transactionId = 9007199254740993L
            val receipt = "{\"ledgerId\":\"0\",\"id\":\"71\",\"transactionId\":\"$transactionId\"}"
            assertTrue(repo.aiReview.markPosted(request, receipt))
            val original = legacy().copy(deleted = true, syncState = SyncState.SYNCED)
            db.dao().upsertTransaction(original)
            var uploads = 0; var reads = 0
            val result = AIReviewDelivery(repo.aiReview, { uploads++; error("Known receipt must never POST") }, { ledgerId, itemId ->
                reads++; assertEquals(0L, ledgerId); assertEquals(71L, itemId)
                LedgerAIReviewItem(0, 71, 1, transactionId, 1, true, false, 1, 2, "Synthetic source", null, "", 1)
            }).push()
            assertTrue(result.errors.isEmpty()); assertEquals(0, uploads); assertEquals(1, reads); assertEquals(1, result.acknowledged)
            assertTrue(repo.aiReview.requests().isEmpty())
            assertEquals(original, db.dao().findTransaction(original.localId))
            assertTrue(db.dao().hasLegacyAIReviewTransaction(71))
            assertNotNull(pendingTransactionRecord(original))
        } finally { db.close() }
    }

    @Test fun unrelatedLocalTransactionDoesNotBlockAnExplicitAtomicRequest() = runBlocking<Unit> {
        val db = database()
        try {
            val repo = TransactionRepository(context, db, false)
            val ordinary = legacy().copy(reviewItemId = null, deleted = true, syncState = SyncState.SYNCED)
            db.dao().upsertTransaction(ordinary)
            assertFalse(db.dao().hasLegacyAIReviewTransaction(71))
            assertNull(pendingTransactionRecord(ordinary))
            val request = repo.aiReview.enqueue(draft(repo.aiReview), "confirm", confirmation())
            assertEquals(AIReviewRequestEntity.PENDING, request.state)
            assertEquals(ordinary, db.dao().findTransaction(ordinary.localId))
        } finally { db.close() }
    }
}
