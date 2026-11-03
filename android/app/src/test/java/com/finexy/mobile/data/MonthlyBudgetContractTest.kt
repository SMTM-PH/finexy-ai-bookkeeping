package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.ZoneId

class MonthlyBudgetContractTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun envelope(result: JsonElement) = buildJsonObject { put("success", true); put("result", result) }.toString()
    private fun budget(ledger: String = "9", month: JsonElement = JsonPrimitive(202610), amount: JsonElement = JsonPrimitive(12345),
                       currency: String = "CNY", categories: JsonElement = buildJsonObject { put("21", 5000) }) = buildJsonObject {
        put("id", "9007199254740993"); put("ledgerId", ledger); put("yearMonth", month)
        put("amount", amount); put("currency", currency); put("categoryAmounts", categories)
        put("revision", 1)
    }
    private fun account(id: String = "10", currency: String = "CNY", ledger: String = "9") = buildJsonObject {
        put("id", id); put("ledgerId", ledger); put("type", 1); put("currency", currency)
    }
    private fun row(id: String, amount: Long, account: String = "10", type: Int = 3, time: Long = 1_791_000_000,
                    ledger: String = "9", category: String = "21") = buildJsonObject {
        put("id", id); put("ledgerId", ledger); put("type", type); put("time", time)
        put("sourceAccountId", account); put("sourceAmount", amount); put("categoryId", category)
    }
    private fun expenses(accounts: List<JsonObject>, rows: List<JsonObject>, zone: ZoneId = this.zone) = MonthlyBudgetContract.parseExpenses(
        envelope(JsonArray(accounts)), envelope(buildJsonObject { put("items", JsonArray(rows)) }), 9, 202610, zone)
    private fun category(id: String, parent: String, type: Int = 2, hidden: Boolean = false) = buildJsonObject {
        put("id", id); put("parentId", parent); put("type", type); put("name", "合成分类"); put("hidden", hidden)
    }

    @Test fun onlyExplicitSuccessfulNullMeansUnset() {
        assertNull(MonthlyBudgetContract.parseBudget(envelope(JsonNull), 9, 202610))
        listOf("{}", "{\"success\":true}", "{\"success\":false,\"result\":null}", "{\"success\":true,\"result\":[]}").forEach { raw ->
            assertThrows(Exception::class.java) { MonthlyBudgetContract.parseBudget(raw, 9, 202610) }
        }
        assertThrows(IllegalArgumentException::class.java) { MonthlyBudgetContract.parseBudget(envelope(JsonNull), 9, 202610, allowUnset = false) }
    }

    @Test fun responseKeepsLongIdentifiersAndExplicitCurrency() {
        val parsed = MonthlyBudgetContract.parseBudget(envelope(budget()), 9, 202610)!!
        assertEquals(9_007_199_254_740_993L, parsed.id)
        assertEquals("CNY", parsed.currency)
        assertEquals(mapOf(21L to 5000L), parsed.categoryAmounts)
        assertEquals(0L, MonthlyBudgetContract.parseBudget(envelope(budget(ledger = "0")), 0, 202610)!!.ledgerId)
    }

    @Test fun wrongLedgerOrMonthCannotEnterCurrentPage() {
        assertThrows(IllegalArgumentException::class.java) { MonthlyBudgetContract.parseBudget(envelope(budget(ledger = "8")), 9, 202610) }
        assertThrows(IllegalArgumentException::class.java) { MonthlyBudgetContract.parseBudget(envelope(budget()), 9, 202611) }
    }

    @Test fun malformedMoneyIdentifiersAndCategoryMapAreRejected() {
        val malformed = listOf(budget(amount = JsonPrimitive(1.5)), budget(amount = JsonPrimitive("1200")),
            budget(amount = JsonPrimitive(0)), budget(amount = JsonPrimitive(MAXIMUM_MONTHLY_BUDGET_AMOUNT + 1)),
            budget(currency = ""), budget(currency = "---"), budget(currency = "cny"), budget(currency = "ZZZ"), budget(ledger = "9.0"),
            budget(categories = JsonNull), budget(categories = buildJsonObject { put("21", -1) }),
            budget(categories = buildJsonObject { put("0", 1) }), budget(categories = buildJsonObject { put("21", 1.5) }),
            budget(month = JsonPrimitive(202610.0)))
        malformed.forEach { item -> assertThrows(Exception::class.java) { MonthlyBudgetContract.parseBudget(envelope(item), 9, 202610) } }
        val numericLedger = JsonObject(budget().toMutableMap().apply { put("ledgerId", JsonPrimitive(9)) })
        assertThrows(Exception::class.java) { MonthlyBudgetContract.parseBudget(envelope(numericLedger), 9, 202610) }
    }

    @Test fun invalidCalendarMonthsAreNotTreatedAsAnotherMonth() {
        listOf(199912, 202600, 202613, 1_000_001).forEach { month ->
            assertThrows(IllegalArgumentException::class.java) { requireBudgetMonth(month) }
        }
        requireBudgetMonth(200001); requireBudgetMonth(999912)
    }

    @Test fun revisionsUseTheSharedSafeIntegerContractAndCannotBeMissing() {
        listOf(null, JsonPrimitive(0), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("1"), JsonPrimitive(MAXIMUM_MONTHLY_BUDGET_REVISION + 1)).forEach { revision ->
            val invalid = JsonObject(budget().toMutableMap().apply { if (revision == null) remove("revision") else put("revision", revision) })
            assertThrows(Exception::class.java) { MonthlyBudgetContract.parseBudget(envelope(invalid), 9, 202610) }
        }
        val draft = MonthlyBudgetDraft(202610, 100, "CNY")
        assertThrows(IllegalArgumentException::class.java) { draft.copy(expectedRevision = MAXIMUM_MONTHLY_BUDGET_REVISION + 1).payload(9) }
        assertThrows(IllegalArgumentException::class.java) { MonthlyBudgetContract.deletePayload(9, 202610, 0) }
        assertThrows(IllegalArgumentException::class.java) { MonthlyBudgetContract.deletePayload(9, 202610, MAXIMUM_MONTHLY_BUDGET_REVISION + 1) }
    }

    @Test fun amountInputUsesExactMinorUnitsAndNeverRounds() {
        assertEquals(12345L, parseMonthlyBudgetAmount("123.45"))
        assertEquals(10L, parseMonthlyBudgetAmount("0.1"))
        assertEquals(MAXIMUM_MONTHLY_BUDGET_AMOUNT, parseMonthlyBudgetAmount("99999999999.99"))
        listOf("0", "-1", "1.005", "1e2", "NaN", "1,000", "100000000000.00", "999999999999999999999999999").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { parseMonthlyBudgetAmount(text) }
        }
    }

    @Test fun writeAndDeletePayloadsAlwaysCarryLedgerIncludingZero() {
        val draft = MonthlyBudgetDraft(202610, 12345, "USD", mapOf(21L to 100))
        val payload = Json.parseToJsonElement(draft.payload(9)).jsonObject
        assertEquals(JsonPrimitive("9"), payload["ledgerId"])
        assertEquals(JsonPrimitive(12345), payload["amount"])
        assertEquals(JsonPrimitive("USD"), payload["currency"])
        assertEquals(JsonPrimitive(100), payload["categoryAmounts"]!!.jsonObject["21"])
        assertEquals(JsonPrimitive("0"), Json.parseToJsonElement(draft.payload(0)).jsonObject["ledgerId"])
        assertEquals(JsonPrimitive(0), payload["expectedRevision"])
        assertEquals(JsonPrimitive("9"), Json.parseToJsonElement(MonthlyBudgetContract.deletePayload(9, 202610, 1)).jsonObject["ledgerId"])
        assertEquals(JsonPrimitive(7), Json.parseToJsonElement(draft.copy(expectedRevision = 7).payload(9)).jsonObject["expectedRevision"])
        assertThrows(IllegalArgumentException::class.java) { draft.payload(-1) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(categoryAmounts = mapOf(0L to 1)).payload(9) }
    }

    @Test fun deleteRequiresConfirmedTrueInsteadOfHttpSuccessAlone() {
        MonthlyBudgetContract.requireDeleted(envelope(JsonPrimitive(true)))
        listOf(envelope(JsonNull), envelope(JsonPrimitive(false)), "{\"success\":true}").forEach { raw ->
            assertThrows(Exception::class.java) { MonthlyBudgetContract.requireDeleted(raw) }
        }
    }

    @Test fun ownerAndAdminCanManageButOtherRolesCannot() {
        assertTrue(monthlyBudgetCanManage(1)); assertTrue(monthlyBudgetCanManage(2))
        listOf(null, 0, 3, 4, 5).forEach { assertFalse(monthlyBudgetCanManage(it)) }
    }

    @Test fun newerRetryAndChangedAccountLedgerOrMonthDiscardLateResponse() {
        val gate = MonthlyBudgetRequestGate()
        val context = MonthlyBudgetContext("server-user-a", 9, 202610)
        val old = gate.begin(context)
        val latest = gate.begin(context)
        assertFalse(gate.accepts(old, context, context.identity))
        assertTrue(gate.accepts(latest, context, context.identity))
        assertFalse(gate.accepts(latest, context.copy(ledgerId = 8), context.identity))
        assertFalse(gate.accepts(latest, context.copy(yearMonth = 202611), context.identity))
        assertFalse(gate.accepts(latest, context, "server-user-b"))
        val switched = context.copy(ledgerId = 8)
        val newer = gate.begin(switched)
        assertFalse(gate.accepts(latest, context, context.identity))
        assertTrue(gate.accepts(newer, switched, context.identity))
    }

    @Test fun categoryLevelsRequireExpenseTypeParentAndCompleteResult() {
        val parsed = MonthlyBudgetContract.parseCategoryLevel(envelope(buildJsonObject { put("2", JsonArray(listOf(category("21", "20")))) }), 20)
        assertEquals(21L, parsed.single().id)
        assertTrue(MonthlyBudgetContract.parseCategoryLevel(envelope(buildJsonObject {}), 20).isEmpty())
        listOf(envelope(JsonNull), "{\"success\":true}",
            envelope(buildJsonObject { put("2", JsonArray(listOf(category("21", "0")))) }),
            envelope(buildJsonObject { put("2", JsonArray(listOf(category("21", "20", type = 1)))) }),
            envelope(buildJsonObject { put("2", JsonArray(listOf(category("21", "20"), category("21", "20")))) })).forEach { raw ->
            assertThrows(Exception::class.java) { MonthlyBudgetContract.parseCategoryLevel(raw, 20) }
        }
    }

    @Test fun usageGroupsCurrenciesAndRefundsWhileExcludingIncomeTransfersAndAdjustments() {
        val snapshot = expenses(listOf(account(), account("11", "USD")), listOf(
            row("1", 1000), row("2", -200), row("3", 300, "11"), row("4", 9999, type = 2),
            row("5", 9999, type = 1), row("6", 9999, type = 4), row("7", 9999, type = 5)))
        assertEquals(800L, snapshot.usage("CNY").expenseMinor)
        assertEquals(300L, snapshot.usage("USD").expenseMinor)
        assertEquals(800L, snapshot.usage("CNY").categoryAmounts[21L])
        assertEquals(setOf("CNY", "USD"), snapshot.currencies)
    }

    @Test fun naturalMonthUsesDeviceTimezoneAndBothMonthBoundaries() {
        // 2026-10-01 00:00 and 2026-11-01 00:00 in Shanghai.
        val start = java.time.LocalDate.of(2026, 10, 1).atStartOfDay(zone).toEpochSecond()
        val end = java.time.LocalDate.of(2026, 11, 1).atStartOfDay(zone).toEpochSecond()
        val rows = listOf(row("1", 100, time = start - 1), row("2", 200, time = start),
            row("3", 300, time = end - 1), row("4", 400, time = end))
        assertEquals(500L, expenses(listOf(account()), rows).usage("CNY").expenseMinor)
        assertEquals(700L, expenses(listOf(account()), rows, ZoneId.of("UTC")).usage("CNY").expenseMinor)
    }

    @Test fun hiddenAndNestedHistoricalAccountsStillContributeExpense() {
        val child = JsonObject(account().toMutableMap().apply { put("hidden", JsonPrimitive(true)) })
        val parent = buildJsonObject { put("id", "1"); put("ledgerId", "9"); put("type", 2); put("currency", "---"); put("subAccounts", JsonArray(listOf(child))) }
        assertEquals(100L, expenses(listOf(parent), listOf(row("1", 100))).usage("CNY").expenseMinor)
    }

    @Test fun missingAccountCurrencyLedgerOrResultDoesNotBecomeZeroExpense() {
        val missingCurrency = JsonObject(account().toMutableMap().apply { remove("currency") })
        val missingLedger = JsonObject(row("1", 100).toMutableMap().apply { remove("ledgerId") })
        assertThrows(Exception::class.java) { expenses(listOf(missingCurrency), listOf(row("1", 100))) }
        assertThrows(Exception::class.java) { expenses(listOf(account()), listOf(missingLedger)) }
        assertThrows(Exception::class.java) { expenses(emptyList(), listOf(row("1", 100))) }
        assertThrows(Exception::class.java) { expenses(listOf(account(ledger = "8")), emptyList()) }
        assertThrows(Exception::class.java) { expenses(listOf(account()), listOf(row("1", 100, ledger = "8"))) }
        assertThrows(Exception::class.java) { MonthlyBudgetContract.parseExpenses("{\"success\":true}", envelope(buildJsonObject { put("items", JsonArray(emptyList())) }), 9, 202610, zone) }
        assertThrows(Exception::class.java) { MonthlyBudgetContract.parseExpenses(envelope(JsonArray(listOf(account()))), envelope(buildJsonObject {}), 9, 202610, zone) }
    }

    @Test fun duplicateOrFractionalExpenseRowsCannotSilentlyDistortUsage() {
        assertThrows(Exception::class.java) { expenses(listOf(account()), listOf(row("1", 100), row("1", 100))) }
        val fractional = JsonObject(row("1", 100).toMutableMap().apply { put("sourceAmount", JsonPrimitive(1.5)) })
        assertThrows(Exception::class.java) { expenses(listOf(account()), listOf(fractional)) }
    }

    @Test fun emptyValidatedSnapshotIsZeroWithoutInventingCurrency() {
        val snapshot = expenses(emptyList(), emptyList())
        assertTrue(snapshot.currencies.isEmpty())
        assertEquals(0L, snapshot.usage("CNY").expenseMinor)
        assertTrue(snapshot.usage("CNY").categoryAmounts.isEmpty())
    }

    @Test fun overflowRemainsUnavailableAndIndependentCurrenciesStayValid() {
        val snapshot = expenses(listOf(account(), account("11", "USD")), listOf(row("1", Long.MAX_VALUE), row("2", 1), row("3", 123, "11")))
        assertNull(snapshot.usage("CNY").expenseMinor)
        assertNull(snapshot.usage("CNY").categoryAmounts[21L])
        assertEquals(123L, snapshot.usage("USD").expenseMinor)
    }

    @Test fun intermediateOverflowAndRefundsKeepExactFinalTotal() {
        val snapshot = expenses(listOf(account()), listOf(row("1", Long.MAX_VALUE), row("2", Long.MAX_VALUE), row("3", Long.MIN_VALUE)))
        assertEquals(Long.MAX_VALUE - 1, snapshot.usage("CNY").expenseMinor)
    }

    @Test fun progressThresholdsAreExactAndRefundRemainderCannotOverflow() {
        assertEquals(0, monthlyBudgetProgress(100, 79).warning)
        assertEquals(1, monthlyBudgetProgress(100, 80).warning)
        assertEquals(2, monthlyBudgetProgress(100, 100).warning)
        assertEquals(1f, monthlyBudgetProgress(100, 200).fill)
        val refunded = monthlyBudgetProgress(100, Long.MIN_VALUE)
        assertEquals(BigInteger.valueOf(100) - BigInteger.valueOf(Long.MIN_VALUE), refunded.remainingMinor)
        assertEquals(0f, refunded.fill)
        assertEquals(0, refunded.warning)
    }
}
