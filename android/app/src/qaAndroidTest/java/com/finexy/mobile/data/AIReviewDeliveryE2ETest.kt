package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AIReviewDeliveryE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val stores = mutableListOf<SecureStore>()
    private val databases = mutableSetOf<String>()
    private fun url(key: String, port: Int): String {
        check(context.packageName == "com.finexy.mobile.qa")
        val url = requireNotNull(InstrumentationRegistry.getArguments().getString(key)).toHttpUrl()
        require(url.scheme == "http" && url.host == "10.0.2.2" && url.port == port && url.encodedPath == "/" &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        return url.toString().trimEnd('/')
    }
    private data class User(val store: SecureStore, val api: FinexyApi, val name: String)
    private suspend fun user(url: String): User {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(18)
        val store = SecureStore(context, "ai-delivery-$suffix").also(stores::add)
        val name = "aid$suffix"; val password = UUID.randomUUID().toString()
        store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
        AccountSecurity(FinexyApi(store)).register(name, "$name@example.invalid", "Synthetic AI delivery", password, "CNY")
        store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(name, password).token, durable = true)
        store.put("local_mode", "false", durable = true)
        return User(store, FinexyApi(store), name)
    }
    private fun repository(user: User): TransactionRepository {
        val identity = LedgerScope.identity(user.store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), user.store.get(FinexyApi.KEY_TOKEN), false)
        val name = LedgerScope.databaseName(user.store, identity)
        require(name != "finexy.db" && name.startsWith("finexy-")); databases += name
        UserPreferenceStore(user.store, name).save(UserPreferences(autoUpdateExchangeRatesData = false))
        return TransactionRepository(context, FinexyDatabase.get(context, name), false)
    }
    private suspend fun worker(user: User): ListenableWorker.Result {
        val identity = LedgerScope.identity(user.store.get(FinexyApi.KEY_SERVER_URL).orEmpty(), user.store.get(FinexyApi.KEY_TOKEN), false)
        return TestListenableWorkerBuilder<SyncWorker>(context).setInputData(Data.Builder()
            .putString(SyncWorker.KEY_IDENTITY, identity).putString(SyncWorker.KEY_STORE_NAMESPACE, user.store.namespace).build()).build().doWork()
    }
    private fun source(ledger: Long) = JSONObject().put("ledgerId", ledger.toString()).put("clientSessionId", UUID.randomUUID().toString())
        .put("sourceType", 1).put("sourceText", "\u001cSynthetic delivery source\u001f").put("failureReason", "").toString()
    private fun confirmation(ledger: Long, id: Long, account: Long, category: Long) = JSONObject()
        .put("ledgerId", ledger.toString()).put("id", id.toString()).put("transaction", JSONObject()
            .put("ledgerId", ledger.toString()).put("clientSessionId", UUID.randomUUID().toString()).put("type", 3)
            .put("time", System.currentTimeMillis() / 1000).put("utcOffset", 480).put("categoryId", category.toString())
            .put("sourceAccountId", account.toString()).put("destinationAccountId", "0").put("sourceAmount", -123)
            .put("destinationAmount", 0).put("hideAmount", false).put("tagIds", JSONArray()).put("pictureIds", JSONArray()).put("comment", "Synthetic delivery once")).toString()
    @After fun cleanup() {
        databases.forEach { FinexyDatabase.closeInstance(it); context.deleteDatabase(it) }
        stores.forEach { require(it.namespace.startsWith("ai-delivery-")); context.deleteSharedPreferences(it.namespace) }
    }

    @Test fun revokedPermissionKeepsOriginalRequestUntilExplicitWorkerRetry() = runBlocking<Unit> {
        val target = url("finexy.e2e.url", 18142)
        val owner = user(target); val member = user(target)
        val ledger = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic delivery roles", "isolated")
        val invitation = owner.api.createLedgerInvitation(ledger.id, member.name, 3)
        member.api.acceptLedgerInvitation(invitation.token)
        val accountPayload = AccountDraft("Synthetic delivery CNY", 1, "CNY", 10000).toCreatePayload()
            .put("ledgerId", ledger.id.toString()).put("balanceTime", 946684800)
        val account = JSONObject(owner.api.request("v1/accounts/add.json", accountPayload.toString())).getJSONObject("result").getString("id").toLong()
        val category = owner.api.parseCategoryResponse(owner.api.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }.id
        val item = member.api.createLedgerAIReviewItem(ledger.id, source(ledger.id))
        val repo = repository(member); val draft = repo.aiReview.saveDraft(ledger.id, item.id, "{\"amount\":\"1.+\"}")
        val write = repo.aiReview.enqueue(draft, "confirm", confirmation(ledger.id, item.id, account, category))
        val memberId = member.api.listLedgerMembers(ledger.id).single { it.isCurrentUser }.id
        owner.api.changeLedgerMemberRole(ledger.id, memberId, 4)
        assertTrue(worker(member) is ListenableWorker.Result.Failure)
        val retained = repo.aiReview.requests().single()
        assertEquals(write.payloadJson, retained.payloadJson); assertEquals(write.requestId, retained.requestId)
        assertEquals(AIReviewRequestEntity.PENDING, retained.state); assertEquals("permission-denied", retained.lastErrorCode)
        assertEquals(draft, repo.aiReview.draft(ledger.id, item.id)); assertEquals(SyncRunState.FAILED, repo.syncStatus()?.state)
        assertEquals(1, owner.api.getLedgerAIReviewItem(ledger.id, item.id).status)
        assertEquals(10000L, owner.api.parseAccountResponse(owner.api.listAccounts(ledger.id)).single().balanceMinor)
        owner.api.changeLedgerMemberRole(ledger.id, memberId, 3)
        assertTrue(worker(member) is ListenableWorker.Result.Success)
        assertTrue(repo.aiReview.requests().isEmpty()); assertNull(repo.aiReview.draft(ledger.id, item.id))
        assertEquals(10123L, owner.api.parseAccountResponse(owner.api.listAccounts(ledger.id)).single().balanceMinor)
        assertEquals(2, owner.api.getLedgerAIReviewItem(ledger.id, item.id).status)
    }

    @Test fun oldServiceCannotReplaceDurableCreateOrConfirmationWithLegacyWrites() = runBlocking<Unit> {
        val actor = user(url("finexy.old.url", 18143))
        val repo = repository(actor)
        val legacy = actor.api.createAIReviewItem(1, "Synthetic old service source", null)
        val createDraft = repo.aiReview.saveDraft(0, 0, "{\"sourceText\":\"Synthetic original\"}")
        val pendingCreate = repo.aiReview.enqueue(createDraft, "create", source(0))
        val confirmDraft = repo.aiReview.saveDraft(0, legacy.id, "{\"amount\":\"1.+\"}")
        val pendingConfirm = repo.aiReview.enqueue(confirmDraft, "confirm", confirmation(0, legacy.id, 10, 20))
        val baselineTransactions = actor.api.listTransactions()
        val result = AIReviewDelivery.forApi(repo.aiReview, actor.api).push()
        assertEquals(2, result.errors.size); assertEquals(0, result.posted)
        val retained = repo.aiReview.requests()
        assertEquals(setOf(pendingCreate.payloadJson, pendingConfirm.payloadJson), retained.map { it.payloadJson }.toSet())
        assertTrue(retained.all { it.state == AIReviewRequestEntity.PENDING && it.lastErrorCode == "unsupported-service" })
        assertEquals(createDraft, repo.aiReview.draft(0, 0)); assertEquals(confirmDraft, repo.aiReview.draft(0, legacy.id))
        assertEquals(listOf(legacy.id), actor.api.listAIReviewItems().map { it.id })
        assertEquals(baselineTransactions, actor.api.listTransactions())
        assertTrue(repo.ledgerWrites().isEmpty()); assertTrue(repo.pendingAIReviewResolutions().isEmpty())
    }
}
