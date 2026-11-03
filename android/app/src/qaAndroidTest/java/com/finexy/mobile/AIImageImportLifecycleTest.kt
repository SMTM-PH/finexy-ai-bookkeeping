package com.finexy.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.finexy.mobile.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

/** The same ViewModelProvider and import job used by the production source page. */
class AIImageImportLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<LedgerSwitchQaActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 40, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    private fun session(db: FinexyDatabase, ledger: Long = 9) = AIReviewSourceSession(AIReviewPersistence(db), ledger, { true },
        { error("Import must not call a model") }, { error("Import must not call a model") }, { bytes, _ -> bytes }, { error("Import must not enqueue") })
    private fun database(): FinexyDatabase {
        check(context.packageName == "com.finexy.mobile.qa")
        return Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
    }
    private fun viewModel(key: String): AIReviewSourceViewModel {
        lateinit var result: AIReviewSourceViewModel
        rule.activityRule.scenario.onActivity { result = ViewModelProvider(it)[key, AIReviewSourceViewModel::class.java] }
        return result
    }
    private fun bind(model: AIReviewSourceViewModel, session: AIReviewSourceSession) {
        rule.runOnIdle { assertSame(session, model.bind { session }); model.load(session) }
        rule.waitUntil(10_000) { session.state.value.ready }
    }

    @Test fun activityRecreationRetainsAnInFlightImportUntilEncryptedCommit() = runBlocking<Unit> {
        val db = database(); val source = session(db); val key = "native-import-${UUID.randomUUID()}"; val model = viewModel(key)
        try {
            bind(model, source)
            val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>(); val bytes = image(0xff1155aa.toInt())
            val request = AIImageImportRequest("content://synthetic-fixture/owned-image")
            val job = withContext(Dispatchers.Main) { model.importImage(source, request) { started.complete(Unit); resume.await(); source.selectImage(bytes) } }
            started.await(); assertTrue(model.imageImport.value.running)
            rule.activityRule.scenario.recreate()
            assertSame(model, viewModel(key)); assertTrue(model.imageImport.value.running)
            resume.complete(Unit); job.join()
            assertTrue(model.imageImport.value.completed); assertFalse(model.imageImport.value.running)
            val saved = requireNotNull(AIReviewPersistence(db).draft(9, 0)); assertArrayEquals(bytes, AIReviewPersistence(db).sourceImage(saved))
            assertTrue(AIReviewPersistence(db).requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
            withContext(Dispatchers.Main) { model.consumeImageImport() }; assertEquals(AIImageImportState(), model.imageImport.value)
        } finally { withContext(Dispatchers.Main) { model.release(source) }; db.close() }
    }

    @Test fun failedReadRetainsTheExactRetryAndPreviousOriginalUntilExplicitRetry() = runBlocking<Unit> {
        val db = database(); val source = session(db); val model = viewModel("native-import-${UUID.randomUUID()}")
        try {
            bind(model, source); val before = image(0xff335577.toInt()); source.selectImage(before)
            val originalDraft = requireNotNull(AIReviewPersistence(db).draft(9, 0))
            val request = AIImageImportRequest("content://synthetic-fixture/owned-image")
            withContext(Dispatchers.Main) { model.importImage(source, request) { throw SecurityException("Synthetic revoked image grant") } }.join()
            assertFalse(model.imageImport.value.running); assertFalse(model.imageImport.value.completed)
            assertEquals(request, model.imageImport.value.request); assertTrue(source.state.value.failed)
            assertEquals(originalDraft, AIReviewPersistence(db).draft(9, 0)); assertArrayEquals(before, AIReviewPersistence(db).sourceImage(originalDraft))
            val replacement = image(0xff887744.toInt())
            withContext(Dispatchers.Main) { model.importImage(source, request) { source.selectImage(replacement) } }.join()
            assertTrue(model.imageImport.value.completed)
            assertArrayEquals(replacement, AIReviewPersistence(db).sourceImage(requireNotNull(AIReviewPersistence(db).draft(9, 0))))
            assertTrue(AIReviewPersistence(db).requests().isEmpty()); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { withContext(Dispatchers.Main) { model.release(source) }; db.close() }
    }

    @Test fun leavingTheSourceCancelsImportWithoutLeakingItsResultIntoTheNextSession() = runBlocking<Unit> {
        val db = database(); val source = session(db); val model = viewModel("native-import-${UUID.randomUUID()}")
        val next = session(db, 10)
        try {
            bind(model, source); val before = image(0xff556611.toInt()); source.selectImage(before)
            val saved = requireNotNull(AIReviewPersistence(db).draft(9, 0)); val started = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            val job = withContext(Dispatchers.Main) { model.importImage(source, AIImageImportRequest("content://synthetic-fixture/owned-image")) {
                started.complete(Unit); resume.await(); source.selectImage(image(0xffaa9977.toInt()))
            } }
            started.await(); withContext(Dispatchers.Main) { model.release(source) }; bind(model, next)
            resume.complete(Unit); job.join(); assertTrue(job.isCancelled)
            assertEquals(AIImageImportState(), model.imageImport.value)
            assertEquals(saved, AIReviewPersistence(db).draft(9, 0)); assertArrayEquals(before, AIReviewPersistence(db).sourceImage(saved))
            assertNull(AIReviewPersistence(db).draft(10, 0)); assertTrue(db.dao().allTransactions().isEmpty())
        } finally { withContext(Dispatchers.Main) { model.release(next); model.release(source) }; db.close() }
    }
}
