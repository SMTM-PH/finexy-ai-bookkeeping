package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import com.finexy.mobile.data.RemoteTransaction
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.SyncConflictEntity
import com.finexy.mobile.data.TransactionEntity

class ConflictQaActivity : PrivacyActivity() {
    var resolved: Pair<String, Boolean>? = null
    override fun privacyStore() = SecureStore(applicationContext, "conflict-ui-fixture")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val local = TransactionEntity(
            localId = "conflict-ui", serverId = 100, type = 3, sourceAccountId = 10,
            destinationAccountId = null, categoryId = 20, categoryName = "餐饮",
            sourceAmountMinor = 1234, destinationAmountMinor = 0, currency = "CNY",
            comment = "本机午餐", time = 1_788_422_000_000, utcOffset = 480,
            tagIdsJson = "[\"1\"]", pictureIdsJson = "[\"2\"]",
            geoLocationJson = "{\"longitude\":116.3,\"latitude\":39.9}", hideAmount = false
        )
        val remote = RemoteTransaction(
            id = 100, timeSequenceId = 1_788_425_600_007, type = 2, categoryId = 22,
            categoryName = "工资", sourceAccountId = 11, destinationAccountId = 12,
            sourceAmountMinor = 5678, destinationAmountMinor = 4321, currency = "USD",
            comment = "服务器工资", time = 1_788_425_600, utcOffset = -240,
            tagIdsJson = "[\"3\"]", pictureIdsJson = "[\"4\"]",
            geoLocationJson = "{\"longitude\":121.4,\"latitude\":31.2}", hideAmount = true
        )
        val conflict = SyncConflictEntity(
            localId = local.localId, serverId = remote.id,
            localComment = local.comment, localAmountMinor = local.sourceAmountMinor,
            remoteComment = remote.comment, remoteAmountMinor = remote.sourceAmountMinor,
            remoteEntityJson = remote.toJson()
        )
        setContent {
            FinexyTheme(true) {
                SettingsScreen(
                    padding = PaddingValues(), serverUrl = "http://test", localMode = false,
                    isLightTheme = true, categories = emptyList(),
                    syncMessage = "同步失败：网络不可用；将在网络恢复后重试",
                    onThemeChange = {}, onAddCategory = {}, onRemoveCategory = {},
                    onSync = {}, onConnect = {}, conflicts = listOf(conflict),
                    conflictTransactions = listOf(local),
                    onResolveConflict = { id, keepRemote -> resolved = id to keepRemote }
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) deleteSharedPreferences("conflict-ui-fixture")
        super.onDestroy()
    }
}
