package com.finexy.mobile.data

import org.json.JSONArray
import org.json.JSONObject

internal enum class PreferenceValueType { STRING, NUMBER, BOOLEAN, STRING_BOOLEAN_MAP }

/**
 * Explicit allow-list shared by the Android cloud-preference parser and writer.
 * Device secrets and privacy grants are deliberately absent and can never be uploaded.
 */
internal object CloudPreferenceRegistry {
    private val booleanKeys = setOf(
        "showAccountBalance", "autoUpdateExchangeRatesData", "showAddTransactionButtonInDesktopNavbar",
        "showAmountInHomePage", "showTotalAmountInTransactionListPage", "showTagInTransactionListPage",
        "autoGetCurrentGeoLocation", "alwaysShowTransactionPicturesInMobileTransactionEditPage",
        "alwaysRequireConfirmationOfClipboardContentBeforeSubmission", "autoUploadTransactionPictureForAIRecognition",
        "rememberLastSelectedFileTypeInImportTransactionDialog", "showTagInInsightsExplorerPage",
        "hideCategoriesWithoutAccounts"
    )
    private val numberKeys = setOf(
        "timezoneUsedForStatisticsInHomePage", "itemsCountInTransactionListPage",
        "defaultKeywordMatchModeInTransactionListPage", "quickSaveButtonStyleInMobileTransactionListPage",
        "quickAddButtonActionInMobileTransactionEditPage", "transactionPictureQuality",
        "insightsExplorerDefaultDateRangeType", "reconciliationStatementButtonDefaultDateRangeTypeInDesktop",
        "reconciliationStatementPageDefaultDateRangeTypeInMobile", "currencySortByInExchangeRatesPage",
        "mapCacheExpiration", "exchangeRatesDataCacheExpiration", "statistics.defaultChartDataType",
        "statistics.defaultTimezoneType", "statistics.defaultKeywordMatchMode", "statistics.defaultSortingType",
        "statistics.defaultCategoricalChartType", "statistics.defaultCategoricalChartDataRangeType",
        "statistics.defaultTrendChartType", "statistics.defaultTrendChartDataRangeType",
        "statistics.defaultAssetTrendsChartType", "statistics.defaultAssetTrendsChartDataRangeType"
    )
    private val mapKeys = setOf(
        "overviewAccountFilterInHomePage", "overviewTransactionCategoryFilterInHomePage",
        "totalAmountExcludeAccountIds", "statistics.defaultAccountFilter",
        "statistics.defaultTransactionCategoryFilter"
    )
    private val stringKeys = setOf(
        "chartColors", "autoSaveTransactionDraft", "lastSelectedFileTypeInImportTransactionDialog",
        "accountCategoryOrders"
    )

    val types: Map<String, PreferenceValueType> = buildMap {
        booleanKeys.forEach { put(it, PreferenceValueType.BOOLEAN) }
        numberKeys.forEach { put(it, PreferenceValueType.NUMBER) }
        mapKeys.forEach { put(it, PreferenceValueType.STRING_BOOLEAN_MAP) }
        stringKeys.forEach { put(it, PreferenceValueType.STRING) }
    }

    val privateLocalKeys = setOf(
        "theme", "fontScale", "startupTab", "syncPolicy", "aiTextConsent",
        "aiStructuredConsent", FinexyApi.KEY_TOKEN, "app_pin", "biometric_enabled"
    )

    fun isValid(key: String, value: String): Boolean = when (types[key]) {
        PreferenceValueType.STRING -> true
        PreferenceValueType.NUMBER -> value.toDoubleOrNull()?.isFinite() == true
        PreferenceValueType.BOOLEAN -> value == "true" || value == "false"
        PreferenceValueType.STRING_BOOLEAN_MAP -> runCatching {
            val json = JSONObject(value)
            json.keys().asSequence().all { json.opt(it) is Boolean }
        }.getOrDefault(false)
        null -> false
    }

