package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateListOf
import com.finexy.mobile.data.AccountDraft
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.SecureStore

class AccountManagementQaActivity : PrivacyActivity() {
    val accounts = mutableStateListOf(
        AccountEntity(10, "日常账户", "CNY", displayOrder = 0),
        AccountEntity(20, "多币种钱包", "---", type = 2, category = 4, displayOrder = 1),
        AccountEntity(21, "美元子账户", "USD", parentId = 20, category = 4),
        AccountEntity(22, "欧元子账户", "EUR", parentId = 20, category = 4),
        AccountEntity(30, "停用账户", "CNY", hidden = true)
    )
    var created: AccountDraft? = null
    var modified: Triple<Long, String, String>? = null
    var modifiedDraft: AccountDraft? = null
    var hiddenChange: Pair<Long, Boolean>? = null
    var deletedId: Long? = null
    var movedIds: List<Long> = emptyList()

    override fun privacyStore() = SecureStore(applicationContext, "account-management-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FinexyTheme(true) { Scaffold(containerColor = CanvasBlack) { padding ->
            AccountsScreen(padding, emptyList(), accounts = accounts, accountActionsEnabled = true,
                onCreate = { created = it },
                onModify = { account, draft -> modified = Triple(account.id, draft.name, draft.comment); modifiedDraft = draft },
                onHide = { account, hidden -> hiddenChange = account.id to hidden },
                onDelete = { deletedId = it.id },
                onMove = { movedIds = it.map(AccountEntity::id) })
        } } }
    }

    override fun onDestroy() {
        if (isFinishing) deleteSharedPreferences("account-management-ui-fixture")
        super.onDestroy()
    }
}
