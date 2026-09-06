package com.finexy.mobile

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConflictUiTest {
    @get:Rule val rule = createAndroidComposeRule<ConflictQaActivity>()

    @Test fun conflictDialogShowsEveryServerFieldAndAccessibleDecisions() {
        rule.onNodeWithText("同步冲突").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("查看同步冲突：本机午餐").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("解决同步冲突").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("13 个字段不同。", substring = true).assertExists()
        // Each conflict row announces "label，本机：…" — the suffix keeps the
        // matcher unique against settings sections behind the dialog.
        listOf("流水类型", "分类", "来源账户", "目标账户", "来源金额", "目标金额", "币种", "时间", "描述", "标签 ID", "附件 ID", "位置", "隐藏金额").forEach { label ->
            rule.onNodeWithContentDescription("$label，本机", substring = true).assertExists()
        }
        rule.onNodeWithContentDescription("时间，", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
                listOf("时间，本机：2026-09-03 15:53:20 · UTC+08:00，服务器：2026-09-03 04:53:20 · UTC-04:00，内容不同")))
        rule.onNodeWithText("保留服务器版本").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("conflict-ui" to true, rule.activity.resolved)
    }

    @Test fun syncFailureIsAnnouncedAndOffersRetry() {
        rule.onNodeWithText("同步失败：网络不可用；将在网络恢复后重试").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        rule.onNodeWithText("重试").assertIsEnabled().assertHeightIsAtLeast(48.dp)
    }
}
