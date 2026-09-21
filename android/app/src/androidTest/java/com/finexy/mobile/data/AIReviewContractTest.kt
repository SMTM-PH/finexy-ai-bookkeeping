package com.finexy.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AIReviewContractTest {
    private fun api() = FinexyApi(SecureStore(InstrumentationRegistry.getInstrumentation().targetContext, "ai-review-contract-fixture"))

    private fun item(id: String = "9007199254740993") = JSONObject()
        .put("id", id).put("sourceType", AIReviewItemEntity.SOURCE_TEXT).put("status", AIReviewItemEntity.STATUS_PENDING)
        .put("sourceText", "午餐 36 元").put("failureReason", "").put("createdUnixTime", 1_788_700_000)
        .put("recognizedData", JSONObject().put("type", TransactionRepository.TYPE_EXPENSE)
            .put("sourceAccountId", "10").put("categoryId", "201").put("sourceAmount", 3600).put("comment", "午餐"))

    @Test fun listParserPreservesStringIdsAndRejectsPartialResponses() {
        val parsed = api().parseAIReviewItems(JSONObject().put("success", true).put("result", JSONArray().put(item())).toString()).single()
        assertEquals(9007199254740993L, parsed.id)
        assertEquals(3600L, parsed.recognized()?.sourceAmountMinor)
        assertEquals("午餐", parsed.recognized()?.comment)

        listOf(
            "{}", "{\"success\":false,\"result\":[]}", "{\"success\":true,\"result\":null}",
            JSONObject().put("success", true).put("result", JSONArray().put(JSONObject())).toString(),
            JSONObject().put("success", true).put("result", JSONArray().put(item("1")).put(item("1"))).toString()
        ).forEach { raw -> assertThrows(RuntimeException::class.java) { api().parseAIReviewItems(raw) } }
        assertEquals(emptyList<RemoteAIReviewItem>(), api().parseAIReviewItems("{\"success\":true,\"result\":[]}"))
    }

    @Test fun reviewDisappearsFromFlowAfterDraftIsAttachedWithoutDeletingItEarly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val repository = TransactionRepository(context, database, importLegacy = false)
        try {
            repository.migrateLegacyDataIfNeeded()
            val remote = RemoteAIReviewItem(42, 1, 1, "午餐 36 元", item("42").getJSONObject("recognizedData").toString(), "", 100)
            repository.cacheAIReviewItem(remote)
            assertEquals(listOf(42L), repository.observeAIReviewItems().first().map { it.id })
            repository.save(TransactionDraft("review-draft", TransactionRepository.TYPE_EXPENSE, TransactionEntity.LOCAL_ACCOUNT_ID,
                null, "餐饮", 3600, "午餐", "[]", reviewItemId = 42))
            assertEquals(emptyList<AIReviewItemEntity>(), repository.observeAIReviewItems().first())
            // The cached item remains durable until SyncEngine uploads the draft
            // and resolves it on the server; only the user-facing Flow hides it.
            database.dao().clearTransactionReviewItem("review-draft")
            assertEquals(listOf(42L), repository.observeAIReviewItems().first().map { it.id })
        } finally { database.close() }
    }

    @Test fun immediatePullAfterUploadPreservesReviewLinkUntilResolution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        val repository = TransactionRepository(context, database, importLegacy = false)
        try {
            val localId = "review-upload"
            repository.saveAccounts(listOf(AccountEntity(10, "钱包", "CNY")))
            repository.saveCategories(listOf(CategoryEntity(201, "餐饮", parentId = 200, type = 2)))
            repository.save(TransactionDraft(localId, TransactionRepository.TYPE_EXPENSE, 10, 201, "餐饮", 3600, "午餐", "[]", reviewItemId = 42))
            val uploaded = database.dao().findTransaction(localId)!!
            val remote = RemoteTransaction(
                id = 99, timeSequenceId = 100, type = uploaded.type, categoryId = uploaded.categoryId,
                categoryName = uploaded.categoryName, sourceAccountId = uploaded.sourceAccountId,
                destinationAccountId = null, sourceAmountMinor = uploaded.sourceAmountMinor,
                destinationAmountMinor = 0, currency = "CNY", comment = uploaded.comment,
                time = uploaded.time / 1000, utcOffset = uploaded.utcOffset, tagIdsJson = "[]"
            )
            repository.markSynced(uploaded, remote)
            repository.mergeRemote(listOf(remote))

            val merged = database.dao().findTransaction(localId)!!
            assertEquals(42L, merged.reviewItemId)
            assertEquals(listOf(42L), repository.pendingAIReviewResolutions().mapNotNull { it.reviewItemId })
        } finally { database.close() }
    }
}
