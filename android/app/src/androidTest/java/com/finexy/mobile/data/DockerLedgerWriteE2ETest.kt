package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Only a disposable, no-volume Docker service and a random account are used. */
@RunWith(AndroidJUnit4::class)
class DockerLedgerWriteE2ETest {
    @Test fun lostCreateResponseAndReopenedRoomProduceOneTransactionInTheBoundLedger() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Requires an isolated Docker test server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val namespace = "ledger-write-e2e-$suffix"
        val databaseName = "$namespace.db"
        val store = SecureStore(context, namespace)
        var database = Room.databaseBuilder(context, FinexyDatabase::class.java, databaseName).build()
        try {
            val username = "write$suffix"
            val password = UUID.randomUUID().toString()
            val categories = JSONArray().put(JSONObject().put("name", "写入父分类").put("type", 2)
                .put("icon", "1").put("color", "F05537").put("subCategories", JSONArray().put(
                    JSONObject().put("name", "写入子分类").put("type", 2).put("icon", "1").put("color", "F05537"))))
            val request = Request.Builder().url("$url/api/register.json").header("X-Timezone-Offset", "480")
                .post(JSONObject().put("username", username).put("email", "$username@example.com")
                    .put("nickname", "Queue E2E").put("password", password).put("language", "zh-Hans")
                    .put("defaultCurrency", "CNY").put("categories", categories).toString().toRequestBody("application/json".toMediaType())).build()
            OkHttpClient().newCall(request).execute().use { response ->
                check(response.isSuccessful && JSONObject(response.body!!.string()).optBoolean("success")) { "Isolated account registration failed" }
            }
            store.put(FinexyApi.KEY_SERVER_URL, url!!)
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token)
            val api = FinexyApi(store)
            val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "队列测试账本", "isolated")
            val account = api.createAccount(AccountDraft("队列账户", 1, "CNY", 0)).single()
            api.moveAccountToLedger(account.id, ledger.id)
            val category = api.parseCategoryResponse(api.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }
            val baseline = api.parseTransactionResponse(api.listTransactions())
            var repository = TransactionRepository(context, database, false)
            repository.replaceLedgers(api.listLedgers())
            val entity = TransactionEntity("stable-$suffix", type = 3, sourceAccountId = account.id, categoryId = category.id,
                categoryName = category.name, sourceAmountMinor = 1234, comment = "queue-$suffix", time = System.currentTimeMillis())
            repository.enqueueLedgerWrite(ledger.id, entity)
            var postCount = 0
            val first = LedgerWriteDelivery(repository) { write ->
                postCount++
                api.parseWrittenTransaction(api.addTransaction(JSONObject(write.payloadJson), write.localId))
                throw IOException("Injected loss after successful POST")
            }.push()
            assertEquals(1, first.errors.size)
            database.close()
            database = Room.databaseBuilder(context, FinexyDatabase::class.java, databaseName).build()
            repository = TransactionRepository(context, database, false)
            val delivery = LedgerWriteDelivery(repository) { write ->
                postCount++
                api.parseWrittenTransaction(api.addTransaction(JSONObject(write.payloadJson), write.localId)).id
            }
            assertEquals(1, delivery.push().pushed)
            assertEquals(LedgerWriteEntity.POSTED, repository.ledgerWrites().single().state)
            // A failed read leaves POSTED persisted. A new sender must perform no POST.
            val afterFailure = LedgerWriteDelivery(repository) { postCount++; error("Posted request must not be sent again") }.push()
            assertEquals(0, afterFailure.pushed)
            assertEquals(2, postCount)
            val remote = api.parseTransactionResponse(api.listTransactions(ledger.id), ledger.id)
            assertEquals(1, remote.count { it.comment == entity.comment })
            assertEquals(1234L, remote.single { it.comment == entity.comment }.sourceAmountMinor)
            assertEquals(baseline.map { it.id }, api.parseTransactionResponse(api.listTransactions()).map { it.id })
            repository.replaceLedgerSnapshot(ledger.id, api.parseAccountResponse(api.listAccounts(ledger.id)), remote)
            assertTrue(repository.ledgerWrites().isEmpty())
        } finally {
            database.close(); context.deleteDatabase(databaseName)
            context.deleteSharedPreferences(namespace)
        }
    }
}
