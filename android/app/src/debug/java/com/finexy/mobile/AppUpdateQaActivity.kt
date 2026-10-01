package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.finexy.mobile.data.AppUpdateInfo
import com.finexy.mobile.data.SecureStore

/** Non-exported visual and interaction fixture for the update reminder. */
class AppUpdateQaActivity : PrivacyActivity() {
    var lightMode by mutableStateOf(true)
    var update by mutableStateOf<AppUpdateInfo?>(
        AppUpdateInfo(
            version = "1.0.2",
            title = "Finexy 1.10.0 · 移动端体验更新",
            releaseUrl = "https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases/tag/v1.10.0",
            apkUrl = "https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases/download/v1.10.0/Finexy-Android-1.0.2.apk"
        )
    )
    var opened by mutableStateOf(false)
    override fun privacyStore() = SecureStore(applicationContext, "app-update-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinexyTheme(lightMode) {
                Scaffold(containerColor = CanvasBlack) { padding ->
                    Dashboard(
                        padding = padding,
                        balance = 12888.0,
                        income = 16800.0,
                        expense = 3912.0,
                        activities = emptyList(),
                        appUpdate = update,
                        appUpdateMessage = if (opened) "已打开发布页面" else null,
                        onOpenUpdate = { opened = true },
                        onDismissUpdate = { update = null },
                        onAll = {}, onWallet = {}, onSettings = {}, onEntry = {}
                    )
                }
            }
        }
    }
}
