package com.finexy.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Only synthetic QA screens after login; never capture authentication fields or browser storage. */
internal fun captureQaUiEvidence(rule: ComposeTestRule, name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    check(instrumentation.targetContext.packageName == "com.finexy.mobile.qa")
    require(name.matches(Regex("[a-z0-9-]+")))
    rule.waitForIdle()
    val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "ledger-budget-20261004")
    check(directory.mkdirs() || directory.isDirectory)
    val roots = rule.onAllNodes(isRoot(), useUnmergedTree = true)
    File(directory, "$name-semantics.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { index ->
        "Root $index\n" + roots[index].printToString()
    })
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "QA screen evidence could not be captured" }
    try {
        File(directory, "$name.png").outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
    } finally { bitmap.recycle() }
}
