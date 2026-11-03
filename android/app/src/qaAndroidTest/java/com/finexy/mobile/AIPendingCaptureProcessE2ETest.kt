package com.finexy.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.finexy.mobile.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Split around an actual external Camera activity and an externally witnessed target-process exit. */
class AIPendingCaptureProcessE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val url = "http://10.0.2.2:18141"
    private fun namespace() = requireNotNull(InstrumentationRegistry.getArguments().getString("finexy.capture.namespace")).also {
        check(context.packageName == "com.finexy.mobile.qa" && it.matches(Regex("ledger-write-recovery-[a-f0-9]{16}")))
        check(InstrumentationRegistry.getArguments().getString("finexy.ai.source.url") == url)
    }
    private fun launch(namespace: String) = ActivityScenario.launch<LedgerSwitchQaActivity>(Intent(context, LedgerSwitchQaActivity::class.java)
        .putExtra(LedgerSwitchQaActivity.RECOVERY_NAMESPACE, namespace))
    private fun repo(store: SecureStore): TransactionRepository {
        val identity = LedgerScope.identity(url, store.get(FinexyApi.KEY_TOKEN), false)
        val name = LedgerScope.databaseName(store, identity); check(name != "finexy.db")
        return TransactionRepository(context, FinexyDatabase.get(context, name), false)
    }
    private suspend fun financial(api: FinexyApi): String = aiImageDigest((api.parseAccountResponse(api.listAccounts(0)).sortedBy { it.id }
        .joinToString { "${it.id}:${it.balanceMinor}" } + "|" + api.parseTransactionResponse(api.listTransactions(0), 0).map { it.id }.sorted().joinToString()).toByteArray())
    private fun modelRequests(): Int = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("http://10.0.2.2:18144/audit").build()).execute().use {
        check(it.code == 200); JSONObject(requireNotNull(it.body).string()).getInt("requests")
    }
    private fun evidence(namespace: String) = File(context.filesDir, "capture-process/$namespace.json").also { check(it.parentFile!!.isDirectory || it.parentFile!!.mkdirs()) }

    @Test fun prepareActualCameraInForegroundWithDurableSourceOwnership() = runBlocking<Unit> {
        val namespace = namespace(); check(context.getSharedPreferences(namespace, 0).all.isEmpty())
        val scenario = launch(namespace); val store = SecureStore(context, namespace)
        val registrationName = "capture-register-${namespace.takeLast(16)}"; val registration = SecureStore(context, registrationName)
        var prepared = false
        try {
            val username = "cap${namespace.takeLast(16)}"; val password = UUID.randomUUID().toString()
            registration.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            val authentication = FinexyApi(registration)
            AccountSecurity(authentication).register(username, "$username@example.invalid", "Synthetic capture recovery", password, "CNY")
            registration.put(FinexyApi.KEY_TOKEN, authentication.login(username, password).token, durable = true)
            val api = FinexyApi(registration); api.createAccount(AccountDraft("Capture sentinel", 1, "CNY", 3456))
            val identity = LedgerScope.identity(url, registration.get(FinexyApi.KEY_TOKEN), false)
            val name = LedgerScope.databaseName(store, identity); check(name != "finexy.db")
            UserPreferenceStore(store, name).save(UserPreferences(autoUpdateExchangeRatesData = false, aiTextConsent = true, aiStructuredConsent = true, cloudSyncEnabled = false))
            loginQaThroughRealAuthScreen(rule, url, username, password, "总览")
            val repository = repo(store)
            SyncEngine(FinexyApi(store), repository, autoUpdateExchangeRates = false).sync()
            val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff227766.toInt())
            val before = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
            val draft = repository.aiReview.saveSourceImage(0, AISourceForm().changed(mapOf("sourceText" to "Synthetic camera original")).json(), before)
            store.put("capture_baseline", financial(FinexyApi(store)), durable = true)
            store.put("capture_original_hash", aiImageDigest(before), durable = true)
            store.put("capture_model_requests", modelRequests().toString(), durable = true)
            openSource(); click("拍摄票据")
            cameraClick("shutter_button")
            scenario.onActivity { assertEquals(androidx.lifecycle.Lifecycle.State.CREATED, it.lifecycle.currentState) }
            val pendingStore = AIPendingCaptureStore(context, repository.aiReview.storagePartition, 0)
            val pending = requireNotNull(pendingStore.read()); assertTrue(pending.matches(draft))
            store.put("capture_operation", pending.operationId, durable = true)
            store.put("capture_prepare_pid", Process.myPid().toString(), durable = true)
            store.put("capture_database", name, durable = true)
            WorkManager.getInstance(context).cancelAllWorkByTag("finexy-sync-${identity.take(24)}-${namespace.hashCode()}").result.get(10, TimeUnit.SECONDS)
            assertEquals(store.get("capture_baseline"), financial(FinexyApi(store)))
            assertTrue(FinexyApi(store).listLedgerAIReviewItems(0).isEmpty()); assertTrue(repository.aiReview.requests().isEmpty())
            assertEquals(store.get("capture_model_requests")?.toInt(), modelRequests())
            evidence(namespace).writeText(JSONObject().put("preparePid", Process.myPid()).put("operationId", pending.operationId)
                .put("capturePath", pendingStore.file(pending).relativeTo(context.filesDir.canonicalFile).path.replace('\\', '/')).put("cameraForeground", true).toString())
            prepared = true
        } finally { context.deleteSharedPreferences(registrationName); if (!prepared) scenario.close() }
    }

    @Test fun verifyNewProcessRecoversActualCameraWithoutSendingOrLosingOriginal() = runBlocking<Unit> {
        val namespace = namespace(); val store = SecureStore(context, namespace)
        assertNotEquals(requireNotNull(store.get("capture_prepare_pid")).toInt(), Process.myPid())
        val repository = repo(store); val pendingStore = AIPendingCaptureStore(context, repository.aiReview.storagePartition, 0)
        val operation = requireNotNull(store.get("capture_operation")); val prepared = JSONObject(evidence(namespace).readText())
        val scenario = launch(namespace)
        var completed = false
        try {
            // The cold entry may return to the source or the overview, depending on the system's result delivery.
            rule.waitUntil(30_000) { rule.onAllNodesWithText("AI 来源录入").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithText("总览").fetchSemanticsNodes().isNotEmpty() }
            if (rule.onAllNodesWithText("AI 来源录入").fetchSemanticsNodes().isEmpty()) openSource() else resumeSource()
            val pending = pendingStore.read()
            val explicitlyRecovered = pending != null
            if (pending != null) {
                assertEquals(operation, pending.operationId)
                val prior = requireNotNull(repository.aiReview.draft(0, 0)); assertTrue(pending.matches(prior))
                assertEquals(store.get("capture_original_hash"), aiImageDigest(repository.aiReview.sourceImage(prior)))
                assertTrue(pendingStore.file(pending).length() > 0)
                captureQaUiEvidence(rule, "ai-capture-process-before-explicit-recovery")
                click("重新读取拍照图片"); waitText("完成裁剪"); click("完成裁剪")
            } else if (rule.onAllNodesWithText("完成裁剪").fetchSemanticsNodes().isNotEmpty()) click("完成裁剪")
            rule.waitUntil(30_000) { rule.onAllNodesWithContentDescription("本次将发送的裁剪图片预览").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("本次将发送的裁剪图片预览").performScrollTo().assertIsDisplayed()
            val saved = requireNotNull(repository.aiReview.draft(0, 0)); val original = repository.aiReview.sourceImage(saved)
            assertEquals(operation, AISourceForm.read(saved).fields["captureOperationId"])
            assertNotEquals(store.get("capture_original_hash"), aiImageDigest(original))
            assertNull(pendingStore.read()); assertFalse(File(context.filesDir, prepared.getString("capturePath")).exists())
            assertEquals(store.get("capture_baseline"), financial(FinexyApi(store)))
            assertTrue(FinexyApi(store).listLedgerAIReviewItems(0).isEmpty()); assertTrue(repository.aiReview.requests().isEmpty())
            assertEquals(store.get("capture_model_requests")?.toInt(), modelRequests())
            captureQaUiEvidence(rule, "ai-capture-process-restored")
            prepared.put("verifyPid", Process.myPid()).put("passed", true).put("explicitlyRecovered", explicitlyRecovered)
                .put("encryptedOriginal", true).put("encryptedOriginalSHA256", aiImageDigest(original))
                .put("noFinancialWrites", true).put("pendingCleaned", true)
            evidence(namespace).writeText(prepared.toString()); completed = true
        } finally {
            scenario.close()
            if (completed) {
                FinexyDatabase.closeInstance(requireNotNull(store.get("capture_database")))
                context.deleteDatabase(requireNotNull(store.get("capture_database"))); context.deleteSharedPreferences(namespace)
            }
        }
    }
    private fun waitText(text: String) { rule.waitUntil(30_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun click(text: String) {
        val node = rule.onNode(hasText(text) and hasClickAction()); var parent = node.fetchSemanticsNode().parent; var scroll = false
        while (parent != null) { if (parent.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) scroll = true; parent = parent.parent }
        if (scroll) node.performScrollTo(); node.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun openSource() { waitText("设置"); click("设置"); click("AI 与票据识别"); waitText("AI 来源录入"); resumeSource() }
    private fun resumeSource() {
        rule.waitUntil(30_000) { rule.onAllNodesWithText("继续编辑来源").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodes(hasText("描述或粘贴票据文字") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodesWithText("继续编辑来源").fetchSemanticsNodes().isNotEmpty()) click("继续编辑来源")
        rule.waitForIdle()
    }
    private fun cameraClick(id: String) {
        val info = instrumentation.uiAutomation.serviceInfo; val previous = info.flags
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS; instrumentation.uiAutomation.serviceInfo = info
        try {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                // Preparing the journal crosses IO before Compose launches Camera. Pump pending frames
                // while waiting for a system window; a sleeping test clock would prevent that effect.
                rule.waitForIdle()
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo) { nodes += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
                instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
                nodes.firstOrNull { it.packageName?.toString() == "com.android.camera2" && it.viewIdResourceName?.endsWith(":id/$id") == true && it.isVisibleToUser && it.isEnabled }?.let {
                    if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                }
                nodes.firstOrNull { it.packageName?.toString() in setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller") && it.viewIdResourceName?.endsWith(":id/permission_allow_one_time_button") == true }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                nodes.firstOrNull { it.packageName?.toString() == "com.android.camera2" && it.text?.toString() in setOf("Not now", "No thanks", "不用了", "暂不") }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                SystemClock.sleep(100)
            }
            error("Actual camera shutter unavailable")
        } finally { val restored = instrumentation.uiAutomation.serviceInfo; restored.flags = previous; instrumentation.uiAutomation.serviceInfo = restored }
    }
}
