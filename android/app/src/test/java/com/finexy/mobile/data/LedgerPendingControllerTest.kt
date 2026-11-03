package com.finexy.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LedgerPendingControllerTest {
    private val hash = "ab".repeat(32)
    private fun context(ledger: Long = 9, revision: Long = 1) = LedgerPendingContext("qa", "https://example.invalid", "identity", "qa.db", ledger, revision)
    private fun draft(ledger: Long = 9, revision: Long = 1) = LedgerPendingRecord(
        LedgerPendingTarget.AIDraft(ledger, 0, "draft", revision, hash), LedgerPendingKind.DRAFT)
    private fun remote(ledger: Long = 9) = LedgerPendingRecord(LedgerPendingTarget.AIItem(ledger, 9007199254740993L), LedgerPendingKind.REMOTE_PENDING)
    private class Fake : LedgerPendingTransport {
        var roleValue = 1
        var roleFailure: Exception? = null
        var roleReads = 0
        val records = mutableListOf<LedgerPendingRecord>()
        val failures = mutableMapOf<LedgerPendingSource, Exception>()
        val reads = mutableMapOf<LedgerPendingSource, Int>()
        var beforeRead: suspend (LedgerPendingContext, LedgerPendingSource) -> Unit = { _, _ -> }
        override suspend fun role(context: LedgerPendingContext): Int { roleReads++; roleFailure?.let { throw it }; return roleValue }
        override suspend fun read(context: LedgerPendingContext, source: LedgerPendingSource): List<LedgerPendingRecord> {
            reads[source] = (reads[source] ?: 0) + 1
            beforeRead(context, source)
            failures[source]?.let { throw it }
            return records.filter { it.source == source && it.target.ledgerId == context.ledgerId }.toList()
        }
    }

    @Test fun onlyCompleteSuccessfulSourcesCanProveEmpty() = runBlocking {
        val fake = Fake(); val current = context()
        val controller = LedgerPendingController(fake) { current }
        controller.bind(current)
        assertNull(controller.state.value.exactTotal)
        assertTrue(controller.refresh())
        assertEquals(0, controller.state.value.exactTotal)
        assertEquals(LedgerPendingSource.entries.toSet(), fake.reads.keys)
        assertTrue(controller.state.value.sources.values.all { it.count == 0 })
    }

    @Test fun failedUnsupportedAndDeniedSourcesDoNotBecomeZeroOrHideKnownWork() = runBlocking {
        val fake = Fake(); fake.records += draft()
        fake.failures[LedgerPendingSource.AI_QUEUE] = IOException("sensitive response body")
        fake.failures[LedgerPendingSource.OCCURRENCES] = LedgerPendingUnavailableException()
        fake.failures[LedgerPendingSource.CAPTURE] = LedgerPendingDeniedException()
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current)
        controller.refresh()
        val state = controller.state.value
        assertEquals(1, state.knownCount); assertNull(state.exactTotal)
        assertEquals(LedgerPendingAvailability.FAILED, state.sources.getValue(LedgerPendingSource.AI_QUEUE).availability)
        assertEquals(LedgerPendingAvailability.UNAVAILABLE, state.sources.getValue(LedgerPendingSource.OCCURRENCES).availability)
        assertEquals(LedgerPendingAvailability.DENIED, state.sources.getValue(LedgerPendingSource.CAPTURE).availability)
        assertNull(state.sources.getValue(LedgerPendingSource.AI_QUEUE).count)
        assertFalse(state.toString().contains("sensitive response body"))
    }

    @Test fun unknownPermissionRetainsOwnLocalPendingButNeverClaimsRemoteEmpty() = runBlocking {
        val fake = Fake(); fake.records += listOf(draft(), remote()); fake.roleFailure = LedgerPendingDeniedException()
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current)
        controller.refresh()
        assertEquals(1, controller.state.value.knownCount); assertNull(controller.state.value.exactTotal)
        assertEquals(LedgerPendingAvailability.DENIED, controller.state.value.permission)
        assertFalse(fake.reads.containsKey(LedgerPendingSource.AI_QUEUE))
        assertNull(controller.verify(draft().target))
    }

    @Test fun delayedResultCannotPublishAfterLedgerABAOrClose() = runBlocking {
        val fake = Fake(); fake.records += remote()
        val entered = Channel<Unit>(1); val release = Channel<Unit>(1)
        fake.beforeRead = { _, source -> if (source == LedgerPendingSource.AI_QUEUE) { entered.send(Unit); release.receive() } }
        var current: LedgerPendingContext? = context()
        val controller = LedgerPendingController(fake) { current }; controller.bind(current)
        val loading = async { controller.refresh() }; entered.receive()
        current = context(10); controller.bind(current)
        current = context(); controller.bind(current)
        release.send(Unit); assertFalse(loading.await())
        assertTrue(controller.state.value.entries.isEmpty()); assertNull(controller.state.value.exactTotal)
        val again = async { controller.refresh() }; entered.receive()
        controller.close(); release.send(Unit); assertFalse(again.await())
        assertTrue(controller.state.value.closed); assertNull(controller.state.value.context)
        assertTrue(controller.state.value.sources.isEmpty())
    }

    @Test fun sessionRevisionRejectsEqualIdentityABAWithoutWaitingForOwnerBind() = runBlocking {
        val fake = Fake(); fake.records += remote()
        val entered = Channel<Unit>(1); val release = Channel<Unit>(1)
        fake.beforeRead = { _, source -> if (source == LedgerPendingSource.AI_QUEUE) { entered.send(Unit); release.receive() } }
        var current: LedgerPendingContext? = context()
        val controller = LedgerPendingController(fake) { current }; controller.bind(current)
        val loading = async { controller.refresh() }; entered.receive()
        current = context(revision = 3) // token A -> B -> A, same identity and endpoint
        release.send(Unit); assertFalse(loading.await())
        assertNull(controller.state.value.context); assertTrue(controller.state.value.entries.isEmpty())
    }

    @Test fun verifyRereadsLiveRoleAndExactOriginalLocatorThenIssuesInvalidatableTicket() = runBlocking {
        val fake = Fake(); val original = draft(); fake.records += original
        var current: LedgerPendingContext? = context()
        val controller = LedgerPendingController(fake) { current }; controller.bind(current); controller.refresh()
        val before = fake.roleReads
        val verified = controller.verify(original.target)
        assertNotNull(verified); assertEquals(original.target, verified!!.target)
        assertEquals(before + 1, fake.roleReads); assertTrue(verified.isCurrent())
        controller.bind(current); assertFalse(verified.isCurrent())
        controller.refresh()
        fake.records.clear(); fake.records += draft(revision = 2)
        assertNull(controller.verify(original.target))
        assertEquals(LedgerPendingAvailability.UNAVAILABLE, controller.state.value.navigationFailure)
    }

    @Test fun permissionRevocationAtClickErasesRemoteRowsAndKeepsLocalRecovery() = runBlocking {
        val fake = Fake(); fake.records += listOf(draft(), remote())
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current); controller.refresh()
        assertEquals(2, controller.state.value.exactTotal)
        fake.roleFailure = LedgerPendingDeniedException()
        assertNull(controller.verify(remote().target))
        assertEquals(1, controller.state.value.knownCount)
        assertNull(controller.state.value.exactTotal)
        assertEquals(LedgerPendingAvailability.DENIED, controller.state.value.navigationFailure)
    }

    @Test fun localObserverRefreshDoesNotReadRemoteQueuesAndStillChecksCurrentPermission() = runBlocking {
        val fake = Fake(); fake.records += remote()
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current); controller.refresh()
        val remoteReads = fake.reads.getValue(LedgerPendingSource.AI_QUEUE)
        fake.records += draft(); val roleReads = fake.roleReads
        controller.refreshLocal()
        assertEquals(remoteReads, fake.reads.getValue(LedgerPendingSource.AI_QUEUE))
        assertEquals(roleReads + 1, fake.roleReads)
        assertEquals(2, controller.state.value.exactTotal)
    }

    @Test fun localChangeDuringFullRefreshIsCoalescedWithoutOrphaningRemoteLoading() = runBlocking {
        val fake = Fake(); fake.records += remote()
        val entered = Channel<Unit>(1); val release = Channel<Unit>(1)
        fake.beforeRead = { _, source -> if (source == LedgerPendingSource.AI_QUEUE) { entered.send(Unit); release.receive() } }
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current)
        val full = async { controller.refresh() }; entered.receive()
        fake.records += draft()
        assertFalse(controller.refreshLocal()) // coalesced into the ongoing full read
        release.send(Unit); assertTrue(full.await())
        assertFalse(controller.state.value.busy)
        assertEquals(2, controller.state.value.exactTotal)
        assertEquals(1, fake.reads.getValue(LedgerPendingSource.AI_QUEUE))
        assertEquals(2, fake.reads.getValue(LedgerPendingSource.AI_DRAFTS))
    }

    @Test fun corruptLocatorReadFailsItsOwnSourceAfterSuccessfulPermissionRead() = runBlocking {
        val fake = Fake(); fake.records += listOf(draft(), remote())
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current); controller.refresh()
        fake.failures[LedgerPendingSource.AI_DRAFTS] = IllegalArgumentException("private malformed content")
        assertNull(controller.verify(draft().target))
        val state = controller.state.value
        assertEquals(LedgerPendingAvailability.COMPLETE, state.permission)
        assertEquals(LedgerPendingAvailability.FAILED, state.sources.getValue(LedgerPendingSource.AI_DRAFTS).availability)
        assertEquals(1, state.knownCount); assertNull(state.exactTotal)
        assertFalse(state.toString().contains("private malformed content"))
    }

    @Test fun malformedCommittedCaptureMarkerFailsWholeDraftSourceWithoutLosingOtherSources() = runBlocking {
        val fake = Fake(); fake.records += remote()
        fake.beforeRead = { _, source -> if (source == LedgerPendingSource.AI_DRAFTS) {
            pendingAIDraftRecord(AIReviewDraftEntity(9, 0, "source", 2,
                "{\"captureOperationId\":\"bad-marker\",\"sourceImageId\":\"83edc6aa-30d5-4c5b-bf6d-45a5f1012764\"}", 2))
        } }
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current); controller.refresh()
        val source = controller.state.value.sources.getValue(LedgerPendingSource.AI_DRAFTS)
        assertEquals(LedgerPendingAvailability.FAILED, source.availability); assertNull(source.count)
        assertEquals(1, controller.state.value.knownCount); assertNull(controller.state.value.exactTotal)
    }

    @Test fun captureIdentityJoinDoesNotMakeAnOldSourceRevisionVerifiable() = runBlocking {
        val operation = "b8a11e38-6b52-4c77-93c2-4e256a05d901"
        val capture = LedgerPendingRecord(LedgerPendingTarget.Capture(9, operation, "source", 1, hash), LedgerPendingKind.CAPTURE)
        val committed = LedgerPendingRecord(LedgerPendingTarget.AIDraft(9, 0, "source", 2, hash), LedgerPendingKind.DRAFT,
            captureOperationId = operation)
        val fake = Fake(); fake.records += listOf(capture, committed)
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current); controller.refresh()
        assertEquals(1, controller.state.value.exactTotal)
        assertEquals(committed.target, controller.state.value.entries.single().targets.first())
        val ticket = controller.verify(committed.target)!!
        assertTrue(ticket.isCurrent())
        fake.records.remove(committed)
        fake.records += committed.copy(target = (committed.target as LedgerPendingTarget.AIDraft).copy(revision = 3))
        controller.refreshLocal(); assertFalse(ticket.isCurrent())
        assertEquals(1, controller.state.value.exactTotal)
        assertNull(controller.verify(committed.target))
    }

    @Test fun wrongScopeAndMalformedDuplicateSourceBecomeFailureInsteadOfPartialCount() = runBlocking {
        val fake = Fake(); fake.records += listOf(draft(), draft())
        val current = context(); val controller = LedgerPendingController(fake) { current }; controller.bind(current); controller.refresh()
        assertEquals(LedgerPendingAvailability.FAILED, controller.state.value.sources.getValue(LedgerPendingSource.AI_DRAFTS).availability)
        assertNull(controller.state.value.exactTotal)
        assertNull(controller.verify(draft(ledger = 10).target))
        assertEquals(0, controller.state.value.knownCount)
    }
}
