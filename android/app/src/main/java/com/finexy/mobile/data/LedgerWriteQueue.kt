package com.finexy.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.CancellationException

/** A confirmed write, not an unconfirmed form draft. One immutable request per ledger. */
@Entity(tableName = "ledger_write_queue")
data class LedgerWriteEntity(
    @PrimaryKey val ledgerId: Long,
    val localId: String,
    val payloadJson: String,
    val state: String = PENDING,
    val serverId: Long? = null,
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val PENDING = "pending"
        const val POSTED = "posted"
    }
}

interface LedgerWriteStore {
    suspend fun ledgerWrites(): List<LedgerWriteEntity>
    suspend fun markLedgerWritePosted(write: LedgerWriteEntity, serverId: Long)
    suspend fun markLedgerWriteFailed(write: LedgerWriteEntity, message: String)
}

/** The same persisted request/key survives lost responses and process recreation. */
class LedgerWriteDelivery(
    private val store: LedgerWriteStore,
    private val upload: suspend (LedgerWriteEntity) -> Long
) {
    suspend fun push(): LedgerWriteDeliveryResult {
        var pushed = 0
        val errors = mutableListOf<Exception>()
        for (write in store.ledgerWrites().filter { it.state == LedgerWriteEntity.PENDING }) {
            try {
                val serverId = upload(write)
                require(serverId > 0) { "保存响应缺少流水 ID" }
                // Persist before any unrelated reads. A failed refresh must never send this again.
                store.markLedgerWritePosted(write, serverId)
                pushed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                store.markLedgerWriteFailed(write, error.message ?: "写入结果待确认，请重试")
                errors += error
            }
        }
        return LedgerWriteDeliveryResult(pushed, errors)
    }
}

data class LedgerWriteDeliveryResult(val pushed: Int, val errors: List<Exception>)
