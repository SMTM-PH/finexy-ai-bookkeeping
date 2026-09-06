package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.AccountMappingEntity
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TransactionEntity

class AccountMappingQaActivity : PrivacyActivity() {
    val mapping = mutableStateOf<AccountMappingEntity?>(null)
    var selectedServerId: Long? = null
    var cleared = false

    override fun privacyStore() = SecureStore(applicationContext, "account-mapping-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinexyTheme(true) {
                Scaffold(containerColor = CanvasBlack) { padding -> SettingsScreen(
                    padding = padding, serverUrl = "http://test", localMode = false,
                    isLightTheme = true, categories = emptyList(), syncMessage = "尚未同步",
                    onThemeChange = {}, onAddCategory = {}, onRemoveCategory = {},
                    onSync = {}, onConnect = {},
                    serverAccounts = listOf(
                        AccountEntity(TransactionEntity.LOCAL_ACCOUNT_ID, "本地钱包", "CNY"),
                        AccountEntity(10, "日常银行卡", "CNY"),
                        AccountEntity(11, "旅行现金", "USD"),
                        AccountEntity(12, "隐藏账户", "CNY", hidden = true)
                    ),
                    accountMappings = listOfNotNull(mapping.value),
                    pendingLocalAccountCount = 4,
                    onMapAccount = { id ->
                        selectedServerId = id
                        mapping.value = AccountMappingEntity(TransactionEntity.LOCAL_ACCOUNT_ID, id)
                    },
                    onClearAccountMapping = {
                        cleared = true
                        mapping.value = null
                    }
                ) }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) deleteSharedPreferences("account-mapping-ui-fixture")
        super.onDestroy()
    }
}
