package com.finexy.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finexy.mobile.data.FinexyApi
import com.finexy.mobile.data.FinexyLedgerReportTransport
import com.finexy.mobile.data.LedgerReportAvailability
import com.finexy.mobile.data.LedgerReportConfirmation
import com.finexy.mobile.data.LedgerReportContext
import com.finexy.mobile.data.LedgerReportDelivery
import com.finexy.mobile.data.LedgerReportDraftScope
import com.finexy.mobile.data.LedgerReportEnvironmentState
import com.finexy.mobile.data.LedgerReportLifecycle
import com.finexy.mobile.data.LedgerReportPage
import com.finexy.mobile.data.LedgerReportQuery
import com.finexy.mobile.data.LedgerReportState
import com.finexy.mobile.data.LedgerScope
import com.finexy.mobile.data.SecureStore
import com.finexy.mobile.data.SyncActivity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow

/** Memory-only, Activity-retained report owner. ON_STOP is deliberately not a page exit. */
internal class LedgerReportViewModel : ViewModel() {
    @Volatile private var currentEnvironment: AndroidLedgerReportEnvironment? = null
    @Volatile private var selectedLedgerId = 0L
    private val lifecycle = LedgerReportLifecycle(
        { currentEnvironment?.snapshot(selectedLedgerId) ?: LedgerReportEnvironmentState("closed", null, LedgerReportAvailability.OFFLINE, 0, 0, 0) },
        { FinexyLedgerReportTransport(FinexyApi(requireNotNull(currentEnvironment).store)) }
    )
    val state: StateFlow<LedgerReportState> = lifecycle.state
    val contextEpoch: StateFlow<Long> = lifecycle.contextEpoch
    val draftScope: StateFlow<LedgerReportDraftScope> = lifecycle.draftScope

    fun enter(context: Context, store: SecureStore, ledgerId: Long) {
        require(ledgerId >= 0)
        if (currentEnvironment?.store?.namespace != store.namespace) {
            lifecycle.leave()
            currentEnvironment?.close()
            currentEnvironment = AndroidLedgerReportEnvironment(context.applicationContext, store) { lifecycle.refreshEnvironment() }
        }
        selectedLedgerId = ledgerId
        lifecycle.enter()
    }

    fun selectLedger(ledgerId: Long) {
        require(ledgerId >= 0)
        if (selectedLedgerId != ledgerId) { selectedLedgerId = ledgerId; lifecycle.refreshEnvironment() }
    }

    fun leave() {
        lifecycle.leave()
        currentEnvironment?.close()
        currentEnvironment = null
    }
    fun draftChanged() = lifecycle.draftChanged()
    fun captureTicket(): Long? = lifecycle.captureTicket()
    fun captureTicket(delivery: LedgerReportDelivery): Long? = lifecycle.captureTicket(delivery)
    fun isCurrent(ticket: Long): Boolean = lifecycle.isCurrent(ticket)
    fun cancelExport() = lifecycle.cancelExport()

    // The operation belongs to this ViewModel, so an Activity recreation does not cancel it.
    suspend fun load(query: LedgerReportQuery, count: Int = 50): Boolean =
        viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { lifecycle.load(query, count) }.await()
    suspend fun loadNext(): Boolean = viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { lifecycle.loadNext() }.await()
    suspend fun prepareExport(): LedgerReportConfirmation? =
        viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { lifecycle.prepareExport() }.await()
    suspend fun download(shown: LedgerReportConfirmation, includeHiddenAmounts: Boolean = false): LedgerReportDelivery? =
        viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { lifecycle.download(shown, includeHiddenAmounts) }.await()
    suspend fun compare(query: LedgerReportQuery): LedgerReportPage? =
        viewModelScope.async(start = CoroutineStart.UNDISPATCHED) { lifecycle.compare(query) }.await()

    override fun onCleared() {
        lifecycle.close(); currentEnvironment?.close(); currentEnvironment = null
    }
}

internal class AndroidLedgerReportEnvironment(context: Context, val store: SecureStore, private val changed: () -> Unit) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val sessionObservation = store.observeSessionChanges(changed)
    private val syncObservation = SyncActivity.observe(changed)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = changed()
        override fun onUnavailable() = changed()
    }
    private val networkObservationRegistered = runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }.isSuccess

    fun snapshot(ledgerId: Long): LedgerReportEnvironmentState {
        val server = store.get(FinexyApi.KEY_SERVER_URL).orEmpty().trimEnd('/')
        val token = store.get(FinexyApi.KEY_TOKEN)
        val local = store.get("local_mode") == "true"
        val sync = SyncActivity.state
        // A LAN server remains usable without Android's public-Internet validation probe.
        val activeNetwork = if (networkObservationRegistered) runCatching { connectivity.activeNetwork }.getOrNull() else null
        val reportContext = when {
            local -> LedgerReportContext("local", "local", ledgerId)
            server.isBlank() || token.isNullOrBlank() -> null
            else -> LedgerReportContext(server, LedgerScope.identity(server, token, false), ledgerId)
        }
        val availability = when {
            local -> LedgerReportAvailability.LOCAL_ONLY
            sync.running -> LedgerReportAvailability.SYNCHRONIZING
            activeNetwork == null -> LedgerReportAvailability.OFFLINE
            else -> LedgerReportAvailability.ONLINE
        }
        return LedgerReportEnvironmentState(store.namespace, reportContext, availability, store.sessionRevision, sync.revision,
            activeNetwork?.networkHandle ?: 0L, configuredServerUrl = server,
            configuredIdentity = if (local) {
                if (token.isNullOrBlank()) "local-anonymous" else LedgerScope.identity(server, token, false)
            } else null)
    }

    override fun close() {
        sessionObservation.close(); syncObservation.close()
        if (networkObservationRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    }
}
