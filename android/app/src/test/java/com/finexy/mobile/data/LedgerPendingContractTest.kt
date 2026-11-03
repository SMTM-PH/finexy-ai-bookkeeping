package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test

class LedgerPendingContractTest {
    private val hash = "ab".repeat(32)
    private fun context(ledger: Long = 9) = LedgerPendingContext("qa", "https://example.invalid", "identity", "qa.db", ledger, 1)
    private fun sources(vararg records: LedgerPendingRecord): Map<LedgerPendingSource, LedgerPendingSourceState> =
        LedgerPendingSource.entries.associateWith { source -> LedgerPendingSourceState(LedgerPendingAvailability.COMPLETE, records.filter { it.source == source }) }

    @Test fun completeEmptyIsDifferentFromEveryUnknownSourceState() {
        val complete = LedgerPendingSummary(context(), 1, LedgerPendingAvailability.COMPLETE, sources())
        assertEquals(0, complete.exactTotal)
        for (availability in LedgerPendingAvailability.entries.filter { it != LedgerPendingAvailability.COMPLETE }) {
            val partial = complete.copy(sources = complete.sources + (LedgerPendingSource.CAPTURE to LedgerPendingSourceState(availability)))
            assertNull(partial.exactTotal); assertNull(partial.sources.getValue(LedgerPendingSource.CAPTURE).count)
        }
        assertNull(complete.copy(permission = LedgerPendingAvailability.DENIED).exactTotal)
    }

    @Test fun createReceiptBridgesRemoteItemOriginalRequestDraftAndCaptureWithoutLosingTargets() {
        val draft = LedgerPendingRecord(LedgerPendingTarget.AIDraft(9, 0, "source", 2, hash), LedgerPendingKind.DRAFT)
        val request = LedgerPendingRecord(LedgerPendingTarget.AIRequest(9, 0, "request", "source", 2, hash), LedgerPendingKind.RECEIPT, 9007199254740993L)
        val remote = LedgerPendingRecord(LedgerPendingTarget.AIItem(9, 9007199254740993L), LedgerPendingKind.REMOTE_PENDING)
        val capture = LedgerPendingRecord(LedgerPendingTarget.Capture(9, "capture", "source", 2, hash), LedgerPendingKind.CAPTURE)
        val newer = draft.copy(target = (draft.target as LedgerPendingTarget.AIDraft).copy(revision = 3))
        val entries = aggregateLedgerPending(context(), sources(draft, request, remote, capture, newer))
        assertEquals(2, entries.size)
        val joined = entries.single { it.targets.size == 4 }
        assertEquals(LedgerPendingKind.RECEIPT, joined.kind)
        assertEquals(LedgerPendingSection.AI, joined.section)
        assertTrue(joined.targets.containsAll(listOf(draft.target, request.target, remote.target, capture.target)))
    }

    @Test fun committedCaptureMarkerJoinsNewDraftRevisionAndPrefersEncryptedSourceLocator() {
        val operation = "b8a11e38-6b52-4c77-93c2-4e256a05d901"
        val capture = LedgerPendingRecord(LedgerPendingTarget.Capture(9, operation, "source", 1, hash), LedgerPendingKind.CAPTURE)
        val draft = pendingAIDraftRecord(AIReviewDraftEntity(9, 0, "source", 2,
            "{\"captureOperationId\":\"$operation\",\"sourceImageId\":\"83edc6aa-30d5-4c5b-bf6d-45a5f1012764\"}", 2))
        val snapshots = sources(capture, draft)
        val entries = aggregateLedgerPending(context(), snapshots)
        val joined = entries.single()
        assertEquals(listOf(draft.target, capture.target), joined.targets)
        assertEquals(2L, (joined.targets.first() as LedgerPendingTarget.AIDraft).revision)
        assertEquals(1L, (joined.targets.last() as LedgerPendingTarget.Capture).revision)
        assertEquals(hash, (joined.targets.last() as LedgerPendingTarget.Capture).formHash)
        assertEquals(1, LedgerPendingSummary(context(), 1, LedgerPendingAvailability.COMPLETE, snapshots, entries).exactTotal)
    }

    @Test fun staleCaptureDoesNotMergeWithUnrelatedOperationDraftOrLedger() {
        val operation = "b8a11e38-6b52-4c77-93c2-4e256a05d901"
        val otherOperation = "83edc6aa-30d5-4c5b-bf6d-45a5f1012764"
        val capture = LedgerPendingRecord(LedgerPendingTarget.Capture(9, operation, "source", 1, hash), LedgerPendingKind.CAPTURE)
        val draft = LedgerPendingRecord(LedgerPendingTarget.AIDraft(9, 0, "source", 2, hash), LedgerPendingKind.DRAFT,
            captureOperationId = operation)
        val noMarker = draft.copy(captureOperationId = null)
        val differentOperation = draft.copy(captureOperationId = otherOperation)
        val differentDraft = draft.copy(target = (draft.target as LedgerPendingTarget.AIDraft).copy(draftId = "another-source"))
        for (unrelated in listOf(noMarker, differentOperation, differentDraft)) {
            assertEquals(2, aggregateLedgerPending(context(), sources(capture, unrelated)).size)
        }
        val otherLedger = draft.copy(target = (draft.target as LedgerPendingTarget.AIDraft).copy(ledgerId = 10))
        assertTrue(capture.links.intersect(otherLedger.links).isEmpty())
        val uncommittedDraft = noMarker.copy(target = (noMarker.target as LedgerPendingTarget.AIDraft).copy(revision = 1))
        assertEquals(capture.target, aggregateLedgerPending(context(), sources(capture, uncommittedDraft)).single().targets.first())
    }

