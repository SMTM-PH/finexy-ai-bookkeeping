package com.finexy.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.finexy.mobile.data.*

/** Retains only navigation metadata across rotation; never saves the location to a Bundle. */
internal class LedgerPendingPageAccessModel : ViewModel() {
    private var bound: LedgerPendingPageLocation? = null
    private var guard: LedgerPendingPageGuard? = null

    fun bind(location: LedgerPendingPageLocation, create: () -> LedgerPendingPageGuard): LedgerPendingPageGuard {
        val previous = guard
        if (bound == location && previous != null && previous.state.value != LedgerPendingPageStatus.CLOSED) return previous
        previous?.close()
        bound = location
        return create().also { guard = it }
    }

    override fun onCleared() { guard?.close() }
}

private tailrec fun Context.pendingPageActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.pendingPageActivity()
    else -> null
}

/** Call verify before initialization and again after loading the exact original entity. */
@Composable
internal fun rememberLedgerPendingPageGuard(
    location: LedgerPendingPageLocation?, store: SecureStore, repository: TransactionRepository,
    localMode: Boolean, ledgerId: Long
): LedgerPendingPageGuard? {
    if (location == null) return null
    val context = LocalContext.current
    val activity = context.pendingPageActivity()
    val model: LedgerPendingPageAccessModel = viewModel(key = "pending-page:${store.namespace}:$ledgerId")
    val guard = model.bind(location) {
        val transport = FinexyLedgerPendingTransport(context.applicationContext, store, repository)
        LedgerPendingPageGuard(location, {
            if (location.context.ledgerId != ledgerId || location.context.localMode != localMode) null
            else transport.context(ledgerId)
        }, { expected, target ->
            val role = transport.role(expected)
            role in 1..4 && transport.contains(expected, target)
        })
    }
    DisposableEffect(guard, store) {
        val observation = store.observeSessionChanges(guard::invalidate)
        onDispose {
            observation.close()
            if (activity?.isChangingConfigurations != true) guard.close()
        }
    }
    return guard
}
