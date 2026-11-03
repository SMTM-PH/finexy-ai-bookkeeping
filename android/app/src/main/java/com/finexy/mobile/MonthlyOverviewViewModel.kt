package com.finexy.mobile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finexy.mobile.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.time.YearMonth
import java.time.ZoneId

/** Reuses the report page's real environment observer; see the one-line visibility
 * patch. Saved state keeps only the chosen month/zone, never results or proofs. */
internal class MonthlyOverviewViewModel : ViewModel() {
    @Volatile private var environment: AndroidLedgerReportEnvironment? = null
    @Volatile private var controller: MonthlyOverviewController? = null
    @Volatile private var ledgerId = 0L
    private var month: YearMonth? = null
    private var zone: ZoneId? = null
    private val mutable = MutableStateFlow(MonthlyOverviewState())
    val state: StateFlow<MonthlyOverviewState> = mutable
    private var collectJob: Job? = null
    private var loadJob: Job? = null

    fun enter(context: Context, store: SecureStore, ledgerId: Long, month: YearMonth, zone: ZoneId) {
        require(ledgerId >= 0)
        val changed = this.ledgerId != ledgerId || this.month != month || this.zone != zone
        this.ledgerId = ledgerId; this.month = month; this.zone = zone
        if (environment?.store?.namespace != store.namespace) {
            loadJob?.cancel(); collectJob?.cancel()
            controller?.close(); environment?.close()
            environment = AndroidLedgerReportEnvironment(context.applicationContext, store) {
                val current = controller
                if (current?.refreshEnvironment() == true) { mutable.value = current.state.value; reload() }
            }
            val api = FinexyApi(store)
            val next = MonthlyOverviewController({ requireNotNull(environment).snapshot(this.ledgerId) },
                FinexyLedgerReportTransport(api), api::getMonthlyBudget)
            controller = next
            collectJob = viewModelScope.launch { next.state.collectLatest { if (controller === next) mutable.value = it } }
            next.bind(month, zone); mutable.value = next.state.value; reload()
        } else if (changed || mutable.value.context == null) {
            controller?.bind(month, zone); mutable.value = controller?.state?.value ?: MonthlyOverviewState(); reload()
        } else if (controller?.refreshEnvironment() == true) { mutable.value = controller?.state?.value ?: MonthlyOverviewState(); reload() }
    }
    fun reload() {
        loadJob?.cancel()
        val captured = controller ?: return
        loadJob = viewModelScope.launch { captured.load() }
    }
    fun leave() {
        loadJob?.cancel(); collectJob?.cancel(); controller?.close(); environment?.close()
        controller = null; environment = null; mutable.value = MonthlyOverviewState()
    }
    override fun onCleared() = leave()
}