    @Test fun periodicTupleDedupPreservesQueuedActionAndOtherDueDate() {
        val remote = LedgerPendingRecord(LedgerPendingTarget.Occurrence(9, 80, 1000), LedgerPendingKind.REMOTE_PENDING)
        val queued = LedgerPendingRecord(LedgerPendingTarget.Occurrence(9, 80, 1000, 1, 2000), LedgerPendingKind.RESULT_UNKNOWN)
        val otherDate = remote.copy(target = LedgerPendingTarget.Occurrence(9, 80, 1001))
        val entries = aggregateLedgerPending(context(), sources(remote, queued, otherDate))
        assertEquals(2, entries.size)
        assertEquals(2, entries.single { it.kind == LedgerPendingKind.RESULT_UNKNOWN }.targets.size)
        assertTrue(entries.all { it.section == LedgerPendingSection.PERIODIC })
    }

    @Test fun lostCreateResponseCannotBeMatchedToRemoteItemByFinancialContent() {
        val request = LedgerPendingRecord(LedgerPendingTarget.AIRequest(9, 0, "request", "source", 2, hash), LedgerPendingKind.RESULT_UNKNOWN)
        val remote = LedgerPendingRecord(LedgerPendingTarget.AIItem(9, 81), LedgerPendingKind.REMOTE_PENDING)
        val snapshots = sources(request, remote)
        val unknown = LedgerPendingSummary(context(), 1, LedgerPendingAvailability.COMPLETE, snapshots,
            aggregateLedgerPending(context(), snapshots))
        assertEquals(2, unknown.knownCount); assertFalse(unknown.identityComplete); assertNull(unknown.exactTotal)
        val posted = request.copy(kind = LedgerPendingKind.RECEIPT, resultingAIItemId = 81)
        val provenSources = sources(posted, remote)
        val proven = unknown.copy(sources = provenSources, entries = aggregateLedgerPending(context(), provenSources))
        assertTrue(proven.identityComplete); assertEquals(1, proven.exactTotal)
    }

    @Test fun templateRequestDedupRequiresSameTypeDraftAndRevision() {
        val draft = LedgerPendingRecord(LedgerPendingTarget.Template(9, 1, null, "draft", 1, hash), LedgerPendingKind.DRAFT)
        val request = LedgerPendingRecord(LedgerPendingTarget.Template(9, 1, "request", "draft", 1, hash), LedgerPendingKind.RESULT_UNKNOWN)
        val newer = draft.copy(target = (draft.target as LedgerPendingTarget.Template).copy(revision = 2))
        val periodic = draft.copy(target = (draft.target as LedgerPendingTarget.Template).copy(templateType = 2))
        val entries = aggregateLedgerPending(context(), sources(draft, request, newer, periodic))
        assertEquals(3, entries.size)
        assertEquals(2, entries.single { it.kind == LedgerPendingKind.RESULT_UNKNOWN }.targets.size)
    }

    @Test fun legacyCacheAndQuarantinedTransactionAreOneAIActionOnlyInDefaultLedger() {
        val remote = LedgerPendingRecord(LedgerPendingTarget.AIItem(0, 81), LedgerPendingKind.REMOTE_PENDING)
        val legacy = LedgerPendingRecord(LedgerPendingTarget.LegacyAIItem(0, 81, 100, hash), LedgerPendingKind.LEGACY_AI)
        val row = LedgerPendingRecord(LedgerPendingTarget.Transaction(0, "local", 1, hash, 81), LedgerPendingKind.LEGACY_AI)
        val normal = row.copy(target = LedgerPendingTarget.Transaction(0, "ordinary", 1, hash), kind = LedgerPendingKind.LOCAL_SYNC)
        val entries = aggregateLedgerPending(context(0), sources(remote, legacy, row, normal))
        assertEquals(2, entries.size)
        assertEquals(3, entries.single { it.section == LedgerPendingSection.AI }.targets.size)
        assertThrows(IllegalArgumentException::class.java) { aggregateLedgerPending(context(9), sources(legacy)) }
    }

    @Test fun wrongSourceDuplicateAndCrossLedgerCannotBecomeCompleteCounts() {
        val row = LedgerPendingRecord(LedgerPendingTarget.AIItem(9, 1), LedgerPendingKind.REMOTE_PENDING)
        assertThrows(IllegalArgumentException::class.java) { aggregateLedgerPending(context(), mapOf(
            LedgerPendingSource.AI_DRAFTS to LedgerPendingSourceState(LedgerPendingAvailability.COMPLETE, listOf(row)))) }
        assertThrows(IllegalArgumentException::class.java) { aggregateLedgerPending(context(), sources(row, row)) }
        assertThrows(IllegalArgumentException::class.java) { aggregateLedgerPending(context(0), sources(row)) }
    }

    @Test fun largeBoundedQueueUsesRealLongIdsAndRejectsOverflowInsteadOfTruncation() {
        val records = (1..10_000).map { LedgerPendingRecord(LedgerPendingTarget.AIItem(9, 9007199254740993L + it), LedgerPendingKind.REMOTE_PENDING) }
        assertEquals(10_000, aggregateLedgerPending(context(), sources(*records.toTypedArray())).size)
        assertThrows(IllegalArgumentException::class.java) { aggregateLedgerPending(context(), sources(*(records + records.first()).toTypedArray())) }
    }

    @Test fun contextRejectsCredentialBearingUrlsAndLocalSharedLedger() {
        assertThrows(IllegalArgumentException::class.java) { context().copy(serverUrl = "https://user:secret@example.invalid") }
        assertThrows(IllegalArgumentException::class.java) { context().copy(serverUrl = "https://example.invalid?token=secret") }
        assertThrows(IllegalArgumentException::class.java) { context().copy(localMode = true) }
        assertEquals(0L, context(0).ledgerId)
    }
}
