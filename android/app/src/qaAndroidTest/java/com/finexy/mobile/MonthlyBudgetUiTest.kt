package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.finexy.mobile.data.monthlyBudgetQaUrl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Production page/Theme/Scaffold inside an independent, non-exported QA host. */
class MonthlyBudgetUiTest {
    @get:Rule val rule = createAndroidComposeRule<MonthlyBudgetQaActivity>()
    @Before fun requireIsolatedBudgetEnvironment() { monthlyBudgetQaUrl() }
    private fun release() { rule.runOnUiThread { rule.activity.fixture.initialRead.complete(Unit) } }
    private fun waitFor(text: String) { rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) = rule.onNodeWithText(text).performScrollTo().performClick()

    @Test fun loadingAndRoleReadOnlyDoNotOfferManagement() {
        rule.onNodeWithText("正在加载当前账本预算…").assertExists()
        rule.onNodeWithText("本月尚未设置预算。").assertDoesNotExist()
        rule.runOnUiThread { rule.activity.fixture.role = 4 }
        release(); waitFor("本月总预算")
        rule.onNodeWithText("我的角色：只读成员，仅查看预算").assertExists()
        rule.onNodeWithText("编辑本月预算").assertDoesNotExist()
        rule.onNodeWithText("删除本月预算").assertDoesNotExist()
        rule.onNodeWithText("保存本月预算").assertDoesNotExist()
    }

    @Test fun failedReadIsVisibleAndRetryRecoversInsteadOfShowingUnset() {
        rule.runOnUiThread { rule.activity.fixture.failingReads = 1 }
        release(); waitFor("预算加载失败，请检查网络、登录状态或账本权限后重试。")
        rule.onNodeWithText("本月尚未设置预算。").assertDoesNotExist()
        click("重试读取"); waitFor("本月总预算")
        rule.onNodeWithText("预算加载失败，请检查网络、登录状态或账本权限后重试。").assertDoesNotExist()
    }

    @Test fun ownerSaveAndClearStayInCapturedLedger() {
        release(); waitFor("本月总预算")
        click("编辑本月预算")
        rule.onNodeWithText("月度总预算金额").performScrollTo().performTextReplacement("200.00")
        click("保存本月预算"); waitFor("预算已保存")
        assertEquals(1, rule.activity.fixture.successfulSaves)
        assertEquals(9L, rule.activity.fixture.lastWriteLedger)
        assertNotNull(rule.activity.fixture.budgets[10])
        click("删除本月预算")
        rule.onNodeWithText("删除预算").performClick(); waitFor("本月尚未设置预算。")
        assertEquals(1, rule.activity.fixture.successfulDeletes)
        assertEquals(9L, rule.activity.fixture.lastWriteLedger)
        assertNotNull(rule.activity.fixture.budgets[10])
    }

    @Test fun staleRevisionKeepsInputAndRequiresExplicitBaselineAdoption() {
        release(); waitFor("本月总预算")
        click("编辑本月预算")
        rule.onNodeWithText("月度总预算金额").performScrollTo().performTextReplacement("200.00")
        rule.runOnUiThread { rule.activity.fixture.budgets[9] = rule.activity.fixture.budgets[9]!!.copy(amountMinor = 30000, revision = 2) }
        click("保存本月预算")
        waitFor("服务器预算已变化，请刷新核对并明确采用当前版本后再保存；当前输入已保留。")
        rule.onNodeWithText("月度总预算金额").assertTextContains("200.00")
        rule.onNodeWithText("保存本月预算").assertIsNotEnabled()
        captureQaUiEvidence(rule, "budget-conflict-input-preserved")
        click("刷新预算")
        waitFor("采用服务器当前版本，保留我的输入")
        rule.onNodeWithText("月度总预算金额").assertTextContains("200.00")
        rule.onNodeWithText("保存本月预算").assertIsNotEnabled()
        click("采用服务器当前版本，保留我的输入")
        click("保存本月预算"); waitFor("预算已保存")
        assertEquals(listOf(1L, 2L), rule.activity.fixture.attemptedRevisions)
        assertEquals(1, rule.activity.fixture.successfulSaves)
    }

    @Test fun narrowLargeFontAndHiddenAmountsKeepControlsReachableWithoutSemanticsLeak() {
        release(); waitFor("本月总预算")
        rule.runOnUiThread { rule.activity.largeText = true; rule.activity.lightTheme = false }
        click("编辑本月预算")
        rule.onNodeWithText("月度总预算金额").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("保存本月预算").performScrollTo().assertIsDisplayed()
        captureQaUiEvidence(rule, "budget-dark-narrow-large-font")
        click("隐藏预算金额")
        rule.onNodeWithText("CNY 100.00").assertDoesNotExist()
        rule.onAllNodesWithText("100.00", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("0.0%", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText("月度总预算金额").assertDoesNotExist()
        rule.onNodeWithText("保存本月预算").assertDoesNotExist()
        rule.onNodeWithText("预算金额已隐藏").assertExists()
        captureQaUiEvidence(rule, "budget-hidden-amounts")
        click("显示预算金额")
        rule.onNodeWithText("月度总预算金额").performScrollTo().assertTextContains("100.00")
        click("返回设置")
        rule.onNodeWithText("放弃输入并离开").assertExists()
        rule.onNodeWithText("继续编辑").performClick()
        rule.onNodeWithText("保存本月预算").performScrollTo().assertIsDisplayed()
    }

    @Test fun lateLedgerAResponseCannotReplaceLedgerBAndSwitchBackIsIsolated() {
        rule.onNodeWithText("正在加载当前账本预算…").assertExists()
        rule.runOnUiThread { rule.activity.selectedLedgerId = 10 }
        waitFor("本月总预算")
        rule.onNodeWithText("USD 50.00").assertExists()
        release(); rule.waitForIdle()
        rule.onNodeWithText("CNY 100.00").assertDoesNotExist()
        rule.onNodeWithText("USD 50.00").assertExists()
        rule.runOnUiThread { rule.activity.selectedLedgerId = 9 }
        waitFor("CNY 100.00")
        rule.onNodeWithText("USD 50.00").assertDoesNotExist()
    }
}
