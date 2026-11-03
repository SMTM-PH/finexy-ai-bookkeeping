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
class LedgerWritePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "Isolated-test-backup-passphrase"

    @Test fun confirmedSnapshotSurvivesDatabaseReopenAndRefreshFailure() = runBlocking {
        val name = "ledger-write-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            db.dao().upsertLedgers(listOf(LedgerEntity(9, 1, 1, name = "测试账本")))
            var repository = TransactionRepository(context, db, false)
            val entity = TransactionEntity("stable-request", type = 3, sourceAccountId = 10, categoryId = 20,
                categoryName = "餐饮", sourceAmountMinor = 1234, comment = "仅测试", time = 1700000000000)
            val queued = repository.enqueueLedgerWrite(9, entity)
            try { repository.enqueueLedgerWrite(9, entity.copy(localId = "another")); fail("Duplicate slot must fail") }
            catch (_: IllegalArgumentException) { }
            db.close()
            db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            repository = TransactionRepository(context, db, false)
            assertEquals(queued, repository.ledgerWrites().single())
            assertEquals("9", JSONObject(repository.ledgerWrites().single().payloadJson).getString("ledgerId"))
            repository.markLedgerWritePosted(queued, 123)
            db.close()
            db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            repository = TransactionRepository(context, db, false)
            assertEquals(LedgerWriteEntity.POSTED, repository.ledgerWrites().single().state)
            assertEquals(123L, repository.ledgerWrites().single().serverId)
            var uploads = 0
            LedgerWriteDelivery(repository) { uploads++; 123 }.push()
            assertEquals(0, uploads)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migrationFromTwentyOnePreservesExistingRowsAndAddsAnEmptyQueue() = runBlocking {
        val name = "ledger-write-migration-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val row = TransactionEntity("kept", type = 3, categoryName = "餐饮", sourceAmountMinor = 100, comment = "保留", time = 1)
            db.dao().upsertTransaction(row)
            db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE ledger_write_queue")
                old.removeAIImageSchemaForTest(); old.version = 21
            }
            db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).addMigrations(FinexyDatabase.MIGRATION_21_22, FinexyDatabase.MIGRATION_22_23, FinexyDatabase.MIGRATION_23_24, FinexyDatabase.MIGRATION_24_25, FinexyDatabase.MIGRATION_25_26).build()
            assertEquals(row, db.dao().findTransaction("kept"))
            assertTrue(db.dao().allLedgerWrites().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun backupSixteenPreservesWriteStatusAndFifteenRemainsCompatible() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val write = LedgerWriteEntity(9, "stable-request", "{\"ledgerId\":\"9\"}", LedgerWriteEntity.POSTED, 123, createdAt = 1)
            source.dao().insertLedgerWrite(write)
            val backup = LedgerBackup(source, "isolated")
            val encrypted = backup.export(password)
            val restore = LedgerBackup(target, "isolated")
            val doc = restore.preview(encrypted, password)
            assertEquals(20, doc.getInt("schema"))
            restore.restore(doc)
            assertEquals(write, target.dao().allLedgerWrites().single())
            val sixteen = JSONObject(PrivacyCrypto.decrypt(encrypted, password)).put("schema", 16)
            removePeriodicFieldsForLegacyBackup(sixteen)
            restore.restore(restore.preview(PrivacyCrypto.encrypt(sixteen.toString(), password), password))
            assertEquals(write, target.dao().allLedgerWrites().single())
            val older = JSONObject(PrivacyCrypto.decrypt(encrypted, password)).put("schema", 15)
            removePeriodicFieldsForLegacyBackup(older)
            older.getJSONObject("tables").remove("ledger_write_queue")
            restore.restore(restore.preview(PrivacyCrypto.encrypt(older.toString(), password), password))
            assertEquals(write, target.dao().allLedgerWrites().single())
        } finally { source.close(); target.close() }
    }

    private fun removePeriodicFieldsForLegacyBackup(document: JSONObject) {
        val tables = document.getJSONObject("tables")
        tables.remove("template_form_drafts"); tables.remove("template_creation_queue"); tables.remove("ledger_ai_review_cache"); tables.remove("ai_review_drafts"); tables.remove("ai_review_requests"); tables.remove("ai_review_images"); tables.remove("ai_review_image_chunks")
        listOf("transaction_templates", "scheduled_occurrences").forEach { table ->
            tables.optJSONArray(table)?.let { rows ->
                repeat(rows.length()) { index ->
                    rows.getJSONObject(index).apply {
                        remove("ledgerId")
                        remove("pendingAction")
                        remove("pendingActionQueuedAt")
                    }
                }
            }
        }
    }
}
