package com.finexy.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class UserPreferencesUiTest {
    @get:Rule val rule = createAndroidComposeRule<UserPreferencesQaActivity>()

    @Test fun preferenceGroupsAccountCurrencyAndPrivacyBoundaryAreVisible() {
        rule.onNodeWithText("用户偏好").assertExists()
        rule.onNodeWithText("日常账户").assertExists()
        rule.onNodeWithText("跟随系统").performClick()
        rule.onNodeWithText("较大").performClick()
        rule.onNodeWithContentDescription("用户偏好列表").performScrollToNode(hasText("AI 与隐私"))
        rule.onNodeWithText("两项授权只保存在当前设备和账本范围内，不会上传到云设置。").assertExists()
        rule.onNodeWithContentDescription("用户偏好列表").performScrollToNode(hasText("云端偏好"))
        rule.onNodeWithText("PIN、token、生物识别状态、主题、字号及 AI 授权永不上传。").assertExists()
        rule.onNodeWithContentDescription("保存偏好").performScrollTo().assertHasClickAction()
    }

    @Test fun accountAndCurrencyDialogsCanUpdateSelections() {
        rule.onAllNodesWithText("更改")[0].performClick()
        rule.onNodeWithText("旅行账户 · USD").performClick()
        rule.onNodeWithText("旅行账户", substring = true).assertExists()
        rule.onAllNodesWithText("更改")[1].performClick()
        rule.onNodeWithText("USD").performClick()
        rule.onNodeWithText("USD").assertExists()
    }

    @Test fun holdPreferencesForVisualCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("finexy.visual.capture") == "true")
        if (InstrumentationRegistry.getArguments().getString("finexy.visual.theme") == "dark") {
            rule.runOnUiThread { rule.activity.lightMode = false }
            rule.waitForIdle()
        }
        Thread.sleep(25_000)
    }
}
