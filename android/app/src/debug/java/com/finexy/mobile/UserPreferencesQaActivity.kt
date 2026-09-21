package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.UserPreferences

/** Non-exported visual and interaction fixture for D8 preferences. */
class UserPreferencesQaActivity : PrivacyActivity() {
    var lightMode by mutableStateOf(true)
    private var preferences by mutableStateOf(UserPreferences())
    private var defaultAccountId by mutableLongStateOf(101L)
    private var defaultCurrency by mutableStateOf("CNY")
    private var message by mutableStateOf<String?>(null)
    override fun privacyStore() = SecureStore(applicationContext, "user-preferences-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinexyTheme(lightMode) {
                Scaffold(containerColor = CanvasBlack) { padding ->
                    UserPreferencesScreen(
                        padding, preferences, defaultAccountId, defaultCurrency,
                        accounts = listOf(
                            AccountEntity(101, "日常账户", "CNY"),
                            AccountEntity(102, "旅行账户", "USD")
                        ),
                        categories = emptyList(),
                        localMode = false, running = false, message = message,
                        onBack = {}, onRefreshCloud = { message = "已从服务器更新偏好" }
                    ) { updated, accountId, currency ->
                        preferences = updated; defaultAccountId = accountId; defaultCurrency = currency
                        message = "偏好已保存并同步"
                    }
                }
            }
        }
    }
}
