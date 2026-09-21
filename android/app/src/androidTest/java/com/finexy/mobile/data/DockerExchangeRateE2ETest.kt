package com.finexy.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DockerExchangeRateE2ETest {
    @Test fun updatePullModifySyncAndDeleteLifecycle() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Use only an isolated disposable Docker server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "rate-e2e-${UUID.randomUUID()}"
        val store = SecureStore(context, namespace)
        val username = "rate" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = UUID.randomUUID().toString()
        val database = Room.inMemoryDatabaseBuilder(context, FinexyDatabase::class.java).build()
        try {
            store.put(FinexyApi.KEY_SERVER_URL, url!!, durable = true)
            AccountSecurity(FinexyApi(store)).register(username, "$username@example.com", "Rate Test", password, "CNY")
            store.put(FinexyApi.KEY_TOKEN, FinexyApi(store).login(username, password).token, durable = true)
            val api = FinexyApi(store)
            val repository = TransactionRepository(context, database, importLegacy = false)

            val initial = api.latestExchangeRates()
            assertEquals(ExchangeRateEntityData.USER_CUSTOM_SOURCE, initial.dataSource)
            assertEquals(listOf("CNY"), initial.exchangeRates.map { it.currency })

            assertEquals("0.13876543", api.updateUserCustomExchangeRate("USD", "0.13876543").rate)
            api.updateUserCustomExchangeRate("JPY", "20.12345678")
            val pulled = api.latestExchangeRates()
            assertEquals(listOf("CNY", "JPY", "USD"), pulled.exchangeRates.map { it.currency })
            repository.replaceExchangeRates(pulled)
            assertEquals(13_877L, convertMinorAmount(100_000, "CNY", "USD", repository.observeExchangeRates().first()))

            assertEquals("0.14", api.updateUserCustomExchangeRate("USD", "0.14").rate)
            SyncEngine(api, repository).sync()
            assertEquals("0.14", repository.observeExchangeRates().first().first { it.currency == "USD" }.rate)

            api.deleteUserCustomExchangeRate("JPY")
            repository.replaceExchangeRates(api.latestExchangeRates())
            assertFalse(repository.observeExchangeRates().first().any { it.currency == "JPY" })
            api.deleteUserCustomExchangeRate("USD")
            assertEquals(listOf("CNY"), api.latestExchangeRates().exchangeRates.map { it.currency })
        } finally {
            database.close()
            context.deleteSharedPreferences(namespace)
        }
    }
}
