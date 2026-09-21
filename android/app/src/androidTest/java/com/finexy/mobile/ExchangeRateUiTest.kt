package com.finexy.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ExchangeRateUiTest {
    @get:Rule val rule = createAndroidComposeRule<ExchangeRateQaActivity>()

    private fun waitForFixture() = rule.waitUntil(5_000) { rule.onAllNodesWithText("0.13876543", substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun sourceDirectionConverterAndCustomActionsAreVisible() {
        waitForFixture()
        rule.onNodeWithText("用户自定义").assertExists()
        rule.onNodeWithText("1 CNY = 0.13876543 USD").assertExists()
        rule.onNodeWithText("添加自定义汇率").performScrollTo().performClick()
        rule.onAllNodesWithText("添加自定义汇率").assertCountEquals(2)
        rule.onNodeWithText("取消").performClick()
        rule.onAllNodesWithText("编辑")[0].performScrollTo().performClick()
        rule.onNodeWithText("编辑 EUR 汇率").assertExists()
    }

    /** Opt-in visual fixture; normal regression skips it immediately. */
    @Test fun holdExchangeRatePageForVisualCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("finexy.visual.capture") == "true")
        if (InstrumentationRegistry.getArguments().getString("finexy.visual.theme") == "dark") {
            rule.activityRule.scenario.onActivity { it.lightMode = false }
        }
        waitForFixture(); Thread.sleep(25_000)
    }
}
