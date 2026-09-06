package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import com.finexy.mobile.data.FinexyApi
import com.finexy.mobile.data.FinexyDatabase
import com.finexy.mobile.data.LedgerScope
import com.finexy.mobile.data.SecureStore
import java.util.UUID

/** Non-exported host for exercising real login/account switching without the user's store. */
class LedgerSwitchQaActivity : PrivacyActivity() {
    val preferencesName: String by lazy { "ledger-switch-ui-${UUID.randomUUID()}" }
    val store: SecureStore by lazy { SecureStore(applicationContext, preferencesName) }
    private val databaseNames = mutableSetOf<String>()

    override fun privacyStore(): SecureStore = store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FinexyTheme(true) { FinexyApp(store, true) {} } }
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
        if (isFinishing) {
            databaseNames.forEach { name ->
                FinexyDatabase.closeInstance(name)
                applicationContext.deleteDatabase(name)
            }
            deleteSharedPreferences(preferencesName)
        }
        super.onDestroy()
    }
}
