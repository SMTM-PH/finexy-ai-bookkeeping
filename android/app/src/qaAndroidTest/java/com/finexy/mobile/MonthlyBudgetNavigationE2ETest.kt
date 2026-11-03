package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.finexy.mobile.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** True settings navigation and HTTP writes through FinexyApp, in the isolated QA UID only. */
class MonthlyBudgetNavigationE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val registrationStores = mutableListOf<String>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requireIsolatedBudgetHost() { monthlyBudgetQaUrl() }

    private data class Actor(val username: String, val password: String, val api: FinexyApi)
    private data class FinancialSnapshot(val balances: Map<Long, Long>, val transactionIds: Set<Long>)
    private data class Fixture(val owner: Actor, val actor: Actor, val repository: TransactionRepository,
        val ledgerId: Long, val month: Int, val sharedBudget: MonthlyBudget,
        val ownerDefaultBudget: MonthlyBudget, val actorDefaultBudget: MonthlyBudget,
        val ownerDefaultSnapshot: FinancialSnapshot, val actorDefaultSnapshot: FinancialSnapshot,
        val sharedSnapshot: FinancialSnapshot)

    @After fun cleanOnlyOwnFixtureDataAndWork() {
        val store = rule.activity.store
        check(store.namespace.startsWith("ledger-switch-ui-"))
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), false)
        val tag = "finexy-sync-${identity.take(24)}-${store.namespace.hashCode()}"
        WorkManager.getInstance(context).cancelAllWorkByTag(tag).result.get(10, TimeUnit.SECONDS)
        rule.activityRule.scenario.close()
        registrationStores.forEach { namespace ->
            check(namespace.startsWith("budget-navigation-register-"))
            context.deleteSharedPreferences(namespace)
        }
    }

    @Test fun ownerOpensSelectedLedgerBudgetSavesOnlyThatLedgerAndReturnsToIt() {
        val fixture = prepare(viewer = false)
        openSelectedBudget()
        waitFor("我的角色：所有者")
        waitFor("USD 150.00")
        rule.onNodeWithText("账本：Budget navigation A").assertExists()
        rule.onNodeWithText("EUR 777.00").assertDoesNotExist()
        rule.onNodeWithText("编辑本月预算").performScrollTo().performClick()
        rule.onNodeWithText("币种代码，如 CNY / USD").assertTextContains("USD")
        rule.onNodeWithText("月度总预算金额").performScrollTo().performTextReplacement("200.00")
        rule.onNodeWithText("保存本月预算").performScrollTo().assertIsEnabled().performClick()
        waitFor("预算已保存")
        waitFor("USD 200.00")
        captureQaUiEvidence(rule, "budget-navigation-owner-saved")
        runBlocking {
            val saved = fixture.owner.api.getMonthlyBudget(fixture.ledgerId, fixture.month)
            assertTrue("Settings save must change only the captured ledger budget with its fixed currency and revision",
                saved != null && saved.id == fixture.sharedBudget.id && saved.ledgerId == fixture.ledgerId &&
                    saved.yearMonth == fixture.month && saved.currency == "USD" && saved.amountMinor == 20000L &&
                    saved.categoryAmounts == fixture.sharedBudget.categoryAmounts && saved.revision > fixture.sharedBudget.revision)
            assertUnchangedOutsideBudget(fixture)
        }
        returnToSelectedLedgerSettings()
        captureQaUiEvidence(rule, "budget-navigation-owner-returned")
        rule.onNodeWithContentDescription("查看当前账本月度预算").performScrollTo().performClick()
        waitFor("账本：Budget navigation A")
        waitFor("USD 200.00")
        rule.onNodeWithText("我的角色：所有者").assertExists()
    }

    @Test fun viewerOpensSelectedLedgerBudgetReadOnlyAndReturnsToIt() {
        val fixture = prepare(viewer = true)
        openSelectedBudget()
        waitFor("我的角色：只读成员，仅查看预算")
        waitFor("USD 150.00")
        rule.onNodeWithText("账本：Budget navigation A").assertExists()
        rule.onNodeWithText("CNY 333.00").assertDoesNotExist()
        listOf("编辑本月预算", "设置本月预算", "删除本月预算", "保存本月预算", "月度总预算金额").forEach { label ->
            rule.onNodeWithText(label, substring = false).assertDoesNotExist()
        }
        captureQaUiEvidence(rule, "budget-navigation-viewer-read-only")
        runBlocking {
            assertTrue("Viewer must read the owner's actual selected-ledger budget", fixture.actor.api.getMonthlyBudget(fixture.ledgerId, fixture.month) == fixture.sharedBudget)
            assertTrue("Viewing must preserve the shared budget and revision", fixture.owner.api.getMonthlyBudget(fixture.ledgerId, fixture.month) == fixture.sharedBudget)
            assertUnchangedOutsideBudget(fixture)
        }
        returnToSelectedLedgerSettings()
        captureQaUiEvidence(rule, "budget-navigation-viewer-returned")
        rule.onNodeWithContentDescription("查看当前账本月度预算").performScrollTo().performClick()
        waitFor("账本：Budget navigation A")
        waitFor("我的角色：只读成员，仅查看预算")
        waitFor("USD 150.00")
        rule.onNodeWithText("编辑本月预算").assertDoesNotExist()
    }

    private fun prepare(viewer: Boolean): Fixture = runBlocking {
        val url = monthlyBudgetQaUrl()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(14)
        suspend fun register(role: String): Actor {
            val username = "budgetnav$suffix$role"
            val password = UUID.randomUUID().toString()
            val namespace = "budget-navigation-register-$suffix-$role".also(registrationStores::add)
            val store = SecureStore(context, namespace)
            store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            AccountSecurity(FinexyApi(store)).register(username, "$username@example.invalid", "Budget navigation QA", password, "CNY")
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            return Actor(username, password, FinexyApi(store))
        }
        val owner = register("o")
        owner.api.createAccount(AccountDraft("Budget navigation default sentinel", 1, "CNY", 4567))
        val sharedAccount = owner.api.createAccount(AccountDraft("Budget navigation shared account", 1, "USD", 0)).single()
        val ledger = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Budget navigation A", "isolated QA")
        owner.api.moveAccountToLedger(sharedAccount.id, ledger.id)
        val month = YearMonth.now(ZoneId.systemDefault()).let { it.year * 100 + it.monthValue }
        val ownerDefault = owner.api.setMonthlyBudget(0, MonthlyBudgetDraft(month, 77700, "EUR"))
        val selectedBudget = owner.api.setMonthlyBudget(ledger.id, MonthlyBudgetDraft(month, 15000, "USD"))
        val actor = if (viewer) register("v") else owner
        val actorDefault = if (viewer) {
            val invitation = owner.api.createLedgerInvitation(ledger.id, actor.username, RemoteLedgerMember.ROLE_VIEWER)
            actor.api.acceptLedgerInvitation(invitation.token)
            actor.api.createAccount(AccountDraft("Budget navigation viewer default sentinel", 1, "CNY", 1234))
            actor.api.setMonthlyBudget(0, MonthlyBudgetDraft(month, 33300, "CNY"))
        } else ownerDefault
        val ownerSnapshot = financialSnapshot(owner.api, 0)
        val actorSnapshot = if (viewer) financialSnapshot(actor.api, 0) else ownerSnapshot
        val sharedSnapshot = financialSnapshot(owner.api, ledger.id)
        loginQaThroughRealAuthScreen(rule, url, actor.username, actor.password)
        val databaseName = rule.activity.currentDatabaseName()
        check(databaseName != "finexy.db") { "Budget navigation must never use the default database" }
        val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), importLegacy = false)
        SyncEngine(FinexyApi(rule.activity.store), repository, autoUpdateExchangeRates = false).sync()
        waitFor("Budget navigation A")
        Fixture(owner, actor, repository, ledger.id, month, selectedBudget, ownerDefault, actorDefault, ownerSnapshot, actorSnapshot, sharedSnapshot)
    }

    private fun openSelectedBudget() {
        rule.onNodeWithContentDescription("切换到账本：Budget navigation A").performClick()
        rule.onNodeWithContentDescription("切换到账本：Budget navigation A").assertIsSelected()
        rule.onNodeWithText("设置", substring = false).performClick()
        rule.onNodeWithContentDescription("查看当前账本月度预算").performScrollTo().assertIsEnabled().performClick()
        waitFor("账本：Budget navigation A")
    }

    private fun returnToSelectedLedgerSettings() {
        rule.onNodeWithText("返回设置", substring = false).performScrollTo().assertIsEnabled().performClick()
        waitFor("当前账本月度预算")
        rule.onNodeWithContentDescription("切换到账本：Budget navigation A").assertIsSelected()
        rule.onNodeWithText("个人账本", substring = false).assertIsNotSelected()
    }

    private suspend fun financialSnapshot(api: FinexyApi, ledgerId: Long) = FinancialSnapshot(
        api.parseAccountResponse(api.listAccounts(ledgerId)).associate { it.id to it.balanceMinor },
        api.parseTransactionResponse(api.listTransactions(ledgerId), ledgerId).map { it.id }.toSet())

    private suspend fun assertUnchangedOutsideBudget(fixture: Fixture) {
        assertTrue("Saving/viewing the selected budget must preserve the owner's default budget", fixture.owner.api.getMonthlyBudget(0, fixture.month) == fixture.ownerDefaultBudget)
        assertTrue("Saving/viewing the selected budget must preserve the signed-in actor's default budget", fixture.actor.api.getMonthlyBudget(0, fixture.month) == fixture.actorDefaultBudget)
        assertTrue("Saving/viewing a budget must preserve owner default balances and records", financialSnapshot(fixture.owner.api, 0) == fixture.ownerDefaultSnapshot)
        assertTrue("Saving/viewing a budget must preserve actor default balances and records", financialSnapshot(fixture.actor.api, 0) == fixture.actorDefaultSnapshot)
        assertTrue("Saving/viewing a budget must preserve selected-ledger balances and records", financialSnapshot(fixture.owner.api, fixture.ledgerId) == fixture.sharedSnapshot)
        assertTrue("Online budget settings must not create pending transaction writes", fixture.repository.ledgerWrites().isEmpty())
    }

    private fun waitFor(text: String) {
        rule.waitUntil(20_000) { rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
}
