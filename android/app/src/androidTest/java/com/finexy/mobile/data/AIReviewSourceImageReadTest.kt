package com.finexy.mobile.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Expected-image reads against isolated real Room; no API, Worker or main application data. */
@RunWith(AndroidJUnit4::class)
class AIReviewSourceImageReadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database() = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 40, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray() }
    }
    private fun session(db: FinexyDatabase, current: () -> Boolean = { true }) = AIReviewSourceSession(AIReviewPersistence(db), 9, { true },
        { error("Image read must not call a model") }, { error("Image read must not call a model") },
        { bytes, _ -> bytes }, { error("Image read must not deliver a request") }, current)

    @Test fun ownTextAndCropRevisionKeepsTheSameImmutableOriginalReference() = runBlocking<Unit> {
        val db = database()
        try {
            val source = session(db); source.load(); val bytes = image(0xff224466.toInt()); source.selectImage(bytes)
            val reference = requireNotNull(source.imageReference()); val previous = requireNotNull(source.state.value.saved)
            source.edit(mapOf("sourceText" to "Synthetic edited input", "cropLeft" to "0.10")); source.persist()
            assertTrue(requireNotNull(source.state.value.saved).revision > previous.revision)
            assertEquals(reference, source.imageReference()); assertArrayEquals(bytes, source.original(reference))
            assertTrue(AIReviewPersistence(db).requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun replacementImageRejectsThePreviousReferenceWithoutReturningItsBytes() = runBlocking<Unit> {
        val db = database()
        try {
            val source = session(db); source.load(); source.selectImage(image(0xff442266.toInt()))
            val previous = requireNotNull(source.imageReference()); val replacement = image(0xff884422.toInt())
            source.selectImage(replacement)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { source.original(previous) } }
            val current = requireNotNull(source.imageReference()); assertNotEquals(previous.imageId, current.imageId)
            assertEquals(previous.draftId, current.draftId); assertArrayEquals(replacement, source.original(current))
            assertTrue(AIReviewPersistence(db).requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun foreignPartitionDraftOrExpiredScopeCannotReadTheSelectedOriginal() = runBlocking<Unit> {
        val db = database(); var active = true
        try {
            val source = session(db) { active }; source.load(); val bytes = image(0xff116633.toInt()); source.selectImage(bytes)
            val reference = requireNotNull(source.imageReference()); val saved = requireNotNull(source.state.value.saved)
            for (foreign in listOf(reference.copy(storagePartition = "foreign-owner"), reference.copy(ledgerId = 10), reference.copy(draftId = "foreign-draft"))) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { source.original(foreign) } }
            }
            active = false
            assertThrows(IllegalStateException::class.java) { runBlocking { source.original(reference) } }
            assertFalse(source.state.value.ready)
            assertArrayEquals(bytes, AIReviewPersistence(db).sourceImage(saved))
            assertTrue(AIReviewPersistence(db).requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }
}
