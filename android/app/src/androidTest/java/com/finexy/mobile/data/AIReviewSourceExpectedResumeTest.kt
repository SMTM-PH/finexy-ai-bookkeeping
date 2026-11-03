package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated in-memory Room only. This class never touches main-ledger data or a server. */
@RunWith(AndroidJUnit4::class)
class AIReviewSourceExpectedResumeTest {
    private fun session(persistence: AIReviewPersistence) = AIReviewSourceSession(persistence, 9, { true },
        { error("Resume must never call a model") }, { error("Resume must never call a model") },
        { _, _ -> error("Resume must never prepare an image") }, { error("Resume must never deliver a request") })

    @Test fun replacedDraftAfterLoadCannotBeResumedThroughAnOldExpectedEntity() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FinexyDatabase::class.java).build()
        try {
            val persistence = AIReviewPersistence(db)
            val original = persistence.saveDraft(9, 0, AISourceForm().changed(mapOf("sourceText" to "Synthetic original")).json())
            val source = session(persistence); source.load()
            val replacement = persistence.saveDraft(9, 0, AISourceForm().changed(mapOf("sourceText" to "Synthetic replacement")).json(), original)
            source.resume(original)
            assertTrue(source.state.value.restoring)
            assertTrue(source.state.value.failed)
            assertEquals("", source.state.value.form.text)
            assertEquals(original, source.state.value.saved)
            assertEquals(replacement, persistence.draft(9, 0))
            assertTrue(persistence.requests().isEmpty())
            assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun matchingExpectedEntityRestoresOnlyTheOriginalRawInput() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FinexyDatabase::class.java).build()
        try {
            val persistence = AIReviewPersistence(db)
            val original = persistence.saveDraft(9, 0, AISourceForm().changed(mapOf("sourceText" to "Synthetic raw 1.+")).json())
            val source = session(persistence); source.load(); source.resume(original)
            assertFalse(source.state.value.restoring)
            assertFalse(source.state.value.failed)
            assertEquals("Synthetic raw 1.+", source.state.value.form.text)
            assertEquals(original, persistence.draft(9, 0))
            assertTrue(persistence.requests().isEmpty())
            assertTrue(db.dao().allTransactions().isEmpty())
        } finally { db.close() }
    }

    @Test fun nullExpectedRetainsTheExistingExplicitLatestResumeBehavior() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FinexyDatabase::class.java).build()
        try {
            val persistence = AIReviewPersistence(db)
            val original = persistence.saveDraft(9, 0, AISourceForm().changed(mapOf("sourceText" to "Synthetic original")).json())
            val source = session(persistence); source.load()
            val replacement = persistence.saveDraft(9, 0, AISourceForm().changed(mapOf("sourceText" to "Synthetic latest")).json(), original)
            source.resume()
            assertFalse(source.state.value.restoring)
            assertEquals("Synthetic latest", source.state.value.form.text)
            assertEquals(replacement, source.state.value.saved)
        } finally { db.close() }
    }

    @Test fun discardWithOldExpectedEntityPreservesTheReplacement() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, FinexyDatabase::class.java).build()
        try {
            val persistence = AIReviewPersistence(db)
            val original = persistence.saveDraft(9, 0, AISourceForm().changed(mapOf("sourceText" to "Synthetic original")).json())
            val source = session(persistence); source.load()
            val replacement = persistence.saveDraft(9, 0, AISourceForm().changed(mapOf("sourceText" to "Synthetic replacement")).json(), original)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { source.discard(original) } }
            assertEquals(replacement, persistence.draft(9, 0))
            assertTrue(persistence.requests().isEmpty())
        } finally { db.close() }
    }
}
