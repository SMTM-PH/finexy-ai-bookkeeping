package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.finexy.mobile.data.RemoteLedgerMember
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NormalTemplateUiTest {
    @get:Rule val rule = createAndroidComposeRule<NormalTemplateQaActivity>()
    @Test fun viewerHasNoTemplateManagementOrApplicationActions() {
        rule.runOnUiThread { rule.activity.role = RemoteLedgerMember.ROLE_VIEWER }
        rule.onNodeWithText("当前成员只读").assertExists()
        rule.onNodeWithText("新增模板").assertDoesNotExist()
        rule.onNodeWithContentDescription("套用模板：退款模板").assertDoesNotExist()
        rule.onNodeWithContentDescription("编辑模板：退款模板").assertDoesNotExist()
    }
    @Test fun memberMustConfirmOwnerTagRemovalAndRefundRemainsANewDraft() {
        rule.runOnUiThread { rule.activity.role = RemoteLedgerMember.ROLE_MEMBER }
        rule.onNodeWithText("新增模板").assertDoesNotExist()
        rule.onNodeWithContentDescription("套用模板：退款模板").performScrollTo().performClick()
        rule.onNodeWithText("确认模板标签").assertExists()
        rule.onNodeWithText("取消").performClick()
        assertNull(rule.activity.seed)
        rule.onNodeWithContentDescription("套用模板：退款模板").performClick()
        rule.onNodeWithText("去除标签并继续").performClick()
        rule.onNodeWithText("保存支出").performScrollTo().assertIsEnabled().performClick()
        val saved = requireNotNull(rule.activity.saved)
        assertEquals(-1250L, saved.sourceAmountMinor); assertEquals(23L, saved.categoryId)
        assertEquals("[]", saved.tagIdsJson); assertEquals(9L, rule.activity.seed!!.ledgerId); assertEquals(true, saved.hideAmount)
        assertEquals(rule.activity.seed!!.localId, saved.localId)
    }
    @Test fun crossCurrencyTransferKeepsDualAmountsThroughTheEntryForm() {
        rule.onNodeWithContentDescription("套用模板：双金额模板").performScrollTo().performClick()
        rule.onNodeWithText("保存转账").performScrollTo().assertIsEnabled().performClick()
        val saved = requireNotNull(rule.activity.saved)
        assertEquals(12345L, saved.sourceAmountMinor); assertEquals(12200L, saved.destinationAmountMinor)
        assertEquals(10L, saved.sourceAccountId); assertEquals(11L, saved.destinationAccountId); assertEquals(31L, saved.categoryId)
    }
    @Test fun sameNameCategorySelectionUsesTheExplicitId() {
        rule.onNodeWithContentDescription("套用模板：退款模板").performScrollTo().performClick()
        rule.onNodeWithContentDescription("记账分类：其他 / 同名 · 23").assertIsSelected()
        rule.onNodeWithContentDescription("记账分类：日常 / 同名 · 21").performClick()
        rule.onNodeWithText("保存支出").performScrollTo().performClick()
        assertEquals(21L, rule.activity.saved!!.categoryId)
        assertEquals("[\"35\"]", rule.activity.saved!!.tagIdsJson)
    }
}
