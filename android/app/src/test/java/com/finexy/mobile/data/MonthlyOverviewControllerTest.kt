package com.finexy.mobile.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneId

/** Home overview states: missing data, failure, mixed currencies and a genuine zero. */
class MonthlyOverviewControllerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val month = YearMonth.of(2026, 10)
    private val ledgerId = 11L
    private fun environment() = LedgerReportEnvironmentState("synthetic", LedgerReportContext("https://example.test", "synthetic-account", ledgerId),
        LedgerReportAvailability.ONLINE, 1, 1, 1)
    private fun context() = MonthlyOverviewContext(environment(), month, zone)
    private fun transport(page: LedgerReportPage) = object : LedgerReportTransport {
        override suspend fun query(context: LedgerReportContext, query: LedgerReportQuery, offset: Int, count: Int,
                                   expectedHash: String?): LedgerReportPage = page
        override suspend fun preview(context: LedgerReportContext, page: LedgerReportPage): LedgerReportPreview =
            throw UnsupportedOperationException()
        override suspend fun download(context: LedgerReportContext, knownPage: LedgerReportPage, preview: LedgerReportPreview,
                                      confirmed: Boolean, includeHiddenAmounts: Boolean): LedgerReportDownload =
            throw UnsupportedOperationException()
    }
    private fun emptyPage() : LedgerReportPage {
        val query = context().query()
        val result = buildJsonObject {
            put("query", JsonObject(query.json + mapOf("version" to JsonPrimitive(1), "utcRanges" to buildJsonArray {
                add(buildJsonObject { put("start", "1790784000"); put("endExclusive", "1793462400") })
            })))
            put("snapshotHash", "ab".repeat(32)); put("memberRole", 1); put("dataComplete", true); put("matchedCount", 0)
            put("details", buildJsonArray {}); put("totals", buildJsonArray {}); put("trend", buildJsonArray {})
            put("categories", buildJsonArray {}); put("tagGroups", buildJsonArray {}); put("tagGroupsAdditive", false)
            put("accounts", buildJsonArray {}); put("categoryMetadata", buildJsonArray {}); put("readableTags", buildJsonArray {})
            put("offset", 0); put("returnedCount", 0); put("nextOffset", JsonNull); put("detailsComplete", true); put("exportAllowed", true)
        }
        return LedgerReportContract.page(buildJsonObject { put("success", true); put("result", result) }.toString(), query)
    }
    private fun money(currency: String, expense: Long) = LedgerReportMoney(currency, 0, expense, -expense, 0, 0, 0)
    private fun state(totals: List<LedgerReportMoney>, amountMinor: Long): MonthlyOverviewState {
        val scope = context()
        return MonthlyOverviewState(scope,
            LedgerReportState(ledgerId, scope.query(), LedgerReportSource.ONLINE_COMPLETE, report = LedgerReportPage(
                scope.query(), emptyList(), "ab".repeat(32), 1, 0, emptyList(), totals, emptyList(), emptyList(), emptyList(),
                emptyList(), emptyList(), emptyList(), 0, null, true, true, buildJsonObject {})),
            MonthlyOverviewBudget(MonthlyBudgetReadKind.READY,
                MonthlyBudget(1, ledgerId, 202610, amountMinor, "CNY", emptyMap(), 1)))
    }

    @Test
    fun absentBudgetIsMissingNotZero() = runBlocking {
        val controller = MonthlyOverviewController({ environment() }, transport(emptyPage())) { _, _ -> null }
        controller.bind(month, zone)
        controller.load()
        assertEquals(MonthlyBudgetReadKind.EMPTY, controller.state.value.budget.kind)
        assertNull(controller.state.value.budget.value)
        assertNull(controller.state.value.budgetUsage())
        controller.close()
    }

    @Test
    fun budgetFailureIsReportedAndNeverZeroUsage() = runBlocking {
        val controller = MonthlyOverviewController({ environment() }, transport(emptyPage())) { _, _ ->
            throw IOException("synthetic unavailable")
        }
        controller.bind(month, zone)
        controller.load()
        assertEquals(MonthlyBudgetReadKind.FAILURE, controller.state.value.budget.kind)
        assertNull(controller.state.value.budgetUsage())
        assertTrue(controller.state.value.budget.message.contains("无法取得"))
        controller.close()
    }

    @Test
    fun mixedCurrenciesUseOnlyBudgetCurrency() {
        val state = state(listOf(money("CNY", 1200), money("USD", 500)), 2000)
        assertEquals(1200L, state.budgetExpenseMinor())
        assertEquals("60.0", state.budgetUsage()!!.percentage.toPlainString())
        assertEquals(800, state.budgetUsage()!!.remainingMinor.toLong())
    }

    @Test
    fun completeZeroReportIsGenuineZero() {
        val state = state(listOf(money("CNY", 0)), 5000)
        assertEquals(0L, state.budgetExpenseMinor())
        assertEquals("0.0", state.budgetUsage()!!.percentage.toPlainString())
        assertEquals(0, state.budgetUsage()!!.warning)
    }
}
