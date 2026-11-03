package com.finexy.mobile.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Pure metadata and suspendable read doubles; no Android, transport, database or writes. */
class LedgerPendingPageGuardTest {
    private fun context(ledgerId: Long = 9, revision: Long = 1) = LedgerPendingContext(
        "qa", "https://synthetic.invalid", "original-user", "qa.db", ledgerId, revision)
    private fun target(ledgerId: Long = 9) = LedgerPendingTarget.AIDraft(ledgerId, 0, "original-draft", 3, "ab".repeat(32))
    private fun location(ledgerId: Long = 9) = LedgerPendingPageLocation(context(ledgerId), target(ledgerId))

    @Test fun locationRejectsAnotherLedgerAndKeepsZeroExplicit() {
        assertThrows(IllegalArgumentException::class.java) { LedgerPendingPageLocation(context(9), target(0)) }
        assertThrows(IllegalArgumentException::class.java) { LedgerPendingPageLocation(context(0), target(9)) }
        val original = LedgerPendingPageLocation(context(0), target(0))
        assertEquals(0L, original.context.ledgerId)
        assertEquals(0L, original.target.ledgerId)
    }

    @Test fun readyRequiresSuccessfulReadonlyValidationOfOriginalMetadata() = runBlocking {
        val original = location(9007199254740993L)
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { scope, locator ->
            reads++
            assertEquals(original.context, scope)
            assertEquals(original.target, locator)
            true
        }
        assertEquals(LedgerPendingPageStatus.CHECKING, guard.state.value)
        assertFalse(guard.isCurrent())
        assertFalse(guard.release())
        assertEquals(0, reads)
        assertTrue(guard.verify())
        assertEquals(1, reads)
        assertEquals(LedgerPendingPageStatus.READY, guard.state.value)
        assertTrue(guard.isCurrent())
    }

