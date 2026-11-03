package com.finexy.mobile

import com.finexy.mobile.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Test

/** Injected coroutine owner tests cover navigation/cache races; no Android, Room or HTTP is claimed. */
class LedgerPendingViewModelTest {
    private val hash = "ab".repeat(32)
    private fun context(revision: Long = 1) = LedgerPendingContext("qa", "https://example.invalid", "identity", "qa.db", 9, revision)
    private fun draft(id: String = "draft") = LedgerPendingRecord(LedgerPendingTarget.AIDraft(9, 0, id, 1, hash), LedgerPendingKind.DRAFT)
    private fun remote() = LedgerPendingRecord(LedgerPendingTarget.AIItem(9, 81), LedgerPendingKind.REMOTE_PENDING)
    private class Transport : LedgerPendingTransport {
        val records = mutableListOf<LedgerPendingRecord>()
        val reads = mutableMapOf<LedgerPendingSource, Int>()
        var roleReads = 0
        var nextRoleGate: CompletableDeferred<Unit>? = null
        override suspend fun role(context: LedgerPendingContext): Int {
            roleReads++
            val gate = nextRoleGate; nextRoleGate = null
            // Models a transport that finishes its old work even after the caller was cancelled.
            if (gate != null) withContext(NonCancellable) { gate.await() }
            return 1
        }
        override suspend fun read(context: LedgerPendingContext, source: LedgerPendingSource): List<LedgerPendingRecord> {
            reads[source] = (reads[source] ?: 0) + 1
            return records.filter { it.source == source && it.target.ledgerId == context.ledgerId }
        }
    }
    private class Owner(val transport: Transport = Transport(), baseline: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val events = MutableSharedFlow<LedgerPendingChange>(extraBufferCapacity = 16)
        var current: LedgerPendingContext? = LedgerPendingContext("qa", "https://example.invalid", "identity", "qa.db", 9, 1)
        var sessionChanged: (() -> Unit)? = null
        var observerStarts = 0
        val model = LedgerPendingViewModel(transport, { current }, { observerStarts++; events }, { listener ->
            sessionChanged = listener
            AutoCloseable { if (sessionChanged === listener) sessionChanged = null }
        }, baseline, scope)
        fun close() { model.leave(); scope.cancel() }
    }
    private suspend fun settled(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; yield() }
        assertTrue("Owner did not reach the expected state", condition())
    }

    @Test fun cacheBaselinePrecedesInitialOnlineReadAndTrueCacheChangeRevokesOldEmptyProof() = runBlocking {
        val owner = Owner(baseline = true)
        try {
            owner.model.enter(9)
            assertTrue(owner.model.preparing.value)
            assertEquals(0, owner.transport.roleReads)
            owner.events.emit(LedgerPendingChange.LOCAL)
            assertEquals(0, owner.transport.roleReads)
            owner.events.emit(LedgerPendingChange.REMOTE_BASELINE)
            settled { owner.model.state.value.exactTotal == 0 }
            val onlineReads = owner.transport.reads.getValue(LedgerPendingSource.AI_QUEUE)
            owner.transport.records += remote()
            owner.events.emit(LedgerPendingChange.REMOTE_CACHE)
            settled { !owner.model.state.value.busy && owner.model.state.value.sources[LedgerPendingSource.AI_QUEUE]?.availability == LedgerPendingAvailability.NOT_LOADED }
            assertNull(owner.model.state.value.exactTotal)
            assertEquals(LedgerPendingAvailability.NOT_LOADED, owner.model.state.value.sources.getValue(LedgerPendingSource.AI_QUEUE).availability)
            assertEquals(onlineReads, owner.transport.reads.getValue(LedgerPendingSource.AI_QUEUE))
            owner.model.refresh()
            settled { owner.model.state.value.exactTotal == 1 }
        } finally { owner.close() }
    }

    @Test fun localQueueSignalRereadsOnlyLocalSourcesAndKeepsUnchangedOnlineSnapshot() = runBlocking {
        val owner = Owner(); owner.transport.records += remote()
        try {
            owner.model.enter(9); settled { owner.model.state.value.exactTotal == 1 }
            val onlineReads = owner.transport.reads.getValue(LedgerPendingSource.AI_QUEUE)
            owner.transport.records += draft()
            owner.events.emit(LedgerPendingChange.LOCAL)
            settled { owner.model.state.value.exactTotal == 2 }
            assertEquals(onlineReads, owner.transport.reads.getValue(LedgerPendingSource.AI_QUEUE))
        } finally { owner.close() }
    }

    @Test fun oldDestinationFinallyCannotClearBusyFlagOfNewNavigation() = runBlocking {
        val owner = Owner(); val first = draft("first"); val second = draft("second")
        owner.transport.records += listOf(first, second)
        val oldGate = CompletableDeferred<Unit>(); val newGate = CompletableDeferred<Unit>()
        try {
            owner.model.enter(9); settled { owner.model.state.value.exactTotal == 2 }
            owner.model.open(first.target); settled { owner.model.destination.value != null }
            val shown = owner.model.destination.value!!
            owner.transport.nextRoleGate = oldGate
            owner.model.verifyDestination(shown)
            assertTrue(owner.model.opening.value)
            owner.model.closeDestination(); settled { owner.model.state.value.exactTotal == 2 }
            owner.transport.nextRoleGate = newGate
            owner.model.open(second.target); assertTrue(owner.model.opening.value)
            oldGate.complete(Unit); yield()
            assertTrue(owner.model.opening.value)
            assertNull(owner.model.destination.value)
            newGate.complete(Unit)
            settled { owner.model.destination.value?.target == second.target }
            assertFalse(owner.model.opening.value)
        } finally { oldGate.complete(Unit); newGate.complete(Unit); owner.close() }
    }

    @Test fun sessionABAImmediatelyClearsStateAndOldRequestCannotNavigateAfterExplicitRefresh() = runBlocking {
        val owner = Owner(); val target = draft(); owner.transport.records += target
        val gate = CompletableDeferred<Unit>()
        try {
            owner.model.enter(9); settled { owner.model.state.value.exactTotal == 1 }
            owner.transport.nextRoleGate = gate; owner.model.open(target.target)
            owner.current = context(3); owner.sessionChanged!!.invoke()
            assertNull(owner.model.state.value.context); assertNull(owner.model.destination.value)
            assertFalse(owner.model.opening.value)
            gate.complete(Unit); yield()
            assertNull(owner.model.destination.value)
            owner.model.refresh(); settled { owner.model.state.value.context == context(3) && owner.model.state.value.exactTotal == 1 }
            assertEquals(2, owner.observerStarts)
            owner.model.leave()
            assertNull(owner.model.state.value.context); assertNull(owner.sessionChanged)
        } finally { gate.complete(Unit); owner.close() }
    }

    @Test fun unknownRepositoryScopeDoesNotObserveOldRoomPartitionOrClaimEmpty() = runBlocking {
        val owner = Owner(baseline = true); owner.current = null
        try {
            owner.model.enter(9); yield()
            assertEquals(0, owner.observerStarts); assertEquals(0, owner.transport.roleReads)
            assertNull(owner.model.state.value.context); assertNull(owner.model.state.value.exactTotal)
        } finally { owner.close() }
    }
}
