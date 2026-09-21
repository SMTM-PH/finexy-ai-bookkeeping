package com.finexy.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DockerUserPreferencesE2ETest {
    @Test fun profileCloudMergeSecondDeviceAndDisableLifecycle() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("finexy.e2e.url")
        assumeTrue("Use only an isolated disposable Docker server", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstNamespace = "preference-e2e-${UUID.randomUUID()}"
        val secondNamespace = "$firstNamespace-second"
        val firstStore = SecureStore(context, firstNamespace)
        val secondStore = SecureStore(context, secondNamespace)
        val username = "pref" + UUID.randomUUID().toString().replace("-", "").take(16)
        val password = UUID.randomUUID().toString()
        try {
            firstStore.put(FinexyApi.KEY_SERVER_URL, url!!, durable = true)
            secondStore.put(FinexyApi.KEY_SERVER_URL, url, durable = true)
            AccountSecurity(FinexyApi(firstStore)).register(username, "$username@example.com", "Preference Test", password, "CNY")
            val firstLogin = FinexyApi(firstStore).login(username, password)
            firstStore.put(FinexyApi.KEY_TOKEN, firstLogin.token, durable = true)
            val firstApi = FinexyApi(firstStore)
            assertTrue(firstApi.applicationCloudSettings().isEmpty())

            firstApi.updateApplicationCloudSettings(mapOf(
                "showAccountBalance" to "false",
                "showAmountInHomePage" to "false",
                "chartColors" to "f05537,62d3a0",
                "statistics.defaultAccountFilter" to "{\"101\":true}"
            ))
            firstApi.updateApplicationCloudSettings(mapOf("showAmountInHomePage" to "true"))
            val firstCloud = firstApi.applicationCloudSettings()
            assertEquals("false", firstCloud["showAccountBalance"])
            assertEquals("true", firstCloud["showAmountInHomePage"])
            assertEquals("f05537,62d3a0", firstCloud["chartColors"])
            assertFalse(firstCloud.keys.any { it in CloudPreferenceRegistry.privateLocalKeys })

            val secondLogin = FinexyApi(secondStore).login(username, password)
            secondStore.put(FinexyApi.KEY_TOKEN, secondLogin.token, durable = true)
            val secondPreferences = UserPreferences().withCloud(FinexyApi(secondStore).applicationCloudSettings())
            assertFalse(secondPreferences.showAccountBalance)
            assertTrue(secondPreferences.showAmountInHomePage)
            assertFalse(secondPreferences.aiTextConsent)

            val account = firstApi.createAccount(AccountDraft("Default Wallet", 1, "USD", 0)).single()
            val updatedProfile = AccountSecurity(firstApi).updateProfile(JSONObject()
                .put("defaultAccountId", account.id.toString()).put("defaultCurrency", "USD"))
            assertEquals(account.id.toString(), updatedProfile.getString("defaultAccountId"))
            assertEquals("USD", updatedProfile.getString("defaultCurrency"))

            firstApi.disableApplicationCloudSettings()
            assertTrue(FinexyApi(secondStore).applicationCloudSettings().isEmpty())
        } finally {
            context.deleteSharedPreferences(firstNamespace)
            context.deleteSharedPreferences(secondNamespace)
        }
    }
}
