package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Opt-in acceptance against the isolated disposable Docker server: cron
 * dispatches due schedules into the review queue without touching balances,
 * the client confirms exactly once, and the posted transaction arrives with
 * the correct amount. Run only with `-e finexy.e2e.url` pointing at the
 * throwaway container; the dispatch wait can take up to ~20 minutes because
 * the server cron runs every 15 minutes.
 */
@RunWith(AndroidJUnit4::class)
class DockerOccurrenceE2ETest {
    @Test fun realServerDispatchConfirmBalanceAndDismissRestore() {
        kotlinx.coroutines.runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Requires an isolated Docker test server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "docker-occurrence-e2e-${UUID.randomUUID()}"
        val store = SecureStore(context, prefsName)
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val client = OkHttpClient()
        val username = "android" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = UUID.randomUUID().toString()
        var token: String? = null

        fun post(path: String, payload: JSONObject, authToken: String? = token): JSONObject {
            val builder = Request.Builder().url("$url/api/$path")
                .header("X-Timezone-Offset", "480")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            authToken?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                val result = JSONObject(response.body!!.string())
                check(response.isSuccessful) { "$path failed with HTTP ${response.code}" }
                check(result.optBoolean("success")) { "$path rejected test request" }
                return result
            }
        }

        try {
            val testCategories = JSONArray().put(JSONObject().put("name", "Occurrence Parent").put("type", 2)
                .put("icon", "1").put("color", "F05537").put("subCategories", JSONArray().put(
                    JSONObject().put("name", "Occurrence Expense").put("type", 2).put("parentId", "0")
                        .put("icon", "1").put("color", "F05537"))))
            post("register.json", JSONObject().put("username", username).put("email", "$username@example.com")
                .put("nickname", "Android Occurrence E2E").put("password", password).put("language", "zh-Hans")
                .put("defaultCurrency", "CNY").put("firstDayOfWeek", 1).put("categories", testCategories))
            store.put(FinexyApi.KEY_SERVER_URL, url!!)
            token = FinexyApi(store).login(username, password).token
            store.put(FinexyApi.KEY_TOKEN, token!!)
            val api = FinexyApi(store)
            val accountId = post("v1/accounts/add.json", JSONObject().put("name", "Occurrence Wallet")
                .put("category", 1).put("type", 1).put("icon", "1").put("color", "F05537")
                .put("currency", "CNY").put("balance", 1_000_000).put("balanceTime", System.currentTimeMillis() / 1000))
                .getJSONObject("result").getString("id").toLong()
            val repository = TransactionRepository(context, database, importLegacy = false)
            val engine = SyncEngine(api, repository)
            engine.sync()
            val category = api.parseCategoryResponse(api.listCategories()).first { it.type == 2 && it.parentId != 0L && !it.hidden }

            // The template API fixes execution to template-timezone midnight, so
            // pick the offset that lands scheduled_at inside the next cron window.
            val quarterSeconds = 900
            var nextTick = (System.currentTimeMillis() / 1000 / quarterSeconds + 1) * quarterSeconds
            if (nextTick - System.currentTimeMillis() / 1000 < 120) nextTick += quarterSeconds
            val tickMinute = ((nextTick % 86400) / 60).toInt()
            val utcOffset = if (tickMinute <= 720) -tickMinute else 1440 - tickMinute

            val confirmTemplate = TemplateEntity(0, "Occurrence Rent", 3, category.id, accountId, 420000, "确认入账",
                templateType = 2, scheduledFrequencyType = 3, scheduledFrequency = "1", utcOffset = utcOffset)
            api.createTemplate(confirmTemplate)
            val dismissTemplate = TemplateEntity(0, "Occurrence Subscription", 3, category.id, accountId, 1500, "忽略恢复",
                templateType = 2, scheduledFrequencyType = 3, scheduledFrequency = "1", utcOffset = utcOffset)
            api.createTemplate(dismissTemplate)
            engine.sync()

            // Wait for the cron tick that dispatches both due templates.
            val deadline = nextTick * 1000 + 17 * 60_000
            while (repository.allOccurrences().count { it.status == ScheduledOccurrenceEntity.STATUS_PENDING } < 2) {
                check(System.currentTimeMillis() < deadline) { "cron never dispatched the due templates" }
                Thread.sleep(30_000)
                engine.sync()
            }
            val pending = repository.allOccurrences().filter { it.status == ScheduledOccurrenceEntity.STATUS_PENDING }
            assertEquals(2, pending.size)
            assertEquals(1_000_000L, api.parseAccountResponse(api.listAccounts()).single { it.id == accountId }.balanceMinor)
            assertTrue(api.parseTransactionResponse(api.listTransactions()).none { it.type == 3 })

            // Dismiss one occurrence, restore it, then confirm the other one.
            val dismissed = pending.first { it.comment == "忽略恢复" }
            val confirmed = pending.first { it.comment == "确认入账" }
            api.setOccurrenceDismissed(dismissed.templateId, dismissed.scheduledUnixTime, dismissed = true)
            repository.applyOccurrenceAction(dismissed.templateId, dismissed.scheduledUnixTime, ScheduledOccurrenceEntity.STATUS_DISMISSED)
            assertEquals(ScheduledOccurrenceEntity.STATUS_DISMISSED,
                repository.allOccurrences().first { it.templateId == dismissed.templateId }.status)
            api.setOccurrenceDismissed(dismissed.templateId, dismissed.scheduledUnixTime, dismissed = false)
            repository.applyOccurrenceAction(dismissed.templateId, dismissed.scheduledUnixTime, ScheduledOccurrenceEntity.STATUS_PENDING)

            val transactionId = api.confirmOccurrence(confirmed.templateId, confirmed.scheduledUnixTime)
            assertTrue(transactionId > 0)
            repository.applyOccurrenceAction(confirmed.templateId, confirmed.scheduledUnixTime,
                ScheduledOccurrenceEntity.STATUS_CONFIRMED, transactionId)
            assertEquals(transactionId, api.confirmOccurrence(confirmed.templateId, confirmed.scheduledUnixTime))
            engine.sync()

            val posted = api.parseTransactionResponse(api.listTransactions()).filter { it.type == 3 }
            assertEquals(1, posted.size)
            assertEquals(420000L, posted.single().sourceAmountMinor)
            assertEquals("确认入账", posted.single().comment)
            assertEquals(580_000L, api.parseAccountResponse(api.listAccounts()).single { it.id == accountId }.balanceMinor)
            engine.sync()
            val pulled = database.dao().allTransactions().filter { it.type == 3 && !it.deleted }
            assertEquals(1, pulled.size)
            assertEquals(transactionId, pulled.single().serverId)

            // Another account must not be able to confirm someone else's queue.
            val otherPrefs = "docker-occurrence-e2e-other-${UUID.randomUUID()}"
            val otherStore = SecureStore(context, otherPrefs)
            val otherUsername = "android" + UUID.randomUUID().toString().replace("-", "").take(16)
            val otherPassword = UUID.randomUUID().toString()
            post("register.json", JSONObject().put("username", otherUsername).put("email", "$otherUsername@example.com")
                .put("nickname", "Other").put("password", otherPassword).put("language", "zh-Hans")
                .put("defaultCurrency", "CNY").put("firstDayOfWeek", 1).put("categories", testCategories))
            otherStore.put(FinexyApi.KEY_SERVER_URL, url)
            val otherToken = FinexyApi(otherStore).login(otherUsername, otherPassword).token
            otherStore.put(FinexyApi.KEY_TOKEN, otherToken)
            assertThrows(java.lang.Exception::class.java) {
                kotlinx.coroutines.runBlocking {
                    FinexyApi(otherStore).confirmOccurrence(confirmed.templateId, confirmed.scheduledUnixTime)
                }
            }
            context.deleteSharedPreferences(otherPrefs)
        } finally {
            database.close()
            context.deleteSharedPreferences(prefsName)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        }
    }
}
