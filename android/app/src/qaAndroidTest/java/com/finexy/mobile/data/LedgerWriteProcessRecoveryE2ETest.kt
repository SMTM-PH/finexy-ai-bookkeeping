package com.finexy.mobile.data

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** QA-only source: separate prepare/verify invocations in the isolated com.finexy.mobile.qa UID. */
@RunWith(AndroidJUnit4::class)
class LedgerWriteProcessRecoveryE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun arguments(): Pair<String, String> {
        check(context.packageName == SyncRecoveryProbeWorker.QA_PACKAGE) { "Requires the isolated QA package" }
        val args = InstrumentationRegistry.getArguments()
        val namespace = requireNotNull(args.getString(ARG_NAMESPACE)) { "Requires a fresh recovery namespace" }
        SyncRecoveryProbeWorker.evidenceFile(context, namespace) // Also validates its bounded random suffix.
        val url = requireNotNull(args.getString(ARG_URL)) { "Requires the disposable recovery server" }.toHttpUrl()
        require(url.scheme == "http" && url.host == "10.0.2.2" && url.port == 18086 && url.encodedPath == "/" &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Recovery test only accepts the reserved disposable emulator-host endpoint"
        }
        return url.toString().trimEnd('/') to namespace
    }

    @Test fun prepareLostResponseAndPersistentNonDefaultLedgerWrite() = runBlocking {
        val (url, namespace) = arguments()
        require(!SyncRecoveryProbeWorker.evidenceFile(context, namespace).exists()) { "Use a fresh namespace" }
        require(context.getSharedPreferences(namespace, 0).all.isEmpty()) { "Recovery preferences already exist" }
        val store = SecureStore(context, namespace)
        val client = OkHttpClient()
        var databaseName: String? = null
        var prepared = false
        try {
            val suffix = namespace.removePrefix(SyncRecoveryProbeWorker.NAMESPACE_PREFIX)
            val username = "recover$suffix"
            val password = UUID.randomUUID().toString()
            val categories = JSONArray().put(JSONObject().put("name", "Recovery Parent").put("type", 2)
                .put("icon", "1").put("color", "F05537").put("subCategories", JSONArray().put(
                    JSONObject().put("name", "Recovery Expense").put("type", 2).put("icon", "1").put("color", "F05537"))))
            val request = Request.Builder().url("$url/api/register.json").header("X-Timezone-Offset", "480")
                .post(JSONObject().put("username", username).put("email", "$username@example.com")
                    .put("nickname", "Process Recovery QA").put("password", password).put("language", "zh-Hans")
                    .put("defaultCurrency", "CNY").put("categories", categories).toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful && JSONObject(response.body!!.string()).optBoolean("success")) { "Disposable account registration failed" }
            }
            store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            store.put("local_mode", "false", durable = true)
            val identity = LedgerScope.identity(url, store.get(FinexyApi.KEY_TOKEN), false)
            databaseName = LedgerScope.databaseName(store, identity)
            require(databaseName != "finexy.db" && databaseName.startsWith("finexy-")) { "Requires a dedicated fixture database" }
            val database = FinexyDatabase.get(context, databaseName)
            val repository = TransactionRepository(context, database, false)
            val api = FinexyApi(store)
            // A nonempty default-account baseline makes accidental default-ledger changes observable.
            api.createAccount(AccountDraft("Default Sentinel", 1, "CNY", 5000))
            val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Recovery Ledger", "isolated")
            require(ledger.id > 0)
            val account = api.createAccount(AccountDraft("Recovery Account", 1, "CNY", 0)).single()
            api.moveAccountToLedger(account.id, ledger.id)
            val baselineBalance = api.parseAccountResponse(api.listAccounts(ledger.id)).single { it.id == account.id }.balanceMinor
            val defaultDigest = defaultLedgerDigest(api)
            val allCategories = api.parseCategoryResponse(api.listCategories(ledgerId = ledger.id))
            val category = allCategories.first { leaf -> !leaf.hidden && leaf.type == 2 && leaf.parentId > 0 &&
                allCategories.any { it.id == leaf.parentId && it.type == 2 && !it.hidden && it.parentId == 0L } }
            repository.replaceLedgers(api.listLedgers())
            val localId = "recovery-$suffix"
            val row = TransactionEntity(localId, type = TransactionRepository.TYPE_EXPENSE, sourceAccountId = account.id,
                categoryId = category.id, categoryName = category.name, sourceAmountMinor = TEST_AMOUNT, currency = account.currency,
                comment = "recovery-$suffix", time = System.currentTimeMillis())
            val queued = repository.enqueueLedgerWrite(ledger.id, row)
            val payloadDigest = digest(queued.payloadJson)
            var firstServerId = 0L
            val loss = LedgerWriteDelivery(repository) { write ->
                firstServerId = api.parseWrittenTransaction(api.addTransaction(JSONObject(write.payloadJson), write.localId)).id
                throw IOException("Injected lost create response")
            }.push()
            assertTrue("The successful create response must be lost before acknowledging Room", firstServerId > 0 && loss.errors.size == 1)
            val pending = repository.ledgerWrites().single()
            assertTrue("The original immutable request must remain pending", pending.state == LedgerWriteEntity.PENDING &&
                pending.localId == localId && pending.ledgerId == ledger.id && digest(pending.payloadJson) == payloadDigest)
            store.put(KEY_IDENTITY, identity, durable = true)
            store.put(KEY_LOCAL_ID, localId, durable = true)
            store.put(KEY_LEDGER_ID, ledger.id.toString(), durable = true)
            store.put(KEY_ACCOUNT_ID, account.id.toString(), durable = true)
            store.put(KEY_SERVER_ID, firstServerId.toString(), durable = true)
            store.put(KEY_BASELINE_BALANCE, baselineBalance.toString(), durable = true)
            store.put(KEY_DEFAULT_DIGEST, defaultDigest, durable = true)
            val work = OneTimeWorkRequestBuilder<SyncRecoveryProbeWorker>()
                .setInputData(Data.Builder().putString(SyncWorker.KEY_IDENTITY, identity)
                    .putString(SyncWorker.KEY_STORE_NAMESPACE, namespace).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(45, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(workTag(namespace)).build()
            store.put(KEY_REQUEST_ID, work.id.toString(), durable = true)
            SyncRecoveryProbeWorker.prepareEvidence(context, namespace)
            val manager = WorkManager.getInstance(context)
            manager.enqueue(work).result.get(10, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.ENQUEUED, requireNotNull(manager.getWorkInfoById(work.id).get(10, TimeUnit.SECONDS)).state)
            assertTrue("Prepare must not upload the pending snapshot again", repository.ledgerWrites().single().state == LedgerWriteEntity.PENDING)
            prepared = true
        } finally {
            databaseName?.let { FinexyDatabase.closeInstance(it) }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            if (!prepared) cleanFixture(namespace, databaseName)
        }
    }

    @Test fun verifyBackgroundWorkerReplayedOnceAndCleanUp() = runBlocking {
        val verifyStarted = SystemClock.elapsedRealtime()
        val (url, namespace) = arguments()
        val store = SecureStore(context, namespace)
        require(store.get(FinexyApi.KEY_SERVER_URL) == url) { "Verify must use the same disposable endpoint" }
        val identity = requireNotNull(store.get(KEY_IDENTITY))
        val databaseName = LedgerScope.databaseName(store, identity)
        require(databaseName != "finexy.db" && databaseName.startsWith("finexy-")) { "Requires the original fixture database" }
        try {
            val evidence = JSONObject(SyncRecoveryProbeWorker.evidenceFile(context, namespace).readText(Charsets.UTF_8))
            val workerPid = evidence.getInt("workerPid")
            assertTrue("Worker must run in a different process from prepare and verify", workerPid > 0 &&
                workerPid != evidence.getInt("preparePid") && workerPid != Process.myPid())
            assertTrue("Worker must finish in the background before verify instrumentation", evidence.getString("state") == "SUCCEEDED" &&
                evidence.getLong("start") > 0 && evidence.getLong("finish") >= evidence.getLong("start") && evidence.getLong("finish") < verifyStarted)
            val manager = WorkManager.getInstance(context)
            val requestId = UUID.fromString(requireNotNull(store.get(KEY_REQUEST_ID)))
            var info = requireNotNull(manager.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS))
            repeat(25) {
                if (info.state.isFinished) return@repeat
                delay(200)
                info = requireNotNull(manager.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS))
            }
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
            assertEquals(SyncRunState.SUCCEEDED, repository.syncStatus()?.state)
            assertTrue("Validated refresh must acknowledge the confirmed ledger write", repository.ledgerWrites().isEmpty())
            val ledgerId = requireNotNull(store.get(KEY_LEDGER_ID)).toLong()
            val accountId = requireNotNull(store.get(KEY_ACCOUNT_ID)).toLong()
            val serverId = requireNotNull(store.get(KEY_SERVER_ID)).toLong()
            val localId = requireNotNull(store.get(KEY_LOCAL_ID))
            val api = FinexyApi(store)
            val matching = api.parseTransactionResponse(api.listTransactions(ledgerId), ledgerId).filter { it.comment == localId }
            assertTrue("The original key must replay exactly one persisted transaction", matching.size == 1 &&
                matching.single().id == serverId && matching.single().sourceAccountId == accountId && matching.single().sourceAmountMinor == TEST_AMOUNT)
            val account = api.parseAccountResponse(api.listAccounts(ledgerId)).single { it.id == accountId }
            assertTrue("The account balance must change exactly once", account.balanceMinor == requireNotNull(store.get(KEY_BASELINE_BALANCE)).toLong() - TEST_AMOUNT)
            assertTrue("Default ledger records and balances must stay unchanged", defaultLedgerDigest(api) == store.get(KEY_DEFAULT_DIGEST))
            assertTrue("The confirmed row must be cached in its original nondefault ledger", repository.observeLedgerTransactions(ledgerId).first().count { it.serverId == serverId } == 1)
        } finally {
            cleanFixture(namespace, databaseName)
        }
    }

    private suspend fun defaultLedgerDigest(api: FinexyApi): String {
        val rows = JSONArray().apply { api.parseTransactionResponse(api.listTransactions()).map { it.id }.sorted().forEach { put(it.toString()) } }
        val accounts = JSONArray().apply {
            api.parseAccountResponse(api.listAccounts()).sortedBy { it.id }.forEach {
                put(JSONObject().put("id", it.id.toString()).put("currency", it.currency).put("balance", it.balanceMinor))
            }
        }
        return digest(JSONObject().put("rows", rows).put("accounts", accounts).toString())
    }

    private fun cleanFixture(namespace: String, databaseName: String?) {
        SyncRecoveryProbeWorker.evidenceFile(context, namespace) // Never clean a main-package fixture.
        WorkManager.getInstance(context).cancelAllWorkByTag(workTag(namespace)).result.get(10, TimeUnit.SECONDS)
        databaseName?.let { name ->
            require(name != "finexy.db" && name.startsWith("finexy-") && name.endsWith(".db"))
            FinexyDatabase.closeInstance(name)
            context.deleteDatabase(name)
        }
        context.deleteSharedPreferences(namespace)
        // Keep only the fixed-schema PID/time/state file for the host's evidence capture.
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun workTag(namespace: String) = "qa-$namespace"

    companion object {
        private const val ARG_URL = "finexy.recovery.url"
        private const val ARG_NAMESPACE = "finexy.recovery.namespace"
        private const val KEY_IDENTITY = "recovery_identity"
        private const val KEY_LOCAL_ID = "recovery_local_id"
        private const val KEY_LEDGER_ID = "recovery_ledger_id"
        private const val KEY_ACCOUNT_ID = "recovery_account_id"
        private const val KEY_SERVER_ID = "recovery_server_id"
        private const val KEY_BASELINE_BALANCE = "recovery_baseline_balance"
        private const val KEY_DEFAULT_DIGEST = "recovery_default_digest"
        private const val KEY_REQUEST_ID = "recovery_request_id"
        private const val TEST_AMOUNT = 1234L
    }
}
