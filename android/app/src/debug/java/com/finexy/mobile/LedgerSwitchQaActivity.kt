package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.finexy.mobile.data.FinexyApi
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.LedgerScope
import com.finexy.mobile.data.SecureStore
import java.util.UUID

/** Non-exported host for exercising real login/account switching without the user's store. */
class LedgerSwitchQaActivity : PrivacyActivity() {
    var qaLightTheme by mutableStateOf(true)
    private var restoredNamespace: String? = null
    val preferencesName: String by lazy { restoredNamespace ?: "ledger-switch-ui-${UUID.randomUUID()}" }
    val store: SecureStore by lazy { SecureStore(applicationContext, preferencesName) }
    private val databaseNames = mutableSetOf<String>()
    private var retainRecoveryFixture = false

    override fun privacyStore(): SecureStore = store

    override fun onCreate(savedInstanceState: Bundle?) {
        val recoveryNamespace = savedInstanceState?.getString("qaRecoveryNamespace") ?: intent.getStringExtra(RECOVERY_NAMESPACE)
        if (recoveryNamespace != null) {
            check(packageName == "com.finexy.mobile.qa" && recoveryNamespace.matches(Regex("ledger-write-recovery-[a-f0-9]{16}")))
            retainRecoveryFixture = true
            restoredNamespace = recoveryNamespace
        } else restoredNamespace = savedInstanceState?.getString("qaNamespace")?.also { check(it.startsWith("ledger-switch-ui-")) }
        savedInstanceState?.getStringArrayList("qaDatabases")?.let(databaseNames::addAll)
        qaLightTheme = savedInstanceState?.getBoolean("qaLightTheme", true) ?: true
        super.onCreate(savedInstanceState)
        setContent { FinexyTheme(qaLightTheme) { FinexyApp(store, qaLightTheme) { qaLightTheme = it } } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("qaNamespace", preferencesName)
        outState.putBoolean("qaLightTheme", qaLightTheme)
        if (retainRecoveryFixture) outState.putString("qaRecoveryNamespace", preferencesName)
        outState.putStringArrayList("qaDatabases", ArrayList(databaseNames))
        super.onSaveInstanceState(outState)
    }

    fun currentDatabaseName(): String {
        val identity = LedgerScope.identity(
            store.get(FinexyApi.KEY_SERVER_URL).orEmpty(),
            store.get(FinexyApi.KEY_TOKEN),
            store.get("local_mode") == "true"
        )
        return LedgerScope.databaseName(store, identity).also(databaseNames::add)
    }

    override fun onDestroy() {
        if (isFinishing && !retainRecoveryFixture) {
            databaseNames.forEach { name ->
                FinexyDatabase.closeInstance(name)
                applicationContext.deleteDatabase(name)
            }
            deleteSharedPreferences(preferencesName)
        }
        super.onDestroy()
    }

    companion object {
        // Only the non-exported QA host accepts a bounded fixture namespace; no session injection.
        const val RECOVERY_NAMESPACE = "finexy.qa.recovery.namespace"
    }
}
