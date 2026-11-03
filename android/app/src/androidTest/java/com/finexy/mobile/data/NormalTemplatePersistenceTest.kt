package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NormalTemplatePersistenceTest {
    @Test fun refundDraftPersistsSignedAmountHideMetadataAndStableLocalId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            db.dao().upsertAccounts(listOf(AccountEntity(10, "Fixture", "USD")))
            db.dao().upsertCategories(listOf(CategoryEntity(20, "Root", type = 2), CategoryEntity(21, "Refund", 20, 2)))
            val repository = TransactionRepository(context, db, false)
            val draft = TransactionDraft("stable-refund", 3, 10, 21, "Refund", -1234, "fixture", "[]", hideAmount = true)
            repository.save(draft)
            val row = requireNotNull(db.dao().findTransaction("stable-refund"))
            assertEquals(-1234L, row.sourceAmountMinor); assertTrue(row.hideAmount); assertEquals("USD", row.currency)
            assertEquals(-1234L, row.toApiPayload().getLong("sourceAmount")); assertTrue(row.toApiPayload().getBoolean("hideAmount"))
            repository.save(draft.copy(comment = "edited", hideAmount = null))
            assertEquals(1, db.dao().allTransactions().size); assertTrue(db.dao().findTransaction("stable-refund")!!.hideAmount)
        } finally { db.close() }
    }
    @Test fun normalTemplateFlowIncludesHiddenRowsOnlyInTheCapturedLedgerAndKeepsOrdering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            val row = TemplateEntity(1, "fixture", 3, 21, 10, 123, "", ledgerId = 9)
            db.dao().upsertTemplates(listOf(row.copy(hidden = true, displayOrder = 2), row.copy(id = 2, displayOrder = 1), row.copy(ledgerId = 10), row.copy(ledgerId = 0)))
            val repository = TransactionRepository(context, db, false)
            val actual = repository.observeNormalTemplates(9).first()
            assertEquals(listOf(2L, 1L), actual.map { it.id }); assertEquals(listOf(9L, 9L), actual.map { it.ledgerId })
            assertEquals(listOf(2L), repository.observeTemplates(9).first().map { it.id })
        } finally { db.close() }
    }
}
