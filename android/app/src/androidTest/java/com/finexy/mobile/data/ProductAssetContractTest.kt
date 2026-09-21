package com.finexy.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProductAssetContractTest {
    private fun api() = FinexyApi(SecureStore(InstrumentationRegistry.getInstrumentation().targetContext, "asset-contract-fixture"))

    private fun item(id: String = "9007199254740993", name: String = "工作电脑") = JSONObject()
        .put("id", id).put("sourceTransactionId", "0").put("saleTransactionId", "0")
        .put("category", 3).put("status", 1).put("name", name).put("brand", "Framework").put("model", "13")
        .put("purchaseAmount", 899900).put("purchaseTime", 1_788_700_000).put("utcOffset", 480)
        .put("usefulLifeDays", 1825).put("residualAmount", 10000).put("manualMarketValue", 700000)
        .put("comment", "开发设备").put("valuation", JSONObject().put("heldDays", 30)
            .put("dailyDepreciation", 487.6).put("accumulatedDepreciation", 14628)
            .put("bookValue", 885272).put("marketValue", 700000).put("averageDailyCost", 29996.6))

    @Test fun parserPreservesStringIdsAndRejectsUnsafeFullRefreshes() {
        val parsed = api().parseProductAssets(JSONObject().put("success", true).put("result", JSONArray().put(item())).toString()).single()
        assertEquals(9007199254740993L, parsed.id)
        assertEquals(1_788_700_000_000L, parsed.purchaseTime)
        assertEquals(885272L, parsed.bookValueMinor)
        assertEquals(700000L, parsed.manualMarketValueMinor)

        listOf(
            "{}", "{\"success\":false,\"result\":[]}", "{\"success\":true,\"result\":null}",
            JSONObject().put("success", true).put("result", JSONArray().put(JSONObject())).toString(),
            JSONObject().put("success", true).put("result", JSONArray().put(item("1")).put(item("1"))).toString()
        ).forEach { raw -> assertThrows(RuntimeException::class.java) { api().parseProductAssets(raw) } }
        assertTrue(api().parseProductAssets("{\"success\":true,\"result\":[]}").isEmpty())
    }

    @Test fun payloadUsesMinorUnitsSecondsAndExplicitClearFlag() {
        val draft = ProductAssetDraft(2, " 手机 ", "品牌", "型号", 123456, 1_788_700_000_999, 480,
            usefulLifeDays = 1460, residualAmountMinor = 5000, manualMarketValueMinor = null, comment = " 自用 ")
        val create = draft.toCreatePayload()
        assertEquals(123456L, create.getLong("purchaseAmount"))
        assertEquals(1_788_700_000L, create.getLong("purchaseTime"))
        assertEquals("手机", create.getString("name"))
        assertFalse(create.has("manualMarketValue"))
        val modify = draft.toModifyPayload(9007199254740993L, clearManualMarketValue = true)
        assertEquals("9007199254740993", modify.getString("id"))
        assertTrue(modify.getBoolean("clearManualMarketValue"))
        assertEquals(1460, defaultUsefulLifeDays(2))
        assertThrows(IllegalArgumentException::class.java) { draft.copy(residualAmountMinor = 200000).validate() }
    }

    @Test fun roomFlowReconcilesCompleteServerListAndMigrationCreatesTable() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val repository = TransactionRepository(context, database, importLegacy = false)
        try {
            val first = RemoteProductAsset.from(item("41", "电脑"))
            val second = RemoteProductAsset.from(item("42", "手机"))
            repository.replaceProductAssets(listOf(first, second))
            assertEquals(listOf(41L, 42L), repository.observeProductAssets().first().map { it.id }.sorted())
            repository.replaceProductAssets(listOf(second.copy(name = "新手机")))
            assertEquals(listOf("新手机"), repository.observeProductAssets().first().map { it.name })
            repository.replaceProductAssets(emptyList())
            assertTrue(repository.observeProductAssets().first().isEmpty())
        } finally { database.close() }

        val name = "asset-migration-${UUID.randomUUID()}.db"
        try {
            val seed = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            seed.openHelper.writableDatabase
            seed.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("DROP TABLE product_assets")
                old.version = 15
            }
            val migrated = Room.databaseBuilder(context, FinexyDatabase::class.java, name)
                .addMigrations(FinexyDatabase.MIGRATION_15_16, FinexyDatabase.MIGRATION_16_17,
                FinexyDatabase.MIGRATION_17_18, FinexyDatabase.MIGRATION_18_19).build()
            try { assertTrue(migrated.dao().allProductAssets().isEmpty()) } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
