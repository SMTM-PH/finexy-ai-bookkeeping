package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.UUID

/** Storage boundary test, not a claim of production screen/Worker integration. */
@RunWith(AndroidJUnit4::class)
class TemplateCreationStorageE2ETest {
    @Test fun confirmedSnapshotsReplayAgainstDurableServerAfterDatabaseReopen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.finexy.mobile.qa")
        val url = requireNotNull(InstrumentationRegistry.getArguments().getString("finexy.e2e.url")).toHttpUrl()
        require(url.scheme == "http" && url.host == "10.0.2.2" && url.port == 18116 && url.encodedPath == "/" && url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty())
        val suffix = UUID.randomUUID().toString().replace("-", "").take(20)
        val namespace = "template-storage-$suffix"
        val store = SecureStore(context, namespace)
        val client = OkHttpClient()
        val username = "tpl$suffix"
        val password = UUID.randomUUID().toString()
        val name = "template-storage-$suffix.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        fun request(path: String, payload: JSONObject): JSONObject {
            val builder = Request.Builder().url(url.resolve("api/$path")!!).header("X-Timezone-Offset", "480")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            store.get(FinexyApi.KEY_TOKEN)?.let { builder.header("Authorization", "Bearer $it") }
            return client.newCall(builder.build()).execute().use {
                val response = JSONObject(requireNotNull(it.body).string())
                check(it.isSuccessful && response.get("success") == true) { "Isolated template API failed: $path ${it.code}, errorCode=${response.optInt("errorCode")}" }
                response
            }
        }
        try {
            val categories = JSONArray().put(JSONObject().put("name", "Parent").put("type", 2).put("icon", "1").put("color", "000000")
                .put("subCategories", JSONArray().put(JSONObject().put("name", "Leaf").put("type", 2).put("icon", "1").put("color", "000000"))))
            request("register.json", JSONObject().put("username", username).put("password", password).put("email", "$username@example.invalid")
                .put("nickname", "Synthetic storage").put("language", "zh-Hans").put("defaultCurrency", "CNY").put("categories", categories))
            store.put(FinexyApi.KEY_SERVER_URL, url.toString().trimEnd('/'))
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token)
            val api = FinexyApi(store)
            val sentinel = api.createAccount(AccountDraft("Default sentinel", 1, "CNY", 5000)).single()
            val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic templates", "isolated")
            val account = api.createAccount(AccountDraft("Template account", 1, "CNY", 9000)).single()
            api.moveAccountToLedger(account.id, ledger.id)
            val category = api.parseCategoryResponse(api.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }
            val baselineDefault = api.listAccounts()
            val baselineLedger = api.listAccounts(ledger.id)
            val baselineDefaultTransactions = api.listTransactions()
            val baselineLedgerTransactions = api.listTransactions(ledger.id)
            val repository = TransactionRepository(context, db, false)
            val original = TemplateEntity(0, "Synthetic ordinary", 3, category.id, account.id, -1234, "confirmed original", hideAmount = true, ledgerId = ledger.id)
            val originalDraft = repository.saveTemplateDraft(ledger.id, 1, 0, "{\"name\":\"Synthetic ordinary\",\"amount\":\"12.34\"}")
            val queued = repository.enqueueTemplateCreation(originalDraft, api.templatePayload(original).toString())
            val plan = original.copy(name = "Synthetic schedule", templateType = 2, scheduledFrequencyType = 3, scheduledFrequency = "0", scheduledStartDate = "2026-10-05", utcOffset = 480)
            val planDraft = repository.saveTemplateDraft(ledger.id, 2, 0, "{\"name\":\"Synthetic schedule\",\"frequency\":\"0\"}")
            repository.enqueueTemplateCreation(planDraft, api.templatePayload(plan).toString())
            val other = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Draft only", "isolated")
            repository.saveTemplateDraft(other.id, 1, 0, "{\"name\":\"Never confirmed\",\"amount\":\"1.\"}")
            var ordinaryPosts = 0
            var schedulePosts = 0
            var serverId = 0L
            var loseReply = true
            val upload: suspend (TemplateCreationEntity) -> TemplateCreationReceipt = { write ->
                if (write.templateType == 1) ordinaryPosts++ else schedulePosts++
                val response = request("v1/transaction/templates/add.json", JSONObject(write.payloadJson).put("clientSessionId", write.clientSessionId)).getJSONObject("result")
                val id = response.getString("id").toLong()
                if (write.templateType == 1) { serverId = id; if (loseReply) { loseReply = false; throw IOException("Injected loss after backend commit, before Room receipt") } }
                check(response.getString("ledgerId") == write.ledgerId.toString() && response.getInt("templateType") == write.templateType)
                TemplateCreationReceipt(write.ledgerId, write.templateType, id, response.optBoolean("deleted"))
            }
            val first = TemplateCreationDelivery(repository, upload).push()
            assertEquals(1, first.posted); assertEquals(1, first.errors.size); assertTrue(serverId > 0)
            val newer = repository.saveTemplateDraft(ledger.id, 1, 0, "{\"name\":\"Later input\",\"amount\":\"99.\"}", originalDraft)
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            val recovered = TransactionRepository(context, db, false)
            assertEquals(queued.payloadJson, recovered.templateCreations().first { it.templateType == 1 }.payloadJson)
            assertEquals(queued.clientSessionId, recovered.templateCreations().first { it.templateType == 1 }.clientSessionId)
            assertEquals(1, TemplateCreationDelivery(recovered, upload).push().posted)
            assertEquals(2, ordinaryPosts); assertEquals(1, schedulePosts)
            val ordinary = api.parseTemplateResponse(api.listTemplates(1, ledger.id), ledger.id).single()
            assertEquals(serverId, ordinary.id); assertEquals(-1234L, ordinary.sourceAmountMinor); assertEquals("confirmed original", ordinary.comment)
            val scheduled = api.parseScheduledTemplateList(api.listTemplates(2, ledger.id), ledger.id)
            assertEquals(1, scheduled.size)
            assertTrue(api.parseTemplateResponse(api.listTemplates(1, other.id), other.id).isEmpty())
            recovered.replacePeriodicSnapshot(ledger.id, listOf(ordinary), scheduled, emptyList())
            assertTrue(recovered.templateCreations().isEmpty()); assertEquals(newer, recovered.templateDraft(ledger.id, 1)); assertNull(recovered.templateDraft(ledger.id, 2))
            assertEquals(baselineDefault, api.listAccounts()); assertEquals(baselineLedger, api.listAccounts(ledger.id))
            assertEquals(baselineDefaultTransactions, api.listTransactions()); assertEquals(baselineLedgerTransactions, api.listTransactions(ledger.id))
            assertEquals(5000L, api.parseAccountResponse(api.listAccounts()).single { it.id == sentinel.id }.balanceMinor)
            api.deleteTemplate(serverId, ledger.id)
            val tombstone = request("v1/transaction/templates/add.json", JSONObject(queued.payloadJson).put("clientSessionId", queued.clientSessionId)).getJSONObject("result")
            assertEquals(serverId.toString(), tombstone.getString("id")); assertEquals(true, tombstone.get("deleted"))
            assertTrue(api.parseTemplateResponse(api.listTemplates(1, ledger.id), ledger.id).isEmpty())
        } finally {
            db.close(); context.deleteDatabase(name)
            context.getSharedPreferences(namespace, 0).edit().clear().commit()
        }
    }
}
