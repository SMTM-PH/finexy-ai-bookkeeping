package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TemplateCreationPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "isolated-template-backup-passphrase"
    private val partial = "{\"name\":\"\",\"amount\":\"1.\",\"comment\":\"未确认原文\",\"accountId\":\"\"}"
    private fun payload(ledger: Long, kind: Int = 1) = JSONObject().put("ledgerId", ledger.toString()).put("templateType", kind)
        .put("name", "isolated").put("type", 3).put("categoryId", "20").put("sourceAccountId", "10")
        .put("destinationAccountId", "0").put("sourceAmount", -1234).put("destinationAmount", 0)
        .put("hideAmount", true).put("tagIds", org.json.JSONArray()).put("comment", "snapshot").apply {
            if (kind == 2) { put("scheduledFrequencyType", 1); put("scheduledFrequency", "1"); put("scheduledStartDate", "2026-10-05"); put("scheduledEndDate", JSONObject.NULL); put("utcOffset", 480) }
        }.toString()
    private suspend fun enqueue(repo: TransactionRepository, ledger: Long = 9, kind: Int = 1): Pair<TemplateFormDraftEntity, TemplateCreationEntity> {
        val draft = repo.saveTemplateDraft(ledger, kind, 0, partial)
        return draft to repo.enqueueTemplateCreation(draft, payload(ledger, kind))
    }

    @Test fun migrationTwentyThreeRetainsTransactionsTemplatesAndPreviousConfirmedQueue() = runBlocking<Unit> {
        val name = "template-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val transaction = TransactionEntity("kept", type = 3, categoryName = "fixture", sourceAmountMinor = 1234, comment = "kept", time = 1)
            val template = TemplateEntity(9007199254740993L, "kept", 3, 20, 10, -1234, "kept", ledgerId = 9)
            val previous = LedgerWriteEntity(9, "previous", "{\"ledgerId\":\"9\"}", createdAt = 1)
            db.dao().upsertTransaction(transaction); db.dao().upsertTemplates(listOf(template)); db.dao().insertLedgerWrite(previous)
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("DROP TABLE template_form_drafts"); sqlite.execSQL("DROP TABLE template_creation_queue")
            sqlite.removeAIImageSchemaForTest(); sqlite.execSQL("DROP TABLE room_master_table"); sqlite.execSQL("PRAGMA user_version=23")
            db.close()
            db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).addMigrations(FinexyDatabase.MIGRATION_23_24, FinexyDatabase.MIGRATION_24_25, FinexyDatabase.MIGRATION_25_26).build()
            assertEquals(transaction, db.dao().findTransaction("kept")); assertEquals(template, db.dao().findTemplate(template.id, 9))
            assertEquals(previous, db.dao().allLedgerWrites().single()); assertTrue(db.dao().allTemplateCreations().isEmpty())
            assertNull(db.dao().findTemplateDraft(9, 1))
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(26, it.getInt(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun incompleteDraftsStayLocalPartitionedAndSurviveDatabaseReopen() = runBlocking<Unit> {
        val name = "template-drafts-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val repo = TransactionRepository(context, db, false)
            val originals = listOf(0L to 1, 9L to 1, 9L to 2, 10L to 1).map { (ledger, kind) -> repo.saveTemplateDraft(ledger, kind, 0, partial) }
            assertTrue(repo.templateCreations().isEmpty())
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            val restored = TransactionRepository(context, db, false)
            for (draft in originals) assertEquals(draft, restored.templateDraft(draft.ledgerId, draft.templateType))
            var posts = 0
            assertEquals(0, TemplateCreationDelivery(restored) { posts++; TemplateCreationReceipt(it.ledgerId, it.templateType, 42) }.push().posted)
            assertEquals(0, posts)
            assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun confirmationIsImmutableAndOldReceiptCannotDeleteNewerInput() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val repo = TransactionRepository(context, db, false)
            val (draft, write) = enqueue(repo)
            assertEquals(write, repo.enqueueTemplateCreation(draft, payload(9)))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.enqueueTemplateCreation(draft, payload(9).replace("snapshot", "changed")) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.discardTemplateDraft(draft) } }
            val newer = repo.saveTemplateDraft(9, 1, 0, "{\"name\":\"newer\",\"amount\":\"9.\"}", draft)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveTemplateDraft(9, 1, 0, partial, draft) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.enqueueTemplateCreation(newer, payload(9)) } }
            assertFalse(repo.markTemplateCreationPosted(write.copy(payloadJson = payload(9).replace("snapshot", "changed")), TemplateCreationReceipt(9, 1, 42)))
            assertTrue(repo.markTemplateCreationPosted(write, TemplateCreationReceipt(9, 1, 42)))
            repo.replacePeriodicSnapshot(9, emptyList(), null, null)
            assertTrue(repo.templateCreations().isEmpty()); assertEquals(newer, repo.templateDraft(9, 1))
            assertTrue(repo.discardTemplateDraft(newer))
        } finally { db.close() }
    }

    @Test fun receiptSurvivesReopenAndFailedRefreshNeverReposts() = runBlocking<Unit> {
        val name = "template-receipt-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            var repo = TransactionRepository(context, db, false)
            val (draft, write) = enqueue(repo, 0)
            assertTrue(repo.markTemplateCreationPosted(write, TemplateCreationReceipt(0, 1, 9007199254740993L, deleted = true)))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.replacePeriodicSnapshot(0, emptyList(), emptyList(), null) } }
            assertEquals(draft, repo.templateDraft(0, 1))
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build(); repo = TransactionRepository(context, db, false)
            assertEquals(9007199254740993L, repo.templateCreations().single().serverId); assertTrue(repo.templateCreations().single().serverDeleted)
            var posts = 0
            TemplateCreationDelivery(repo) { posts++; TemplateCreationReceipt(0, 1, 42) }.push()
            assertEquals(0, posts)
            repo.replacePeriodicSnapshot(0, emptyList(), null, null)
            assertTrue(repo.templateCreations().isEmpty()); assertNull(repo.templateDraft(0, 1))
            assertTrue(db.dao().allTemplates().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun refreshDoesNotClearPendingOrUnavailableScheduleAndOtherLedger() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val repo = TransactionRepository(context, db, false)
            val pending = enqueue(repo).second
            val schedule = enqueue(repo, 9, 2).second
            val other = enqueue(repo, 10).second
            repo.markTemplateCreationPosted(schedule, TemplateCreationReceipt(9, 2, 42))
            repo.markTemplateCreationPosted(other, TemplateCreationReceipt(10, 1, 43))
            repo.replacePeriodicSnapshot(9, emptyList(), null, null)
            assertEquals(3, repo.templateCreations().size)
            repo.replacePeriodicSnapshot(9, emptyList(), emptyList(), emptyList())
            assertEquals(setOf(pending.clientSessionId, other.clientSessionId), repo.templateCreations().map { it.clientSessionId }.toSet())
        } finally { db.close() }
    }

    @Test fun currentSchemaRoundTripPreservesRawDraftsReceiptsAndNewerDeviceRows() = runBlocking<Unit> {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val repo = TransactionRepository(context, source, false)
            val (draft, write) = enqueue(repo)
            val schedule = enqueue(repo, 9, 2).second
            repo.markTemplateCreationPosted(schedule, TemplateCreationReceipt(9, 2, 42, true))
            val backup = LedgerBackup(target, "isolated")
            val document = backup.preview(LedgerBackup(source, "isolated").export(password), password)
            assertEquals(20, document.getInt("schema")); backup.restore(document)
            val restored = TransactionRepository(context, target, false)
            assertEquals(draft, restored.templateDraft(9, 1)); assertEquals(repo.templateCreations(), restored.templateCreations())
            val newer = restored.saveTemplateDraft(9, 1, 0, "{\"name\":\"newer\"}", draft)
            restored.markTemplateCreationPosted(write, TemplateCreationReceipt(9, 1, 50))
            backup.restore(document)
            assertEquals(newer, restored.templateDraft(9, 1))
            assertEquals(TemplateCreationEntity.POSTED, restored.templateCreations().first { it.templateType == 1 }.state)
            for (mutate in listOf<(JSONObject) -> Unit>(
                { it.put("scope", "other") },
                { it.getJSONObject("tables").getJSONArray("template_creation_queue").getJSONObject(0).put("payloadJson", payload(10)) },
                { it.getJSONObject("tables").getJSONArray("template_creation_queue").getJSONObject(0).put("state", "posted") },
                { it.getJSONObject("tables").getJSONArray("template_form_drafts").getJSONObject(0).put("formJson", "{\"amount\":1.5}") }
            )) {
                val invalid = JSONObject(document.toString()); mutate(invalid)
                assertThrows(IllegalArgumentException::class.java) { runBlocking { backup.preview(PrivacyCrypto.encrypt(invalid.toString(), password), password) } }
            }
        } finally { source.close(); target.close() }
    }

    @Test fun schemaSeventeenBackupRemainsCompatibleWithoutManufacturingRequests() = runBlocking<Unit> {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val original = TemplateEntity(42, "legacy", 3, 20, 10, 1234, "legacy", ledgerId = 9)
            source.dao().upsertTemplates(listOf(original))
            val encrypted = LedgerBackup(source, "isolated").export(password)
            val document = JSONObject(PrivacyCrypto.decrypt(encrypted, password)).put("schema", 17)
            document.getJSONObject("tables").apply { remove("template_form_drafts"); remove("template_creation_queue"); remove("ledger_ai_review_cache"); remove("ai_review_drafts"); remove("ai_review_requests"); remove("ai_review_images"); remove("ai_review_image_chunks") }
            val backup = LedgerBackup(target, "isolated")
            backup.restore(backup.preview(PrivacyCrypto.encrypt(document.toString(), password), password))
            assertEquals(original, target.dao().findTemplate(42, 9)); assertTrue(target.dao().allTemplateCreations().isEmpty())
            assertNull(target.dao().findTemplateDraft(9, 1))
        } finally { source.close(); target.close() }
    }
}
