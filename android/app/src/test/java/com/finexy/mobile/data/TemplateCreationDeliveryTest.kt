package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TemplateCreationDeliveryTest {
    private fun write(ledger: Long = 9, kind: Int = 1): TemplateCreationEntity {
        val schedule = if (kind == 2) ",\"scheduledFrequencyType\":1,\"scheduledFrequency\":\"1\",\"scheduledStartDate\":\"2026-10-05\",\"scheduledEndDate\":null,\"utcOffset\":480" else ""
        return TemplateCreationEntity(ledger, kind, "stable-$ledger-$kind", "draft-$ledger-$kind", 1,
            "{\"ledgerId\":\"$ledger\",\"templateType\":$kind,\"name\":\"test\",\"type\":3,\"categoryId\":\"20\",\"sourceAccountId\":\"10\",\"destinationAccountId\":\"0\",\"sourceAmount\":-1234,\"destinationAmount\":0,\"hideAmount\":false,\"tagIds\":[],\"comment\":\"\"$schedule}", createdAt = 1)
    }
    private class Store(var rows: List<TemplateCreationEntity>) : TemplateCreationStore {
        override suspend fun templateCreations() = rows
        override suspend fun markTemplateCreationPosted(write: TemplateCreationEntity, receipt: TemplateCreationReceipt): Boolean {
            val found = rows.any { it.clientSessionId == write.clientSessionId && it.payloadJson == write.payloadJson }
            rows = rows.map { if (it.clientSessionId == write.clientSessionId && it.payloadJson == write.payloadJson)
                it.copy(state = TemplateCreationEntity.POSTED, serverId = receipt.id, serverDeleted = receipt.deleted, lastError = "") else it }
            return found
        }
        override suspend fun markTemplateCreationFailed(write: TemplateCreationEntity, message: String) {
            rows = rows.map { if (it.clientSessionId == write.clientSessionId) it.copy(lastError = message) else it }
        }
    }
    @Test fun lostReplyRetriesIdenticalBytesAndStableKeyAcrossSenderRecreation() = runBlocking {
        val request = write()
        val store = Store(listOf(request))
        val receipts = mutableMapOf<String, Long>()
        val sent = mutableListOf<TemplateCreationEntity>()
        val upload: suspend (TemplateCreationEntity) -> TemplateCreationReceipt = {
            sent += it
            val id = receipts.getOrPut(it.clientSessionId) { 9007199254740993L }
            if (sent.size == 1) throw IOException("reply lost")
            TemplateCreationReceipt(it.ledgerId, it.templateType, id)
        }
        assertEquals(1, TemplateCreationDelivery(store, upload).push().errors.size)
        assertEquals(1, TemplateCreationDelivery(store, upload).push().posted)
        assertEquals(1, receipts.size)
        assertTrue(sent.all { it.clientSessionId == request.clientSessionId && it.payloadJson == request.payloadJson })
        assertEquals(9007199254740993L, store.rows.single().serverId)
        assertEquals(0, TemplateCreationDelivery(store, upload).push().posted)
        assertEquals(2, sent.size)
    }
    @Test fun deniedLedgerDoesNotPreventOtherLedgerOrScheduleAndTombstoneAcknowledgesCreation() = runBlocking {
        val store = Store(listOf(write(), write(10), write(9, 2)))
        val result = TemplateCreationDelivery(store) {
            if (it.ledgerId == 9L && it.templateType == 1) throw IOException("revoked")
            TemplateCreationReceipt(it.ledgerId, it.templateType, 42, deleted = true)
        }.push()
        assertEquals(2, result.posted); assertEquals(1, result.errors.size)
        assertEquals(TemplateCreationEntity.PENDING, store.rows[0].state)
        assertTrue(store.rows.drop(1).all { it.serverDeleted })
    }
    @Test fun malformedOrWrongScopeReceiptLeavesRequestPending() = runBlocking {
        for (receipt in listOf(TemplateCreationReceipt(9, 1, 0), TemplateCreationReceipt(10, 1, 42), TemplateCreationReceipt(9, 2, 42))) {
            val store = Store(listOf(write()))
            assertEquals(1, TemplateCreationDelivery(store) { receipt }.push().errors.size)
            assertEquals(TemplateCreationEntity.PENDING, store.rows.single().state)
        }
    }
    @Test fun malformedSnapshotIsNotUploadedAndUnconfirmedInputIsNotARequest() = runBlocking {
        val store = Store(listOf(write().copy(payloadJson = "{\"ledgerId\":\"10\"}")))
        var uploads = 0
        assertEquals(1, TemplateCreationDelivery(store) { uploads++; TemplateCreationReceipt(9, 1, 42) }.push().errors.size)
        assertEquals(0, uploads)
        validateTemplateDraft(TemplateFormDraftEntity(0, 1, "partial", 1, 0, "{\"name\":\"\",\"amount\":\"1.\",\"comment\":\"未确认\"}", 1))
        assertEquals(0, TemplateCreationDelivery(Store(emptyList())) { uploads++; TemplateCreationReceipt(0, 1, 42) }.push().posted)
        assertEquals(0, uploads)
    }
    @Test fun cancellationPreservesOriginalPendingState() = runBlocking {
        val original = write(0)
        val store = Store(listOf(original))
        try { TemplateCreationDelivery(store) { throw CancellationException("exit") }.push(); fail("cancel") }
        catch (_: CancellationException) { }
        assertEquals(listOf(original), store.rows)
    }
}
