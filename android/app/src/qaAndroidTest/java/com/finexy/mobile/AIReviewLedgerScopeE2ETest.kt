package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.finexy.mobile.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real FinexyApp navigation, random namespace/user, guarded QA server; missing args fail. */
class AIReviewLedgerScopeE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val registrationStores = mutableListOf<String>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requireIsolatedHost() { qaUrl() }
    private fun qaUrl(): String {
        check(context.packageName == "com.finexy.mobile.qa")
        val args = InstrumentationRegistry.getArguments()
        val url = requireNotNull(args.getString("finexy.ai.source.url") ?: args.getString("finexy.budget.e2e.url"))
        require(url in setOf("http://10.0.2.2:18141", "http://10.0.2.2:18111"))
        return url
    }

    private data class Fixture(val api: FinexyApi, val repository: TransactionRepository,
                               val ledgerId: Long, val reviewId: Long, val comment: String,
                               val defaultAccountId: Long,
                               val baselineDefaultIds: Set<Long>, val baselineSharedIds: Set<Long>)

    @After fun cleanupOnlyFixtureNamespaces() {
        val activity = rule.activity
        val store = activity.store
        check(store.namespace.startsWith("ledger-switch-ui-"))
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), false)
        val tag = "finexy-sync-${identity.take(24)}-${store.namespace.hashCode()}"
        WorkManager.getInstance(context).cancelAllWorkByTag(tag).result.get(10, TimeUnit.SECONDS)
        rule.activityRule.scenario.close()
        registrationStores.forEach { namespace ->
            check(namespace.startsWith("ai-scope-register-"))
            context.deleteSharedPreferences(namespace)
        }
    }

    @Test fun sharedQueueDoesNotExposeDefaultReviewAndDefaultConfirmationUsesAtomicEndpoint() {
        val fixture = prepare()
        openReviewFromShared(fixture)
        waitFor("没有待复核条目")
        rule.onNodeWithText("核对并确认入账").assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        click("总览"); click("个人账本"); openQueue()
        waitFor("核对并确认入账"); click("核对并确认入账")
        waitFor("预览确认入账")
        rule.onNode(hasText("金额（退款保留负号）") and hasSetTextAction()).assertTextContains("12.34")
        click("预览确认入账"); waitFor("确认入账到 默认个人账本"); click("确认入账")
        waitFor("没有待复核条目")
        runBlocking {
            val rows = fixture.api.parseTransactionResponse(fixture.api.listTransactions())
            val posted = rows.filter { it.comment == fixture.comment }
            assertTrue(posted.size == 1 && posted.single().sourceAccountId == fixture.defaultAccountId)
            assertTrue(rows.map { it.id }.toSet().containsAll(fixture.baselineDefaultIds))
            assertTrue(fixture.api.parseTransactionResponse(fixture.api.listTransactions(fixture.ledgerId), fixture.ledgerId).map { it.id }.toSet() == fixture.baselineSharedIds)
            assertTrue(fixture.api.listLedgerAIReviewItems(0).none { it.id == fixture.reviewId })
            assertTrue(fixture.repository.aiReview.requests().isEmpty())
            assertTrue(fixture.repository.ledgerWrites().isEmpty())
        }
        captureQaUiEvidence(rule, "ai-review-default-atomic-isolation")
    }

    @Test fun switchingLedgersRetainsOriginalRawEditorAndNeverInjectsItIntoAnotherQueue() {
        val fixture = prepare()
        openReviewFromShared(fixture); waitFor("没有待复核条目")
        androidx.test.espresso.Espresso.pressBack(); click("总览"); click("个人账本"); openQueue()
        waitFor("核对并确认入账"); click("核对并确认入账"); waitFor("预览确认入账")
        rule.onNode(hasText("金额（退款保留负号）") and hasSetTextAction()).performScrollTo().performTextReplacement("1.+")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack(); waitFor("AI 待复核")
        androidx.test.espresso.Espresso.pressBack(); click("总览")
        rule.onNodeWithContentDescription("切换到账本：AI scope shared").performClick(); openQueue()
        waitFor("没有待复核条目")
        runBlocking {
            val parent = fixture.api.parseCategoryResponse(fixture.api.listCategories()).first { it.type == 2 && it.parentId == 0L && !it.hidden }
            fixture.api.createCategory(CategoryDraft("AI scope refresh leaf", 2, parent.id))
            fixture.repository.mergeAccounts(fixture.api.parseAccountResponse(fixture.api.listAccounts()))
            fixture.repository.mergeCategories(fixture.api.parseCategoryResponse(fixture.api.listCategories()))
            assertTrue(AIReviewEditorForm.read(requireNotNull(fixture.repository.aiReview.draft(0, fixture.reviewId))).fields["sourceAmount"] == "1.+")
            assertTrue(fixture.repository.aiReview.draft(fixture.ledgerId, fixture.reviewId) == null)
            assertTrue(fixture.api.parseTransactionResponse(fixture.api.listTransactions()).map { it.id }.toSet() == fixture.baselineDefaultIds)
            assertTrue(fixture.api.parseTransactionResponse(fixture.api.listTransactions(fixture.ledgerId), fixture.ledgerId).map { it.id }.toSet() == fixture.baselineSharedIds)
            assertTrue(fixture.repository.aiReview.requests().isEmpty())
        }
        rule.onNodeWithText(fixture.comment, substring = true).assertDoesNotExist()
        captureQaUiEvidence(rule, "ai-review-other-ledger-does-not-inject-editor")
        androidx.test.espresso.Espresso.pressBack(); click("总览"); click("个人账本"); openQueue()
        click("恢复本机编辑草稿 · ${fixture.reviewId}"); waitFor("继续编辑原始输入"); click("继续编辑原始输入")
        rule.onNode(hasText("金额（退款保留负号）") and hasSetTextAction()).assertTextContains("1.+")
        captureQaUiEvidence(rule, "ai-review-default-raw-editor-restored")
    }

    private fun prepare(): Fixture = runBlocking {
        val url = qaUrl()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val username = "aiscope$suffix"
        val password = UUID.randomUUID().toString()
        val namespace = "ai-scope-register-$suffix".also(registrationStores::add)
        val store = SecureStore(context, namespace)
        store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
        AccountSecurity(FinexyApi(store)).register(username, "$username@example.invalid", "AI scope QA", password, "CNY")
        store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
        val api = FinexyApi(store)
        val account = api.createAccount(AccountDraft("AI scope default", 1, "CNY", 0)).single()
        val shared = api.createAccount(AccountDraft("AI scope shared account", 1, "CNY", 0)).single()
        val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "AI scope shared", "isolated QA")
        api.moveAccountToLedger(shared.id, ledger.id)
        val parent = api.parseCategoryResponse(api.listCategories()).first { it.type == 2 && it.parentId == 0L && !it.hidden }
        val category = api.createCategory(CategoryDraft("餐饮", 2, parent.id))
        val comment = "AI scope draft $suffix"
        val recognized = RecognizedTransaction(TransactionRepository.TYPE_EXPENSE, null, category.id, account.id, null, 1234, null, "[]", comment)
        val review = api.createAIReviewItem(AIReviewItemEntity.SOURCE_TEXT, "Synthetic account review", recognized)
        val beforeDefault = api.parseTransactionResponse(api.listTransactions()).map { it.id }.toSet()
        val beforeShared = api.parseTransactionResponse(api.listTransactions(ledger.id), ledger.id).map { it.id }.toSet()
        loginQaThroughRealAuthScreen(rule, url, username, password)
        val databaseName = rule.activity.currentDatabaseName()
        check(databaseName != "finexy.db") { "AI scope test must never use the default database" }
        val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), importLegacy = false)
        SyncEngine(FinexyApi(rule.activity.store), repository, autoUpdateExchangeRates = false).sync()
        waitFor("AI scope shared")
        Fixture(api, repository, ledger.id, review.id, comment, account.id, beforeDefault, beforeShared)
    }

    private fun openReviewFromShared(fixture: Fixture) {
        rule.onNodeWithContentDescription("切换到账本：AI scope shared").performClick()
        openQueue()
    }
    private fun openQueue() {
        click("设置")
        rule.onNode(hasText("当前账本 AI 待复核", substring = true) and hasClickAction()).performScrollTo().performClick()
        waitFor("AI 待复核")
    }
    private fun click(text: String) {
        val matcher = hasText(text, substring = false) and hasClickAction()
        rule.waitUntil(30000) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onNode(matcher)
        var parent = node.fetchSemanticsNode().parent
        var scrollable = false
        while (parent != null) {
            if (parent.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) scrollable = true
            parent = parent.parent
        }
        if (scrollable) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    private fun waitFor(text: String) {
        rule.waitUntil(20_000) { rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

}
