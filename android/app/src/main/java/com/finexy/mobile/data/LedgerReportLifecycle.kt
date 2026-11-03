package com.finexy.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** No token, report value or file URI is included in this observable environment metadata. */
internal data class LedgerReportEnvironmentState(
    val namespace: String,
    val context: LedgerReportContext?,
    val availability: LedgerReportAvailability,
    val sessionRevision: Long,
    val syncRevision: Long,
    val networkId: Long,
    val configuredServerUrl: String? = null,
    val configuredIdentity: String? = null
) {
    val draftOwner: LedgerReportDraftOwner? get() = context?.let {
        val local = availability == LedgerReportAvailability.LOCAL_ONLY
        LedgerReportDraftOwner(namespace, if (local) configuredServerUrl ?: it.serverUrl else it.serverUrl,
            if (local) configuredIdentity ?: it.identity else it.identity, it.ledgerId, local)
    }
}

/** Pure lifecycle core, shared by the production ViewModel and deterministic race tests. */
internal class LedgerReportLifecycle(
    private val environment: () -> LedgerReportEnvironmentState,
    private val transportFactory: () -> LedgerReportTransport,
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val monitor = Any()
    @Volatile private var entered = false
    @Volatile private var binding: LedgerReportEnvironmentState? = null
    @Volatile private var transport: LedgerReportTransport? = null
    private var ticket = 0L
    private var comparisonSerial = 0L
    private var pendingDelivery: Pair<Long, LedgerReportDelivery>? = null
    private val mutableEpoch = MutableStateFlow(0L)
    val contextEpoch: StateFlow<Long> = mutableEpoch.asStateFlow()
    private var draftScopeInitialized = false
    private val mutableDraftScope = MutableStateFlow(LedgerReportDraftScope(null, 0))
    val draftScope: StateFlow<LedgerReportDraftScope> = mutableDraftScope.asStateFlow()
    private val controller = LedgerReportController(object : LedgerReportTransport {
        override suspend fun query(context: LedgerReportContext, query: LedgerReportQuery, offset: Int, count: Int, expectedHash: String?) =
            requireNotNull(transport).query(context, query, offset, count, expectedHash)
        override suspend fun preview(context: LedgerReportContext, page: LedgerReportPage) = requireNotNull(transport).preview(context, page)
        override suspend fun download(context: LedgerReportContext, knownPage: LedgerReportPage, preview: LedgerReportPreview,
            confirmed: Boolean, includeHiddenAmounts: Boolean) = requireNotNull(transport).download(context, knownPage, preview, confirmed, includeHiddenAmounts)
    }, {
        val expected = binding
        if (entered && expected != null && runCatching(environment).getOrNull() == expected) expected.context else null
    }, nowSeconds)
    val state: StateFlow<LedgerReportState> = controller.state

    private fun readEnvironment(): LedgerReportEnvironmentState = runCatching(environment).getOrElse {
        LedgerReportEnvironmentState(binding?.namespace ?: "unavailable", null, LedgerReportAvailability.OFFLINE,
            binding?.sessionRevision ?: 0, binding?.syncRevision ?: 0, 0)
    }

    /** Reattaching an unchanged Activity after rotation keeps the memory session. */
    fun enter() = synchronized(monitor) {
        if (!entered) { entered = true; rebind(readEnvironment()) }
        else refreshEnvironmentLocked()
    }

    fun refreshEnvironment() = synchronized(monitor) { if (entered) refreshEnvironmentLocked() }
    private fun refreshEnvironmentLocked() {
        val latest = readEnvironment()
        if (latest != binding) rebind(latest)
    }
    private fun rebind(latest: LedgerReportEnvironmentState) {
        ticket++; comparisonSerial++; pendingDelivery = null
        // Erase the old view before constructing an adapter, even if adapter setup fails.
        controller.bind(null)
        transport = null
        binding = latest
        transport = runCatching(transportFactory).getOrNull()
        controller.bind(latest.context, latest.availability)
        bindDraftOwner(latest.draftOwner)
        mutableEpoch.value++
    }

    private fun bindDraftOwner(owner: LedgerReportDraftOwner?, force: Boolean = false) {
        val current = mutableDraftScope.value
        if (force || !draftScopeInitialized || current.owner != owner) {
            draftScopeInitialized = true
            mutableDraftScope.value = LedgerReportDraftScope(owner, current.revision + 1)
        }
    }

    fun leave() = synchronized(monitor) {
        if (entered) {
            entered = false; ticket++; comparisonSerial++; binding = null; transport = null; pendingDelivery = null
            // Bind null rather than permanently closing: the same Activity ViewModel can re-enter.
            controller.bind(null)
            bindDraftOwner(null, force = true)
            mutableEpoch.value++
        }
    }

    fun close() = synchronized(monitor) {
        entered = false; ticket++; comparisonSerial++; binding = null; transport = null; pendingDelivery = null
        controller.close(); bindDraftOwner(null, force = true); mutableEpoch.value++
    }

    fun draftChanged() = synchronized(monitor) {
        if (!entered) return@synchronized
        refreshEnvironmentLocked()
        ticket++; comparisonSerial++; pendingDelivery = null
        val current = requireNotNull(binding)
        controller.bind(current.context, current.availability)
    }

    fun captureTicket(): Long? = synchronized(monitor) {
        if (!entered) return@synchronized null
        refreshEnvironmentLocked()
        if (binding?.context == null || binding?.availability != LedgerReportAvailability.ONLINE) null else ticket
    }

    fun isCurrent(shownTicket: Long): Boolean = synchronized(monitor) {
        if (!entered) return@synchronized false
        refreshEnvironmentLocked()
        shownTicket == ticket && binding?.context != null && binding?.availability == LedgerReportAvailability.ONLINE
    }

    fun captureTicket(delivery: LedgerReportDelivery): Long? = synchronized(monitor) {
        val pending = pendingDelivery ?: return@synchronized null
        if (pending.second !== delivery || !isCurrent(pending.first)) return@synchronized null
        pendingDelivery = null // The file owner now holds the one-shot delivery; do not retain its CSV bytes here.
        pending.first
    }

    private fun begin(): Long? = synchronized(monitor) {
        if (!entered) return@synchronized null
        refreshEnvironmentLocked()
        ticket++; comparisonSerial++; pendingDelivery = null
        if (binding?.context != null && binding?.availability == LedgerReportAvailability.ONLINE) ticket else null
    }
    suspend fun load(query: LedgerReportQuery, count: Int = 50): Boolean {
        val operation = begin() ?: return false
        return controller.load(query, count) && isCurrent(operation)
    }
    suspend fun loadNext(): Boolean {
        val operation = begin() ?: return false
        return controller.loadNext() && isCurrent(operation)
    }
    suspend fun prepareExport(): LedgerReportConfirmation? {
        val operation = begin() ?: return null
        val result = controller.prepareExport()
        return if (isCurrent(operation)) result else null
    }
    suspend fun download(shown: LedgerReportConfirmation, includeHiddenAmounts: Boolean = false): LedgerReportDelivery? {
        val operation = begin() ?: return null
        val result = controller.download(shown, true, includeHiddenAmounts) ?: return null
        return synchronized(monitor) {
            if (!isCurrent(operation)) null
            else { pendingDelivery = operation to result; result }
        }
    }
    fun cancelExport() = synchronized(monitor) {
        ticket++; comparisonSerial++; pendingDelivery = null
        controller.cancelExport()
    }

    /** Complete aggregates from a strict previous natural month, without replacing the main report. */
    suspend fun compare(query: LedgerReportQuery): LedgerReportPage? {
        val work = synchronized(monitor) {
            val currentTicket = captureTicket() ?: return null
            val currentPage = state.value.report ?: return null
            if (state.value.busy || runCatching { previousFullReportMonth(currentPage.query) }.getOrNull() != query) return null
            val activeTransport = transport ?: return null
            Triple(currentTicket, ++comparisonSerial, requireNotNull(binding).context to activeTransport)
        }
        try {
            val received = work.third.second.query(requireNotNull(work.third.first), query, 0, 50, null)
            val result = LedgerReportContract.page(buildJsonObject { put("success", true); put("result", received.json) }.toString(), query)
            return synchronized(monitor) {
                if (isCurrent(work.first) && work.second == comparisonSerial) result else null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (!isCurrent(work.first)) return null
            throw IllegalStateException("上月报告未能完成，请重新读取后重试。")
        }
    }
}
