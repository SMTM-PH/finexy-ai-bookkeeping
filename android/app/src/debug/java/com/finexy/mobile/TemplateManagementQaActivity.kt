package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.finexy.mobile.data.*

class TemplateManagementQaActivity : PrivacyActivity() {
    val templates = mutableStateListOf(TemplateEntity(20, "每日早餐", 3, 101, 10, 1250, "豆浆油条", "[\"30\"]"))
    var added: TemplateEntity? = null
    var edited: TemplateEntity? = null
    var deletedId: Long? = null
    val actionMessage = mutableStateOf<String?>(null)
    val retryAvailable = mutableStateOf(false)
    var retryCount = 0

    override fun privacyStore() = SecureStore(applicationContext, "template-management-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinexyTheme(true) { Scaffold(containerColor = CanvasBlack) { padding ->
                SettingsScreen(
                    padding, "http://test", false, true, emptyList(), "尚未同步", {}, {}, {}, {}, {},
                    serverAccounts = listOf(AccountEntity(10, "日常银行卡", "CNY"), AccountEntity(11, "旅行现金", "CNY")),
                    serverCategories = listOf(CategoryEntity(100, "日常", type = 2), CategoryEntity(101, "餐饮", parentId = 100, type = 2), CategoryEntity(200, "收入", type = 1), CategoryEntity(201, "工资", parentId = 200, type = 1)),
                    tags = listOf(TagEntity(30, "工作", 0), TagEntity(31, "重要", 0)), templates = templates,
                    templateActionMessage = actionMessage.value, templateRetryAvailable = retryAvailable.value,
                    onRetryTemplate = { retryCount++; actionMessage.value = "模板已保存"; retryAvailable.value = false },
                    onAddTemplate = { added = it; templates += it.copy(id = 21) },
                    onEditTemplate = { edited = it; templates[0] = it },
                    onRemoveTemplate = { deletedId = it; templates.removeAll { template -> template.id == it } }
                )
            } }
        }
    }
}
