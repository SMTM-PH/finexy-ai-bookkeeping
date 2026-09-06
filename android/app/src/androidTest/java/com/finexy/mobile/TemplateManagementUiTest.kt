package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.espresso.Espresso.closeSoftKeyboard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TemplateManagementUiTest {
    @get:Rule val rule = createAndroidComposeRule<TemplateManagementQaActivity>()

    @Test fun newTemplateRequiresExplicitFieldsAndPreviewBeforeSave() {
        rule.onNodeWithContentDescription("流水模板：新增").performScrollTo().performClick()
        rule.onNodeWithText("模板名称").performTextInput("工资入账")
        // The settings page behind the dialog also renders category rows; the
        // type segment is the only clickable "收入" node.
        rule.onAllNodesWithText("收入").filterToOne(hasClickAction()).performClick()
        rule.onNodeWithText("金额").performTextInput("8888.88")
        rule.onNodeWithText("日常银行卡").performScrollTo().performClick()
        rule.onNode(hasText("工资") and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText("备注（可选）").performScrollTo().performTextInput("九月工资")
        closeSoftKeyboard()
        rule.onNodeWithContentDescription("切换标签：重要").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNodeWithText("预览").performClick()
        rule.waitUntil(5_000) { rule.onNodeWithText("确认模板内容").isDisplayed() }
        rule.onNodeWithText("确认模板内容").assertIsDisplayed()
        rule.onNodeWithText("收入 · ¥ 8,888.88").assertIsDisplayed()
        assertNull(rule.activity.added)
        rule.onNodeWithText("确认保存").performClick()
        val added = rule.activity.added!!
        assertEquals(888888L, added.sourceAmountMinor)
        assertEquals(201L, added.categoryId)
        assertEquals(10L, added.sourceAccountId)
        assertEquals("[\"31\"]", added.tagIdsJson)
    }

    @Test fun editingPreservesAllFieldsAndCanReturnFromPreview() {
        rule.onNodeWithContentDescription("编辑模板：每日早餐").performScrollTo().performClick()
        rule.onNodeWithText("预览").performClick()
        rule.onNodeWithText("返回修改").performClick()
        rule.onNodeWithText("模板名称").assertTextContains("每日早餐")
        rule.onNodeWithText("预览").performClick()
        rule.onNodeWithText("确认保存").performClick()
        val edited = rule.activity.edited!!
        assertEquals(20L, edited.id)
        assertEquals(1250L, edited.sourceAmountMinor)
        assertEquals(101L, edited.categoryId)
        assertEquals("[\"30\"]", edited.tagIdsJson)
    }

    @Test fun deleteRequiresConfirmationAndFailedActionCanRetry() {
        rule.onNodeWithContentDescription("删除模板：每日早餐").performScrollTo().performClick()
        rule.onNodeWithText("删除流水模板？").assertIsDisplayed()
        assertNull(rule.activity.deletedId)
        rule.onNodeWithText("取消").performClick()
        assertNull(rule.activity.deletedId)
        rule.runOnUiThread {
            rule.activity.actionMessage.value = "模板保存失败：网络不可用"
            rule.activity.retryAvailable.value = true
        }
        rule.onNodeWithText("重试上次操作").performScrollTo().performClick()
        assertEquals(1, rule.activity.retryCount)
        rule.onNodeWithText("模板已保存").assertExists()
    }
}
