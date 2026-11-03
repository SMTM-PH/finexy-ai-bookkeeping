package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException

/** The stored tuple identifies the original ledger and due occurrence, even after a restart. */
data class PeriodicAction(val ledgerId: Long, val templateId: Long, val scheduledUnixTime: Long, val action: Int, val queuedAt: Long) {
    init {
        require(ledgerId >= 0 && templateId > 0 && scheduledUnixTime > 0 && queuedAt > 0)
        require(action in ScheduledOccurrenceEntity.ACTION_CONFIRM..ScheduledOccurrenceEntity.ACTION_RESTORE)
    }
}

interface PeriodicActionStore {
    suspend fun pendingPeriodicActions(): List<PeriodicAction>
    /** Compare the complete uploaded intent before acknowledging it. */
    suspend fun acknowledgePeriodicAction(action: PeriodicAction, transactionId: Long): Boolean
}

class PeriodicActionDelivery(private val store: PeriodicActionStore, private val send: suspend (PeriodicAction) -> Long) {
    suspend fun push(): PeriodicActionDeliveryResult {
        var acknowledged = 0
        val errors = mutableListOf<Exception>()
        for (action in store.pendingPeriodicActions()) {
            try {
                val transactionId = send(action)
                require(if (action.action == ScheduledOccurrenceEntity.ACTION_CONFIRM) transactionId > 0 else transactionId == 0L) { "周期操作回执无效" }
                if (store.acknowledgePeriodicAction(action, transactionId)) acknowledged++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Never replace or discard an uncertain request. Other ledgers can still make progress.
                errors += error
            }
        }
        return PeriodicActionDeliveryResult(acknowledged, errors)
    }
}

data class PeriodicActionDeliveryResult(val acknowledged: Int, val errors: List<Exception>)
