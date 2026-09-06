package com.finexy.mobile

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AccountMappingUiTest {
    @get:Rule val rule = createAndroidComposeRule<AccountMappingQaActivity>()

    @Test fun mappingRequiresExplicitAccountChoiceAndShowsBulkImpact() {
        rule.onNodeWithText("4 条待同步流水需要选择账户").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("选择账户").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("将把 4 条尚未上传", substring = true).assertIsDisplayed()
        rule.onNodeWithText("日常银行卡 · CNY").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(10L, rule.activity.selectedServerId)
        rule.onNodeWithText("已绑定：日常银行卡 · CNY").assertIsDisplayed()
        rule.onNodeWithText("清除").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(rule.activity.cleared)
    }

    @Test fun hiddenAccountIsNotOffered() {
        rule.onNodeWithText("选择账户").performScrollTo().performClick()
        rule.onNodeWithText("旅行现金 · USD").assertIsDisplayed()
        rule.onNodeWithText("隐藏账户 · CNY").assertIsNotDisplayed()
    }

    /** Opt-in visual fixture; normal regression skips it immediately. */
    @Test fun holdAccountMappingPageForVisualCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("finexy.visual.capture") == "true")
        rule.onNodeWithText("4 条待同步流水需要选择账户").performScrollTo().assertIsDisplayed()
        Thread.sleep(15_000)
    }
}
