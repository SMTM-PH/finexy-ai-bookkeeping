package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicitly opt-in. Use only with the isolated, disposable Docker server in the test runbook. */
@RunWith(AndroidJUnit4::class)
class DockerSyncE2ETest {
    @Test fun realServerCreatePullEditConflictsDeleteAndIdempotency() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Requires an isolated Docker test server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "docker-e2e-${UUID.randomUUID()}"
        val store = SecureStore(context, prefsName)
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val client = OkHttpClient()
        val username = "android" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = UUID.randomUUID().toString()
        var token: String? = null

        fun post(path: String, payload: JSONObject): JSONObject {
            val builder = Request.Builder().url("$url/api/$path")
                .header("X-Timezone-Offset", "480")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            token?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                val result = JSONObject(response.body!!.string())
                check(response.isSuccessful) { "$path failed with HTTP ${response.code}: ${result.optJSONObject("error")?.optString("errorMessage").orEmpty()}" }
                check(result.optBoolean("success")) { "$path rejected test request" }
                return result
            }
        }

        try {
            val testCategories = JSONArray().put(JSONObject().put("name", "E2E Parent").put("type", 2)
                .put("icon", "1").put("color", "F05537").put("subCategories", JSONArray().put(
                    JSONObject().put("name", "E2E Expense").put("type", 2).put("parentId", "0")
                        .put("icon", "1").put("color", "F05537"))))
            post("register.json", JSONObject().put("username", username).put("email", "$username@example.com")
                .put("nickname", "Android E2E").put("password", password).put("language", "zh-Hans")
                .put("defaultCurrency", "CNY").put("firstDayOfWeek", 1).put("categories", testCategories))
            store.put(FinexyApi.KEY_SERVER_URL, url!!)
            token = FinexyApi(store).login(username, password).token
            store.put(FinexyApi.KEY_TOKEN, token!!)
            val api = FinexyApi(store)
            val accountId = post("v1/accounts/add.json", JSONObject().put("name", "Android E2E Wallet")
                .put("category", 1).put("type", 1).put("icon", "1").put("color", "F05537")
                .put("currency", "CNY").put("balance", 0).put("balanceTime", System.currentTimeMillis() / 1000))
                .getJSONObject("result").getString("id").toLong()
            val repository = TransactionRepository(context, database, importLegacy = false)
            val engine = SyncEngine(api, repository)
            engine.sync()
            val category = api.parseCategoryResponse(api.listCategories()).first { it.type == 2 && it.parentId != 0L && !it.hidden }
            val tag = api.createTag("Android E2E Tag")
            val lifecycleTag = api.createTag("Android E2E Tag CRUD")
            val renamedTag = api.modifyTag(TagEntity(lifecycleTag.id, lifecycleTag.name, lifecycleTag.groupId), "Android E2E Tag Renamed")
            assertEquals("Android E2E Tag Renamed", renamedTag.name)
            api.hideTag(renamedTag.id)
            assertTrue(api.parseTagResponse(api.listTags()).single { it.id == renamedTag.id }.hidden)
            api.deleteTag(renamedTag.id)
            assertTrue(api.parseTagResponse(api.listTags()).none { it.id == renamedTag.id })
            val localId = UUID.randomUUID().toString()
            repository.save(TransactionDraft(localId, 3, accountId, category.id, category.name, 1234,
                "Android original", JSONArray().put(tag.id.toString()).toString()))
            val uploaded = repository.pendingTransactions().single()
            assertEquals(1, engine.sync().pushed)
            val afterUpload = database.dao().findTransaction(localId)!!
            assertNotNull(afterUpload.serverId)
            val serverId = afterUpload.serverId!!

            // Same logical create repeated against the actual server must return the same ID.
            val retry = api.parseWrittenTransaction(api.addTransaction(uploaded.toApiPayload(), localId))
            assertEquals(serverId, retry.id)
            engine.sync()
            assertEquals(1, database.dao().allTransactions().count { it.serverId == serverId || it.localId == localId })
            val serverRow = api.parseTransactionResponse(api.listTransactions()).single { it.id == serverId }
            assertEquals(uploaded.time / 1000, serverRow.time)
            assertEquals(tag.id.toString(), JSONArray(serverRow.tagIdsJson).getString(0))

            // A separate API caller represents a Web edit.
            post("v1/transactions/modify.json", database.dao().findTransaction(localId)!!.toApiPayload()
                .put("id", serverId.toString()).put("comment", "Web changed").put("sourceAmount", 2468))
            engine.sync()
            val pulled = database.dao().findTransaction(localId)!!
            assertEquals("Web changed", pulled.comment)
            assertEquals(2468L, pulled.sourceAmountMinor)

            fun draft(row: TransactionEntity, comment: String) = TransactionDraft(row.localId, row.type,
                row.sourceAccountId, row.categoryId, row.categoryName, row.sourceAmountMinor, comment, row.tagIdsJson)

            repository.save(draft(pulled, "Android conflict"))
            post("v1/transactions/modify.json", pulled.toApiPayload().put("id", serverId.toString()).put("comment", "Web conflict"))
            assertEquals(1, engine.sync().conflicts)
            repository.resolveConflict(localId, false)
            assertEquals(1, engine.sync().pushed)
            assertEquals("Android conflict", api.parseTransactionResponse(api.listTransactions()).single { it.id == serverId }.comment)

            val again = database.dao().findTransaction(localId)!!
            repository.save(draft(again, "Android second conflict"))
            post("v1/transactions/modify.json", again.toApiPayload().put("id", serverId.toString())
                .put("comment", "Web wins").put("time", uploaded.time / 1000 - 86400).put("tagIds", JSONArray()))
            assertEquals(1, engine.sync().conflicts)
            repository.resolveConflict(localId, true)
            val resolved = database.dao().findTransaction(localId)!!
            assertEquals("Web wins", resolved.comment)
            assertEquals((uploaded.time / 1000 - 86400) * 1000, resolved.time)
            assertEquals("[]", resolved.tagIdsJson)

            // A Web deletion of an unchanged row is pulled as a local tombstone.
            post("v1/transactions/delete.json", JSONObject().put("id", serverId.toString()))
            assertEquals(1, engine.sync().removed)
            assertTrue(database.dao().findTransaction(localId)!!.deleted)

            // If Android edited the row meanwhile, expose a deletion conflict.
            // Keeping Android recreates it with a new server ID; it must not try
            // to modify the already-deleted ID.
            val secondLocalId = UUID.randomUUID().toString()
            repository.save(TransactionDraft(secondLocalId, 3, accountId, category.id, category.name, 3456,
                "Before remote delete", "[]"))
            assertEquals(1, engine.sync().pushed)
            val secondServerId = database.dao().findTransaction(secondLocalId)!!.serverId!!
            val second = database.dao().findTransaction(secondLocalId)!!
            repository.save(draft(second, "Keep after remote delete"))
            post("v1/transactions/delete.json", JSONObject().put("id", secondServerId.toString()))
            assertEquals(1, engine.sync().conflicts)
            assertEquals(TransactionRepository.REMOTE_DELETION_MARKER,
                database.dao().findConflict(secondLocalId)!!.remoteEntityJson)
            repository.resolveConflict(secondLocalId, false)
            val recreatedLocal = repository.pendingTransactions().single()
            assertNotEquals(secondLocalId, recreatedLocal.localId)
            assertNull(recreatedLocal.serverId)
            assertEquals(1, engine.sync().pushed)
            val recreatedId = database.dao().findTransaction(recreatedLocal.localId)!!.serverId!!
            assertNotEquals(secondServerId, recreatedId)
            assertEquals("Keep after remote delete", api.parseTransactionResponse(api.listTransactions()).single { it.id == recreatedId }.comment)

            repository.markDeleted(recreatedLocal.localId)
            assertEquals(1, engine.sync().pushed)
            assertTrue(api.parseTransactionResponse(api.listTransactions()).none { it.id == recreatedId })
            assertTrue(database.dao().findTransaction(recreatedLocal.localId)!!.deleted)

            // A legacy local-wallet row must remain pending until the user
            // explicitly binds it to a real server account. The chosen mapping
            // backfills that row and makes exactly one upload possible.
            val mappedLocalId = UUID.randomUUID().toString()
            repository.save(TransactionDraft(mappedLocalId, 3, TransactionEntity.LOCAL_ACCOUNT_ID,
                category.id, category.name, 7890, "Explicit account mapping", "[]"))
            assertEquals(TransactionEntity.LOCAL_ACCOUNT_ID, database.dao().findTransaction(mappedLocalId)!!.sourceAccountId)
            assertEquals(1, repository.mapAccount(TransactionEntity.LOCAL_ACCOUNT_ID, accountId))
            assertEquals(accountId, database.dao().findTransaction(mappedLocalId)!!.sourceAccountId)
            assertEquals(1, engine.sync().pushed)
            val mappedServerId = database.dao().findTransaction(mappedLocalId)!!.serverId
            assertEquals(1, api.parseTransactionResponse(api.listTransactions()).count {
                it.id == mappedServerId && it.comment == "Explicit account mapping"
            })
            repository.clearAccountMapping(TransactionEntity.LOCAL_ACCOUNT_ID)

            // Simulate the server committing a create while the client loses
            // the response before markSynced. The next sync must replay the
            // same clientSessionId before pull and converge to one Room row.
            val lostResponseLocalId = UUID.randomUUID().toString()
            repository.save(TransactionDraft(lostResponseLocalId, 3, accountId, category.id,
                category.name, 9012, "Response lost after commit", "[]"))
            val lostResponseUpload = repository.pendingTransactions().single { it.localId == lostResponseLocalId }
            val committed = api.parseWrittenTransaction(api.addTransaction(lostResponseUpload.toApiPayload(), lostResponseLocalId))
            assertNull(database.dao().findTransaction(lostResponseLocalId)!!.serverId)
            assertEquals(1, engine.sync().pushed)
            assertEquals(committed.id, database.dao().findTransaction(lostResponseLocalId)!!.serverId)
            assertEquals(1, database.dao().allTransactions().count { it.serverId == committed.id })
            assertEquals(1, api.parseTransactionResponse(api.listTransactions()).count { it.id == committed.id })
        } finally {
            database.close()
            context.deleteSharedPreferences(prefsName)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            // Server account/data belongs to the disposable container; remove that container
            // after this test, including on failure. Never run this test against a user server.
        }
    }
}
