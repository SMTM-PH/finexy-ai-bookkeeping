package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DockerProductAssetE2ETest {
    @Test fun createSyncModifySellAndDeleteLifecycle() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Use only an isolated disposable Docker server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "asset-e2e-${UUID.randomUUID()}"
        val store = SecureStore(context, namespace)
        val username = "asset" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = UUID.randomUUID().toString()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            store.put(FinexyApi.KEY_SERVER_URL, url!!, durable = true)
            AccountSecurity(FinexyApi(store)).register(username, "$username@example.com", "Asset Test", password, "CNY")
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            val api = FinexyApi(store)
            val repository = TransactionRepository(context, database, importLegacy = false)
            assertTrue(api.listProductAssets().isEmpty())

            val purchaseTime = LocalDate.now().minusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val created = api.createProductAsset(ProductAssetDraft(3, "工作电脑", "Framework", "13", 899900,
                purchaseTime, 480, 1825, 10000, 700000, "开发设备"))
            assertTrue(created.id > 0)
            assertEquals(700000L, created.manualMarketValueMinor)

            SyncEngine(api, repository).sync()
            val cached = database.dao().allProductAssets().single()
            assertEquals(created.id, cached.id)
            assertEquals("工作电脑", cached.name)

            val modifiedDraft = ProductAssetDraft(3, "工作电脑 2026", "Framework", "13", 899900,
                purchaseTime, 480, 1825, 10000, null, "清除手动估值")
            val modified = api.modifyProductAsset(cached, modifiedDraft)
            assertEquals("工作电脑 2026", modified.name)
            assertNull(modified.manualMarketValueMinor)

            val soldTime = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val sold = api.sellProductAsset(created.id, 500000, soldTime)
            assertEquals(ProductAssetEntity.STATUS_SOLD, sold.status)
            assertEquals(500000L, sold.soldAmountMinor)
            assertTrue(sold.heldDays >= 30)

            api.deleteProductAsset(created.id)
            assertTrue(api.listProductAssets().isEmpty())
            repository.replaceProductAssets(api.listProductAssets())
            assertTrue(database.dao().allProductAssets().isEmpty())
        } finally {
            database.close()
            context.deleteSharedPreferences(namespace)
        }
    }
}
