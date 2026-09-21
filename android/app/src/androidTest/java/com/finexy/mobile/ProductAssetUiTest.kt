package com.finexy.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ProductAssetUiTest {
    @get:Rule val rule = createAndroidComposeRule<ProductAssetQaActivity>()

    private fun waitForFixture() {
        rule.waitUntil(5_000) { rule.onAllNodesWithText("工作电脑").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun listSummaryFiltersAndDangerousActionsAreReachable() {
        waitForFixture()
        rule.onNodeWithText("1 件资产").assertExists()
        rule.onAllNodesWithText("¥ 7,000.00").assertCountEquals(2)
        rule.onNodeWithText("已售出").performClick()
        rule.onNodeWithText("旧手机").assertExists()
        rule.onNodeWithText("持有中").performClick()

        rule.onNodeWithContentDescription("编辑资产工作电脑").performScrollTo().performClick()
        rule.onNodeWithText("编辑资产").assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithContentDescription("登记售出工作电脑").performScrollTo().performClick()
        rule.onNodeWithText("登记后资产变为已售出", substring = true).assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithContentDescription("删除资产工作电脑").performScrollTo().performClick()
        rule.onNodeWithText("不会删除关联流水", substring = true).assertExists()
    }

    @Test fun amountAndCategoryHelpersAreExact() {
        assertEquals(123456L, amountMinor("1234.56"))
        assertEquals(1L, amountMinor("0.01"))
        assertNull(amountMinor("1.001"))
        assertEquals("电脑", assetCategoryName(3))
        assertEquals("¥ 1,234.56", assetMoney(123456))
    }

    /** Opt-in visual fixture; normal regression skips it immediately. */
    @Test fun holdProductAssetPageForVisualCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("finexy.visual.capture") == "true")
        if (InstrumentationRegistry.getArguments().getString("finexy.visual.theme") == "dark") {
            rule.activityRule.scenario.onActivity { it.lightMode = false }
        }
        waitForFixture()
        Thread.sleep(25_000)
    }
}
