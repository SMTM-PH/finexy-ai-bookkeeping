package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateListOf
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TagEntity

class TagManagementQaActivity : PrivacyActivity() {
    val tags = mutableStateListOf(TagEntity(10, "报销", 0), TagEntity(11, "旅行", 0))
    var addedName: String? = null
    var editedName: String? = null
    var hiddenId: Long? = null
    var deletedId: Long? = null

    override fun privacyStore() = SecureStore(applicationContext, "tag-management-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinexyTheme(true) {
                Scaffold(containerColor = CanvasBlack) { padding ->
                    SettingsScreen(
                        padding, "http://test", false, true, emptyList(), "尚未同步",
                        {}, {}, {}, {}, {}, tags = tags,
                        onAddTag = { addedName = it; tags += TagEntity(12, it, 0) },
                        onEditTag = { tag, name -> editedName = name; tags[tags.indexOfFirst { it.id == tag.id }] = tag.copy(name = name) },
                        onHideTag = { hiddenId = it; tags.removeAll { tag -> tag.id == it } },
                        onRemoveTag = { deletedId = it; tags.removeAll { tag -> tag.id == it } }
                    )
                }
            }
        }
    }
}
