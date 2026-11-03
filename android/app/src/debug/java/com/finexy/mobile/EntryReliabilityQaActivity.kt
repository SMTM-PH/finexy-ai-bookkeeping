package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.CategoryEntity
import com.finexy.mobile.data.CurrencyAmounts
import com.finexy.mobile.data.SecureStore

/** Isolated form fixture; it never connects to a server or opens the user's Room database. */
class EntryReliabilityQaActivity : PrivacyActivity() {
    var categories by mutableStateOf(emptyList<CategoryEntity>())
    var loading by mutableStateOf(false)
    var categoryError by mutableStateOf<String?>(null)
    var showDashboard by mutableStateOf(false)
    var saves = 0
    override fun privacyStore() = SecureStore(applicationContext, "entry-reliability-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinexyTheme(true) {
                Scaffold(containerColor = CanvasBlack) { padding ->
                    if (showDashboard) Dashboard(padding,
                        currencyAmounts = listOf(CurrencyAmounts("CNY", 1200, 0, 100), CurrencyAmounts("USD", null, null, 200)),
                        activities = emptyList(), onAll = {}, onWallet = {}, onSettings = {}, onEntry = {})
                    else EntryScreen(padding, false, accounts = listOf(AccountEntity(10, "测试账户", "CNY")),
                        serverCategories = categories, defaultAccountId = 10, onlineOnly = true, nonDefaultLedger = true,
                        categoriesLoading = loading, categoriesError = categoryError,
                        onRetryCategories = {
                            loading = false; categoryError = null
                            categories = listOf(CategoryEntity(20, "日常", 0, 2), CategoryEntity(21, "餐饮", 20, 2))
                        }, onSave = { _, _, _, _, _, _, _, _, _, _ -> saves++ })
                }
            }
        }
    }
}
