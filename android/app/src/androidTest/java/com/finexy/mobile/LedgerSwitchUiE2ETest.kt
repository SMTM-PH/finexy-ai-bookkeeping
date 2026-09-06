package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.finexy.mobile.data.AccountSecurity
import com.finexy.mobile.data.FinexyApi
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.TransactionDraft
import com.finexy.mobile.data.TransactionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class LedgerSwitchUiE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val registrationStores = mutableListOf<String>()

    @After fun cleanUp() {
        rule.activityRule.scenario.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        registrationStores.forEach(context::deleteSharedPreferences)
    }

    @Test fun switchingRealUsersChangesTheVisibleRoomLedgerAndSwitchingBackRestoresIt() {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Requires an isolated disposable Docker server", !url.isNullOrBlank())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val password = "Switch-$suffix!"
        val userA = "switcha$suffix"
        val userB = "switchb$suffix"
        register(url!!, userA, password)
        register(url, userB, password)

        rule.onNodeWithText("已有服务器？连接账本").performClick()
        rule.onNodeWithText("服务器地址").performTextInput(url)
        rule.onNodeWithText("连接并登录").performClick()
        login(userA, password)
        seedCurrentLedger("A 用户专属流水")
        rule.waitUntil(20_000) { rule.onAllNodesWithText("A 用户专属流水").fetchSemanticsNodes().isNotEmpty() }

        logoutLocally()
        login(userB, password)
        rule.onNodeWithText("A 用户专属流水").assertDoesNotExist()
        seedCurrentLedger("B 用户专属流水")
        rule.waitUntil(20_000) { rule.onAllNodesWithText("B 用户专属流水").fetchSemanticsNodes().isNotEmpty() }

        logoutLocally()
        login(userA, password)
        rule.onNodeWithText("A 用户专属流水").assertIsDisplayed()
        rule.onNodeWithText("B 用户专属流水").assertDoesNotExist()
    }

    private fun register(url: String, username: String, password: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "ledger-switch-register-${UUID.randomUUID()}".also(registrationStores::add)
        val store = SecureStore(context, name)
        store.put(FinexyApi.KEY_SERVER_URL, url)
        AccountSecurity(FinexyApi(store)).register(username, "$username@example.com", username, password, "CNY")
    }

    private fun login(username: String, password: String) {
        rule.onNodeWithText("用户名或邮箱").performTextInput(username)
        rule.onNodeWithText("密码").performTextInput(password)
        rule.onNodeWithText("登录", substring = false).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("总余额").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun seedCurrentLedger(comment: String) = runBlocking {
        val activity = rule.activity
        val name = activity.currentDatabaseName()
        val repository = TransactionRepository(activity, FinexyDatabase.get(activity, name), importLegacy = false)
        repository.save(TransactionDraft(UUID.randomUUID().toString(), TransactionRepository.TYPE_EXPENSE,
            -1, null, "其他", 1234, comment, "[]"))
    }

    private fun logoutLocally() {
        rule.onNodeWithContentDescription("打开设置").performClick()
        rule.onNodeWithText("用户与安全").performScrollTo().performClick()
        rule.onNodeWithText("退出登录").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("仅退出本机").performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("登录 Finexy").fetchSemanticsNodes().isNotEmpty() }
    }
}
