package com.finexy.mobile

import android.app.job.JobScheduler
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.finexy.mobile.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Mandatory split phases: UI prepare -> process exit -> real Worker -> UI verify. */
class TemplateEditorProcessE2ETest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun namespace(): String = requireNotNull(InstrumentationRegistry.getArguments().getString("finexy.recovery.namespace")).also {
        SyncRecoveryProbeWorker.evidenceFile(context, it)
        require(InstrumentationRegistry.getArguments().getString("finexy.recovery.url") == URL)
    }
    private fun launch(namespace: String) = ActivityScenario.launch<LedgerSwitchQaActivity>(
        Intent(context, LedgerSwitchQaActivity::class.java).putExtra(LedgerSwitchQaActivity.RECOVERY_NAMESPACE, namespace))
    private fun repo(store: SecureStore): TransactionRepository {
        val identity = LedgerScope.identity(URL, store.get(FinexyApi.KEY_TOKEN), false)
        val name = LedgerScope.databaseName(store, identity)
        require(name != "finexy.db" && name.startsWith("finexy-"))
        return TransactionRepository(context, FinexyDatabase.get(context, name), false)
    }

    @Test fun prepareRawFormsAndConfirmedCreationThroughProductionPages() = runBlocking<Unit> {
        val namespace = namespace()
        require(context.getSharedPreferences(namespace, 0).all.isEmpty() && !SyncRecoveryProbeWorker.evidenceFile(context, namespace).exists())
        val registerNamespace = "template-editor-register-${namespace.takeLast(16)}"
        val registerStore = SecureStore(context, registerNamespace)
        var prepared = false
        val scenario = launch(namespace)
        val store = SecureStore(context, namespace)
        try {
            registerStore.put(FinexyApi.KEY_SERVER_URL, URL, durable = true)
            val username = "uiedit${namespace.takeLast(16)}"
            val password = UUID.randomUUID().toString()
            val registrationApi = FinexyApi(registerStore)
            AccountSecurity(registrationApi).register(username, "$username@example.invalid", "Synthetic UI recovery", password, "CNY")
            registerStore.put(FinexyApi.KEY_TOKEN, registrationApi.login(username, password).token, durable = true)
            val api = FinexyApi(registerStore)
            api.createAccount(AccountDraft("Default sentinel", 1, "CNY", 5432))
            val ledgerA = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Process ledger A", "isolated")
            val ledgerB = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Process ledger B", "isolated")
            suspend fun accounts(label: String, ledger: Long): Pair<Long, Long> {
                val source = api.createAccount(AccountDraft("Process source $label", 1, "USD", 50000)).single()
                val destination = api.createAccount(AccountDraft("Process destination $label", 1, "CNY", 70000)).single()
                api.moveAccountToLedger(source.id, ledger); api.moveAccountToLedger(destination.id, ledger)
                return source.id to destination.id
            }
            val a = accounts("A", ledgerA.id); val b = accounts("B", ledgerB.id)
            val parent = api.createCategory(CategoryDraft("Process transfer", 3))
            val category = api.createCategory(CategoryDraft("Process leaf", 3, parent.id))
            val tag = api.createTag("Recovery private")
            val expectedIdentity = LedgerScope.identity(URL, registerStore.get(FinexyApi.KEY_TOKEN), false)
            val database = LedgerScope.databaseName(store, expectedIdentity)
            require(database != "finexy.db")
            store.put("ui_fixture_identity", expectedIdentity, durable = true)
            store.put("ui_fixture_database", database, durable = true)
            UserPreferenceStore(store, database).save(UserPreferences(autoUpdateExchangeRatesData = false, cloudSyncEnabled = false))
            loginQaThroughRealAuthScreen(rule, URL, username, password, readyText = "总览")
            val repository = repo(store)
            SyncEngine(FinexyApi(store), repository, autoUpdateExchangeRates = false).sync()
            waitText("Process ledger A")
            for (ledger in listOf(0L, ledgerA.id, ledgerB.id)) store.put("ui_baseline_$ledger", financialDigest(api, ledger), durable = true)

            openNormal("Process ledger A")
            waitEnabled("新增模板"); rule.onNodeWithText("新增模板").performScrollTo().performClick()
            fillNormal("A", a, category.id, "  Raw ordinary A  ", "1..", "55.")
            field("备注（可选）", "Raw note A")
            rule.onNodeWithContentDescription("切换标签：Recovery private").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
            hideAmount()
            saved(); rule.onNodeWithText("预览").assertIsNotEnabled()
            rule.onNodeWithText("取消").performClick()
            val ordinary = requireNotNull(repository.templateDraft(ledgerA.id, 1))
            assertEquals("1..", templateFormFields(ordinary.formJson)["amount"])
            assertEquals("55.", templateFormFields(ordinary.formJson)["destinationAmount"])
            store.put("ui_ordinary_draft", ordinary.draftId, durable = true)
            store.put("ui_ordinary_revision", ordinary.revision.toString(), durable = true)
            store.put("ui_ordinary_hash", digest(ordinary.formJson), durable = true)
            captureQaUiEvidence(rule, "template-process-ordinary-prepared")

            returnHome(); openSchedule("Process ledger A")
            waitDescription("周期计划：新增"); rule.onNodeWithContentDescription("周期计划：新增").performClick()
            field("计划名称", "Raw schedule A")
            rule.onAllNodesWithText("转账").filterToOne(hasClickAction()).performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
            field("转出金额", "2.."); field("转入金额（币种不同时必填）", "44.")
            rule.onNodeWithContentDescription("周期来源账户：Process source A · USD · ${a.first}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
            rule.onNodeWithContentDescription("周期转入账户：Process destination A · CNY · ${a.second}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
            rule.onNodeWithText("Process leaf").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
            rule.onNodeWithText("每N天").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
            field("间隔天数", "7"); field("计划时区（±HH:MM）", "+08:")
            field("开始日期（可选，YYYY-MM-DD）", "2026-10-"); field("结束日期（可选）", "2026-12-")
            field("备注（可选）", "Raw calendar A"); hideAmount()
            saved(); rule.onNodeWithText("预览").assertIsNotEnabled(); rule.onNodeWithText("取消").performClick()
            val scheduled = requireNotNull(repository.templateDraft(ledgerA.id, 2))
            store.put("ui_schedule_draft", scheduled.draftId, durable = true)
            store.put("ui_schedule_revision", scheduled.revision.toString(), durable = true)
            store.put("ui_schedule_hash", digest(scheduled.formJson), durable = true)
            assertTrue(repository.templateCreations().isEmpty())
            assertTrue(api.parseTemplateResponse(api.listTemplates(1, ledgerA.id), ledgerA.id).isEmpty())
            assertTrue(api.parseTemplateResponse(api.listTemplates(2, ledgerA.id), ledgerA.id).isEmpty())

            returnHome(); openNormal("Process ledger B")
            waitEnabled("新增模板"); rule.onNodeWithText("新增模板").performScrollTo().performClick()
            fillNormal("B", b, category.id, "Confirmed only in B", "12.34", "90.12")
            field("备注（可选）", "Original confirmed content")
            rule.onNodeWithContentDescription("切换标签：Recovery private").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
            hideAmount(); waitEnabled("预览"); rule.onNodeWithText("预览").performClick(); waitText("确认模板内容")
            assertTrue(repository.templateCreations().isEmpty())
            assertTrue(api.parseTemplateResponse(api.listTemplates(1, ledgerB.id), ledgerB.id).isEmpty())
            rule.onNodeWithText("确认保存").performClick(); waitEnabled("重试原请求")
            rule.onNodeWithText("新增模板").assertIsNotEnabled(); rule.onNodeWithText("丢弃草稿").assertDoesNotExist()
            val write = repository.templateCreations().single()
            assertEquals(ledgerB.id, write.ledgerId); assertEquals(TemplateCreationEntity.PENDING, write.state)
            val created = api.parseTemplateResponse(api.listTemplates(1, ledgerB.id), ledgerB.id).single()
            store.put("ui_ledger_a", ledgerA.id.toString(), durable = true); store.put("ui_ledger_b", ledgerB.id.toString(), durable = true)
            store.put("ui_source_a", a.first.toString(), durable = true); store.put("ui_destination_a", a.second.toString(), durable = true)
            store.put("ui_source_b", b.first.toString(), durable = true); store.put("ui_destination_b", b.second.toString(), durable = true)
            store.put("ui_category", category.id.toString(), durable = true); store.put("ui_tag", tag.id.toString(), durable = true)
            store.put("ui_original_id", created.id.toString(), durable = true); store.put("ui_original_key", write.clientSessionId, durable = true)
            store.put("ui_original_hash", digest(write.payloadJson), durable = true)
            captureQaUiEvidence(rule, "template-process-confirmed-response-lost")
            assertFinance(store, api)
            val manager = WorkManager.getInstance(context)
            manager.cancelAllWorkByTag("finexy-sync-${expectedIdentity.take(24)}-${namespace.hashCode()}").result.get(10, TimeUnit.SECONDS)
            val work = OneTimeWorkRequestBuilder<SyncRecoveryProbeWorker>()
                .setInputData(Data.Builder().putString(SyncWorker.KEY_IDENTITY, expectedIdentity).putString(SyncWorker.KEY_STORE_NAMESPACE, namespace).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(45, TimeUnit.SECONDS).addTag("qa-$namespace").build()
            SyncRecoveryProbeWorker.prepareEvidence(context, namespace)
            store.put("ui_work_id", work.id.toString(), durable = true)
            manager.enqueue(work).result.get(10, TimeUnit.SECONDS)
            require(Build.VERSION.SDK_INT >= 34) { "This process host targets namespaced WorkManager jobs on API 34+" }
            val jobNamespace = "androidx.work.systemjobscheduler"
            val scheduler = requireNotNull(context.getSystemService(JobScheduler::class.java)).forNamespace(jobNamespace)
            var job = scheduler.allPendingJobs.singleOrNull { it.extras.getString("EXTRA_WORK_SPEC_ID") == work.id.toString() }
            repeat(25) { if (job == null) { delay(200); job = scheduler.allPendingJobs.singleOrNull { it.extras.getString("EXTRA_WORK_SPEC_ID") == work.id.toString() } } }
            val ownJob = requireNotNull(job) { "Owned WorkManager job not scheduled" }
            require(ownJob.service.packageName == context.packageName && ownJob.service.className.endsWith("SystemJobService"))
            val metadata = File(File(context.filesDir, "template-editor-recovery"), "$namespace.json")
            require(metadata.parentFile!!.mkdirs() || metadata.parentFile!!.isDirectory)
            metadata.writeText(JSONObject().put("preparePid", Process.myPid()).put("jobId", ownJob.id)
                .put("jobNamespace", jobNamespace).put("workId", work.id.toString()).toString())
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(work.id).get(10, TimeUnit.SECONDS)!!.state)
            assertEquals("PREPARED", JSONObject(SyncRecoveryProbeWorker.evidenceFile(context, namespace).readText()).getString("state"))
            prepared = true
        } finally {
            scenario.close(); context.deleteSharedPreferences(registerNamespace)
            if (!prepared) cleanup(store)
        }
    }

    @Test fun verifyProductionPagesInFreshProcessAfterWorkerRecovery() = runBlocking<Unit> {
        val started = SystemClock.elapsedRealtime()
        val namespace = namespace(); val store = SecureStore(context, namespace)
        val witness = JSONObject(SyncRecoveryProbeWorker.evidenceFile(context, namespace).readText())
        assertEquals("SUCCEEDED", witness.getString("state"))
        assertNotEquals(witness.getInt("preparePid"), Process.myPid()); assertNotEquals(witness.getInt("workerPid"), Process.myPid())
        assertNotEquals(witness.getInt("preparePid"), witness.getInt("workerPid")); assertTrue(witness.getLong("finish") < started)
        val workId = UUID.fromString(requireNotNull(store.get("ui_work_id")))
        assertEquals(WorkInfo.State.SUCCEEDED, WorkManager.getInstance(context).getWorkInfoById(workId).get(10, TimeUnit.SECONDS)!!.state)
        val scenario = launch(namespace)
        try {
            require(store.get(FinexyApi.KEY_SERVER_URL) == URL)
            val repository = repo(store); val api = FinexyApi(store)
            assertTrue(repository.templateCreations().isEmpty())
            val a = requireNotNull(store.get("ui_ledger_a")).toLong(); val b = requireNotNull(store.get("ui_ledger_b")).toLong()
            val ordinary = requireNotNull(repository.templateDraft(a, 1)); val scheduled = requireNotNull(repository.templateDraft(a, 2))
            assertEquals(store.get("ui_ordinary_draft"), ordinary.draftId); assertEquals(store.get("ui_ordinary_revision")!!.toLong(), ordinary.revision)
            assertEquals(store.get("ui_ordinary_hash"), digest(ordinary.formJson))
            assertEquals(store.get("ui_schedule_draft"), scheduled.draftId); assertEquals(store.get("ui_schedule_revision")!!.toLong(), scheduled.revision)
            assertEquals(store.get("ui_schedule_hash"), digest(scheduled.formJson)); assertNull(repository.templateDraft(b, 1))
            val posted = api.parseTemplateResponse(api.listTemplates(1, b), b).single()
            assertEquals(store.get("ui_original_id")!!.toLong(), posted.id); assertEquals("Confirmed only in B", posted.name)
            assertEquals(1234L, posted.sourceAmountMinor); assertEquals(9012L, posted.destinationAmountMinor)
            assertEquals(store.get("ui_source_b")!!.toLong(), posted.sourceAccountId)
            assertEquals(store.get("ui_destination_b")!!.toLong(), posted.destinationAccountId)
            assertEquals("Original confirmed content", posted.comment)
            assertEquals("[\"${store.get("ui_tag")}\"]", posted.tagIdsJson); assertTrue(posted.hideAmount)
            waitText("总览"); rule.onNodeWithText("总览", substring = false).performClick(); waitText("Process ledger A")
            rule.onNode(hasText("个人账本", substring = false) and hasClickAction()).performClick()
            rule.onNodeWithText("设置", substring = false).performClick()
            rule.onNodeWithText("流水模板 · 当前账本").performScrollTo().performClick()
            waitEnabled("新增模板"); rule.onNodeWithText("继续编辑上次草稿").assertDoesNotExist()
            returnHome(); openNormal("Process ledger A")
            waitEnabled("继续编辑上次草稿"); rule.onNodeWithText("继续编辑上次草稿").performScrollTo().performClick()
            rule.onNode(hasText("模板名称") and hasSetTextAction()).assertTextContains("  Raw ordinary A  ")
            rule.onNode(hasText("金额") and hasSetTextAction()).performScrollTo().assertTextContains("1..")
            rule.onNode(hasText("转入金额") and hasSetTextAction()).performScrollTo().assertTextContains("55.")
            rule.onNodeWithContentDescription("模板来源账户：Process source A · USD · ${store.get("ui_source_a")}").performScrollTo().assertIsSelected()
            rule.onNodeWithContentDescription("模板转入账户：Process destination A · CNY · ${store.get("ui_destination_a")}").performScrollTo().assertIsSelected()
            rule.onNodeWithContentDescription("模板分类：Process transfer / Process leaf · ${store.get("ui_category")}").performScrollTo().assertIsSelected()
            rule.onNodeWithContentDescription("切换标签：Recovery private").performScrollTo().assertIsSelected()
            rule.onNode(hasText("备注（可选）") and hasSetTextAction()).performScrollTo().assertTextContains("Raw note A")
            hiddenCheckbox().performScrollTo().assertIsOn(); rule.onNodeWithText("预览").assertIsNotEnabled()
            captureQaUiEvidence(rule, "template-process-ordinary-restored")
            rule.onNodeWithText("取消").performClick(); returnHome(); openSchedule("Process ledger A")
            waitEnabled("继续编辑上次草稿"); rule.onNodeWithText("继续编辑上次草稿").performScrollTo().performClick()
            rule.onNode(hasText("计划名称") and hasSetTextAction()).assertTextContains("Raw schedule A")
            rule.onNode(hasText("转出金额") and hasSetTextAction()).performScrollTo().assertTextContains("2..")
            rule.onNode(hasText("转入金额（币种不同时必填）") and hasSetTextAction()).performScrollTo().assertTextContains("44.")
            rule.onNodeWithContentDescription("周期来源账户：Process source A · USD · ${store.get("ui_source_a")}").performScrollTo().assertIsSelected()
            rule.onNodeWithContentDescription("周期转入账户：Process destination A · CNY · ${store.get("ui_destination_a")}").performScrollTo().assertIsSelected()
            rule.onNodeWithText("Process leaf").performScrollTo().assertIsSelected()
            rule.onNodeWithText("每N天").performScrollTo().assertIsSelected()
            rule.onNode(hasText("间隔天数") and hasSetTextAction()).performScrollTo().assertTextContains("7")
            rule.onNode(hasText("计划时区（±HH:MM）") and hasSetTextAction()).performScrollTo().assertTextContains("+08:")
            rule.onNode(hasText("开始日期（可选，YYYY-MM-DD）") and hasSetTextAction()).performScrollTo().assertTextContains("2026-10-")
            rule.onNode(hasText("结束日期（可选）") and hasSetTextAction()).performScrollTo().assertTextContains("2026-12-")
            rule.onNode(hasText("备注（可选）") and hasSetTextAction()).performScrollTo().assertTextContains("Raw calendar A")
            hiddenCheckbox().performScrollTo().assertIsOn(); rule.onNodeWithText("预览").assertIsNotEnabled()
            captureQaUiEvidence(rule, "template-process-schedule-restored")
            rule.onNodeWithText("取消").performClick(); returnHome(); openNormal("Process ledger B")
            waitEnabled("新增模板"); waitText("Confirmed only in B")
            rule.onNodeWithText("重试原请求").assertDoesNotExist(); rule.onNodeWithText("继续编辑上次草稿").assertDoesNotExist()
            captureQaUiEvidence(rule, "template-process-original-created-once")
            assertTrue(api.parseTemplateResponse(api.listTemplates(1, a), a).isEmpty())
            assertTrue(api.parseTemplateResponse(api.listTemplates(2, a), a).isEmpty())
            assertTrue(api.parseTemplateResponse(api.listTemplates(2, b), b).isEmpty())
            assertFinance(store, api)
            assertEquals(store.get("ui_ordinary_hash"), digest(requireNotNull(repository.templateDraft(a, 1)).formJson))
            assertEquals(store.get("ui_schedule_hash"), digest(requireNotNull(repository.templateDraft(a, 2)).formJson))
            File(File(context.filesDir, "template-editor-recovery"), "$namespace-verified.json").writeText(
                JSONObject().put("verifyPid", Process.myPid()).put("started", started).put("draftsRestored", true).put("financesUnchanged", true).toString())
        } finally { scenario.close(); cleanup(store) }
    }

    private fun hiddenCheckbox() = rule.onNode(isToggleable() and
        (hasContentDescription("隐藏金额") or hasAnySibling(hasText("隐藏金额"))), useUnmergedTree = true)
    private fun hideAmount() { hiddenCheckbox().performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsOn() }
    private fun saved() { waitText("草稿已保存在本机，尚未提交") }
    private fun field(label: String, value: String) {
        rule.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }
    private fun fillNormal(label: String, ids: Pair<Long, Long>, category: Long, name: String, amount: String, destination: String) {
        field("模板名称", name)
        rule.onAllNodesWithText("转账").filterToOne(hasClickAction()).performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        field("金额", amount)
        rule.onNodeWithContentDescription("模板来源账户：Process source $label · USD · ${ids.first}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        rule.onNodeWithContentDescription("模板转入账户：Process destination $label · CNY · ${ids.second}").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        field("转入金额", destination)
        rule.onNodeWithContentDescription("模板分类：Process transfer / Process leaf · $category").performScrollTo().performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
    }
    private fun returnHome() {
        rule.onNodeWithText("返回设置").performScrollTo().performClick()
        rule.onNodeWithText("总览", substring = false).performClick(); waitText("Process ledger A")
    }
    private fun openNormal(ledger: String) {
        rule.onNodeWithContentDescription("切换到账本：$ledger").performClick()
        rule.onNodeWithText("设置", substring = false).performClick()
        rule.onNodeWithText("流水模板 · 当前账本").performScrollTo().performClick(); waitText(ledger)
    }
    private fun openSchedule(ledger: String) {
        rule.onNodeWithContentDescription("切换到账本：$ledger").performClick()
        rule.onNodeWithText("设置", substring = false).performClick()
        rule.onNodeWithContentDescription("打开周期计划管理页").performScrollTo().performClick(); waitText("周期计划")
    }
    private fun waitText(text: String) { rule.waitUntil(30000) { rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitEnabled(text: String) { rule.waitUntil(30000) { rule.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
    private fun waitDescription(text: String) { rule.waitUntil(30000) { rule.onAllNodes(hasContentDescription(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
    private suspend fun financialDigest(api: FinexyApi, ledger: Long) = digest(api.listAccounts(ledger) + "\n" + api.listTransactions(ledger))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private suspend fun assertFinance(store: SecureStore, api: FinexyApi) {
        for (ledger in listOf(0L, requireNotNull(store.get("ui_ledger_a")).toLong(), requireNotNull(store.get("ui_ledger_b")).toLong()))
            assertEquals(store.get("ui_baseline_$ledger"), financialDigest(api, ledger))
    }
    private fun cleanup(store: SecureStore) {
        SyncRecoveryProbeWorker.evidenceFile(context, store.namespace)
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag("qa-${store.namespace}").result.get(10, TimeUnit.SECONDS)
        store.get("ui_fixture_identity")?.let { identity ->
            require(identity.matches(Regex("[a-f0-9]{64}")))
            manager.cancelAllWorkByTag("finexy-sync-${identity.take(24)}-${store.namespace.hashCode()}").result.get(10, TimeUnit.SECONDS)
            val database = requireNotNull(store.get("ui_fixture_database"))
            require(database == LedgerScope.databaseName(store, identity) && database != "finexy.db" && database.startsWith("finexy-"))
            FinexyDatabase.closeInstance(database); context.deleteDatabase(database)
        }
        context.deleteSharedPreferences(store.namespace)
    }
    companion object { private const val URL = "http://10.0.2.2:18123" }
}
