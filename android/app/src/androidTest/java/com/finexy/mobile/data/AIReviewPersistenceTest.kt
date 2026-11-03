package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AIReviewPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "synthetic-ai-backup-password"
    private val partial = "{\"sourceText\":\"Synthetic receipt\",\"amount\":\"1.+\",\"accountId\":\"\",\"timeZone\":\"Asia/Shanghai\"}"
    private fun item(ledger: Long = 9, id: Long = 101, status: Int = 1, deleted: Boolean = false) = LedgerAIReviewItem(
        ledger, id, 9007199254740993L, if (status == 2) 201 else 0, if (status == 2) 9007199254740993L else 0,
        !deleted && status != 2, deleted, 1, status, "Synthetic receipt", null, "", 1788700000)
    private fun create(ledger: Long = 9) = JSONObject().put("ledgerId", ledger.toString()).put("clientSessionId", UUID.randomUUID().toString())
        .put("sourceType", 1).put("sourceText", "Synthetic receipt").put("failureReason", "").toString()
    private fun confirm(ledger: Long = 9, id: Long = 101) = JSONObject().put("ledgerId", ledger.toString()).put("id", id.toString())
        .put("transaction", JSONObject().put("ledgerId", ledger.toString()).put("clientSessionId", UUID.randomUUID().toString())
            .put("type", 3).put("time", 1788700000).put("utcOffset", 480).put("categoryId", "20")
            .put("sourceAccountId", "10").put("destinationAccountId", "0").put("sourceAmount", -123)
            .put("destinationAmount", 0).put("hideAmount", true).put("tagIds", JSONArray()).put("pictureIds", JSONArray()).put("comment", "Synthetic refund")).toString()
    private fun receipt(ledger: Long = 9, id: Long = 101, transaction: Long = 201) = JSONObject().put("ledgerId", ledger.toString())
        .put("id", id.toString()).put("transactionId", transaction.toString()).toString()
    private suspend fun queued(store: AIReviewPersistence, ledger: Long = 9, id: Long = 0): Pair<AIReviewDraftEntity, AIReviewRequestEntity> {
        val draft = store.saveDraft(ledger, id, partial)
        return draft to store.enqueue(draft, if (id == 0L) "create" else "confirm", if (id == 0L) create(ledger) else confirm(ledger, id))
    }
    private fun rejected(block: suspend () -> Unit) { assertThrows(RuntimeException::class.java) { runBlocking { block() } } }

    @Test fun migrationTwentyFourPreservesLegacyRowsAndCreatesNoConfirmedIntent() = runBlocking<Unit> {
        val name = "ai-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val transaction = TransactionEntity("kept", type = 3, categoryName = "Synthetic", sourceAmountMinor = 123, comment = "Synthetic", time = 1)
            val legacy = AIReviewItemEntity(9007199254740993L, 1, 1, "Synthetic legacy", createdUnixTime = 1)
            val template = TemplateFormDraftEntity(9, 1, "original", 1, 0, "{\"amount\":\"1.\"}", 1)
            db.dao().upsertTransaction(transaction); db.dao().upsertAIReviewItems(listOf(legacy)); db.dao().upsertTemplateDraft(template)
            val sqlite = db.openHelper.writableDatabase
            listOf("ledger_ai_review_cache", "ai_review_drafts", "ai_review_requests").forEach { sqlite.execSQL("DROP TABLE $it") }
            sqlite.execSQL("DROP TABLE IF EXISTS ai_review_image_chunks"); sqlite.execSQL("DROP TABLE IF EXISTS ai_review_images"); sqlite.execSQL("DROP TABLE room_master_table"); sqlite.execSQL("PRAGMA user_version=24")
            db.close()
            db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).addMigrations(FinexyDatabase.MIGRATION_24_25, FinexyDatabase.MIGRATION_25_26).build()
            assertEquals(transaction, db.dao().findTransaction("kept")); assertEquals(template, db.dao().findTemplateDraft(9, 1))
            assertEquals(legacy, db.dao().observeAIReviewItems().first().single())
            assertTrue(db.dao().allAIReviewRequests().isEmpty()); assertNull(db.dao().findAIReviewDraft(9, 0))
            assertTrue(db.dao().observeLedgerAIReview(9, 1).first().isEmpty())
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(26, it.getInt(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun rawDraftsRemainLocalAcrossPartitionsAndDatabaseReopen() = runBlocking<Unit> {
        val name = "ai-drafts-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val store = TransactionRepository(context, db, false).aiReview
            val drafts = listOf(0L to 0L, 9L to 0L, 9L to 101L, 10L to 101L).map { (ledger, id) -> store.saveDraft(ledger, id, partial) }
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            val recovered = TransactionRepository(context, db, false).aiReview
            for (draft in drafts) assertEquals(draft, recovered.observeDraft(draft.ledgerId, draft.itemId).first())
            var posts = 0
            val result = AIReviewDelivery(recovered, { posts++; error("Unconfirmed input must not upload") }, { _, _ -> error("No receipt") }).push()
            assertEquals(0, result.posted); assertEquals(0, posts); assertTrue(recovered.requests().isEmpty())
            assertTrue(db.dao().allTransactions().isEmpty())
            rejected { recovered.saveDraft(9, 999, "{\"amount\":1.5}") }
            rejected { recovered.saveDraft(9, 999, "{private malformed draft") }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun frozenConfirmationCannotBeReplacedAndOldReceiptKeepsNewerRawInput() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val store = TransactionRepository(context, db, false).aiReview
            val (draft, write) = queued(store)
            assertEquals(write, store.enqueue(draft, "create", write.payloadJson))
            rejected { store.enqueue(draft, "create", create()) }; rejected { store.discardDraft(draft) }
            val newer = store.saveDraft(9, 0, "{\"amount\":\"99.\"}", draft)
            rejected { store.saveDraft(9, 0, partial, draft) }; rejected { store.enqueue(newer, "create", create()) }
            assertFalse(store.markPosted(write.copy(requestId = "other"), item().toStoredJson()))
            assertTrue(store.markPosted(write, item().toStoredJson()))
            val posted = store.requests().single()
            assertTrue(store.acknowledge(posted, item()))
            assertEquals(newer, store.draft(9, 0)); assertTrue(store.requests().isEmpty())
            assertEquals(101L, store.observeItems(9).first().single().id)
            assertTrue(store.discardDraft(newer))
        } finally { db.close() }
    }

    @Test fun persistedReceiptSurvivesFailedReadAndReopenWithoutReposting() = runBlocking<Unit> {
        val name = "ai-receipt-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            var store = TransactionRepository(context, db, false).aiReview
            val (draft, _) = queued(store, 0)
            var posts = 0
            val first = AIReviewDelivery(store, { posts++; item(0).toStoredJson() }, { _, _ -> throw IOException("Synthetic private response marker") }).push()
            assertEquals(1, first.posted); assertEquals(1, first.errors.size)
            assertEquals(AIReviewRequestEntity.POSTED, store.requests().single().state)
            assertEquals("refresh-failed", store.requests().single().lastErrorCode)
            assertEquals(draft, store.draft(0, 0))
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            store = TransactionRepository(context, db, false).aiReview
            val next = AIReviewDelivery(store, { posts++; error("Known receipt must not post") }, { ledger, id -> item(ledger, id) }).push()
            assertEquals(1, next.acknowledged); assertEquals(1, posts); assertTrue(store.requests().isEmpty()); assertNull(store.draft(0, 0))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun unknownResultReplaysOriginalBytesAndStableKeyAfterReopen() = runBlocking<Unit> {
        val name = "ai-unknown-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            var store = TransactionRepository(context, db, false).aiReview
            val write = queued(store).second
            var calls = 0; val seen = mutableSetOf<String>()
            val upload: suspend (AIReviewRequestEntity) -> String = {
                calls++; assertEquals(write.requestId, it.requestId); assertEquals(write.payloadJson, it.payloadJson)
                seen += JSONObject(it.payloadJson).getString("clientSessionId")
                if (calls == 1) throw IOException("Synthetic loss after commit")
                item().toStoredJson()
            }
            assertEquals(1, AIReviewDelivery(store, upload, { ledger, id -> item(ledger, id) }).push().errors.size)
            assertEquals(AIReviewRequestEntity.PENDING, store.requests().single().state)
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            store = TransactionRepository(context, db, false).aiReview
            assertEquals(write.payloadJson, store.requests().single().payloadJson)
            assertEquals(1, AIReviewDelivery(store, upload, { ledger, id -> item(ledger, id) }).push().acknowledged)
            assertEquals(2, calls); assertEquals(1, seen.size); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun confirmationReceiptMustMatchOriginalTransactionAndLedger() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val store = TransactionRepository(context, db, false).aiReview
            val write = queued(store, id = 101).second
            rejected { store.markPosted(write, receipt(10)) }; assertEquals(AIReviewRequestEntity.PENDING, store.requests().single().state)
            assertTrue(store.markPosted(write, receipt()))
            val posted = store.requests().single()
            rejected { store.acknowledge(posted, item(status = 2).copy(transactionId = 202)) }
            rejected { store.acknowledge(posted, item(10, status = 2)) }
            assertEquals(posted, store.requests().single()); assertNotNull(store.draft(9, 101))
            assertTrue(store.acknowledge(posted, item(status = 2)))
            assertEquals(-123, JSONObject(write.payloadJson).getJSONObject("transaction").getInt("sourceAmount"))
            val deleted = queued(store, ledger = 10).second
            assertTrue(store.markPosted(deleted, item(10, deleted = true).toStoredJson()))
            assertTrue(store.acknowledge(store.requests().single(), item(10, deleted = true)))
            assertTrue(store.observeItems(10).first().isEmpty()); assertEquals(1, store.observeItems(9, 2).first().size)
        } finally { db.close() }
    }

    @Test fun fullSnapshotOnlyPrunesItsLedgerAndStatusAndNeverAcknowledgesRequests() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val store = TransactionRepository(context, db, false).aiReview
            store.replaceSnapshot(9, 1, listOf(item())); store.replaceSnapshot(10, 1, listOf(item(10)))
            store.replaceSnapshot(9, 3, listOf(item(id = 103, status = 3)))
            val write = queued(store).second
            rejected { store.replaceSnapshot(9, 1, listOf(item(10))) }
            rejected { store.replaceSnapshot(9, 1, listOf(item(), item())) }
            rejected { store.replaceSnapshot(9, 1, listOf(item(deleted = true))) }
            store.replaceSnapshot(9, 1, listOf(item(id = 102)))
            assertEquals(listOf(102L), store.observeItems(9).first().map { it.id })
            store.replaceSnapshot(9, 1, emptyList())
            assertTrue(store.observeItems(9).first().isEmpty()); assertEquals(1, store.observeItems(10).first().size)
            assertEquals(1, store.observeItems(9, 3).first().size); assertEquals(write, store.requests().single())
            store.replaceSnapshot(9, 2, listOf(item(id = 102, status = 2)))
            store.replaceSnapshot(9, 1, listOf(item(id = 102)))
            assertTrue(store.observeItems(9).first().isEmpty())
            rejected { store.replaceSnapshot(9, 2, listOf(item(id = 102, status = 2).copy(transactionId = 999))) }
            assertEquals(201L, validateLedgerAICache(store.observeItems(9, 2).first().single()).transactionId)
        } finally { db.close() }
    }

    @Test fun currentSchemaPreservesDraftsRequestsReceiptsAndNewerDeviceState() = runBlocking<Unit> {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val store = TransactionRepository(context, source, false).aiReview
            val (draft, write) = queued(store)
            val confirmed = queued(store, id = 101).second
            store.markPosted(confirmed, receipt())
            store.replaceSnapshot(10, 1, listOf(item(10)))
            val backup = LedgerBackup(target, "synthetic-scope")
            val document = backup.preview(LedgerBackup(source, "synthetic-scope").export(password), password)
            assertEquals(20, document.getInt("schema")); backup.restore(document)
            val restored = TransactionRepository(context, target, false).aiReview
            assertEquals(store.requests(), restored.requests()); assertEquals(draft, restored.draft(9, 0))
            assertEquals(1, restored.observeItems(10).first().size)
            val newer = restored.saveDraft(9, 0, "{\"amount\":\"999.\"}", draft)
            restored.markPosted(write, item().toStoredJson())
            backup.restore(document)
            assertEquals(newer, restored.draft(9, 0)); assertTrue(restored.requests().all { it.state == AIReviewRequestEntity.POSTED })
            for (mutate in listOf<(JSONObject) -> Unit>(
                { it.put("scope", "foreign") },
                { it.getJSONObject("tables").getJSONArray("ai_review_requests").getJSONObject(0).put("payloadJson", create(10)) },
                { it.getJSONObject("tables").getJSONArray("ai_review_requests").getJSONObject(0).put("state", "posted") },
                { it.getJSONObject("tables").getJSONArray("ai_review_requests").getJSONObject(0).put("lastErrorCode", "financial content must not be an error code") },
                { it.getJSONObject("tables").getJSONArray("ai_review_drafts").getJSONObject(0).put("formJson", "{\"amount\":1.5}") },
                { it.getJSONObject("tables").getJSONArray("ledger_ai_review_cache").getJSONObject(0).put("ledgerId", 99) }
            )) {
                val invalid = JSONObject(document.toString()); mutate(invalid)
                rejected { backup.preview(PrivacyCrypto.encrypt(invalid.toString(), password), password) }
                rejected { backup.restore(invalid) }
            }
            assertEquals(newer, restored.draft(9, 0)); assertEquals(2, restored.requests().size)
        } finally { source.close(); target.close() }
    }

    @Test fun schemaEighteenRemainsReadableWithoutManufacturingScopedAIRequests() = runBlocking<Unit> {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val legacy = AIReviewItemEntity(101, 1, 1, "Synthetic legacy", createdUnixTime = 1)
            source.dao().upsertAIReviewItems(listOf(legacy))
            val encrypted = LedgerBackup(source, "synthetic").export(password)
            val old = JSONObject(PrivacyCrypto.decrypt(encrypted, password)).put("schema", 18)
            listOf("ledger_ai_review_cache", "ai_review_drafts", "ai_review_requests", "ai_review_images", "ai_review_image_chunks").forEach(old.getJSONObject("tables")::remove)
            val backup = LedgerBackup(target, "synthetic")
            backup.restore(backup.preview(PrivacyCrypto.encrypt(old.toString(), password), password))
            assertEquals(legacy, target.dao().observeAIReviewItems().first().single())
            assertTrue(target.dao().allAIReviewRequests().isEmpty()); assertNull(target.dao().findAIReviewDraft(0, 0))
            assertTrue(target.dao().observeLedgerAIReview(0, 1).first().isEmpty())
        } finally { source.close(); target.close() }
    }

    @Test fun concurrentExplicitConfirmationStoresOneImmutableRequest() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val store = TransactionRepository(context, db, false).aiReview
            val draft = store.saveDraft(9, 0, partial); val payload = create()
            val writes = coroutineScope { (1..8).map { async { store.enqueue(draft, "create", payload) } }.map { it.await() } }
            assertEquals(1, writes.map { it.requestId }.toSet().size); assertEquals(1, store.requests().size)
            assertEquals(payload, store.requests().single().payloadJson)
        } finally { db.close() }
    }
}
