package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountContractTest {
    @Test fun createPayloadUsesServerTypesStringIdsAndUnixSeconds() {
        val before = System.currentTimeMillis() / 1000
        val payload = AccountDraft("旅行钱包", 4, "usd", 12345, "备用").toCreatePayload()
        assertEquals(1, payload.getInt("type"))
        assertEquals(4, payload.getInt("category"))
        assertEquals("500", payload.getString("icon"))
        assertEquals("USD", payload.getString("currency"))
        assertEquals(12345L, payload.getLong("balance"))
        assert(payload.getLong("balanceTime") >= before)
        assert(payload.getString("clientSessionId").isNotBlank())
    }

    @Test fun accountParserPreservesCrudMetadataAndHiddenRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = FinexyApi(SecureStore(context))
        val item = JSONObject().put("id", "9007199254740993").put("name", "信用卡")
            .put("parentId", "0").put("category", 3).put("type", 1).put("icon", "100")
            .put("color", "ff6b22").put("currency", "CNY").put("balance", -8800)
            .put("comment", "每月还款").put("displayOrder", 7).put("creditCardStatementDate", 18).put("hidden", true)
        val parsed = api.parseAccountResponse(JSONObject().put("result", JSONArray().put(item)).toString()).single()
        assertEquals(9007199254740993L, parsed.id)
        assertEquals(3, parsed.category)
        assertEquals(18, parsed.creditCardStatementDate)
        assertEquals("每月还款", parsed.comment)
        assertFalse(parsed.color.isBlank())
        assert(parsed.hidden)
    }

    @Test fun editingOneSubAccountKeepsEverySiblingInModifyPayload() {
        val parent = AccountEntity(20, "多币种钱包", "---", type = 2, category = 4)
        val usd = AccountEntity(21, "美元", "USD", parentId = 20, category = 4)
        val eur = AccountEntity(22, "欧元", "EUR", parentId = 20, category = 4)
        val payload = accountModifyPayload(usd, "美元现金", "旅行", 0, listOf(parent, usd, eur))
        assertEquals("20", payload.getString("id"))
        val children = payload.getJSONArray("subAccounts")
        assertEquals(2, children.length())
        assertEquals(setOf("美元现金", "欧元"), (0 until children.length()).map { children.getJSONObject(it).getString("name") }.toSet())
        assertFalse(children.getJSONObject(0).has("currency"))
    }

    @Test fun multiAccountCreatePayloadUsesPlaceholderAndCompleteChildren() {
        val payload = AccountDraft("旅行资金", 4, "---", 0, type = 2, subAccounts = listOf(
            AccountDraft("美元", 4, "USD", 1200), AccountDraft("欧元", 4, "EUR", 0)
        )).toCreatePayload()
        assertEquals(2, payload.getInt("type"))
        assertEquals("---", payload.getString("currency"))
        assertEquals(0L, payload.getLong("balance"))
        val children = payload.getJSONArray("subAccounts")
        assertEquals(2, children.length())
        assertEquals("USD", children.getJSONObject(0).getString("currency"))
        assertFalse(children.getJSONObject(0).has("clientSessionId"))
    }
}
