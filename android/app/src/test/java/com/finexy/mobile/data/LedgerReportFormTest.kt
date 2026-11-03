package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LedgerReportFormTest {
    private val form = LedgerReportForm("2026-03-01", "2026-03-31", "America/New_York",
        types = setOf(1, 3, 4), accountIds = setOf(9007199254740993L), excludeAccounts = true,
        categoryIds = setOf(0L, 99L), categoryChildren = false,
        tags = listOf(LedgerReportTagChoice("notAny", setOf(8L)), LedgerReportTagChoice("all", setOf(7L, 9L))),
        currencies = setOf("CNY", "USD"), hidden = true)

    @Test fun allFiveFiltersRoundTripWithoutFloatingPointIds() {
        val query = form.query(9007199254740995L)
        assertEquals(query, LedgerReportForm.from(query).query(9007199254740995L))
        assertTrue(query.json.toString().contains("\"9007199254740993\""))
        assertEquals("exclude", query.json.getValue("accounts").jsonObject.getValue("mode").jsonPrimitive.content)
    }

    @Test fun invalidDraftNeverSilentlyBecomesAnUnfilteredReport() {
        for (bad in listOf(form.copy(start = "2026-02-30"), form.copy(end = "2025-01-01"),
            form.copy(timezone = "GMT+8"), form.copy(types = emptySet()), form.copy(accountIds = setOf(-1L)),
            form.copy(tags = listOf(LedgerReportTagChoice("any"))), form.copy(currencies = setOf("cny")))) {
            assertThrows(LedgerReportContractException::class.java) { bad.query(0) }
        }
    }

    @Test fun unclassifiedDrillIsExactZeroAndPreservesOtherDimensions() {
        val original = form.query(2)
        val narrowed = narrowLedgerReport(original, "category", "0", "USD")
        for (key in listOf("ledgerId", "startDate", "endDate", "timezone", "types", "accounts", "tags", "hidden"))
            assertEquals(key, original.json[key], narrowed.json[key])
        assertEquals(listOf("0"), narrowed.json.getValue("categories").jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(narrowed.json.getValue("categories").jsonObject.getValue("includeDescendants").jsonPrimitive.boolean)
    }

    @Test fun tagDrillAddsConjunctionAndDayDrillKeepsOriginalCurrencyScope() {
        val query = form.query(0)
        val narrowed = narrowLedgerReport(query, "tag", "12", "CNY")
        assertEquals(3, narrowed.json.getValue("tags").jsonArray.size)
        val day = narrowLedgerReport(query, "day", "2026-03-08", "USD")
        assertEquals("2026-03-08", day.startDate); assertEquals(day.startDate, day.endDate)
        assertEquals(query.json["categories"], day.json["categories"])
        assertThrows(IllegalArgumentException::class.java) { narrowLedgerReport(query, "day", "2026-04-01", "USD") }
        assertThrows(IllegalArgumentException::class.java) { narrowLedgerReport(query, "day", "2026-03-08", "EUR") }
    }

    @Test fun fullMonthComparisonRetainsIanaAndEveryNonDateFilter() {
        val original = form.query(2); val previous = previousFullReportMonth(original)
        assertEquals("2026-02-01", previous.startDate); assertEquals("2026-02-28", previous.endDate)
        original.json.filterKeys { it !in setOf("startDate", "endDate") }.forEach { (key, value) -> assertEquals(key, value, previous.json[key]) }
        assertThrows(IllegalArgumentException::class.java) { previousFullReportMonth(form.copy(start = "2026-03-02").query(2)) }
    }

    @Test fun currentMonthUsesNaturalCalendarBoundaries() {
        val query = LedgerReportForm.currentMonth(LocalDate.of(2024, 2, 12), ZoneId.of("Asia/Shanghai")).query(0)
        assertEquals("2024-02-01", query.startDate); assertEquals("2024-02-29", query.endDate)
        assertEquals("Asia/Shanghai", query.timezone)
    }
}
