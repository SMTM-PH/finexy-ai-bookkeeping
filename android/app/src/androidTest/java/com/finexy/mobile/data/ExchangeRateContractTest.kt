package com.finexy.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExchangeRateContractTest {
    private val valid = """{"success":true,"result":{"dataSource":"user_custom","referenceUrl":"","updateTime":1788700000,"baseCurrency":"CNY","exchangeRates":[{"currency":"CNY","rate":"1"},{"currency":"USD","rate":"0.13876543"},{"currency":"JPY","rate":"20.12345678"}]}}"""

    @Test fun parserPreservesDecimalPrecisionDirectionAndRejectsPartialSnapshots() {
        val snapshot = parseExchangeRateSnapshot(valid, fetchedAt = 1_788_700_100_000)
        assertEquals("CNY", snapshot.baseCurrency)
        assertEquals("0.13876543", snapshot.exchangeRates.first { it.currency == "USD" }.rate)
        val rows = snapshot.toEntities()
        assertEquals(72_064L, convertMinorAmount(10_000, "USD", "CNY", rows, 1_788_700_100_000))
        assertEquals(13_877L, convertMinorAmount(100_000, "CNY", "USD", rows, 1_788_700_100_000))

        listOf(
            "{}", "{\"success\":false}",
            valid.replace("{\"currency\":\"CNY\",\"rate\":\"1\"},", ""),
            valid.replace("0.13876543", "0"),
            valid.replace("{\"currency\":\"JPY\",\"rate\":\"20.12345678\"}", "{\"currency\":\"USD\",\"rate\":\"2\"}")
        ).forEach { assertThrows(RuntimeException::class.java) { parseExchangeRateSnapshot(it) } }
    }

    @Test fun externalQuotesExpireButUserCustomQuotesRemainExplicitlyUsable() {
        val external = parseExchangeRateSnapshot(valid.replace("user_custom", "European Central Bank"), fetchedAt = 1_788_700_100_000).toEntities()
        val justValid = (1_788_700_000L + ExchangeRateEntityData.EXTERNAL_MAX_AGE_SECONDS) * 1000
        assertNotNull(convertMinorAmount(100, "CNY", "USD", external, justValid))
        assertNull(convertMinorAmount(100, "CNY", "USD", external, justValid + 1000))
        val custom = parseExchangeRateSnapshot(valid, fetchedAt = 1_788_700_100_000).toEntities()
        assertNotNull(convertMinorAmount(100, "CNY", "USD", custom, justValid + 365L * 24 * 60 * 60 * 1000))
        assertNull(convertMinorAmount(100, "EUR", "USD", custom))
    }

    @Test fun roomFlowAtomicallyReconcilesSnapshotAndMigrationCreatesTable() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val repository = TransactionRepository(context, database, importLegacy = false)
        try {
            repository.replaceExchangeRates(parseExchangeRateSnapshot(valid))
            assertEquals(3, repository.observeExchangeRates().first().size)
            repository.replaceExchangeRates(parseExchangeRateSnapshot(valid.replace(",{\"currency\":\"JPY\",\"rate\":\"20.12345678\"}", "")))
            assertEquals(listOf("CNY", "USD"), repository.observeExchangeRates().first().map { it.currency })
        } finally { database.close() }

        val name = "rate-migration-${UUID.randomUUID()}.db"
        try {
            val seed = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            seed.openHelper.writableDatabase; seed.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE exchange_rates"); old.version = 16
            }
            val migrated = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_16_17, FinexyDatabase.MIGRATION_17_18, FinexyDatabase.MIGRATION_18_19).build()
            try { assertTrue(migrated.dao().allExchangeRates().isEmpty()) } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
