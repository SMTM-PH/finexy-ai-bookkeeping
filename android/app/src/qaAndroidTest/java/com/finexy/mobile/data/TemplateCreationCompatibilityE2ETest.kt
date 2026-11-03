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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TemplateCreationCompatibilityE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Actor(val username: String, val store: SecureStore) { val api get() = FinexyApi(store) }
    private fun url(legacy: Boolean): String {
        check(context.packageName == "com.finexy.mobile.qa")
        val arg = if (legacy) "finexy.legacy.url" else "finexy.recovery.url"
        val value = requireNotNull(InstrumentationRegistry.getArguments().getString(arg)).toHttpUrl()
        require(value.scheme == "http" && value.host == "10.0.2.2" && value.port == (if (legacy) 18118 else 18117) &&
            value.encodedPath == "/" && value.query == null && value.fragment == null && value.username.isEmpty() && value.password.isEmpty())
        return value.toString().trimEnd('/')
    }
    private suspend fun actor(url: String, actors: MutableList<Actor>): Actor {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(20)
        val username = "tpl$suffix"
        val password = UUID.randomUUID().toString()
        val store = SecureStore(context, "template-compat-$suffix")
        val actor = Actor(username, store); actors += actor
        val categories = JSONArray().put(JSONObject().put("name", "Parent").put("type", 2).put("icon", "1").put("color", "000000")
            .put("subCategories", JSONArray().put(JSONObject().put("name", "Leaf").put("type", 2).put("icon", "1").put("color", "000000"))))
        val data = JSONObject().put("username", username).put("password", password).put("email", "$username@example.invalid")
            .put("nickname", "Synthetic contract").put("language", "zh-Hans").put("defaultCurrency", "CNY").put("categories", categories)
        val client = OkHttpClient()
        try {
            client.newCall(Request.Builder().url("$url/api/register.json").post(data.toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                check(it.isSuccessful && JSONObject(it.body!!.string()).get("success") == true) { "Isolated registration failed" }
            }
            store.put(FinexyApi.KEY_SERVER_URL, url)
            store.put(FinexyApi.KEY_TOKEN, actor.api.login(username, password).token)
            store.put("local_mode", "false")
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        return actor
    }
    private suspend fun queue(repo: TransactionRepository, api: FinexyApi, template: TemplateEntity): TemplateCreationEntity {
        val draft = repo.saveTemplateDraft(template.ledgerId, template.templateType, 0, "{\"name\":\"Confirmed\"}")
        return repo.enqueueTemplateCreation(draft, api.templatePayload(template).toString())
    }

    @Test fun oldServerKeepsConfirmedRequestWithoutFallingBackToLegacyAdd() = runBlocking<Unit> {
        val actors = mutableListOf<Actor>()
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val actor = actor(url(true), actors)
            val api = actor.api
            val account = api.createAccount(AccountDraft("Legacy sentinel", 1, "CNY", 5000)).single()
            val category = api.parseCategoryResponse(api.listCategories()).first { it.type == 2 && it.parentId > 0 }
            val baseline = api.listAccounts() + api.listTransactions()
            val repo = TransactionRepository(context, db, false)
            val original = queue(repo, api, TemplateEntity(0, "Blocked legacy", 3, category.id, account.id, 1234, "confirmed"))
            val delivery = TemplateCreationDelivery(repo) { api.createTemplate(it) }.push()
            assertEquals(0, delivery.posted); assertEquals(1, delivery.errors.size)
            assertTrue(delivery.errors.single() is IllegalStateException)
            assertEquals(original.payloadJson, repo.templateCreations().single().payloadJson)
            assertEquals(original.clientSessionId, repo.templateCreations().single().clientSessionId)
            try { SyncEngine(api, repo, false).sync(); fail("Unsupported server must be reported") }
            catch (_: IllegalStateException) { }
            assertTrue(api.parseTemplateResponse(api.listTemplates()).isEmpty())
            assertEquals(baseline, api.listAccounts() + api.listTransactions())
            assertEquals(TemplateCreationEntity.PENDING, repo.templateCreations().single().state)
        } finally { db.close(); actors.forEach { context.deleteSharedPreferences(it.store.namespace) } }
    }

    @Test fun revokedAdminCannotReplayButOwnConfirmedRequestStillCompletes() = runBlocking<Unit> {
        val actors = mutableListOf<Actor>()
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val owner = actor(url(false), actors)
            val admin = actor(url(false), actors)
            val ownerApi = owner.api; val adminApi = admin.api
            val ledger = ownerApi.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Shared contract", "isolated")
            val source = ownerApi.createAccount(AccountDraft("Shared sentinel", 1, "CNY", 9000)).single()
            ownerApi.moveAccountToLedger(source.id, ledger.id)
            adminApi.acceptLedgerInvitation(ownerApi.createLedgerInvitation(ledger.id, admin.username, RemoteLedgerMember.ROLE_MEMBER).token)
            val membership = adminApi.listLedgerMembers(ledger.id).single { it.isCurrentUser }
            ownerApi.changeLedgerMemberRole(ledger.id, membership.id, RemoteLedgerMember.ROLE_ADMIN)
            val category = ownerApi.parseCategoryResponse(ownerApi.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }
            val ownAccount = adminApi.createAccount(AccountDraft("Own sentinel", 1, "CNY", 5000)).single()
            val ownCategory = adminApi.parseCategoryResponse(adminApi.listCategories()).first { it.type == 2 && it.parentId > 0 }
            val baselineShared = ownerApi.listAccounts(ledger.id) + ownerApi.listTransactions(ledger.id)
            val baselineOwn = adminApi.listAccounts() + adminApi.listTransactions()
            val repo = TransactionRepository(context, db, false)
            val shared = queue(repo, adminApi, TemplateEntity(0, "Original shared", 3, category.id, source.id, -1234, "original", ledgerId = ledger.id))
            val committed = adminApi.createTemplate(shared)
            ownerApi.changeLedgerMemberRole(ledger.id, membership.id, RemoteLedgerMember.ROLE_MEMBER)
            val own = queue(repo, adminApi, TemplateEntity(0, "Own confirmed", 3, ownCategory.id, ownAccount.id, 1234, "own"))
            try { SyncEngine(adminApi, repo, false).sync(); fail("Revoked request must be reported") }
            catch (_: IllegalArgumentException) { }
            val pending = repo.templateCreations().single()
            assertEquals(shared.clientSessionId, pending.clientSessionId); assertEquals(shared.payloadJson, pending.payloadJson)
            assertEquals(TemplateCreationEntity.PENDING, pending.state); assertTrue(pending.lastError.isNotBlank())
            assertEquals(committed.id, adminApi.parseTemplateResponse(adminApi.listTemplates(1, ledger.id), ledger.id).single().id)
            val ownCreated = adminApi.parseTemplateResponse(adminApi.listTemplates(1), 0).single()
            assertEquals("Own confirmed", ownCreated.name)
            assertEquals(baselineShared, ownerApi.listAccounts(ledger.id) + ownerApi.listTransactions(ledger.id))
            assertEquals(baselineOwn, adminApi.listAccounts() + adminApi.listTransactions())
            adminApi.deleteTemplate(ownCreated.id)
            val deleted = adminApi.createTemplate(own)
            assertEquals(ownCreated.id, deleted.id); assertTrue(deleted.deleted)
            assertTrue(adminApi.parseTemplateResponse(adminApi.listTemplates(1)).isEmpty())
        } finally { db.close(); actors.forEach { context.deleteSharedPreferences(it.store.namespace) } }
    }
}
