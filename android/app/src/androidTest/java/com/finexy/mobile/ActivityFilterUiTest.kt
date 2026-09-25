package com.finexy.mobile

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class ActivityFilterUiTest {
    @get:Rule val rule = createAndroidComposeRule<ActivityFilterQaActivity>()
    private fun chip(text: String) = rule.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))

    @Test fun dateTypeAccountCategoryAndTagFiltersComposeTogether() {
        chip("近 30 天").performScrollTo().performClick()
        chip("支出").performScrollTo().performClick()
        chip("日常银行卡").performScrollTo().performClick()
        chip("餐饮").performScrollTo().performClick()
        chip("#工作").performScrollTo().performClick()
        rule.onNodeWithText("1 笔记录").assertIsDisplayed()
        rule.onNodeWithText("工作午餐").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("私人午餐").assertDoesNotExist()
        rule.onNodeWithText("历史工作午餐").assertDoesNotExist()
    }

    @Test fun globalLedgerSwitcherExposesFamilyReadOnlyState() {
        rule.onNodeWithContentDescription("切换到账本：温暖小家").performClick()
        rule.onNodeWithText("已切换账本").assertIsDisplayed()
        rule.onNodeWithText("可新增流水；既有流水编辑正在接入。").assertIsDisplayed()
        rule.onNodeWithText("工作午餐").performScrollTo().performClick()
        rule.onNodeWithText("删除这笔流水？").assertDoesNotExist()
        chip("个人账本").performClick()
        rule.onNodeWithText("每一笔，都清楚。").assertIsDisplayed()
    }
}
