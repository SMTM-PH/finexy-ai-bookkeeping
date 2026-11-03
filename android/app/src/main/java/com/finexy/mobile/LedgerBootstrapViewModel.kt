package com.finexy.mobile

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.finexy.mobile.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class LedgerBootstrapStatus { NOT_STARTED, RUNNING, READY, FAILED }
internal data class LedgerBootstrapState(val status: LedgerBootstrapStatus = LedgerBootstrapStatus.NOT_STARTED) {
    val failure: String? get() = if (status == LedgerBootstrapStatus.FAILED)
        "本机账本初始化或启动排程失败，请重试并核对本机存储与登录归属。已有记录保留。" else null
}

/** One attempt per owner activation; page navigation and rotation retain the current activation. */
internal class LedgerBootstrapViewModel(
    private val initialize: suspend (() -> Boolean) -> Unit,
    private val executionScope: CoroutineScope? = null
) : ViewModel() {
    private val monitor = Any()
    private val mutable = MutableStateFlow(LedgerBootstrapState())
    val state: StateFlow<LedgerBootstrapState> = mutable.asStateFlow()
    private var attempted = false
    private var active = false
    private var contentAttached = false
    private var activation = 0L
    private var running: Deferred<Boolean>? = null

    fun activate() {
        val shouldStart = synchronized(monitor) {
            if (active) false else {
                active = true; activation++; attempted = false; running = null; mutable.value = LedgerBootstrapState()
                contentAttached
            }
        }
        if (shouldStart) start()
    }
    fun deactivate() = synchronized(monitor) {
        if (active) {
            active = false; activation++; running?.cancel(); running = null; attempted = false
            mutable.value = LedgerBootstrapState()
        }
    }
    fun contentEntered() { synchronized(monitor) { contentAttached = true }; start() }
    fun contentLeft() { synchronized(monitor) { contentAttached = false } }

    fun start() { begin(retryFailure = false) }
    fun retry() { begin(retryFailure = true) }
    suspend fun prepareManualSync(): Boolean = begin(retryFailure = true)?.await() == true

    fun activationTicket(): (() -> Boolean)? = synchronized(monitor) {
        if (!active) return@synchronized null
        val ticket = activation
        return@synchronized { synchronized(monitor) { active && activation == ticket } }
    }

    /** Explicit manual intent remains pinned across both initialization and the status write. */
    suspend fun manualSync(ticket: () -> Boolean, scopeCurrent: () -> Boolean,
        markQueued: suspend () -> Unit, enqueue: () -> Unit): Boolean {
        suspend fun current(): Boolean { currentCoroutineContext().ensureActive(); return ticket() && scopeCurrent() }
        if (!current()) return false
        val preparation = synchronized(monitor) { if (ticket()) begin(retryFailure = true) else null } ?: return false
        if (!preparation.await() || !current()) return false
        markQueued()
        if (!current()) return false
        enqueue()
        return true
    }

    private fun begin(retryFailure: Boolean): Deferred<Boolean>? = synchronized(monitor) {
        if (!active) return@synchronized null
        running?.takeIf { it.isActive }?.let { return@synchronized it }
        if (attempted && (!retryFailure || mutable.value.status == LedgerBootstrapStatus.READY)) return@synchronized running
        attempted = true
        val ticket = activation
        fun current() = synchronized(monitor) { active && activation == ticket }
        mutable.value = LedgerBootstrapState(LedgerBootstrapStatus.RUNNING)
        (executionScope ?: viewModelScope).async {
            try {
                currentCoroutineContext().ensureActive()
                initialize(::current)
                currentCoroutineContext().ensureActive()
                synchronized(monitor) {
                    if (!current()) false else { mutable.value = LedgerBootstrapState(LedgerBootstrapStatus.READY); true }
                }
            } catch (cancelled: CancellationException) {
                synchronized(monitor) { if (current()) mutable.value = LedgerBootstrapState(LedgerBootstrapStatus.FAILED) }
                throw cancelled
            } catch (_: Exception) {
                // No migration exception, server response or financial content is published.
                synchronized(monitor) { if (current()) mutable.value = LedgerBootstrapState(LedgerBootstrapStatus.FAILED) }
                false
            }
        }.also { running = it }
    }

    companion object {
        fun factory(context: Context, store: SecureStore, repository: TransactionRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == LedgerBootstrapViewModel::class.java)
                    val appContext = context.applicationContext
                    val partition = repository.aiReview.storagePartition
                    fun current(): LedgerBootstrapScope? = runCatching {
                        val server = store.get(FinexyApi.KEY_SERVER_URL).orEmpty().trimEnd('/')
                        val token = store.get(FinexyApi.KEY_TOKEN)
                        val local = store.get("local_mode") == "true"
                        require(local || server.isNotBlank() && !token.isNullOrBlank())
                        val identity = LedgerScope.identity(server, token, local)
                        require(partition == LedgerScope.databaseName(store, identity))
                        LedgerBootstrapScope(store.namespace, partition, server, identity, local)
                    }.getOrNull()
                    val model = LedgerBootstrapViewModel({ activationCurrent ->
                        val expected = current()
                        check(expected != null && activationCurrent() && current() == expected)
                        repository.migrateLegacyDataIfNeeded()
                        check(activationCurrent() && current() == expected)
                        if (!expected.localMode) {
                            // Input stays pinned to the owner captured before the suspendable migration.
                            SyncScheduler.enqueue(appContext, expected.identity, expected.namespace)
                            val automatic = UserPreferenceStore(store, partition).load().syncPolicy == SyncPolicy.AUTOMATIC
                            check(activationCurrent() && current() == expected)
                            if (automatic) SyncScheduler.schedulePeriodic(appContext, expected.identity, expected.namespace)
                            else SyncScheduler.cancelPeriodic(appContext, expected.identity, expected.namespace)
                        }
                    })
                    @Suppress("UNCHECKED_CAST")
                    return model as T
                }
            }
    }
}

/** Retained owner switch also detects an identity change that occurs during Activity rotation. */
internal class LedgerBootstrapActivationModel : ViewModel() {
    private val monitor = Any()
    private var owner: LedgerBootstrapViewModel? = null
    fun activate(model: LedgerBootstrapViewModel) = synchronized(monitor) {
        if (owner !== model) { owner?.deactivate(); owner = model }
        model.activate()
    }
    fun deactivate(model: LedgerBootstrapViewModel) = synchronized(monitor) {
        if (owner === model) { model.deactivate(); owner = null }
    }
    override fun onCleared() { synchronized(monitor) { owner?.deactivate(); owner = null } }
}

internal tailrec fun Context.ledgerBootstrapActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.ledgerBootstrapActivity()
    else -> null
}

private data class LedgerBootstrapScope(val namespace: String, val partition: String, val server: String,
    val identity: String, val localMode: Boolean)
