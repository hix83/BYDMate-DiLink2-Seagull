package com.bydmate.app.service

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val checker = UpdateChecker(OkHttpClient())

    @Test fun `semantic version comparison accepts next patch`() {
        assertTrue(checker.isNewer("3.8.5", "3.8.4"))
        assertFalse(checker.isNewer("3.8.4", "3.8.5"))
        assertFalse(checker.isNewer("3.8.5", "3.8.5"))
    }

    @Test fun `field revision is newer than its base release`() {
        assertTrue(checker.isNewer("3.8.5-1", "3.8.5"))
        assertTrue(checker.isNewer("3.8.5-2", "3.8.5-1"))
        assertFalse(checker.isNewer("3.8.5", "3.8.5-1"))
    }

    @Test fun `physical DiLink artifact wins for this update channel`() {
        val assets = JSONArray()
            .put(asset("BYDMate-v3.8.5-physical-debug.apk", "https://example/debug.apk"))
            .put(asset("BYDMate-v3.8.5.apk", "https://example/release.apk"))
        assertEquals("https://example/debug.apk", checker.selectReleaseApk("3.8.5", assets))
    }

    @Test fun `physical-only release is accepted`() {
        val assets = JSONArray().put(asset("BYDMate-v3.8.5-physical-debug.apk", "https://example/debug.apk"))
        assertEquals("https://example/debug.apk", checker.selectReleaseApk("3.8.5", assets))
    }

    private fun asset(name: String, url: String) =
        JSONObject().put("name", name).put("browser_download_url", url)
}
