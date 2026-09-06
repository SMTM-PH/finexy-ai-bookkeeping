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
}
