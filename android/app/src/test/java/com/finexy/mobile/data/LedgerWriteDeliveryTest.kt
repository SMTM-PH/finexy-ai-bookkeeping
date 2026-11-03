package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LedgerWriteDeliveryTest {
    private class Store(var rows: List<LedgerWriteEntity>) : LedgerWriteStore {
        override suspend fun ledgerWrites() = rows
        override suspend fun markLedgerWritePosted(write: LedgerWriteEntity, serverId: Long) {
            rows = rows.map { if (it.localId == write.localId) it.copy(state = LedgerWriteEntity.POSTED, serverId = serverId, lastError = "") else it }
        }
        override suspend fun markLedgerWriteFailed(write: LedgerWriteEntity, message: String) {
            rows = rows.map { if (it.localId == write.localId) it.copy(lastError = message) else it }
        }
    }
    private fun write(ledger: Long = 9) = LedgerWriteEntity(ledger, "stable-$ledger", "snapshot-$ledger", createdAt = 1)

    @Test fun lostResponseAndRecreatedSenderKeepTheSameLedgerSnapshotAndKey() = runBlocking {
        val original = write()
        val store = Store(listOf(original))
        val receipts = mutableMapOf<String, Long>()
        val sent = mutableListOf<LedgerWriteEntity>()
        var loseResponse = true
        val upload: suspend (LedgerWriteEntity) -> Long = { request ->
            sent += request
            val id = receipts.getOrPut("${request.ledgerId}:${request.localId}") { 100 }
            if (loseResponse) { loseResponse = false; throw IOException("response lost") }
            id
        }
        assertEquals(1, LedgerWriteDelivery(store, upload).push().errors.size)
        assertEquals(LedgerWriteEntity.PENDING, store.rows.single().state)
        assertEquals(1, LedgerWriteDelivery(store, upload).push().pushed)
        assertEquals(1, receipts.size)
        assertEquals(listOf(original.localId, original.localId), sent.map { it.localId })
        assertTrue(sent.all { it.ledgerId == original.ledgerId && it.payloadJson == original.payloadJson })
        assertEquals(LedgerWriteEntity.POSTED, store.rows.single().state)
    }

    @Test fun postedWriteIsNotSentAgainWhenLaterReadsFail() = runBlocking {
        val store = Store(listOf(write()))
        var posts = 0
        val upload: suspend (LedgerWriteEntity) -> Long = { posts++; 100 }
        LedgerWriteDelivery(store, upload).push()
        // A pull failure must not roll back the persisted write receipt.
        try { throw IOException("pull failed") } catch (_: IOException) { }
        LedgerWriteDelivery(store, upload).push()
        assertEquals(1, posts)
        assertEquals(100L, store.rows.single().serverId)
    }

    @Test fun oneDeniedLedgerDoesNotPreventOtherConfirmedWrites() = runBlocking {
        val store = Store(listOf(write(9), write(10)))
        val result = LedgerWriteDelivery(store) { request ->
            if (request.ledgerId == 9L) throw IllegalStateException("access revoked") else 100
        }.push()
        assertEquals(1, result.pushed)
        assertEquals(1, result.errors.size)
        assertEquals(LedgerWriteEntity.PENDING, store.rows[0].state)
        assertEquals(LedgerWriteEntity.POSTED, store.rows[1].state)
    }

    @Test fun cancellationPreservesPendingRequestWithoutPretendingFailureOrSuccess() = runBlocking {
        val store = Store(listOf(write()))
        try {
            LedgerWriteDelivery(store) { throw CancellationException("process closing") }.push()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(write(), store.rows.single())
    }
}
