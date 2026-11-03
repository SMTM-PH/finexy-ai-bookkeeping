package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PeriodicContractTest {
    private fun template(ledger: Long = 9) = buildJsonObject {
        put("ledgerId", ledger.toString()); put("id", "9007199254740993"); put("templateType", 2)
        put("name", "fixture"); put("type", 3); put("categoryId", "12"); put("sourceAccountId", "13")
        put("sourceAmount", -102); put("scheduledFrequencyType", 3); put("scheduledFrequency", "0"); put("utcOffset", 480)
    }
    private fun JsonObject.with(name: String, value: JsonElement) = JsonObject(toMutableMap().apply { put(name, value) })
    private fun envelope(vararg rows: JsonObject) = buildJsonObject { put("success", true); put("result", JsonArray(rows.toList())) }.toString()
    private fun occurrence(ledger: Long = 9) = buildJsonObject {
        put("ledgerId", ledger.toString()); put("templateId", "9007199254740993"); put("scheduledUnixTime", 1788700000)
        put("status", 1); put("transactionId", "0"); put("snapshot", template(ledger))
    }

    @Test fun preciseIdsRefundAndBothDailyWireValuesSurvive() {
        for (daily in listOf("0", "1")) {
            val row = parsePeriodicTemplates(envelope(template().with("scheduledFrequency", JsonPrimitive(daily))), 9, 2).single()
            assertEquals(9007199254740993L, row.id); assertEquals(9L, row.ledgerId); assertEquals(-102L, row.sourceAmountMinor)
        }
    }

    @Test fun missingOwnershipOnlyWorksForLegacyDefaultLedger() {
        val legacy = JsonObject(template(0).toMutableMap().apply { remove("ledgerId") })
        assertEquals(0L, parsePeriodicTemplates(envelope(legacy)).single().ledgerId)
        assertThrows(RuntimeException::class.java) { parsePeriodicTemplates(envelope(legacy), 9) }
        assertThrows(RuntimeException::class.java) { parsePeriodicTemplates(envelope(template(10)), 9) }
    }

    @Test fun malformedAndPartialResponsesNeverBecomeEmptySnapshots() {
        listOf("{}", "{\"success\":false,\"result\":[]}", "{\"success\":true}", "{\"success\":true,\"result\":null}", "{\"success\":true,\"result\":[null]}")
            .forEach { raw -> assertThrows(RuntimeException::class.java) { parsePeriodicTemplates(raw, 9) } }
        assertTrue(parsePeriodicTemplates("{\"success\":true,\"result\":[]}", 9).isEmpty())
        assertThrows(RuntimeException::class.java) { parsePeriodicTemplates(envelope(template(), template()), 9) }
    }

    @Test fun moneyAndIdentifiersRejectCoercionAndOverflow() {
        for ((field, value) in listOf("sourceAmount" to JsonPrimitive("102"), "sourceAmount" to Json.parseToJsonElement("1.25"),
            "sourceAmount" to JsonPrimitive(10_000_000_000_000L), "id" to JsonPrimitive("9007199254740993.0"), "id" to JsonPrimitive("9223372036854775808"))) {
            assertThrows(RuntimeException::class.java) { parsePeriodicTemplates(envelope(template().with(field, value)), 9) }
        }
    }

    @Test fun outerAndSnapshotLedgerMustBothMatchRequest() {
        assertEquals(9L, parsePeriodicOccurrences(envelope(occurrence()), 9, 1).single().ledgerId)
        assertThrows(RuntimeException::class.java) { parsePeriodicOccurrences(envelope(occurrence(10)), 9) }
        assertThrows(RuntimeException::class.java) { parsePeriodicOccurrences(envelope(occurrence().with("snapshot", template(10))), 9) }
        assertThrows(RuntimeException::class.java) { parsePeriodicOccurrences(envelope(occurrence()), 9, 3) }
        assertThrows(RuntimeException::class.java) { parsePeriodicOccurrences(envelope(occurrence(), occurrence()), 9) }
    }

    @Test fun confirmationRequiresMatchingLedgerAndPositiveExactReceipt() {
        fun ack(ledger: Long, id: String) = "{\"success\":true,\"result\":{\"ledgerId\":\"$ledger\",\"transactionId\":\"$id\"}}"
        assertEquals(Long.MAX_VALUE, parsePeriodicConfirmation(ack(9, Long.MAX_VALUE.toString()), 9))
        assertThrows(RuntimeException::class.java) { parsePeriodicConfirmation(ack(10, "12"), 9) }
        assertThrows(RuntimeException::class.java) { parsePeriodicConfirmation(ack(9, "0"), 9) }
    }

    @Test fun transferPreservesIndependentAmountsAndNeverUsesFloatingPoint() {
        val transfer = template().with("type", JsonPrimitive(4)).with("sourceAmount", JsonPrimitive(10000))
            .with("destinationAccountId", JsonPrimitive("14")).with("destinationAmount", JsonPrimitive(1380))
        val row = parsePeriodicTemplates(envelope(transfer), 9).single()
        assertEquals(10000L, row.sourceAmountMinor); assertEquals(1380L, row.destinationAmountMinor)
    }

    @Test fun invalidCalendarAndSuccessTypesFailClosed() {
        for ((kind, frequency) in listOf(3 to "2", 3 to "0,1", 1 to "7", 2 to "0", 4 to "230", 5 to "1")) {
            val row = template().with("scheduledFrequencyType", JsonPrimitive(kind)).with("scheduledFrequency", JsonPrimitive(frequency))
            assertThrows(RuntimeException::class.java) { parsePeriodicTemplates(envelope(row), 9) }
        }
        assertThrows(RuntimeException::class.java) { requirePeriodicBooleanResult("{\"success\":true,\"result\":\"true\"}") }
        requirePeriodicBooleanResult("{\"success\":true,\"result\":true}")
    }
}
