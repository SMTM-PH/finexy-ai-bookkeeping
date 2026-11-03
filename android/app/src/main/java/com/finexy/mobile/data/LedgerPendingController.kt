package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All methods are reads. Implementations must never enqueue, sync, recognize or acknowledge. */
internal interface LedgerPendingTransport {
    suspend fun role(context: LedgerPendingContext): Int
    suspend fun read(context: LedgerPendingContext, source: LedgerPendingSource): List<LedgerPendingRecord>
    suspend fun contains(context: LedgerPendingContext, target: LedgerPendingTarget): Boolean =
        read(context, target.source()).any { it.target == target }
}

internal class LedgerPendingUnavailableException : IllegalStateException("此来源暂不可核验")
internal class LedgerPendingDeniedException : IllegalStateException("当前账本权限无法确认")

internal fun ledgerPendingFailure(error: Exception): LedgerPendingAvailability = when {
    error is LedgerPendingDeniedException -> LedgerPendingAvailability.DENIED
    error is LedgerPendingUnavailableException -> LedgerPendingAvailability.UNAVAILABLE
    error is ApiException && error.status in setOf(401, 403) -> LedgerPendingAvailability.DENIED
    error is ApiException && error.status in setOf(404, 405, 501) -> LedgerPendingAvailability.UNAVAILABLE
    else -> LedgerPendingAvailability.FAILED
}

/** A navigation permission is short lived, and cannot outlive a bind, refresh, close or session ABA. */
internal class LedgerPendingVerifiedTarget internal constructor(
    val context: LedgerPendingContext, val target: LedgerPendingTarget, private val current: () -> Boolean
) {
    fun isCurrent(): Boolean = current()
    override fun toString(): String = "LedgerPendingVerifiedTarget()"
}

/**
 * Memory-only summary. The UI owner must bind on every selected-ledger change (including ABA),
 * observe session changes synchronously, and close on leaving its containing screen. No DTOs or
 * raw transport errors enter StateFlow. A failed source never publishes an empty result.
 */
