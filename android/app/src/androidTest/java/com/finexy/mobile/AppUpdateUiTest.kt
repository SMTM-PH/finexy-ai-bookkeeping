package com.finexy.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry

class AppUpdateUiTest {
    @get:Rule val rule = createAndroidComposeRule<AppUpdateQaActivity>()

    @Test fun updateCardShowsVersionAndOpensReleaseAction() {
        rule.onNodeWithContentDescription("发现 Finexy 新版本 1.0.2").assertExists()
        rule.onNodeWithText("当前版本 1.0.1", substring = true).assertExists()
        rule.onNodeWithText("查看更新").performScrollTo().performClick()
        rule.onNodeWithText("已打开发布页面").assertExists()
    }

    @Test fun dismissRemovesOnlyTheVisibleUpdateCard() {
        rule.onNodeWithText("忽略此版本").performScrollTo().performClick()
        rule.onNodeWithContentDescription("发现 Finexy 新版本 1.0.2").assertDoesNotExist()
        rule.onNodeWithText("总余额").assertExists()
    }

    @Test fun holdUpdateCardForVisualCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("finexy.visual.capture") == "true")
        rule.activityRule.scenario.onActivity { activity ->
            activity.lightMode = InstrumentationRegistry.getArguments().getString("finexy.visual.theme") != "dark"
        }
        rule.waitForIdle()
        Thread.sleep(18_000)
    }
}
