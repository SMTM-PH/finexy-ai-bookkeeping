package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryContractTest {
    @Test fun createAndModifyPayloadRespectHierarchyAndImmutableType() {
        val draft = CategoryDraft("旅行", 2, 10, 100, "ff6b22", "假期")
        val create = draft.toCreatePayload()
        assertEquals(2, create.getInt("type")); assertEquals("10", create.getString("parentId")); assert(create.has("clientSessionId"))
        val modify = draft.toModifyPayload(9007199254740993L, false)
        assertEquals("9007199254740993", modify.getString("id")); assertFalse(modify.has("type")); assertFalse(modify.has("clientSessionId"))
    }

    @Test fun parserPreservesCommentOrderAndNestedType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = FinexyApi(SecureStore(context))
        val child = JSONObject().put("id", "12").put("name", "地铁").put("parentId", "10").put("icon", "100").put("color", "000000").put("comment", "通勤").put("displayOrder", 3)
        val parent = JSONObject().put("id", "10").put("name", "交通").put("parentId", "0").put("type", 2).put("icon", "100").put("color", "000000").put("subCategories", JSONArray().put(child))
        val parsed = api.parseCategoryResponse(JSONObject().put("result", JSONObject().put("2", JSONArray().put(parent))).toString())
        assertEquals(2, parsed.size); assertEquals(2, parsed.last().type); assertEquals("通勤", parsed.last().comment); assertEquals(3, parsed.last().displayOrder)
    }
}
