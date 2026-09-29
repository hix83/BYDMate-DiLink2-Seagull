package com.bydmate.app.service

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val checker = UpdateChecker(OkHttpClient())

    @Test fun `semantic version comparison accepts next patch`() {
        assertTrue(checker.isNewer("3.8.5", "3.8.4"))
        assertFalse(checker.isNewer("3.8.4", "3.8.5"))
        assertFalse(checker.isNewer("3.8.5", "3.8.5"))
    }

    @Test fun `exact release apk wins over debug artifact`() {
        val assets = JSONArray()
            .put(asset("BYDMate-v3.8.5-physical-debug.apk", "https://example/debug.apk"))
            .put(asset("BYDMate-v3.8.5.apk", "https://example/release.apk"))
        assertEquals("https://example/release.apk", checker.selectReleaseApk("3.8.5", assets))
    }

    @Test fun `debug-only release is rejected`() {
        val assets = JSONArray().put(asset("BYDMate-v3.8.5-physical-debug.apk", "https://example/debug.apk"))
        assertNull(checker.selectReleaseApk("3.8.5", assets))
    }

    private fun asset(name: String, url: String) =
        JSONObject().put("name", name).put("browser_download_url", url)
}
