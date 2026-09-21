package com.finexy.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.room.Room
import com.finexy.mobile.data.*

/** Non-exported visual and interaction fixture for exchange-rate UI. */
class ExchangeRateQaActivity : PrivacyActivity() {
    var lightMode by mutableStateOf(true)
    private val database by lazy { Room.inMemoryDatabaseBuilder(applicationContext, FinexyDatabase::class.java).allowMainThreadQueries().build() }
    private val repository by lazy { TransactionRepository(applicationContext, database, importLegacy = false) }
    override fun privacyStore() = SecureStore(applicationContext, "exchange-rate-ui-fixture")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kotlinx.coroutines.runBlocking {
            val now = System.currentTimeMillis()
            database.dao().upsertExchangeRates(listOf(
                ExchangeRateEntity("CNY", "1", "CNY", "user_custom", "", now / 1000, now),
                ExchangeRateEntity("EUR", "0.1184", "CNY", "user_custom", "", now / 1000, now),
                ExchangeRateEntity("JPY", "20.12345678", "CNY", "user_custom", "", now / 1000, now),
                ExchangeRateEntity("USD", "0.13876543", "CNY", "user_custom", "", now / 1000, now)
            ))
        }
        setContent { FinexyTheme(lightMode) { ExchangeRatesPage(privacyStore(), repository, localMode = false, onBack = {}) } }
    }
}
