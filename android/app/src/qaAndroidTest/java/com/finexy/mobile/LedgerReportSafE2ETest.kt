package com.finexy.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.finexy.mobile.reportqa.ReportSafQaContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Real FinexyApp -> export confirmation -> DocumentsUI -> original URI. No substituted ActivityResult. */
class LedgerReportSafE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private lateinit var fixture: LedgerReportQaFixture
    private val runs = mutableListOf<String>()
    private val downloads = mutableListOf<Pair<Uri, String>>()
    private var originalAutomationFlags: Int? = null
    private var lastFiles: LedgerReportFileViewModel? = null

    @Before fun requireIsolatedFixtureAndSeparateReceiverUid() {
        LedgerReportQaFixture.requireReportQaUrl()
        check(Build.VERSION.SDK_INT >= 35 && context.packageName == ReportSafQaContract.TARGET_PACKAGE)
        check(instrumentation.context.packageName == ReportSafQaContract.TEST_PACKAGE)
        check(context.checkSelfPermission(ReportSafQaContract.PERMISSION) == PackageManager.PERMISSION_GRANTED)
        val targetUid = context.applicationInfo.uid
        val testUid = context.packageManager.getApplicationInfo(ReportSafQaContract.TEST_PACKAGE, 0).uid
        check(targetUid != testUid)
        val info = automation.serviceInfo
        originalAutomationFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        fixture = LedgerReportQaFixture(rule)
        fixture.prepare()
    }

    @After fun removeOnlyExplicitlyOwnedSyntheticDocumentsAndFixture() {
        var failure: Throwable? = null
        fun clean(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error else failure?.addSuppressed(error) } }
        runs.toList().forEach { run -> clean { control("release", run) } }
        lastFiles?.let { files ->
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (onMain { files.working } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            clean { check(!onMain { files.working }) { "Synthetic file work did not finish before cleanup" } }
        }
        downloads.forEach { (uri, expectedName) -> clean {
            check(uri.authority == "com.android.providers.downloads.documents")
            check(documentName(uri) == expectedName && expectedName.matches(Regex("finexy-qa-[a-f0-9]{32}\\.csv")))
            check(DocumentsContract.deleteDocument(context.contentResolver, uri))
        } }
        runs.toList().forEach { run -> clean { control("end", run); runs.remove(run) } }
        originalAutomationFlags?.let { flags -> clean { val info = automation.serviceInfo; info.flags = flags; automation.serviceInfo = info } }
        if (::fixture.isInitialized) clean { fixture.close() }
        failure?.let { throw it }
    }

    @Test fun downloadsSaveOpenAndShareUseOneVerifiedUriWithActualReadOnlyConsumerAccess() {
        val run = begin("GOOD")
        fixture.openReport(); fixture.applyReport(includeHidden = true)
        val expected = runBlocking { fixture.expectedExport(includeHidden = true).bytes() }
        openPicker(includeHidden = true)
        selectRoot(setOf("Downloads", "下载"))
        setDocumentName(ReportSafQaContract.filename(run)); pressSystemSave()
        val uri = awaitSaved(expected, ownedDownloadsName = ReportSafQaContract.filename(run))
        assertEquals("com.android.providers.downloads.documents", uri.authority)
        assertEquals(ReportSafQaContract.filename(run), documentName(uri))
        control("expect", run, Bundle().apply {
            putString("uri", uri.toString()); putString("sha256", ReportSafQaContract.hash(expected)); putInt("bytes", expected.size)
        })
        fixture.clickText("打开已保存 CSV")
        chooseReceiverIfNecessary(run, expectedCount = 1)
        val opened = state(run).getJSONArray("received").getJSONObject(0)
        assertReceiver(opened, uri, expected, Intent.ACTION_VIEW)
        fixture.clickText("分享已保存 CSV")
        chooseReceiverIfNecessary(run, expectedCount = 2)
        val shared = state(run).getJSONArray("received").getJSONObject(1)
        assertReceiver(shared, uri, expected, Intent.ACTION_SEND)
        assertEquals(uri.toString(), shared.getString("stream"))
        assertEquals(listOf(uri.toString()), shared.getJSONArray("clipUris").let { items -> List(items.length()) { items.getString(it) } })
        assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        captureQaUiEvidence(rule, "report-saf-downloads-open-share")
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    @Test fun confirmationAndDocumentsUiCancellationNeverWriteOrOfferSavedFileActions() {
        val run = begin("GOOD")
        fixture.openReport(); fixture.applyReport()
        preview()
        fixture.clickText("取消")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("确认导出 CSV").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, state(run).getInt("createCount")); assertNoSavedFile()
        openPicker()
        selectRoot(setOf(ReportSafQaContract.rootTitle(run)))
        setDocumentName(ReportSafQaContract.filename(run))
        hideSystemKeyboard()
        check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        rule.waitUntil(30_000) { fileSnapshot().message == "已取消保存，尚未写入文件。" }
        assertEquals(0, state(run).getInt("createCount"))
        assertEquals(0, state(run).getInt("writeOpenCount"))
        assertFalse(fileSnapshot().pending)
        assertNoSavedFile()
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    @Test fun actualProviderWriteReadAndCorruptReadbackFailuresNeverBecomeAcceptedSavedFiles() {
        fixture.openReport(); fixture.applyReport()
        val expected = runBlocking { fixture.expectedExport().bytes() }
        for (mode in listOf("OPEN_WRITE_FAIL", "READBACK_FAIL", "CORRUPT_READBACK")) {
            val run = begin(mode)
            openPicker(); selectRoot(setOf(ReportSafQaContract.rootTitle(run)))
            setDocumentName(ReportSafQaContract.filename(run)); pressSystemSave()
            rule.waitUntil(30_000) { fileSnapshot().failed && !fileSnapshot().working }
            assertEquals("Upstream provider failure must remain visible: $mode",
                "上次文件保存或重新核验未完成，请检查所选文件；它可能包含部分内容。", fileSnapshot().message)
            val snapshot = state(run)
            assertEquals(mode, 1, snapshot.getInt("createCount"))
            assertEquals(mode, 1, snapshot.getInt("writeOpenCount"))
            assertEquals(context.applicationInfo.uid, snapshot.getInt("lastWriteCallerUid"))
            if (mode == "OPEN_WRITE_FAIL") {
                assertEquals(0, snapshot.getInt("readOpenCount")); assertEquals(0, actualBytes(run).size)
            } else {
                assertTrue(snapshot.getInt("readOpenCount") >= 1)
                assertArrayEquals("The provider's actual file must be correct; only production re-read failed: $mode", expected, actualBytes(run))
                rule.waitUntil(10_000) { state(run).getInt("writeCloseCount") >= 1 }
            }
            assertNoSavedFile()
            control("end", run); runs.remove(run)
        }
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    @Test fun realPickerRotationKeepsOriginalDeliveryUntilTheNewActivityVerifiesTheSameFile() {
        val run = begin("GOOD")
        fixture.openReport(); fixture.applyReport()
        val expected = runBlocking { fixture.expectedExport().bytes() }
        val originalActivity = rule.activity
        openPicker(); selectRoot(setOf(ReportSafQaContract.rootTitle(run)))
        setDocumentName(ReportSafQaContract.filename(run))
        val before = filesModel()
        assertNotNull(onMain { before.pending })
        instrumentation.runOnMainSync { originalActivity.recreate() }
        rule.waitUntil(30_000) { rule.activity !== originalActivity && isDocumentsUiForeground() }
        assertSame("Production file work must survive Activity recreation", before, filesModel())
        pressSystemSave()
        val uri = awaitSaved(expected)
        assertEquals(ReportSafQaContract.DOCS_AUTHORITY, uri.authority)
        assertArrayEquals(expected, actualBytes(run))
        assertEquals(state(run).getJSONArray("documents").getJSONObject(0).getString("uri"), uri.toString())
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    @Test fun lateRealPickerReturnAfterSessionModeABADoesNotOpenOutputOrReviveOldReport() {
        val run = begin("GOOD")
        fixture.openReport(); fixture.applyReport()
        val files = filesModel()
        openPicker(); selectRoot(setOf(ReportSafQaContract.rootTitle(run)))
        setDocumentName(ReportSafQaContract.filename(run))
        invalidateThroughOwnedModeABA()
        pressSystemSave()
        rule.waitUntil(30_000) { !isDocumentsUiForeground() }
        rule.waitUntil(30_000) { onMain { !files.working && files.pending == null } }
        assertNull(onMain { files.saved })
        assertEquals(1, state(run).getInt("createCount"))
        assertEquals("Returning a real but stale document URI must perform no output open", 0, state(run).getInt("writeOpenCount"))
        assertEquals(0, actualBytes(run).size)
        rule.onAllNodesWithText("CSV 已写入并重新读取核验。可打开文件或交给系统分享。").fetchSemanticsNodes().also { assertTrue(it.isEmpty()) }
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    @Test fun realBlockedReadbackDisablesBackAndSessionModeABAPreventsAcceptanceAfterRelease() {
        val run = begin("BLOCK_READBACK")
        fixture.openReport(); fixture.applyReport()
        val expected = runBlocking { fixture.expectedExport().bytes() }
        val files = filesModel()
        openPicker(); selectRoot(setOf(ReportSafQaContract.rootTitle(run)))
        setDocumentName(ReportSafQaContract.filename(run)); pressSystemSave()
        rule.waitUntil(30_000) { state(run).optBoolean("readBlocked") && onMain { files.working } }
        fixture.findText("正在保存…").assertIsNotEnabled()
        rule.onAllNodesWithText("正在写入并核验所选文件，请稍候…").onFirst().assertExists()
        assertArrayEquals(expected, actualBytes(run))
        invalidateThroughOwnedModeABA()
        control("release", run)
        rule.waitUntil(30_000) { !onMain { files.working } }
        assertNull(onMain { files.saved })
        assertFalse("The completed old write cannot leave an accepted saved URI", onMain { files.message?.contains("CSV 已写入并重新读取核验") == true })
        runBlocking { fixture.assertFinanciallyUnchanged() }
    }

    /** Same main-thread turn: invalidate through the actual SecureStore observer, then return.
     * No intermediate UI frame can open the shared local/legacy database and no credential is fabricated.
     */
    private fun invalidateThroughOwnedModeABA() {
        val activity = rule.activity
        val database = activity.currentDatabaseName()
        check(database != "finexy.db" && activity.store.get("local_mode") != "true")
        onMain {
            activity.store.put("local_mode", "true", durable = true)
            activity.store.put("local_mode", "false", durable = true)
        }
        check(activity.currentDatabaseName() == database)
    }

    private data class FileSnapshot(val pending: Boolean, val saved: Uri?, val working: Boolean, val failed: Boolean, val message: String?)
    private fun <T> onMain(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
    private fun filesModel(): LedgerReportFileViewModel {
        val activity = rule.activity
        val value = onMain {
            check(activity.preferencesName.startsWith("ledger-switch-ui-"))
            ViewModelProvider(activity).get("ledger-report-files-${activity.preferencesName}", LedgerReportFileViewModel::class.java)
        }
        lastFiles = value; return value
    }
    private fun fileSnapshot(): FileSnapshot {
        val files = filesModel()
        return onMain { FileSnapshot(files.pending != null, files.saved?.second, files.working, files.failed, files.message) }
    }
    private fun begin(mode: String): String = UUID.randomUUID().toString().replace("-", "").also { run ->
        runs += run
        val result = control("begin", run, Bundle().apply { putString("mode", mode) })
        assertEquals(mode, JSONObject(requireNotNull(result.getString("json"))).getString("mode"))
    }
    private fun control(method: String, run: String, extras: Bundle? = null): Bundle = requireNotNull(
        context.contentResolver.call(ReportSafQaContract.CONTROL_URI, method, run, extras)
    )
    private fun state(run: String) = JSONObject(requireNotNull(control("state", run).getString("json")))
    private fun actualBytes(run: String): ByteArray {
        val id = state(run).getJSONArray("documents").getJSONObject(0).getString("id")
        return requireNotNull(control("actual", run, Bundle().apply { putString("documentId", id) }).getByteArray("bytes"))
    }
    private fun documentName(uri: Uri): String = requireNotNull(context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use {
        check(it.moveToFirst()); it.getString(0)
    }
    private fun preview() { fixture.clickText("预览 CSV 导出"); fixture.waitFor("确认导出 CSV") }
    private fun openPicker(includeHidden: Boolean = false) {
        preview()
        if (includeHidden) {
            rule.onNodeWithText("确认并选择文件位置").assertIsNotEnabled()
            fixture.findText("另行确认导出隐藏记录的实际金额").performClick()
        }
        fixture.clickText("确认并选择文件位置")
        rule.waitUntil(30_000) { isDocumentsUiForeground() }
        assertTrue("Download must be held in the production file ViewModel before choosing a document", fileSnapshot().pending)
    }
    private fun awaitSaved(expected: ByteArray, ownedDownloadsName: String? = null): Uri {
        rule.waitUntil(30_000) { fileSnapshot().saved != null && !fileSnapshot().working }
        val snapshot = fileSnapshot()
        val uri = requireNotNull(snapshot.saved)
        if (ownedDownloadsName != null) {
            check(uri.authority == "com.android.providers.downloads.documents")
            check(ownedDownloadsName.matches(Regex("finexy-qa-[a-f0-9]{32}\\.csv")))
            check(documentName(uri) == ownedDownloadsName)
            downloads += uri to ownedDownloadsName
        }
        assertFalse(snapshot.failed)
        assertEquals("CSV 已写入并重新读取核验。可打开文件或交给系统分享。", snapshot.message)
        assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        fixture.findText("打开已保存 CSV").assertIsEnabled()
        fixture.findText("分享已保存 CSV").assertIsEnabled()
        return uri
    }
    private fun assertNoSavedFile() {
        assertNull(fileSnapshot().saved)
        assertTrue(rule.onAllNodesWithText("打开已保存 CSV").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("分享已保存 CSV").fetchSemanticsNodes().isEmpty())
    }
    private fun assertReceiver(record: JSONObject, uri: Uri, expected: ByteArray, action: String) {
        assertEquals(action, record.getString("action")); assertEquals(uri.toString(), record.getString("uri"))
        assertEquals("text/csv", record.getString("mime"))
        assertEquals(expected.size, record.getInt("bytes")); assertEquals(ReportSafQaContract.hash(expected), record.getString("sha256"))
        assertNotEquals(record.getInt("targetUid"), record.getInt("receiverUid"))
        assertNotEquals(record.getInt("providerUid"), record.getInt("receiverUid"))
        assertEquals(PackageManager.PERMISSION_GRANTED, record.getInt("readPermission"))
        assertEquals(PackageManager.PERMISSION_DENIED, record.getInt("writePermission"))
        assertTrue(record.getBoolean("writeAttemptDenied")); assertFalse(record.getBoolean("writeAttemptUnexpectedlyOpened"))
        assertEquals(0, record.getInt("flags") and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    private fun isDocumentsUiForeground() = automation.rootInActiveWindow?.packageName?.toString()?.let {
        it == "com.android.documentsui" || it == "com.google.android.documentsui"
    } == true
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = automation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        visit(root); return result
    }
    private fun clickSystem(node: AccessibilityNodeInfo) {
        var target = node
        var clicked = false
        while (true) {
            if (target.isVisibleToUser && target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                clicked = true
                break
            }
            target = target.parent ?: break
        }
        if (!clicked) {
            // API 35 DocumentsUI 把根目录行交给 ListView 处理，行节点本身标记 clickable=false；
            // 此处用真实坐标点击，不使用语义 ACTION_CLICK，也不改变任何产品代码。
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            check(!bounds.isEmpty)
            val x = bounds.centerX()
            val y = bounds.centerY()
            automation.executeShellCommand("input tap $x $y").close()
            SystemClock.sleep(500)
        }
    }
    private fun selectRoot(labels: Set<String>) {
        check(isDocumentsUiForeground())
        fun inDrawer(node: AccessibilityNodeInfo): Boolean = generateSequence(node) { it.parent }.take(10).any {
            it.viewIdResourceName?.substringAfterLast(":id/") in
                setOf("roots_list", "roots_drawer", "roots_container", "drawer_roots")
        }
        fun drawerRow(): AccessibilityNodeInfo? =
            nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() in labels && inDrawer(it) }
        // 选择成功的判据：目标标签出现在抽屉之外的工具栏/面包屑，而不是仍停留在抽屉行。
        fun switched(): Boolean = nodes().any { it.isVisibleToUser && it.text?.toString() in labels && !inDrawer(it) }
        repeat(3) {
            if (switched()) return
            val opener = nodes().firstOrNull { it.isVisibleToUser &&
                it.contentDescription?.toString() in setOf("Show roots", "显示根目录", "显示位置", "Open navigation drawer") }
            if (opener != null) clickSystem(opener)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var row = drawerRow()
            var scrolls = 0
            while (row == null && SystemClock.elapsedRealtime() < deadline) {
                if (scrolls < 2) nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                scrolls++
                SystemClock.sleep(100)
                row = drawerRow()
            }
            if (row != null) clickSystem(row)
            val settle = SystemClock.elapsedRealtime() + 5_000
            while (!switched() && SystemClock.elapsedRealtime() < settle) SystemClock.sleep(100)
        }
        check(switched()) { "DocumentsUI did not switch to the requested root: $labels" }
        rule.waitUntil(10_000) { nodes().any { it.isVisibleToUser && it.isEditable } }
    }
    private fun setDocumentName(name: String) {
        require(name.matches(Regex("finexy-qa-[a-f0-9]{32}\\.csv")))
        val editor = nodes().firstOrNull { it.isEditable && it.viewIdResourceName?.endsWith(":id/title") == true }
            ?: nodes().singleOrNull { it.isEditable && it.viewIdResourceName?.contains("search") != true }
            ?: error("DocumentsUI filename editor not found")
        check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        rule.waitUntil(10_000) { nodes().any { it.isEditable && it.text?.toString() == name } }
        hideSystemKeyboard()
    }
    private fun hideSystemKeyboard() {
        if (automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        }
    }
    private fun pressSystemSave() {
        check(isDocumentsUiForeground()); hideSystemKeyboard()
        val save = nodes().firstOrNull { it.isEnabled && it.text?.toString()?.lowercase() in setOf("save", "保存") }
            ?: error("DocumentsUI has no enabled Save action")
        clickSystem(save)
    }
    private fun chooseReceiverIfNecessary(run: String, expectedCount: Int) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        var scrolled = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = state(run)
            check(!snapshot.has("receiverErrorClass")) { "Cross-UID receiver rejected the real handoff: ${snapshot.optString("receiverErrorClass")}" }
            if (snapshot.getJSONArray("received").length() >= expectedCount) return
            val resolverPackages = setOf("android", "com.android.intentresolver", "com.google.android.intentresolver")
            val resolverNodes = nodes().filter { it.isVisibleToUser && it.packageName?.toString() in resolverPackages }
            val justOnce = resolverNodes.firstOrNull { it.isEnabled && it.text?.toString()?.lowercase() in setOf("just once", "仅此一次", "仅一次") }
            val candidate = resolverNodes.firstOrNull { it.text?.toString() == ReportSafQaContract.RECEIVER_LABEL }
            if (justOnce != null) clickSystem(justOnce)
            else if (candidate != null) clickSystem(candidate)
            else if (!scrolled && nodes().any { it.isVisibleToUser && it.isScrollable && it.packageName?.toString() in resolverPackages }) {
                nodes().first { it.isVisibleToUser && it.isScrollable && it.packageName?.toString() in resolverPackages }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); scrolled = true
            }
            SystemClock.sleep(100)
        }
        error("No actual cross-UID CSV receipt was recorded")
    }
}
