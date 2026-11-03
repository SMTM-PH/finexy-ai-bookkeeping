package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real production API calls against an explicitly disposable service; never a Room/transport fake. */
@RunWith(AndroidJUnit4::class)
class LedgerReportApiE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespaces = mutableListOf<String>()
    private lateinit var url: String
    private var transactionOrdinal = 0L
    private data class User(val api: FinexyApi, val store: SecureStore, val username: String)
    private data class Seed(val owner: User, val ledgerA: Long, val ledgerB: Long,
        val source: Long, val destination: Long, val defaultAccount: Long, val otherAccount: Long,
        val expenseCategory: Long, val tag: Long, val income: Long, val refund: Long, val adjustment: Long,
        val transfer: Long, val query: LedgerReportQuery)

    @Before fun requireExplicitDisposableQaTarget() {
        check(context.packageName == "com.finexy.mobile.qa") { "Report HTTP tests require the isolated QA application" }
        val args = InstrumentationRegistry.getArguments()
        val supplied = args.getString("finexy.e2e.url")
        assumeTrue("No disposable report service URL supplied; HTTP coverage was skipped", !supplied.isNullOrBlank())
        check(args.getString("finexy.e2e.disposable") == "true") { "Explicit disposable-service marker required" }
        val target = requireNotNull(supplied).toHttpUrl()
        require(target.scheme == "http" && target.host == "10.0.2.2" && target.port == 18155 &&
            target.encodedPath == "/" && target.query == null && target.fragment == null &&
            target.username.isEmpty() && target.password.isEmpty()) { "Only the dedicated emulator-host report test port is allowed" }
        url = target.toString().trimEnd('/')
    }

    @After fun clearOnlyThisTestsPrivateCredentials() {
        namespaces.forEach { namespace ->
            check(namespace.startsWith("report-api-"))
            check(context.deleteSharedPreferences(namespace)) { "Could not clear the owned QA credential namespace" }
        }
    }

    private suspend fun user(): User {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(18)
        val username = "rpa$suffix"
        val password = UUID.randomUUID().toString()
        val store = SecureStore(context, "report-api-$suffix".also(namespaces::add))
        store.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
        AccountSecurity(FinexyApi(store)).register(username, "$username@example.invalid", "Synthetic report HTTP", password, "CNY")
        store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
        return User(FinexyApi(store), store, username)
    }

    private suspend fun account(api: FinexyApi, ledger: Long, name: String, currency: String, balance: Long,
        balanceTime: Long = 946684800): Long {
        val payload = AccountDraft(name, 1, currency, balance).toCreatePayload()
            .put("ledgerId", ledger.toString()).put("balanceTime", balanceTime)
        return JSONObject(api.request("v1/accounts/add.json", payload.toString())).getJSONObject("result").getString("id").toLong()
    }

    private suspend fun leaf(api: FinexyApi, name: String, categoryType: Int): Long {
        val parent = api.createCategory(CategoryDraft("$name parent", categoryType, 0))
        return api.createCategory(CategoryDraft(name, categoryType, parent.id)).id
    }

    private suspend fun post(api: FinexyApi, ledger: Long, type: Int, category: Long, source: Long, amount: Long,
        destination: Long = 0, destinationAmount: Long = 0, tags: List<Long> = emptyList(), hidden: Boolean = false,
        comment: String = "Synthetic report HTTP"): Long {
        val payload = JSONObject().put("ledgerId", ledger.toString()).put("type", type)
            .put("categoryId", category.toString()).put("sourceAccountId", source.toString()).put("sourceAmount", amount)
            .put("destinationAccountId", destination.toString()).put("destinationAmount", destinationAmount)
            .put("time", 1792497600L + transactionOrdinal++).put("utcOffset", 0).put("tagIds", JSONArray(tags.map(Long::toString)))
            .put("pictureIds", JSONArray()).put("hideAmount", hidden).put("comment", comment)
        return api.parseWrittenTransaction(api.addTransaction(payload, UUID.randomUUID().toString())).id
    }

    private suspend fun seed(): Seed {
        val owner = user(); val api = owner.api
        val a = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic report A", "Disposable HTTP fixture").id
        val b = api.createLedger(RemoteLedger.TYPE_PERSONAL, 0, "Synthetic report B", "Disposable HTTP fixture").id
        val source = account(api, a, "=Synthetic, \"CNY\"", "CNY", 10000)
        val destination = account(api, a, "Synthetic USD", "USD", 2000)
        val defaultAccount = account(api, 0, "Synthetic default", "CNY", 7000)
        val otherAccount = account(api, b, "Synthetic other", "CNY", 5000)
        val incomeCategory = leaf(api, "Synthetic income", 1)
        val expenseCategory = leaf(api, "Synthetic expense", 2)
        val transferCategory = leaf(api, "Synthetic transfer", 3)
        val tag = api.createTag("+Synthetic report tag").id
        val income = post(api, a, 2, incomeCategory, source, 2000)
        post(api, a, 3, expenseCategory, source, 450, tags = listOf(tag), hidden = true,
            comment = "=SUM(1,2)\n\"quoted\",中文")
        val refund = post(api, a, 3, expenseCategory, source, -50, tags = listOf(tag))
        val transfer = post(api, a, 4, transferCategory, source, 300, destination, 40)
        post(api, a, 4, transferCategory, source, 100)
        post(api, a, 4, transferCategory, 0, 0, destination, 20)
        // The public API only permits a balance adjustment as an account's first row.
        // Unequal historic target/delta remains a separate database/JVM fixture, not this HTTP seed.
        val adjustedAccount = account(api, a, "Synthetic adjustment", "CNY", 1000, 1792497609)
        post(api, 0, 2, incomeCategory, defaultAccount, 17)
        post(api, b, 3, expenseCategory, otherAccount, 31)
        val query = LedgerReportForm("2026-10-01", "2026-10-31", "UTC", hidden = true).query(a)
        val adjustment = api.queryLedgerReport(api.captureLedgerReportContext(a), query).details
            .single { it.type == 1 && it.source?.accountId == adjustedAccount }.id
        return Seed(owner, a, b, source, destination, defaultAccount, otherAccount, expenseCategory, tag, income, refund, adjustment, transfer, query)
    }

    private suspend fun balances(seed: Seed): Map<Long, Long> = listOf(0L, seed.ledgerA, seed.ledgerB).flatMap { ledger ->
        seed.owner.api.parseAccountResponse(seed.owner.api.listAccounts(ledger)).map { it.id to it.balanceMinor }
    }.toMap()

    private suspend fun allPages(api: FinexyApi, query: LedgerReportQuery): LedgerReportPage {
        val session = api.captureLedgerReportContext(query.ledgerId)
        val first = api.queryLedgerReport(session, query, count = 2)
        var rows = first.details
        var next = first.nextOffset
        while (next != null) {
            val page = api.queryLedgerReport(session, query, next, 2, first.snapshotHash)
            rows = LedgerReportContract.append(first, rows, page)
            next = page.nextOffset
        }
        assertEquals(first.matchedCount, rows.size)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        return first.copy(details = rows)
    }

    private suspend fun rejected(status: Int, operation: suspend () -> Unit) {
        try { operation(); fail("Expected isolated report endpoint to reject this operation") }
        catch (error: ApiException) { assertEquals(status, error.status) }
    }

    private suspend fun stale(operation: suspend () -> Unit) {
        try { operation(); fail("Expected the stale report context to be rejected") }
        catch (_: IllegalStateException) { /* The API deliberately rejects the captured session before delivery. */ }
    }

    @Test fun realFinancialQueriesPaginationPreviewAndCsvKeepOriginalLedgerAndIntegerMeaning() = runBlocking<Unit> {
        val fixture = seed(); val api = fixture.owner.api
        val before = balances(fixture)
        assertEquals(11200L, before[fixture.source]); assertEquals(2060L, before[fixture.destination])
        assertEquals(7017L, before[fixture.defaultAccount]); assertEquals(4969L, before[fixture.otherAccount])
        val report = allPages(api, fixture.query)
        assertEquals(7, report.matchedCount)
        assertEquals(listOf(LedgerReportMoney("CNY", 2000, 400, 1600, 1000, 0, 400),
            LedgerReportMoney("USD", 0, 0, 0, 0, 60, 0)), report.totals)
        val adjustment = report.details.single { it.id == fixture.adjustment }
        assertEquals(1000L, adjustment.adjustment); assertEquals(1000L, adjustment.balanceTarget)
        val transfer = report.details.single { it.id == fixture.transfer }
        assertEquals(2, transfer.physicalIds.size); assertEquals(300L, transfer.source!!.amount)
        assertEquals(40L, transfer.destination!!.amount); assertEquals("USD", transfer.destination.currency)
        assertEquals(-50L, report.details.single { it.id == fixture.refund }.source!!.amount)
        assertTrue(report.details.none { it.id == 0L })
        assertTrue(report.accounts.all { it.id != fixture.defaultAccount && it.id != fixture.otherAccount })

        val sourceContext = api.captureLedgerReportContext(fixture.ledgerA)
        val preview = api.previewLedgerReportExport(sourceContext, report)
        try { api.downloadLedgerReportCsv(sourceContext, report, preview, true, false); fail("Hidden amounts require a separate acknowledgement") }
        catch (_: LedgerReportContractException) { }
        val csv = api.downloadLedgerReportCsv(sourceContext, report, preview, true, true)
        assertEquals(7, csv.rowCount)
        assertEquals("finexy-report-${fixture.ledgerA}-2026-10-01-2026-10-31.csv", csv.filename)
        assertTrue(csv.bytes().toString(Charsets.UTF_8).contains("中文"))
        assertTrue(csv.bytes().size > 37)

        val base = LedgerReportForm.from(fixture.query)
        val expenses = allPages(api, base.copy(types = setOf(3)).query(fixture.ledgerA))
        assertEquals(2, expenses.matchedCount); assertEquals(400L, expenses.totals.single().expense)
        val tagged = allPages(api, base.copy(tags = listOf(LedgerReportTagChoice("all", setOf(fixture.tag)))).query(fixture.ledgerA))
        assertEquals(setOf(fixture.refund), tagged.details.filter { !it.hidden }.map { it.id }.toSet())
        assertEquals(2, tagged.matchedCount)
        val unclassified = allPages(api, base.copy(categoryIds = setOf(0), categoryChildren = false).query(fixture.ledgerA))
        assertEquals(listOf(fixture.adjustment), unclassified.details.map { it.id })
        val excluded = allPages(api, base.copy(accountIds = setOf(fixture.destination), excludeAccounts = true).query(fixture.ledgerA))
        assertTrue(excluded.details.none { it.source?.accountId == fixture.destination || it.destination?.accountId == fixture.destination })
        val dollars = allPages(api, base.copy(currencies = setOf("USD")).query(fixture.ledgerA))
        // Like the shared currency-either-leg oracle, this selects the logical
        // transfer and preserves both currency legs in its financial totals.
        assertEquals(2, dollars.matchedCount)
        assertEquals(listOf("CNY", "USD"), dollars.totals.map { it.currency })
        assertEquals(300L, dollars.totals.single { it.currency == "CNY" }.transferOut)
        assertEquals(60L, dollars.totals.single { it.currency == "USD" }.transferIn)
        val visible = allPages(api, base.copy(hidden = false).query(fixture.ledgerA))
        assertEquals(6, visible.matchedCount); assertEquals(-50L, visible.totals.single { it.currency == "CNY" }.expense)

        for ((ledger, expectedIncome, expectedExpense) in listOf(Triple(0L, 17L, 0L), Triple(fixture.ledgerB, 0L, 31L))) {
            val other = allPages(api, base.copy().query(ledger))
            assertEquals(1, other.matchedCount)
            assertEquals(expectedIncome, other.totals.single().income); assertEquals(expectedExpense, other.totals.single().expense)
        }
        assertEquals(before, balances(fixture))
    }

    @Test fun realFourRolesSnapshotMutationAndRevokedExportUseCurrentPermissions() = runBlocking<Unit> {
        val fixture = seed(); val api = fixture.owner.api
        val admin = user(); val member = user(); val viewer = user(); val outsider = user()
        val memberIds = mutableMapOf<User, Long>()
        for ((person, role) in listOf(admin to 2, member to 3, viewer to 4)) {
            val invitation = api.createLedgerInvitation(fixture.ledgerA, person.username, if (role == 2) 3 else role)
            person.api.acceptLedgerInvitation(invitation.token)
            val me = person.api.listLedgerMembers(fixture.ledgerA).single { it.isCurrentUser }
            memberIds[person] = me.id
            if (role == 2) api.changeLedgerMemberRole(fixture.ledgerA, me.id, 2)
        }
        val before = balances(fixture)
        val ownerPage = allPages(api, fixture.query)
        for ((person, role) in listOf(fixture.owner to 1, admin to 2, member to 3, viewer to 4)) {
            val page = allPages(person.api, fixture.query)
            assertEquals(role, page.memberRole); assertEquals(ownerPage.totals, page.totals)
            assertEquals(ownerPage.details.map { it.id }, page.details.map { it.id })
        }
        rejected(403) { outsider.api.queryLedgerReport(outsider.api.captureLedgerReportContext(fixture.ledgerA), fixture.query) }

        val viewerContext = viewer.api.captureLedgerReportContext(fixture.ledgerA)
        val viewerPage = allPages(viewer.api, fixture.query)
        val viewerPreview = viewer.api.previewLedgerReportExport(viewerContext, viewerPage)
        api.removeLedgerMember(fixture.ledgerA, requireNotNull(memberIds[viewer]))
        rejected(403) { viewer.api.downloadLedgerReportCsv(viewerContext, viewerPage, viewerPreview, true, true) }
        rejected(403) { viewer.api.queryLedgerReport(viewerContext, fixture.query) }
        assertEquals(before, balances(fixture))

        val current = api.captureLedgerReportContext(fixture.ledgerA)
        val first = api.queryLedgerReport(current, fixture.query, count = 2)
        val preview = api.previewLedgerReportExport(current, first)
        post(api, fixture.ledgerA, 3, fixture.expenseCategory, fixture.source, 1)
        rejected(409) { api.queryLedgerReport(current, fixture.query, 2, 2, first.snapshotHash) }
        rejected(409) { api.downloadLedgerReportCsv(current, first, preview, true, true) }
        val updated = allPages(api, fixture.query)
        assertEquals(8, updated.matchedCount); assertFalse(first.snapshotHash == updated.snapshotHash)
        assertEquals(11199L, balances(fixture)[fixture.source])
    }

    @Test fun capturedContextRejectsAnotherLedgerTokenServerAndLocalModeWithoutReturningOldResults() = runBlocking<Unit> {
        val fixture = seed(); val api = fixture.owner.api; val store = fixture.owner.store
        val original = api.captureLedgerReportContext(fixture.ledgerA)
        val report = allPages(api, fixture.query)
        val preview = api.previewLedgerReportExport(original, report)
        val anotherLedger = LedgerReportForm.from(fixture.query).query(fixture.ledgerB)
        stale { api.queryLedgerReport(original, anotherLedger) }
        val originalToken = requireNotNull(store.get(FinexyApi.KEY_TOKEN))
        val anotherUser = user()
        store.put(FinexyApi.KEY_TOKEN, requireNotNull(anotherUser.store.get(FinexyApi.KEY_TOKEN)), durable = true)
        try {
            stale { api.queryLedgerReport(original, fixture.query) }
            stale { api.previewLedgerReportExport(original, report) }
            stale { api.downloadLedgerReportCsv(original, report, preview, true, true) }
        } finally { store.put(FinexyApi.KEY_TOKEN, originalToken, durable = true) }
        store.put(FinexyApi.KEY_SERVER_URL, "$url/changed-server", durable = true)
        try { stale { api.queryLedgerReport(original, fixture.query) } }
        finally { store.put(FinexyApi.KEY_SERVER_URL, url, durable = true) }
        store.put("local_mode", "true", durable = true)
        try { stale { api.downloadLedgerReportCsv(original, report, preview, true, true) } }
        finally { store.remove("local_mode") }
        val restored = allPages(api, fixture.query)
        assertEquals(report.totals, restored.totals)
        assertEquals(report.details.map { it.id }, restored.details.map { it.id })
    }
}
