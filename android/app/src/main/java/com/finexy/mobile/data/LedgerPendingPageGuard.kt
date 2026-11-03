package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Original navigation metadata only; this is not a saved editor or a write command. */
internal data class LedgerPendingPageLocation(
    val context: LedgerPendingContext,
    val target: LedgerPendingTarget
) {
    init { require(context.ledgerId == target.ledgerId) { "待处理定位与原账本不一致" } }
}

internal enum class LedgerPendingPageStatus { CHECKING, READY, FAILED, RELEASED, CLOSED }

/**
 * Pins a destination to the session and locator selected in the pending overview.
 * validate must only read live access and the original locator. A page calls release
 * only after an explicit recovery/edit has succeeded, allowing its own new revision
 * while retaining the original session boundary. No financial DTOs or errors enter state.
 *
 * The owner must expose its monotonic sessionRevision, observe changes synchronously,
 * and invalidate/close on leaving. An observed unknown or different context permanently
 * invalidates this instance; even equal fields cannot reactivate it afterwards.
 */
internal class LedgerPendingPageGuard(
    val location: LedgerPendingPageLocation,
    private val currentContext: () -> LedgerPendingContext?,
    private val validate: suspend (LedgerPendingContext, LedgerPendingTarget) -> Boolean
) {
    private data class Ticket(val generation: Long, val released: Boolean)
    private val monitor = Any()
    private val mutable = MutableStateFlow(LedgerPendingPageStatus.CHECKING)
    val state: StateFlow<LedgerPendingPageStatus> = mutable.asStateFlow()
    private var generation = 0L
    private var invalidated = false
    private var closed = false
    private var released = false

    private fun invalidateLocked() {
        if (closed) return
        generation++
        invalidated = true
        mutable.value = LedgerPendingPageStatus.FAILED
    }

    private fun scopeCurrentLocked(): Boolean {
        if (closed || invalidated) return false
        if (runCatching(currentContext).getOrNull() != location.context) {
            invalidateLocked()
            return false
        }
        return true
    }

    fun isCurrent(): Boolean = synchronized(monitor) {
        scopeCurrentLocked() && mutable.value in setOf(LedgerPendingPageStatus.READY, LedgerPendingPageStatus.RELEASED)
    }

    /** A failed original-locator read can be explicitly retried in the same live scope.
     * A released page verifies only scope: its original draft revision may now differ. */
    suspend fun verify(): Boolean {
        val ticket = synchronized(monitor) {
            if (!scopeCurrentLocked()) return false
            Ticket(++generation, released).also { mutable.value = LedgerPendingPageStatus.CHECKING }
        }
        try {
            currentCoroutineContext().ensureActive()
            val valid = ticket.released || validate(location.context, location.target)
            currentCoroutineContext().ensureActive()
            return synchronized(monitor) {
                if (!scopeCurrentLocked() || generation != ticket.generation) return@synchronized false
                mutable.value = if (!valid) LedgerPendingPageStatus.FAILED
                    else if (ticket.released) LedgerPendingPageStatus.RELEASED else LedgerPendingPageStatus.READY
                valid
            }
        } catch (cancelled: CancellationException) {
            fail(ticket)
            throw cancelled
        } catch (_: Exception) {
            fail(ticket)
            return false
        }
    }

    private fun fail(ticket: Ticket) = synchronized(monitor) {
        if (!scopeCurrentLocked() || generation != ticket.generation) return@synchronized
        generation++
        mutable.value = LedgerPendingPageStatus.FAILED
    }

    /** Releases only the original locator pin; this never releases the account/ledger pin. */
    fun release(): Boolean = synchronized(monitor) {
        if (!scopeCurrentLocked() || mutable.value !in setOf(LedgerPendingPageStatus.READY, LedgerPendingPageStatus.RELEASED)) return@synchronized false
        generation++
        released = true
        mutable.value = LedgerPendingPageStatus.RELEASED
        true
    }

    fun invalidate() = synchronized(monitor) { invalidateLocked() }

    fun close() = synchronized(monitor) {
        generation++
        closed = true
        invalidated = true
        mutable.value = LedgerPendingPageStatus.CLOSED
    }
}