internal class LedgerPendingController(
    private val transport: LedgerPendingTransport,
    private val currentContext: () -> LedgerPendingContext?
) {
    private data class Ticket(val generation: Long, val context: LedgerPendingContext)
    private val monitor = Any()
    private val mutable = MutableStateFlow(LedgerPendingSummary())
    val state: StateFlow<LedgerPendingSummary> = mutable.asStateFlow()
    private var context: LedgerPendingContext? = null
    private var generation = 0L
    private var closed = false
    private var localRefreshRequested = false

    fun bind(context: LedgerPendingContext?) = synchronized(monitor) {
        check(!closed)
        generation++; this.context = context; localRefreshRequested = false
        mutable.value = LedgerPendingSummary(context = context,
            sources = if (context == null) emptyMap() else LedgerPendingSource.entries.associateWith { LedgerPendingSourceState() })
    }

    fun invalidate() = synchronized(monitor) {
        if (!closed) { generation++; context = null; localRefreshRequested = false; mutable.value = LedgerPendingSummary() }
    }

    fun close() = synchronized(monitor) {
        generation++; closed = true; context = null; localRefreshRequested = false
        mutable.value = LedgerPendingSummary(closed = true)
    }

    private fun currentLocked(): Boolean {
        if (closed || context == null) return false
        if (runCatching(currentContext).getOrNull() != context) { invalidate(); return false }
        return true
    }
    private fun currentLocked(ticket: Ticket): Boolean = currentLocked() &&
        ticket.generation == generation && ticket.context == context

    private fun publishSource(ticket: Ticket, source: LedgerPendingSource, value: LedgerPendingSourceState) = synchronized(monitor) {
        if (!currentLocked(ticket)) return@synchronized
        val sources = mutable.value.sources + (source to value)
        mutable.value = mutable.value.copy(sources = sources, entries = aggregateLedgerPending(ticket.context, sources))
    }

    private suspend fun read(ticket: Ticket, source: LedgerPendingSource) {
        try {
            val rows = transport.read(ticket.context, source).toList()
            val value = LedgerPendingSourceState(LedgerPendingAvailability.COMPLETE, rows)
            aggregateLedgerPending(ticket.context, mapOf(source to value))
            publishSource(ticket, source, value)
        } catch (cancelled: CancellationException) {
            publishSource(ticket, source, LedgerPendingSourceState()); throw cancelled
        } catch (error: Exception) {
            publishSource(ticket, source, LedgerPendingSourceState(ledgerPendingFailure(error)))
        }
    }

    suspend fun refresh(): Boolean = refresh(localOnly = false)
    /** Suitable for Room notifications: reads only; it never starts SyncEngine or a Worker. */
    suspend fun refreshLocal(): Boolean {
        synchronized(monitor) {
            if (!currentLocked()) return false
            if (mutable.value.busy) { localRefreshRequested = true; return false }
        }
        return refresh(localOnly = true)
    }

    private suspend fun refresh(localOnly: Boolean): Boolean {
        var onlyLocal = localOnly
        while (true) {
            val result = refreshOnce(onlyLocal)
            val again = synchronized(monitor) {
                if (!currentLocked() || mutable.value.busy || !localRefreshRequested) false
                else { localRefreshRequested = false; true }
            }
            if (!again) return result
            onlyLocal = true
        }
    }

    private suspend fun refreshOnce(localOnly: Boolean): Boolean {
        val selected = LedgerPendingSource.entries.filter { !localOnly || !it.remote }
        val ticket = synchronized(monitor) {
            if (!currentLocked()) return false
            if (localOnly && mutable.value.busy) { localRefreshRequested = true; return false }
            val ticket = Ticket(++generation, requireNotNull(context))
            val sources = mutable.value.sources + selected.associateWith { LedgerPendingSourceState(LedgerPendingAvailability.LOADING) }
            mutable.value = mutable.value.copy(role = null, permission = LedgerPendingAvailability.LOADING,
                sources = sources, entries = aggregateLedgerPending(ticket.context, sources), navigationFailure = null)
            ticket
        }
        try {
            coroutineScope {
                selected.filter { !it.remote }.forEach { source -> launch { read(ticket, source) } }
                val role = try {
                    transport.role(ticket.context).also { require(it in 1..4) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val failure = ledgerPendingFailure(error)
                    synchronized(monitor) {
                        if (currentLocked(ticket)) {
                            val sources = mutable.value.sources + LedgerPendingSource.entries.filter { it.remote }
                                .associateWith { LedgerPendingSourceState(failure) }
                            mutable.value = mutable.value.copy(role = null, permission = failure, sources = sources,
                                entries = aggregateLedgerPending(ticket.context, sources))
                        }
                    }
                    null
                }
                if (role != null) {
                    synchronized(monitor) {
                        if (currentLocked(ticket)) mutable.value = mutable.value.copy(role = role, permission = LedgerPendingAvailability.COMPLETE)
                    }
                    selected.filter { it.remote }.forEach { source -> launch { read(ticket, source) } }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(monitor) {
                if (currentLocked(ticket)) {
                    localRefreshRequested = false
                    val sources = mutable.value.sources.mapValues { (_, value) ->
                        if (value.availability == LedgerPendingAvailability.LOADING) LedgerPendingSourceState() else value
                    }
                    mutable.value = mutable.value.copy(permission = LedgerPendingAvailability.NOT_LOADED, role = null,
                        sources = sources, entries = aggregateLedgerPending(ticket.context, sources))
                }
            }
            throw cancelled
        }
        return synchronized(monitor) { currentLocked(ticket) }
    }

    /** Verifies live access plus the original locator; it never performs the pending operation. */
    suspend fun verify(target: LedgerPendingTarget): LedgerPendingVerifiedTarget? {
        val ticket = synchronized(monitor) {
            if (!currentLocked() || mutable.value.busy || target.ledgerId != context?.ledgerId ||
                mutable.value.entries.none { target in it.targets }) return null
            Ticket(++generation, requireNotNull(context))
        }
        var verifiedRole: Int? = null
        try {
            val role = transport.role(ticket.context).also { require(it in 1..4); verifiedRole = it }
            val exists = transport.contains(ticket.context, target)
            return synchronized(monitor) {
                if (!currentLocked(ticket)) return@synchronized null
                if (!exists) {
                    val sources = mutable.value.sources + (target.source() to LedgerPendingSourceState())
                    mutable.value = mutable.value.copy(sources = sources, entries = aggregateLedgerPending(ticket.context, sources),
                        navigationFailure = LedgerPendingAvailability.UNAVAILABLE)
                    return@synchronized null
                }
                mutable.value = mutable.value.copy(role = role, permission = LedgerPendingAvailability.COMPLETE, navigationFailure = null)
                LedgerPendingVerifiedTarget(ticket.context, target) { synchronized(monitor) { currentLocked(ticket) } }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            synchronized(monitor) {
                if (currentLocked(ticket)) {
                    val failure = ledgerPendingFailure(error)
                    // Permission failure clears server data; locator failures affect only that source.
                    val permissionFailed = verifiedRole == null || failure == LedgerPendingAvailability.DENIED
                    val sources = if (permissionFailed) mutable.value.sources +
                        LedgerPendingSource.entries.filter { it.remote }.associateWith { LedgerPendingSourceState(failure) }
                    else mutable.value.sources + (target.source() to LedgerPendingSourceState(failure))
                    mutable.value = mutable.value.copy(role = if (permissionFailed) null else verifiedRole,
                        permission = if (permissionFailed) failure else LedgerPendingAvailability.COMPLETE, sources = sources,
                        entries = aggregateLedgerPending(ticket.context, sources), navigationFailure = failure)
                }
            }
            return null
        }
    }
}
