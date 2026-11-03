package com.finexy.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Stable handoff for target pages: original scope, locator and the proof actually obtained. */
internal data class PendingDestination(
    val navigationId: Long, val location: LedgerPendingPageLocation,
    val initialVerifiedTicket: LedgerPendingVerifiedTarget
) {
    val context: LedgerPendingContext get() = location.context
    val target: LedgerPendingTarget get() = location.target
}

internal enum class PendingDestinationStatus { CHECKING, VERIFIED, UNAVAILABLE }
internal enum class LedgerPendingChange { LOCAL, REMOTE_BASELINE, REMOTE_CACHE }

/** Rotation retains original metadata; process death deliberately cannot restore it from a Bundle. */
internal class LedgerPendingRouteHolder : ViewModel() {
    private val monitor = Any()
    var location by mutableStateOf<LedgerPendingPageLocation?>(null)
        private set
    private var sessionObservation: AutoCloseable? = null

    fun hold(location: LedgerPendingPageLocation, store: SecureStore, partition: String,
        currentContext: (Long) -> LedgerPendingContext?): Boolean = synchronized(monitor) {
        clearLocked()
        if (location.context.namespace != store.namespace || location.context.storagePartition != partition ||
            currentContext(location.target.ledgerId) != location.context) return@synchronized false
        sessionObservation = store.observeSessionChanges(::clear)
        this.location = location
        if (currentContext(location.target.ledgerId) != location.context) { clearLocked(); false } else true
    }
    private fun clearLocked() {
        location = null; sessionObservation?.close(); sessionObservation = null
    }
    fun clear() = synchronized(monitor) { clearLocked() }
    override fun onCleared() { clear() }
}

