package com.finexy.mobile.data

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerScopeTest {
    private fun token(user: String, revision: Int = 1): String = "header." + Base64.encodeToString(
        "{\"jti\":\"$user\",\"revision\":$revision}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP
    ) + ".signature"

    @Test fun usersAndServersAreSeparatedButTokenRefreshKeepsTheLedger() {
        val first = LedgerScope.identity("https://example.com", token("100"), false)
        assertEquals(first, LedgerScope.identity("https://example.com/", token("100", 2), false))
        assertNotEquals(first, LedgerScope.identity("https://example.com", token("101"), false))
        assertNotEquals(first, LedgerScope.identity("https://other.example.com", token("100"), false))
        assertNotEquals(first, LedgerScope.identity("https://example.com", token("100"), true))
        assertNotEquals(LedgerScope.identity("https://example.com", "opaque-1", false),
            LedgerScope.identity("https://example.com", "opaque-2", false))
    }

    @Test fun legacyDatabaseIsBoundOnceAndCannotBeReassignedByAnotherLogin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "scope-test-${java.util.UUID.randomUUID()}"
        try {
            val store = SecureStore(context, name)
            store.put(FinexyApi.KEY_SERVER_URL, "https://example.com")
            store.put(FinexyApi.KEY_TOKEN, token("100"))
            LedgerScope.initializeLegacyOwner(store)
            val original = LedgerScope.identity("https://example.com", token("100"), false)
            assertEquals("finexy.db", LedgerScope.databaseName(store, original))
            store.put(FinexyApi.KEY_TOKEN, token("200"))
            LedgerScope.initializeLegacyOwner(store)
            val newUser = LedgerScope.identity("https://example.com", token("200"), false)
            assertNotEquals("finexy.db", LedgerScope.databaseName(store, newUser))
            assertEquals("finexy.db", LedgerScope.databaseName(store, original))
        } finally { context.deleteSharedPreferences(name) }
    }
}
