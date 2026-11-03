package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AIReviewLedgerApiContractTest {
    private fun envelope(value: Any) = JSONObject().put("success", true).put("result", value).toString()
    private fun item(id: Long = 9007199254740993L, ledger: Long = 9, status: Int = 1) = JSONObject()
        .put("ledgerId", ledger.toString()).put("id", id.toString()).put("creatorUid", "9007199254740995")
        .put("transactionId", if (status == 2) "9007199254740997" else "0").put("confirmedUid", if (status == 2) "9007199254740995" else "0")
        .put("editable", status != 2).put("deleted", false).put("sourceType", 1).put("status", status)
        .put("sourceText", "synthetic source").put("failureReason", "").put("createdUnixTime", 1788700000)
        .put("recognizedData", JSONObject().put("ledgerId", ledger.toString()).put("type", 3).put("sourceAccountId", "9007199254740999")
            .put("sourceAmount", -123).put("categoryId", "20").put("comment", "synthetic refund"))
    private fun caps() = JSONObject().put("ledgerId", "9").put("contractVersion", 1).put("recognitionLedgerContext", true)
        .put("durableCreation", true).put("atomicConfirmation", true).put("payloadBinding", true).put("deletedReceipts", true)
        .put("maxClientSessionIdBytes", 128).put("maxPageSize", 100).put("pagination", "beforeId")
        .put("reviewerScope", "manager-or-creator").put("confirmationPath", "v1/ai/review/confirm.json")
    private fun create() = JSONObject().put("ledgerId", "9").put("clientSessionId", "stable-create").put("sourceType", 1)
        .put("sourceText", "\u0085 synthetic source\u00a0").put("failureReason", "\u0085 ")
        .put("recognizedData", item().getJSONObject("recognizedData"))
    private fun confirm(type: Int = 3) = JSONObject().put("ledgerId", "9").put("id", "9007199254740993")
        .put("transaction", JSONObject().put("ledgerId", "9").put("clientSessionId", "stable-confirm").put("type", type)
            .put("time", 1788700000).put("utcOffset", 480).put("categoryId", "20")
            .put("sourceAccountId", "9007199254740999").put("destinationAccountId", "0").put("sourceAmount", -123)
            .put("destinationAmount", 0).put("hideAmount", true).put("tagIds", JSONArray()).put("pictureIds", JSONArray()).put("comment", "synthetic"))

    @Test fun stringIdsAndSignedRefundSurviveWithoutFloatingPointOrDefaultAccount() {
        val parsed = parseLedgerAIReviewItem(envelope(item()), 9)
        assertEquals(9007199254740993L, parsed.id); assertEquals(9007199254740995L, parsed.creatorUid)
        assertEquals(9007199254740999L, parsed.recognized!!.sourceAccountId); assertEquals(-123L, parsed.recognized.sourceAmountMinor)
        val unmatched = item().apply { getJSONObject("recognizedData").remove("sourceAccountId") }
        assertNull(parseLedgerAIReviewItem(envelope(unmatched), 9).recognized!!.sourceAccountId)
        assertEquals(0L, parseLedgerAIReviewItem(envelope(item(ledger = 0)), 0).ledgerId)
    }

    @Test fun malformedEnvelopesAndNumericIdsCannotBecomeAnEmptyValidSnapshot() {
        listOf("{}", "{\"success\":false,\"result\":[]}", "{\"success\":\"true\",\"result\":[]}",
            "{\"success\":true,\"result\":null}", "{\"success\":true,\"result\":{}}").forEach {
            assertThrows(RuntimeException::class.java) { parseLedgerAIReviewPage(it, 9, 1, 0) }
        }
        listOf(9007199254740993L, "09", "9.0", "-1", "9223372036854775808").forEach {
            assertThrows(RuntimeException::class.java) { parseLedgerAIReviewItem(envelope(item().put("id", it)), 9) }
        }
        assertEquals(emptyList<LedgerAIReviewItem>(), parseLedgerAIReviewPage(envelope(JSONArray()), 9, 1, 0))
    }

    @Test fun ledgerObjectAndRecognitionScopeAreCheckedIndependently() {
        assertThrows(RuntimeException::class.java) { parseLedgerAIReviewItem(envelope(item()), 10) }
        assertThrows(RuntimeException::class.java) { parseLedgerAIReviewItem(envelope(item()), 9, 42) }
        assertThrows(RuntimeException::class.java) { parseLedgerAIReviewItem(envelope(item().apply { getJSONObject("recognizedData").put("ledgerId", "10") }), 9) }
        assertThrows(RuntimeException::class.java) { parseLedgerAIRecognition(envelope(JSONObject().put("type", 3)), 9) }
        assertEquals(0L, parseLedgerAIRecognition(envelope(JSONObject().put("type", 3)), 0).ledgerId)
        // Old queue data may omit the recognition ledger; the queue owns its scope.
        val legacy = item().apply { getJSONObject("recognizedData").remove("ledgerId") }
        assertEquals(9L, parseLedgerAIReviewItem(envelope(legacy), 9).recognized!!.ledgerId)
    }

    @Test fun confirmationAndDeletedStatesRequireConsistentRealReceipts() {
        assertEquals(9007199254740997L, parseLedgerAIReviewItem(envelope(item(status = 2)), 9).transactionId)
        listOf(item(status = 2).put("transactionId", "0"), item().put("transactionId", "1"),
            item().put("confirmedUid", "1"), item().put("deleted", true), item().put("editable", "true")).forEach {
            assertThrows(RuntimeException::class.java) { parseLedgerAIReviewItem(envelope(it), 9) }
        }
        assertTrue(parseLedgerAIReviewItem(envelope(item().put("deleted", true).put("editable", false)), 9).deleted)
    }

    @Test fun capabilityMismatchAndMissingRecognitionSupportFailClosed() {
        requireLedgerAIReviewCapabilities(envelope(caps()), 9, true)
        for ((key, value) in listOf("ledgerId" to "10", "contractVersion" to 2, "atomicConfirmation" to false,
            "payloadBinding" to "true", "confirmationPath" to "v1/transactions/add.json", "maxPageSize" to 50)) {
            assertThrows(RuntimeException::class.java) { requireLedgerAIReviewCapabilities(envelope(caps().put(key, value)), 9) }
        }
        assertThrows(RuntimeException::class.java) { requireLedgerAIReviewCapabilities(envelope(caps().apply { remove("recognitionLedgerContext") }), 9, true) }
        requireLedgerAIReviewCapabilities(envelope(caps().apply { remove("recognitionLedgerContext") }), 9, false)
    }

    @Test fun oneHundredAndOneItemsAreCollectedThroughStringCursorWithoutOffsetTruncation() = runBlocking<Unit> {
        val cursors = mutableListOf<Long>()
        val result = collectLedgerAIReviewPages(9, 1) { cursor ->
            cursors += cursor
            if (cursor == 0L) envelope(JSONArray().apply { for (id in 201L downTo 102L) put(item(id)) })
            else { assertEquals(102L, cursor); envelope(JSONArray().put(item(101))) }
        }
        assertEquals(listOf(0L, 102L), cursors); assertEquals(101, result.size)
        assertEquals((201L downTo 101L).toList(), result.map { it.id })
    }

    @Test fun failedSecondPageAndRepeatedCursorCannotReturnPartialData() = runBlocking<Unit> {
        var calls = 0
        for (last in listOf("{}", envelope(JSONArray().put(item(102))), envelope(JSONArray().put(item(101, 10))))) {
            calls = 0
            try {
                collectLedgerAIReviewPages(9, 1) { cursor ->
                    calls++; if (cursor == 0L) envelope(JSONArray().apply { for (id in 201L downTo 102L) put(item(id)) }) else last
                }
                fail("A bad later page must not expose a partial snapshot")
            } catch (_: RuntimeException) { assertEquals(2, calls) }
        }
    }

    @Test fun pageOrderStatusDuplicatesAndPageLimitAreValidated() {
        listOf(JSONArray().put(item(1)).put(item(2)), JSONArray().put(item(1)).put(item(1)),
            JSONArray().put(item(status = 3)), JSONArray().put(item().put("deleted", true).put("editable", false)),
            JSONArray().apply { for (id in 201L downTo 101L) put(item(id)) }).forEach {
            assertThrows(RuntimeException::class.java) { parseLedgerAIReviewPage(envelope(it), 9, 1, 0) }
        }
        assertEquals(3, parseLedgerAIReviewPage(envelope(JSONArray().put(item(status = 3))), 9, 3, 0).single().status)
    }

    @Test fun createUsesByteLimitsAndBindsNormalizedSourceAndRecognitionReceipt() {
        validateLedgerAIReviewCreate(create().toString(), 9)
        requireLedgerAIReviewCreateReceipt(parseLedgerAIReviewItem(envelope(item()), 9), create().toString(), 9)
        val zeroFields = create().apply { getJSONObject("recognizedData").put("destinationAccountId", "0").put("destinationAmount", 0).put("tagIds", JSONArray()) }
        requireLedgerAIReviewCreateReceipt(parseLedgerAIReviewItem(envelope(item()), 9), zeroFields.toString(), 9)
        val controls = "\u001cSynthetic source\u001f"
        val controlPayload = create().put("sourceText", controls).put("failureReason", "\u001d")
        val controlReceipt = item().put("sourceText", controls).put("failureReason", "\u001d")
        requireLedgerAIReviewCreateReceipt(parseLedgerAIReviewItem(envelope(controlReceipt), 9), controlPayload.toString(), 9)
        assertEquals(controls, trimAIReviewSource("\u0085$controls\u00a0"))
        assertThrows(RuntimeException::class.java) { requireLedgerAIReviewCreateReceipt(parseLedgerAIReviewItem(envelope(item()), 9), create().put("sourceText", "different").toString(), 9) }
        assertThrows(RuntimeException::class.java) { validateLedgerAIReviewCreate(create().put("sourceText", "账".repeat(2667)).toString(), 9) }
        assertThrows(RuntimeException::class.java) { validateLedgerAIReviewCreate(create().put("clientSessionId", "界".repeat(43)).toString(), 9) }
        assertThrows(RuntimeException::class.java) { validateLedgerAIReviewCreate(create().put("failureReason", "🙂".repeat(501)).toString(), 9) }
        assertThrows(RuntimeException::class.java) { validateLedgerAIReviewCreate(create().put("transaction", JSONObject()).toString(), 9) }
    }

    @Test fun financialSnapshotRetainsRefundDualCurrencyAndExternalDirections() {
        validateLedgerAIConfirmation(confirm().toString(), 9, 9007199254740993L)
        val transfer = confirm(4).apply { getJSONObject("transaction").put("sourceAmount", 150).put("destinationAccountId", "42").put("destinationAmount", 125) }
        val parsed = validateLedgerAIConfirmation(transfer.toString(), 9, 9007199254740993L)["transaction"]!!.jsonObject
        assertEquals(150L, parsed["sourceAmount"]!!.jsonPrimitive.long); assertEquals(125L, parsed["destinationAmount"]!!.jsonPrimitive.long)
        validateLedgerAIConfirmation(confirm(4).apply { getJSONObject("transaction").put("sourceAmount", 150) }.toString(), 9, 9007199254740993L)
        validateLedgerAIConfirmation(confirm(4).apply { getJSONObject("transaction").put("sourceAccountId", "0").put("sourceAmount", 0).put("destinationAccountId", "42").put("destinationAmount", 125) }.toString(), 9, 9007199254740993L)
    }

    @Test fun invalidFinancialFieldsNeverReceiveAReplacementRequest() {
        for ((key, value) in listOf("ledgerId" to "10", "sourceAccountId" to "0", "sourceAmount" to 1.5,
            "utcOffset" to "480", "time" to 0, "categoryId" to "0", "type" to 1, "pictureIds" to JSONArray().put("1").put("1"))) {
            val payload = confirm().apply { getJSONObject("transaction").put(key, value) }
            assertThrows(RuntimeException::class.java) { validateLedgerAIConfirmation(payload.toString(), 9, 9007199254740993L) }
        }
        assertThrows(RuntimeException::class.java) { validateLedgerAIConfirmation(confirm(2).toString(), 9, 9007199254740993L) }
        for ((key, value) in listOf("clientSessionId" to "x".repeat(129), "time" to Long.MAX_VALUE)) {
            assertThrows(RuntimeException::class.java) { validateLedgerAIConfirmation(
                confirm().apply { getJSONObject("transaction").put(key, value) }.toString(), 9, 9007199254740993L) }
        }
        val unknown = confirm().apply { getJSONObject("transaction").put("id", "1") }
        assertThrows(RuntimeException::class.java) { validateLedgerAIConfirmation(unknown.toString(), 9, 9007199254740993L) }
        val invalidTransfer = confirm(4).apply { getJSONObject("transaction").put("sourceAmount", 150).put("destinationAmount", 125) }
        assertThrows(RuntimeException::class.java) { validateLedgerAIConfirmation(invalidTransfer.toString(), 9, 9007199254740993L) }
    }

    @Test fun confirmationReceiptAndStatusResultMustMatchOriginalOperation() {
        val raw = envelope(JSONObject().put("ledgerId", "9").put("id", "9007199254740993").put("transactionId", "9007199254740997"))
        assertEquals(9007199254740997L, parseLedgerAIConfirmationReceipt(raw, 9, 9007199254740993L).transactionId)
        assertThrows(RuntimeException::class.java) { parseLedgerAIConfirmationReceipt(raw, 10, 9007199254740993L) }
        assertThrows(RuntimeException::class.java) { parseLedgerAIConfirmationReceipt(raw, 9, 42) }
        assertThrows(RuntimeException::class.java) { parseLedgerAIConfirmationReceipt(raw.replace("9007199254740997", "0"), 9, 9007199254740993L) }
        requireLedgerAIStateResult(envelope(true))
        listOf(false, "true", JSONObject(), JSONArray()).forEach { assertThrows(RuntimeException::class.java) { requireLedgerAIStateResult(envelope(it)) } }
    }
}
