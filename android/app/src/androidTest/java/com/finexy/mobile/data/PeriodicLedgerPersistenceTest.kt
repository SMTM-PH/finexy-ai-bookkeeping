package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PeriodicLedgerPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "isolated-periodic-backup-password"
    private fun template(ledger: Long = 0) = TemplateEntity(9007199254740993L, "fixture", 4, 12, 13, 10200, "full snapshot", "[\"14\"]",
        hidden = true, updatedAt = 10, templateType = 2, destinationAccountId = 15, destinationAmountMinor = 1390,
        hideAmount = true, scheduledFrequencyType = 0, scheduledFrequency = "", scheduledStartDate = "2026-10-01",
        scheduledEndDate = "2026-12-31", utcOffset = 480, scheduledAt = 0, nextScheduledTime = 1788700000,
        displayOrder = 3, pausedFromFrequencyType = 3, pausedFromFrequency = "0", ledgerId = ledger)
    private fun remote(ledger: Long, amount: Long = -102) = RemoteOccurrence(9007199254740993L, 1788700000, 1, 0, "fixture", 3, 12, 13, 0, amount, 0, 480, false, "[\"14\"]", "snapshot", ledger)

    @Test fun roomTwentyTwoMigrationPreservesEveryFieldAndExistingWriteQueue() = runBlocking<Unit> {
        val name = "periodic-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val originalTemplate = template()
            val originalOccurrence = ScheduledOccurrenceEntity(9007199254740993L, 1788700000, 3, name = "fixture", type = 4,
                categoryId = 12, sourceAccountId = 13, destinationAccountId = 15, sourceAmountMinor = 10200,
                destinationAmountMinor = 1390, utcOffset = 480, hideAmount = true, tagIdsJson = "[\"14\"]", comment = "original", updatedAt = 10)
            db.dao().upsertTemplates(listOf(originalTemplate)); db.dao().upsertOccurrences(listOf(originalOccurrence))
            val write = LedgerWriteEntity(9, "stable", "{\"ledgerId\":\"9\"}", createdAt = 10)
            db.dao().insertLedgerWrite(write)
            val sqlite = db.openHelper.writableDatabase
            // Recreate the actual pre-23 primary keys and columns, retaining all existing data.
            for ((table, keys) in listOf("transaction_templates" to listOf("id"), "scheduled_occurrences" to listOf("templateId", "scheduledUnixTime"))) {
                val columns = mutableListOf<Pair<String, String>>()
                sqlite.query("PRAGMA table_info($table)").use { cursor ->
                    while (cursor.moveToNext()) {
                        val column = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (column !in setOf("ledgerId", "pendingAction", "pendingActionQueuedAt")) {
                            val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                            val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                            columns += column to "`$column` $type${if (notNull) " NOT NULL" else ""}"
                        }
                    }
                }
                sqlite.execSQL("ALTER TABLE $table RENAME TO ${table}_fixture23")
                sqlite.execSQL("CREATE TABLE $table (${columns.joinToString { it.second }}, PRIMARY KEY(${keys.joinToString()}))")
                val names = columns.joinToString { "`${it.first}`" }
                sqlite.execSQL("INSERT INTO $table ($names) SELECT $names FROM ${table}_fixture23")
                sqlite.execSQL("DROP TABLE ${table}_fixture23")
            }
            sqlite.removeAIImageSchemaForTest(); sqlite.execSQL("DROP TABLE room_master_table"); sqlite.execSQL("PRAGMA user_version=22")
            db.close()
            db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).addMigrations(FinexyDatabase.MIGRATION_22_23, FinexyDatabase.MIGRATION_23_24, FinexyDatabase.MIGRATION_24_25, FinexyDatabase.MIGRATION_25_26).build()
            assertEquals(originalTemplate, db.dao().findTemplate(originalTemplate.id))
            assertEquals(originalOccurrence, db.dao().findOccurrence(0, originalOccurrence.templateId, originalOccurrence.scheduledUnixTime))
            assertEquals(write, db.dao().allLedgerWrites().single())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun sameIdsStayPartitionedAndQueuedSnapshotSurvivesPullAndReopen() = runBlocking<Unit> {
        val name = "periodic-partition-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            var repository = TransactionRepository(context, db, false)
            db.dao().upsertTemplates(listOf(template(9), template(10)))
            repository.replaceOccurrences(listOf(remote(9)), 9); repository.replaceOccurrences(listOf(remote(10, 500)), 10)
            val intent = repository.enqueuePeriodicAction(9, 9007199254740993L, 1788700000, ScheduledOccurrenceEntity.ACTION_CONFIRM)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.enqueuePeriodicAction(9, intent.templateId, intent.scheduledUnixTime, ScheduledOccurrenceEntity.ACTION_DISMISS) } }
            repository.replaceOccurrences(listOf(remote(9, 999)), 9)
            repository.replaceOccurrences(emptyList(), 9)
            assertEquals(-102L, db.dao().occurrencesInLedger(9).single().sourceAmountMinor)
            assertEquals(500L, db.dao().occurrencesInLedger(10).single().sourceAmountMinor)
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            repository = TransactionRepository(context, db, false)
            assertEquals(listOf(intent), repository.pendingPeriodicActions())
            assertFalse(repository.acknowledgePeriodicAction(intent.copy(queuedAt = intent.queuedAt + 1), 42))
            assertTrue(repository.acknowledgePeriodicAction(intent, 42))
            assertEquals(42L, db.dao().occurrencesInLedger(9).single().transactionId)
            assertEquals(1, db.dao().occurrencesInLedger(10).single().status)
            repository.removeTemplate(9007199254740993L, 9)
            assertNotNull(db.dao().findTemplate(9007199254740993L, 10))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun schemaNineteenRoundTripRetainsPartitionsAndPendingIntentWithoutOverwritingNewerRows() = runBlocking<Unit> {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            source.dao().upsertTemplates(listOf(template(9), template(10)))
            val repo = TransactionRepository(context, source, false)
            repo.replaceOccurrences(listOf(remote(9)), 9)
            val intent = repo.enqueuePeriodicAction(9, 9007199254740993L, 1788700000, 1)
            val encrypted = LedgerBackup(source, "isolated").export(password)
            val backup = LedgerBackup(target, "isolated")
            val document = backup.preview(encrypted, password)
            assertEquals(20, document.getInt("schema"))
            assertEquals(0, backup.restore(document))
            assertEquals(2, target.dao().allTemplates().size)
            assertEquals(listOf(intent), TransactionRepository(context, target, false).pendingPeriodicActions())
            target.dao().upsertTemplates(listOf(template(9).copy(name = "newer")))
            assertEquals(0, backup.restore(document)); assertEquals("newer", target.dao().findTemplate(9007199254740993L, 9)?.name)
            val invalid = JSONObject(document.toString())
            invalid.getJSONObject("tables").getJSONArray("scheduled_occurrences").getJSONObject(0).put("pendingActionQueuedAt", 0)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { backup.preview(PrivacyCrypto.encrypt(invalid.toString(), password), password) } }
        } finally { source.close(); target.close() }
    }

    @Test fun schemaThirteenStandaloneBackupRestoresPeriodicRowsOnlyIntoDefaultLedger() = runBlocking<Unit> {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            source.dao().upsertTemplates(listOf(template()))
            TransactionRepository(context, source, false).replaceOccurrences(listOf(remote(0)))
            val document = LedgerBackup(source, "isolated").preview(LedgerBackup(source, "isolated").export(password), password)
            document.put("schema", 13)
            val tables = document.getJSONObject("tables")
            listOf("ledgers", "savings_goals", "ledger_write_queue", "template_form_drafts", "template_creation_queue", "ledger_ai_review_cache", "ai_review_drafts", "ai_review_requests", "ai_review_images", "ai_review_image_chunks").forEach(tables::remove)
            for (table in listOf("transaction_templates", "scheduled_occurrences")) {
                val rows = tables.getJSONArray(table)
                repeat(rows.length()) { i -> rows.getJSONObject(i).apply { remove("ledgerId"); remove("pendingAction"); remove("pendingActionQueuedAt") } }
            }
            val backup = LedgerBackup(target, "isolated")
            val preview = backup.preview(PrivacyCrypto.encrypt(document.toString(), password), password)
            assertEquals(0, backup.restore(preview)); assertEquals(1, target.dao().allTemplates().size); assertEquals(1, target.dao().allOccurrences().size); assertEquals(0L, target.dao().allTemplates().single().ledgerId)
            assertEquals(0L, target.dao().allOccurrences().single().ledgerId)
            assertTrue(TransactionRepository(context, target, false).pendingPeriodicActions().isEmpty())
            tables.getJSONArray("transaction_templates").getJSONObject(0).put("ledgerId", 9)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { backup.preview(PrivacyCrypto.encrypt(document.toString(), password), password) } }
        } finally { source.close(); target.close() }
    }
}
