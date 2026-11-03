package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated Room transactions. No server, app database, WorkManager or user account is used. */
@RunWith(AndroidJUnit4::class)
class OriginalTransactionRecoveryPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun db() = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
    private fun pending(ledger: Long = 9, local: String = "original-key") = LedgerWriteEntity(ledger, local,
        """{"ledgerId":"$ledger","type":3,"categoryId":"20","time":1790000000,"utcOffset":480,"sourceAccountId":"10","destinationAccountId":"0","sourceAmount":123,"destinationAmount":0,"tagIds":[],"pictureIds":[],"hideAmount":false,"geoLocation":null,"comment":"Synthetic"}""", createdAt = 10)
    private fun remote(id: Long = 77, ledger: Long = 9) = RemoteTransaction(id, null, 3, 20, "Synthetic category",
        10, null, 123, 0, "CNY", "Synthetic", 1790000000, 480, "[]", ledgerId = ledger, editable = true)
    private fun cache(id: Long, ledger: Long) = LedgerTransactionCacheEntity(id, ledger, 3, 10, null, 20,
        "Synthetic existing", 111, 0, "CNY", "Synthetic existing", 1000, 480, "[]", false)
    private fun row(id: String = "local-original", type: Int = 3) = TransactionEntity(id, serverId = 9007199254740993L,
        type = type, sourceAccountId = 10, categoryId = if (type == 4) 23 else 20, categoryName = if (type == 4) "Synthetic transfer" else "Synthetic expense",
        sourceAmountMinor = 123, comment = "Synthetic original", time = 1790000000000, utcOffset = -240,
        tagIdsJson = "[\"501\"]", syncState = SyncState.FAILED, updatedAt = 10,
        pictureIdsJson = "[\"601\"]", geoLocationJson = "{\"longitude\":1.25,\"latitude\":2.5}", hideAmount = true)
    private suspend fun resources(database: FinexyDatabase) {
        database.dao().upsertAccounts(listOf(AccountEntity(10, "Synthetic source", "USD", 10000), AccountEntity(11, "Synthetic destination", "CNY", 5000)))
        database.dao().upsertCategories(listOf(CategoryEntity(1, "Synthetic expense parent", 0, 2), CategoryEntity(20, "Synthetic expense", 1, 2),
            CategoryEntity(3, "Synthetic transfer parent", 0, 3), CategoryEntity(23, "Synthetic transfer", 3, 3)))
    }
    private fun draft(original: TransactionEntity, sourceAmount: Long = 456, comment: String = "Synthetic edit",
        direction: TransactionTransferDirection = TransactionTransferDirection.INTERNAL, destinationAmount: Long = original.destinationAmountMinor) =
        TransactionDraft(original.localId, original.type, original.sourceAccountId, original.categoryId, original.categoryName,
            sourceAmount, comment, original.tagIdsJson, original.destinationAccountId, destinationAmount, hideAmount = original.hideAmount,
            transferDirection = direction)
    private fun sameOriginalFields(original: TransactionEntity, saved: TransactionEntity) {
        assertEquals(original.localId, saved.localId); assertEquals(original.serverId, saved.serverId)
        assertEquals(original.time, saved.time); assertEquals(original.utcOffset, saved.utcOffset)
        assertEquals(original.pictureIdsJson, saved.pictureIdsJson); assertEquals(original.geoLocationJson, saved.geoLocationJson)
        assertEquals(original.hideAmount, saved.hideAmount); assertEquals(original.tagIdsJson, saved.tagIdsJson)
    }

    @Test fun oneReceiptAckPreservesOtherRowsQueuesAndAccountBalances() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val expected = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77)
            val other = pending(10, "other-original")
            database.dao().insertLedgerWrite(expected); database.dao().insertLedgerWrite(other)
            database.dao().upsertLedgerTransactions(listOf(cache(99, 9), cache(88, 10)))
            val local = row(); database.dao().upsertTransaction(local)
            assertTrue(repo.acknowledgeOriginalLedgerWrite(expected, remote(), { true }))
            assertEquals(listOf(other), repo.ledgerWrites())
            assertEquals(setOf(77L, 99L), repo.observeLedgerTransactions(9).first().map { it.serverId }.toSet())
            assertEquals(listOf(88L), repo.observeLedgerTransactions(10).first().map { it.serverId })
            assertEquals(1790000000000, repo.observeLedgerTransactions(9).first().single { it.serverId == 77L }.time)
            assertEquals(local, repo.originalTransaction(local.localId))
            assertEquals(mapOf(10L to 10000L, 11L to 5000L), repo.observeAccounts().first().associate { it.id to it.balanceMinor })
        } finally { database.close() }
    }

    @Test fun replacementQueueCannotBeDeletedByAnOldReceipt() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false)
            val original = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77)
            val replacement = pending(local = "new-original")
            database.dao().insertLedgerWrite(replacement); database.dao().upsertLedgerTransactions(listOf(cache(99, 9)))
            assertFalse(repo.acknowledgeOriginalLedgerWrite(original, remote(), { true }))
            assertEquals(listOf(replacement), repo.ledgerWrites())
            assertEquals(listOf(99L), repo.observeLedgerTransactions(9).first().map { it.serverId })
        } finally { database.close() }
    }

    @Test fun scopeChangeDuringAckRollsBackTheSingleCacheAndQueueTogether() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false)
            val original = pending().copy(state = LedgerWriteEntity.POSTED, serverId = 77)
            database.dao().insertLedgerWrite(original); database.dao().upsertLedgerTransactions(listOf(cache(99, 9)))
            var checks = 0
            try { repo.acknowledgeOriginalLedgerWrite(original, remote(), { ++checks == 1 }); fail("Scope change must roll back") }
            catch (_: IllegalStateException) { }
            assertEquals(listOf(original), repo.ledgerWrites())
            assertEquals(listOf(99L), repo.observeLedgerTransactions(9).first().map { it.serverId })
        } finally { database.close() }
    }

    @Test fun originalEditUsesFullEntityCasAndPreservesServerTimeAndAssociations() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row(); val replacement = original.copy(comment = "Synthetic newer input", updatedAt = 11)
            database.dao().upsertTransaction(replacement)
            try { repo.saveOriginalTransaction(original, draft(original), { true }); fail("Newer row must win") }
            catch (_: IllegalStateException) { }
            assertEquals(replacement, repo.originalTransaction(original.localId))
            val saved = repo.saveOriginalTransaction(replacement, draft(replacement), { true })
            sameOriginalFields(replacement, saved); assertEquals(456L, saved.sourceAmountMinor); assertEquals(SyncState.PENDING, saved.syncState)
        } finally { database.close() }
    }

    @Test fun originalIncomingEditKeepsZeroSourceAndOnlyTheOriginalDestination() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row(type = 4).copy(sourceAccountId = 0, sourceAmountMinor = 0,
                destinationAccountId = 11, destinationAmountMinor = 456, currency = "CNY")
            database.dao().upsertTransaction(original)
            val saved = repo.saveOriginalTransaction(original, draft(original, 0, direction = TransactionTransferDirection.IN, destinationAmount = 654), { true })
            sameOriginalFields(original, saved); assertEquals(0L, saved.sourceAccountId); assertEquals(0L, saved.sourceAmountMinor)
            assertEquals(11L, saved.destinationAccountId); assertEquals(654L, saved.destinationAmountMinor); assertEquals("CNY", saved.currency)
            val payload = saved.toApiPayload(); assertEquals("0", payload.getString("sourceAccountId")); assertEquals("11", payload.getString("destinationAccountId"))
        } finally { database.close() }
    }

    @Test fun originalOutgoingEditCannotAcquireAnInternalDestinationOrLoseOriginalFields() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row(type = 4).copy(destinationAccountId = null, destinationAmountMinor = 0)
            database.dao().upsertTransaction(original)
            val saved = repo.saveOriginalTransaction(original, draft(original, 789, direction = TransactionTransferDirection.OUT), { true })
            sameOriginalFields(original, saved); assertEquals(10L, saved.sourceAccountId); assertEquals(789L, saved.sourceAmountMinor)
            assertNull(saved.destinationAccountId); assertEquals(0L, saved.destinationAmountMinor); assertEquals("USD", saved.currency)
            val payload = saved.toApiPayload(); assertEquals("10", payload.getString("sourceAccountId")); assertEquals("0", payload.getString("destinationAccountId"))
        } finally { database.close() }
    }

    @Test fun incomingWithoutExplicitDirectionIsRejectedWithoutSelectingTheFirstAccount() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row(type = 4).copy(sourceAccountId = 0, sourceAmountMinor = 0, destinationAccountId = 11, destinationAmountMinor = 456)
            database.dao().upsertTransaction(original)
            try { repo.saveOriginalTransaction(original, draft(original, 0, destinationAmount = 654), { true }); fail("Must not infer a new direction") }
            catch (_: IllegalArgumentException) { }
            assertEquals(original, repo.originalTransaction(original.localId))
            assertEquals(0L, repo.originalTransaction(original.localId)?.sourceAccountId)
        } finally { database.close() }
    }

    @Test fun conflictCasAndLegacyBarrierCannotBeBypassedByTheRecoveryEditor() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row().copy(syncState = SyncState.CONFLICT)
            val conflict = SyncConflictEntity(original.localId, original.serverId!!, original.comment, 123,
                "Synthetic remote", 234, remote(original.serverId!!, 0).copy(comment = "Synthetic remote", sourceAmountMinor = 234).toJson())
            database.dao().upsertTransaction(original); database.dao().upsertConflict(conflict.copy(createdAt = conflict.createdAt + 1))
            try { repo.resolveOriginalConflict(original, conflict, true, { true }); fail("A replacement conflict is not authorized") }
            catch (_: IllegalStateException) { }
            assertEquals(original, repo.originalTransaction(original.localId))
            val legacy = row("legacy-original").copy(reviewItemId = 71, deleted = true)
            database.dao().upsertTransaction(legacy)
            try { repo.saveOriginalTransaction(legacy, draft(legacy), { true }); fail("Legacy deletion cannot become a new form") }
            catch (_: IllegalArgumentException) { }
            assertEquals(legacy, repo.originalTransaction(legacy.localId)); assertTrue(database.dao().hasLegacyAIReviewTransaction(71))
        } finally { database.close() }
    }

    @Test fun lostScopeDuringOriginalSaveRollsBackAllFinancialFields() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row(); database.dao().upsertTransaction(original)
            var checks = 0
            try { repo.saveOriginalTransaction(original, draft(original), { ++checks == 1 }); fail("A stale scope must not commit") }
            catch (_: IllegalStateException) { }
            assertEquals(original, repo.originalTransaction(original.localId))
            assertEquals(listOf(original), repo.ledgerPendingTransactions())
        } finally { database.close() }
    }

    @Test fun aKnownReceiptCannotBeReplacedByAnotherServerId() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); val original = pending()
            database.dao().insertLedgerWrite(original)
            val posted = requireNotNull(repo.markOriginalLedgerWritePosted(original, 77))
            assertEquals(LedgerWriteEntity.POSTED, posted.state); assertEquals(77L, posted.serverId)
            assertNull(repo.markOriginalLedgerWritePosted(original, 88))
            assertEquals(posted, repo.originalLedgerWrite(9))
        } finally { database.close() }
    }

    @Test fun anExplicitOriginalCategoryCannotSilentlyBecomeUnmappedDuringSave() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row(); database.dao().upsertTransaction(original)
            database.dao().upsertCategories(listOf(CategoryEntity(20, "Synthetic expense", 1, 2, hidden = true)))
            try { repo.saveOriginalTransaction(original, draft(original), { true }); fail("The original category must be explicitly resolved") }
            catch (_: IllegalArgumentException) { }
            assertEquals(original, repo.originalTransaction(original.localId))
        } finally { database.close() }
    }

    @Test fun negativeRefundAndOriginalWalletMappingRemainExplicit() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val original = row().copy(sourceAmountMinor = -123)
            database.dao().upsertTransaction(original)
            val saved = repo.saveOriginalTransaction(original, draft(original, -456), { true })
            assertEquals(-456L, saved.sourceAmountMinor); sameOriginalFields(original, saved)
            val wallet = row("wallet-original").copy(serverId = null, sourceAccountId = TransactionEntity.LOCAL_ACCOUNT_ID)
            database.dao().upsertTransaction(wallet)
            val unmapped = repo.saveOriginalTransaction(wallet, draft(wallet), { true })
            assertEquals(TransactionEntity.LOCAL_ACCOUNT_ID, unmapped.sourceAccountId)
            assertNotEquals(10L, unmapped.sourceAccountId)
        } finally { database.close() }
    }

    @Test fun balanceAdjustmentTargetAndRemotelyDeletedConflictCannotBeOrdinaryNewRows() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); resources(database)
            val balance = row("balance-original", type = 1).copy(categoryId = null, sourceAmountMinor = 0)
            database.dao().upsertTransaction(balance)
            try { repo.saveOriginalTransaction(balance, draft(balance, 100), { true }); fail("Target zero must not become a delta") }
            catch (_: IllegalArgumentException) { }
            assertEquals(balance, repo.originalTransaction(balance.localId))
            val original = row().copy(syncState = SyncState.CONFLICT)
            val conflict = SyncConflictEntity(original.localId, original.serverId!!, original.comment, 123,
                "Synthetic absence", 0, TransactionRepository.REMOTE_DELETION_MARKER)
            database.dao().upsertTransaction(original); database.dao().upsertConflict(conflict)
            try { repo.resolveOriginalConflict(original, conflict, false, { true }); fail("Absence must not create a new key") }
            catch (_: IllegalStateException) { }
            assertEquals(original, repo.originalTransaction(original.localId)); assertEquals(conflict, repo.originalConflict(original.localId))
            assertEquals(setOf(balance.localId, original.localId), repo.ledgerPendingTransactions().map { it.localId }.toSet())
        } finally { database.close() }
    }

    @Test fun lateReceiptBindsOnlyTheUploadedSnapshotAndPreservesAnEditMadeDuringHttp() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false)
            val uploaded = row().copy(serverId = null)
            val edited = uploaded.copy(comment = "Synthetic changed during HTTP", sourceAmountMinor = -456, updatedAt = 11)
            database.dao().upsertTransaction(edited)
            repo.acknowledgeOriginalLocalSnapshot(uploaded, remote(77, 0))
            val retained = requireNotNull(repo.originalTransaction(uploaded.localId))
            assertEquals(77L, retained.serverId); assertEquals(-456L, retained.sourceAmountMinor)
            assertEquals(edited.comment, retained.comment); assertEquals(SyncState.PENDING, retained.syncState)
            assertEquals(uploaded.syncSnapshot(), retained.syncedSnapshotJson)
            assertEquals(uploaded.pictureIdsJson, retained.pictureIdsJson); assertEquals(uploaded.geoLocationJson, retained.geoLocationJson)
        } finally { database.close() }
    }

    @Test fun lateReceiptCannotOverwriteAReplacementIdentityOrLegacyAssociation() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); val uploaded = row().copy(serverId = null)
            for (replacement in listOf(uploaded.copy(serverId = 88), uploaded.copy(time = uploaded.time + 1000),
                uploaded.copy(reviewItemId = 71, deleted = true))) {
                database.dao().upsertTransaction(replacement)
                try { repo.acknowledgeOriginalLocalSnapshot(uploaded, remote(77, 0)); fail("Replacement must not inherit the receipt") }
                catch (_: IllegalStateException) { }
                assertEquals(replacement, repo.originalTransaction(uploaded.localId))
            }
        } finally { database.close() }
    }

    @Test fun readOnlyReceiptBindingDoesNotInventABaselineForTheEditedCurrentRow() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false)
            val edited = row().copy(serverId = null, comment = "Synthetic edit after lost create reply", sourceAmountMinor = -456)
            database.dao().upsertTransaction(edited)
            val bound = repo.bindOriginalTransactionReceipt(edited, 77, { true })
            assertEquals(edited.copy(serverId = 77), bound); assertEquals("", bound.syncedSnapshotJson)
            val merged = repo.mergeOriginalTransaction(bound, remote(77, 0), { true })
            assertEquals(77L, merged.serverId); assertEquals(edited.localId, merged.localId)
            assertEquals(edited.comment, merged.comment); assertEquals(-456L, merged.sourceAmountMinor)
            assertEquals(SyncState.CONFLICT, merged.syncState); assertNotNull(repo.originalConflict(edited.localId))
            assertEquals(1, repo.ledgerPendingTransactions().size)
        } finally { database.close() }
    }

    @Test fun readOnlyReceiptBindingRejectsAReplacementAndAnAlreadyAssociatedServerId() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false); val expected = row().copy(serverId = null)
            val newer = expected.copy(comment = "Synthetic newer row", updatedAt = 11)
            database.dao().upsertTransaction(newer)
            try { repo.bindOriginalTransactionReceipt(expected, 77, { true }); fail("The exact mutable row must still match") }
            catch (_: IllegalStateException) { }
            assertEquals(newer, repo.originalTransaction(expected.localId))
            database.dao().upsertTransaction(row("other-associated").copy(serverId = 77))
            try { repo.bindOriginalTransactionReceipt(newer, 77, { true }); fail("Must not merge two local identities") }
            catch (_: IllegalStateException) { }
            assertEquals(newer, repo.originalTransaction(expected.localId))
        } finally { database.close() }
    }

    @Test fun keepingLocalAuthorizesOnlyTheRemoteConflictVersionActuallyShown() = runBlocking<Unit> {
        val database = db()
        try {
            val repo = TransactionRepository(context, database, false)
            val local = row().copy(syncState = SyncState.PENDING)
            val seen = remote(requireNotNull(local.serverId), 0).copy(comment = "Synthetic server A")
            val approved = SyncConflictEntity(local.localId, seen.id, local.comment, local.sourceAmountMinor,
                seen.comment, seen.sourceAmountMinor, seen.toJson(), resolution = ConflictResolution.KEEP_LOCAL)
            database.dao().upsertTransaction(local); database.dao().upsertConflict(approved)
            assertEquals(local, repo.mergeOriginalTransaction(local, seen, { true }))
            assertEquals(approved, repo.originalConflict(local.localId))
            val unseen = seen.copy(comment = "Synthetic server B", sourceAmountMinor = 456)
            val conflicted = repo.mergeOriginalTransaction(local, unseen, { true })
            assertEquals(SyncState.CONFLICT, conflicted.syncState); assertEquals(local.comment, conflicted.comment)
            assertEquals(local.sourceAmountMinor, conflicted.sourceAmountMinor)
            val renewed = requireNotNull(repo.originalConflict(local.localId))
            assertEquals("", renewed.resolution); assertEquals(unseen.toJson(), renewed.remoteEntityJson)
        } finally { database.close() }
    }
}
