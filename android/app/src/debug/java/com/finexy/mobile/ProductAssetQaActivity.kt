package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.ProductAssetEntity
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TransactionRepository

/** Non-exported visual and interaction fixture for the product asset page. */
class ProductAssetQaActivity : PrivacyActivity() {
    var lightMode by mutableStateOf(true)
    private val database by lazy {
        Room.inMemoryDatabaseBuilder(applicationContext, FinexyDatabase::class.java).allowMainThreadQueries().build()
    }
    private val repository by lazy { TransactionRepository(applicationContext, database, importLegacy = false) }
    override fun privacyStore() = SecureStore(applicationContext, "product-asset-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kotlinx.coroutines.runBlocking {
            database.dao().upsertProductAssets(listOf(
                ProductAssetEntity(41, category = 3, status = 1, name = "工作电脑", brand = "Framework", model = "13",
                    purchaseAmountMinor = 899900, purchaseTime = 1_788_700_000_000, utcOffset = 480,
                    usefulLifeDays = 1825, residualAmountMinor = 10000, manualMarketValueMinor = 700000,
                    comment = "开发设备", heldDays = 30, accumulatedDepreciationMinor = 14628, bookValueMinor = 885272),
                ProductAssetEntity(42, category = 2, status = 2, name = "旧手机", purchaseAmountMinor = 499900,
                    purchaseTime = 1_700_000_000_000, utcOffset = 480, usefulLifeDays = 1460,
                    residualAmountMinor = 0, soldAmountMinor = 150000, soldTime = 1_780_000_000_000,
                    heldDays = 900, accumulatedDepreciationMinor = 308157, bookValueMinor = 191743)
            ))
        }
        setContent { FinexyTheme(lightMode) { ProductAssetsPage(privacyStore(), repository, localMode = false, onBack = {}) } }
    }
}
