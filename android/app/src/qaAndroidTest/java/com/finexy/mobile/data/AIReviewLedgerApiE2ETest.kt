package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.ByteArrayOutputStream

/** Real HTTP contracts, private QA identities, no main application database or preferences. */
@RunWith(AndroidJUnit4::class)
class AIReviewLedgerApiE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespaces = mutableListOf<String>()
    private lateinit var url: String
    private data class User(val api: FinexyApi, val username: String, val store: SecureStore)

    @Before fun requireDisposableTarget() {
        check(context.packageName == "com.finexy.mobile.qa")
        val target = requireNotNull(InstrumentationRegistry.getArguments().getString("finexy.e2e.url")).toHttpUrl()
        require(target.scheme == "http" && target.host == "10.0.2.2" && target.port == 18139 &&
            target.encodedPath == "/" && target.query == null && target.fragment == null &&
            target.username.isEmpty() && target.password.isEmpty())
        url = target.toString().trimEnd('/')
    }

    @After fun clearOnlyOwnedNamespaces() {
        namespaces.forEach { name -> check(name.startsWith("ai-ledger-api-")); context.deleteSharedPreferences(name) }
    }

    private suspend fun user(): User {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(18)
        val name = "aia$suffix"
        val password = UUID.randomUUID().toString()
        val namespace = "ai-ledger-api-$suffix".also(namespaces::add)
        val store = SecureStore(context, namespace)
        store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
        try {
            AccountSecurity(FinexyApi(store)).register(name, "$name@example.invalid", "Synthetic AI API", password, "CNY")
        } catch (error: ApiException) {
            throw AssertionError("Synthetic registration rejected: HTTP ${error.status}, code=${error.serverCode}")
        }
        store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(name, password).token, durable = true)
        return User(FinexyApi(store), name, store)
    }

    private fun creation(ledger: Long, text: String = "Synthetic source") = JSONObject()
        .put("ledgerId", ledger.toString()).put("clientSessionId", UUID.randomUUID().toString())
        .put("sourceType", 1).put("sourceText", text).put("failureReason", "").toString()

    private fun confirmation(row: LedgerAIReviewItem, account: Long, category: Long,
                             amount: Long = -123, destination: Long = 0, destinationAmount: Long = 0, type: Int = 3) = JSONObject()
        .put("ledgerId", row.ledgerId.toString()).put("id", row.id.toString()).put("transaction", JSONObject()
            .put("ledgerId", row.ledgerId.toString()).put("clientSessionId", UUID.randomUUID().toString())
            .put("type", type).put("time", System.currentTimeMillis() / 1000).put("utcOffset", 480)
            .put("categoryId", category.toString()).put("sourceAccountId", account.toString())
            .put("destinationAccountId", destination.toString()).put("sourceAmount", amount)
            .put("destinationAmount", destinationAmount).put("hideAmount", false)
            .put("tagIds", JSONArray()).put("pictureIds", JSONArray()).put("comment", "Synthetic atomic API")).toString()

    private suspend fun denied(status: Int, operation: suspend () -> Unit) {
        try { operation(); fail("Expected isolated service rejection") }
        catch (error: ApiException) { assertEquals(status, error.status) }
    }

    private suspend fun balance(api: FinexyApi, ledger: Long, account: Long) =
        api.parseAccountResponse(api.listAccounts(ledger)).single { it.id == account }.balanceMinor

    // An opening balance in the same second may occupy a later sequence than
    // the confirmation's minimum sequence. Keep this fixture's baseline earlier.
    private suspend fun account(api: FinexyApi, name: String, currency: String, amount: Long): RemoteAccount {
        val payload = AccountDraft(name, 1, currency, amount).toCreatePayload().put("balanceTime", 946684800)
        val result = JSONObject(api.request("v1/accounts/add.json", payload.toString())).getJSONObject("result")
        return api.parseAccountResponse(JSONObject().put("result", JSONArray().put(result)).toString()).single()
    }

    private suspend fun post(api: FinexyApi, row: LedgerAIReviewItem, payload: String): LedgerAIConfirmationReceipt = try {
        api.confirmLedgerAIReviewItem(row.ledgerId, row.id, payload)
    } catch (error: ApiException) {
        throw AssertionError("Synthetic atomic confirmation rejected: HTTP ${error.status}, code=${error.serverCode}")
    }

    @Test fun stableCreationAtomicRefundTransfersPaginationAndDeletedReplay() = runBlocking<Unit> {
        val api = user().api
        val sentinel = account(api, "Default sentinel", "CNY", 5000)
        val ledger = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic AI A", "isolated")
        val other = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic AI B", "isolated")
        val account = account(api, "Synthetic CNY", "CNY", 10000)
        val destination = account(api, "Synthetic USD", "USD", 7000)
        api.moveAccountToLedger(account.id, ledger.id); api.moveAccountToLedger(destination.id, ledger.id)
        val category = api.parseCategoryResponse(api.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }.id
        val root = api.createCategory(CategoryDraft("Synthetic transfers", 3, 0))
        val transferCategory = api.createCategory(CategoryDraft("Synthetic transfer leaf", 3, root.id)).id
        val defaultBaseline = api.listTransactions()
        api.requireLedgerAIReview(0); api.requireLedgerAIReview(ledger.id, recognition = true)
        val payload = creation(ledger.id, "\u0085 Synthetic source\u00a0")
        val row = api.createLedgerAIReviewItem(ledger.id, payload)
        assertEquals("Synthetic source", row.sourceText)
        assertEquals(row.id, api.createLedgerAIReviewItem(ledger.id, payload).id)
        denied(409) { api.createLedgerAIReviewItem(ledger.id, JSONObject(payload).put("sourceText", "Different").toString()) }
        val original = confirmation(row, account.id, category)
        val receipt = post(api, row, original)
        assertEquals(receipt, api.confirmLedgerAIReviewItem(ledger.id, row.id, original))
        denied(409) { api.confirmLedgerAIReviewItem(ledger.id, row.id,
            JSONObject(original).apply { getJSONObject("transaction").put("sourceAmount", -124) }.toString()) }
        assertEquals(receipt.transactionId, api.getLedgerAIReviewItem(ledger.id, row.id).transactionId)
        assertEquals(10123L, balance(api, ledger.id, account.id))
        assertEquals(1, api.listLedgerAIReviewItems(ledger.id, 2).size)

        for ((sourceId, destId, amounts) in listOf(Triple(account.id, destination.id, 150L to 125L),
            Triple(account.id, 0L, 40L to 0L), Triple(0L, destination.id, 0L to 30L))) {
            val transfer = api.createLedgerAIReviewItem(ledger.id, creation(ledger.id, "Synthetic transfer"))
            val snapshot = confirmation(transfer, sourceId, transferCategory, amounts.first, destId, amounts.second, 4)
            val posted = api.confirmLedgerAIReviewItem(ledger.id, transfer.id, snapshot)
            assertEquals(posted, api.confirmLedgerAIReviewItem(ledger.id, transfer.id, snapshot))
        }
        assertEquals(9933L, balance(api, ledger.id, account.id)); assertEquals(7155L, balance(api, ledger.id, destination.id))
        assertEquals(5000L, balance(api, 0, sentinel.id)); assertEquals(defaultBaseline, api.listTransactions())
        assertTrue(api.listLedgerAIReviewItems(other.id).isEmpty())
        val defaultItem = api.createLedgerAIReviewItem(0, creation(0))
        assertEquals(defaultItem.id, api.listLedgerAIReviewItems(0).single().id)
        val defaultSnapshot = confirmation(defaultItem, sentinel.id, category)
        val defaultReceipt = api.confirmLedgerAIReviewItem(0, defaultItem.id, defaultSnapshot)
        assertEquals(defaultReceipt, api.confirmLedgerAIReviewItem(0, defaultItem.id, defaultSnapshot))
        assertEquals(5123L, balance(api, 0, sentinel.id))
        assertEquals(defaultReceipt.transactionId, api.getLedgerAIReviewItem(0, defaultItem.id).transactionId)
        val tombstonePayload = creation(ledger.id)
        val tombstone = api.createLedgerAIReviewItem(ledger.id, tombstonePayload)
        api.changeLedgerAIReviewItem(ledger.id, tombstone.id, "dismiss")
        assertEquals(3, api.listLedgerAIReviewItems(ledger.id, 3).single().status)
        api.changeLedgerAIReviewItem(ledger.id, tombstone.id, "restore")
        api.changeLedgerAIReviewItem(ledger.id, tombstone.id, "delete")
        val replay = api.createLedgerAIReviewItem(ledger.id, tombstonePayload)
        assertEquals(tombstone.id, replay.id); assertTrue(replay.deleted); assertFalse(replay.editable)
        val created = (1..101).map { api.createLedgerAIReviewItem(ledger.id, creation(ledger.id, "Synthetic page $it")).id }
        assertEquals(created.reversed(), api.listLedgerAIReviewItems(ledger.id).map { it.id })
    }

    @Test fun fourRolesActualObjectScopeAndPermissionRevocation() = runBlocking<Unit> {
        val owner = user(); val admin = user(); val member = user(); val viewer = user(); val outsider = user()
        val ledger = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic roles", "isolated")
        val other = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic other", "isolated")
        for ((person, role) in listOf(admin to 2, member to 3, viewer to 4)) {
            val invitation = owner.api.createLedgerInvitation(ledger.id, person.username, if (role == 2) 3 else role)
            person.api.acceptLedgerInvitation(invitation.token)
            if (role == 2) owner.api.changeLedgerMemberRole(ledger.id,
                person.api.listLedgerMembers(ledger.id).single { it.isCurrentUser }.id, 2)
        }
        val account = account(owner.api, "Roles account", "CNY", 10000)
        owner.api.moveAccountToLedger(account.id, ledger.id)
        val category = owner.api.parseCategoryResponse(owner.api.listCategories(ledgerId = ledger.id)).first { it.type == 2 && it.parentId > 0 }.id
        val ownerRow = owner.api.createLedgerAIReviewItem(ledger.id, creation(ledger.id))
        val memberRow = member.api.createLedgerAIReviewItem(ledger.id, creation(ledger.id))
        for (person in listOf(owner, admin, member, viewer)) {
            person.api.requireLedgerAIReview(ledger.id)
            val rows = person.api.listLedgerAIReviewItems(ledger.id)
            assertEquals(2, rows.size)
            assertEquals(person == owner || person == admin, rows.single { it.id == ownerRow.id }.editable)
            assertEquals(person != viewer, rows.single { it.id == memberRow.id }.editable)
        }
        denied(403) { viewer.api.createLedgerAIReviewItem(ledger.id, creation(ledger.id)) }
        denied(403) { outsider.api.listLedgerAIReviewItems(ledger.id) }
        denied(403) { member.api.changeLedgerAIReviewItem(ledger.id, ownerRow.id, "dismiss") }
        denied(403) { viewer.api.confirmLedgerAIReviewItem(ledger.id, memberRow.id, confirmation(memberRow, account.id, category)) }
        denied(404) { owner.api.getLedgerAIReviewItem(other.id, memberRow.id) }
        val stale = confirmation(memberRow, account.id, category)
        val memberId = member.api.listLedgerMembers(ledger.id).single { it.isCurrentUser }.id
        owner.api.changeLedgerMemberRole(ledger.id, memberId, 4)
        denied(403) { member.api.confirmLedgerAIReviewItem(ledger.id, memberRow.id, stale) }
        assertEquals(10000L, balance(owner.api, ledger.id, account.id))
        assertEquals(1, owner.api.getLedgerAIReviewItem(ledger.id, memberRow.id).status)
        val receipt = post(admin.api, ownerRow, confirmation(ownerRow, account.id, category))
        assertTrue(receipt.transactionId > 0); assertEquals(10123L, balance(owner.api, ledger.id, account.id))
        assertEquals(1, owner.api.listLedgerAIReviewItems(ledger.id, 2).size)
    }

    @Test fun textAndMultipartImageUseSelectedResourcesWithoutFinancialWrites() = runBlocking<Unit> {
        val owner = user(); val viewer = user()
        val ledger = owner.api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic recognition", "isolated")
        val selected = account(owner.api, "SyntheticSelectedAndroidAccount", "CNY", 10000)
        owner.api.moveAccountToLedger(selected.id, ledger.id)
        account(owner.api, "SyntheticForeignAndroidAccount", "CNY", 9000)
        val root = owner.api.createCategory(CategoryDraft("Synthetic recognition parent", 2, 0))
        val leaf = owner.api.createCategory(CategoryDraft("SyntheticAndroidExpense", 2, root.id))
        val invitation = owner.api.createLedgerInvitation(ledger.id, viewer.username, 4)
        viewer.api.acceptLedgerInvitation(invitation.token)
        val mock = OkHttpClient()
        val rawResult = JSONObject().put("type", "expense").put("account", selected.name)
            .put("category", leaf.name).put("amount", "-1.23").put("description", "Synthetic recognition")
        val control = JSONObject().put("result", rawResult).put("required", JSONArray().put(selected.name).put(leaf.name))
            .put("forbidden", JSONArray().put("SyntheticForeignAndroidAccount"))
        mock.newCall(Request.Builder().url("http://10.0.2.2:18140/control")
            .post(control.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(204, it.code) }
        val text = owner.api.recognizeLedgerTransactionText(ledger.id, "Synthetic receipt")
        assertEquals(ledger.id, text.ledgerId); assertEquals(selected.id, text.sourceAccountId)
        assertEquals(leaf.id, text.categoryId); assertEquals(-123L, text.sourceAmountMinor)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); stream.toByteArray() }
        bitmap.recycle()
        val image = viewer.api.recognizeLedgerReceiptImage(ledger.id, bytes, "synthetic.png", "image/png")
        assertEquals(ledger.id, image.ledgerId); assertEquals(selected.id, image.sourceAccountId)
        assertEquals(-123L, image.sourceAmountMinor)
        mock.newCall(Request.Builder().url("http://10.0.2.2:18140/audit").build()).execute().use {
            val audit = JSONObject(requireNotNull(it.body).string())
            assertEquals(2, audit.getInt("requests")); assertEquals(0, audit.getInt("violations"))
        }
        assertEquals(10000L, balance(owner.api, ledger.id, selected.id))
        assertTrue(owner.api.listLedgerAIReviewItems(ledger.id).isEmpty())
        val creationSnapshot = JSONObject(creation(ledger.id, "Synthetic receipt"))
            .put("recognizedData", JSONObject(text.toJson().toString())).toString()
        val queued = owner.api.createLedgerAIReviewItem(ledger.id, creationSnapshot)
        assertEquals(selected.id, queued.recognized!!.sourceAccountId)
        assertEquals(-123L, queued.recognized.sourceAmountMinor)
        assertEquals(queued.id, owner.api.createLedgerAIReviewItem(ledger.id, creationSnapshot).id)
        assertEquals(10000L, balance(owner.api, ledger.id, selected.id))
        denied(403) { viewer.api.createLedgerAIReviewItem(ledger.id, creation(ledger.id)) }

        // A response from the old server must not reach a caller after settings change.
        val heldControl = JSONObject(control.toString()).put("hold", true)
        mock.newCall(Request.Builder().url("http://10.0.2.2:18140/control")
            .post(heldControl.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(204, it.code) }
        val pending = async {
            try { owner.api.recognizeLedgerTransactionText(ledger.id, "Synthetic held receipt"); false }
            catch (_: IllegalStateException) { true }
        }
        try {
            var reachedModel = false
            repeat(100) {
                if (!reachedModel) {
                    reachedModel = mock.newCall(Request.Builder().url("http://10.0.2.2:18140/audit").build()).execute().use {
                        JSONObject(requireNotNull(it.body).string()).getInt("requests") == 1
                    }
                    if (!reachedModel) delay(25)
                }
            }
            assertTrue("Held request must actually reach the isolated model", reachedModel)
            owner.store.put(FinexyApi.KEY_SERVER_URL, "http://10.0.2.2:18140", durable = true)
        } finally {
            mock.newCall(Request.Builder().url("http://10.0.2.2:18140/release").post(ByteArray(0).toRequestBody()).build()).execute().close()
        }
        assertTrue("Old-server response must be rejected after session change", pending.await())
    }
}