    fun sanitize(values: Map<String, String>): Map<String, String> =
        values.filter { (key, value) -> key in types && isValid(key, value) }.toSortedMap()
}

internal enum class ThemePreference { SYSTEM, LIGHT, DARK }
internal enum class TextSizePreference(val scale: Float) { COMPACT(0.9f), STANDARD(1f), LARGE(1.15f) }
internal enum class StartupPreference(val tab: Int) { HOME(0), ACTIVITY(1), ENTRY(2), ACCOUNTS(3), SETTINGS(4) }
internal enum class SyncPolicy { AUTOMATIC, MANUAL }

internal data class UserPreferences(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val textSize: TextSizePreference = TextSizePreference.STANDARD,
    val startup: StartupPreference = StartupPreference.HOME,
    val syncPolicy: SyncPolicy = SyncPolicy.AUTOMATIC,
    val showAccountBalance: Boolean = true,
    val showAmountInHomePage: Boolean = true,
    val autoUpdateExchangeRatesData: Boolean = true,
    val chartColors: String = "",
    val statisticsAccountFilter: Map<String, Boolean> = emptyMap(),
    val statisticsCategoryFilter: Map<String, Boolean> = emptyMap(),
    val aiTextConsent: Boolean = false,
    val aiStructuredConsent: Boolean = false,
    val cloudSyncEnabled: Boolean = false
) {
    fun cloudValues(): Map<String, String> = mapOf(
        "showAccountBalance" to showAccountBalance.toString(),
        "showAmountInHomePage" to showAmountInHomePage.toString(),
        "autoUpdateExchangeRatesData" to autoUpdateExchangeRatesData.toString(),
        "chartColors" to chartColors,
        "statistics.defaultAccountFilter" to JSONObject(statisticsAccountFilter).toString(),
        "statistics.defaultTransactionCategoryFilter" to JSONObject(statisticsCategoryFilter).toString()
    )

    fun withCloud(values: Map<String, String>): UserPreferences {
        val safe = CloudPreferenceRegistry.sanitize(values)
        return copy(
            showAccountBalance = safe["showAccountBalance"]?.toBooleanStrictOrNull() ?: showAccountBalance,
            showAmountInHomePage = safe["showAmountInHomePage"]?.toBooleanStrictOrNull() ?: showAmountInHomePage,
            autoUpdateExchangeRatesData = safe["autoUpdateExchangeRatesData"]?.toBooleanStrictOrNull() ?: autoUpdateExchangeRatesData,
            chartColors = safe["chartColors"]?.takeIf(::validChartColors) ?: chartColors,
            statisticsAccountFilter = safe["statistics.defaultAccountFilter"]?.let(::parseBooleanMap) ?: statisticsAccountFilter,
            statisticsCategoryFilter = safe["statistics.defaultTransactionCategoryFilter"]?.let(::parseBooleanMap) ?: statisticsCategoryFilter
        )
    }
}

internal class UserPreferenceStore(private val store: SecureStore, private val ledgerScope: String) {
    private fun key(name: String) = "preferences:$ledgerScope:$name"

    fun load(): UserPreferences {
        val legacyLight = store.get("light_theme")?.toBooleanStrictOrNull()
        val theme = enumValue(store.get(DEVICE_THEME), legacyLight?.let { if (it) ThemePreference.LIGHT else ThemePreference.DARK } ?: ThemePreference.SYSTEM)
        return UserPreferences(
            theme = theme,
            textSize = enumValue(store.get(DEVICE_TEXT_SIZE), TextSizePreference.STANDARD),
            startup = enumValue(store.get(DEVICE_STARTUP), StartupPreference.HOME),
            syncPolicy = enumValue(store.get(key("syncPolicy")), SyncPolicy.AUTOMATIC),
            showAccountBalance = boolean("showAccountBalance", true),
            showAmountInHomePage = boolean("showAmountInHomePage", true),
            autoUpdateExchangeRatesData = boolean("autoUpdateExchangeRatesData", true),
            chartColors = store.get(key("chartColors")).orEmpty().takeIf(::validChartColors).orEmpty(),
            statisticsAccountFilter = parseBooleanMap(store.get(key("statistics.defaultAccountFilter")).orEmpty()),
            statisticsCategoryFilter = parseBooleanMap(store.get(key("statistics.defaultTransactionCategoryFilter")).orEmpty()),
            aiTextConsent = boolean("aiTextConsent", false),
            aiStructuredConsent = boolean("aiStructuredConsent", false),
            cloudSyncEnabled = boolean("cloudSyncEnabled", false)
        )
    }

