package com.finexy.mobile.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DockerAIRecognitionE2ETest {
    @Test fun visionModelReviewTransactionAndResolutionLifecycle() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Use only an isolated disposable Docker server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "ai-e2e-${UUID.randomUUID()}"
        val store = SecureStore(context, namespace)
        val username = "ai" + UUID.randomUUID().toString().replace("-", "").take(18)
        val password = UUID.randomUUID().toString()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            store.put(FinexyApi.KEY_SERVER_URL, url!!, durable = true)
            AccountSecurity(FinexyApi(store)).register(username, "$username@example.com", "AI E2E", password, "CNY")
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            val api = FinexyApi(store)
            val repository = TransactionRepository(context, database, importLegacy = false)

            val account = api.createAccount(AccountDraft("AI Test Wallet", 1, "CNY", 0)).single()
            SyncEngine(api, repository, autoUpdateExchangeRates = false).sync()
            val expenseCategory = repository.observeCategories().first().first {
                it.type == 2 && it.parentId > 0 && !it.hidden
            }

            val source = "FINEXY TEST RECEIPT · LUNCH · CNY 36.50"
            val recognized = api.recognizeReceiptImage(
                syntheticReceipt(account.name, expenseCategory.name),
                "finexy-receipt.png",
                "image/png"
            )
            assertEquals(TransactionRepository.TYPE_EXPENSE, recognized.type)
            assertEquals(3650L, recognized.sourceAmountMinor)
            assertEquals(account.id, recognized.sourceAccountId)
            assertEquals(expenseCategory.id, recognized.categoryId)

            val remoteReview = api.createAIReviewItem(AIReviewItemEntity.SOURCE_IMAGE, source, recognized)
            assertEquals(remoteReview.id, api.listAIReviewItems().single().id)
            repository.cacheAIReviewItem(remoteReview)
            assertEquals(remoteReview.id, repository.observeAIReviewItems().first().single().id)

            val localId = UUID.randomUUID().toString()
            repository.save(
                TransactionDraft(
                    localId = localId,
                    type = recognized.type,
                    sourceAccountId = requireNotNull(recognized.sourceAccountId),
                    categoryId = requireNotNull(recognized.categoryId),
                    categoryName = expenseCategory.name,
                    sourceAmountMinor = requireNotNull(recognized.sourceAmountMinor),
                    comment = recognized.comment,
                    tagIdsJson = recognized.tagIdsJson,
                    reviewItemId = remoteReview.id
                )
            )
            val sync = SyncEngine(api, repository, autoUpdateExchangeRates = false).sync()
            assertEquals(1, sync.pushed)
            assertTrue(api.listAIReviewItems().isEmpty())
            assertTrue(repository.observeAIReviewItems().first().isEmpty())

            val posted = database.dao().allTransactions().single { it.localId == localId }
            assertNotNull(posted.serverId)
            assertEquals(SyncState.SYNCED, posted.syncState)
            assertNull(posted.reviewItemId)
            val serverTransaction = api.parseTransactionResponse(api.listTransactions()).single()
            assertEquals(3650L, serverTransaction.sourceAmountMinor)
            assertEquals(TransactionRepository.TYPE_EXPENSE, serverTransaction.type)
        } finally {
            database.close()
            context.deleteSharedPreferences(namespace)
        }
    }

    private fun syntheticReceipt(accountName: String, categoryName: String): ByteArray {
        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 76f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        listOf(
            "FINEXY TEST RECEIPT",
            "DATE 2026-09-08",
            "TOTAL CNY 36.50",
            "ACCOUNT $accountName",
            "CATEGORY $categoryName"
        ).forEachIndexed { index, line ->
            canvas.drawText(line, 90f, 170f + index * 170f, paint)
        }
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
