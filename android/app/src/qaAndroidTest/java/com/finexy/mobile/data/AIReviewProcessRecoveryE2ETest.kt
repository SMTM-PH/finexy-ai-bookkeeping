package com.finexy.mobile.data

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Two separate instrumentation phases around a new-PID production SyncWorker. */
@RunWith(AndroidJUnit4::class)
class AIReviewProcessRecoveryE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = OkHttpClient()
    private fun original(color: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff337700.toInt() + color)
        return java.io.ByteArrayOutputStream().use { out ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)); bitmap.recycle(); out.toByteArray()
        }
    }
    private fun arguments(): Pair<String, String> {
        check(context.packageName == SyncRecoveryProbeWorker.QA_PACKAGE)
        val args = InstrumentationRegistry.getArguments()
        val namespace = requireNotNull(args.getString("finexy.recovery.namespace"))
        SyncRecoveryProbeWorker.evidenceFile(context, namespace)
        val url = requireNotNull(args.getString("finexy.recovery.url")).toHttpUrl()
        require(url.scheme == "http" && url.host == "10.0.2.2" && url.port == 18142 && url.encodedPath == "/" &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        return url.toString().trimEnd('/') to namespace
    }
    private fun block(url: String, path: String) {
        client.newCall(Request.Builder().url("$url/__qa/control").post(JSONObject().put("blockedPath", path).toString()
            .toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(204, it.code) }
    }
    private fun source(ledger: Long, text: String) = JSONObject().put("ledgerId", ledger.toString())
        .put("clientSessionId", UUID.randomUUID().toString()).put("sourceType", 1).put("sourceText", text).put("failureReason", "").toString()
    private fun confirmation(row: LedgerAIReviewItem, account: Long, category: Long, amount: Long) = JSONObject()
        .put("ledgerId", row.ledgerId.toString()).put("id", row.id.toString()).put("transaction", JSONObject()
            .put("ledgerId", row.ledgerId.toString()).put("clientSessionId", UUID.randomUUID().toString()).put("type", 3)
            .put("time", System.currentTimeMillis() / 1000).put("utcOffset", 480).put("categoryId", category.toString())
            .put("sourceAccountId", account.toString()).put("destinationAccountId", "0").put("sourceAmount", amount)
            .put("destinationAmount", 0).put("hideAmount", true).put("tagIds", JSONArray()).put("pictureIds", JSONArray())
            .put("comment", "Synthetic AI process once")).toString()
    private fun scoped(store: AIReviewPersistence, id: String) = object : AIReviewRequestStore by store {
        override suspend fun requests() = store.requests().filter { it.requestId == id }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test fun prepareLostCreateLostConfirmAndKnownReceipt() = runBlocking<Unit> {
        val (url, namespace) = arguments()
        require(!SyncRecoveryProbeWorker.evidenceFile(context, namespace).exists() && context.getSharedPreferences(namespace, 0).all.isEmpty())
        val store = SecureStore(context, namespace)
        var databaseName: String? = null
        var prepared = false
        try {
            val suffix = namespace.removePrefix(SyncRecoveryProbeWorker.NAMESPACE_PREFIX)
            val username = "ai$suffix"; val password = UUID.randomUUID().toString()
            store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            AccountSecurity(FinexyApi(store)).register(username, "$username@example.invalid", "Synthetic AI Worker", password, "CNY")
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            store.put("local_mode", "false", durable = true)
            val api = FinexyApi(store)
            val identity = LedgerScope.identity(url, store.get(FinexyApi.KEY_TOKEN), false)
            databaseName = LedgerScope.databaseName(store, identity)
            require(databaseName != "finexy.db" && databaseName.startsWith("finexy-"))
            val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
            val persistence = repository.aiReview
            UserPreferenceStore(store, databaseName).save(UserPreferences(autoUpdateExchangeRatesData = false))
            val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic AI process", "isolated")
            val other = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic draft only", "isolated")
            val accountPayload = AccountDraft("Synthetic AI account", 1, "CNY", 10000).toCreatePayload()
                .put("ledgerId", ledger.id.toString()).put("balanceTime", 946684800)
            val accountId = JSONObject(api.request("v1/accounts/add.json", accountPayload.toString())).getJSONObject("result").getString("id").toLong()
            val category = api.parseCategoryResponse(api.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }.id
            val unknown = api.createLedgerAIReviewItem(ledger.id, source(ledger.id, "Synthetic unknown confirmation"))
            val known = api.createLedgerAIReviewItem(ledger.id, source(ledger.id, "Synthetic known confirmation"))
            val raw = "{\"amount\":\"1.+\",\"timeZone\":\"Asia/Shanghai\"}"
            val sourceDraft = persistence.saveSourceImage(ledger.id, raw, original(1))
            val sourceWrite = persistence.enqueue(sourceDraft, "create", JSONObject(source(ledger.id, "Synthetic queued source")).put("sourceType", 2).toString())
            assertArrayEquals(original(1), persistence.requestSourceImage(sourceWrite))
            val confirmDraft = persistence.saveDraft(ledger.id, unknown.id, raw)
            val confirmWrite = persistence.enqueue(confirmDraft, "confirm", confirmation(unknown, accountId, category, -123))
            val knownDraft = persistence.saveDraft(ledger.id, known.id, raw)
            val knownWrite = persistence.enqueue(knownDraft, "confirm", confirmation(known, accountId, category, -77))
            block(url, "/api/v1/ai/review/create.json")
            val lostCreate = AIReviewDelivery.forApi(scoped(persistence, sourceWrite.requestId), api).push()
            assertEquals(1, lostCreate.errors.size); assertEquals(0, lostCreate.posted)
            block(url, "/api/v1/ai/review/confirm.json")
            val lostConfirm = AIReviewDelivery.forApi(scoped(persistence, confirmWrite.requestId), api).push()
            assertEquals(1, lostConfirm.errors.size); assertEquals(0, lostConfirm.posted)
            block(url, "/api/v1/ai/review/get.json")
            val knownReceipt = AIReviewDelivery.forApi(scoped(persistence, knownWrite.requestId), api).push()
            assertEquals(1, knownReceipt.errors.size); assertEquals(1, knownReceipt.posted)
            block(url, "")
            val rows = api.listLedgerAIReviewItems(ledger.id)
            val created = rows.single { it.sourceText == "Synthetic queued source" }
            val unknownTransaction = api.getLedgerAIReviewItem(ledger.id, unknown.id).transactionId
            val knownTransaction = api.getLedgerAIReviewItem(ledger.id, known.id).transactionId
            assertTrue(unknownTransaction > 0 && knownTransaction > 0 && unknownTransaction != knownTransaction)
            val requests = persistence.requests()
            assertEquals(2, requests.count { it.state == AIReviewRequestEntity.PENDING })
            assertEquals(1, requests.count { it.state == AIReviewRequestEntity.POSTED })
            assertEquals(sourceWrite.payloadJson, requests.single { it.requestId == sourceWrite.requestId }.payloadJson)
            assertEquals(confirmWrite.payloadJson, requests.single { it.requestId == confirmWrite.requestId }.payloadJson)
            val newerSource = persistence.saveSourceImage(ledger.id, "{\"amount\":\"9.+\"}", original(2), sourceDraft)
            val newerConfirm = persistence.saveDraft(ledger.id, unknown.id, "{\"amount\":\"99.\"}", confirmDraft)
            val otherDraft = persistence.saveSourceImage(other.id, raw, original(3))
            val defaultDraft = persistence.saveSourceImage(0, raw, original(4))
            assertEquals(2, FinexyDatabase.get(context, databaseName).dao().aiReviewImages(ledger.id).size)
            for ((key, value) in mapOf("recovery_identity" to identity, "ai_ledger" to ledger.id.toString(),
                "ai_other" to other.id.toString(), "ai_account" to accountId.toString(), "ai_created" to created.id.toString(),
                "ai_unknown" to unknown.id.toString(), "ai_known" to known.id.toString(), "ai_unknown_tx" to unknownTransaction.toString(),
                "ai_known_tx" to knownTransaction.toString(), "ai_source_draft_digest" to digest(newerSource.formJson),
                "ai_confirm_draft_digest" to digest(newerConfirm.formJson), "ai_other_draft" to otherDraft.draftId, "ai_default_draft" to defaultDraft.draftId,
                "ai_old_image" to sourceWrite.sourceImageId)) {
                store.put(key, value, durable = true)
            }
            val work = OneTimeWorkRequestBuilder<SyncRecoveryProbeWorker>().setInputData(Data.Builder()
                .putString(SyncWorker.KEY_IDENTITY, identity).putString(SyncWorker.KEY_STORE_NAMESPACE, namespace).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(40, TimeUnit.SECONDS).addTag("qa-$namespace").build()
            store.put("recovery_request_id", work.id.toString(), durable = true)
            SyncRecoveryProbeWorker.prepareEvidence(context, namespace)
            val metadata = java.io.File(context.filesDir, "ai-storage-recovery/$namespace.json")
            check(metadata.parentFile!!.isDirectory || metadata.parentFile!!.mkdirs())
            metadata.writeText(JSONObject().put("workId", work.id.toString()).put("notBefore", SystemClock.elapsedRealtime() + 41_000).toString())
            val manager = WorkManager.getInstance(context)
            manager.enqueue(work).result.get(10, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(work.id).get(10, TimeUnit.SECONDS)!!.state)
            prepared = true
        } finally {
            runCatching { block(url, "") }
            databaseName?.let(FinexyDatabase::closeInstance)
            if (!prepared) cleanup(namespace, databaseName)
        }
    }

    @Test fun verifyNewProcessRecoveredOriginalOperationsWithoutDuplicateFinances() = runBlocking<Unit> {
        val started = SystemClock.elapsedRealtime()
        val (url, namespace) = arguments()
        val store = SecureStore(context, namespace)
        require(store.get(FinexyApi.KEY_SERVER_URL) == url)
        val identity = requireNotNull(store.get("recovery_identity"))
        val databaseName = LedgerScope.databaseName(store, identity)
        require(databaseName != "finexy.db" && databaseName.startsWith("finexy-"))
        try {
            val witness = JSONObject(SyncRecoveryProbeWorker.evidenceFile(context, namespace).readText())
            assertEquals("SUCCEEDED", witness.getString("state"))
            assertTrue(witness.getInt("workerPid") != witness.getInt("preparePid") && witness.getInt("workerPid") != Process.myPid())
            assertTrue(witness.getLong("finish") in 1 until started)
            val work = UUID.fromString(requireNotNull(store.get("recovery_request_id")))
            assertEquals(WorkInfo.State.SUCCEEDED, WorkManager.getInstance(context).getWorkInfoById(work).get(10, TimeUnit.SECONDS)!!.state)
            val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
            assertEquals(SyncRunState.SUCCEEDED, repository.syncStatus()?.state)
            val persistence = repository.aiReview
            assertTrue(persistence.requests().isEmpty())
            val ledger = requireNotNull(store.get("ai_ledger")).toLong(); val other = requireNotNull(store.get("ai_other")).toLong()
            val api = FinexyApi(store)
            val pending = api.listLedgerAIReviewItems(ledger)
            assertEquals(requireNotNull(store.get("ai_created")).toLong(), pending.single().id)
            assertEquals(2, api.listLedgerAIReviewItems(ledger, 2).size)
            val unknown = requireNotNull(store.get("ai_unknown")).toLong(); val known = requireNotNull(store.get("ai_known")).toLong()
            assertEquals(requireNotNull(store.get("ai_unknown_tx")).toLong(), api.getLedgerAIReviewItem(ledger, unknown).transactionId)
            assertEquals(requireNotNull(store.get("ai_known_tx")).toLong(), api.getLedgerAIReviewItem(ledger, known).transactionId)
            val account = requireNotNull(store.get("ai_account")).toLong()
            assertEquals(10200L, api.parseAccountResponse(api.listAccounts(ledger)).single { it.id == account }.balanceMinor)
            val expenses = api.parseTransactionResponse(api.listTransactions(ledger), ledger).filter { it.type == 3 }
            assertEquals(2, expenses.size); assertEquals(setOf(-123L, -77L), expenses.map { it.sourceAmountMinor }.toSet())
            assertEquals(store.get("ai_source_draft_digest"), digest(requireNotNull(persistence.draft(ledger, 0)).formJson))
            assertEquals(store.get("ai_confirm_draft_digest"), digest(requireNotNull(persistence.draft(ledger, unknown)).formJson))
            assertNull(persistence.draft(ledger, known))
            assertEquals(store.get("ai_other_draft"), persistence.draft(other, 0)?.draftId)
            assertEquals(store.get("ai_default_draft"), persistence.draft(0, 0)?.draftId)
            assertArrayEquals(original(2), persistence.sourceImage(requireNotNull(persistence.draft(ledger, 0))))
            assertArrayEquals(original(3), persistence.sourceImage(requireNotNull(persistence.draft(other, 0))))
            assertArrayEquals(original(4), persistence.sourceImage(requireNotNull(persistence.draft(0, 0))))
            val imageDao = FinexyDatabase.get(context, databaseName).dao()
            assertEquals(1, imageDao.aiReviewImages(ledger).size)
            assertNull(imageDao.findAIReviewImage(ledger, requireNotNull(store.get("ai_old_image"))))
            assertTrue(imageDao.aiReviewImageChunks(ledger, requireNotNull(store.get("ai_old_image"))).isEmpty())
            assertTrue(api.listLedgerAIReviewItems(other).isEmpty()); assertTrue(api.listLedgerAIReviewItems(0).isEmpty())
            assertTrue(repository.ledgerWrites().isEmpty()); assertTrue(repository.pendingAIReviewResolutions().isEmpty())
        } finally { cleanup(namespace, databaseName) }
    }

    private fun cleanup(namespace: String, databaseName: String?) {
        SyncRecoveryProbeWorker.evidenceFile(context, namespace)
        WorkManager.getInstance(context).cancelAllWorkByTag("qa-$namespace").result.get(10, TimeUnit.SECONDS)
        databaseName?.let {
            require(it != "finexy.db" && it.startsWith("finexy-") && it.endsWith(".db"))
            FinexyDatabase.closeInstance(it); context.deleteDatabase(it)
        }
        context.deleteSharedPreferences(namespace)
    }
}
