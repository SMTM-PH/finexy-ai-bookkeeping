package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.finexy.mobile.data.CategoryDraft
import com.finexy.mobile.data.CategoryEntity
import com.finexy.mobile.data.SecureStore

class CategoryManagementQaActivity : PrivacyActivity() {
    val categories = mutableStateListOf(
        CategoryEntity(10, "日常支出", 0, 2, displayOrder = 1),
        CategoryEntity(11, "餐饮", 10, 2, displayOrder = 1),
        CategoryEntity(12, "交通", 10, 2, displayOrder = 2),
        CategoryEntity(20, "工资收入", 0, 1, displayOrder = 1),
        CategoryEntity(30, "停用分类", 0, 2, hidden = true)
    )
    var created: CategoryDraft? = null
    var modified: Pair<Long, CategoryDraft>? = null
    var hiddenChange: Pair<Long, Boolean>? = null
    var deletedId: Long? = null
    var movedIds: List<Long> = emptyList()
    override fun privacyStore() = SecureStore(applicationContext, "category-management-ui-fixture")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FinexyTheme(true) { Scaffold(containerColor = CanvasBlack) { padding -> Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            ServerCategoryManager(categories, true, false, null, { created = it }, { category, draft -> modified = category.id to draft },
                { category, hidden -> hiddenChange = category.id to hidden }, { deletedId = it.id }, { movedIds = it.map(CategoryEntity::id) })
        } } } }
    }
    override fun onDestroy() { if (isFinishing) deleteSharedPreferences("category-management-ui-fixture"); super.onDestroy() }
}
