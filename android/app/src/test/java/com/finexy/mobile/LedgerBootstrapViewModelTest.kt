package com.finexy.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Owner activation and scheduler intent boundaries; no WorkManager or migration execution claimed. */
class LedgerBootstrapViewModelTest {
    private suspend fun settled(condition: () -> Boolean) {
        repeat(100) { if (condition()) return; yield() }
        assertTrue("Bootstrap did not reach expected state", condition())
    }

    @Test fun coldRestoredChildPageDoesNotStartAndSameActivationNavigationOrRotationRunsOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>(); var attempts = 0; var schedules = 0
        val model = LedgerBootstrapViewModel({ current -> attempts++; gate.await(); if (current()) schedules++ }, scope)
        val owner = LedgerBootstrapActivationModel()
        try {
            owner.activate(model) // A cold restored Source page has not shown LedgerContent.
            assertEquals(0, attempts)
            model.contentEntered(); assertEquals(1, attempts)
            model.contentLeft(); owner.activate(model); model.contentEntered(); model.start()
            assertEquals(1, attempts); assertEquals(0, schedules)
            gate.complete(Unit); settled { model.state.value.status == LedgerBootstrapStatus.READY }
            model.contentLeft(); model.contentEntered(); owner.activate(model)
            assertEquals(1, attempts); assertEquals(1, schedules)
        } finally { gate.complete(Unit); owner.deactivate(model); scope.cancel() }
    }

    @Test fun ownerABAReactivatesOriginalModelWithoutStartingWhileOnlyItsChildPageIsShown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attemptsA = 0; var attemptsB = 0
        val modelA = LedgerBootstrapViewModel({ current -> if (current()) attemptsA++ }, scope)
        val modelB = LedgerBootstrapViewModel({ current -> if (current()) attemptsB++ }, scope)
        val owner = LedgerBootstrapActivationModel()
        try {
            owner.activate(modelA); modelA.contentEntered()
            settled { modelA.state.value.status == LedgerBootstrapStatus.READY }
            modelA.contentLeft(); owner.activate(modelB); modelB.contentEntered()
            settled { modelB.state.value.status == LedgerBootstrapStatus.READY }
            modelB.contentLeft(); owner.activate(modelA)
            assertEquals(1, attemptsA); assertEquals(1, attemptsB)
            assertEquals(LedgerBootstrapStatus.NOT_STARTED, modelA.state.value.status)
            modelA.contentEntered(); settled { modelA.state.value.status == LedgerBootstrapStatus.READY }
            assertEquals(2, attemptsA)
        } finally { owner.deactivate(modelA); owner.deactivate(modelB); scope.cancel() }
    }

    @Test fun oldMigrationCannotScheduleAfterActivationABAOrOverwriteNewReadyState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val oldMigration = CompletableDeferred<Unit>(); var attempts = 0; var schedules = 0
        var oldCurrent: (() -> Boolean)? = null
        val model = LedgerBootstrapViewModel({ current ->
            attempts++
            if (attempts == 1) { oldCurrent = current; withContext(NonCancellable) { oldMigration.await() } }
            if (current()) schedules++
        }, scope)
        val owner = LedgerBootstrapActivationModel()
        val other = LedgerBootstrapViewModel({}, scope)
        try {
            owner.activate(model); model.contentEntered(); assertEquals(1, attempts)
            model.contentLeft(); owner.activate(other); owner.activate(model)
            assertFalse(oldCurrent!!.invoke()) // Scope fields can be equal again; activation is different.
            model.contentEntered(); settled { model.state.value.status == LedgerBootstrapStatus.READY }
            assertEquals(2, attempts); assertEquals(1, schedules)
            oldMigration.complete(Unit); yield()
            assertEquals(1, schedules); assertEquals(LedgerBootstrapStatus.READY, model.state.value.status)
        } finally { oldMigration.complete(Unit); owner.deactivate(model); scope.cancel() }
    }

    @Test fun failedAttemptStaysVisibleAcrossNavigationUntilExplicitRetryAndNeverLeaksException() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); var attempts = 0
        val model = LedgerBootstrapViewModel({ _ -> attempts++; if (attempts == 1) throw IOException("synthetic-private-migration-content") }, scope)
        try {
            model.activate(); model.contentEntered(); settled { model.state.value.status == LedgerBootstrapStatus.FAILED }
            assertFalse(model.state.value.toString().contains("synthetic-private-migration-content"))
            assertFalse(model.state.value.failure!!.contains("synthetic-private-migration-content"))
            model.contentLeft(); model.contentEntered(); model.start()
            assertEquals(1, attempts); assertEquals(LedgerBootstrapStatus.FAILED, model.state.value.status)
            model.retry(); settled { model.state.value.status == LedgerBootstrapStatus.READY }
            assertEquals(2, attempts); assertTrue(model.prepareManualSync()); assertEquals(2, attempts)
        } finally { model.deactivate(); scope.cancel() }
    }

    @Test fun explicitManualSyncWaitsForTheSameInFlightInitialization() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val migration = CompletableDeferred<Unit>(); var attempts = 0
        val model = LedgerBootstrapViewModel({ _ -> attempts++; migration.await() }, scope)
        try {
            model.activate(); model.contentEntered()
            val manual = async { model.prepareManualSync() }; yield()
            assertEquals(1, attempts); assertFalse(manual.isCompleted)
            migration.complete(Unit); assertTrue(manual.await()); assertEquals(1, attempts)
            model.deactivate(); assertFalse(model.prepareManualSync())
        } finally { migration.complete(Unit); model.deactivate(); scope.cancel() }
    }

    @Test fun manualStatusAwaitRejectsOwnerABAEvenWithEqualScopeAndPendingCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model = LedgerBootstrapViewModel({}, scope); var enqueues = 0
        val statusEntered = CompletableDeferred<Unit>(); val statusComplete = CompletableDeferred<Unit>()
        val cancelledEntered = CompletableDeferred<Unit>(); val cancelledComplete = CompletableDeferred<Unit>()
        try {
            model.activate(); model.contentEntered(); settled { model.state.value.status == LedgerBootstrapStatus.READY }
            val original = model.activationTicket()!!
            val manual = async { model.manualSync(original, { true }, {
                statusEntered.complete(Unit); statusComplete.await()
            }, { enqueues++ }) }
            statusEntered.await()
            model.deactivate(); model.activate()
            settled { model.state.value.status == LedgerBootstrapStatus.READY }
            assertFalse(original())
            statusComplete.complete(Unit); assertFalse(manual.await()); assertEquals(0, enqueues)
            val current = model.activationTicket()!!
            val cancelled = async { model.manualSync(current, { true }, {
                withContext(NonCancellable) { cancelledEntered.complete(Unit); cancelledComplete.await() }
            }, { enqueues++ }) }
            cancelledEntered.await(); cancelled.cancel(); cancelledComplete.complete(Unit); cancelled.join()
            assertEquals(0, enqueues)
        } finally {
            statusComplete.complete(Unit); cancelledComplete.complete(Unit); model.deactivate(); scope.cancel()
        }
    }
}
