package com.finexy.mobile.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SyncActivityTest {
    @Test fun actualWorkIsVisibleBeforeActionAndClearedAfterSuccessOrFailure() = runBlocking {
        val tracker = SyncActivityTracker()
        val observed = mutableListOf<SyncActivityState>()
        tracker.observe { observed.add(tracker.state) }
        assertEquals("result", tracker.during { assertTrue(tracker.state.running); "result" })
        assertFalse(tracker.state.running)
        try { tracker.during { assertTrue(tracker.state.running); error("synthetic") }; fail() } catch (_: IllegalStateException) { }
        assertEquals(listOf(1, 0, 1, 0), observed.map { it.active })
        assertEquals(listOf(1L, 2L, 3L, 4L), observed.map { it.revision })
    }
    @Test fun cancelledActionReleasesStatusAndCancelledWaitDoesNotStayRunning() = runBlocking {
        val tracker = SyncActivityTracker()
        val started = CompletableDeferred<Unit>()
        val job = launch { tracker.during { started.complete(Unit); awaitCancellation() } }
        started.await(); assertTrue(tracker.state.running)
        job.cancelAndJoin(); assertFalse(tracker.state.running); assertEquals(2L, tracker.state.revision)
    }
    @Test fun nestedActivitiesAndClosedObserversKeepAccurateCounts() = runBlocking {
        val tracker = SyncActivityTracker()
        var calls = 0
        val observer = tracker.observe { calls++ }
        tracker.during {
            tracker.during { assertEquals(2, tracker.state.active) }
            assertEquals(1, tracker.state.active)
        }
        observer.close(); observer.close()
        tracker.during { assertTrue(tracker.state.running) }
        assertEquals(4, calls); assertFalse(tracker.state.running); assertEquals(6L, tracker.state.revision)
    }
    @Test fun failingObserverDoesNotChangeWorkOutcome() = runBlocking {
        val tracker = SyncActivityTracker()
        tracker.observe { error("synthetic listener") }
        assertEquals(7, tracker.during { 7 }); assertFalse(tracker.state.running)
    }
}
