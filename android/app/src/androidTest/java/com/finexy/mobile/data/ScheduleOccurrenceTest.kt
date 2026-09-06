package com.finexy.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleOccurrenceTest {
    private fun occurrenceJson(templateId: Long = 42, scheduledUnixTime: Long = 1_788_700_000, status: Int = 1,
        transactionId: Long = 0, type: Int = 3, categoryId: Long = 201, sourceAccountId: Long = 10,
        destinationAccountId: Long = 0, destinationAmount: Long = 0, hideAmount: Boolean = false) = JSONObject()
        .put("templateId", templateId.toString()).put("scheduledUnixTime", scheduledUnixTime).put("status", status)
        .put("transactionId", transactionId.toString())
        .put("snapshot", JSONObject().put("name", "房租").put("type", type).put("categoryId", categoryId.toString())
            .put("sourceAccountId", sourceAccountId.toString()).put("destinationAccountId", destinationAccountId.toString())
            .put("sourceAmount", 420000).put("destinationAmount", destinationAmount)
            .put("utcOffset", 480).put("hideAmount", hideAmount).put("tagIds", JSONArray().put("7")).put("comment", "月租"))

    private fun listPayload(vararg items: JSONObject): String =
        JSONObject().put("success", true).put("result", JSONArray().apply { items.forEach { put(it) } }).toString()

    private fun api(): FinexyApi = FinexyApi(SecureStore(InstrumentationRegistry.getInstrumentation().targetContext, "occurrence-contract-fixture"))

    @Test fun occurrenceListContractRejectsMalformedPages() {
        val client = api()
        listOf(
            "{}",
            "{\"success\":false,\"result\":[]}",
            "{\"success\":true,\"result\":null}",
            "{\"success\":true,\"result\":[null]}",
            "{\"success\":true,\"result\":[{}]}",
            "{\"success\":true,\"result\":[{\"templateId\":\"0\",\"scheduledUnixTime\":10,\"status\":1}]}",
            "{\"success\":true,\"result\":[{\"templateId\":\"1\",\"scheduledUnixTime\":0,\"status\":1}]}",
            "{\"success\":true,\"result\":[{\"templateId\":\"1\",\"scheduledUnixTime\":10,\"status\":9}]}",
            "{\"success\":true,\"result\":[{\"templateId\":\"1\",\"scheduledUnixTime\":10,\"status\":1,\"snapshot\":{}}]}",
            listPayload(occurrenceJson(type = 1)),
            listPayload(occurrenceJson(categoryId = 0)),
            listPayload(occurrenceJson(sourceAccountId = 0))
        ).forEach { raw -> assertThrows(RuntimeException::class.java) { client.parseOccurrenceList(raw) } }
        // A real empty array is the only valid empty queue.
        assertEquals(emptyList<RemoteOccurrence>(), client.parseOccurrenceList("{\"success\":true,\"result\":[]}"))
    }

    @Test fun occurrenceListParsesSnapshotWithoutLosingPrecision() {
        val parsed = api().parseOccurrenceList(listPayload(
            occurrenceJson(templateId = 9223372036854775807L % 1000000, scheduledUnixTime = 1_788_700_000),
            occurrenceJson(templateId = 77, type = 4, destinationAccountId = 11, destinationAmount = 90000, hideAmount = true)
        ))
        assertEquals(2, parsed.size)
        val expense = parsed.first { it.templateId == 9223372036854775807L % 1000000 }
        assertEquals(1_788_700_000, expense.scheduledUnixTime)
        assertEquals(1, expense.status)
        assertEquals(420000L, expense.sourceAmountMinor)
        assertEquals(201L, expense.categoryId)
        assertEquals(10L, expense.sourceAccountId)
        assertEquals(480, expense.utcOffset)
        assertEquals(listOf("7"), JSONArray(expense.tagIdsJson).let { array -> (0 until array.length()).map(array::getString) })
        val transfer = parsed.first { it.templateId == 77L }
        assertEquals(4, transfer.type)
        assertEquals(11L, transfer.destinationAccountId)
        assertEquals(90000L, transfer.destinationAmountMinor)
        assertTrue(transfer.hideAmount)
    }

    @Test fun confirmResponseRequiresPositiveTransactionId() {
        val client = api()
        assertEquals(9223372036854775807L % 1000000, client.parseConfirmedTransactionId(
            JSONObject().put("success", true).put("result", JSONObject().put("transactionId", (9223372036854775807L % 1000000).toString())).toString()))
        listOf("{\"success\":true,\"result\":{}}", "{\"success\":true,\"result\":{\"transactionId\":\"0\"}}",
            "{\"success\":true,\"result\":{\"transactionId\":null}}", "{\"success\":true}")
            .forEach { raw -> assertThrows(RuntimeException::class.java) { client.parseConfirmedTransactionId(raw) } }
    }

    @Test fun occurrenceActionPayloadUsesServerContract() {
        val body = api().occurrencePayload(42, 1_788_700_000)
        assertEquals("42", body.getString("templateId"))
        assertEquals(1_788_700_000, body.getLong("scheduledUnixTime"))
        assertThrows(IllegalArgumentException::class.java) { api().occurrencePayload(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { api().occurrencePayload(1, 0) }
    }

    @Test fun replaceOccurrencesReconcilesAndKeepsDismissedState() {
        kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val repository = TransactionRepository(context, database, importLegacy = false)
        try {
            repository.replaceOccurrences(listOf(
                RemoteOccurrence(1, 100, 1, 0, "a", 3, 201, 10, 0, 1000, 0, 480, false, "[]", "first"),
                RemoteOccurrence(2, 200, 3, 0, "b", 3, 201, 10, 0, 2000, 0, 480, false, "[]", "dismissed")
            ))
            // Second pull drops the first occurrence (confirmed elsewhere) and
            // adds a new one; the dismissed row keeps its state.
            repository.replaceOccurrences(listOf(
                RemoteOccurrence(2, 200, 3, 0, "b", 3, 201, 10, 0, 2000, 0, 480, false, "[]", "dismissed"),
                RemoteOccurrence(3, 300, 1, 0, "c", 2, 202, 10, 0, 3000, 0, 480, false, "[]", "")
            ))
            val remaining = repository.allOccurrences()
            assertEquals(setOf(2L to 200L, 3L to 300L), remaining.map { it.templateId to it.scheduledUnixTime }.toSet())
            assertEquals(ScheduledOccurrenceEntity.STATUS_DISMISSED, remaining.first { it.templateId == 2L }.status)
            // A real empty pull clears the queue entirely.
            repository.replaceOccurrences(emptyList())
            assertEquals(emptyList<ScheduledOccurrenceEntity>(), repository.allOccurrences())
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    repository.replaceOccurrences(listOf(RemoteOccurrence(1, 100, 2, 99, "a", 3, 201, 10, 0, 1000, 0, 480, false, "[]", "")))
                }
            }
        } finally { database.close() }
        }
    }

    @Test fun applyOccurrenceActionTransitionsStateAndStoresTransactionId() {
        kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val repository = TransactionRepository(context, database, importLegacy = false)
        try {
            repository.replaceOccurrences(listOf(RemoteOccurrence(5, 500, 1, 0, "rent", 3, 201, 10, 0, 420000, 0, 480, false, "[]", "")))
            repository.applyOccurrenceAction(5, 500, ScheduledOccurrenceEntity.STATUS_DISMISSED)
            assertEquals(ScheduledOccurrenceEntity.STATUS_DISMISSED, repository.allOccurrences().single().status)
            repository.applyOccurrenceAction(5, 500, ScheduledOccurrenceEntity.STATUS_PENDING)
            assertEquals(ScheduledOccurrenceEntity.STATUS_PENDING, repository.allOccurrences().single().status)
            repository.applyOccurrenceAction(5, 500, ScheduledOccurrenceEntity.STATUS_CONFIRMED, transactionId = 987654321)
            val confirmed = repository.allOccurrences().single()
            assertEquals(ScheduledOccurrenceEntity.STATUS_CONFIRMED, confirmed.status)
            assertEquals(987654321L, confirmed.transactionId)
        } finally { database.close() }
        }
    }

    @Test fun pausedSchedulePreservesFrequencyForResume() {
        val active = TemplateEntity(9, "房租", 3, 201, 10, 420000, "月租", templateType = 2, scheduledFrequencyType = 2, scheduledFrequency = "1,15", utcOffset = 480)
        val paused = active.pausedSchedule()
        assertEquals(0, paused.scheduledFrequencyType)
        assertEquals("", paused.scheduledFrequency)
        assertEquals(2, paused.pausedFromFrequencyType)
        assertEquals("1,15", paused.pausedFromFrequency)
        val resumed = paused.resumedSchedule()
        assertEquals(2, resumed.scheduledFrequencyType)
        assertEquals("1,15", resumed.scheduledFrequency)
        assertEquals(null, resumed.pausedFromFrequencyType)
        assertThrows(IllegalArgumentException::class.java) { active.resumedSchedule() }
        val missingBackup = active.copy(scheduledFrequencyType = 0, scheduledFrequency = "", pausedFromFrequencyType = null)
        assertThrows(IllegalArgumentException::class.java) { missingBackup.resumedSchedule() }
    }

    @Test fun migration13To14AddsOccurrencesAndPauseBackupColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "occurrence-migration-test"
        var migrated: FinexyDatabase? = null
        try {
            context.deleteDatabase(name)
            // Build at the current version, then downgrade the file to v13 so
            // reopening with only MIGRATION_13_14 must recreate what v14 needs.
            val seed = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            kotlinx.coroutines.runBlocking {
                seed.dao().upsertTemplates(listOf(TemplateEntity(7, "保留计划", 3, 201, 10, 420000, "月租", scheduledFrequencyType = 2, scheduledFrequency = "1")))
            }
            seed.close()
            val downgrade = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
            downgrade.execSQL("DROP TABLE scheduled_occurrences")
            downgrade.execSQL("ALTER TABLE transaction_templates DROP COLUMN pausedFromFrequencyType")
            downgrade.execSQL("ALTER TABLE transaction_templates DROP COLUMN pausedFromFrequency")
            downgrade.version = 13
            downgrade.close()
            migrated = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_13_14).build()
            lateinit var template: TemplateEntity
            lateinit var occurrence: ScheduledOccurrenceEntity
            kotlinx.coroutines.runBlocking {
                template = migrated.dao().allTemplates().single()
                migrated.dao().upsertOccurrences(listOf(ScheduledOccurrenceEntity(42, 1_788_700_000, ScheduledOccurrenceEntity.STATUS_PENDING)))
                occurrence = migrated.dao().allOccurrences().single()
            }
            assertEquals(7L, template.id)
            assertEquals(2, template.scheduledFrequencyType)
            assertEquals(42L, occurrence.templateId)
        } finally {
            migrated?.close()
            context.deleteDatabase(name)
        }
    }
}
