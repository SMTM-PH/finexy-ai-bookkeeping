package com.finexy.mobile.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Controller semantics only. Production local Room/adapter/navigation still need native acceptance. */
class LedgerPendingLocalModeTest {
    @Test fun localOwnerCanVerifyOriginalRowWhileOnlineSourcesStayUnavailable() = runBlocking {
        val context = LedgerPendingContext("qa", "local://device", "local", "qa-local.db", 0, 1, localMode = true)
        val target = LedgerPendingTarget.Transaction(0, "original-row", 1, "ab".repeat(32))
        var roleReads = 0
        val transport = object : LedgerPendingTransport {
            override suspend fun role(context: LedgerPendingContext): Int { roleReads++; return 1 }
            override suspend fun read(context: LedgerPendingContext, source: LedgerPendingSource): List<LedgerPendingRecord> {
                if (source.remote) throw LedgerPendingUnavailableException()
                return if (source == LedgerPendingSource.LOCAL_TRANSACTIONS) listOf(LedgerPendingRecord(target, LedgerPendingKind.LOCAL_SYNC)) else emptyList()
            }
        }
        val controller = LedgerPendingController(transport) { context }
        controller.bind(context); controller.refresh()
        assertEquals(1, controller.state.value.knownCount); assertNull(controller.state.value.exactTotal)
        assertEquals(LedgerPendingAvailability.UNAVAILABLE, controller.state.value.sources.getValue(LedgerPendingSource.AI_QUEUE).availability)
        val verified = controller.verify(target)
        assertNotNull(verified); assertTrue(verified!!.isCurrent()); assertEquals(target, verified.target)
        assertEquals(2, roleReads)
        controller.close(); assertFalse(verified.isCurrent())
    }
}
