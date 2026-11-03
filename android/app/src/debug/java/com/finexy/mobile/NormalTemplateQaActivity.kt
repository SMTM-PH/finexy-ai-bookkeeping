package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.room.Room
import com.finexy.mobile.data.*
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** In-memory fixtures only, never the main ledger or an authenticated server. */
class NormalTemplateQaActivity : PrivacyActivity() {
    private val namespace = "normal-template-ui-${UUID.randomUUID()}"
    private val store by lazy { SecureStore(applicationContext, namespace) }
    private val database by lazy { Room.inMemoryDatabaseBuilder(applicationContext, FinexyDatabase::class.java).build() }
    private val repository by lazy { TransactionRepository(applicationContext, database, false) }
    var role by mutableIntStateOf(RemoteLedgerMember.ROLE_OWNER)
    var seed by mutableStateOf<TemplateEntrySeed?>(null)
    var saved: TransactionDraft? = null
    val accounts = listOf(AccountEntity(10, "外币账户", "USD"), AccountEntity(11, "转入账户", "CNY"))
    val categories = listOf(CategoryEntity(20, "日常", type = 2), CategoryEntity(21, "同名", 20, 2),
        CategoryEntity(22, "其他", type = 2), CategoryEntity(23, "同名", 22, 2),
        CategoryEntity(30, "转账", type = 3), CategoryEntity(31, "账户间", 30, 3))
    override fun privacyStore() = store
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runBlocking { database.dao().upsertTemplates(listOf(
            TemplateEntity(100, "退款模板", 3, 23, 10, -1250, "退款说明", "[\"35\"]", hideAmount = true, ledgerId = 9),
            TemplateEntity(101, "双金额模板", 4, 31, 10, 12345, "转账说明", destinationAccountId = 11, destinationAmountMinor = 12200, ledgerId = 9))) }
        setContent { FinexyTheme(true) { Scaffold(containerColor = CanvasBlack) { padding ->
            if (seed == null) NormalTemplatePage(store, repository, false, 9, onApply = { seed = it },
                fixtureResources = PeriodicResources(accounts, categories, role, loading = false), onBack = {})
            else EntryScreen(padding, false, existing = seed!!.toEntryActivity(), accounts = accounts, serverCategories = categories,
                defaultAccountId = 10, onlineOnly = true, onSave = { amount, note, type, name, category, localId, account, tags, destination, destinationAmount ->
                    saved = TransactionDraft(requireNotNull(localId), type, account, category, name, amount, note,
                        tagIdsJson = tags, destinationAccountId = destination, destinationAmountMinor = destinationAmount, hideAmount = seed!!.hideAmount)
                })
        } } }
    }
    override fun onDestroy() { super.onDestroy(); if (isFinishing) { database.close(); deleteSharedPreferences(namespace) } }
}
