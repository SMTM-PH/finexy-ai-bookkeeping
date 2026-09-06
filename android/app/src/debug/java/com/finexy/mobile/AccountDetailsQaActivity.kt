package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableLongStateOf
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TransactionEntity

/** Non-exported production-style host for account list/detail interaction tests. */
class AccountDetailsQaActivity : PrivacyActivity() {
    val defaultAccountId = mutableLongStateOf(10L)
    var entryAccountId: Long? = null

    override fun privacyStore() = SecureStore(applicationContext, "account-details-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = System.currentTimeMillis()
        val activities = listOf(
            Activity("今天午餐", "-¥ 25.00", "支出", "餐饮", accountId = 10, time = now),
            Activity("八天前交通", "-¥ 18.00", "支出", "交通", accountId = 10, time = now - 8 * 86_400_000L),
            Activity("四十天前购物", "-¥ 99.00", "支出", "购物", accountId = 10, time = now - 40 * 86_400_000L),
            Activity("其他账户流水", "+¥ 88.00", "收入", "工资", accountId = 11, time = now)
        )
        val accounts = listOf(
            AccountEntity(10, "日常银行卡", "CNY", 123_456),
            AccountEntity(11, "旅行现金", "USD", 88_00),
            AccountEntity(12, "已停用账户", "CNY", hidden = true)
        )
        setContent {
            FinexyTheme(true) {
                Scaffold(containerColor = CanvasBlack) { padding ->
                    AccountsScreen(padding, activities, accounts = accounts, selectedAccountId = defaultAccountId.longValue,
                        onSelectAccount = { defaultAccountId.longValue = it }, onEntry = { entryAccountId = it })
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) deleteSharedPreferences("account-details-ui-fixture")
        super.onDestroy()
    }
}
