package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import com.finexy.mobile.data.AccountEntity
import com.finexy.mobile.data.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountDetailsUiTest {
    @get:Rule val rule = createAndroidComposeRule<AccountDetailsQaActivity>()

    @Test fun accountDetailFiltersItsOwnTransactionsAndPrefillsNewEntry() {
        rule.onNodeWithContentDescription("查看日常银行卡账户详情").assertIsDisplayed()
        rule.onNodeWithText("已停用账户").assertDoesNotExist()
        rule.onNodeWithContentDescription("查看日常银行卡账户详情").performClick()
        rule.onNodeWithText("¥ 1,234.56").assertIsDisplayed()
        rule.onNodeWithText("默认账户 · 新流水会优先使用此账户").assertIsDisplayed()
        rule.onNodeWithText("今天午餐").assertIsDisplayed()
        rule.onNodeWithText("八天前交通").assertIsDisplayed()
        rule.onNodeWithText("四十天前购物").assertDoesNotExist()
        rule.onNodeWithText("其他账户流水").assertDoesNotExist()
        rule.onNodeWithText("全部").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("四十天前购物").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("其他账户流水").assertDoesNotExist()
        rule.onNodeWithText("在此账户记一笔").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(10L, rule.activity.entryAccountId)
    }

    @Test fun anotherAccountCanBecomeDefaultAndInvalidDefaultsFallBackLocally() {
        rule.onNodeWithContentDescription("查看本地钱包账户详情").performClick()
        rule.onNodeWithText("设为默认账户").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(TransactionEntity.LOCAL_ACCOUNT_ID, rule.activity.defaultAccountId.longValue)
        val accounts = listOf(AccountEntity(10, "可用", "CNY"), AccountEntity(12, "停用", "CNY", hidden = true))
        assertEquals(10L, validDefaultAccountId(10, accounts))
        assertEquals(TransactionEntity.LOCAL_ACCOUNT_ID, validDefaultAccountId(12, accounts))
        assertEquals(TransactionEntity.LOCAL_ACCOUNT_ID, validDefaultAccountId(999, accounts))
    }

    @Test fun statisticsFiltersAndExportConfirmationShareTheSameRows() {
        fun chip(text: String) = rule.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        rule.onNodeWithText("支出统计").performScrollTo()
        chip("收入").performScrollTo().performClick()
        chip("旅行现金").performScrollTo().performClick()
        chip("工资").performScrollTo().performClick()
        rule.onNodeWithText("导出 CSV").performScrollTo().performClick()
        rule.onAllNodesWithText("确认导出").assertCountEquals(2)
        rule.onNodeWithText("将按当前筛选导出 1 条流水。收入 ¥ 88.00，支出 ¥ 0.00。").assertIsDisplayed()
    }
}
