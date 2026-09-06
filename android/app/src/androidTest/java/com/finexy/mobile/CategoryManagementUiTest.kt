package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CategoryManagementUiTest {
    @get:Rule val rule = createAndroidComposeRule<CategoryManagementQaActivity>()

    @Test fun createSecondaryCategoryAndEditExistingCategory() {
        rule.onNodeWithText("新增").performClick()
        rule.onNodeWithText("分类名称").performTextInput("旅行")
        rule.onAllNodesWithText("日常支出").filterToOne(hasClickAction()).performClick()
        rule.onNodeWithText("保存").performClick()
        assertEquals(10L, rule.activity.created?.parentId)
        assertEquals(2, rule.activity.created?.type)

        rule.onNodeWithContentDescription("编辑分类餐饮").performScrollTo().performClick()
        rule.onNodeWithText("分类名称").performTextClearance()
        rule.onNodeWithText("分类名称").performTextInput("外出就餐")
        rule.onNodeWithText("保存").performClick()
        assertEquals(11L, rule.activity.modified?.first)
        assertEquals("外出就餐", rule.activity.modified?.second?.name)
    }

    @Test fun reorderRestoreAndDeleteConfirmationAreReachable() {
        rule.onNodeWithContentDescription("下移餐饮").performScrollTo().performClick()
        assertEquals(listOf(12L, 11L), rule.activity.movedIds)
        rule.onNodeWithText("恢复").performScrollTo().performClick()
        assertEquals(30L to false, rule.activity.hiddenChange)
        rule.onNodeWithText("删除", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("永久删除").performClick()
        assertEquals(30L, rule.activity.deletedId)
    }
}
