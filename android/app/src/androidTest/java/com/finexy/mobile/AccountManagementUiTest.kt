package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class AccountManagementUiTest {
    @get:Rule val rule = createAndroidComposeRule<AccountManagementQaActivity>()

    @Test fun createEditRestoreDeleteAndHierarchyAreReachable() {
        rule.onNodeWithText("新增服务端账户").performClick()
        rule.onNodeWithText("账户名称").performTextInput("旅行钱包")
        rule.onNodeWithText("币种代码").performTextClearance()
        rule.onNodeWithText("币种代码").performTextInput("USD")
        rule.onNodeWithText("初始余额").performTextInput("12.34")
        rule.onAllNodesWithText("保存").onLast().performClick()
        assertNotNull(rule.activity.created)
        assertEquals(1234L, rule.activity.created?.balanceMinor)

        rule.onNodeWithContentDescription("查看日常账户账户详情").performScrollTo()
        rule.onAllNodesWithText("编辑").onFirst().performClick()
        rule.onNodeWithText("账户名称").performTextClearance()
        rule.onNodeWithText("账户名称").performTextInput("日常银行卡")
        rule.onAllNodesWithText("保存").onLast().performClick()
        assertEquals(Triple(10L, "日常银行卡", ""), rule.activity.modified)

        rule.onNodeWithText("↳ 美元子账户", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("恢复").performScrollTo().performClick()
        assertEquals(30L to false, rule.activity.hiddenChange)
        rule.onNodeWithText("删除", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("永久删除").performClick()
        assertEquals(30L, rule.activity.deletedId)
    }

    @Test fun multiAccountRequiresAndSubmitsChildFields() {
        rule.onNodeWithText("新增服务端账户").performClick()
        rule.onNodeWithText("账户名称").performTextInput("旅行资金")
        rule.onNodeWithText("多子账户").performClick()
        rule.onNodeWithText("子账户 1 名称").performTextInput("美元")
        rule.onNodeWithText("子账户 1 币种").performTextClearance()
        rule.onNodeWithText("子账户 1 币种").performTextInput("USD")
        rule.onNodeWithText("子账户 1 初始余额").performTextInput("20.50")
        rule.onNodeWithText("添加子账户").performScrollTo().performClick()
        rule.onNodeWithText("子账户 2 名称").performTextInput("欧元")
        rule.onNodeWithText("子账户 2 币种").performTextClearance()
        rule.onNodeWithText("子账户 2 币种").performTextInput("EUR")
        rule.onAllNodesWithText("保存").onLast().performClick()
        val draft = rule.activity.created
        assertEquals(2, draft?.type)
        assertEquals(2, draft?.subAccounts?.size)
        assertEquals(2050L, draft?.subAccounts?.first()?.balanceMinor)
    }

    @Test fun existingMultiAccountCanAddAndRemoveChildrenWithoutLosingIds() {
        rule.onNodeWithContentDescription("编辑多币种钱包").performScrollTo().performClick()
        rule.onNodeWithText("子账户 1 名称").assertTextContains("美元子账户")
        rule.onAllNodesWithText("删除此子账户").onFirst().performScrollTo().performClick()
        rule.onNodeWithText("添加子账户").performScrollTo().performClick()
        rule.onNodeWithText("子账户 2 名称").performTextInput("英镑子账户")
        rule.onNodeWithText("子账户 2 币种").performTextClearance()
        rule.onNodeWithText("子账户 2 币种").performTextInput("GBP")
        rule.onAllNodesWithText("保存").onLast().performClick()
        val children = rule.activity.modifiedDraft?.subAccounts.orEmpty()
        assertEquals(2, children.size)
        assertEquals(22L, children.first().id)
        assertEquals(0L, children.last().id)
        assertEquals("英镑子账户", children.last().name)
    }
}
