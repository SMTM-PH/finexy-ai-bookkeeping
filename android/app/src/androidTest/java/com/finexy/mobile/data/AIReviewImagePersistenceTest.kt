package com.finexy.mobile.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AIReviewImagePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "synthetic-image-backup-passphrase"
    private val form = "{\"sourceText\":\"Synthetic source\",\"cropLeft\":\"0.125\",\"rotation\":\"90\",\"amount\":\"1.+\",\"recognizedJson\":\"{suggestion}\"}"
    private fun database() = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
    private fun image(large: Boolean = false): ByteArray {
        val size = if (large) 1024 else 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (large) {
            val random = java.util.Random(41)
            bitmap.setPixels(IntArray(size * size) { random.nextInt() or (0xff shl 24) }, 0, size, 0, 0, size, size)
        } else bitmap.eraseColor(0xff3377aa.toInt())
        return ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); bitmap.recycle(); out.toByteArray() }
    }
    private fun create() = JSONObject().put("ledgerId", "9").put("clientSessionId", UUID.randomUUID().toString())
        .put("sourceType", 2).put("sourceText", "Synthetic source").put("failureReason", "").toString()
    private fun remote() = LedgerAIReviewItem(9, 101, 100, 0, 0, true, false, 2, 1, "Synthetic source", null, "", 1788700000)
    private fun rejected(block: suspend () -> Unit) { assertThrows(RuntimeException::class.java) { runBlocking { block() } } }
    private fun alias() = "finexy_image_test_" + UUID.randomUUID().toString().replace("-", "")
    private fun deleteKey(alias: String) { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }

    @Test fun migrationTwentyFiveKeepsFinancialAndFrozenStateWithoutManufacturingImages() = runBlocking<Unit> {
        val name = "ai-image-migrate-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val store = AIReviewPersistence(db)
            val draft = store.saveDraft(9, 0, form)
            val write = store.enqueue(draft, "create", create())
            val transaction = TransactionEntity("kept", type = 3, categoryName = "Synthetic", sourceAmountMinor = -123, comment = "Synthetic", time = 1)
            db.dao().upsertTransaction(transaction)
            db.openHelper.writableDatabase.apply { removeAIImageSchemaForTest(); execSQL("DROP TABLE room_master_table"); execSQL("PRAGMA user_version=25") }
            db.close()
            db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).addMigrations(FinexyDatabase.MIGRATION_25_26).build()
            assertEquals(transaction, db.dao().findTransaction("kept")); assertEquals(write, AIReviewPersistence(db).requests().single())
            assertEquals(draft, AIReviewPersistence(db).draft(9, 0)); assertTrue(db.dao().aiReviewImages(9).isEmpty())
            db.openHelper.readableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(26, it.getInt(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun multiMegabyteOriginalAndRawCropSurviveReopenWithOnlySmallEncryptedRows() = runBlocking<Unit> {
        val name = "ai-image-reopen-${UUID.randomUUID()}.db"
        val bytes = image(true); assertTrue(bytes.size > 2 * 1024 * 1024)
        var db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
        try {
            val draft = AIReviewPersistence(db).saveSourceImage(0, form, bytes)
            val id = aiDraftImageId(draft)
            val chunks = db.dao().aiReviewImageChunks(0, id)
            assertTrue(chunks.size > 32); assertTrue(chunks.all { it.content.startsWith("local1:") && it.content.length < 90_000 })
            assertFalse(chunks.any { it.content.contains("Synthetic source") || it.content.contains("iVBORw0KGgo") })
            db.close(); db = Room.databaseBuilder(context, FinexyDatabase::class.java, name).build()
            val store = AIReviewPersistence(db)
            assertEquals(draft, store.draft(0, 0)); assertArrayEquals(bytes, store.sourceImage(draft))
            assertEquals("0.125", JSONObject(draft.formJson).getString("cropLeft")); assertTrue(store.requests().isEmpty())
            assertTrue(db.dao().allTransactions().isEmpty()); assertNull(store.draft(9, 0))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun authenticationRejectsCiphertextMetadataIdentityLedgerAndChunkSwaps() = runBlocking<Unit> {
        val db = database(); val key = alias()
        try {
            val crypto = AIReviewImageCrypto("server-user-A", key)
            val store = AIReviewPersistence(db, crypto)
            val draft = store.saveSourceImage(9, form, image())
            val row = db.dao().aiReviewImages(9).single(); val chunk = db.dao().aiReviewImageChunks(9, row.imageId).single()
            rejected { AIReviewImageCrypto("server-user-B", key).decrypt(row, chunk) }
            rejected { crypto.decrypt(row.copy(ledgerId = 10), chunk.copy(ledgerId = 10)) }
            rejected { crypto.decrypt(row.copy(draftId = "foreign-draft"), chunk) }
            rejected { crypto.decrypt(row.copy(width = row.width + 1), chunk) }
            rejected { crypto.decrypt(row, chunk.copy(chunkIndex = 1)) }
            val tampered = chunk.copy(content = chunk.content.dropLast(10) + "AAAAAAAAAA")
            rejected { crypto.decrypt(row, tampered) }
            assertArrayEquals(image(), store.sourceImage(draft)); assertEquals(draft, store.draft(9, 0))
        } finally { db.close(); deleteKey(key) }
    }

    @Test fun oldRequestPinsItsOriginalWhileReplacementAndReceiptKeepNewerDraftImage() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db)
            val first = store.saveSourceImage(9, form, image())
            val write = store.enqueue(first, "create", create())
            val newer = store.saveSourceImage(9, form.replace("90", "180"), image(), first)
            assertEquals(aiDraftImageId(first), write.sourceImageId); assertEquals(2, db.dao().aiReviewImages(9).size)
            assertArrayEquals(image(), store.requestSourceImage(write))
            rejected { store.sourceImage(first) }; rejected { store.discardDraft(newer) }
            assertTrue(store.markPosted(write, remote().toStoredJson()))
            assertTrue(store.acknowledge(store.requests().single(), remote()))
            assertEquals(newer, store.draft(9, 0)); assertEquals(aiDraftImageId(newer), db.dao().aiReviewImages(9).single().imageId)
            assertArrayEquals(image(), store.sourceImage(newer))
            assertFalse(store.discardDraft(first)); assertTrue(store.discardDraft(newer))
            assertTrue(db.dao().aiReviewImages(9).isEmpty()); assertTrue(db.dao().aiReviewImageChunks(9, write.sourceImageId).isEmpty())
        } finally { db.close() }
    }

    @Test fun invalidImagesStaleSavesAndForeignReferencesLeaveCurrentDraftAndChunksIntact() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); val original = store.saveSourceImage(9, form, image())
            rejected { store.saveSourceImage(9, form, byteArrayOf(1, 2, 3), original) }
            rejected { store.saveSourceImage(9, form, ByteArray(AI_IMAGE_MAX_BYTES + 1), original) }
            rejected { store.saveSourceImage(9, "{\"amount\":1.5}", image(), original) }
            rejected { store.saveSourceImage(9, form, image()) }
            rejected { store.saveDraft(10, 0, original.formJson) }
            rejected { store.saveDraft(9, 101, original.formJson) }
            assertEquals(original, store.draft(9, 0)); assertEquals(1, db.dao().aiReviewImages(9).size)
            val noImage = store.saveDraft(9, 0, form, original)
            assertEquals("", aiDraftImageId(noImage)); assertTrue(db.dao().aiReviewImages(9).isEmpty())
        } finally { db.close() }
    }

    @Test fun passwordBackupRestoresOriginalAndPinnedRequestAfterSourceKeystoreLoss() = runBlocking<Unit> {
        val source = database(); val target = database(); val oldKey = alias(); val newKey = alias()
        try {
            val fromCrypto = AIReviewImageCrypto("source-install", oldKey); val toCrypto = AIReviewImageCrypto("target-install", newKey)
            val from = AIReviewPersistence(source, fromCrypto)
            val first = from.saveSourceImage(9, form, image()); val write = from.enqueue(first, "create", create())
            val newer = from.saveSourceImage(9, form.replace("90", "180"), image(), first)
            val encrypted = LedgerBackup(source, "same-server-user", fromCrypto).export(password)
            assertFalse(encrypted.contains("Synthetic source")); assertFalse(encrypted.contains("backup1:"))
            deleteKey(oldKey); rejected { from.sourceImage(newer) }
            val sameDevice = LedgerBackup(source, "same-server-user", fromCrypto)
            sameDevice.restore(sameDevice.preview(encrypted, password))
            assertArrayEquals(image(), from.sourceImage(newer)); assertArrayEquals(image(), from.requestSourceImage(write))
            val backup = LedgerBackup(target, "same-server-user", toCrypto)
            val doc = backup.preview(encrypted, password); assertEquals(20, doc.getInt("schema"))
            backup.restore(doc)
            val restored = AIReviewPersistence(target, toCrypto)
            assertEquals(newer, restored.draft(9, 0)); assertEquals(write, restored.requests().single())
            assertArrayEquals(image(), restored.sourceImage(newer)); assertArrayEquals(image(), restored.requestSourceImage(write))
            assertTrue(target.dao().aiReviewImages(9).all { row -> target.dao().aiReviewImageChunks(9, row.imageId).all { it.content.startsWith("local1:") } })
            // Idempotent merge never overwrites the current draft or a posted receipt.
            val edited = restored.saveDraft(9, 0, JSONObject(newer.formJson).put("amount", "999.").toString(), newer)
            restored.markPosted(write, remote().toStoredJson()); backup.restore(doc)
            assertEquals(edited, restored.draft(9, 0)); assertEquals(AIReviewRequestEntity.POSTED, restored.requests().single().state)
        } finally { source.close(); target.close(); deleteKey(oldKey); deleteKey(newKey) }
    }

    @Test fun corruptIncompleteOrCrossLedgerBackupFailsBeforeAnyFinancialOrImageRestore() = runBlocking<Unit> {
        val source = database(); val target = database()
        try {
            AIReviewPersistence(source).saveSourceImage(9, form, image())
            source.dao().upsertTransaction(TransactionEntity("must-not-restore", type = 3, categoryName = "Synthetic", sourceAmountMinor = 123, comment = "Synthetic", time = 1))
            val backup = LedgerBackup(target, "synthetic")
            val doc = backup.preview(LedgerBackup(source, "synthetic").export(password), password)
            val mutations = listOf<(JSONObject) -> Unit>(
                { it.getJSONObject("tables").getJSONArray("ai_review_image_chunks").getJSONObject(0).put("content", "backup1:AQID") },
                { it.getJSONObject("tables").getJSONArray("ai_review_image_chunks").remove(0) },
                { it.getJSONObject("tables").getJSONArray("ai_review_images").getJSONObject(0).put("ledgerId", 10) },
                { it.getJSONObject("tables").getJSONArray("ai_review_drafts").getJSONObject(0).put("draftId", "foreign") },
                { it.getJSONObject("tables").getJSONArray("ai_review_images").getJSONObject(0).put("sha256", "0".repeat(64)) }
            )
            for (mutate in mutations) {
                val invalid = JSONObject(doc.toString()); mutate(invalid)
                rejected { backup.restore(invalid) }; rejected { backup.preview(PrivacyCrypto.encrypt(invalid.toString(), password), password) }
                assertTrue(target.dao().allTransactions().isEmpty()); assertTrue(target.dao().aiReviewImages(9).isEmpty()); assertNull(AIReviewPersistence(target).draft(9, 0))
            }
        } finally { source.close(); target.close() }
    }

    @Test fun schemaNineteenWithoutImageTablesOrRequestReferenceStillRestoresExactOriginalRequest() = runBlocking<Unit> {
        val source = database(); val target = database()
        try {
            val from = AIReviewPersistence(source); val draft = from.saveDraft(9, 0, form); val request = from.enqueue(draft, "create", create())
            val doc = JSONObject(PrivacyCrypto.decrypt(LedgerBackup(source, "synthetic").export(password), password)).put("schema", 19)
            doc.getJSONObject("tables").apply { remove("ai_review_images"); remove("ai_review_image_chunks"); getJSONArray("ai_review_requests").getJSONObject(0).remove("sourceImageId") }
            val backup = LedgerBackup(target, "synthetic"); backup.restore(backup.preview(PrivacyCrypto.encrypt(doc.toString(), password), password))
            assertEquals(request, AIReviewPersistence(target).requests().single()); assertEquals(draft, AIReviewPersistence(target).draft(9, 0))
            assertTrue(target.dao().aiReviewImages(9).isEmpty())
        } finally { source.close(); target.close() }
    }

    @Test fun restoringOldSourceKeepsNewDeviceImageAndPrunesUnreferencedImportedOriginal() = runBlocking<Unit> {
        val source = database(); val target = database()
        try {
            AIReviewPersistence(source).saveSourceImage(9, form, image())
            val current = AIReviewPersistence(target).saveSourceImage(9, form.replace("90", "270"), image())
            val backup = LedgerBackup(target, "synthetic")
            backup.restore(backup.preview(LedgerBackup(source, "synthetic").export(password), password))
            assertEquals(current, AIReviewPersistence(target).draft(9, 0)); assertEquals(aiDraftImageId(current), target.dao().aiReviewImages(9).single().imageId)
            assertArrayEquals(image(), AIReviewPersistence(target).sourceImage(current))
        } finally { source.close(); target.close() }
    }

    @Test fun clearLocalRemovesEncryptedChunksAndDraftsWithoutExportingKeys() = runBlocking<Unit> {
        val db = database()
        try {
            val store = AIReviewPersistence(db); val draft = store.saveSourceImage(9, form, image()); store.saveSourceImage(0, form, image())
            LedgerBackup(db, "synthetic").clearLocal("清空当前账本")
            assertTrue(db.dao().aiReviewImages(9).isEmpty()); assertTrue(db.dao().aiReviewImages(0).isEmpty())
            assertTrue(db.dao().aiReviewImageChunks(9, aiDraftImageId(draft)).isEmpty()); assertNull(store.draft(9, 0))
        } finally { db.close() }
    }
}
