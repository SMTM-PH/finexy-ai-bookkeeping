package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EntryReliabilityUiTest {
    @get:Rule val rule = createAndroidComposeRule<EntryReliabilityQaActivity>()

    @Test fun emptyCategoriesDisableIncomeExpenseAndCanRecoverWithoutLosingAmount() {
        rule.onNodeWithText("此类型暂无可用末级分类", substring = true).assertExists()
        rule.onNodeWithContentDescription("记账金额").performTextInput("12.34")
        rule.onNodeWithText("保存支出").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("重新读取分类").performScrollTo().performClick()
        rule.onNodeWithContentDescription("记账金额").assertTextContains("12.34")
        rule.onNodeWithText("保存支出").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, rule.activity.saves)
    }

    @Test fun loadingAndFailureRemainVisibleAndRetryRestoresTheForm() {
        rule.runOnUiThread { rule.activity.loading = true }
        rule.onNodeWithText("正在读取账本分类…").assertExists()
        rule.onNodeWithText("重新读取分类").assertIsNotEnabled()
        rule.runOnUiThread { rule.activity.loading = false; rule.activity.categoryError = "分类读取失败，请重试" }
        rule.onNodeWithText("分类读取失败，请重试").assertExists()
        rule.onNodeWithText("重新读取分类").performClick()
        rule.onNodeWithText("分类读取失败，请重试").assertDoesNotExist()
        rule.onNodeWithContentDescription("记账分类：日常 / 餐饮 · 21").assertExists()
    }

    @Test fun balanceAdjustmentDoesNotNeedCategories() {
        rule.onNodeWithText("余额调整").performClick()
        rule.onNodeWithContentDescription("记账金额").performTextInput("12.34")
        rule.onNodeWithText("保存余额调整").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, rule.activity.saves)
    }

    @Test fun nonDefaultLedgerExplainsAccountAiScopeWithoutProvidingMisleadingEntry() {
        rule.onNodeWithText("此账本使用手动记账；账号 AI 待复核目前归默认个人账本。").assertExists()
        rule.onNodeWithText("AI 文本 / 票据识别 · 默认个人账本").assertDoesNotExist()
    }

    @Test fun unrepresentableTotalsExplainTheProblemAndPreserveValidCurrencies() {
        rule.runOnUiThread { rule.activity.showDashboard = true }
        rule.onNodeWithText("CNY 12.00").assertExists()
        rule.onNodeWithText("USD 无法汇总").assertExists()
        rule.onNodeWithText("部分合计超出支持范围，请查看单个账户或流水。").assertExists()
        rule.onNodeWithText("USD 0.00").assertDoesNotExist()
        rule.onNodeWithText("USD 2.00").performScrollTo().assertExists()
    }
}