    fun save(value: UserPreferences) {
        store.put(DEVICE_THEME, value.theme.name)
        store.put(DEVICE_TEXT_SIZE, value.textSize.name)
        store.put(DEVICE_STARTUP, value.startup.name)
        store.put(key("syncPolicy"), value.syncPolicy.name)
        store.put(key("showAccountBalance"), value.showAccountBalance.toString())
        store.put(key("showAmountInHomePage"), value.showAmountInHomePage.toString())
        store.put(key("autoUpdateExchangeRatesData"), value.autoUpdateExchangeRatesData.toString())
        store.put(key("chartColors"), value.chartColors.takeIf(::validChartColors).orEmpty())
        store.put(key("statistics.defaultAccountFilter"), JSONObject(value.statisticsAccountFilter).toString())
        store.put(key("statistics.defaultTransactionCategoryFilter"), JSONObject(value.statisticsCategoryFilter).toString())
        store.put(key("aiTextConsent"), value.aiTextConsent.toString())
        store.put(key("aiStructuredConsent"), value.aiStructuredConsent.toString())
        store.put(key("cloudSyncEnabled"), value.cloudSyncEnabled.toString())
    }

    private fun boolean(name: String, default: Boolean) = store.get(key(name))?.toBooleanStrictOrNull() ?: default

    companion object {
        private const val DEVICE_THEME = "preferences:device:theme"
        private const val DEVICE_TEXT_SIZE = "preferences:device:textSize"
        private const val DEVICE_STARTUP = "preferences:device:startupTab"
    }
}

internal suspend fun FinexyApi.applicationCloudSettings(): Map<String, String> {
    val result = JSONObject(request("v1/users/settings/cloud/get.json")).opt("result")
    if (result !is JSONArray) return emptyMap()
    val values = LinkedHashMap<String, String>()
    for (index in 0 until result.length()) {
        val item = result.optJSONObject(index) ?: continue
        val key = item.optString("settingKey")
        val value = item.optString("settingValue")
        if (key !in values && CloudPreferenceRegistry.isValid(key, value)) values[key] = value
    }
    return values
}

internal suspend fun FinexyApi.updateApplicationCloudSettings(changes: Map<String, String>) {
    val current = CloudPreferenceRegistry.sanitize(applicationCloudSettings())
    val merged = CloudPreferenceRegistry.sanitize(current + changes)
    if (merged == current) return
    val settings = JSONArray().apply {
        merged.forEach { (key, value) -> put(JSONObject().put("settingKey", key).put("settingValue", value)) }
    }
    request("v1/users/settings/cloud/update.json", JSONObject().put("settings", settings).put("fullUpdate", true).toString())
}

internal suspend fun FinexyApi.disableApplicationCloudSettings() {
    request("v1/users/settings/cloud/disable.json", "{}")
}

private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
    raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback

private fun parseBooleanMap(raw: String): Map<String, Boolean> = runCatching {
    val json = JSONObject(raw)
    json.keys().asSequence().associateWith { key -> json.getBoolean(key) }
}.getOrDefault(emptyMap())

private fun validChartColors(value: String): Boolean = value.isBlank() || value.split(',').let { colors ->
    colors.size <= 12 && colors.all { Regex("[0-9a-fA-F]{6}").matches(it.trim().removePrefix("#")) }
}