    @Test fun failedLocatorOrExceptionCanRetryOnlyInTheSameOriginalScope() = runBlocking {
        val original = location()
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ ->
            when (++reads) {
                1 -> false
                2 -> throw IOException("private response must never enter state")
                else -> true
            }
        }
        repeat(2) {
            assertFalse(guard.verify())
            assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
            assertFalse(guard.isCurrent())
            assertFalse(guard.release())
            assertFalse(guard.state.value.toString().contains("private"))
        }
        assertTrue(guard.verify())
        assertEquals(LedgerPendingPageStatus.READY, guard.state.value)
        assertEquals(3, reads)
    }

    @Test fun changedNamespaceServerIdentityPartitionLedgerOrRevisionBlocksBeforeRead() = runBlocking {
        val original = location()
        val changed = listOf(
            original.context.copy(namespace = "different-app"),
            original.context.copy(serverUrl = "https://another.invalid"),
            original.context.copy(identity = "another-user"),
            original.context.copy(storagePartition = "another.db"),
            original.context.copy(ledgerId = 10),
            original.context.copy(sessionRevision = 2)
        )
        for (scope in changed) {
            var current = scope
            var reads = 0
            val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> reads++; true }
            assertFalse(guard.verify())
            assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
            assertEquals(0, reads)
            current = original.context
            assertFalse(guard.verify())
            assertFalse(guard.release())
            assertEquals(0, reads)
        }
    }

    @Test fun switchingToLocalModeDoesNotReuseAnOnlinePersonalLedgerGuard() = runBlocking {
        val original = location(0)
        val local = original.context.copy(serverUrl = "local://device", localMode = true)
        var current = local
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> reads++; true }
        assertFalse(guard.verify())
        current = original.context
        assertFalse(guard.verify())
        assertEquals(0, reads)
    }

    @Test fun unknownContextBeforeVerificationCannotReviveWhenTheOriginalReappears() = runBlocking {
        val original = location()
        var current: LedgerPendingContext? = null
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> reads++; true }
        assertFalse(guard.verify())
        current = original.context
        assertFalse(guard.verify())
        assertFalse(guard.isCurrent())
        assertEquals(0, reads)
    }

    @Test fun contextLookupFailureIsUnknownAndPermanentlyInvalidatesTheGuard() = runBlocking {
        val original = location()
        var failing = true
        var reads = 0
        val guard = LedgerPendingPageGuard(original, {
            if (failing) throw IllegalStateException("private session details")
            original.context
        }) { _, _ -> reads++; true }
        assertFalse(guard.isCurrent())
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
        failing = false
        assertFalse(guard.verify())
        assertEquals(0, reads)
    }

    @Test fun observedLedgerABACannotReactivateAnAlreadyReadyGuard() = runBlocking {
        val original = location()
        var current = original.context
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> reads++; true }
        assertTrue(guard.verify())
        current = current.copy(ledgerId = 10)
        assertFalse(guard.isCurrent())
        current = original.context
        assertFalse(guard.verify())
        assertFalse(guard.isCurrent())
        assertEquals(1, reads)
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
    }

    @Test fun sessionRevisionDetectsABAThatHappensDuringASuspendedRead() = runBlocking {
        val original = location()
        var current = original.context
        val read = CompletableDeferred<Boolean>()
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> reads++; read.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        assertEquals(1, reads)
        current = current.copy(sessionRevision = 3) // A -> B -> A, equal identity but newer session.
        read.complete(true)
        assertFalse(result.await())
        assertFalse(guard.isCurrent())
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
        current = original.context // Even an incorrectly rolled-back owner cannot revive it.
        assertFalse(guard.verify())
        assertEquals(1, reads)
    }

    @Test fun contextChangedWhileValidationIsSuspendedRejectsItsLateSuccess() = runBlocking {
        val original = location()
        val changed = listOf(
            original.context.copy(namespace = "different-app"),
            original.context.copy(serverUrl = "https://another.invalid"),
            original.context.copy(identity = "another-user"),
            original.context.copy(storagePartition = "another.db"),
            original.context.copy(ledgerId = 10)
        )
        for (scope in changed) {
            var current = original.context
            val read = CompletableDeferred<Boolean>()
            var reads = 0
            val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> reads++; read.await() }
            val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
            current = scope
            read.complete(true)
            assertFalse(result.await())
            assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
            current = original.context
            assertFalse(guard.verify())
            assertEquals(1, reads)
        }
    }

    @Test fun unknownContextAfterASuspendedReadCannotPublishReady() = runBlocking {
        val original = location()
        var current: LedgerPendingContext? = original.context
        val read = CompletableDeferred<Boolean>()
        val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> read.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        current = null
        read.complete(true)
        assertFalse(result.await())
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
        current = original.context
        assertFalse(guard.verify())
    }

    @Test fun lateSuccessAfterExplicitInvalidationCannotPublishOrRetry() = runBlocking {
        val original = location()
        val read = CompletableDeferred<Boolean>()
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ -> reads++; read.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        guard.invalidate()
        read.complete(true)
        assertFalse(result.await())
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
        assertFalse(guard.verify())
        assertFalse(guard.release())
        assertEquals(1, reads)
    }

    @Test fun lateSuccessOrFailureCannotOverrideANewerSuccessfulVerification() = runBlocking {
        for (olderThrows in listOf(false, true)) {
            val original = location()
            val olderRead = CompletableDeferred<Boolean>()
            var reads = 0
            val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ ->
                if (++reads == 1) olderRead.await() else true
            }
            val older = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
            assertFalse(guard.isCurrent())
            assertTrue(guard.verify())
            if (olderThrows) olderRead.completeExceptionally(IOException("private late failure")) else olderRead.complete(true)
            assertFalse(older.await())
            assertEquals(LedgerPendingPageStatus.READY, guard.state.value)
            assertTrue(guard.isCurrent())
        }
    }

    @Test fun newerFailedVerificationCannotBeRescuedByAnOlderSuccessfulRead() = runBlocking {
        val original = location()
        val olderRead = CompletableDeferred<Boolean>()
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ ->
            if (++reads == 1) olderRead.await() else false
        }
        val older = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        assertFalse(guard.verify())
        olderRead.complete(true)
        assertFalse(older.await())
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
        assertFalse(guard.isCurrent())
    }

    @Test fun cancellingAnActiveVerificationClearsCheckingAndAllowsExplicitSameScopeRetry() = runBlocking {
        val original = location()
        val read = CompletableDeferred<Boolean>()
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ ->
            if (++reads == 1) read.await() else true
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
        assertFalse(guard.isCurrent())
        assertTrue(guard.verify())
        assertEquals(2, reads)
    }

    @Test fun validatorIgnoringCancellationStillCannotPublishReady() = runBlocking {
        val original = location()
        val read = CompletableDeferred<Boolean>()
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ ->
            withContext(NonCancellable) { read.await() }
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        result.cancel()
        read.complete(true)
        result.join()
        assertTrue(result.isCancelled)
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
        assertFalse(guard.isCurrent())
    }

    @Test fun releaseRequiresAReadyPageAndRemovesOnlyTheOriginalLocatorPin() = runBlocking {
        val original = location()
        var originalStillPresent = true
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ -> reads++; originalStillPresent }
        assertFalse(guard.release())
        assertTrue(guard.verify())
        assertTrue(guard.release())
        assertTrue(guard.release())
        originalStillPresent = false // A deliberate successful edit changed this revision.
        assertTrue(guard.isCurrent())
        assertTrue(guard.verify())
        assertEquals(LedgerPendingPageStatus.RELEASED, guard.state.value)
        assertEquals(1, reads)
    }

    @Test fun releasedPageStillRejectsUnknownOrChangedIdentityAndCannotRevive() = runBlocking {
        for (unknown in listOf(false, true)) {
            val original = location()
            var current: LedgerPendingContext? = original.context
            var reads = 0
            val guard = LedgerPendingPageGuard(original, { current }) { _, _ -> reads++; true }
            assertTrue(guard.verify())
            assertTrue(guard.release())
            current = if (unknown) null else original.context.copy(identity = "another-user")
            assertFalse(guard.isCurrent())
            assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
            current = original.context
            assertFalse(guard.release())
            assertFalse(guard.verify())
            assertEquals(1, reads)
        }
    }

    @Test fun releaseDuringAnUnfinishedOrFailedReadCannotBypassValidation() = runBlocking {
        val original = location()
        val read = CompletableDeferred<Boolean>()
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ -> read.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        assertFalse(guard.release())
        read.complete(false)
        assertFalse(result.await())
        assertFalse(guard.release())
        assertEquals(LedgerPendingPageStatus.FAILED, guard.state.value)
    }

    @Test fun closingIgnoresLateValidationAndAllLaterOperations() = runBlocking {
        val original = location()
        val read = CompletableDeferred<Boolean>()
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ -> reads++; read.await() }
        val result = async(start = CoroutineStart.UNDISPATCHED) { guard.verify() }
        guard.close()
        read.complete(true)
        assertFalse(result.await())
        guard.invalidate()
        guard.close()
        assertEquals(LedgerPendingPageStatus.CLOSED, guard.state.value)
        assertFalse(guard.verify())
        assertFalse(guard.release())
        assertFalse(guard.isCurrent())
        assertEquals(1, reads)
    }

    @Test fun invalidateAlsoPermanentlyClosesAReleasedLocatorBoundary() = runBlocking {
        val original = location()
        var reads = 0
        val guard = LedgerPendingPageGuard(original, { original.context }) { _, _ -> reads++; true }
        assertTrue(guard.verify())
        assertTrue(guard.release())
        guard.invalidate()
        assertFalse(guard.isCurrent())
        assertFalse(guard.release())
        assertFalse(guard.verify())
        assertEquals(1, reads)
    }
}
