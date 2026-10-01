package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Opt-in: run only against a disposable Docker server with no mounted data volume. */
@RunWith(AndroidJUnit4::class)
class DockerStatementImportE2ETest {
    @Test fun alipayAndWechatParsePreviewAndPostOnlyAfterExplicitMapping() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Requires an isolated Docker test server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "statement-import-e2e-${UUID.randomUUID()}")
        val client = OkHttpClient()
        val username = "statement" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = UUID.randomUUID().toString()
        var token: String? = null

        fun post(path: String, payload: JSONObject): JSONObject {
            val builder = Request.Builder().url("$url/api/$path").header("X-Timezone-Offset", "480")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            token?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                val root = JSONObject(response.body!!.string())
                check(response.isSuccessful && root.optBoolean("success")) { "Disposable test server rejected $path" }
                return root
            }
        }

        try {
            val categories = JSONArray().put(JSONObject().put("name", "Statement Parent").put("type", 2)
                .put("icon", "1").put("color", "F05537").put("subCategories", JSONArray().put(
                    JSONObject().put("name", "Statement Expense").put("type", 2).put("parentId", "0")
                        .put("icon", "1").put("color", "F05537"))))
            post("register.json", JSONObject().put("username", username).put("email", "$username@example.com")
                .put("nickname", "Statement E2E").put("password", password).put("language", "zh-Hans")
                .put("defaultCurrency", "CNY").put("firstDayOfWeek", 1).put("categories", categories))
            store.put(FinexyApi.KEY_SERVER_URL, url!!)
            token = FinexyApi(store).login(username, password).token
            store.put(FinexyApi.KEY_TOKEN, token!!)
            val api = FinexyApi(store)
            val accountId = post("v1/accounts/add.json", JSONObject().put("name", "Statement Wallet")
                .put("category", 1).put("type", 1).put("icon", "1").put("color", "F05537")
                .put("currency", "CNY").put("balance", 0).put("balanceTime", System.currentTimeMillis() / 1000))
                .getJSONObject("result").getString("id").toLong()
            val categoryId = api.parseCategoryResponse(api.listCategories()).first { it.type == 2 && it.parentId > 0 }.id
            val now = System.currentTimeMillis() / 1000
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            val first = date.format(Date(now * 1000))
            val second = date.format(Date((now + 2) * 1000))
            val alipayCsv = ("------------------------------------------------------------------------------------\n" +
                "支付宝支付科技有限公司  电子客户回单\n" +
                "交易时间,交易分类,交易对方,商品说明,金额,收/支,收/付款方式,交易状态,备注,\n" +
                "$first,日用品,测试商家,测试商品,1.23,支出,余额,交易成功,合同测试,\n")
                .toByteArray(charset("GB18030"))
            val wechatCsv = ("微信支付账单明细,,,,\n微信昵称：[test],,,,\n" +
                "起始时间：[2026-01-01 00:00:00] 终止时间：[2026-12-31 23:59:59],,,,\n,,,,\n" +
                "----------------------微信支付账单明细列表--------------------,,,,\n" +
                "交易时间,交易类型,收/支,金额(元),当前状态\n" +
                "$second,商户消费,支出,￥2.34,支付成功\n").toByteArray()
            val alipay = api.parseStatement("支付宝交易明细.csv", alipayCsv, "alipay_app_csv")
            val wechat = api.parseStatement("微信支付账单.csv", wechatCsv, "wechat_pay_app_csv")
            assertEquals(1, alipay.size)
            assertEquals(1, wechat.size)
            assertEquals(123L, alipay.single().sourceAmount)
            assertEquals(234L, wechat.single().sourceAmount)
            val account = AccountEntity(accountId, "Statement Wallet", "CNY")
            val category = CategoryEntity(categoryId, "Statement Expense", parentId = 1, type = 2)
            assertFalse(alipay.single().ready(listOf(account), listOf(category)))
            val rows = (alipay + wechat).map { it.copy(sourceAccountId = accountId, categoryId = categoryId) }
            assertTrue(rows.all { it.ready(listOf(account), listOf(category)) })
            assertEquals(2, api.importStatement(rows, 0, UUID.randomUUID().toString()))
            val posted = api.parseTransactionResponse(api.listTransactions()).filter { it.type == 3 }
            assertEquals(2, posted.size)
            assertEquals(setOf(123L, 234L), posted.map { it.sourceAmountMinor }.toSet())
        } finally {
            store.remove(FinexyApi.KEY_TOKEN)
        }
    }
}
