package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test

class LegacyAIPendingProjectionTest {
    private fun row() = TransactionEntity("original", type = 3, categoryName = "Synthetic", sourceAmountMinor = 123,
        comment = "Synthetic private raw input", time = 1790000000000, updatedAt = 10, reviewItemId = 71)

    @Test fun syncedDeletedTombstonesRemainVisibleAndRetainExactLegacyIdentity() {
        for (serverId in listOf(null, 9007199254740993L)) for (deleted in listOf(false, true)) {
            val original = row().copy(serverId = serverId, deleted = deleted, syncState = SyncState.SYNCED)
            val record = requireNotNull(pendingTransactionRecord(original))
            val target = record.target as LedgerPendingTarget.Transaction
            assertEquals(LedgerPendingKind.LEGACY_AI, record.kind)
            assertEquals(0L, target.ledgerId); assertEquals(71L, target.relatedAIItemId)
            assertEquals(original.localId, target.localId)
            assertFalse(record.toString().contains(original.comment))
            assertNotEquals(target, pendingTransactionRecord(original.copy(deleted = !deleted))!!.target)
        }
    }

    @Test fun cleanUnlinkedSyncedHistoryDoesNotBecomePending() {
        for (deleted in listOf(false, true)) {
            assertNull(pendingTransactionRecord(row().copy(reviewItemId = null, syncState = SyncState.SYNCED, deleted = deleted)))
        }
    }
}
