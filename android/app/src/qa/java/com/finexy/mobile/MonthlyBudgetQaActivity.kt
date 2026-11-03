package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.finexy.mobile.data.*
import kotlinx.coroutines.CompletableDeferred
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** QA variant only, non-exported, independent Room/prefs, no real HTTP or financial writes. */
class MonthlyBudgetQaActivity : PrivacyActivity() {
    private val namespace = "budget-ui-fixture-${UUID.randomUUID()}"
    private lateinit var database: FinexyDatabase
    internal val fixture = MonthlyBudgetUiFixture()
    var selectedLedgerId by mutableLongStateOf(9)
    var largeText by mutableStateOf(false)
    var lightTheme by mutableStateOf(true)
    override fun privacyStore() = SecureStore(applicationContext, namespace)

    override fun onCreate(savedInstanceState: Bundle?) {
        check(packageName == "com.finexy.mobile.qa") { "Budget fixture requires the isolated QA package" }
        super.onCreate(savedInstanceState)
        database = FinexyDatabase.get(applicationContext, "$namespace.db")
        val repository = TransactionRepository(applicationContext, database, false)
        val store = privacyStore()
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else density.fontScale)) {
                FinexyTheme(lightTheme) {
                    Scaffold(containerColor = CanvasBlack) { padding ->
                        Box(Modifier.padding(padding).width(if (largeText) 320.dp else 640.dp)) {
                            MonthlyBudgetPage(store, repository, false, selectedLedgerId, gateway = fixture, onBack = {})
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::database.isInitialized) database.close()
        check(namespace.startsWith("budget-ui-fixture-"))
        applicationContext.deleteDatabase("$namespace.db")
        applicationContext.deleteSharedPreferences(namespace)
    }
}

internal class MonthlyBudgetUiFixture : MonthlyBudgetGateway {
    val initialRead = CompletableDeferred<Unit>()
    var role = 1
    var failingReads = 0
    var successfulSaves = 0
    var successfulDeletes = 0
    val attemptedRevisions = mutableListOf<Long>()
    var lastWriteLedger = 0L
    private val month = YearMonth.now().let { it.year * 100 + it.monthValue }
    val budgets = mutableMapOf<Long, MonthlyBudget?>(
        9L to MonthlyBudget(90, 9, month, 10000, "CNY", emptyMap(), 1),
        10L to MonthlyBudget(100, 10, month, 5000, "USD", emptyMap(), 1))
    override suspend fun role(ledgerId: Long): Int {
        if (ledgerId == 9L) initialRead.await()
        return role
    }
    override suspend fun get(ledgerId: Long, yearMonth: Int): MonthlyBudget? {
        if (ledgerId == 9L) initialRead.await()
        if (failingReads > 0) { failingReads--; error("Injected incomplete budget response") }
        return budgets[ledgerId]?.takeIf { it.yearMonth == yearMonth }
    }
    override suspend fun set(ledgerId: Long, draft: MonthlyBudgetDraft): MonthlyBudget {
        attemptedRevisions += draft.expectedRevision
        if (!monthlyBudgetCanManage(role)) throw ApiException(403, "Injected permission change")
        if ((budgets[ledgerId]?.revision ?: 0) != draft.expectedRevision) throw ApiException(409, "Injected stale budget revision")
        lastWriteLedger = ledgerId; successfulSaves++
        val saved = MonthlyBudget(90, ledgerId, draft.yearMonth, draft.amountMinor, draft.currency, draft.categoryAmounts, draft.expectedRevision + 1)
        budgets[ledgerId] = saved
        return saved
    }
    override suspend fun delete(ledgerId: Long, yearMonth: Int, expectedRevision: Long) {
        if (!monthlyBudgetCanManage(role)) throw ApiException(403, "Injected permission change")
        if (budgets[ledgerId]?.revision != expectedRevision) throw ApiException(409, "Injected stale budget revision")
        lastWriteLedger = ledgerId; successfulDeletes++; budgets[ledgerId] = null
    }
    override suspend fun categories(ledgerId: Long) = listOf(MonthlyBudgetCategory(21, "合成分类 / 合成支出", 20, false))
    override suspend fun expenses(ledgerId: Long, yearMonth: Int, zone: ZoneId) = MonthlyBudgetExpenseSnapshot(setOf("CNY", "USD"), emptyMap(), emptyMap())
}
