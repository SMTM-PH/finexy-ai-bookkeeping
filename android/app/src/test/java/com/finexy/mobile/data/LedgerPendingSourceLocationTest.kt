package com.finexy.mobile.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class LedgerPendingSourceLocationTest {
    private val operation = "b8a11e38-6b52-4c77-93c2-4e256a05d901"
    private val image = "83edc6aa-30d5-4c5b-bf6d-45a5f1012764"
    private fun draft() = AIReviewDraftEntity(9, 0, "original-draft", 3, "{\"sourceText\":\"synthetic original\"}", 10)
    private fun capture(saved: AIReviewDraftEntity = draft()) = AIPendingCapture("synthetic-partition", operation,
        saved.ledgerId, saved.draftId, saved.revision, aiImageDigest(saved.formJson.toByteArray(Charsets.UTF_8)))
    private fun request() = AIReviewRequestEntity(9, 0, "original-request", "original-draft", 3, "create", buildJsonObject {
        put("ledgerId", "9"); put("clientSessionId", "original-request"); put("sourceType", 1)
        put("sourceText", "synthetic original"); put("failureReason", "")
    }.toString(), createdAt = 10)
    private fun committed(saved: AIReviewDraftEntity = draft(), marker: String = operation) = saved.copy(revision = 4, formJson = buildJsonObject {
        put("sourceText", "synthetic original"); put("sourceImageId", image); put("captureOperationId", marker)
    }.toString(), updatedAt = 11)

    @Test fun draftRequiresExactOriginalProjectionIncludingRawInputHash() {
        val saved = draft(); val target = pendingAIDraftRecord(saved).target
        assertEquals(LedgerPendingSourceMatch.EXACT, matchPendingSourceLocation(target, saved, null, null))
        for (changed in listOf(saved.copy(draftId = "replacement"), saved.copy(revision = 4), saved.copy(ledgerId = 10),
            saved.copy(formJson = "{\"sourceText\":\"replacement with same revision\"}"))) {
            assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, changed, null, null))
        }
    }

    @Test fun sourcePageRejectsReviewAndOtherDestinationKinds() {
        val saved = draft()
        val targets = listOf(LedgerPendingTarget.AIItem(9, 71), LedgerPendingTarget.Occurrence(9, 8, 1790000000),
            LedgerPendingTarget.AIDraft(9, 71, "review-draft", 1, "ab".repeat(32)),
            LedgerPendingTarget.Transaction(9, "write", 1, "ab".repeat(32)))
        for (target in targets) {
            assertFalse(target.isSourceLocation())
            assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, saved, null, null))
        }
    }

    @Test fun originalRequestRemainsExactButCannotExposeAnUnrelatedCurrentDraft() {
        val pending = request(); val target = pendingAIRequestRecord(pending).target
        assertTrue(target.isSourceLocation())
        assertEquals(LedgerPendingSourceMatch.EXACT, matchPendingSourceLocation(target, draft(), pending, null))
        assertTrue(pendingSourceOwnsDraft(target, draft(), pending, null))
        val replaced = draft().copy(draftId = "replacement", revision = 5)
        assertEquals(LedgerPendingSourceMatch.EXACT, matchPendingSourceLocation(target, replaced, pending, null))
        assertFalse(pendingSourceOwnsDraft(target, replaced, pending, null))
        assertFalse(pendingSourceOwnsDraft(target, draft().copy(revision = 4), pending, null))
    }

    @Test fun originalRequestKeyPayloadRevisionAndLedgerCannotBeSubstituted() {
        val pending = request(); val target = pendingAIRequestRecord(pending).target
        for (changed in listOf(pending.copy(requestId = "replacement"), pending.copy(draftRevision = 4),
            pending.copy(payloadJson = pending.payloadJson.replace("synthetic original", "synthetic replacement")),
            pending.copy(ledgerId = 10))) {
            assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, draft(), changed, null))
        }
    }

    @Test fun captureRequiresTheExactOriginalJournalAndMatchingSource() {
        val pending = capture(); val target = pendingCaptureRecord(pending).target
        assertTrue(target.isSourceLocation())
        assertEquals(LedgerPendingSourceMatch.EXACT, matchPendingSourceLocation(target, draft(), null, pending))
        assertTrue(pendingSourceOwnsDraft(target, draft(), null, pending))
        assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, draft(), null,
            pending.copy(operationId = "a8a11e38-6b52-4c77-93c2-4e256a05d901")))
        assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, draft(), null, null))
    }

    @Test fun oldCaptureMayOnlyBeDisposedWhenTheSourceWasReplaced() {
        val pending = capture(); val target = pendingCaptureRecord(pending).target
        for (replacement in listOf(null, draft().copy(draftId = "replacement"), draft().copy(revision = 4),
            draft().copy(formJson = "{\"sourceText\":\"replacement same metadata\"}"))) {
            assertEquals(LedgerPendingSourceMatch.CAPTURE_ONLY, matchPendingSourceLocation(target, replacement, null, pending))
            assertFalse(pendingSourceOwnsDraft(target, replacement, null, pending))
        }
    }

    @Test fun committedCaptureMarkerAllowsExplicitCleanupWithoutReimport() {
        val pending = capture(); val target = pendingCaptureRecord(pending).target; val saved = committed()
        assertTrue(pendingCaptureIsCommitted(pending, saved))
        assertEquals(LedgerPendingSourceMatch.CAPTURE_COMMITTED, matchPendingSourceLocation(target, saved, null, pending))
        assertTrue(pendingSourceOwnsDraft(target, saved, null, pending))
        // A missing journal is not silently treated as the original capture.
        assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, saved, null, null))
    }

    @Test fun copiedOrInvalidCommitMarkerCannotGrantAccessToAnotherSource() {
        val pending = capture(); val target = pendingCaptureRecord(pending).target
        for (replacement in listOf(committed().copy(draftId = "replacement"), committed().copy(ledgerId = 10),
            committed().copy(revision = 3), committed(marker = "a8a11e38-6b52-4c77-93c2-4e256a05d901"),
            committed().copy(formJson = "{\"captureOperationId\":\"$operation\"}"))) {
            assertFalse(pendingCaptureIsCommitted(pending, replacement))
            assertEquals(LedgerPendingSourceMatch.CAPTURE_ONLY, matchPendingSourceLocation(target, replacement, null, pending))
            assertFalse(pendingSourceOwnsDraft(target, replacement, null, pending))
        }
    }

    @Test fun malformedSavedSourceIsUnavailableInsteadOfAnEmptyNewForm() {
        val saved = draft(); val target = pendingAIDraftRecord(saved).target
        assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, saved.copy(formJson = "{"), null, null))
        assertEquals(LedgerPendingSourceMatch.UNAVAILABLE, matchPendingSourceLocation(target, null, null, null))
    }
}
