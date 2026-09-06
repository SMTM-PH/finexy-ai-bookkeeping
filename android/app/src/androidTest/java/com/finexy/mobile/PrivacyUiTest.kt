package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PrivacyUiTest {
    @get:Rule val rule = createAndroidComposeRule<PrivacyQaActivity>()
    private var preferencesName: String? = null
    @After fun cleanUp() {
        val name = preferencesName
        rule.activity.createdDocuments.forEach { assertTrue(android.provider.DocumentsContract.deleteDocument(rule.activity.contentResolver, it)) }
        rule.activityRule.scenario.close()
        if (name != null) InstrumentationRegistry.getInstrumentation().targetContext.deleteSharedPreferences(name)
    }

    @Test fun enablingPinLocksOnBackgroundAndRecreationAndCanBeDisabled() {
        preferencesName = rule.activity.preferencesName
        rule.onNodeWithText("新 PIN").performTextInput("638195")
        rule.onNodeWithText("再次输入 PIN").performTextInput("638195")
        rule.onNodeWithText("启用应用锁").performScrollTo().performClick()
        rule.waitUntil(20_000) { com.finexy.mobile.data.AppLock(rule.activity.store).enabled }
        rule.waitForIdle()
        assertTrue(rule.activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("解锁私人账本").assertIsDisplayed()
        rule.onNodeWithText("应用 PIN").performTextInput("111111")
        rule.onNodeWithText("解锁", substring = false).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("PIN 不正确").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("应用 PIN").performTextInput("638195")
        rule.onNodeWithText("解锁", substring = false).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("解锁私人账本").fetchSemanticsNodes().isEmpty() }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText("解锁私人账本").assertIsDisplayed()
        rule.onNodeWithText("应用 PIN").performTextInput("638195")
        rule.onNodeWithText("解锁", substring = false).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("解锁私人账本").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("当前 PIN").performTextInput("638195")
        rule.onNodeWithText("验证 PIN 并关闭应用锁").performScrollTo().performClick()
        rule.waitUntil(20_000) { !com.finexy.mobile.data.AppLock(rule.activity.store).enabled }
    }

    @Test fun destructiveClearRequiresExactConfirmationAndCanCancel() {
        preferencesName = rule.activity.preferencesName
        rule.onNodeWithText("清空当前本地账本", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("确认清空").assertIsNotEnabled()
        rule.onNodeWithText("确认文字").performTextInput("清空")
        rule.onNodeWithText("确认清空").assertIsNotEnabled()
        rule.onNodeWithText("确认文字").performTextReplacement("清空当前账本")
        rule.onNodeWithText("确认清空").assertIsEnabled()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("确认清空").assertDoesNotExist()
    }

    @Test fun securityFieldsExposeUniqueVisibilityActionsAndHeadings() {
        preferencesName = rule.activity.preferencesName
        rule.onNodeWithText("隐私与数据管理").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        )
        rule.onNodeWithText("应用锁").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        )
        rule.onNodeWithContentDescription("显示新 PIN").assertHasClickAction().performClick()
        rule.onNodeWithContentDescription("隐藏新 PIN").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "内容已显示")
        )
        rule.onNodeWithContentDescription("显示再次输入 PIN").assertHasClickAction()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithContentDescription("显示新 PIN").assertExists()
    }

    @Test fun failedUnlockIsMarkedAsAnAnnouncedError() {
        preferencesName = rule.activity.preferencesName
        com.finexy.mobile.data.AppLock(rule.activity.store).enable("638195")
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("应用 PIN").performTextInput("111111")
        rule.onNodeWithText("解锁", substring = false).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("PIN 不正确").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("PIN 不正确").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite)
        ).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test fun pendingConfirmationCannotCoverLockAfterBackground() {
        preferencesName = rule.activity.preferencesName
        // Enable on the isolated store to focus this test on dialog/lifecycle ordering.
        com.finexy.mobile.data.AppLock(rule.activity.store).enable("638195")
        rule.onNodeWithText("清空当前本地账本", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("确认清空").assertExists()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("解锁私人账本").assertIsDisplayed()
        rule.onNodeWithText("确认清空").assertDoesNotExist()
        rule.onNodeWithText("应用 PIN").performTextInput("638195")
        rule.onNodeWithText("解锁", substring = false).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText("解锁私人账本").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("确认清空").assertExists()
        rule.onNodeWithText("取消").performClick()
    }

    @Test fun systemFilePickerWritesAnEncryptedBackupAndImportCanBeConfirmed() {
        preferencesName = rule.activity.preferencesName
        val pass = "Test-backup-passphrase-123"
        rule.onNodeWithText("备份密码（12–128 个字符）").performScrollTo().performTextInput(pass)
        rule.onNodeWithText("再次输入密码（导出时必填）").performScrollTo().performTextInput(pass)
        rule.onNodeWithText("导出加密备份").performScrollTo().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        rule.waitUntil(30_000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        fun findNode(node: android.view.accessibility.AccessibilityNodeInfo?, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) findNode(node.getChild(i), predicate)?.let { return it }
            return null
        }
        val save = findNode(automation.rootInActiveWindow) { it.text?.toString()?.lowercase() in listOf("save", "保存") }
            ?: error("System document picker has no Save action")
        assertTrue(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitUntil(30_000) { rule.activity.createdDocuments.isNotEmpty() }
        rule.waitUntil(30_000) { rule.onAllNodesWithText("加密备份已保存，请妥善保管密码").fetchSemanticsNodes().isNotEmpty() }
        val uri = rule.activity.createdDocuments.single()
        val contents = rule.activity.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals("FinexyBackup", org.json.JSONObject(contents).getString("format"))
        assertFalse(contents.contains("transactions"))
        val name = rule.activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        rule.onNodeWithText("备份密码（12–128 个字符）").performScrollTo().performTextInput(pass)
        rule.onNodeWithText("选择备份并预览导入").performScrollTo().performClick()
        rule.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
        rule.waitUntil(20_000) { findNode(automation.rootInActiveWindow) { it.text?.toString() == name } != null }
        var file = findNode(automation.rootInActiveWindow) { it.text?.toString() == name }!!
        while (!file.isClickable && file.parent != null) file = file.parent
        assertTrue(file.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        rule.waitUntil(30_000) { rule.onAllNodesWithText("确认恢复备份").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("确认导入").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("已恢复 0 条缺失流水", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
