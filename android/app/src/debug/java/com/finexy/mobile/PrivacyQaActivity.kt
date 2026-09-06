package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.room.Room
import com.finexy.mobile.data.*
import java.util.UUID

/** Non-exported, debug-only host; every run uses isolated preferences and in-memory Room. */
class PrivacyQaActivity : PrivacyActivity() {
    lateinit var store: SecureStore
    lateinit var database: FinexyDatabase
    val createdDocuments = mutableListOf<android.net.Uri>()
    val preferencesName: String get() = intent.getStringExtra("privacyFixture")!!
    override fun privacyStore(): SecureStore {
        if (!intent.hasExtra("privacyFixture")) intent.putExtra("privacyFixture", "privacy-ui-${UUID.randomUUID()}")
        require(preferencesName.startsWith("privacy-ui-"))
        return SecureStore(applicationContext, preferencesName)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = privacyStore()
        database = Room.inMemoryDatabaseBuilder(this, FinexyDatabase::class.java).build()
        val backup = LedgerBackup(database, "local")
        setContent { FinexyTheme(true) { DataPrivacyScreen(store, backup, "privacy-test.db", { finish() }, { createdDocuments += it }); LockOverlay() } }
    }
    override fun onDestroy() {
        database.close()
        if (isFinishing) deleteSharedPreferences(preferencesName)
        super.onDestroy()
    }
}
