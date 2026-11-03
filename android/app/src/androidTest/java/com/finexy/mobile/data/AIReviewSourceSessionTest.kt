package com.finexy.mobile.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AIReviewSourceSessionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
    private fun recognized() = LedgerAIRecognition(9, 3, 1788700000, 201, 10, 0, -123, 0, emptyList(), "Synthetic refund")
    private fun session(store: AIReviewPersistence, access: suspend () -> Boolean = { true },
        text: suspend (String) -> LedgerAIRecognition = { recognized() }, image: suspend (ByteArray) -> LedgerAIRecognition = { recognized() },
        prepare: suspend (ByteArray, AISourceForm) -> ByteArray = { bytes, _ -> bytes },
        deliver: suspend (AIReviewRequestEntity) -> Unit = {}, current: () -> Boolean = { true }) =
        AIReviewSourceSession(store, 9, access, text, image, prepare, deliver, current)
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff224488.toInt())
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    private fun result(write: AIReviewRequestEntity): LedgerAIReviewItem {
        val payload = JSONObject(write.payloadJson)
        val data = payload.optJSONObject("recognizedData")?.let { parseLedgerAIRecognition(JSONObject().put("success", true).put("result", it).toString(), write.ledgerId) }
        return LedgerAIReviewItem(write.ledgerId, 101, 100, 0, 0, true, false, payload.getInt("sourceType"), 1, payload.getString("sourceText"), data, "", 1788700000)
    }
    private fun rejected(block: suspend () -> Unit) { assertThrows(RuntimeException::class.java) { runBlocking { block() } } }

    @Test fun durableSourceRequiresExplicitResumeAndNeverSendsDuringReload() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); var calls = 0
            val first = session(store, text = { calls++; recognized() }); first.load()
            first.edit(mapOf("sourceText" to "Synthetic raw 1.+")); first.persist(); first.close()
            val restored = session(store, text = { calls++; recognized() }); restored.load()
            assertTrue(restored.state.value.restoring); assertEquals("", restored.state.value.form.text)
            assertEquals(0, calls); assertTrue(store.requests().isEmpty())
            restored.resume(); assertEquals("Synthetic raw 1.+", restored.state.value.form.text); assertTrue(restored.state.value.persisted)
            assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun recognitionPersistsSuggestionButOnlyExplicitSaveCreatesOneStableRequest() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); var modelCalls = 0; var deliveries = 0
            val source = session(store, text = { modelCalls++; recognized() }, deliver = { write ->
                deliveries++; val item = result(write); store.markPosted(write, item.toStoredJson()); store.acknowledge(store.requests().single(), item)
            })
            source.load(); source.edit(mapOf("sourceText" to "Synthetic refund", "sourceType" to "3")); source.persist(); source.recognize()
            assertEquals(1, modelCalls); assertEquals(-123L, source.state.value.form.recognized(9)?.sourceAmountMinor)
            assertTrue(store.requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
            val preview = source.preview(); source.confirmSave(preview)
            assertEquals(1, deliveries); assertTrue(store.requests().isEmpty()); assertNull(store.draft(9, 0))
            assertEquals(3, validateLedgerAICache(store.observeItems(9).first().single()).sourceType)
            assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun repeatedAsyncSavesKeepLatestRawTextAndInvalidateOldSuggestions() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); val source = session(store); source.load()
            source.edit(mapOf("sourceText" to "Synthetic first")); source.persist(); source.recognize()
            assertTrue(source.state.value.form.recognizedJson.isNotEmpty())
            source.edit(mapOf("sourceText" to "Synthetic latest 99.+"))
            coroutineScope { (1..8).map { async { source.persist() } }.awaitAll() }
            assertEquals(source.state.value.form.json(), store.draft(9, 0)?.formJson)
            assertEquals("", source.state.value.form.recognizedJson); assertTrue(store.requests().isEmpty())
        } finally { db.close() }
    }

    @Test fun cancelledRecognitionCannotOverwriteNewerInputEvenIfRemoteReturnsLate() = runBlocking<Unit> {
        val db = database()
        try {
            val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<LedgerAIRecognition>()
            val store = AIReviewPersistence(db); val source = session(store, text = { started.complete(Unit); response.await() })
            source.load(); source.edit(mapOf("sourceText" to "Synthetic old")); source.persist()
            val job = launch { source.recognize() }; started.await()
            source.cancelRecognition(); source.edit(mapOf("sourceText" to "Synthetic newer")); source.persist()
            response.complete(recognized()); job.join()
            assertEquals("Synthetic newer", source.state.value.form.text); assertEquals("", source.state.value.form.recognizedJson)
            assertEquals(source.state.value.form.json(), store.draft(9, 0)?.formJson); assertTrue(store.requests().isEmpty())
        } finally { db.close() }
    }

    @Test fun changedPreviewAndRevokedPermissionCannotCreateAQueueRequest() = runBlocking<Unit> {
        val db = database()
        try {
            var allowed = true; val store = AIReviewPersistence(db); val source = session(store, access = { allowed })
            source.load(); source.edit(mapOf("sourceText" to "Synthetic source")); source.persist()
            val preview = source.preview(); source.edit(mapOf("sourceText" to "Synthetic changed")); source.persist()
            rejected { source.confirmSave(preview) }; val current = source.preview(); allowed = false
            rejected { source.confirmSave(current) }; assertTrue(store.requests().isEmpty()); assertEquals("Synthetic changed", source.state.value.form.text)
        } finally { db.close() }
    }

    @Test fun unknownSaveReplaysExactPayloadAndKnownReceiptDoesNotLoseOriginalImage() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); val payloads = mutableListOf<String>(); var lose = true
            val source = session(store, deliver = { write ->
                payloads += write.payloadJson
                if (lose) throw IOException("Synthetic lost response")
                val item = result(write); store.markPosted(write, item.toStoredJson()); store.acknowledge(store.requests().single(), item)
            })
            source.load(); source.selectImage(png()); source.confirmSave(source.preview())
            val frozen = store.requests().single(); assertArrayEquals(png(), store.requestSourceImage(frozen))
            assertNotNull(source.state.value.request); rejected { source.discard() }
            lose = false; source.retry()
            assertEquals(2, payloads.size); assertEquals(payloads[0], payloads[1]); assertTrue(store.requests().isEmpty())
            assertTrue(db.dao().aiReviewImages(9).isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun sessionChangeClearsDisplayedContentAndRejectsLateRecognitionButKeepsOldScopedDraft() = runBlocking<Unit> {
        val db = database()
        try {
            var current = true; val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<LedgerAIRecognition>()
            val store = AIReviewPersistence(db); val source = session(store, text = { started.complete(Unit); response.await() }, current = { current })
            source.load(); source.edit(mapOf("sourceText" to "Synthetic old identity")); source.persist()
            val job = launch { source.recognize() }; started.await(); current = false; response.complete(recognized()); job.join()
            assertEquals("", source.state.value.form.text); assertFalse(source.state.value.ready)
            assertTrue(requireNotNull(store.draft(9, 0)).formJson.contains("Synthetic old identity")); assertTrue(store.requests().isEmpty())
        } finally { db.close() }
    }

    @Test fun originalCropRotationAndSuggestionRestoreWithoutAnotherModelCall() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); var calls = 0; var sentCrop = ""
            val source = session(store, image = { calls++; recognized() }, prepare = { bytes, form -> sentCrop = form.fields.getValue("cropLeft"); bytes })
            source.load(); source.selectImage(png()); source.edit(mapOf("cropLeft" to "0.2", "rotation" to "90")); source.persist(); source.recognize(); source.close()
            val restored = session(store, image = { calls++; recognized() }); restored.load(); restored.resume()
            assertEquals("0.2", sentCrop); assertEquals(90, restored.state.value.form.rotation)
            assertEquals("0.2", restored.state.value.form.fields["cropLeft"]); assertNotNull(restored.state.value.form.recognized(9))
            assertEquals(1, calls); assertArrayEquals(png(), restored.original()); assertTrue(store.requests().isEmpty())
        } finally { db.close() }
    }

    @Test fun viewerMayRecognizeAndKeepLocalSourceButCannotCreateServerQueue() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); var calls = 0
            val source = session(store, access = { false }, text = { calls++; recognized() })
            source.load(); assertFalse(source.state.value.canWrite); assertTrue(source.state.value.editable)
            source.edit(mapOf("sourceText" to "Synthetic viewer source")); source.persist(); source.recognize()
            assertEquals(1, calls); assertNotNull(source.state.value.form.recognized(9))
            val preview = source.preview(); rejected { source.confirmSave(preview) }
            assertTrue(store.requests().isEmpty()); assertNotNull(store.draft(9, 0)); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun sourceConfirmationRetainsGoNonWhitespaceControlCharactersAndUnsupportedServiceKeepsDraft() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); var payload = ""
            val source = session(store, deliver = { write -> payload = write.payloadJson; throw IOException("Synthetic unknown save") })
            source.load(); source.edit(mapOf("sourceText" to "\u001c\u001f")); source.persist(); source.confirmSave(source.preview())
            assertEquals("\u001c\u001f", JSONObject(payload).getString("sourceText"))
            source.close()
            val old = session(store, access = { throw IllegalArgumentException("当前服务不支持可靠的账本 AI 流程") })
            old.load(); assertFalse(old.state.value.ready); assertTrue(old.state.value.message.orEmpty().contains("升级服务"))
            assertEquals(payload, store.requests().single().payloadJson); assertNotNull(store.draft(9, 0))
        } finally { db.close() }
    }

    @Test fun actualCropEncoderProducesReadableJpegAfterRotationWithoutChangingOriginalBytes() = runBlocking<Unit> {
        val original = png(); val digest = aiImageDigest(original)
        val form = AISourceForm(AISourceForm.defaults + mapOf("rotation" to "180"))
        val encoded = com.finexy.mobile.prepareAISourceImage(original, form)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        assertEquals("image/jpeg", bounds.outMimeType); assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
        assertEquals(digest, aiImageDigest(original))
    }

    @Test fun rawConfirmationPreservesRefundAndCrossCurrencyTransferWithoutImplicitAccountSelection() {
        val accounts = listOf(AccountEntity(10, "Synthetic CNY", "CNY"), AccountEntity(11, "Synthetic USD", "USD"))
        val categories = listOf(CategoryEntity(200, "Expense", type = 2), CategoryEntity(201, "Leaf", parentId = 200, type = 2),
            CategoryEntity(300, "Transfer", type = 3), CategoryEntity(301, "Leaf", parentId = 300, type = 3))
        val item = LedgerAIReviewItem(9, 101, 100, 0, 0, true, false, 1, 1, "Synthetic", recognized(), "", 1)
        val refund = AIReviewEditorForm.initial(item, ZoneId.of("Asia/Shanghai"))
        val payload = JSONObject(refund.payload(9, 101, accounts, categories)).getJSONObject("transaction")
        assertEquals(-123L, payload.getLong("sourceAmount")); assertEquals("10", payload.getString("sourceAccountId"))
        val transfer = refund.copy(fields = refund.fields + mapOf("type" to "4", "direction" to "internal", "categoryId" to "301", "destinationAccountId" to "11", "sourceAmount" to "12.34", "destinationAmount" to "5.67"))
        val tx = JSONObject(transfer.payload(9, 101, accounts, categories)).getJSONObject("transaction")
        assertEquals(1234L, tx.getLong("sourceAmount")); assertEquals(567L, tx.getLong("destinationAmount"))
        assertThrows(RuntimeException::class.java) { transfer.changed("sourceAccountId", "").payload(9, 101, accounts, categories) }
        assertThrows(RuntimeException::class.java) { transfer.changed("sourceAmount", "1.+").payload(9, 101, accounts, categories) }
    }

    @Test fun ambiguousOrMissingLocalTimeOffsetAndCategoryNeverBecomeDefaultValues() {
        val accounts = listOf(AccountEntity(10, "Synthetic", "CNY"))
        val categories = listOf(CategoryEntity(200, "Parent", type = 2), CategoryEntity(201, "Leaf", parentId = 200, type = 2))
        val item = LedgerAIReviewItem(9, 101, 100, 0, 0, true, false, 1, 1, "Synthetic", recognized(), "", 1)
        val form = AIReviewEditorForm.initial(item).copy(fields = AIReviewEditorForm.initial(item).fields + mapOf("timeZone" to "America/New_York", "dateTime" to "2026-03-08T02:30:00", "utcOffset" to "-300"))
        assertThrows(RuntimeException::class.java) { form.payload(9, 101, accounts, categories) }
        val fall = form.copy(fields = form.fields + mapOf("dateTime" to "2026-11-01T01:30:00", "utcOffset" to "-240"))
        val first = JSONObject(fall.payload(9, 101, accounts, categories)).getJSONObject("transaction").getLong("time")
        val second = JSONObject(fall.changed("utcOffset", "-300").payload(9, 101, accounts, categories)).getJSONObject("transaction").getLong("time")
        assertEquals(3600L, second - first)
        assertThrows(RuntimeException::class.java) { fall.changed("categoryId", "").payload(9, 101, accounts, categories) }
    }

    @Test fun legacyUncertainTransactionStaysQuarantinedWhenQueueNoLongerListsIt() = runBlocking<Unit> {
        val db = database()
        try {
            val repo = TransactionRepository(context, db, false)
            val legacy = TransactionEntity("legacy-uncertain", type = 3, categoryName = "Synthetic", sourceAmountMinor = 123, comment = "Synthetic", time = 1, sourceAccountId = 10, categoryId = 201, reviewItemId = 101)
            db.dao().upsertTransaction(legacy); repo.replaceAIReviewItems(emptyList())
            assertEquals(101L, db.dao().findTransaction(legacy.localId)?.reviewItemId)
            val draft = repo.aiReview.saveDraft(0, 101, "{\"amount\":\"1.+\"}")
            val payload = confirmationPayload(0, 101)
            rejected { repo.aiReview.enqueue(draft, "confirm", payload) }
            assertTrue(repo.aiReview.requests().isEmpty()); assertEquals(legacy, db.dao().findTransaction(legacy.localId))
        } finally { db.close() }
    }

    @Test fun legacyRowsRestoredAfterConfirmationWasQueuedBlockUploadWithoutReplacingTheOriginal() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); val draft = store.saveDraft(0, 101, "{\"amount\":\"1.+\"}")
            val write = store.enqueue(draft, "confirm", confirmationPayload(0, 101))
            db.dao().upsertTransaction(TransactionEntity("legacy-late", type = 3, categoryName = "Synthetic", sourceAmountMinor = 123, comment = "Synthetic", time = 1, reviewItemId = 101))
            var posted = 0
            val result = AIReviewDelivery(store, { posted++; error("Must not send") }, { _, _ -> error("No receipt") }).push()
            assertEquals(0, posted); assertEquals(1, result.errors.size)
            assertEquals(write.payloadJson, store.requests().single().payloadJson); assertEquals(AIReviewRequestEntity.PENDING, store.requests().single().state)
        } finally { db.close() }
    }

    private fun confirmationPayload(ledger: Long, id: Long): String {
        val item = LedgerAIReviewItem(ledger, id, 100, 0, 0, true, false, 1, 1, "Synthetic", recognized().copy(ledgerId = ledger), "", 1)
        return AIReviewEditorForm.initial(item, ZoneId.of("Asia/Shanghai")).payload(ledger, id, listOf(AccountEntity(10, "Synthetic", "CNY")),
            listOf(CategoryEntity(200, "Parent", type = 2), CategoryEntity(201, "Leaf", parentId = 200, type = 2)))
    }
}
