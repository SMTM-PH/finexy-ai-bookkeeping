package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TagManagementUiTest {
    @get:Rule val rule = createAndroidComposeRule<TagManagementQaActivity>()

    @Test fun addEditHideAndConfirmedDeleteAreWired() {
        rule.onNodeWithText("标签管理").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("标签管理：新增").performScrollTo().performClick()
        rule.onNodeWithText("标签名称").performTextInput("重要")
        rule.onNodeWithText("添加").performClick()
        assertEquals("重要", rule.activity.addedName)

        rule.onNodeWithContentDescription("编辑标签：报销").performScrollTo().performClick()
        rule.onNodeWithText("标签名称").performTextClearance()
        rule.onNodeWithText("标签名称").performTextInput("待报销")
        rule.onNodeWithText("保存").performClick()
        assertEquals("待报销", rule.activity.editedName)

        rule.onNodeWithContentDescription("停用标签：待报销").performScrollTo().performClick()
        assertEquals(10L, rule.activity.hiddenId)

        rule.onNodeWithContentDescription("删除标签：旅行").performScrollTo().performClick()
        rule.onNodeWithText("删除标签？").assertIsDisplayed()
        rule.onNodeWithText("确认删除").performClick()
        assertEquals(11L, rule.activity.deletedId)
    }
}
