package com.finexy.mobile.data

import org.junit.Assert.*
import org.junit.Test

class TransactionTransferDirectionTest {
    @Test fun anUnselectedNewSourceNeverInfersIncomingOrSelectsAnotherAccount() {
        assertEquals(TransactionTransferDirection.INTERNAL, initialTransactionTransferDirection(null, 0, 11))
        assertEquals(TransactionTransferDirection.INTERNAL, initialTransactionTransferDirection(null, 0, null))
        try { transactionTransferFields(TransactionTransferDirection.INTERNAL, 0, 123, 11, 123); fail("Unselected source must stay invalid") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun originalIncomingRestoresOnlyTheOriginalDestinationAndItsAmount() {
        assertEquals(TransactionTransferDirection.IN, initialTransactionTransferDirection(4, 0, 11))
        assertEquals(TransactionTransferFields(0, 0, 11, 456), transactionTransferFields(TransactionTransferDirection.IN, 11, 456, 99, 777))
        val draft = TransactionDraft("original", 4, 0, 23, "Synthetic transfer", 0, "Synthetic", "[]", 11, 456,
            transferDirection = TransactionTransferDirection.IN)
        requireTransactionTransferDirection(draft)
        try { requireTransactionTransferDirection(draft.copy(transferDirection = TransactionTransferDirection.INTERNAL)); fail("An explicit incoming intent is required") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun originalOutgoingCannotLeakAHiddenInternalDestination() {
        assertEquals(TransactionTransferDirection.OUT, initialTransactionTransferDirection(4, 10, null))
        assertEquals(TransactionTransferDirection.OUT, initialTransactionTransferDirection(4, 10, 0))
        assertEquals(TransactionTransferFields(10, 123, null, 0), transactionTransferFields(TransactionTransferDirection.OUT, 10, 123, 99, 777))
        requireTransactionTransferDirection(TransactionDraft("original", 4, 10, 23, "Synthetic transfer", 123, "Synthetic", "[]",
            transferDirection = TransactionTransferDirection.OUT))
    }
    @Test fun explicitDirectionNeverDefaultsToTheFirstAccountAndInternalSidesStayDistinct() {
        for (direction in TransactionTransferDirection.entries) {
            try { transactionTransferFields(direction, 0, 123, null, 0); fail("No default account is authorized") }
            catch (_: IllegalArgumentException) { }
        }
        try { transactionTransferFields(TransactionTransferDirection.INTERNAL, 10, 123, 10, 123); fail("The two internal accounts must differ") }
        catch (_: IllegalArgumentException) { }
        assertEquals(TransactionTransferFields(10, 123, 11, 25), transactionTransferFields(TransactionTransferDirection.INTERNAL, 10, 123, 11, 25))
    }
    @Test fun explicitlyChosenLocalWalletOnEitherInternalSideRemainsUnmapped() {
        assertEquals(TransactionTransferFields(-1, 123, 11, 25),
            transactionTransferFields(TransactionTransferDirection.INTERNAL, -1, 123, 11, 25))
        assertEquals(TransactionTransferFields(10, 123, -1, 25),
            transactionTransferFields(TransactionTransferDirection.INTERNAL, 10, 123, -1, 25))
        requireTransactionTransferDirection(TransactionDraft("original", 4, 10, 23, "Synthetic transfer", 123,
            "Synthetic wallet", "[]", -1, 25))
    }
}
