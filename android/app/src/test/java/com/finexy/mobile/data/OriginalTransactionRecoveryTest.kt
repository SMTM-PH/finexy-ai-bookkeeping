package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OriginalTransactionRecoveryTest {
    private fun pending(local: String = "original-key", created: Long = 10) = LedgerWriteEntity(9, local,
        """{"ledgerId":"9","type":3,"categoryId":"20","time":1790000000,"utcOffset":480,"sourceAccountId":"10","destinationAccountId":"0","sourceAmount":123,"destinationAmount":0,"tagIds":[],"pictureIds":[],"hideAmount":false,"geoLocation":null,"comment":"Synthetic"}""", createdAt = created)
    private fun remote(id: Long = 77, ledger: Long = 9) = RemoteTransaction(id, null, 3, 20, "Synthetic category",
        10, null, 123, 0, "CNY", "Synthetic", 1790000000, 480, "[]", ledgerId = ledger)
    private fun command(row: LedgerWriteEntity, receipt: Long? = null) = OriginalTransactionCommand("synthetic-namespace",
        "https://example.invalid", "synthetic-identity", "synthetic.db", pendingLedgerWriteRecord(row).target as LedgerPendingTarget.Transaction, receipt)
    private fun session(row: LedgerWriteEntity, revision: Long = 7) = LedgerPendingContext("synthetic-namespace",
        "https://example.invalid", "synthetic-identity", "synthetic.db", row.ledgerId, revision)
    private class Store(var row: LedgerWriteEntity?) : OriginalLedgerWriteStore {
        var failedCalls = 0; var ackCalls = 0
        val cached = mutableMapOf<Long, RemoteTransaction>()
        override suspend fun read(ledgerId: Long) = row?.takeIf { it.ledgerId == ledgerId }
        override suspend fun posted(expected: LedgerWriteEntity, serverId: Long): LedgerWriteEntity? {
            if (row != expected) return null
            return expected.copy(state = LedgerWriteEntity.POSTED, serverId = serverId, lastError = "").also { row = it }
        }
        override suspend fun failed(expected: LedgerWriteEntity) {
            failedCalls++
            if (row == expected && expected.state == LedgerWriteEntity.PENDING) row = expected.copy(lastError = "Fixed recovery message")
        }
        override suspend fun acknowledge(expected: LedgerWriteEntity, remote: RemoteTransaction, current: () -> Boolean): Boolean {
            if (!current() || row != expected) return false
            ackCalls++; cached[remote.id] = remote; row = null
            return true
        }
    }

    @Test fun postedViewerOnlyReadsThePinnedOriginalReceipt() = runBlocking {
        val row = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77)
        val store = Store(row); var uploads = 0; var reads = 0
        val engine = OriginalLedgerWriteRecovery(store, { true }, { 4 }, { uploads++; error("Must not POST") }, { id, ledger ->
            reads++; assertEquals(77L, id); assertEquals(9L, ledger); remote()
        })
        assertEquals(OriginalTransactionRecoveryResult.ACKNOWLEDGED, engine.recover(command(row, 77)))
        assertEquals(0, uploads); assertEquals(1, reads); assertNull(store.row); assertEquals(1, store.ackCalls)
    }

    @Test fun pendingViewerCannotUploadOrReadAnInventedReceipt() = runBlocking {
        val row = pending(); val store = Store(row); var uploads = 0; var reads = 0
        try {
            OriginalLedgerWriteRecovery(store, { true }, { 4 }, { uploads++; remote() }, { _, _ -> reads++; remote() }).recover(command(row))
            fail("Read-only member must not submit")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, uploads); assertEquals(0, reads)
        assertEquals(row.localId, store.row?.localId); assertEquals(row.payloadJson, store.row?.payloadJson)
    }

    @Test fun successfulPostPersistsReceiptBeforeFailedGetAndRetryDoesNotPost() = runBlocking {
        val row = pending(); val store = Store(row); var uploads = 0; var reads = 0
        val first = OriginalLedgerWriteRecovery(store, { true }, { 1 }, { uploads++; remote() }, { _, _ -> reads++; throw IOException("Synthetic unavailable") })
        try { first.recover(command(row)); fail("GET must fail") } catch (_: IOException) { }
        assertEquals(LedgerWriteEntity.POSTED, store.row?.state); assertEquals(77L, store.row?.serverId)
        val retry = OriginalLedgerWriteRecovery(store, { true }, { 4 }, { uploads++; error("Known receipt must not POST") }, { _, _ -> reads++; remote() })
        assertEquals(OriginalTransactionRecoveryResult.ACKNOWLEDGED, retry.recover(command(row)))
        assertEquals(1, uploads); assertEquals(2, reads)
    }

    @Test fun replacementDuringRoleAwaitCannotReuseTheOriginalPermission() = runBlocking {
        val row = pending(); val replacement = pending("replacement-key"); val store = Store(row); var uploads = 0
        val engine = OriginalLedgerWriteRecovery(store, { true }, { yield(); store.row = replacement; 1 }, { uploads++; remote() }, { _, _ -> remote() })
        assertEquals(OriginalTransactionRecoveryResult.CHANGED, engine.recover(command(row)))
        assertEquals(0, uploads); assertEquals(replacement, store.row); assertEquals(0, store.ackCalls)
    }

    @Test fun sessionChangeAfterPostKeepsReceiptAndDoesNotReadUsingTheNewAccount() = runBlocking {
        val row = pending(); val store = Store(row); var current = true; var reads = 0
        val engine = OriginalLedgerWriteRecovery(store, { current }, { 1 }, { current = false; remote() }, { _, _ -> reads++; remote() })
        assertEquals(OriginalTransactionRecoveryResult.PAUSED, engine.recover(command(row)))
        assertEquals(LedgerWriteEntity.POSTED, store.row?.state); assertEquals(77L, store.row?.serverId); assertEquals(0, reads)
    }

    @Test fun replacementDuringGetCannotBeAcknowledgedOrCached() = runBlocking {
        val row = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77)
        val replacement = pending("replacement-key"); val store = Store(row)
        val engine = OriginalLedgerWriteRecovery(store, { true }, { 4 }, { error("Must not POST") }, { _, _ -> yield(); store.row = replacement; remote() })
        assertEquals(OriginalTransactionRecoveryResult.CHANGED, engine.recover(command(row, 77)))
        assertEquals(replacement, store.row); assertEquals(0, store.ackCalls); assertTrue(store.cached.isEmpty())
    }

    @Test fun postedReceiptSubstitutionOrRecreatedSameKeyIsNotTheOriginalTarget() = runBlocking {
        val original = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77)
        var reads = 0
        for (replacement in listOf(original.copy(serverId = 88), pending(created = 11))) {
            val store = Store(replacement)
            val engine = OriginalLedgerWriteRecovery(store, { true }, { 4 }, { error("Must not POST") }, { _, _ -> reads++; remote() })
            assertEquals(OriginalTransactionRecoveryResult.CHANGED, engine.recover(command(original, 77)))
            assertEquals(replacement, store.row)
        }
        assertEquals(0, reads)
    }

    @Test fun cancellationDoesNotConvertTheOriginalReceiptIntoAResendableRequest() = runBlocking {
        val original = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77); val store = Store(original)
        try {
            OriginalLedgerWriteRecovery(store, { true }, { 4 }, { error("Must not POST") }, { _, _ -> throw CancellationException("Synthetic cancellation") })
                .recover(command(original, 77))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(original, store.row); assertEquals(0, store.failedCalls); assertEquals(0, store.ackCalls)
    }

    @Test fun localProjectionRejectsReplacedRowsAndLegacyDeletionNeverBecomesAnEditor() {
        val row = TransactionEntity("local-original", type = 3, categoryName = "Synthetic", sourceAmountMinor = 123, comment = "Synthetic", time = 1000, updatedAt = 10)
        val target = pendingTransactionRecord(row)!!.target as LedgerPendingTarget.Transaction
        assertTrue(matchesOriginalTransaction(target, row)); assertFalse(matchesOriginalTransaction(target, row.copy(comment = "Synthetic replacement")))
        assertFalse(matchesOriginalTransaction(target, null))
        for (blocked in listOf(row.copy(deleted = true), row.copy(syncState = SyncState.CONFLICT), row.copy(reviewItemId = 71), row.copy(reviewItemId = 71, deleted = true))) {
            try { requireOrdinaryOriginalEditor(blocked); fail("Must retain the original recovery boundary") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun balanceTargetCannotBecomeAnOrdinaryDeltaEditorOrUnverifiedReplay() = runBlocking {
        val balance = TransactionEntity("balance-original", type = 1, sourceAccountId = 10,
            categoryName = "余额调整", sourceAmountMinor = 0, comment = "Synthetic target zero", time = 1000)
        try { requireOrdinaryOriginalEditor(balance); fail("Target balance is not a signed delta") }
        catch (_: IllegalArgumentException) { }
        val original = pending().copy(payloadJson = pending().payloadJson.replace("\"type\":3", "\"type\":1"))
        val store = Store(original); var posts = 0
        try {
            OriginalLedgerWriteRecovery(store, { true }, { 1 }, { posts++; remote() }, { _, _ -> remote() })
                .recover(command(original))
            fail("An unverified legacy balance target must remain quarantined")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, posts); assertEquals(original.payloadJson, store.row?.payloadJson)
        assertEquals(original.localId, store.row?.localId); assertEquals(LedgerWriteEntity.PENDING, store.row?.state)
    }

    @Test fun revocationAfterGetCannotAcknowledgeOrDiscardTheKnownReceipt() = runBlocking {
        val original = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77)
        val store = Store(original); var allowed = true
        try {
            OriginalLedgerWriteRecovery(store, { true }, { if (allowed) 4 else 0 }, { error("Must not POST") }, { _, _ ->
                allowed = false; remote()
            }).recover(command(original, 77))
            fail("Revoked role must keep the original receipt")
        } catch (_: IllegalArgumentException) { }
        assertEquals(original, store.row); assertEquals(0, store.ackCalls); assertTrue(store.cached.isEmpty())
    }

    @Test fun refundAndLegacyIncomingWriteProjectionKeepTheFinancialDirection() {
        val refund = TransactionEntity("refund-original", type = 3, sourceAccountId = 10,
            categoryName = "Synthetic expense", sourceAmountMinor = -456, comment = "Synthetic refund", time = 1000)
        assertEquals(-456L, refund.toApiPayload().getLong("sourceAmount"))
        val incoming = refund.copy(type = 4, sourceAccountId = 0, sourceAmountMinor = 789,
            destinationAccountId = 11, destinationAmountMinor = 789)
        val projection = incoming.toApiPayload()
        assertEquals("0", projection.getString("sourceAccountId")); assertEquals(0L, projection.getLong("sourceAmount"))
        assertEquals("11", projection.getString("destinationAccountId")); assertEquals(789L, projection.getLong("destinationAmount"))
        assertEquals(789L, incoming.sourceAmountMinor) // Read model is retained, not destructively normalized.
    }

    @Test fun nonLocatorPendingCallbackCannotRebindAfterAToBToA() = runBlocking {
        val original = pending(); val expected = session(original); var actual = expected
        val guard = LedgerPendingPageGuard(LedgerPendingPageLocation(expected, command(original).target), { actual }, { _, _ -> true })
        assertTrue(guard.verify())
        actual = expected.copy(identity = "synthetic-other-identity", sessionRevision = 8)
        actual = expected.copy(sessionRevision = 9)
        val store = Store(original); var posts = 0; var gets = 0
        val result = OriginalLedgerWriteRecovery(store, guard::isCurrent, { 1 }, { posts++; remote() }, { _, _ -> gets++; remote() })
            .recover(command(original))
        assertEquals(OriginalTransactionRecoveryResult.PAUSED, result)
        assertEquals(0, posts); assertEquals(0, gets); assertEquals(original, store.row)
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
    }

    @Test fun disposedPendingPageCannotAuthorizeADelayedOriginalCallback() = runBlocking {
        val original = pending(); val expected = session(original)
        val guard = LedgerPendingPageGuard(LedgerPendingPageLocation(expected, command(original).target), { expected }, { _, _ -> true })
        assertTrue(guard.verify()); guard.close()
        val store = Store(original); var posts = 0
        assertEquals(OriginalTransactionRecoveryResult.PAUSED,
            OriginalLedgerWriteRecovery(store, guard::isCurrent, { 1 }, { posts++; remote() }, { _, _ -> remote() })
                .recover(command(original)))
        assertEquals(0, posts); assertEquals(original, store.row); assertEquals(0, store.failedCalls)
    }

    @Test fun uiRevisionIsPinnedWhileADurableWorkerMayResumeTheSameOriginalPartition() {
        val original = pending(); val command = command(original); val shown = session(original)
        val reauthenticated = shown.copy(sessionRevision = shown.sessionRevision + 2)
        assertEquals(shown, originalRecoveryContext(command, shown, shown))
        assertNull(originalRecoveryContext(command, reauthenticated, shown))
        assertEquals(reauthenticated, originalRecoveryContext(command, reauthenticated))
        assertNull(originalRecoveryContext(command, reauthenticated.copy(identity = "synthetic-other-identity")))
        assertNull(originalRecoveryContext(command, reauthenticated.copy(storagePartition = "other-synthetic.db")))
        assertNull(originalRecoveryContext(command, null, shown))
    }

    @Test fun roleAwaitCannotRebindANonLocatorCommandToANewerSameAccountSession() = runBlocking {
        val original = pending(); val command = command(original); val shown = session(original); var actual = shown
        val bound = requireNotNull(originalRecoveryContext(command, actual, shown))
        val store = Store(original); var posts = 0; var gets = 0
        val engine = OriginalLedgerWriteRecovery(store,
            { originalRecoveryContext(command, actual, shown) == bound },
            { yield(); actual = shown.copy(sessionRevision = shown.sessionRevision + 2); 1 },
            { posts++; remote() }, { _, _ -> gets++; remote() })
        try { engine.recover(command); fail("A newer same-account session cannot inherit the old callback") }
        catch (_: IllegalStateException) { }
        assertEquals(0, posts); assertEquals(0, gets); assertEquals(0, store.ackCalls)
        assertEquals(original.localId, store.row?.localId); assertEquals(original.payloadJson, store.row?.payloadJson)
        assertEquals(LedgerWriteEntity.PENDING, store.row?.state)
    }
}
