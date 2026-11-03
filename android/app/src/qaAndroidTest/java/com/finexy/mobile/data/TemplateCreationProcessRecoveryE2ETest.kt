package com.finexy.mobile.data

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The QA witness delegates to the production SyncWorker in a new process. */
@RunWith(AndroidJUnit4::class)
class TemplateCreationProcessRecoveryE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun arguments(): Pair<String, String> {
        check(context.packageName == SyncRecoveryProbeWorker.QA_PACKAGE)
        val args = InstrumentationRegistry.getArguments()
        val namespace = requireNotNull(args.getString("finexy.recovery.namespace"))
        SyncRecoveryProbeWorker.evidenceFile(context, namespace)
        val url = requireNotNull(args.getString("finexy.recovery.url")).toHttpUrl()
        require(url.scheme == "http" && url.host == "10.0.2.2" && url.port == 18117 && url.encodedPath == "/" &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        return url.toString().trimEnd('/') to namespace
    }

    @Test fun prepareLostCreationResponseAndConfirmedSnapshots() = runBlocking<Unit> {
        val (url, namespace) = arguments()
        require(!SyncRecoveryProbeWorker.evidenceFile(context, namespace).exists() && context.getSharedPreferences(namespace, 0).all.isEmpty())
        val store = SecureStore(context, namespace)
        val client = OkHttpClient()
        var databaseName: String? = null
        var prepared = false
        try {
            val suffix = namespace.removePrefix(SyncRecoveryProbeWorker.NAMESPACE_PREFIX)
            val username = "tpl$suffix"
            val password = UUID.randomUUID().toString()
            val categories = JSONArray().put(JSONObject().put("name", "Parent").put("type", 2).put("icon", "1").put("color", "000000")
                .put("subCategories", JSONArray().put(JSONObject().put("name", "Leaf").put("type", 2).put("icon", "1").put("color", "000000"))))
            val registration = JSONObject().put("username", username).put("password", password).put("email", "$username@example.invalid")
                .put("nickname", "Synthetic worker").put("language", "zh-Hans").put("defaultCurrency", "CNY").put("categories", categories)
            client.newCall(Request.Builder().url("$url/api/register.json").post(registration.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                check(it.isSuccessful && JSONObject(it.body!!.string()).get("success") == true) { "Isolated registration failed" }
            }
            store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            store.put("local_mode", "false", durable = true)
            val api = FinexyApi(store)
            api.requireDurableTemplateCreation(1); api.requireDurableTemplateCreation(2)
            val identity = LedgerScope.identity(url, store.get(FinexyApi.KEY_TOKEN), false)
            databaseName = LedgerScope.databaseName(store, identity)
            require(databaseName != "finexy.db" && databaseName.startsWith("finexy-"))
            val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
            UserPreferenceStore(store, databaseName).save(UserPreferences(autoUpdateExchangeRatesData = false))
            api.createAccount(AccountDraft("Default sentinel", 1, "CNY", 5000))
            val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Confirmed templates", "isolated")
            val other = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Draft-only ledger", "isolated")
            val account = api.createAccount(AccountDraft("Template source", 1, "CNY", 9000)).single()
            api.moveAccountToLedger(account.id, ledger.id)
            val category = api.parseCategoryResponse(api.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }
            repository.replaceLedgers(api.listLedgers())
            val baseline = financialDigest(api, ledger.id)
            val defaultBaseline = financialDigest(api, 0)
            val template = TemplateEntity(0, "Original ordinary", 3, category.id, account.id, -1234, "confirmed original", hideAmount = true, ledgerId = ledger.id)
            val draft = repository.saveTemplateDraft(ledger.id, 1, 0, "{\"name\":\"Original ordinary\",\"amount\":\"12.34\"}")
            val write = repository.enqueueTemplateCreation(draft, api.templatePayload(template).toString())
            var receipt: TemplateCreationReceipt? = null
            val result = TemplateCreationDelivery(repository) {
                receipt = api.createTemplate(it)
                throw IOException("Injected response loss after dedicated endpoint committed")
            }.push()
            assertEquals(1, result.errors.size); assertTrue(requireNotNull(receipt).id > 0)
            val newer = repository.saveTemplateDraft(ledger.id, 1, 0, "{\"name\":\"Later unsaved confirmation\",\"amount\":\"99.\"}", draft)
            val schedule = template.copy(name = "Original schedule", templateType = 2, scheduledFrequencyType = 3,
                scheduledFrequency = "0", scheduledStartDate = "2026-10-05", utcOffset = 480)
            val scheduleDraft = repository.saveTemplateDraft(ledger.id, 2, 0, "{\"name\":\"Original schedule\",\"frequency\":\"0\"}")
            repository.enqueueTemplateCreation(scheduleDraft, api.templatePayload(schedule).toString())
            val otherDraft = repository.saveTemplateDraft(other.id, 1, 0, "{\"name\":\"Not confirmed\",\"amount\":\"1.\"}")
            store.put("recovery_identity", identity, durable = true)
            store.put("template_ledger", ledger.id.toString(), durable = true)
            store.put("template_other_ledger", other.id.toString(), durable = true)
            store.put("template_server_id", requireNotNull(receipt).id.toString(), durable = true)
            store.put("template_key", write.clientSessionId, durable = true)
            store.put("template_snapshot_digest", digest(write.payloadJson), durable = true)
            store.put("template_new_revision", newer.revision.toString(), durable = true)
            store.put("template_new_form_digest", digest(newer.formJson), durable = true)
            store.put("template_other_draft", otherDraft.draftId, durable = true)
            store.put("template_default_baseline", defaultBaseline, durable = true)
            store.put("template_ledger_baseline", baseline, durable = true)
            val work = OneTimeWorkRequestBuilder<SyncRecoveryProbeWorker>()
                .setInputData(Data.Builder().putString(SyncWorker.KEY_IDENTITY, identity).putString(SyncWorker.KEY_STORE_NAMESPACE, namespace).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(45, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("qa-$namespace").build()
            store.put("recovery_request_id", work.id.toString(), durable = true)
            SyncRecoveryProbeWorker.prepareEvidence(context, namespace)
            val manager = WorkManager.getInstance(context)
            manager.enqueue(work).result.get(10, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(work.id).get(10, TimeUnit.SECONDS)!!.state)
            assertEquals(2, repository.templateCreations().count { it.state == TemplateCreationEntity.PENDING })
            val saved = repository.templateCreations().single { it.templateType == 1 }
            assertEquals(write.clientSessionId, saved.clientSessionId); assertEquals(write.payloadJson, saved.payloadJson)
            prepared = true
        } finally {
            databaseName?.let(FinexyDatabase::closeInstance)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            if (!prepared) cleanFixture(namespace, databaseName)
        }
    }

    @Test fun verifyWorkerReplayedOriginalAndPreservedUnconfirmedInput() = runBlocking<Unit> {
        val started = SystemClock.elapsedRealtime()
        val (url, namespace) = arguments()
        val store = SecureStore(context, namespace)
        require(store.get(FinexyApi.KEY_SERVER_URL) == url)
        val identity = requireNotNull(store.get("recovery_identity"))
        val databaseName = LedgerScope.databaseName(store, identity)
        require(databaseName != "finexy.db" && databaseName.startsWith("finexy-"))
        try {
            val witness = JSONObject(SyncRecoveryProbeWorker.evidenceFile(context, namespace).readText())
            assertTrue(witness.getInt("workerPid") > 0 && witness.getInt("workerPid") != witness.getInt("preparePid") && witness.getInt("workerPid") != Process.myPid())
            assertEquals("SUCCEEDED", witness.getString("state"))
            assertTrue(witness.getLong("start") > 0 && witness.getLong("finish") >= witness.getLong("start") && witness.getLong("finish") < started)
            val manager = WorkManager.getInstance(context)
            val work = UUID.fromString(requireNotNull(store.get("recovery_request_id")))
            var info = manager.getWorkInfoById(work).get(10, TimeUnit.SECONDS)!!
            repeat(25) { if (!info.state.isFinished) { delay(200); info = manager.getWorkInfoById(work).get(10, TimeUnit.SECONDS)!! } }
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            val repo = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
            assertEquals(SyncRunState.SUCCEEDED, repo.syncStatus()?.state); assertTrue(repo.templateCreations().isEmpty())
            val ledger = requireNotNull(store.get("template_ledger")).toLong()
            val other = requireNotNull(store.get("template_other_ledger")).toLong()
            val api = FinexyApi(store)
            val ordinary = api.parseTemplateResponse(api.listTemplates(1, ledger), ledger).single()
            assertEquals(requireNotNull(store.get("template_server_id")).toLong(), ordinary.id)
            assertEquals("Original ordinary", ordinary.name); assertEquals(-1234L, ordinary.sourceAmountMinor); assertEquals("confirmed original", ordinary.comment)
            val scheduled = api.parseScheduledTemplateList(api.listTemplates(2, ledger), ledger).single()
            assertEquals("Original schedule", scheduled.name); assertEquals("0", scheduled.scheduledFrequency); assertEquals(480, scheduled.utcOffset)
            assertEquals(1, repo.observeNormalTemplates(ledger).first().size); assertEquals(1, repo.observeScheduledTemplates(ledger).first().size)
            val newer = requireNotNull(repo.templateDraft(ledger, 1))
            assertEquals(requireNotNull(store.get("template_new_revision")).toLong(), newer.revision)
            assertEquals(store.get("template_new_form_digest"), digest(newer.formJson))
            assertNull(repo.templateDraft(ledger, 2))
            assertEquals(store.get("template_other_draft"), repo.templateDraft(other, 1)?.draftId)
            assertTrue(api.parseTemplateResponse(api.listTemplates(1, other), other).isEmpty())
            assertEquals(store.get("template_default_baseline"), financialDigest(api, 0))
            assertEquals(store.get("template_ledger_baseline"), financialDigest(api, ledger))
        } finally { cleanFixture(namespace, databaseName) }
    }

    private suspend fun financialDigest(api: FinexyApi, ledger: Long) = digest(api.listAccounts(ledger) + "\n" + api.listTransactions(ledger))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun cleanFixture(namespace: String, databaseName: String?) {
        SyncRecoveryProbeWorker.evidenceFile(context, namespace)
        WorkManager.getInstance(context).cancelAllWorkByTag("qa-$namespace").result.get(10, TimeUnit.SECONDS)
        databaseName?.let {
            require(it != "finexy.db" && it.startsWith("finexy-") && it.endsWith(".db"))
            FinexyDatabase.closeInstance(it); context.deleteDatabase(it)
        }
        context.deleteSharedPreferences(namespace)
    }
}
