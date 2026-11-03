package com.finexy.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LedgerPendingReviewLocationTest {
    private fun draft() = AIReviewDraftEntity(9, 71, "original-draft", 3,
        "{\"sourceAmount\":\"1.+\",\"comment\":\"Synthetic original\"}", 10)
    private fun request(comment: String = "Synthetic original") = AIReviewRequestEntity(9, 71, "original-request", "original-draft", 3,
        "confirm", buildJsonObject {
            put("ledgerId", "9"); put("id", "71")
            put("transaction", buildJsonObject {
                put("ledgerId", "9"); put("clientSessionId", "original-transaction"); put("type", 3)
                put("time", 1790000000); put("utcOffset", 480); put("categoryId", "20")
                put("sourceAccountId", "10"); put("destinationAccountId", "0")
                put("sourceAmount", 123); put("destinationAmount", 0); put("hideAmount", false)
                put("tagIds", JsonArray(emptyList())); put("pictureIds", JsonArray(emptyList())); put("comment", comment)
            })
        }.toString(), createdAt = 10)
    private fun posted(original: AIReviewRequestEntity = request(), transactionId: String = "9007199254740993") = original.copy(
        state = AIReviewRequestEntity.POSTED,
        receiptJson = "{\"ledgerId\":\"9\",\"id\":\"71\",\"transactionId\":\"$transactionId\"}")

    @Test fun onlyReviewItemAndPositiveReviewDraftOrRequestAreAccepted() {
        assertEquals(71L, LedgerPendingTarget.AIItem(9, 71).reviewItemId())
        assertEquals(71L, pendingAIDraftRecord(draft()).target.reviewItemId())
        assertEquals(71L, pendingAIRequestRecord(request()).target.reviewItemId())
        assertNull(pendingAIDraftRecord(draft().copy(itemId = 0)).target.reviewItemId())
        assertNull(LedgerPendingTarget.Occurrence(9, 8, 1790000000).reviewItemId())
        assertFalse(matchPendingReviewLocation(LedgerPendingTarget.AIItem(9, 71), 10, 71, null, null))
        assertFalse(matchPendingReviewLocation(LedgerPendingTarget.AIItem(9, 71), 9, 72, null, null))
    }

    @Test fun originalDraftAcceptsOnlyItsExactRawHashAndAssociatedRequestRevision() {
        val saved = draft(); val target = pendingAIDraftRecord(saved).target
        assertTrue(matchPendingReviewLocation(target, 9, 71, saved, null))
        assertTrue(matchPendingReviewLocation(target, 9, 71, saved, request()))
        assertTrue(matchPendingReviewLocation(target, 9, 71, saved, posted()))
        for (replacement in listOf(saved.copy(draftId = "replacement"), saved.copy(revision = 4),
            saved.copy(formJson = "{\"sourceAmount\":\"99.00\"}"))) {
            assertFalse(matchPendingReviewLocation(target, 9, 71, replacement, null))
        }
        assertFalse(matchPendingReviewLocation(target, 9, 71, saved, request().copy(draftId = "replacement")))
        assertFalse(matchPendingReviewLocation(target, 9, 71, saved, request().copy(draftRevision = 4)))
    }

    @Test fun requestLocatorCannotBecomeAnotherRequestOrAnEmptyNewForm() {
        val original = request(); val target = pendingAIRequestRecord(original).target
        assertTrue(matchPendingReviewLocation(target, 9, 71, draft(), original))
        assertTrue(matchPendingReviewLocation(target, 9, 71, null, posted()))
        assertFalse(matchPendingReviewLocation(target, 9, 71, draft(), null))
        for (replacement in listOf(original.copy(requestId = "replacement"), original.copy(draftRevision = 4),
            request(comment = "Synthetic changed payload"), original.copy(createdAt = 11))) {
            assertFalse(matchPendingReviewLocation(target, 9, 71, draft(), replacement))
        }
    }

    @Test fun observerMayAdvanceReceiptButNeverAdoptReplacementRequest() {
        val original = request()
        assertTrue(samePendingReviewRequest(original, posted(original)))
        assertTrue(samePendingReviewRequest(original, original.copy(lastErrorCode = "delivery-failed")))
        assertTrue(samePendingReviewRequest(null, null))
        assertFalse(samePendingReviewRequest(original, null))
        assertFalse(samePendingReviewRequest(null, original))
        assertFalse(samePendingReviewRequest(original, request(comment = "replacement")))
        assertFalse(samePendingReviewRequest(original, original.copy(requestId = "replacement")))
    }

    @Test fun expectedResumeComparesTheFullFreshEntityBeforeReturningRawFields() {
        val original = draft()
        assertEquals("1.+", restoreExpectedAIReviewEditor(original, original).fields["sourceAmount"])
        for (replacement in listOf(null, original.copy(draftId = "replacement"), original.copy(revision = 4),
            original.copy(formJson = "{\"sourceAmount\":\"999.00\"}"), original.copy(updatedAt = 11),
            original.copy(ledgerId = 10), original.copy(itemId = 72))) {
            assertThrows(IllegalArgumentException::class.java) { restoreExpectedAIReviewEditor(original, replacement) }
        }
    }

    @Test fun expectedResumeDoesNotConvertMalformedRawDraftIntoAnInitialForm() {
        val malformed = draft().copy(formJson = "{\"sourceAmount\":123}")
        assertThrows(IllegalArgumentException::class.java) { restoreExpectedAIReviewEditor(malformed, malformed) }
        assertFalse(matchPendingReviewLocation(pendingAIDraftRecord(draft()).target, 9, 71, malformed, null))
    }

    @Test fun knownReceiptNeverDowngradesToAnUploadableRequestOrDifferentReceipt() {
        val known = posted()
        assertEquals(known, requireOriginalAIReviewDelivery(known, known))
        assertThrows(IllegalArgumentException::class.java) { requireOriginalAIReviewDelivery(known, request()) }
        assertThrows(IllegalArgumentException::class.java) { requireOriginalAIReviewDelivery(known, posted(transactionId = "9007199254740995")) }
        assertThrows(IllegalArgumentException::class.java) { requireOriginalAIReviewDelivery(known, null) }
    }

    @Test fun originalPendingCanObserveItsReceiptWithoutRegeneratingARequest() {
        val original = request(); val known = posted(original)
        assertEquals(original, requireOriginalAIReviewDelivery(original, original))
        assertEquals(known, requireOriginalAIReviewDelivery(original, known))
        assertThrows(IllegalArgumentException::class.java) { requireOriginalAIReviewDelivery(original, request(comment = "replacement")) }
        assertThrows(IllegalArgumentException::class.java) { requireOriginalAIReviewDelivery(original, original.copy(requestId = "replacement")) }
    }
}
