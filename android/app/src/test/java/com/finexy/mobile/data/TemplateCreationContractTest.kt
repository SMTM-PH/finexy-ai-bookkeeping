package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TemplateCreationContractTest {
    private fun envelope(item: JsonObject) = buildJsonObject { put("success", true); put("result", item) }.toString()
    private fun capability() = buildJsonObject {
        put("creationContractVersion", 1); put("durableCreation", true); put("payloadBinding", true); put("deletedReceipts", true)
        put("maxClientSessionIdBytes", 128); put("submissionScope", "owner-creator-ledger")
        put("durableCreatePath", "v1/transaction/templates/add_durable.json")
        put("templateTypes", buildJsonArray { add(1); add(2) })
    }
    private fun template() = buildJsonObject {
        put("id", "9007199254740993"); put("ledgerId", "9"); put("templateType", 1); put("name", "test"); put("type", 3)
        put("categoryId", "20"); put("sourceAccountId", "10"); put("destinationAccountId", "0")
        put("sourceAmount", -1234); put("destinationAmount", 0); put("comment", ""); put("tagIds", JsonArray(emptyList()))
        put("hidden", false); put("hideAmount", true); put("editable", true)
    }
    @Test fun capabilityRequiresAllGuaranteesAndNeverUsesVersionAsProof() {
        requireTemplateCreationCapabilities(envelope(capability()), 1)
        requireTemplateCreationCapabilities(envelope(capability()), 2)
        val invalid = listOf(
            capability() - "durableCreation", capability() + ("durableCreation" to JsonPrimitive(false)),
            capability() + ("payloadBinding" to JsonPrimitive("true")), capability() + ("creationContractVersion" to JsonPrimitive(2)),
            capability() + ("submissionScope" to JsonPrimitive("ledger")), capability() + ("maxClientSessionIdBytes" to JsonPrimitive(16)),
            capability() + ("durableCreatePath" to JsonPrimitive("v1/transaction/templates/add.json")),
            mapOf("version" to JsonPrimitive("1.9.3")), capability() + ("templateTypes" to buildJsonArray { add(1); add(1) })
        )
        for (item in invalid) {
            try { requireTemplateCreationCapabilities(envelope(JsonObject(item)), 1); fail("Must reject unsupported creation contract") }
            catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }
    @Test fun scheduleNotAdvertisedCannotBeCreatedWhileOrdinaryRemainsAvailable() {
        val onlyOrdinary = JsonObject(capability() + ("templateTypes" to buildJsonArray { add(1) }))
        requireTemplateCreationCapabilities(envelope(onlyOrdinary), 1)
        assertThrows(IllegalArgumentException::class.java) { requireTemplateCreationCapabilities(envelope(onlyOrdinary), 2) }
    }
    @Test fun activeReceiptUsesLongAndRejectsMissingOrWrongScope() {
        assertEquals(TemplateCreationReceipt(9, 1, 9007199254740993L), parseTemplateCreationReceipt(envelope(template()), 9, 1))
        for (item in listOf(template() - "ledgerId", template() + ("ledgerId" to JsonPrimitive("10")),
            template() + ("templateType" to JsonPrimitive(2)), template() + ("id" to JsonPrimitive(1.25)))) {
            assertThrows(IllegalArgumentException::class.java) { parseTemplateCreationReceipt(envelope(JsonObject(item)), 9, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { parseTemplateCreationReceipt(envelope(JsonObject(template() - "ledgerId")), 0, 1) }
    }
    @Test fun deletedReceiptAcknowledgesOriginalCreationAndCannotEnterActiveCache() {
        val tombstone = JsonObject(template() + mapOf("deleted" to JsonPrimitive(true), "editable" to JsonPrimitive(false)))
        assertEquals(TemplateCreationReceipt(9, 1, 9007199254740993L, true), parseTemplateCreationReceipt(envelope(tombstone), 9, 1))
        val list = buildJsonObject { put("success", true); put("result", buildJsonArray { add(tombstone) }) }.toString()
        assertThrows(IllegalArgumentException::class.java) { parsePeriodicTemplates(list, 9, 1) }
        for (bad in listOf(tombstone + ("editable" to JsonPrimitive(true)), tombstone + ("nextScheduledTime" to JsonPrimitive(123)), tombstone + ("deleted" to JsonPrimitive("true")))) {
            assertThrows(IllegalArgumentException::class.java) { parseTemplateCreationReceipt(envelope(JsonObject(bad)), 9, 1) }
        }
    }
}
