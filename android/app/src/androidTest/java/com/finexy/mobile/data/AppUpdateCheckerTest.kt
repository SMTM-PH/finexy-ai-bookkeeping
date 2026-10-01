package com.finexy.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    private fun release(
        tag: String = "v1.10.0",
        androidVersion: String? = "1.0.2",
        draft: Boolean = false,
        prerelease: Boolean = false,
        releaseUrl: String = "https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases/tag/v1.10.0"
    ) = JSONObject()
        .put("tag_name", tag)
        .put("name", "Finexy $tag")
        .put("html_url", releaseUrl)
        .put("published_at", "2026-09-26T08:00:00Z")
        .put("draft", draft)
        .put("prerelease", prerelease)
        .put("assets", JSONArray().also { assets ->
            assets.put(JSONObject().put("name", "Finexy-NAS-1.10.0-amd64-deploy.zip"))
            androidVersion?.let { version ->
                assets.put(JSONObject()
                    .put("name", "Finexy-Android-$version-debug.apk")
                    .put("browser_download_url", "https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases/download/$tag/Finexy-Android-$version-debug.apk"))
            }
        })

    private fun feed(vararg releases: JSONObject) = JSONArray().also { list -> releases.forEach(list::put) }.toString()

    @Test fun newerAndroidApkIsParsedWithTrustedLinks() {
        val update = AppUpdateChecker.parseAvailableRelease(feed(release()), "1.0.1")
        requireNotNull(update)
        assertEquals("1.0.2", update.version)
        assertTrue(update.releaseUrl.startsWith("https://github.com/"))
        assertTrue(update.apkUrl.endsWith(".apk"))
    }

    @Test fun serverOnlyReleaseAndUnchangedAndroidApkDoNotPrompt() {
        assertNull(AppUpdateChecker.parseAvailableRelease(feed(release(androidVersion = null)), "1.0.1"))
        assertNull(AppUpdateChecker.parseAvailableRelease(feed(release(tag = "v1.11.0", androidVersion = "1.0.1")), "1.0.1"))
        assertNull(AppUpdateChecker.parseAvailableRelease(feed(release(androidVersion = "1.0.0")), "1.0.1"))
    }

    @Test fun draftAndPrereleaseDoNotPrompt() {
        assertNull(AppUpdateChecker.parseAvailableRelease(feed(release(draft = true)), "1.0.1"))
        assertNull(AppUpdateChecker.parseAvailableRelease(feed(release(prerelease = true)), "1.0.1"))
    }

    @Test fun highestAndroidVersionWinsEvenWhenLatestProductReleaseHasNoApk() {
        val update = AppUpdateChecker.parseLatestAndroidRelease(feed(
            release(tag = "v1.12.0", androidVersion = null),
            release(tag = "v1.11.0", androidVersion = "1.0.3"),
            release(tag = "v1.10.0", androidVersion = "1.0.2")
        ))
        assertEquals("1.0.3", update?.version)
    }

    @Test fun dismissOnlySuppressesTheExactAndroidVersion() {
        assertNull(AppUpdateChecker.parseAvailableRelease(feed(release()), "1.0.1", "v1.0.2"))
        assertEquals("1.0.3", AppUpdateChecker.parseAvailableRelease(feed(release(androidVersion = "1.0.3")), "1.0.1", "1.0.2")?.version)
    }

    @Test fun semanticVersionsCompareNumerically() {
        assertTrue(AppUpdateChecker.compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(AppUpdateChecker.compareVersions("2.0.0", "10.0.0") < 0)
        assertEquals(0, AppUpdateChecker.compareVersions("v1.0.1", "1.0.1"))
    }

    @Test fun untrustedReleaseLinkFallsBackToProjectReleasesPage() {
        val update = AppUpdateChecker.parseAvailableRelease(feed(release(releaseUrl = "http://example.com/update")), "1.0.1")
        assertEquals("https://github.com/SMTM-PH/finexy-ai-bookkeeping/releases", update?.releaseUrl)
    }

    @Test fun untrustedApkAssetIsIgnored() {
        val data = release()
        data.getJSONArray("assets").getJSONObject(1).put("browser_download_url", "https://example.com/finexy.apk")
        assertNull(AppUpdateChecker.parseAvailableRelease(feed(data), "1.0.1"))
    }
}
