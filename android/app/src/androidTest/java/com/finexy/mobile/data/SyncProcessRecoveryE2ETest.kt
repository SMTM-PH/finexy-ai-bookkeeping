package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Two-phase, opt-in process recovery test. Run prepare, terminate the target app
 * process with `am kill`, then run verify with the same isolated namespace.
 */
@RunWith(AndroidJUnit4::class)
class SyncProcessRecoveryE2ETest {
    @Test fun preparePersistentSyncThatSurvivesProcessDeath() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString(ARG_URL)
        val namespace = args.getString(ARG_NAMESPACE)
        assumeTrue("Requires an isolated Docker test server", !url.isNullOrBlank() && !namespace.isNullOrBlank())
        require(namespace!!.startsWith("sync-recovery-"))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, namespace)
        val client = OkHttpClient()
        val username = "recovery" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = UUID.randomUUID().toString()
        var token: String? = null

        fun post(path: String, payload: JSONObject): JSONObject {
            val builder = Request.Builder().url("$url/api/$path")
                .header("X-Timezone-Offset", "480")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            token?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                val body = JSONObject(response.body!!.string())
                check(response.isSuccessful && body.optBoolean("success")) { "$path failed: HTTP ${response.code}" }
                return body
            }
        }

        try {
            val categories = JSONArray().put(JSONObject().put("name", "Recovery Parent").put("type", 2)
                .put("icon", "1").put("color", "F05537").put("subCategories", JSONArray().put(
                    JSONObject().put("name", "Recovery Expense").put("type", 2).put("parentId", "0")
                        .put("icon", "1").put("color", "F05537"))))
            post("register.json", JSONObject().put("username", username).put("email", "$username@example.com")
                .put("nickname", "Process Recovery").put("password", password).put("language", "zh-Hans")
                .put("defaultCurrency", "CNY").put("firstDayOfWeek", 1).put("categories", categories))
            store.put(FinexyApi.KEY_SERVER_URL, url!!, durable = true)
            token = FinexyApi(store).login(username, password).token
            store.put(FinexyApi.KEY_TOKEN, token!!, durable = true)
            store.put("local_mode", "false", durable = true)
            val accountId = post("v1/accounts/add.json", JSONObject().put("name", "Recovery Wallet")
                .put("category", 1).put("type", 1).put("icon", "1").put("color", "F05537")
                .put("currency", "CNY").put("balance", 0)
                .put("balanceTime", System.currentTimeMillis() / 1000))
                .getJSONObject("result").getString("id").toLong()

            val identity = LedgerScope.identity(url, token, false)
            val databaseName = LedgerScope.databaseName(store, identity)
            val repository = TransactionRepository(context, FinexyDatabase.get(context, databaseName), false)
            val api = FinexyApi(store)
            SyncEngine(api, repository).sync()
            val category = api.parseCategoryResponse(api.listCategories())
                .first { it.type == 2 && it.parentId != 0L && !it.hidden }
            val localId = UUID.randomUUID().toString()
            repository.save(TransactionDraft(localId, 3, accountId, category.id, category.name, 4567,
                "Process recovery upload", "[]"))

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(androidx.work.Data.Builder()
                    .putString(SyncWorker.KEY_IDENTITY, identity)
                    .putString(SyncWorker.KEY_STORE_NAMESPACE, namespace).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(12, TimeUnit.SECONDS)
                .addTag("sync-recovery-$namespace")
                .build()
            store.put(KEY_REQUEST_ID, request.id.toString(), durable = true)
            store.put(KEY_LOCAL_ID, localId, durable = true)
            WorkManager.getInstance(context).enqueue(request).result.get(10, TimeUnit.SECONDS)
            assertEquals(WorkInfo.State.ENQUEUED,
                requireNotNull(WorkManager.getInstance(context).getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)).state)
            assertEquals(1, repository.pendingTransactions().size)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun verifyWorkCompletedAfterProcessRestartAndCleanUp() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val namespace = args.getString(ARG_NAMESPACE)
        assumeTrue("Requires the prepare phase", !namespace.isNullOrBlank())
        require(namespace!!.startsWith("sync-recovery-"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, namespace)
        val identity = LedgerScope.identity(store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), store.get(FinexyApi.KEY_TOKEN), false)
        val databaseName = LedgerScope.databaseName(store, identity)
        val manager = WorkManager.getInstance(context)
        val requestId = UUID.fromString(requireNotNull(store.get(KEY_REQUEST_ID)))
        val localId = requireNotNull(store.get(KEY_LOCAL_ID))
        try {
            var info = requireNotNull(manager.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS))
            repeat(30) {
                if (info.state.isFinished) return@repeat
                delay(1_000)
                info = requireNotNull(manager.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS))
            }
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            val database = FinexyDatabase.get(context, databaseName)
            val repository = TransactionRepository(context, database, false)
            assertEquals(SyncRunState.SUCCEEDED, repository.syncStatus()?.state)
            assertTrue(repository.pendingTransactions().isEmpty())
            val row = database.dao().findTransaction(localId)
            assertNotNull(row?.serverId)
            val remote = FinexyApi(store).parseTransactionResponse(FinexyApi(store).listTransactions())
            assertEquals(1, remote.count { it.id == row!!.serverId && it.comment == "Process recovery upload" })
        } finally {
            manager.cancelAllWorkByTag("sync-recovery-$namespace").result.get(10, TimeUnit.SECONDS)
            FinexyDatabase.closeInstance(databaseName)
            context.deleteDatabase(databaseName)
            context.deleteSharedPreferences(namespace)
        }
    }

    companion object {
        private const val ARG_URL = "finexy.recovery.url"
        private const val ARG_NAMESPACE = "finexy.recovery.namespace"
        private const val KEY_REQUEST_ID = "recovery_request_id"
        private const val KEY_LOCAL_ID = "recovery_local_id"
    }
}
