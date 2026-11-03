package com.finexy.mobile

import android.content.ContentUris
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.finexy.mobile.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual system photo picker/DocumentsUI and Camera activities; no substituted ActivityResult. */
class AIReviewNativeImageE2ETest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val downloads = mutableListOf<String>()
    private val registrations = mutableListOf<String>()
    private val client = OkHttpClient()
    private var originalAutomationFlags: Int? = null
    private data class Fixture(val api: FinexyApi, val ledger: Long, val repo: TransactionRepository,
        val baseline: Pair<Map<Long, Long>, Set<Long>>, val defaultBaseline: Pair<Map<Long, Long>, Set<Long>>)

    @After fun cleanupOnlyOwnedData() {
        originalAutomationFlags?.let { flags ->
            val info = instrumentation.uiAutomation.serviceInfo; info.flags = flags; instrumentation.uiAutomation.serviceInfo = info
        }
        downloads.forEach { name ->
            check(name.matches(Regex("fxqa-[a-f0-9]{8}\\.png")))
            shell("content delete --uri content://media/external/file --where \"_display_name='$name'\"")
            shell("rm -f /sdcard/Download/$name")
            check(shell("test ! -e /sdcard/Download/$name && echo absent").trim() == "absent")
            check(shell("content query --uri content://media/external/file --projection _id --where \"_display_name='$name'\"").contains("No result found"))
        }
        val store = rule.activity.store; check(store.namespace.startsWith("ledger-switch-ui-"))
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), false)
        WorkManager.getInstance(context).cancelAllWorkByTag("finexy-sync-${identity.take(24)}-${store.namespace.hashCode()}").result.get(10, TimeUnit.SECONDS)
        rule.activityRule.scenario.close()
        registrations.forEach { check(it.startsWith("ai-native-register-")); context.deleteSharedPreferences(it) }
    }

    @Test fun actualGalleryReturnEncryptsTheSelectedBytesAndRestoresAfterActivityRecreation() {
        val fixture = prepare(); val bytes = png(); val name = download(bytes); openSource()
        click("选择票据图片"); selectDownload(name); waitFor("完成裁剪"); click("完成裁剪")
        val saved = draft(fixture); runBlocking { assertArrayEquals(bytes, fixture.repo.aiReview.sourceImage(saved)) }
        rule.activityRule.scenario.recreate(); awaitEditor()
        runBlocking { assertArrayEquals(bytes, fixture.repo.aiReview.sourceImage(draft(fixture))) }
        awaitPreview()
        assertUntouched(fixture); captureQaUiEvidence(rule, "ai-native-gallery-return")
    }

    @Test fun cancellingTheActualGalleryKeepsTheExistingEncryptedOriginal() {
        val fixture = prepare(); val bytes = png(); val prior = seed(fixture, bytes); openSource()
        click("替换票据图片"); waitExternal(); backToApp(); awaitEditor()
        assertEquals(prior, draft(fixture)); runBlocking { assertArrayEquals(bytes, fixture.repo.aiReview.sourceImage(prior)) }
        awaitPreview()
        assertUntouched(fixture); captureQaUiEvidence(rule, "ai-native-gallery-cancel")
    }

    @Test fun oversizedGalleryReturnKeepsTheOriginalAndExplicitRetryReadsTheRepairedSameUri() {
        val fixture = prepare(); val before = png(); val prior = seed(fixture, before)
        val replacement = png(0xffcc8833.toInt()); val name = download(replacement, oversized = true); openSource()
        click("替换票据图片"); selectDownload(name)
        waitFor("图片未保存。原来源保留，请选择 8 MiB 内的有效图片或重新读取。")
        assertEquals(prior, draft(fixture)); runBlocking { assertArrayEquals(before, fixture.repo.aiReview.sourceImage(prior)) }
        assertUntouched(fixture)
        awaitPreview()
        rule.onNodeWithText("图片未保存。原来源保留，请选择 8 MiB 内的有效图片或重新读取。").performScrollTo().assertIsDisplayed()
        captureQaUiEvidence(rule, "ai-native-gallery-oversize-rejected")
        val key = "ai-source-${rule.activity.store.namespace}-${fixture.repo.aiReview.storagePartition}-${fixture.ledger}"
        lateinit var model: AIReviewSourceViewModel
        rule.activityRule.scenario.onActivity { model = androidx.lifecycle.ViewModelProvider(it)[key, AIReviewSourceViewModel::class.java] }
        val request = requireNotNull(model.imageImport.value.request); assertFalse(model.imageImport.value.running)
        check(downloads.contains(name))
        val encoded = android.util.Base64.encodeToString(replacement, android.util.Base64.NO_WRAP)
        check(encoded.matches(Regex("[A-Za-z0-9+/=]+")))
        shell("sh -c 'echo $encoded | base64 -d > /sdcard/Download/$name'")
        assertEquals(request, model.imageImport.value.request)
        click("重新读取所选图片"); waitFor("完成裁剪"); click("完成裁剪")
        runBlocking { assertArrayEquals(replacement, fixture.repo.aiReview.sourceImage(draft(fixture))) }
        awaitPreview()
        assertUntouched(fixture); captureQaUiEvidence(rule, "ai-native-gallery-oversize-retry")
    }

    @Test fun cancellingTheActualCameraKeepsTheOriginalAndRemovesItsOwnedTemporaryCapture() {
        val fixture = prepare(); val bytes = png(); val prior = seed(fixture, bytes); val files = captureFiles(); openSource()
        click("拍摄票据"); waitExternal(); backToApp(); awaitEditor()
        rule.waitUntil(10_000) { captureFiles() == files }
        assertEquals(prior, draft(fixture)); runBlocking { assertArrayEquals(bytes, fixture.repo.aiReview.sourceImage(prior)) }
        awaitPreview()
        assertUntouched(fixture); captureQaUiEvidence(rule, "ai-native-camera-cancel")
    }

    @Test fun actualCameraReturnEncryptsItsImageAndDeletesThePrivateTemporaryFile() {
        val fixture = prepare(); val files = captureFiles(); val publicImages = publicImageIds(); openSource(); click("拍摄票据"); waitExternal()
        allowCameraOnceIfRequested()
        nativeClick("camera shutter", 30_000) { it.viewIdResourceName?.endsWith(":id/shutter_button") == true || it.contentDescription?.toString() in setOf("Shutter", "快门") }
        nativeClick("camera use photo", 30_000) { it.viewIdResourceName?.endsWith(":id/done_button") == true || it.contentDescription?.toString() in setOf("Done", "Use photo", "完成", "使用照片") }
        waitFor("完成裁剪"); click("完成裁剪")
        val saved = draft(fixture); val bytes = runBlocking { fixture.repo.aiReview.sourceImage(saved) }
        assertTrue(bytes.isNotEmpty()); val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); assertNotNull(decoded); requireNotNull(decoded).recycle()
        rule.waitUntil(10_000) { captureFiles() == files }
        assertEquals(publicImages, publicImageIds())
        rule.activityRule.scenario.recreate(); awaitEditor()
        runBlocking { assertArrayEquals(bytes, fixture.repo.aiReview.sourceImage(draft(fixture))) }
        awaitPreview()
        assertUntouched(fixture); captureQaUiEvidence(rule, "ai-native-camera-return")
    }

    @Test fun interruptedInvalidCaptureKeepsOriginalUntilExplicitReadRetrySucceeds() {
        val fixture = prepare(); val original = png(); val prior = seed(fixture, original)
        val store = AIPendingCaptureStore(context, fixture.repo.aiReview.storagePartition, fixture.ledger)
        val capture = store.begin(prior)
        try {
            store.file(capture).writeBytes(byteArrayOf(1, 2, 3))
            openSource(); click("重新读取拍照图片"); waitForContaining("图片未保存")
            assertEquals(prior, draft(fixture)); assertEquals(capture, store.read())
            runBlocking { assertArrayEquals(original, fixture.repo.aiReview.sourceImage(prior)) }
            assertUntouched(fixture)
            val replacement = png(0xff8a4422.toInt()); store.file(capture).writeBytes(replacement)
            click("重新读取拍照图片"); waitFor("完成裁剪"); click("完成裁剪")
            val saved = draft(fixture)
            assertEquals(capture.operationId, AISourceForm.read(saved).fields["captureOperationId"])
            runBlocking { assertArrayEquals(replacement, fixture.repo.aiReview.sourceImage(saved)) }
            assertNull(store.read()); assertFalse(store.file(capture).exists())
            awaitPreview(); assertUntouched(fixture)
            captureQaUiEvidence(rule, "ai-native-pending-invalid-explicit-retry")
        } finally { if (store.read() == capture) store.discard(capture) }
    }

    @Test fun staleInterruptedCaptureRequiresSeparateDiscardAndNeverReplacesNewerSource() {
        val fixture = prepare(); val original = png(); val prior = seed(fixture, original)
        val store = AIPendingCaptureStore(context, fixture.repo.aiReview.storagePartition, fixture.ledger)
        val capture = store.begin(prior)
        try {
            store.file(capture).writeBytes(png(0xff552288.toInt()))
            val newer = runBlocking { fixture.repo.aiReview.saveDraft(fixture.ledger, 0,
                AISourceForm.read(prior).changed(mapOf("sourceText" to "Newer synthetic source")).json(), prior) }
            openSource(); waitForContaining("来源已更新")
            rule.onNode(hasText("重新读取拍照图片") and hasClickAction()).assertIsNotEnabled()
            click("丢弃未保存的拍照内容"); waitFor("确认丢弃拍照内容"); click("继续保留")
            assertEquals(capture, store.read()); assertTrue(store.file(capture).exists())
            assertEquals(newer, draft(fixture)); assertUntouched(fixture)
            click("丢弃未保存的拍照内容"); click("确认丢弃拍照内容")
            rule.waitUntil(10_000) { store.read() == null }
            assertEquals(newer, draft(fixture))
            runBlocking { assertArrayEquals(original, fixture.repo.aiReview.sourceImage(newer)) }
            assertFalse(store.file(capture).exists()); awaitPreview(); assertUntouched(fixture)
            captureQaUiEvidence(rule, "ai-native-stale-pending-explicit-discard")
        } finally { if (store.read() == capture) store.discard(capture) }
    }

    @Test fun corruptCaptureOwnershipBlocksEditingAndRetryDoesNotTreatItAsEmpty() {
        val fixture = prepare(); val original = png(); val prior = seed(fixture, original)
        val store = AIPendingCaptureStore(context, fixture.repo.aiReview.storagePartition, fixture.ledger)
        val capture = store.begin(prior); val file = store.file(capture)
        val journal = File(file.parentFile, "pending.json"); val metadata = journal.readText()
        try {
            file.writeBytes(png(0xff884411.toInt())); journal.writeText("{damaged synthetic ownership")
            openSource(); waitForContaining("拍照恢复记录未能读取")
            rule.onNodeWithText("描述或粘贴票据文字").assertIsNotEnabled()
            click("重新读取拍照恢复记录"); waitForContaining("拍照恢复记录未能读取")
            assertEquals("{damaged synthetic ownership", journal.readText()); assertTrue(file.length() > 0)
            assertEquals(prior, draft(fixture))
            runBlocking { assertArrayEquals(original, fixture.repo.aiReview.sourceImage(prior)) }
            assertUntouched(fixture)
            // Repair only this test-owned damaged journal, then use the production discard confirmation.
            journal.writeText(metadata); click("重新读取拍照恢复记录"); waitFor("丢弃未保存的拍照内容")
            click("丢弃未保存的拍照内容"); click("确认丢弃拍照内容")
            rule.waitUntil(10_000) { store.read() == null }
            assertEquals(prior, draft(fixture)); assertUntouched(fixture)
            captureQaUiEvidence(rule, "ai-native-corrupt-pending-blocked")
        } finally { if (journal.exists()) journal.writeText(metadata); if (store.read() == capture) store.discard(capture) }
    }

    @Test fun committedEncryptedCaptureCleanupFailureIsVisibleAndRetryNeverReimports() {
        val fixture = prepare(); val prior = seed(fixture, png())
        val store = AIPendingCaptureStore(context, fixture.repo.aiReview.storagePartition, fixture.ledger)
        val capture = store.begin(prior); val file = store.file(capture)
        val replacement = png(0xff336699.toInt())
        val committed = runBlocking { fixture.repo.aiReview.saveSourceImage(fixture.ledger,
            AISourceForm.read(prior).changed(mapOf("captureOperationId" to capture.operationId)).json(), replacement, prior) }
        val obstruction = File(file, "test-owned-obstruction")
        try {
            // A non-file at this exact owned path makes the real cleanup reject; no broad FS mutation.
            assertTrue(file.delete()); assertTrue(file.mkdir()); obstruction.writeText("synthetic")
            openSource(); waitForContaining("拍照恢复记录未能读取")
            rule.onNodeWithText("描述或粘贴票据文字").assertIsNotEnabled()
            assertEquals(committed, draft(fixture)); assertEquals(capture, store.read())
            runBlocking { assertArrayEquals(replacement, fixture.repo.aiReview.sourceImage(committed)) }
            assertUntouched(fixture)
            assertTrue(obstruction.delete()); assertTrue(file.delete())
            click("重新读取拍照恢复记录")
            rule.waitUntil(10_000) { store.read() == null }
            assertEquals(committed, draft(fixture))
            runBlocking { assertArrayEquals(replacement, fixture.repo.aiReview.sourceImage(committed)) }
            rule.onNodeWithText("描述或粘贴票据文字").assertIsEnabled()
            awaitPreview(); assertUntouched(fixture)
            captureQaUiEvidence(rule, "ai-native-committed-pending-cleanup-retry")
        } finally {
            if (obstruction.exists()) assertTrue(obstruction.delete())
            if (file.isDirectory) assertTrue(file.delete())
            if (store.read() == capture) store.discard(capture)
        }
    }

    private fun prepare(): Fixture = runBlocking {
        check(context.packageName == "com.finexy.mobile.qa")
        require(InstrumentationRegistry.getArguments().getString("finexy.ai.source.url") == "http://10.0.2.2:18141")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16); val namespace = "ai-native-register-$suffix".also(registrations::add)
        val name = "ain$suffix"; val password = UUID.randomUUID().toString(); val url = "http://10.0.2.2:18141"
        val store = SecureStore(context, namespace); store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
        val authentication = FinexyApi(store); AccountSecurity(authentication).register(name, "$name@example.invalid", "Synthetic native image QA", password, "CNY")
        store.put(FinexyApi.KEY_TOKEN, authentication.login(name, password).token, durable = true)
        val api = FinexyApi(store)
        api.createAccount(AccountDraft("Native default sentinel", 1, "CNY", 1234))
        val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Native image ledger", "isolated")
        val account = api.createAccount(AccountDraft("Native image account", 1, "USD", 9876)).single { it.name == "Native image account" }; api.moveAccountToLedger(account.id, ledger.id)
        val identity = LedgerScope.identity(url, store.get(FinexyApi.KEY_TOKEN), false)
        val dbName = LedgerScope.databaseName(rule.activity.store, identity); require(dbName != "finexy.db")
        UserPreferenceStore(rule.activity.store, dbName).save(UserPreferences(autoUpdateExchangeRatesData = false, aiTextConsent = true, aiStructuredConsent = true, cloudSyncEnabled = false))
        val baseline = financial(api, ledger.id); val defaultBaseline = financial(api, 0)
        loginQaThroughRealAuthScreen(rule, url, name, password, "总览")
        val repository = TransactionRepository(context, FinexyDatabase.get(context, rule.activity.currentDatabaseName()), false)
        SyncEngine(FinexyApi(rule.activity.store), repository, autoUpdateExchangeRates = false).sync()
        waitFor("Native image ledger"); rule.onNodeWithContentDescription("切换到账本：Native image ledger").performClick()
        client.newCall(Request.Builder().url("http://10.0.2.2:18144/control").post("{\"result\":{}}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(204, it.code) }
        Fixture(api, ledger.id, repository, baseline, defaultBaseline)
    }
    private fun seed(f: Fixture, bytes: ByteArray) = runBlocking { f.repo.aiReview.saveSourceImage(f.ledger, AISourceForm().changed(mapOf("sourceType" to "2")).json(), bytes) }
    private fun draft(f: Fixture): AIReviewDraftEntity = runBlocking { requireNotNull(f.repo.aiReview.draft(f.ledger, 0)) }
    private suspend fun financial(api: FinexyApi, ledger: Long) = api.parseAccountResponse(api.listAccounts(ledger)).associate { it.id to it.balanceMinor } to
        api.parseTransactionResponse(api.listTransactions(ledger), ledger).map { it.id }.toSet()
    private fun assertUntouched(f: Fixture) = runBlocking {
        assertEquals(f.baseline, financial(f.api, f.ledger)); assertEquals(f.defaultBaseline, financial(f.api, 0))
        assertTrue(f.api.listLedgerAIReviewItems(f.ledger).isEmpty()); assertTrue(f.repo.aiReview.requests().isEmpty())
        client.newCall(Request.Builder().url("http://10.0.2.2:18144/audit").build()).execute().use { assertEquals(0, JSONObject(requireNotNull(it.body).string()).getInt("requests")) }
    }
    private fun png(color: Int = 0xff387b99.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    private fun download(bytes: ByteArray, oversized: Boolean = false): String {
        val name = "fxqa-${UUID.randomUUID().toString().take(8)}.png"
        check(name.matches(Regex("fxqa-[a-f0-9]{8}\\.png")))
        check(shell("content query --uri content://media/external/file --projection _id --where \"_display_name='$name'\"").contains("No result found"))
        check(shell("sh -c 'set -C; : > /sdcard/Download/$name && echo created'").trim() == "created")
        downloads += name
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        check(encoded.matches(Regex("[A-Za-z0-9+/=]+")))
        // Shell-owned fixture proves the real returned URI grant is needed; app-owned media would not.
        shell("sh -c 'echo $encoded | base64 -d > /sdcard/Download/$name'")
        if (oversized) shell("truncate -s " + (AI_IMAGE_MAX_BYTES + 1) + " /sdcard/Download/$name")
        shell("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/$name")
        val deadline = SystemClock.elapsedRealtime() + 20_000; var id: Long? = null
        while (SystemClock.elapsedRealtime() < deadline && id == null) {
            val row = shell("content query --uri content://media/external/images/media --projection _id --where \"_display_name='$name'\"")
            id = Regex("_id=(\\d+)").find(row)?.groupValues?.get(1)?.toLong()
            if (id == null) SystemClock.sleep(100)
        }
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, requireNotNull(id) { "Owned synthetic download was not indexed" })
        var denied = false
        try { context.contentResolver.openInputStream(uri)?.use { it.read() } }
        catch (_: SecurityException) { denied = true }
        check(denied) { "Gallery fixture must be unreadable before the real picker grants its URI" }
        return name
    }
    private fun captureFiles() = listOf(File(context.cacheDir, "receipt-captures"), File(context.filesDir, "receipt-captures"))
        .flatMap { root -> if (root.isDirectory) root.walkTopDown().filter { it.isFile && it.name.endsWith(".jpg") }.map { it.path }.toList() else emptyList() }.toSet()
    private fun publicImageIds() = Regex("_id=(\\d+)").findAll(shell("content query --uri content://media/external/images/media --projection _id")).map { it.groupValues[1].toLong() }.toSet()
    private fun openSource() { click("设置"); click("AI 与票据识别"); waitFor("AI 来源录入"); awaitEditor() }
    private fun awaitEditor() {
        try {
            rule.waitUntil(30_000) { rule.onAllNodesWithText("继续编辑来源").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodes(hasText("描述或粘贴票据文字") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            try { captureQaUiEvidence(rule, "ai-native-wait-failure-${SystemClock.elapsedRealtime()}") }
            catch (_: Throwable) { }
            throw failure
        }
        if (rule.onAllNodesWithText("继续编辑来源").fetchSemanticsNodes().isNotEmpty()) click("继续编辑来源")
    }
    private fun waitFor(text: String) = waitForSourceText(text, substring = false)
    private fun waitForContaining(text: String) = waitForSourceText(text, substring = true)
    private fun waitForSourceText(text: String, substring: Boolean) {
        try {
            rule.waitUntil(30_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            // Capture before the test's finally/After repairs its owned fixture.
            // A diagnostic failure must never replace the original test failure.
            try { captureQaUiEvidence(rule, "ai-native-wait-failure-${SystemClock.elapsedRealtime()}") }
            catch (_: Throwable) { }
            throw failure
        }
    }
    private fun awaitPreview() {
        rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription("本次将发送的裁剪图片预览").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("本次将发送的裁剪图片预览").performScrollTo().assertIsDisplayed()
    }
    private fun click(text: String) {
        val node = rule.onNode(hasText(text) and hasClickAction()); var parent = node.fetchSemanticsNode().parent; var scroll = false
        while (parent != null) { if (parent.config.contains(SemanticsActions.ScrollBy)) scroll = true; parent = parent.parent }
        if (scroll) node.performScrollTo(); node.assertIsDisplayed().performClick()
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (originalAutomationFlags == null) {
            val info = instrumentation.uiAutomation.serviceInfo; originalAutomationFlags = info.flags
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            instrumentation.uiAutomation.serviceInfo = info
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        visit(root); return result
    }
    private fun allowed(node: AccessibilityNodeInfo): Boolean {
        val pkg = node.packageName?.toString().orEmpty()
        return pkg in setOf("com.google.android.providers.media.module", "com.android.providers.media.module", "com.google.android.documentsui", "com.android.documentsui", "com.android.camera2", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "android")
    }
    private fun tryClick(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val target = nodes().firstOrNull { allowed(it) && it.isVisibleToUser && it.isEnabled && predicate(it) } ?: return false
        var action: AccessibilityNodeInfo? = target
        repeat(6) {
            action?.let { if (it.isClickable && allowed(it) && it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
            action = action?.parent
        }
        // DocumentsUI image cards can expose touch bounds without an ACTION_CLICK.
        val bounds = android.graphics.Rect(); target.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0 || bounds.centerX() < 0 || bounds.centerY() < 0) return false
        shell("input tap " + bounds.centerX() + " " + bounds.centerY())
        return true
    }
    private fun nativeClick(description: String, timeout: Long = 10_000, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            rule.waitForIdle()
            if (tryClick(predicate)) return
            if (description.startsWith("camera")) allowCameraOnceIfRequested()
            SystemClock.sleep(100)
        }
        val ids = nodes().filter(::allowed).mapNotNull { it.viewIdResourceName }.distinct()
        error("Native action unavailable: $description; resource IDs=$ids")
    }
    private fun waitExternal() {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) { rule.waitForIdle(); if (nodes().any(::allowed)) return; SystemClock.sleep(100) }
        error("System image activity did not open")
    }
    private fun selectDownload(name: String) {
        waitExternal()
        if (nodes().any { it.packageName?.toString()?.contains("providers.media") == true }) {
            nativeClick("photo picker menu") { it.contentDescription?.toString() in setOf("More options", "更多选项") || it.viewIdResourceName?.endsWith(":id/overflow") == true }
            nativeClick("browse owned download") { it.text?.toString() in setOf("Browse…", "Browse...", "Browse", "浏览…", "浏览") }
        }
        nativeClick("search DocumentsUI") { it.viewIdResourceName?.endsWith(":id/option_menu_search") == true || it.contentDescription?.toString() in setOf("Search", "搜索") }
        val deadline = SystemClock.elapsedRealtime() + 10_000; var editor: AccessibilityNodeInfo? = null
        while (SystemClock.elapsedRealtime() < deadline && editor == null) {
            editor = nodes().firstOrNull { allowed(it) && it.isVisibleToUser && it.isEditable }
            if (editor == null) SystemClock.sleep(100)
        }
        val arguments = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) }
        check(requireNotNull(editor) { "DocumentsUI search editor unavailable" }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        shell("input keyevent 66")
        val exactName = Regex("(^|[^A-Za-z0-9._-])" + Regex.escape(name) + "($|[^A-Za-z0-9._-])")
        try {
            nativeClick("owned synthetic download", 20_000) { node ->
                !node.isEditable && (node.text?.toString() == name || exactName.containsMatchIn(node.contentDescription?.toString().orEmpty())) &&
                    generateSequence(node) { it.parent }.take(12).any { it.viewIdResourceName?.endsWith(":id/dir_list") == true }
            }
        } catch (error: IllegalStateException) {
            val owned = nodes().filter { it.text?.toString()?.contains(name) == true || it.contentDescription?.toString()?.contains(name) == true }
                .map { node -> "id=" + node.viewIdResourceName + ",editable=" + node.isEditable + ",clickable=" + node.isClickable + ",parents=" + generateSequence(node) { it.parent }.take(12).map { it.viewIdResourceName }.toList() }
            throw IllegalStateException("Owned download accessibility metadata: " + owned, error)
        }
    }
    private fun backToApp() {
        repeat(4) {
            if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName) return
            shell("input keyevent 4"); SystemClock.sleep(600)
        }
        check(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName)
    }
    private fun allowCameraOnceIfRequested() {
        repeat(5) {
            if (tryClick { it.viewIdResourceName?.endsWith(":id/permission_allow_one_time_button") == true }) SystemClock.sleep(500)
            else if (tryClick { it.packageName?.toString() == "com.android.camera2" && it.text?.toString() in setOf("Not now", "No thanks", "不用了", "暂不") }) SystemClock.sleep(500)
            else return
        }
    }
    private fun shell(command: String): String = runBlocking {
        // Stream the script to sh: UiAutomationConnection executes command strings with Runtime.exec.
        // API contract: https://developer.android.com/reference/android/app/UiAutomation#executeShellCommandRwe(java.lang.String)
        val pipes = instrumentation.uiAutomation.executeShellCommandRwe("sh")
        val stdout = async(Dispatchers.IO) { android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() } }
        val stderr = async(Dispatchers.IO) { android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader().use { it.readText() } }
        withContext(Dispatchers.IO) {
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use {
                it.write(command + "\nnativeStatus=\$?\nprintf '\\nFINEXY_NATIVE_STATUS:%s\\n' \"\$nativeStatus\"\nexit \"\$nativeStatus\"\n")
            }
        }
        val output = stdout.await(); val errors = stderr.await()
        val marker = "\nFINEXY_NATIVE_STATUS:"; val boundary = output.lastIndexOf(marker)
        check(boundary >= 0) { "Native shell result incomplete" }
        check(output.substring(boundary + marker.length).trim().toInt() == 0) { "Native shell command failed: " + errors.take(200) }
        output.substring(0, boundary)
    }
}
