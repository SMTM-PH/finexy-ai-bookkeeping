package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PeriodicActionDeliveryTest {
    private class Store(var rows: List<PeriodicAction>) : PeriodicActionStore {
        val receipts = mutableMapOf<PeriodicAction, Long>()
        override suspend fun pendingPeriodicActions() = rows.toList()
        override suspend fun acknowledgePeriodicAction(action: PeriodicAction, transactionId: Long): Boolean {
            if (action !in rows) return false
            receipts[action] = transactionId; rows = rows - action; return true
        }
    }
    private fun action(ledger: Long = 9, kind: Int = 1) = PeriodicAction(ledger, 9007199254740993L, 1788700000, kind, 100)

    @Test fun lostResponseAndRecreatedSenderRetryOriginalTupleExactlyOnce() = runBlocking {
        val request = action(); val store = Store(listOf(request)); val server = mutableMapOf<Triple<Long, Long, Long>, Long>()
        val sent = mutableListOf<PeriodicAction>(); var lost = true
        val send: suspend (PeriodicAction) -> Long = { row ->
            sent += row
            val id = server.getOrPut(Triple(row.ledgerId, row.templateId, row.scheduledUnixTime)) { 42 }
            if (lost) { lost = false; throw IOException("lost response") }; id
        }
        assertEquals(1, PeriodicActionDelivery(store, send).push().errors.size)
        assertEquals(listOf(request), store.rows)
        assertEquals(1, PeriodicActionDelivery(store, send).push().acknowledged)
        assertEquals(listOf(request, request), sent); assertEquals(1, server.size)
        PeriodicActionDelivery(store, send).push(); assertEquals(2, sent.size)
    }

    @Test fun denialKeepsOriginalIntentWhileOtherLedgersProgress() = runBlocking {
        val store = Store(listOf(action(9), action(10, 2)))
        val result = PeriodicActionDelivery(store) { if (it.ledgerId == 9L) throw IOException("denied") else 0 }.push()
        assertEquals(1, result.acknowledged); assertEquals(1, result.errors.size); assertEquals(listOf(action(9)), store.rows)
    }

    @Test fun invalidReceiptKeepsRequestAndCancellationIsNotSwallowed() = runBlocking {
        val store = Store(listOf(action()))
        assertEquals(1, PeriodicActionDelivery(store) { 0 }.push().errors.size)
        try { PeriodicActionDelivery(store) { throw CancellationException() }.push(); fail("cancellation expected") }
        catch (_: CancellationException) { }
        assertEquals(listOf(action()), store.rows); assertTrue(store.receipts.isEmpty())
    }

    @Test fun lateReceiptCannotAcknowledgeNewerIntent() = runBlocking {
        val original = action(); val store = Store(listOf(original))
        val result = PeriodicActionDelivery(store) { store.rows = listOf(original.copy(queuedAt = 101)); 42 }.push()
        assertEquals(0, result.acknowledged); assertTrue(store.receipts.isEmpty()); assertEquals(101L, store.rows.single().queuedAt)
    }
}
