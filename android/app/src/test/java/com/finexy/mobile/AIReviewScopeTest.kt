package com.finexy.mobile

import org.junit.Assert.assertThrows
import org.junit.Test

class AIReviewScopeTest {
    @Test fun accountReviewCanOnlyBeSavedToTheDefaultLedger() {
        requireAIReviewSaveLedger(ACCOUNT_AI_REVIEW_LEDGER_ID, 42)
    }

    @Test fun manualWritesRemainAvailableForEveryLedger() {
        listOf(0L, 9L, Long.MAX_VALUE).forEach { requireAIReviewSaveLedger(it, null) }
    }

    @Test fun capturedReviewAssociationCannotEnterAnySharedQueue() {
        listOf(9L, Long.MAX_VALUE).forEach { ledger ->
            assertThrows(IllegalArgumentException::class.java) { requireAIReviewSaveLedger(ledger, 42) }
        }
    }

    @Test fun invalidReviewAssociationRequiresReopeningTheQueue() {
        listOf(0L, -1L).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { requireAIReviewSaveLedger(0, id) }
        }
    }
}
