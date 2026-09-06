package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplatePayloadTest {
    @Test fun scheduledFullRefreshRejectsMalformedResultsBeforeDeletingLocalRows() {
        val api = FinexyApi(SecureStore(InstrumentationRegistry.getInstrumentation().targetContext, "schedule-list-fixture"))
        listOf("{}", "{\"success\":true,\"result\":null}",
            "{\"success\":true,\"result\":[null]}",
            "{\"success\":true,\"result\":[{\"id\":\"1\",\"templateType\":1}]}",
            "{\"success\":true,\"result\":[{\"id\":\"1\",\"templateType\":2},{\"id\":\"1\",\"templateType\":2}]}")
            .forEach { raw -> assertThrows(RuntimeException::class.java) { api.parseScheduledTemplateList(raw) } }
        assertEquals(emptyList<RemoteTemplate>(), api.parseScheduledTemplateList("{\"success\":true,\"result\":[]}"))
    }
    @Test fun fullTemplateFieldsMapToServerContractWithoutChangingIds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = FinexyApi(SecureStore(context, "template-payload-fixture"))
        val payload = api.templatePayload(TemplateEntity(42, "工资", 2, 201, 10, 888888, "九月工资", "[\"31\",\"32\"]"))
        assertEquals("工资", payload.getString("name"))
        assertEquals(2, payload.getInt("type"))
        assertEquals("201", payload.getString("categoryId"))
        assertEquals("10", payload.getString("sourceAccountId"))
        assertEquals(888888L, payload.getLong("sourceAmount"))
        assertEquals("九月工资", payload.getString("comment"))
        assertEquals(listOf("31", "32"), JSONArray(payload.getJSONArray("tagIds").toString()).let { array -> (0 until array.length()).map(array::getString) })
    }

    @Test fun scheduledTemplatePayloadAndResponsePreserveScheduleAndTransferFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = FinexyApi(SecureStore(context, "scheduled-template-payload-fixture"))
        val template = TemplateEntity(
            9007199254740993L, "房租", 4, 201, 10, 888800, "每月房租", "[\"31\"]",
            templateType = 2, destinationAccountId = 11, destinationAmountMinor = 888800,
            scheduledFrequencyType = 2, scheduledFrequency = "1,-1", scheduledStartDate = "2026-09-05",
            scheduledEndDate = null, utcOffset = 480
        )
        val payload = api.templatePayload(template)
        assertEquals(2, payload.getInt("templateType"))
        assertEquals("9007199254740993", template.id.toString())
        assertEquals("11", payload.getString("destinationAccountId"))
        assertEquals("1,-1", payload.getString("scheduledFrequency"))
        assertEquals(480, payload.getInt("utcOffset"))
        assertEquals(JSONObject.NULL, payload.get("scheduledEndDate"))

        val parsed = api.parseTemplateResponse(JSONObject().put("result", JSONArray().put(JSONObject()
            .put("id", "9007199254740993").put("templateType", 2).put("name", "房租").put("type", 4)
            .put("categoryId", "201").put("sourceAccountId", "10").put("destinationAccountId", "11")
            .put("sourceAmount", 888800).put("destinationAmount", 888800).put("tagIds", JSONArray().put("31"))
            .put("comment", "每月房租").put("scheduledFrequencyType", 2).put("scheduledFrequency", "1,-1")
            .put("scheduledStartDate", "2026-09-05").put("scheduledEndDate", JSONObject.NULL).put("utcOffset", 480)
            .put("scheduledAt", 60).put("nextScheduledTime", "1788195600").put("displayOrder", 3))).toString()).single()
        assertEquals(9007199254740993L, parsed.id)
        assertEquals(11L, parsed.destinationAccountId)
        assertEquals(2, parsed.scheduledFrequencyType)
        assertEquals(1788195600L, parsed.nextScheduledTime)
        assertNull(parsed.scheduledEndDate)
    }
}
