package com.finexy.mobile.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPreferencesContractTest {
    @Test fun registryRejectsPrivateUnknownAndMalformedValues() {
        assertTrue(CloudPreferenceRegistry.privateLocalKeys.none { it in CloudPreferenceRegistry.types })
        assertFalse(CloudPreferenceRegistry.isValid(FinexyApi.KEY_TOKEN, "secret"))
        assertFalse(CloudPreferenceRegistry.isValid("app_pin", "1234"))
        assertFalse(CloudPreferenceRegistry.isValid("showAccountBalance", "yes"))
        assertFalse(CloudPreferenceRegistry.isValid("statistics.defaultAccountFilter", "[]"))
        assertTrue(CloudPreferenceRegistry.isValid("showAccountBalance", "false"))
        assertTrue(CloudPreferenceRegistry.isValid("statistics.defaultAccountFilter", "{\"101\":true}"))
    }

    @Test fun invalidLocalValuesFallBackAndValidValuesRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secure = SecureStore(context, "d8-preferences-${System.nanoTime()}")
        val store = UserPreferenceStore(secure, "ledger-a")
        val expected = UserPreferences(
            theme = ThemePreference.DARK,
            textSize = TextSizePreference.LARGE,
            startup = StartupPreference.ACCOUNTS,
            syncPolicy = SyncPolicy.MANUAL,
            chartColors = "f05537,62d3a0,668cff",
            statisticsAccountFilter = mapOf("101" to true, "102" to false),
            aiTextConsent = true,
            aiStructuredConsent = true,
            cloudSyncEnabled = true
        )
        store.save(expected)
        assertEquals(expected, store.load())
        assertFalse(expected.cloudValues().keys.any { it in CloudPreferenceRegistry.privateLocalKeys })
    }

    @Test fun cloudMergeUsesValidKnownValuesOnly() {
        val merged = UserPreferences().withCloud(mapOf(
            "showAccountBalance" to "false",
            "showAmountInHomePage" to "broken",
            "chartColors" to "f05537,62d3a0",
            "unknown" to "true",
            "aiTextConsent" to "true"
        ))
        assertFalse(merged.showAccountBalance)
        assertTrue(merged.showAmountInHomePage)
        assertEquals("f05537,62d3a0", merged.chartColors)
        assertFalse(merged.aiTextConsent)
    }

    @Test fun malformedCloudChartColorsFallBackToTheLocalPalette() {
        val local = UserPreferences(chartColors = "f05537,62d3a0")

        assertEquals(
            local.chartColors,
            local.withCloud(mapOf("chartColors" to "red,not-a-color")).chartColors
        )
    }

    @Test fun ledgerScopedPrivacyPreferencesDoNotLeakBetweenUsers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secure = SecureStore(context, "d8-scope-${System.nanoTime()}")
        val firstLedger = UserPreferenceStore(secure, "ledger-a")
        val secondLedger = UserPreferenceStore(secure, "ledger-b")

        firstLedger.save(
            UserPreferences(
                aiTextConsent = true,
                aiStructuredConsent = true,
                cloudSyncEnabled = true
            )
        )

        assertTrue(firstLedger.load().aiTextConsent)
        assertTrue(firstLedger.load().aiStructuredConsent)
        assertFalse(secondLedger.load().aiTextConsent)
        assertFalse(secondLedger.load().aiStructuredConsent)
        assertFalse(secondLedger.load().cloudSyncEnabled)
    }
}
