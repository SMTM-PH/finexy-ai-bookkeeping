package com.finexy.mobile

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import com.finexy.mobile.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class LedgerReportQaEntry { OVERVIEW, ACCOUNTS }

internal data class ReportLedgerTarget(val id: Long, val name: String, val accountId: Long)

/**
 * Real FinexyApp login and report navigation, against the dedicated disposable HTTP service.
 * No session is injected into the host, no report state is replaced, and no default database is used.
 */
internal class LedgerReportQaFixture(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<LedgerSwitchQaActivity>, LedgerSwitchQaActivity>
) : AutoCloseable {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val registrationNamespaces = mutableListOf<String>()
    private var ownedNamespace: String? = null
    private var ownedIdentity: String? = null
    private var openedLedgerId: Long? = null
    private var baseline = emptyMap<Long, String>()
    private var closed = false
    private class Actor(val username: String, val password: String, val api: FinexyApi) {
        override fun toString() = "SyntheticReportActor"
    }

    private lateinit var actor: Actor
    val api: FinexyApi get() = actor.api
    lateinit var repository: TransactionRepository
        private set
    lateinit var ledgerA: ReportLedgerTarget
        private set
    lateinit var ledgerB: ReportLedgerTarget
        private set
    lateinit var defaultLedger: ReportLedgerTarget
        private set

    fun prepare(): LedgerReportQaFixture {
        check(!::actor.isInitialized && !closed)
        val url = requireReportQaUrl()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        actor = runBlocking {
            val username = "rnav$suffix"
            val password = UUID.randomUUID().toString()
            val namespace = "report-navigation-register-$suffix".also(registrationNamespaces::add)
            val store = SecureStore(context, namespace)
            store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            AccountSecurity(FinexyApi(store)).register(
                username, "$username@example.invalid", "Synthetic report navigation", password, "CNY"
            )
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            Actor(username, password, FinexyApi(store))
        }
        runBlocking {
            val a = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Report navigation A", "Disposable UI fixture")
            val b = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Report navigation B", "Disposable UI fixture")
            ledgerA = ReportLedgerTarget(a.id, a.name, createAccount(a.id, "Synthetic report A account"))
            ledgerB = ReportLedgerTarget(b.id, b.name, createAccount(b.id, "Synthetic report B account"))
            defaultLedger = ReportLedgerTarget(0, "个人账本", createAccount(0, "Synthetic report default account"))
            val income = leaf("Synthetic report income", 1)
            val expense = leaf("Synthetic report expense", 2)
            post(ledgerA, 2, income, 25000, "Synthetic A income", 0)
            post(ledgerA, 3, expense, 1234, "Synthetic A visible expense", 1)
            post(ledgerA, 3, expense, 567, "Synthetic A hidden expense", 2, hidden = true)
            post(ledgerB, 2, income, 9000, "Synthetic B income", 3)
            post(ledgerB, 3, expense, 432, "Synthetic B expense", 4)
            post(defaultLedger, 2, income, 1700, "Synthetic default income", 5)
            post(defaultLedger, 3, expense, 123, "Synthetic default expense", 6)
            baseline = targets().associate { it.id to financialDigest(it.id) }
        }
        loginQaThroughRealAuthScreen(rule, url, actor.username, actor.password)
        val host = rule.activity
        check(host.preferencesName.startsWith("ledger-switch-ui-"))
        ownedNamespace = host.preferencesName
        ownedIdentity = LedgerScope.identity(url, host.store.get(FinexyApi.KEY_TOKEN), false)
        val databaseName = host.currentDatabaseName()
        check(databaseName != "finexy.db")
        repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), importLegacy = false)
        // The normal login schedules its normal first sync. Wait for initialization before
        // cancelling only this fixture's work and performing the explicit fixture pull.
        val bootstrap = onMain {
            ViewModelProvider(host).get(
                "ledger-bootstrap:${host.store.namespace}:${repository.aiReview.storagePartition}", LedgerBootstrapViewModel::class.java
            )
        }
        rule.waitUntil(30_000) { bootstrap.state.value.status == LedgerBootstrapStatus.READY }
        UserPreferenceStore(host.store, databaseName).let { preferences ->
            preferences.save(preferences.load().copy(syncPolicy = SyncPolicy.MANUAL, autoUpdateExchangeRatesData = false))
        }
        cancelOwnedSync()
        runBlocking { SyncEngine(api, repository, autoUpdateExchangeRates = false).sync() }
        rule.waitUntil(30_000) { !SyncActivity.state.running }
        waitFor("Report navigation A")
        waitFor("Report navigation B")
        return this
    }

    fun query(ledgerId: Long = ledgerA.id, includeHidden: Boolean = false): LedgerReportQuery =
        LedgerReportForm("2026-10-01", "2026-10-31", "UTC", hidden = includeHidden).query(ledgerId)

    fun openReport(ledgerId: Long = ledgerA.id, entry: LedgerReportQaEntry = LedgerReportQaEntry.OVERVIEW) {
        val target = targets().single { it.id == ledgerId }
        selectLedger(target)
        clickText(if (entry == LedgerReportQaEntry.OVERVIEW) "总览" else "账户")
        assertSelectedLedger(target)
        clickText(if (entry == LedgerReportQaEntry.OVERVIEW) "查看当前账本报告" else "查看当前账本报告与导出")
        openedLedgerId = ledgerId
        waitFor("当前账本报告")
        reportHeader(ledgerId).assertIsDisplayed()
        rule.onNodeWithText("读取报告").assertExists()
        // Report creation must not silently choose another ledger or populate a financial result.
        assertTrue("Entering a report must preserve its original selected ledger", productionState().ledgerId == ledgerId)
    }

    /** Applies the actual production form, then returns an independently fetched API oracle. */
    fun applyReport(includeHidden: Boolean = false): LedgerReportPage {
        val ledgerId = requireNotNull(openedLedgerId)
        if (rule.onAllNodesWithText("展开日期、类型、账户、分类和标签").fetchSemanticsNodes().isNotEmpty()) {
            clickText("展开日期、类型、账户、分类和标签")
        }
        setField("开始日期 YYYY-MM-DD", "2026-10-01")
        setField("结束日期 YYYY-MM-DD", "2026-10-31")
        setField("报告时区，例如 Asia/Shanghai", "UTC")
        val hidden = findText("包含隐藏记录")
        val selected = hidden.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        if (selected != includeHidden) hidden.performScrollTo().performClick()
        clickText("收起筛选")
        clickText("读取报告")
        rule.waitUntil(30_000) { productionState().completeOnlineSource }
        waitFor("完整在线报告")
        val expected = runBlocking {
            val request = query(ledgerId, includeHidden)
            api.queryLedgerReport(api.captureLedgerReportContext(ledgerId), request)
        }
        assertAgainstOracle(expected)
        return expected
    }

    fun assertAgainstOracle(expected: LedgerReportPage) {
        val shown = productionState()
        val received = requireNotNull(shown.report)
        assertTrue("The real report page must use the original complete API query and snapshot",
            shown.completeOnlineSource && shown.ledgerId == expected.query.ledgerId &&
                received.query == expected.query && received.ranges == expected.ranges &&
                received.snapshotHash == expected.snapshotHash && received.matchedCount == expected.matchedCount &&
                received.totals == expected.totals && shown.details == expected.details)
        reportHeader(expected.query.ledgerId).assertIsDisplayed()
        findText("${expected.query.startDate} 至 ${expected.query.endDate} · ${expected.query.timezone}").assertIsDisplayed()
        findText("完整在线来源 · ${expected.matchedCount} 笔 · 已读取明细 ${expected.details.size} 笔").assertIsDisplayed()
        val amountsVisible = onMain {
            ViewModelProvider(rule.activity).get(
                "ledger-report-files-${requireNotNull(ownedNamespace)}", LedgerReportFileViewModel::class.java
            ).amountsVisible.value
        }
        if (!amountsVisible) clickText("显示报告金额")
        for (money in expected.totals) {
            for ((label, amount) in listOf("收入" to money.income, "净支出" to money.expense, "结余" to money.net,
                "调整差额" to money.adjustment, "转入" to money.transferIn, "转出" to money.transferOut)) {
                findText("$label：${LedgerReportContract.formatAmount(amount)}").assertIsDisplayed()
            }
        }
    }

    fun productionState(): LedgerReportState = onMain {
        ViewModelProvider(rule.activity).get(
            "ledger-report-${requireNotNull(ownedNamespace)}", LedgerReportViewModel::class.java
        ).state.value
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    suspend fun expectedExport(includeHidden: Boolean = false, ledgerId: Long = ledgerA.id): LedgerReportDownload {
        val request = query(ledgerId, includeHidden)
        val reportContext = api.captureLedgerReportContext(ledgerId)
        val page = api.queryLedgerReport(reportContext, request)
        check(page.detailsComplete)
        val preview = api.previewLedgerReportExport(reportContext, page)
        return api.downloadLedgerReportCsv(reportContext, page, preview, confirmed = true, includeHiddenAmounts = includeHidden)
    }

    suspend fun assertFinanciallyUnchanged() {
        targets().forEach { target ->
            assertTrue("Report navigation, preview and system handoff must preserve every fixture ledger",
                baseline.getValue(target.id) == financialDigest(target.id))
        }
        assertTrue("Report UI must not create an offline transaction request", repository.ledgerWrites().isEmpty())
        assertTrue("Report UI must not create an AI request", repository.aiReview.requests().isEmpty())
    }

    fun returnToEntry(ledgerId: Long, entry: LedgerReportQaEntry) {
        clickText("返回")
        waitFor(if (entry == LedgerReportQaEntry.OVERVIEW) "查看当前账本报告" else "查看当前账本报告与导出")
        assertSelectedLedger(targets().single { it.id == ledgerId })
        // 报告页同时展示标题文本与可选择的 Tab；断言必须落在可交互的 Tab 上。
        rule.onNode(hasText(if (entry == LedgerReportQaEntry.OVERVIEW) "总览" else "账户", substring = false) and
            hasClickAction()).assertIsSelected()
        rule.onNodeWithText("当前账本报告", substring = false).assertDoesNotExist()
        openedLedgerId = null
    }

    fun clickText(text: String) {
        val node = findText(text, clickable = true)
        var parent = node.fetchSemanticsNode().parent
        var scrollable = false
        while (parent != null) {
            if (parent.config.contains(SemanticsActions.ScrollBy)) scrollable = true
            parent = parent.parent
        }
        if (scrollable) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }

    fun findText(text: String, clickable: Boolean = false): SemanticsNodeInteraction {
        val matcher = hasText(text, substring = false).let { if (clickable) it and hasClickAction() else it }
        if (rule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        }
        val node = rule.onAllNodes(matcher).onFirst()
        var parent = node.fetchSemanticsNode().parent
        var scrollable = false
        while (parent != null) {
            if (parent.config.contains(SemanticsActions.ScrollBy)) scrollable = true
            parent = parent.parent
        }
        if (scrollable) node.performScrollTo()
        return node
    }

    fun waitFor(text: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun reportHeader(ledgerId: Long): SemanticsNodeInteraction = findText(
        "${if (ledgerId == 0L) "默认个人账本" else "当前账本"} · 账本 #$ledgerId"
    )

    private fun setField(label: String, value: String) {
        val matcher = hasText(label, substring = false) and hasSetTextAction()
        if (rule.onAllNodes(matcher).fetchSemanticsNodes().isEmpty()) {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
        }
        rule.onNode(matcher).performScrollTo().performTextReplacement(value)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }

    private fun selectLedger(target: ReportLedgerTarget) {
        val node = if (target.id == 0L) rule.onNode(hasText("个人账本", substring = false) and hasClickAction())
            else rule.onNodeWithContentDescription("切换到账本：${target.name}")
        node.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertSelectedLedger(target)
    }

    private fun assertSelectedLedger(target: ReportLedgerTarget) {
        if (target.id == 0L) rule.onNode(hasText("个人账本", substring = false) and hasClickAction()).assertIsSelected()
        else rule.onNodeWithContentDescription("切换到账本：${target.name}").assertIsSelected()
    }

    private fun targets() = listOf(defaultLedger, ledgerA, ledgerB)

    private suspend fun createAccount(ledgerId: Long, name: String): Long {
        val payload = AccountDraft(name, 1, "CNY", 0).toCreatePayload()
            .put("ledgerId", ledgerId.toString()).put("balanceTime", 946684800)
        return JSONObject(api.request("v1/accounts/add.json", payload.toString())).getJSONObject("result").getString("id").toLong()
    }

    private suspend fun leaf(name: String, type: Int): Long {
        val parent = api.createCategory(CategoryDraft("$name parent", type, 0))
        return api.createCategory(CategoryDraft(name, type, parent.id)).id
    }

    private suspend fun post(target: ReportLedgerTarget, type: Int, category: Long, amount: Long, comment: String,
        ordinal: Int, hidden: Boolean = false) {
        val payload = JSONObject().put("ledgerId", target.id.toString()).put("type", type)
            .put("categoryId", category.toString()).put("sourceAccountId", target.accountId.toString())
            .put("sourceAmount", amount).put("destinationAccountId", "0").put("destinationAmount", 0)
            .put("time", 1792756800L + ordinal).put("utcOffset", 0).put("hideAmount", hidden)
            .put("tagIds", JSONArray()).put("pictureIds", JSONArray()).put("comment", comment)
        api.parseWrittenTransaction(api.addTransaction(payload, "report-navigation-" + UUID.randomUUID())).also {
            check(it.ledgerId == target.id && it.sourceAmountMinor == amount)
        }
    }

    private suspend fun financialDigest(ledgerId: Long): String {
        val accounts = api.parseAccountResponse(api.listAccounts(ledgerId)).sortedBy { it.id }
            .map { listOf(it.id.toString(), it.currency, it.balanceMinor.toString(), it.hidden.toString()) }
        val rows = api.parseTransactionResponse(api.listTransactions(ledgerId), ledgerId).sortedBy { it.id }.map { it.toJson() }
        val content = (accounts.toString() + "\n" + rows.joinToString("\n")).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun cancelOwnedSync() {
        val namespace = ownedNamespace ?: return
        val identity = ownedIdentity ?: return
        check(namespace.startsWith("ledger-switch-ui-"))
        WorkManager.getInstance(context).cancelAllWorkByTag(
            "finexy-sync-${identity.take(24)}-${namespace.hashCode()}"
        ).result.get(10, TimeUnit.SECONDS)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            cancelOwnedSync()
            runBlocking { withTimeout(15_000) { SyncEngine.withoutSync { } } }
        } finally {
            try {
                rule.activityRule.scenario.close()
            } finally {
                registrationNamespaces.forEach { namespace ->
                    check(namespace.matches(Regex("report-navigation-register-[a-f0-9]{16}")))
                    context.deleteSharedPreferences(namespace)
                }
            }
        }
    }

    companion object {
        fun requireReportQaUrl(): String {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.finexy.mobile.qa")
            val arguments = InstrumentationRegistry.getArguments()
            require(arguments.getString("finexy.e2e.url") == "http://10.0.2.2:18155")
            require(arguments.getString("finexy.e2e.disposable") == "true")
            return "http://10.0.2.2:18155"
        }
    }
}
