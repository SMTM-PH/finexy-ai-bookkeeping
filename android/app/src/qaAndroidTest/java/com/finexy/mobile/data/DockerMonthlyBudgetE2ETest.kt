package com.finexy.mobile.data

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Mandatory real HTTP tests against the guarded, no-volume budget candidate, with random users only. */
@RunWith(AndroidJUnit4::class)
class DockerMonthlyBudgetE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requireIsolatedBudgetEnvironment() { monthlyBudgetQaUrl() }
    private val month get() = YearMonth.now().let { it.year * 100 + it.monthValue }

    private class Fixture(val owner: FinexyApi, val admin: FinexyApi, val member: FinexyApi, val viewer: FinexyApi,
                          val ledgerA: Long, val ledgerB: Long, val categoryId: Long, val adminId: Long, val memberId: Long,
                          val namespaces: List<String>)

    private suspend fun fixture(): Fixture {
        val url = monthlyBudgetQaUrl()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val namespaces = mutableListOf<String>()
        suspend fun register(role: String): FinexyApi {
            val name = "budget$suffix$role"
            val password = UUID.randomUUID().toString()
            val categories = JSONArray().put(JSONObject().put("name", "Budget QA Parent").put("type", 2).put("icon", "1")
                .put("color", "F05537").put("subCategories", JSONArray().put(JSONObject().put("name", "Budget QA Expense")
                    .put("type", 2).put("icon", "1").put("color", "F05537"))))
            val payload = JSONObject().put("username", name).put("email", "$name@example.com").put("nickname", "Budget QA")
                .put("password", password).put("language", "zh-Hans").put("defaultCurrency", "CNY").put("categories", categories)
            val request = Request.Builder().url("$url/api/register.json").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            OkHttpClient().newCall(request).execute().use { response ->
                check(response.isSuccessful && JSONObject(response.body!!.string()).optBoolean("success")) { "Disposable budget registration failed" }
            }
            val namespace = "budget-api-fixture-$suffix-$role"
            namespaces += namespace
            val store = SecureStore(context, namespace)
            store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(name, password).token, durable = true)
            return FinexyApi(store)
        }
        try {
            val owner = register("o"); val admin = register("a"); val member = register("m"); val viewer = register("v")
            owner.createAccount(AccountDraft("Budget QA Default Sentinel", 1, "CNY", 4567))
            val ledgerA = owner.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Budget QA A", "isolated").id
            val ledgerB = owner.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Budget QA B", "isolated").id
            suspend fun join(api: FinexyApi, role: Int): Long {
                val invitation = owner.createLedgerInvitation(ledgerA, "Budget QA", if (role == 4) 4 else 3)
                api.acceptLedgerInvitation(invitation.token)
                val me = api.listLedgerMembers(ledgerA).single { it.isCurrentUser && it.status == 1 }
                if (role == 2) owner.changeLedgerMemberRole(ledgerA, me.id, 2)
                return me.id
            }
            val adminId = join(admin, 2); val memberId = join(member, 3); join(viewer, 4)
            val category = owner.listMonthlyBudgetCategories(ledgerA).firstOrNull() ?: error("Disposable budget categories unavailable")
            return Fixture(owner, admin, member, viewer, ledgerA, ledgerB, category.id, adminId, memberId, namespaces)
        } catch (failure: Exception) {
            namespaces.forEach(context::deleteSharedPreferences)
            throw failure
        }
    }
    private fun cleanup(fixture: Fixture) { fixture.namespaces.forEach { namespace -> check(namespace.startsWith("budget-api-fixture-")); context.deleteSharedPreferences(namespace) } }
    private suspend fun rejected(status: Int, action: suspend () -> Unit) {
        try { action(); fail("The budget write must be rejected") }
        catch (error: ApiException) { assertTrue("The rejected operation must use the expected HTTP status", error.status == status) }
    }

    @Test fun realApiFourRolesLedgerIsolationSaveAndClearKeepDefaultUntouched() = runBlocking {
        val fixture = fixture()
        try {
            val owner = fixture.owner
            val defaultBudget = owner.setMonthlyBudget(0, MonthlyBudgetDraft(month, 9000, "CNY"))
            val draft = MonthlyBudgetDraft(month, 10000, "CNY", mapOf(fixture.categoryId to 5000))
            var budgetA = owner.setMonthlyBudget(fixture.ledgerA, draft)
            val budgetB = owner.setMonthlyBudget(fixture.ledgerB, MonthlyBudgetDraft(month, 20000, "USD"))
            val beforeAccounts = owner.parseAccountResponse(owner.listAccounts()).map { it.id to it.balanceMinor }
            val beforeDefaultRows = owner.parseTransactionResponse(owner.listTransactions()).map { it.id }
            assertTrue("The default-balance sentinel must be present", beforeAccounts.isNotEmpty())
            val impact = owner.previewLedgerDelete(fixture.ledgerA)
            assertTrue("A ledger budget must be an explicit delete blocker", impact.monthlyBudgetCount == 1 && !impact.canDelete && "monthlyBudgets" in impact.blockingReasons)
            listOf(owner, fixture.admin, fixture.member, fixture.viewer).forEach { actor ->
                assertTrue("All four active roles must read the selected ledger budget", actor.getMonthlyBudget(fixture.ledgerA, month) == budgetA)
            }
            listOf(fixture.member, fixture.viewer).forEach { actor ->
                rejected(403) { actor.setMonthlyBudget(fixture.ledgerA, draft.copy(expectedRevision = budgetA.revision)) }
                rejected(403) { actor.deleteMonthlyBudget(fixture.ledgerA, month, budgetA.revision) }
            }
            val foreignCategory = fixture.admin.listMonthlyBudgetCategories(0).first().id
            rejected(400) { fixture.admin.setMonthlyBudget(fixture.ledgerA, draft.copy(categoryAmounts = mapOf(foreignCategory to 1000), expectedRevision = budgetA.revision)) }
            budgetA = fixture.admin.setMonthlyBudget(fixture.ledgerA, draft.copy(amountMinor = 11000, expectedRevision = budgetA.revision))
            assertTrue("Admin save must be visible to the ledger owner", owner.getMonthlyBudget(fixture.ledgerA, month) == budgetA)
            fixture.admin.deleteMonthlyBudget(fixture.ledgerA, month, budgetA.revision)
            assertTrue("Only explicit null is the cleared budget", owner.getMonthlyBudget(fixture.ledgerA, month) == null)
            assertTrue("Ledger B must remain unchanged", owner.getMonthlyBudget(fixture.ledgerB, month) == budgetB)
            assertTrue("Default ledger budget must remain unchanged", owner.getMonthlyBudget(0, month) == defaultBudget)
            assertTrue("Budget writes must not change account balances", beforeAccounts == owner.parseAccountResponse(owner.listAccounts()).map { it.id to it.balanceMinor })
            assertTrue("Budget writes must not change default transactions", beforeDefaultRows == owner.parseTransactionResponse(owner.listTransactions()).map { it.id })
            rejected(403) { fixture.viewer.getMonthlyBudget(fixture.ledgerB, month) }
        } finally { cleanup(fixture) }
    }

    @Test fun staleRevisionsDeleteRecreateAndLiveDowngradeRejectCachedWrites() = runBlocking {
        val fixture = fixture()
        try {
            val owner = fixture.owner
            val draft = MonthlyBudgetDraft(month, 10000, "CNY")
            val first = owner.setMonthlyBudget(fixture.ledgerA, draft)
            val newer = fixture.admin.setMonthlyBudget(fixture.ledgerA, draft.copy(amountMinor = 12000, expectedRevision = first.revision))
            rejected(409) { owner.setMonthlyBudget(fixture.ledgerA, draft.copy(expectedRevision = first.revision)) }
            rejected(409) { owner.deleteMonthlyBudget(fixture.ledgerA, month, first.revision) }
            assertTrue("Conflict must preserve the latest server budget", owner.getMonthlyBudget(fixture.ledgerA, month) == newer)
            owner.deleteMonthlyBudget(fixture.ledgerA, month, newer.revision)
            val recreated = owner.setMonthlyBudget(fixture.ledgerA, draft)
            assertTrue("A recreated scope must have a later revision", recreated.revision > newer.revision)
            rejected(409) { fixture.admin.deleteMonthlyBudget(fixture.ledgerA, month, newer.revision) }
            owner.changeLedgerMemberRole(fixture.ledgerA, fixture.adminId, 4)
            rejected(403) { fixture.admin.setMonthlyBudget(fixture.ledgerA, draft.copy(expectedRevision = recreated.revision)) }
            assertTrue("A downgraded viewer can still read", fixture.admin.getMonthlyBudget(fixture.ledgerA, month) == recreated)
            owner.removeLedgerMember(fixture.ledgerA, fixture.memberId)
            rejected(403) { fixture.member.getMonthlyBudget(fixture.ledgerA, month) }
        } finally { cleanup(fixture) }
    }

    @Test fun realClientMalformedResponsesCannotBeUnsetOrInventCurrency() = runBlocking {
        val namespace = "budget-api-fixture-malformed-${UUID.randomUUID()}"
        val store = SecureStore(context, namespace)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val invalidBudget = JSONObject().put("id", "90").put("ledgerId", "9").put("yearMonth", month).put("amount", 10000)
                .put("currency", "ZZZ").put("categoryAmounts", JSONObject()).put("revision", 1)
            val responses = listOf("{\"success\":true}", JSONObject().put("success", true).put("result", invalidBudget).toString())
            responses.forEach { body ->
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 10_000
                    store.put(FinexyApi.KEY_SERVER_URL, "http://127.0.0.1:${server.localPort}", durable = true)
                    val witness = executor.submit<String> {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val reader = socket.getInputStream().bufferedReader()
                            val path = reader.readLine()
                            while (!reader.readLine().isNullOrEmpty()) { /* bounded request headers only */ }
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                            path
                        }
                    }
                    try { FinexyApi(store).getMonthlyBudget(9, month); fail("Malformed success must not become an unset budget") }
                    catch (_: Exception) { /* The response must be rejected, with no financial logging. */ }
                    assertTrue("The client must use the actual budget route and explicit ledger", witness.get(10, TimeUnit.SECONDS).startsWith("GET /api/v1/budget/monthly/get.json?ledgerId=9&yearMonth="))
                }
            }
        } finally { executor.shutdownNow(); context.deleteSharedPreferences(namespace) }
    }
}
