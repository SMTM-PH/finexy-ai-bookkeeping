package com.finexy.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.espresso.Espresso

/** Normal production setup/AuthScreen, with explicit page readiness and no saved login secrets. */
internal fun loginQaThroughRealAuthScreen(rule: ComposeTestRule, url: String, username: String, password: String, readyText: String = "账户余额 · 按币种") {
    fun waitFor(text: String, editable: Boolean = false) {
        val selector = hasText(text, substring = false).let { if (editable) it and hasSetTextAction() else it }
        rule.waitUntil(20_000) { rule.onAllNodes(selector).fetchSemanticsNodes().isNotEmpty() }
    }
    fun scrollAndClickAfterKeyboard(text: String) {
        Espresso.closeSoftKeyboard()
        rule.waitForIdle()
        val node = rule.onNode(hasText(text, substring = false) and hasClickAction())
        rule.waitUntil(20_000) {
            try { node.performScrollTo().assertIsDisplayed().assertIsEnabled(); true }
            catch (_: AssertionError) { false }
        }
        node.performClick()
    }
    waitFor("已有服务器？连接账本")
    rule.onNodeWithText("已有服务器？连接账本").performScrollTo().performClick()
    waitFor("服务器地址", editable = true)
    rule.onNode(hasText("服务器地址") and hasSetTextAction()).performScrollTo().performTextReplacement(url)
    scrollAndClickAfterKeyboard("连接并登录")
    waitFor("登录 Finexy")
    waitFor("用户名或邮箱", editable = true)
    rule.onNode(hasText("用户名或邮箱") and hasSetTextAction()).performScrollTo().performTextReplacement(username)
    rule.onNode(hasText("密码", substring = false) and hasSetTextAction()).performScrollTo().performTextReplacement(password)
    scrollAndClickAfterKeyboard("登录")
    waitFor(readyText)
}
