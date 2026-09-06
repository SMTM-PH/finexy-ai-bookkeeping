package com.finexy.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScheduleUiTest {
    @get:Rule val rule = createAndroidComposeRule<ScheduleQaActivity>()

    private fun usePlanPage() {
        rule.activityRule.scenario.onActivity { activity -> activity.reviewMode = false }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("到期后进入待确认队列", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
        rule.onNodeWithText("房租计划", substring = true).assertExists()
    }

    @Test fun reviewPageShowsPendingAndDismissedQueuesWithDialogs() {
        rule.onNodeWithText("待确认入账").performScrollTo()
        rule.onNodeWithContentDescription("确认入账：房租", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("确认后将立即创建一笔流水并更新余额", substring = true).assertExists()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithContentDescription("忽略：房租", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("忽略后本次执行不会入账", substring = true).assertExists()
        rule.onNodeWithText("取消").performClick()
    }

    @Test fun restoreActionReportsResultInQueuePage() {
        // The QA host has no server URL, so the restore call fails deterministically
        // and the failure surfaces through the accessible status line.
        rule.onNodeWithContentDescription("恢复待确认：订阅", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText("恢复失败", substring = true).performScrollTo()
    }

    @Test fun planPageListsSchedulesAndEditorDialogIsReachable() {
        usePlanPage()
        rule.onNodeWithText("周期计划").performScrollTo()
        rule.onNodeWithContentDescription("编辑计划：房租计划", useUnmergedTree = true).performClick()
        rule.onNodeWithText("计划名称").performTextInput("改名的计划")
        rule.onNodeWithText("预览", useUnmergedTree = true).performClick()
        rule.onNodeWithText("确认计划内容").assertExists()
        rule.onNodeWithText("返回修改").performClick()
        rule.onNodeWithText("取消", useUnmergedTree = true).performClick()
        rule.onNodeWithContentDescription("删除计划：房租计划", useUnmergedTree = true).performClick()
        rule.onNodeWithText("将永久删除", substring = true).assertExists()
        rule.onNodeWithText("取消").performClick()
    }

    @Test fun pauseAndMoveControlsReflectPlanState() {
        usePlanPage()
        rule.onNodeWithContentDescription("暂停或恢复计划：停用订阅", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("恢复").assertExists()
        // SQLite orders by name here: 停用订阅 comes before 房租计划, so only the first row's 上移 is disabled.
        rule.onNodeWithContentDescription("上移计划：停用订阅", useUnmergedTree = true).assertIsNotEnabled()
        rule.onNodeWithContentDescription("上移计划：房租计划", useUnmergedTree = true).assertIsEnabled()
    }

    @Test fun scheduleLabelsCoverFrequencyAndOffsetContracts() {
        assertEquals("已暂停", scheduleFrequencyLabel(0, ""))
        assertEquals("每日", scheduleFrequencyLabel(3, ""))
        assertEquals("每周：周一、周五", scheduleFrequencyLabel(1, "1,5"))
        assertEquals("每月：1日、月末第2天", scheduleFrequencyLabel(2, "1,-2"))
        assertEquals("每年：2月29日", scheduleFrequencyLabel(4, "229"))
        assertEquals("每 3 天（自开始日期）", scheduleFrequencyLabel(5, "3"))
        assertEquals("未设置", scheduleFrequencyLabel(null, null))
        assertEquals("+08:00", scheduleOffsetText(480))
        assertEquals("-05:30", scheduleOffsetText(-330))
        assertEquals("UTC+08:00", scheduleOffsetLabel(480))
        assertEquals("UTC-05:30", scheduleOffsetLabel(-330))
        assertEquals(480, parseOffsetText("+08:00"))
        assertEquals(-330, parseOffsetText("-05:30"))
        assertNull(parseOffsetText("8:00"))
        assertNull(parseOffsetText("+08:10"))
        assertNull(parseOffsetText("+15:00"))
        assertTrue(occurrenceAmount(
            com.finexy.mobile.data.ScheduledOccurrenceEntity(1, 1, 2, type = 3, sourceAmountMinor = 420000)
        ).startsWith("-¥ 4200"))
        assertTrue(occurrenceAmount(
            com.finexy.mobile.data.ScheduledOccurrenceEntity(1, 1, 1, type = 3, sourceAmountMinor = 420000, hideAmount = true)
        ).contains("···"))
    }
}
