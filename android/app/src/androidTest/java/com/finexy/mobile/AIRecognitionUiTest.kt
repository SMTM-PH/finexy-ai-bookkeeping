package com.finexy.mobile

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class AIRecognitionUiTest {
    @get:Rule val rule = createAndroidComposeRule<AIRecognitionQaActivity>()

    @Test fun textAndReceiptActionsExplainReviewAndPrivacy() {
        rule.onNodeWithText("AI 与票据识别").assertExists()
        rule.onNodeWithText("生成待复核草稿").assertIsNotEnabled()
        rule.onNodeWithText("例如：今天午餐 36 元，微信支付").performTextInput("午餐 36 元")
        rule.onNodeWithText("生成待复核草稿").assertIsEnabled()
        rule.onNodeWithText("图片将直接发送到服务器配置的视觉大模型", substring = true).assertExists()
        rule.onNodeWithText("从相册选择票据").assertExists()
        rule.onNodeWithText("拍照识别").assertExists()
        rule.onNodeWithText("查看待复核队列").assertExists()
    }

    @Test fun receiptEditorRequiresExplicitConfirmationAndExposesControls() {
        rule.runOnUiThread { rule.activity.showImageEditor = true }
        rule.onNodeWithText("裁剪票据").assertExists()
        val cropRegion = rule.onNodeWithContentDescription("裁剪区域", substring = true)
        cropRegion.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val actions = cropRegion.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.any { it.label == "缩小裁剪范围" })
        assertTrue(actions.any { it.label == "向右移动裁剪框" })
        rule.onNodeWithText("向右旋转 90°").performClick()
        rule.onNodeWithText("重置裁剪").performClick()
        rule.onNodeWithText("用于识别").performClick()
        rule.runOnIdle { assertEquals(1, rule.activity.submittedCount) }
        rule.onNodeWithText("裁剪票据").assertDoesNotExist()
    }

    @Test fun ocrTextMustBeVisibleBeforeExplicitCloudSubmission() {
        rule.runOnUiThread { rule.activity.showOCRPreview = true }
        rule.onNodeWithText("核对 OCR 文字").assertExists()
        rule.onNodeWithText("FINEXY TEST RECEIPT", substring = true).assertExists()
        rule.onNodeWithText("识别置信度 96%", substring = true).assertExists()
        rule.onNodeWithText("发送并结构化").performClick()
        rule.runOnIdle { assertEquals(1, rule.activity.submittedCount) }
        rule.onNodeWithText("核对 OCR 文字").assertDoesNotExist()
    }

    @Test fun holdRecognitionForVisualCapture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("finexy.visual.capture") == "true")
        rule.runOnUiThread {
            rule.activity.lightMode = InstrumentationRegistry.getArguments().getString("finexy.visual.theme") != "dark"
            rule.activity.showOCRPreview = InstrumentationRegistry.getArguments().getString("finexy.visual.preview") == "true"
            rule.activity.showImageEditor = InstrumentationRegistry.getArguments().getString("finexy.visual.editor") == "true"
        }
        rule.waitForIdle()
        Thread.sleep(120_000)
    }
}
