package com.finexy.mobile.data

import com.finexy.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val version: String,
    val title: String,
    val releaseUrl: String,
    val apkUrl: String,
    val publishedAt: String = ""
)

/** Looks for a newer Android APK in public releases; other product releases do not trigger an app prompt. */
object AppUpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/SMTM-PH/finexy-ai-bookkeeping/releases?per_page=30"
    private const val RELEASES_PAGE = "https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases"
    private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    private const val KEY_LAST_CHECKED_AT = "app_update_last_checked_at"
    private const val KEY_LATEST_ANDROID_RELEASE = "app_update_latest_android_release"
    private const val KEY_DISMISSED_VERSION = "app_update_dismissed_version"
    private val apkName = Regex("^Finexy-Android-v?(\\d+\\.\\d+\\.\\d+)(?:-(?:debug|release))?\\.apk$", RegexOption.IGNORE_CASE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class FetchResult(val release: AppUpdateInfo?)

    suspend fun check(store: SecureStore, now: Long = System.currentTimeMillis()): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val cached = store.get(KEY_LATEST_ANDROID_RELEASE)?.let { runCatching { parseCached(it) }.getOrNull() }
        val lastChecked = store.get(KEY_LAST_CHECKED_AT)?.toLongOrNull() ?: 0L
        val latest = if (now - lastChecked in 0 until CHECK_INTERVAL_MILLIS) cached else {
            val fetched = fetchLatest()
            if (fetched == null) cached else {
                runCatching {
                    if (fetched.release == null) store.remove(KEY_LATEST_ANDROID_RELEASE)
                    else store.put(KEY_LATEST_ANDROID_RELEASE, fetched.release.toJson().toString(), durable = true)
                    store.put(KEY_LAST_CHECKED_AT, now.toString(), durable = true)
                }
                fetched.release
            }
        }
        latest?.takeIf {
            compareVersions(it.version, BuildConfig.VERSION_NAME) > 0 &&
                normalizeVersion(store.get(KEY_DISMISSED_VERSION).orEmpty()) != normalizeVersion(it.version)
        }
    }

    fun dismiss(store: SecureStore, version: String) {
        store.put(KEY_DISMISSED_VERSION, normalizeVersion(version), durable = true)
    }

    private fun fetchLatest(): FetchResult? =
        runCatching {
            val request = Request.Builder().url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Finexy-Android/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                FetchResult(parseLatestAndroidRelease(response.body?.string().orEmpty()))
            }
        }.getOrNull()

    internal fun parseAvailableRelease(raw: String, currentVersion: String, dismissedVersion: String? = null): AppUpdateInfo? {
        val release = parseLatestAndroidRelease(raw) ?: return null
        if (compareVersions(release.version, currentVersion) <= 0) return null
        if (normalizeVersion(release.version) == normalizeVersion(dismissedVersion.orEmpty())) return null
        return release
    }

    internal fun parseLatestAndroidRelease(raw: String): AppUpdateInfo? {
        val releases = JSONArray(raw)
        var latest: AppUpdateInfo? = null
        for (index in 0 until releases.length()) {
            val data = releases.optJSONObject(index) ?: continue
            if (data.optBoolean("draft") || data.optBoolean("prerelease")) continue
            val releaseUrl = data.optString("html_url").takeIf(::isTrustedReleaseUrl) ?: RELEASES_PAGE
            val assets = data.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val version = apkName.matchEntire(asset.optString("name"))?.groupValues?.get(1) ?: continue
                val apkUrl = asset.optString("browser_download_url").takeIf(::isTrustedReleaseUrl) ?: continue
                if (latest == null || compareVersions(version, latest.version) > 0) {
                    latest = AppUpdateInfo(
                        version = version,
                        title = data.optString("name").trim().ifBlank { "Finexy Android $version" }.take(100),
                        releaseUrl = releaseUrl,
                        apkUrl = apkUrl,
                        publishedAt = data.optString("published_at").take(40)
                    )
                }
            }
        }
        return latest
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = parseVersion(left) ?: return 0
        val b = parseVersion(right) ?: return 0
        for (index in 0..2) {
            if (a[index] != b[index]) return a[index].compareTo(b[index])
        }
        return 0
    }

    private fun parseVersion(raw: String): List<Int>? {
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(normalizeVersion(raw)) ?: return null
        return (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
    }

    private fun normalizeVersion(raw: String): String = raw.trim().removePrefix("v").removePrefix("V")

    private fun isTrustedReleaseUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let { url ->
        url.isHttps && url.host == "github.com"
    } == true

    private fun parseCached(raw: String): AppUpdateInfo? {
        val data = JSONObject(raw)
        val version = data.optString("version")
        val releaseUrl = data.optString("releaseUrl")
        val apkUrl = data.optString("apkUrl")
        if (parseVersion(version) == null || !isTrustedReleaseUrl(releaseUrl) || !isTrustedReleaseUrl(apkUrl)) return null
        return AppUpdateInfo(version, data.optString("title"), releaseUrl, apkUrl, data.optString("publishedAt"))
    }

    private fun AppUpdateInfo.toJson() = JSONObject()
        .put("version", version)
        .put("title", title)
        .put("releaseUrl", releaseUrl)
        .put("apkUrl", apkUrl)
        .put("publishedAt", publishedAt)
}