/** Owns only metadata and read operations; the injected owner is also usable without an Activity. */
internal class LedgerPendingViewModel(
    private val transport: LedgerPendingTransport,
    private val contextForLedger: (Long) -> LedgerPendingContext?,
    private val observeLocal: (Long) -> Flow<LedgerPendingChange>,
    private val observeSession: (() -> Unit) -> AutoCloseable,
    private val awaitRemoteBaseline: Boolean = false,
    private val executionScope: CoroutineScope? = null
) : ViewModel() {
    private val workScope: CoroutineScope get() = executionScope ?: viewModelScope
    private val monitor = Any()
    @Volatile private var active = false
    @Volatile private var ledgerId = 0L
    private var epoch = 0L
    private var environmentDirty = false
    @Volatile private var waitingForBaseline = false
    private var navigationId = 0L
    private var readJob: Job? = null
    private var localJob: Job? = null
    private var navigationJob: Job? = null
    private var sessionObservation: AutoCloseable? = null
    private val controller = LedgerPendingController(transport) { if (active) contextForLedger(ledgerId) else null }
    val state: StateFlow<LedgerPendingSummary> = controller.state
    private val mutableOpening = MutableStateFlow(false)
    val opening: StateFlow<Boolean> = mutableOpening.asStateFlow()
    private val mutablePreparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = mutablePreparing.asStateFlow()
    private val mutableDestination = MutableStateFlow<PendingDestination?>(null)
    val destination: StateFlow<PendingDestination?> = mutableDestination.asStateFlow()
    private val mutableDestinationStatus = MutableStateFlow(PendingDestinationStatus.CHECKING)
    val destinationStatus: StateFlow<PendingDestinationStatus> = mutableDestinationStatus.asStateFlow()

    fun enter(selectedLedgerId: Long) {
        require(selectedLedgerId >= 0)
        val ticket = synchronized(monitor) {
            if (active && !environmentDirty && ledgerId == selectedLedgerId && state.value.context == contextForLedger(selectedLedgerId)) return
            stopLocked()
            active = true; ledgerId = selectedLedgerId; environmentDirty = false; waitingForBaseline = awaitRemoteBaseline
            sessionObservation = observeSession(::sessionChanged)
            controller.bind(contextForLedger(ledgerId))
            if (state.value.context == null) waitingForBaseline = false
            mutablePreparing.value = waitingForBaseline
            ++epoch
        }
        synchronized(monitor) {
            if (active && epoch == ticket && state.value.context != null) localJob = workScope.launch {
                try { observeLocal(selectedLedgerId).collect { change ->
                    if (isActive(ticket)) when (change) {
                        LedgerPendingChange.REMOTE_BASELINE -> { synchronized(monitor) { waitingForBaseline = false; mutablePreparing.value = false }; refresh() }
                        LedgerPendingChange.REMOTE_CACHE -> remoteCacheChanged(ticket)
                        LedgerPendingChange.LOCAL -> if (!waitingForBaseline) controller.refreshLocal()
                    }
                } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (isActive(ticket)) {
                        synchronized(monitor) { waitingForBaseline = false; mutablePreparing.value = false; environmentDirty = true }
                        remoteCacheChanged(ticket)
                    }
                }
            }
        }
        refresh()
    }

    /** Called synchronously by the ledger switch, before Compose can display the new selection. */
    fun selectLedger(selectedLedgerId: Long) {
        if (active) enter(selectedLedgerId)
        else synchronized(monitor) { ledgerId = selectedLedgerId; controller.invalidate(); clearNavigationLocked() }
    }

    private fun isActive(ticket: Long): Boolean = synchronized(monitor) { active && epoch == ticket }

    private suspend fun remoteCacheChanged(ticket: Long) {
        synchronized(monitor) {
            if (!active || epoch != ticket) return
            readJob?.cancel(); readJob = null; navigationJob?.cancel(); clearNavigationLocked()
            // A new cache snapshot disproves the freshness of the old online count, including zero.
            controller.bind(contextForLedger(ledgerId))
        }
        if (isActive(ticket)) controller.refreshLocal()
    }

    private fun sessionChanged() = synchronized(monitor) {
        // SecureStore delivers this synchronously, including changes made by a Worker.
        epoch++; environmentDirty = true; mutablePreparing.value = false; readJob?.cancel(); localJob?.cancel(); navigationJob?.cancel()
        controller.invalidate(); clearNavigationLocked()
    }

    fun refresh() {
        val reenter = synchronized(monitor) { active && (environmentDirty || state.value.context != contextForLedger(ledgerId)) }
        if (reenter) { enter(ledgerId); return }
        synchronized(monitor) {
            if (!active || waitingForBaseline || readJob?.isActive == true) return
            val expected = contextForLedger(ledgerId)
            if (state.value.context != expected) controller.bind(expected)
            val ticket = epoch
            readJob = workScope.launch { if (isActive(ticket)) controller.refresh() }
        }
    }

    /** Resume and Room events inspect local sources only; they never start synchronization. */
    fun refreshLocal() {
        val ticket = synchronized(monitor) { if (!active) return; epoch }
        workScope.launch { if (isActive(ticket) && !waitingForBaseline) controller.refreshLocal() }
    }

    fun open(target: LedgerPendingTarget) {
        synchronized(monitor) {
            if (!active || mutableOpening.value || target.ledgerId != ledgerId) return
            mutableOpening.value = true
            val owner = epoch
            val navigation = ++navigationId
            navigationJob = workScope.launch {
                try {
                    val verified = controller.verify(target)
                    synchronized(monitor) {
                        if (active && epoch == owner && navigationId == navigation && verified?.isCurrent() == true) {
                            mutableDestinationStatus.value = PendingDestinationStatus.CHECKING
                            mutableDestination.value = PendingDestination(navigation, LedgerPendingPageLocation(verified.context, verified.target), verified)
                        }
                    }
                } finally { synchronized(monitor) { if (epoch == owner && navigationId == navigation) mutableOpening.value = false } }
            }
        }
    }

    /** Target-page initialization performs a second read after the asynchronous navigation. */
    fun verifyDestination(shown: PendingDestination) {
        synchronized(monitor) {
            if (!active || mutableDestination.value?.navigationId != shown.navigationId || mutableOpening.value) return
            mutableOpening.value = true; mutableDestinationStatus.value = PendingDestinationStatus.CHECKING
            val owner = epoch
            navigationJob = workScope.launch {
                try {
                    val verified = controller.verify(shown.target)
                    synchronized(monitor) {
                        if (active && epoch == owner && mutableDestination.value?.navigationId == shown.navigationId) {
                            if (verified != null && verified.context == shown.context && verified.isCurrent()) {
                                mutableDestination.value = shown.copy(initialVerifiedTicket = verified)
                                mutableDestinationStatus.value = PendingDestinationStatus.VERIFIED
                            } else mutableDestinationStatus.value = PendingDestinationStatus.UNAVAILABLE
                        }
                    }
                } finally { synchronized(monitor) {
                    if (epoch == owner && navigationId == shown.navigationId && mutableDestination.value?.navigationId == shown.navigationId) mutableOpening.value = false
                } }
            }
        }
    }

    fun closeDestination() {
        synchronized(monitor) {
            navigationJob?.cancel(); readJob?.cancel(); readJob = null; clearNavigationLocked()
            // Rebinding revokes the proof even if the user immediately returns to this group.
            controller.bind(if (active) contextForLedger(ledgerId) else null)
        }
        refresh()
    }

    private fun clearNavigationLocked() {
        navigationId++; mutableOpening.value = false; mutableDestination.value = null
        mutableDestinationStatus.value = PendingDestinationStatus.CHECKING
    }

    private fun stopLocked() {
        active = false; epoch++; waitingForBaseline = false; mutablePreparing.value = false
        readJob?.cancel(); localJob?.cancel(); navigationJob?.cancel()
        readJob = null; localJob = null; navigationJob = null
        sessionObservation?.close(); sessionObservation = null
        controller.invalidate(); clearNavigationLocked()
    }

    fun leave() = synchronized(monitor) { stopLocked() }
    override fun onCleared() { synchronized(monitor) { stopLocked(); controller.close() } }

    companion object {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun factory(context: Context, store: SecureStore, repository: TransactionRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == LedgerPendingViewModel::class.java)
                    val transport = FinexyLedgerPendingTransport(context.applicationContext, store, repository)
                    val model = LedgerPendingViewModel(transport, transport::context, { ledger ->
                        val flows = mutableListOf<Flow<LedgerPendingChange>>(
                            repository.observeTransactions().map { LedgerPendingChange.LOCAL }, repository.observeLedgerWrites().map { LedgerPendingChange.LOCAL },
                            repository.aiReview.observeRequests().map { LedgerPendingChange.LOCAL }, repository.aiReview.observeDrafts(ledger).map { LedgerPendingChange.LOCAL },
                            repository.observeTemplateCreations().map { LedgerPendingChange.LOCAL }, repository.observeTemplateDraft(ledger, 1).map { LedgerPendingChange.LOCAL },
                            repository.observeTemplateDraft(ledger, 2).map { LedgerPendingChange.LOCAL }, repository.observeOccurrences(ledger).map { LedgerPendingChange.LOCAL }
                        )
                        if (ledger == 0L) flows += repository.observeAIReviewItems().map { LedgerPendingChange.LOCAL }
                        val remoteSnapshots = combine(
                            repository.aiReview.observeItems(ledger).map { rows -> rows.filter { it.status == 1 && !it.deleted }.map { it.id }.sorted() }.distinctUntilChanged(),
                            repository.observeOccurrences(ledger).map { rows -> rows.filter { it.status == ScheduledOccurrenceEntity.STATUS_PENDING }
                                .map { it.templateId to it.scheduledUnixTime }.toSet() }.distinctUntilChanged()
                        ) { ai, occurrences -> ai to occurrences }.distinctUntilChanged()
                        flows += flow {
                            var first = true
                            remoteSnapshots.collect {
                                emit(if (first) LedgerPendingChange.REMOTE_BASELINE else LedgerPendingChange.REMOTE_CACHE)
                                first = false
                            }
                        }
                        // Baseline events must not be conflated away before the first online read.
                        merge(*flows.toTypedArray())
                    }, store::observeSessionChanges, awaitRemoteBaseline = true)
                    @Suppress("UNCHECKED_CAST")
                    return model as T
                }
            }
    }
}
